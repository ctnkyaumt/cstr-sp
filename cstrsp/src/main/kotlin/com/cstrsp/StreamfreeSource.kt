package com.cstrsp

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

data class StreamfreeStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("stream_key") val streamKey: String? = null,
    @JsonProperty("match_timestamp") val matchTimestamp: Long? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null,
    @JsonProperty("sources") val sources: List<String>? = null,
    @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null
) {
    fun allEmbeds(): List<String> {
        val list = mutableListOf<String>()
        sources?.forEach { s -> if (s.isNotBlank()) list.add(s) }
        if (!embedUrl.isNullOrBlank() && embedUrl !in list) list.add(embedUrl)
        return list
    }
}

data class StreamfreeResponse(
    @JsonProperty("count") val count: Int? = null,
    @JsonProperty("streams") val streams: List<StreamfreeStream>? = null
)

data class StreamfreeToken(
    @JsonProperty("_t") val t: String? = null,
    @JsonProperty("_e") val e: Long? = null,
    @JsonProperty("_n") val n: String? = null
)

object StreamfreeSource {
    private const val streamfreeUrl = "https://streamfree.top"
    private val regex0x = Regex("""const\s+_0x\s*=\s*(\{.*?\});""")
    private val regexSourceSuffix = Regex("""const\s+SOURCE_SUFFIX\s*=\s*['"]([^'"]*)['"]""")
    private val regexVariant = Regex("""const\s+VARIANT\s*=\s*['"]([^'"]+)['"]\s*\+\s*QUALITY""")

    suspend fun fetchStreamfreeStreams(): List<StreamfreeStream> = CstrspCache.cached("streamfree") {
        app.get("$streamfreeUrl/api/v1/streams").parsedSafe<StreamfreeResponse>()?.streams
            ?.filter { !it.name.isNullOrBlank() && it.allEmbeds().isNotEmpty() }
    } ?: emptyList()

    fun streamfreeItem(api: MainAPI, stream: StreamfreeStream): SearchResponse =
        api.newLiveSearchResponse("${stream.name ?: "Live Event"} [StreamFree]", "https://streamfree.domains/${stream.streamKey ?: stream.name}") {
            this.posterUrl = stream.thumbnailUrl
        }

    suspend fun getHomeSections(api: MainAPI): List<HomePageList> =
        fetchStreamfreeStreams()
            .groupBy { it.category?.replaceFirstChar { c -> c.uppercase() } ?: "Live" }
            .map { (cat, streams) ->
                HomePageList("$cat [StreamFree]", streams.map { streamfreeItem(api, it) })
            }

    suspend fun search(api: MainAPI, matcher: QueryMatcher): List<SearchResponse> =
        fetchStreamfreeStreams()
            .filter { matcher.matches(it.name, it.category, it.league) }
            .map { streamfreeItem(api, it) }

    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (!url.startsWith("https://streamfree.domains/")) return null
        val key = url.substringAfterLast("/")
        val stream = fetchStreamfreeStreams().find { it.streamKey == key || it.name == key } ?: return null
        val title = stream.name ?: "Live Stream"
        return api.newLiveStreamLoadResponse(
            name = "$title [StreamFree]",
            url = url,
            dataUrl = stream.toJson()
        ) {
            this.posterUrl = stream.thumbnailUrl
            this.plot = title
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = try {
            AppUtils.parseJson<StreamfreeStream>(data)
        } catch (e: Exception) {
            return false
        }

        val embeds = stream.allEmbeds()
        if (embeds.isEmpty()) return false

        val category = stream.category ?: "live"
        val streamKey = stream.streamKey ?: ""
        val refererUrl = "$streamfreeUrl/$category/$streamKey"

        embeds.resolveConcurrently { embedUrl ->
            val embedUri = runCatching { java.net.URI(embedUrl) }.getOrNull()
            val embedOrigin = if (embedUri != null && embedUri.host != null) {
                "${embedUri.scheme ?: "https"}://${embedUri.authority}"
            } else {
                streamfreeUrl
            }

            val embedHtml = try {
                app.get(embedUrl, referer = refererUrl).text
            } catch (e: Exception) {
                null
            }

            var foundDirect = false
            if (embedHtml != null) {
                val m0x = regex0x.find(embedHtml)
                if (m0x != null) {
                    try {
                        val qMap = AppUtils.parseJson<Map<String, StreamfreeToken>>(m0x.groupValues[1])
                        if (!qMap.isNullOrEmpty()) {
                            val suffix = regexSourceSuffix.find(embedHtml)?.groupValues?.get(1) ?: ""
                            val variantKey = regexVariant.find(embedHtml)?.groupValues?.get(1)?.ifBlank { streamKey } ?: streamKey

                            for ((q, params) in qMap) {
                                val t = params.t ?: continue
                                val e = params.e ?: continue
                                val n = params.n ?: continue
                                val directUrl = "$embedOrigin/live/$variantKey$q$suffix/index.m3u8?_t=$t&_e=$e&_n=$n"
                                val quality = when (q.lowercase()) {
                                    "2160p", "4k" -> Qualities.P2160.value
                                    "1080p" -> Qualities.P1080.value
                                    "720p" -> Qualities.P720.value
                                    "540p" -> Qualities.P480.value
                                    else -> Qualities.Unknown.value
                                }
                                val labeled = withQualityLabel("StreamFree - $q", quality)
                                callback.invoke(
                                    ExtractorLink(
                                        source = "StreamFree",
                                        name = labeled,
                                        url = directUrl,
                                        referer = "$embedOrigin/",
                                        quality = quality,
                                        type = ExtractorLinkType.M3U8,
                                        headers = mapOf("Origin" to embedOrigin)
                                    )
                                )
                                foundDirect = true
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            if (!foundDirect) {
                loadEmbed(embedUrl, refererUrl, subtitleCallback) { link ->
                    callback(
                        ExtractorLink(
                            source = "StreamFree",
                            name = withQualityLabel("StreamFree - ${stream.name}", link.quality),
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            type = link.type,
                            headers = link.headers,
                            extractorData = link.extractorData
                        )
                    )
                }
            }
        }
        return true
    }
}

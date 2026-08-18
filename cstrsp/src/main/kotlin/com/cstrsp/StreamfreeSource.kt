package com.cstrsp

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
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
import com.lagradost.cloudstream3.utils.loadExtractor

data class StreamfreeStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("stream_key") val streamKey: String? = null,
    @JsonProperty("match_timestamp") val matchTimestamp: Long? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null,
    @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null
)

data class StreamfreeResponse(
    @JsonProperty("count") val count: Int? = null,
    @JsonProperty("streams") val streams: List<StreamfreeStream>? = null
)

object StreamfreeSource {
    private const val streamfreeUrl = "https://streamfree.top"

    suspend fun fetchStreamfreeStreams(): List<StreamfreeStream> = CstrspCache.cached("streamfree") {
        app.get("$streamfreeUrl/api/v1/streams").parsedSafe<StreamfreeResponse>()?.streams
            ?.filter { !it.name.isNullOrBlank() && !it.embedUrl.isNullOrBlank() }
    } ?: emptyList()

    fun streamfreeItem(stream: StreamfreeStream): SearchResponse =
        newLiveSearchResponse("${stream.name ?: "Live Event"} [StreamFree]", "https://streamfree.domains/${stream.streamKey ?: stream.name}") {
            this.posterUrl = stream.thumbnailUrl
        }

    suspend fun getHomeSections(): List<HomePageList> =
        fetchStreamfreeStreams()
            .groupBy { it.category?.replaceFirstChar { c -> c.uppercase() } ?: "Live" }
            .map { (cat, streams) ->
                HomePageList("$cat [StreamFree]", streams.map { streamfreeItem(it) })
            }

    suspend fun search(matcher: QueryMatcher): List<SearchResponse> =
        fetchStreamfreeStreams()
            .filter { matcher.matches(it.name, it.category, it.league) }
            .map { streamfreeItem(it) }

    suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("https://streamfree.domains/")) return null
        val key = url.substringAfterLast("/")
        val stream = fetchStreamfreeStreams().find { it.streamKey == key || it.name == key } ?: return null
        val title = stream.name ?: "Live Stream"
        return newLiveStreamLoadResponse(
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

        if (stream.embedUrl.isNullOrBlank()) return false

        val embedUrl = stream.embedUrl
        val refererUrl = "$streamfreeUrl/${stream.category ?: "live"}/${stream.streamKey ?: ""}"
        val embedHtml = try {
            app.get(embedUrl, referer = refererUrl).text
        } catch (e: Exception) { null }

        var foundDirect = false
        if (embedHtml != null) {
            val m0x = Regex("""const\s+_0x\s*=\s*(\{.*?\});""").find(embedHtml)
            if (m0x != null) {
                try {
                    val mapType = object : TypeReference<Map<String, Map<String, Any>>>() {}
                    val qMap = AppUtils.mapper.readValue<Map<String, Map<String, Any>>>(m0x.groupValues[1], mapType)
                    for ((q, params) in qMap) {
                        val t = params["_t"]?.toString() ?: continue
                        val e = params["_e"]?.toString() ?: continue
                        val n = params["_n"]?.toString() ?: continue
                        val directUrl = "$streamfreeUrl/live/${stream.streamKey}$q/index.m3u8?_t=$t&_e=$e&_n=$n"
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
                                referer = "$streamfreeUrl/",
                                quality = quality,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        foundDirect = true
                    }
                } catch (e: Exception) {}
            }
        }

        if (!foundDirect) {
            loadExtractor(encodeUrlNonAscii(embedUrl), refererUrl, subtitleCallback) { link ->
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
        return true
    }
}

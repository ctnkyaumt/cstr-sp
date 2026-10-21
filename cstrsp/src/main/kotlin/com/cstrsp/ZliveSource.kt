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
import java.net.URI

data class ZliveSourceItem(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("label") val label: String? = null
)

data class ZliveChannel(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("tagline") val tagline: String? = null,
    @JsonProperty("region") val region: String? = null,
    @JsonProperty("flag") val flag: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("live") val live: Boolean? = null,
    @JsonProperty("sport") val sport: String? = null,
    @JsonProperty("order") val order: Int? = null,
    @JsonProperty("sources") val sources: List<ZliveSourceItem>? = null
)

data class ZliveLoadData(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("quality") val quality: String? = null
)

object ZliveSource {
    private const val ZLIVE_BASE = "https://zlive.st"
    private const val ZLIVE_CHANNELS_URL = "https://iptv.zlive.st/channels.json"

    private val zliveHeaders = mapOf(
        "Referer" to "$ZLIVE_BASE/",
        "Origin" to ZLIVE_BASE
    )

    suspend fun fetchChannels(): List<ZliveChannel> = CstrspCache.cached("zlive-channels") {
        val res = app.get(ZLIVE_CHANNELS_URL, headers = zliveHeaders)
        res.parsedSafe<Array<ZliveChannel>>()?.toList()?.filter { it.id != null && it.live != false }
    } ?: emptyList()

    suspend fun getHomeSections(api: MainAPI): List<HomePageList> =
        fetchChannels()
            .groupBy { it.sport?.trim()?.ifEmpty { "Other" } ?: "Other" }
            .mapNotNull { (sport, channels) ->
                val items = channels.mapNotNull { ch ->
                    val id = ch.id ?: return@mapNotNull null
                    api.newLiveSearchResponse("${ch.name ?: "Channel"} [zlive]", "https://zlive.domains/$id")
                }
                if (items.isEmpty()) null
                else HomePageList("$sport [zlive]", items)
            }

    suspend fun search(api: MainAPI, matcher: QueryMatcher): List<SearchResponse> =
        fetchChannels()
            .filter { matcher.matches(it.name, it.sport, it.region, it.tagline) }
            .mapNotNull { ch ->
                val id = ch.id ?: return@mapNotNull null
                api.newLiveSearchResponse("${ch.name ?: "Channel"} [zlive]", "https://zlive.domains/$id")
            }

    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (!url.startsWith("https://zlive.domains/")) return null
        val id = url.substringAfterLast("/")
        val ch = fetchChannels().find { it.id == id } ?: return null
        val title = "${ch.name ?: "Channel"} [zlive]"
        return api.newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = ZliveLoadData(ch.id!!, ch.name ?: "Channel", ch.quality).toJson()
        ) {
            this.plot = ch.tagline?.ifBlank { title } ?: title
        }
    }

    private suspend fun isPlayableZliveLink(
        playlistUrl: String,
        headers: Map<String, String>?,
        referer: String?
    ): Boolean {
        return try {
            val reqHeaders = headers?.toMutableMap() ?: mutableMapOf()
            if (!referer.isNullOrEmpty() && reqHeaders.keys.none { it.equals("Referer", true) }) {
                reqHeaders["Referer"] = referer
            }

            val res = app.get(playlistUrl, headers = reqHeaders)
            val text = res.text
            if (text.isBlank()) return true

            var variantUrl = playlistUrl
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val streamInfIndex = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val mediaText = if (streamInfIndex != -1) {
                val variantLine = lines.subList(streamInfIndex + 1, lines.size).firstOrNull { !it.startsWith("#") }
                if (variantLine != null) {
                    variantUrl = URI(playlistUrl).resolve(variantLine).toString()
                    app.get(variantUrl, headers = reqHeaders).text
                } else {
                    text
                }
            } else {
                text
            }

            val mediaLines = mediaText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val segmentLine = mediaLines.firstOrNull { !it.startsWith("#") } ?: return true
            val segmentUrl = URI(variantUrl).resolve(segmentLine).toString()

            val segmentHeaders = reqHeaders.toMutableMap()
            segmentHeaders["Range"] = "bytes=0-4095"
            val bytes = app.get(segmentUrl, headers = segmentHeaders).body.byteStream().use { segmentStream ->
                val buf = ByteArray(4096)
                var totalRead = 0
                while (totalRead < buf.size) {
                    val r = segmentStream.read(buf, totalRead, buf.size - totalRead)
                    if (r == -1) break
                    totalRead += r
                }
                if (totalRead == buf.size) buf else buf.copyOf(totalRead)
            }

            val isPng = bytes.size >= 4 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte()

            if (!isPng) return true

            var hasTsSync = false
            if (bytes.size >= 377) {
                val maxS = minOf(187, bytes.size - 377)
                for (s in 0..maxS) {
                    if (bytes[s] == 0x47.toByte() &&
                        bytes[s + 188] == 0x47.toByte() &&
                        bytes[s + 376] == 0x47.toByte()
                    ) {
                        hasTsSync = true
                        break
                    }
                }
            }

            hasTsSync
        } catch (e: Exception) {
            true
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = try {
            AppUtils.parseJson<ZliveLoadData>(data)
        } catch (e: Exception) {
            return false
        }

        val watchUrl = "$ZLIVE_BASE/watch/${load.id}"
        val links = mutableListOf<ExtractorLink>()
        loadEmbed(watchUrl, "$ZLIVE_BASE/", subtitleCallback) { link ->
            links.add(link)
        }

        for (link in links) {
            val playable = isPlayableZliveLink(link.url, link.headers, link.referer)
            if (playable) {
                val qStr = if (!load.quality.isNullOrBlank()) " (${load.quality})" else ""
                val label = "zlive - ${load.name}$qStr"
                callback(
                    ExtractorLink(
                        source = "zlive",
                        name = label,
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

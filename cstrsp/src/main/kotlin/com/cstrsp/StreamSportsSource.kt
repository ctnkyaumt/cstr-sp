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
import com.lagradost.cloudstream3.utils.loadExtractor

data class CdnChannel(
    @JsonProperty("channel_name") val channelName: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("image") val image: String? = null
)

data class CdnEvent(
    @JsonProperty("gameID") val gameID: String? = null,
    @JsonProperty("event") val event: String? = null,
    @JsonProperty("homeTeam") val homeTeam: String? = null,
    @JsonProperty("awayTeam") val awayTeam: String? = null,
    @JsonProperty("homeTeamIMG") val homeTeamImg: String? = null,
    @JsonProperty("eventIMG") val eventImg: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("channels") val channels: List<CdnChannel>? = null
)

data class CdnResponse(
    @JsonProperty("cdn-live-tv") val data: Map<String, Any?>? = null
)

object StreamSportsSource {
    private const val cdnApiUrl = "https://api.cdnlivetv.tv/api/v1"

    private val cdnPlayerSourceRegex = Regex(
        """<source[^>]+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val cdnPlayerSourceVarRegex =
        Regex("""source\s*:\s*\{\s*src\s*:\s*([A-Za-z_$][\w$]*)""", RegexOption.IGNORE_CASE)
    private val cdnJsStringVarRegex =
        Regex("""var\s+([A-Za-z_$][\w$]*)\s*=\s*'([A-Za-z0-9_-]+)'\s*;""")
    private val cdnJsConcatRefRegex = Regex("""\(([A-Za-z_$][\w$]*)\)""")

    private val cdnNotLiveStatuses = setOf(
        "tbd", "canc", "cancl", "cancelled", "canceled", "pst", "postp", "postponed",
        "abd", "abandoned", "susp", "suspended", "wo", "awd", "ft", "aet", "pen", "fin", "finished", "ended"
    )

    fun isLiveCdn(event: CdnEvent): Boolean {
        val s = event.status?.trim()?.lowercase() ?: return true
        return s.isEmpty() || s !in cdnNotLiveStatuses
    }

    fun cdnTitle(event: CdnEvent): String =
        event.event ?: listOfNotNull(event.homeTeam, event.awayTeam).joinToString(" vs ").ifBlank { "Live Event" }

    fun cdnPoster(event: CdnEvent): String? = event.homeTeamImg ?: event.eventImg

    fun cdnItem(api: MainAPI, event: CdnEvent): SearchResponse =
        api.newLiveSearchResponse("${cdnTitle(event)} [StreamSports]", "https://cdn.domains/${event.gameID}") {
            this.posterUrl = cdnPoster(event)
        }

    private fun cdnSourceFrom(html: String, playerUrl: String): String? {
        cdnPlayerSourceRegex.find(html)?.groupValues?.get(1)?.let { direct ->
            return try {
                java.net.URI(playerUrl).resolve(direct.replace("&amp;", "&")).toString()
            } catch (e: Exception) {
                null
            }
        }

        val sourceVar = cdnPlayerSourceVarRegex.find(html)?.groupValues?.get(1) ?: return null
        val values = cdnJsStringVarRegex.findAll(html)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val expression = Regex("""var\s+${Regex.escape(sourceVar)}\s*=\s*([^;]+);""")
            .find(html)?.groupValues?.get(1) ?: return null
        val decoded = StringBuilder()
        try {
            for (match in cdnJsConcatRefRegex.findAll(expression)) {
                val encoded = values[match.groupValues[1]] ?: return null
                val bytes = android.util.Base64.decode(
                    encoded,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
                decoded.append(String(bytes, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            return null
        }
        return decoded.toString().takeIf { it.startsWith("http") }
    }

    suspend fun resolveCdnChannel(url: String): String? = CstrspCache.cached("cdn-channel-$url") {
        val html = app.get(url, referer = "https://cdnlivetv.tv/").text
        cdnSourceFrom(html, url)
    }

    private fun pruneAmbiguousCdnChannels(events: Map<String, List<CdnEvent>>): Map<String, List<CdnEvent>> {
        val liveNameCounts = HashMap<String, Int>()
        events.values.flatten().filter { isLiveCdn(it) }.forEach { ev ->
            ev.channels.orEmpty()
                .mapNotNull { ch -> ch.channelName?.takeIf { !ch.url.isNullOrBlank() } }
                .distinct()
                .forEach { name -> liveNameCounts[name] = (liveNameCounts[name] ?: 0) + 1 }
        }
        val ambiguous = liveNameCounts.filterValues { it > 1 }.keys
        if (ambiguous.isEmpty()) return events

        val cleaned = LinkedHashMap<String, List<CdnEvent>>()
        events.forEach { (sport, list) ->
            val kept = list
                .map { ev -> ev.copy(channels = ev.channels?.filter { it.channelName == null || it.channelName !in ambiguous }) }
                .filter { ev -> ev.channels?.any { !it.url.isNullOrBlank() } == true }
            if (kept.isNotEmpty()) cleaned[sport] = kept
        }
        return cleaned
    }

    suspend fun fetchCdnEvents(): Map<String, List<CdnEvent>> = CstrspCache.cached("cdn") {
        val res = app.get("$cdnApiUrl/events/sports/?user=cdnlivetv&plan=free").parsedSafe<CdnResponse>()
            ?: return@cached null
        val out = LinkedHashMap<String, List<CdnEvent>>()
        res.data?.forEach { (sport, value) ->
            val raw = value as? List<*> ?: return@forEach
            val events = try {
                AppUtils.parseJson<Array<CdnEvent>>(raw.toJson()).toList()
            } catch (e: Exception) {
                emptyList()
            }.filter { ev -> ev.gameID != null && ev.channels?.any { !it.url.isNullOrBlank() } == true }
            if (events.isNotEmpty()) out[sport] = events
        }
        pruneAmbiguousCdnChannels(out)
    } ?: emptyMap()

    suspend fun getHomeSections(api: MainAPI): List<HomePageList> =
        fetchCdnEvents().mapNotNull { (sport, events) ->
            val items = events.filter { isLiveCdn(it) }.map { cdnItem(api, it) }
            if (items.isEmpty()) null
            else HomePageList("${sport.replaceFirstChar { it.uppercase() }} [StreamSports]", items)
        }

    suspend fun search(api: MainAPI, matcher: QueryMatcher): List<SearchResponse> =
        fetchCdnEvents().flatMap { (sport, events) ->
            events.filter { isLiveCdn(it) && matcher.matches(cdnTitle(it), sport, it.homeTeam, it.awayTeam, it.event) }.map { cdnItem(api, it) }
        }

    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (!url.startsWith("https://cdn.domains/")) return null
        val gameId = url.substringAfterLast("/")
        val event = fetchCdnEvents().values.flatten().find { it.gameID == gameId } ?: return null
        val title = cdnTitle(event)
        return api.newLiveStreamLoadResponse(
            name = "$title [StreamSports]",
            url = url,
            dataUrl = event.toJson()
        ) {
            this.posterUrl = cdnPoster(event)
            this.plot = title
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val event = try {
            AppUtils.parseJson<CdnEvent>(data)
        } catch (e: Exception) {
            return false
        }

        if (event.gameID != null && !event.channels.isNullOrEmpty()) {
            val channels = event.channels.filter { !it.url.isNullOrBlank() }.take(8)
            channels.resolveConcurrently { channel ->
                val chName = channel.channelName ?: "Channel"
                val direct = resolveCdnChannel(channel.url!!)
                if (direct != null) {
                    callback(
                        ExtractorLink(
                            source = "StreamSports",
                            name = "StreamSports - $chName",
                            url = direct,
                            referer = "https://cdnlivetv.tv/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                } else {
                    loadExtractor(channel.url!!, "https://cdnlivetv.tv/", subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = "StreamSports",
                                name = withQualityLabel("StreamSports - $chName", link.quality),
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
        return false
    }
}

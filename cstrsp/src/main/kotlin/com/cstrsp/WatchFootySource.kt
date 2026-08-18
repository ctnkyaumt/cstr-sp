package com.cstrsp

import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

data class WFStream(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("source") val source: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("isRedirect") val isRedirect: Boolean? = false,
    @JsonProperty("nsfw") val nsfw: Boolean? = false
)

data class WFTeam(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("logoUrl") val logoUrl: String? = null,
    @JsonProperty("logoId") val logoId: String? = null
)

data class WFTeams(
    @JsonProperty("home") val home: WFTeam? = null,
    @JsonProperty("away") val away: WFTeam? = null
)

data class WFMatch(
    @JsonProperty("matchId") val matchId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("teams") val teams: WFTeams? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("sport") val sport: String? = null,
    @JsonProperty("streams") val streams: List<WFStream>? = null
)

object WatchFootySource {
    private val iframeSrcRegex = Regex(
        """<iframe[^>]+src\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )

    suspend fun fetchWFMatches(): List<WFMatch> = CstrspCache.cached("wf") {
        app.get("https://api.watchfooty.st/api/v1/matches/all").parsedSafe<Array<WFMatch>>()?.toList()
    } ?: emptyList()

    private fun wfQuality(q: String?): Int = when (q?.trim()?.lowercase()) {
        "1080p" -> Qualities.P1080.value
        "hd" -> Qualities.P720.value
        "sd" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    fun isLiveWf(match: WFMatch): Boolean =
        match.status?.trim()?.lowercase().let { it == "in" || it == "live" }

    fun wfHasHd(match: WFMatch): Boolean =
        match.streams?.any { !it.url.isNullOrBlank() } == true

    fun wfPoster(match: WFMatch): String? = match.poster?.let { "https://api.watchfooty.st$it" }

    fun wfListable(match: WFMatch): Boolean =
        match.matchId != null && wfHasHd(match) && isLiveWf(match)

    fun wfItem(match: WFMatch): SearchResponse =
        newLiveSearchResponse("${match.title ?: "Live Event"} [WF]", "https://wf.domains/${match.matchId}") {
            this.posterUrl = wfPoster(match)
        }

    suspend fun unwrapWfEmbed(url: String): String = CstrspCache.cached("wf-embed-$url") {
        val html = app.get(url, referer = "https://api.watchfooty.st/").text
        iframeSrcRegex.find(html)?.groupValues?.get(1)?.let { raw ->
            val src = raw.replace("&amp;", "&")
            try {
                when {
                    src.startsWith("//") -> "https:$src"
                    else -> java.net.URI(url).resolve(src).toString()
                }
            } catch (e: Exception) {
                null
            }
        }?.takeIf { it.startsWith("http") }
    } ?: url

    suspend fun getHomeSections(): List<HomePageList> =
        fetchWFMatches()
            .filter { wfListable(it) }
            .groupBy { it.sport ?: "Unknown" }
            .map { (sport, matches) ->
                HomePageList("${sport.replaceFirstChar { it.uppercase() }} [WF]", matches.map { wfItem(it) })
            }

    suspend fun search(matcher: QueryMatcher): List<SearchResponse> =
        fetchWFMatches()
            .filter { match ->
                wfListable(match) && matcher.matches(match.title ?: "Live Event", match.sport, match.league, match.teams?.home?.name, match.teams?.away?.name)
            }
            .map { wfItem(it) }

    suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("https://wf.domains/")) return null
        val matchId = url.substringAfterLast("/")
        val match = fetchWFMatches().find { it.matchId == matchId } ?: return null
        if (match.streams.isNullOrEmpty()) return null
        val title = match.title ?: "Live Stream"

        return newLiveStreamLoadResponse(
            name = "$title [WF]",
            url = url,
            dataUrl = match.toJson()
        ) {
            this.posterUrl = wfPoster(match)
            this.plot = title
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val match = try {
            AppUtils.parseJson<WFMatch>(data)
        } catch (e: Exception) {
            return false
        }

        if (match.matchId != null && !match.streams.isNullOrEmpty()) {
            val streams = match.streams
                .filter { !it.url.isNullOrBlank() }
                .sortedByDescending { !"SD".equals(it.quality?.trim(), ignoreCase = true) }
                .take(15)
            streams.resolveConcurrently { stream ->
                val base = listOfNotNull(stream.source, stream.language).joinToString(" - ").ifBlank { "Live" }
                val embed = encodeUrlNonAscii(unwrapWfEmbed(stream.url!!))
                loadExtractor(embed, stream.url, subtitleCallback) { link ->
                    var resolvedQuality = if (link.quality != Qualities.Unknown.value) link.quality else wfQuality(stream.quality)
                    val nameLower = link.name.lowercase()
                    val urlLower = link.url.lowercase()
                    val srcLower = (stream.source ?: "").lowercase()
                    if (resolvedQuality < Qualities.P1080.value) {
                        if (nameLower.contains("1080") || nameLower.contains("fhd") ||
                            urlLower.contains("1080") || urlLower.contains("fhd") ||
                            srcLower.contains("1080") || srcLower.contains("fhd")) {
                            resolvedQuality = Qualities.P1080.value
                        }
                    }
                    val labeled = withQualityLabel("WF - $base", resolvedQuality)
                    callback(
                        ExtractorLink(
                            source = "WF",
                            name = labeled,
                            url = link.url,
                            referer = link.referer,
                            quality = resolvedQuality,
                            type = link.type,
                            headers = link.headers,
                            extractorData = link.extractorData
                        )
                    )
                }
            }
            return true
        }
        return false
    }
}

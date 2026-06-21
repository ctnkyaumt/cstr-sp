package com.cstrsp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty

class Cstrsp : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "cstrsp"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://streamed.pk/api"

    data class APIMatch(
        @JsonProperty("id") val id: String,
        @JsonProperty("title") val title: String,
        @JsonProperty("category") val category: String,
        @JsonProperty("date") val date: Long,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("popular") val popular: Boolean = false,
        @JsonProperty("teams") val teams: APITeams? = null,
        @JsonProperty("sources") val sources: List<APISource>? = null
    )

    data class APITeams(
        @JsonProperty("home") val home: APITeam? = null,
        @JsonProperty("away") val away: APITeam? = null
    )

    data class APITeam(
        @JsonProperty("name") val name: String,
        @JsonProperty("badge") val badge: String
    )

    data class APISource(
        @JsonProperty("source") val source: String,
        @JsonProperty("id") val id: String
    )

    data class APIStream(
        @JsonProperty("id") val id: String,
        @JsonProperty("streamNo") val streamNo: Int,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("hd") val hd: Boolean = false,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("source") val source: String
    )

    // Helper to fetch matches from an endpoint, returning empty list on failure
    private suspend fun fetchMatches(endpoint: String): List<APIMatch> {
        return try {
            app.get(endpoint).parsedSafe<Array<APIMatch>>()?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Try live matches first
        var matches = fetchMatches("$apiUrl/matches/live")
        var isLive = true

        // Fallback to today's matches if live is empty
        if (matches.isEmpty()) {
            matches = fetchMatches("$apiUrl/matches/all-today")
            isLive = false
        }

        // Group by category to create rows on the homepage
        val grouped = matches.groupBy { it.category }

        val homePageLists = grouped.map { (category, categoryMatches) ->
            val list = categoryMatches.mapNotNull { match ->
                toSearchResponse(match, isLive)
            }
            val label = if (isLive) {
                category.replaceFirstChar { it.uppercase() }
            } else {
                "${category.replaceFirstChar { it.uppercase() }} [Upcoming]"
            }
            HomePageList(label, list)
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Search live matches first
        val liveMatches = fetchMatches("$apiUrl/matches/live")
        val results = liveMatches.filter { it.title.contains(query, ignoreCase = true) }.mapNotNull { match ->
            toSearchResponse(match, isLive = true)
        }.toMutableList()

        // Also search today's matches for broader results
        if (results.isEmpty()) {
            val todayMatches = fetchMatches("$apiUrl/matches/all-today")
            results.addAll(todayMatches.filter { it.title.contains(query, ignoreCase = true) }.mapNotNull { match ->
                toSearchResponse(match, isLive = false)
            })
        }

        return results
    }

    private fun toSearchResponse(match: APIMatch, isLive: Boolean): SearchResponse? {
        val posterUrl = if (match.poster != null) {
            "$mainUrl${match.poster}"
        } else if (match.teams?.home?.badge != null) {
            "$apiUrl/images/badge/${match.teams.home.badge}.webp"
        } else {
            null
        }

        // Build title with source names
        val sourceNames = match.sources?.joinToString(", ") { src ->
            src.source.replaceFirstChar { it.uppercase() }
        } ?: ""
        val sourceLabel = if (sourceNames.isNotEmpty()) " [$sourceNames]" else ""
        val liveLabel = if (!isLive) " [Upcoming]" else ""
        val displayTitle = "${match.title}$sourceLabel$liveLabel"

        return newLiveSearchResponse(
            name = displayTitle,
            url = "$mainUrl/match/${match.id}" // Pass match URL
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val matchId = url.substringAfterLast("/")

        // Try live first, then all-today
        var matches = fetchMatches("$apiUrl/matches/live")
        var match = matches.find { it.id == matchId }
        var isLive = true

        if (match == null) {
            matches = fetchMatches("$apiUrl/matches/all-today")
            match = matches.find { it.id == matchId }
            isLive = false
        }

        if (match == null) return null

        val posterUrl = if (match.poster != null) {
            "$mainUrl${match.poster}"
        } else if (match.teams?.home?.badge != null) {
            "$apiUrl/images/badge/${match.teams.home.badge}.webp"
        } else {
            null
        }

        // Build title with source names
        val sourceNames = match.sources?.joinToString(", ") { src ->
            src.source.replaceFirstChar { it.uppercase() }
        } ?: ""
        val sourceLabel = if (sourceNames.isNotEmpty()) " [$sourceNames]" else ""
        val liveLabel = if (!isLive) " [Upcoming]" else ""
        val displayTitle = "${match.title}$sourceLabel$liveLabel"

        return newLiveStreamLoadResponse(
            name = displayTitle,
            url = url,
            dataUrl = (match.sources ?: emptyList<APISource>()).toJson()
        ) {
            this.posterUrl = posterUrl
            this.plot = if (isLive) "Live stream for ${match.title}" else "Upcoming: ${match.title}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = try {
            AppUtils.parseJson<List<APISource>>(data)
        } catch (e: Exception) {
            return false
        }

        sources.forEach { source ->
            try {
                val streams = app.get("$apiUrl/stream/${source.source}/${source.id}")
                    .parsedSafe<Array<APIStream>>()?.toList() ?: emptyList()

                streams.forEach { stream ->
                    try {
                        val isHD = stream.hd
                        val langStr = stream.language ?: "Unknown"
                        val quality = if (isHD) Qualities.P1080.value else Qualities.P720.value
                        val sourceName = source.source.replaceFirstChar { it.uppercase() }
                        val name = "$sourceName - $langStr"

                        // Pass the embedUrl directly as the stream URL
                        // The embed URL from the API is the playable endpoint
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - Stream ${stream.streamNo}",
                                url = stream.embedUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "Origin" to mainUrl
                                )
                                this.quality = quality
                            }
                        )
                    } catch (e: Exception) {
                        // Skip this individual stream but continue with others
                    }
                }
            } catch (e: Exception) {
                // Skip this source but continue with others
            }
        }

        return true
    }
}

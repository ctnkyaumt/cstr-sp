package com.cstrsp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Cstrsp : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "cstrsp"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://streamed.pk/api"

    data class APIMatch(
        val id: String,
        val title: String,
        val category: String,
        val date: Long,
        val poster: String? = null,
        val popular: Boolean = false,
        val teams: APITeams? = null,
        val sources: List<APISource>? = null
    )

    data class APITeams(
        val home: APITeam? = null,
        val away: APITeam? = null
    )

    data class APITeam(
        val name: String,
        val badge: String
    )

    data class APISource(
        val source: String,
        val id: String
    )

    data class APIStream(
        val id: String,
        val streamNo: Int,
        val language: String? = null,
        val hd: Boolean = false,
        val embedUrl: String,
        val source: String
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val matches = app.get("$apiUrl/matches/live").parsedSafe<List<APIMatch>>() ?: emptyList()
        
        // Group by category to create rows on the homepage
        val grouped = matches.groupBy { it.category }
        
        val homePageLists = grouped.map { (category, categoryMatches) ->
            val list = categoryMatches.mapNotNull { match ->
                toSearchResponse(match)
            }
            HomePageList(category.replaceFirstChar { it.uppercase() }, list)
        }
        
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val matches = app.get("$apiUrl/matches/live").parsedSafe<List<APIMatch>>() ?: emptyList()
        return matches.filter { it.title.contains(query, ignoreCase = true) }.mapNotNull { match ->
            toSearchResponse(match)
        }
    }

    private fun toSearchResponse(match: APIMatch): SearchResponse? {
        val posterUrl = if (match.poster != null) {
            "$mainUrl${match.poster}.webp"
        } else if (match.teams?.home?.badge != null) {
            "$apiUrl/images/badge/${match.teams.home.badge}.webp"
        } else {
            null
        }

        return newLiveSearchResponse(
            name = match.title,
            url = "$mainUrl/match/${match.id}" // Pass match URL
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val matchId = url.substringAfterLast("/")
        
        val matches = app.get("$apiUrl/matches/live").parsedSafe<List<APIMatch>>() ?: emptyList()
        val match = matches.find { it.id == matchId } ?: return null
        
        val posterUrl = if (match.poster != null) {
            "$mainUrl${match.poster}.webp"
        } else if (match.teams?.home?.badge != null) {
            "$apiUrl/images/badge/${match.teams.home.badge}.webp"
        } else {
            null
        }

        return newLiveStreamLoadResponse(
            name = match.title,
            url = url,
            dataUrl = AppUtils.toJson(match.sources ?: emptyList<APISource>()) // Serialize sources directly
        ) {
            this.posterUrl = posterUrl
            this.plot = "Live stream for ${match.title}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = AppUtils.parseJson<List<APISource>>(data)
        
        sources.forEach { source ->
            val streams = app.get("$apiUrl/stream/${source.source}/${source.id}").parsedSafe<List<APIStream>>() ?: emptyList()
            
            streams.forEach { stream ->
                val isHD = stream.hd
                val langStr = stream.language ?: "Unknown"
                val quality = if (isHD) Qualities.P1080.value else Qualities.P720.value
                val name = "${source.source.replaceFirstChar { it.uppercase() }} - $langStr"
                
                val embedUrl = stream.embedUrl
                
                // Fetch the embed iframe to extract m3u8
                val embedDoc = app.get(embedUrl).document
                val scriptStr = embedDoc.select("script").outerHtml()
                val m3u8Regex = Regex("(?i)(https?://[^\"']+\\.m3u8[^\"']*)")
                val m3u8Match = m3u8Regex.find(scriptStr)
                
                if (m3u8Match != null) {
                    val streamUrl = m3u8Match.groupValues[1]
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - Stream",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf("Referer" to embedUrl)
                            this.quality = quality
                        }
                    )
                } else {
                    // Try generic extractor if not a direct m3u8
                    loadExtractor(embedUrl, embedUrl, subtitleCallback, callback)
                }
            }
        }
        
        return true
    }
}

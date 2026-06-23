package com.cstrsp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.annotation.JsonProperty

class Cstrsp : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "cstrsp"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://streamed.pk/api"
    private val cdnApiUrl = "https://api.cdnlivetv.tv/api/v1"

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

    data class PPVStream(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("iframe") val iframe: String? = null
    )

    data class PPVCategory(
        @JsonProperty("category_name") val category_name: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("streams") val streams: List<PPVStream>? = null
    )

    data class PPVResponse(
        @JsonProperty("streams") val streams: List<PPVCategory>? = null
    )

    private val ppvDomains = listOf("api.ppv.to", "api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx")

    private suspend fun fetchPPVApi(): PPVResponse? {
        for (domain in ppvDomains) {
            try {
                val url = "https://$domain/api/streams"
                val response = app.get(url, timeout = 3L).text
                val parsed = AppUtils.parseJson<PPVResponse>(response)
                if (parsed?.streams != null) {
                    return parsed
                }
            } catch (e: Exception) {}
        }
        return null
    }

    // Helper to fetch matches from streamed.pk
    private suspend fun fetchMatches(endpoint: String): List<APIMatch> {
        return try {
            app.get(endpoint).parsedSafe<Array<APIMatch>>()?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = mutableListOf<HomePageList>()

        // 1. Fetch Streamed.pk (Main Source)
        val mainMatches = fetchMatches("$apiUrl/matches/live")
        mainMatches.groupBy { it.category }.forEach { (category, matches) ->
            val list = matches.mapNotNull { match ->
                val posterUrl = if (match.poster != null) {
                    "$mainUrl${match.poster}"
                } else if (match.teams?.home?.badge != null) {
                    "$apiUrl/images/badge/${match.teams.home.badge}.webp"
                } else {
                    null
                }
                newLiveSearchResponse(match.title, "$mainUrl/match/${match.id}") {
                    this.posterUrl = posterUrl
                }
            }
            if (list.isNotEmpty()) {
                homePageLists.add(HomePageList("${category.replaceFirstChar { it.uppercase() }} [Streamed]", list))
            }
        }

        // 2. Fetch PPV Domains (Backup Source)
        try {
            val ppvRes = fetchPPVApi()
            ppvRes?.streams?.forEach { category ->
                val catName = category.category_name ?: category.category ?: "Unknown"
                val matchList = mutableListOf<SearchResponse>()
                
                category.streams?.forEach { stream ->
                    val title = stream.name ?: "Unknown Event"
                    val posterUrl = stream.poster
                    val embedUrl = stream.iframe
                    
                    if (embedUrl != null) {
                        matchList.add(
                            newLiveSearchResponse(
                                name = "$title [StreamSports]",
                                url = "https://ppv.domains/${stream.id}||$embedUrl"
                            ) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
                if (matchList.isNotEmpty()) {
                    homePageLists.add(HomePageList("$catName [StreamSports]", matchList))
                }
            }
        } catch (e: Exception) {
            // Backup source failed
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        results.add(
            newLiveSearchResponse(
                name = "TRT Yayını",
                url = "https://tv-trt1.medya.trt.com.tr/master.m3u8"
            ) {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/14/TRT_1_logo.svg/1200px-TRT_1_logo.svg.png"
            }
        )

        // Search Streamed.pk
        val mainMatches = fetchMatches("$apiUrl/matches/live")
        results.addAll(mainMatches.filter { it.title.contains(query, ignoreCase = true) }.mapNotNull { match ->
            val posterUrl = if (match.poster != null) "$mainUrl${match.poster}" else if (match.teams?.home?.badge != null) "$apiUrl/images/badge/${match.teams.home.badge}.webp" else null
            newLiveSearchResponse(match.title, "$mainUrl/match/${match.id}") {
                this.posterUrl = posterUrl
            }
        })

        // Search PPV Domains (Backup Source)
        try {
            val ppvRes = fetchPPVApi()
            ppvRes?.streams?.forEach { category ->
                category.streams?.forEach { stream ->
                    val title = stream.name ?: "Unknown Event"
                    
                    if (title.contains(query, ignoreCase = true)) {
                        val posterUrl = stream.poster
                        val embedUrl = stream.iframe
                        
                        if (embedUrl != null) {
                            results.add(
                                newLiveSearchResponse(
                                    name = "$title [StreamSports]",
                                    url = "https://ppv.domains/${stream.id}||$embedUrl"
                                ) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url == "https://tv-trt1.medya.trt.com.tr/master.m3u8") {
            return newLiveStreamLoadResponse(
                name = "TRT Yayını",
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/14/TRT_1_logo.svg/1200px-TRT_1_logo.svg.png"
                this.plot = "TRT Yayını Live Stream"
            }
        }

        // Handle PPV Streams
        if (url.contains("ppv.domains/")) {
            val embedUrl = url.substringAfter("||")
            return newLiveStreamLoadResponse(
                name = "Live Stream",
                url = url,
                dataUrl = "https://ppvextract.domains/||$embedUrl"
            )
        }

        // Handle Streamed.pk (Main Source) URL format
        val matchId = url.substringAfterLast("/")
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
        if (data == "https://tv-trt1.medya.trt.com.tr/master.m3u8") {
            callback.invoke(
                ExtractorLink(
                    source = "TRT",
                    name = "TRT Yayını",
                    url = data,
                    referer = "",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        // Handle PPV Extract
        if (data.contains("ppvextract.domains/||")) {
            val embedUrl = data.substringAfter("||")
            loadExtractor(embedUrl, "https://embedindia.st/", subtitleCallback) { link ->
                callback(
                    ExtractorLink(
                        source = "StreamSports",
                        name = "StreamSports",
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type,
                        headers = link.headers,
                        extractorData = link.extractorData
                    )
                )
            }
            return true
        }

        // Try parsing as Streamed.pk (Main Source) sources
        val sources = try {
            AppUtils.parseJson<List<APISource>>(data)
        } catch (e: Exception) {
            return false
        }

        if (sources != null) {
            for (source in sources) {
                try {
                    val streams = app.get("$apiUrl/stream/${source.source}/${source.id}")
                        .parsedSafe<Array<APIStream>>()?.toList() ?: emptyList()
    
                    for (stream in streams) {
                        try {
                            val isHD = stream.hd
                            val langStr = stream.language ?: "Unknown"
                            val quality = if (isHD) Qualities.P1080.value else Qualities.P720.value
                            val sourceName = source.source.replaceFirstChar { it.uppercase() }
                            val name = "$sourceName - $langStr"
                            val embedUrl = stream.embedUrl
    
                            // Pass the embed URL to our WebView extractor (or built-in extractors)
                            loadExtractor(embedUrl, "$mainUrl/", subtitleCallback) { link ->
                                callback.invoke(
                                    ExtractorLink(
                                        source = "$name - Stream ${stream.streamNo}",
                                        name = "$name - Stream ${stream.streamNo}",
                                        url = link.url,
                                        referer = link.referer,
                                        quality = quality,
                                        type = link.type,
                                        headers = link.headers,
                                        extractorData = link.extractorData
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            // Skip stream
                        }
                    }
                } catch (e: Exception) {
                    // Skip source
                }
            }
        }

        return true
    }
}

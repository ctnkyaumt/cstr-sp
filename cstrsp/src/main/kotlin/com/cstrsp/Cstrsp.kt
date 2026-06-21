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

    data class CDNMatch(
        @JsonProperty("gameID") val gameID: String,
        @JsonProperty("event") val event: String?,
        @JsonProperty("homeTeam") val homeTeam: String?,
        @JsonProperty("awayTeam") val awayTeam: String?,
        @JsonProperty("homeTeamIMG") val homeTeamIMG: String?,
        @JsonProperty("awayTeamIMG") val awayTeamIMG: String?,
        @JsonProperty("time") val time: String?,
        @JsonProperty("tournament") val tournament: String?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("countryIMG") val countryIMG: String?,
        @JsonProperty("status") val status: String?, // "live", "NS", etc.
        @JsonProperty("channels") val channels: List<CDNChannel>? = null
    )

    data class CDNChannel(
        @JsonProperty("channel_name") val channel_name: String? = null,
        @JsonProperty("channel_code") val channel_code: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("code") val code: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("status") val status: String? = null
    )

    data class CDNChannelsResponse(
        @JsonProperty("total_channels") val total_channels: Int? = null,
        @JsonProperty("channels") val channels: List<CDNChannel>? = null
    )

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

        // 2. Fetch CDN Live TV (Backup Source)
        try {
            val cdnJson = app.get("$cdnApiUrl/events/sports/?user=cdnlivetv&plan=free").text
            val parsedMap = AppUtils.parseJson<Map<String, Any>>(cdnJson)
            
            parsedMap?.forEach { (key, value) ->
                val categoriesMap = if (key == "cdn-live-tv" && value is Map<*, *>) value else mapOf(key to value)
                categoriesMap.forEach categoryLoop@{ categoryKey, items ->
                    if (items !is List<*>) return@categoryLoop
                    val matchList = mutableListOf<SearchResponse>()
                    items.forEach { item ->
                        try {
                            val matchStr = item?.toJson() ?: ""
                            val cdnMatch = AppUtils.parseJson<CDNMatch>(matchStr)
                            
                            // Include live and upcoming
                            if (cdnMatch != null && (cdnMatch.status?.lowercase() == "live" || cdnMatch.status?.lowercase() == "ns" || cdnMatch.status?.lowercase() == "pst")) {
                                val title = if (cdnMatch.homeTeam.isNullOrEmpty() || cdnMatch.awayTeam.isNullOrEmpty()) {
                                    cdnMatch.event ?: "Unknown Match"
                                } else {
                                    "${cdnMatch.homeTeam} vs ${cdnMatch.awayTeam}"
                                }
                                
                                val posterUrl = cdnMatch.homeTeamIMG ?: cdnMatch.awayTeamIMG
                                val isLive = cdnMatch.status?.lowercase() == "live"
                                val liveLabel = if (!isLive) " [Upcoming]" else ""
                                
                                matchList.add(
                                    newLiveSearchResponse(
                                        name = "$title [StreamSports]$liveLabel",
                                        // Pass the match JSON embedded in the URL so load() can parse it instantly
                                        url = "cdnlivetv://${cdnMatch.gameID}||$matchStr"
                                    ) {
                                        this.posterUrl = posterUrl
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            // Ignore parsing errors for individual items
                        }
                    }
                    if (matchList.isNotEmpty()) {
                        homePageLists.add(HomePageList("$categoryKey [StreamSports]", matchList))
                    }
                }
            }
        } catch (e: Exception) {
            // Backup source failed
        }

        // 3. Fetch CDN Live TV Channels
        try {
            val channelsJson = app.get("$cdnApiUrl/channels/?user=cdnlivetv&plan=free").text
            val channelsRes = AppUtils.parseJson<CDNChannelsResponse>(channelsJson)
            val channelsList = mutableListOf<SearchResponse>()
            
            channelsRes?.channels?.filter { it.status == "online" }?.forEach { ch ->
                val chName = ch.name ?: "Unknown Channel"
                val chStr = ch.toJson()
                channelsList.add(
                    newLiveSearchResponse(
                        name = "$chName [Channel]",
                        url = "cdnlivetvchannel://||$chStr"
                    ) {
                        this.posterUrl = ch.image
                    }
                )
            }
            if (channelsList.isNotEmpty()) {
                homePageLists.add(HomePageList("24/7 Channels [StreamSports]", channelsList))
            }
        } catch (e: Exception) {}

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // Search Streamed.pk
        val mainMatches = fetchMatches("$apiUrl/matches/live")
        results.addAll(mainMatches.filter { it.title.contains(query, ignoreCase = true) }.mapNotNull { match ->
            val posterUrl = if (match.poster != null) "$mainUrl${match.poster}" else if (match.teams?.home?.badge != null) "$apiUrl/images/badge/${match.teams.home.badge}.webp" else null
            newLiveSearchResponse(match.title, "$mainUrl/match/${match.id}") {
                this.posterUrl = posterUrl
            }
        })

        // Search CDN Live TV (Backup)
        try {
            val cdnJson = app.get("$cdnApiUrl/events/sports/?user=cdnlivetv&plan=free").text
            val parsedMap = AppUtils.parseJson<Map<String, Any>>(cdnJson)
            parsedMap?.forEach { (key, value) ->
                val categoriesMap = if (key == "cdn-live-tv" && value is Map<*, *>) value else mapOf(key to value)
                categoriesMap.forEach categoryLoop@{ _, items ->
                    if (items !is List<*>) return@categoryLoop
                    items.forEach { item ->
                        try {
                            val matchStr = item?.toJson() ?: ""
                            val cdnMatch = AppUtils.parseJson<CDNMatch>(matchStr)
                            
                            if (cdnMatch != null) {
                                val title = if (cdnMatch.homeTeam.isNullOrEmpty() || cdnMatch.awayTeam.isNullOrEmpty()) cdnMatch.event ?: "Unknown" else "${cdnMatch.homeTeam} vs ${cdnMatch.awayTeam}"
                                val status = cdnMatch.status?.lowercase()
                                
                                if ((status == "live" || status == "ns" || status == "pst") && title.contains(query, ignoreCase = true)) {
                                    val isLive = status == "live"
                                    val liveLabel = if (!isLive) " [Upcoming]" else ""
                                    val posterUrl = cdnMatch.homeTeamIMG ?: cdnMatch.awayTeamIMG
                                    
                                    results.add(
                                        newLiveSearchResponse(
                                            name = "$title [StreamSports]$liveLabel",
                                            url = "cdnlivetv://${cdnMatch.gameID}||$matchStr"
                                        ) {
                                            this.posterUrl = posterUrl
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}

        // Search CDN Live TV Channels
        try {
            val channelsJson = app.get("$cdnApiUrl/channels/?user=cdnlivetv&plan=free").text
            val channelsRes = AppUtils.parseJson<CDNChannelsResponse>(channelsJson)
            channelsRes?.channels?.filter { it.status == "online" && it.name?.contains(query, ignoreCase = true) == true }?.forEach { ch ->
                val chName = ch.name ?: "Unknown Channel"
                val chStr = ch.toJson()
                results.add(
                    newLiveSearchResponse(
                        name = "$chName [Channel]",
                        url = "cdnlivetvchannel://||$chStr"
                    ) {
                        this.posterUrl = ch.image
                    }
                )
            }
        } catch (e: Exception) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        // Handle CDN Live TV Channels
        if (url.startsWith("cdnlivetvchannel://")) {
            val data = url.substringAfter("||")
            val ch = AppUtils.parseJson<CDNChannel>(data)
            if (ch != null) {
                return newLiveStreamLoadResponse(
                    name = "${ch.name} [Channel]",
                    url = url,
                    dataUrl = listOf(ch).toJson()
                ) {
                    this.posterUrl = ch.image
                    this.plot = "24/7 Live Stream: ${ch.name}"
                }
            }
        }

        // Handle CDN Live TV (Backup Source) URL format
        if (url.startsWith("cdnlivetv://")) {
            val data = url.substringAfter("||")
            val cdnMatch = AppUtils.parseJson<CDNMatch>(data)
            
            if (cdnMatch != null) {
                val title = if (cdnMatch.homeTeam.isNullOrEmpty() || cdnMatch.awayTeam.isNullOrEmpty()) {
                    cdnMatch.event ?: "Unknown Match"
                } else {
                    "${cdnMatch.homeTeam} vs ${cdnMatch.awayTeam}"
                }
                val posterUrl = cdnMatch.homeTeamIMG ?: cdnMatch.awayTeamIMG
                val isLive = cdnMatch.status == "live"
                val liveLabel = if (!isLive) " [Upcoming]" else ""
    
                return newLiveStreamLoadResponse(
                    name = "$title [StreamSports]$liveLabel",
                    url = url,
                    // Pass channels array as JSON to loadLinks
                    dataUrl = (cdnMatch.channels ?: emptyList<CDNChannel>()).toJson()
                ) {
                    this.posterUrl = posterUrl
                    this.plot = if (isLive) "Live stream for $title" else "Upcoming: $title"
                }
            }
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
        // Try parsing as CDN Live TV (Backup Source) channels first
        try {
            val cdnChannels = AppUtils.parseJson<List<CDNChannel>>(data)
            if (!cdnChannels.isNullOrEmpty() && cdnChannels[0].channel_name != null) {
                for ((index, channel) in cdnChannels.withIndex()) {
                    val url = channel.url ?: continue
                    val name = channel.channel_name ?: "StreamSports Channel ${index + 1}"
                    
                    // Route to our CstrspExtractor
                    loadExtractor(url, "https://api.cdnlivetv.tv/", subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = "StreamSports",
                                name = name,
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
        } catch (e: Exception) {
            // Not CDNChannels, continue to APISource
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

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

    private var apiUrl = "https://streamed.pk/api"
    private val cdnApiUrl = "https://api.cdnlivetv.tv/api/v1"
    private var isDomainChecked = false
    private val domains = listOf("https://streamed.pk", "https://streamed.st")

    private suspend fun checkAndGetDomain() {
        if (isDomainChecked) return
        for (domain in domains) {
            try {
                val response = app.get("$domain/api/matches/live", timeout = 5L)
                if (response.code in 200..299) {
                    mainUrl = domain
                    apiUrl = "$domain/api"
                    isDomainChecked = true
                    return
                }
            } catch (e: Exception) {}
        }
        mainUrl = domains.first()
        apiUrl = "${domains.first()}/api"
        isDomainChecked = true
    }

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
        @JsonProperty("badge") val badge: String? = null
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

    data class PPVSubstream(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("source_tag") val source_tag: String? = null,
        @JsonProperty("locale") val locale: String? = null,
        @JsonProperty("iframe") val iframe: String? = null,
        @JsonProperty("uri_name") val uri_name: String? = null
    )

    data class PPVStream(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("iframe") val iframe: String? = null,
        @JsonProperty("uri_name") val uri_name: String? = null,
        @JsonProperty("substreams") val substreams: List<PPVSubstream>? = null
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
        checkAndGetDomain()
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
                    val posterUrl = stream.poster?.let {
                        val encoded = android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                        "$mainUrl/api/images/proxy/$encoded.webp"
                    }
                    
                    if (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty()) {
                        matchList.add(
                            newLiveSearchResponse(
                                name = "$title [PPV]",
                                url = "https://ppv.domains/${stream.id}"
                            ) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
                if (matchList.isNotEmpty()) {
                    homePageLists.add(HomePageList("$catName [PPV]", matchList))
                }
            }
        } catch (e: Exception) {
            // Backup source failed
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndGetDomain()
        val results = mutableListOf<SearchResponse>()
        
        results.add(
            newLiveSearchResponse(
                name = "TRT Yayını",
                url = "https://tv-trt1.medya.trt.com.tr/master.m3u8"
            ) {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/TRT_1_logo_%282021-%29.svg/1280px-TRT_1_logo_%282021-%29.svg.png"
            }
        )

        // Search Streamed.pk
        val liveMatches = fetchMatches("$apiUrl/matches/live")
        val todayMatches = fetchMatches("$apiUrl/matches/all-today")
        val allMatches = (liveMatches + todayMatches).distinctBy { it.id }
        
        val queryParts = query.split(" ").filter { it.isNotBlank() }
        results.addAll(allMatches.filter { match -> 
            queryParts.all { match.title.contains(it, ignoreCase = true) }
        }.mapNotNull { match ->
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
                        val posterUrl = stream.poster?.let {
                            val encoded = android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                            "$mainUrl/api/images/proxy/$encoded.webp"
                        }
                        
                        if (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty()) {
                            results.add(
                                newLiveSearchResponse(
                                    name = "$title [PPV]",
                                    url = "https://ppv.domains/${stream.id}"
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
        checkAndGetDomain()
        if (url == "https://tv-trt1.medya.trt.com.tr/master.m3u8") {
            return newLiveStreamLoadResponse(
                name = "TRT Yayını",
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/TRT_1_logo_%282021-%29.svg/1280px-TRT_1_logo_%282021-%29.svg.png"
                this.plot = "TRT Yayını Live Stream"
            }
        }

        // Handle PPV Streams
        if (url.startsWith("https://ppv.domains/")) {
            val streamId = url.substringAfterLast("/").toIntOrNull()
            
            val ppvRes = fetchPPVApi()
            var foundStream: PPVStream? = null
            ppvRes?.streams?.forEach { category ->
                val s = category.streams?.find { it.id == streamId }
                if (s != null) foundStream = s
            }
            
            if (foundStream == null) return null
            
            val posterUrl = foundStream?.poster?.let {
                val encoded = android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                "$mainUrl/api/images/proxy/$encoded.webp"
            }
            val title = foundStream?.name ?: "Live Stream"
            
            return newLiveStreamLoadResponse(
                name = title,
                url = url,
                dataUrl = foundStream?.toJson()
            ) {
                this.posterUrl = posterUrl
                this.plot = title
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
        checkAndGetDomain()
        if (data == "https://tv-trt1.medya.trt.com.tr/master.m3u8") {
            callback.invoke(
                ExtractorLink(
                    source = "TRT",
                    name = "TRT Yayını",
                    url = data,
                    referer = "",
                    quality = Qualities.Unknown.value,
                    type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
                )
            )
            return true
        }

        // Handle PPV Extract
        try {
            val stream = AppUtils.parseJson<PPVStream>(data)
            if (stream.id != null && (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty())) {
                val iframes = mutableListOf<Pair<String, String>>()
                val mainIframe = stream.iframe ?: stream.uri_name?.let { "https://embedindia.st/embed/$it" }
                if (mainIframe != null) {
                    iframes.add(Pair("Main", mainIframe))
                }
                stream.substreams?.forEach { sub ->
                    val subIframe = sub.iframe ?: sub.uri_name?.let { "https://embedindia.st/embed/$it" }
                    if (subIframe != null) {
                        val name = sub.source_tag ?: sub.name ?: sub.locale ?: "Substream"
                        iframes.add(Pair(name, subIframe))
                    }
                }
                
                iframes.forEach { (name, iframeUrl) ->
                    loadExtractor(iframeUrl, "https://embedindia.st/", subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = "PPV",
                                name = "PPV - $name",
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
        } catch (e: Exception) {}

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

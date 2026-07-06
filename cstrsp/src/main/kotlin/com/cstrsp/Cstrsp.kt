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
    private val streamfreeUrl = "https://streamfree.top"
    private var isDomainChecked = false
    private val domains = listOf("https://streamed.pk", "https://streamed.st")

    private suspend fun checkAndGetDomain() {
        if (isDomainChecked) return
        for (domain in domains) {
            try {
                val response = app.get("$domain/api/matches/live")
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
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("date") val date: Long? = null,
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
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("badge") val badge: String? = null
    )

    data class APISource(
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("id") val id: String? = null
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

    // cdnlivetv.tv (StreamSports)
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

    // The "cdn-live-tv" object mixes sport arrays (e.g. "Soccer": [...]) with scalar
    // metadata ("total_events_soccer": 793, "cached": true, "timestamp": ...), so it
    // can't be typed as Map<String, List<CdnEvent>>. Parse loosely and keep list values.
    data class CdnResponse(
        @JsonProperty("cdn-live-tv") val data: Map<String, Any?>? = null
    )

    // streamfree.top (StreamFree)
    data class SFStream(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("league") val league: String? = null,
        @JsonProperty("stream_key") val streamKey: String? = null,
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null
    )

    data class SFResponse(
        @JsonProperty("streams") val streams: List<SFStream>? = null
    )

    // streamfree.top builds its m3u8 in JS from three pieces: a token map embedded in
    // the embed page (_0x), the current server from /get-stream-key, and the available
    // quality from /api/stream-status. A headless WebView can't complete the player
    // init chain, so we replicate that construction directly.
    data class SFStreamKey(
        @JsonProperty("is_external") val isExternal: Boolean? = false,
        @JsonProperty("external_url") val externalUrl: String? = null,
        @JsonProperty("server_name") val serverName: String? = null
    )

    data class SFStatus(
        @JsonProperty("qualities") val qualities: Map<String, Boolean>? = null
    )

    data class SFToken(
        @JsonProperty("_t") val t: String? = null,
        @JsonProperty("_e") val e: Long? = null,
        @JsonProperty("_n") val n: String? = null
    )

    private val ppvDomains = listOf("api.ppv.to", "api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx")

    private suspend fun fetchPPVApi(): PPVResponse? {
        for (domain in ppvDomains) {
            try {
                val url = "https://$domain/api/streams"
                val res = app.get(url).parsedSafe<PPVResponse>()
                if (res?.streams != null) return res
            } catch (e: Exception) {}
        }
        return null
    }

    private suspend fun fetchWFMatches(): List<WFMatch>? {
        return try {
            app.get("https://api.watchfooty.st/api/v1/matches/all").parsedSafe<Array<WFMatch>>()?.toList()
        } catch (e: Exception) {
            null
        }
    }

    // Returns sport -> events, keeping only events that currently have a playable channel.
    private suspend fun fetchCdnEvents(): Map<String, List<CdnEvent>> {
        val res = try {
            app.get("$cdnApiUrl/events/sports/?user=cdnlivetv&plan=free").parsedSafe<CdnResponse>()
        } catch (e: Exception) {
            null
        } ?: return emptyMap()

        val out = LinkedHashMap<String, List<CdnEvent>>()
        res.data?.forEach { (sport, value) ->
            val raw = value as? List<*> ?: return@forEach // skip scalar metadata keys
            val events = raw.mapNotNull { el ->
                try { AppUtils.parseJson<CdnEvent>((el ?: return@mapNotNull null).toJson()) } catch (e: Exception) { null }
            }.filter { ev -> ev.gameID != null && ev.channels?.any { !it.url.isNullOrBlank() } == true }
            if (events.isNotEmpty()) out[sport] = events
        }
        return out
    }

    private suspend fun fetchSFStreams(): List<SFStream> {
        return try {
            app.get("$streamfreeUrl/api/v1/streams").parsedSafe<SFResponse>()?.streams
                ?.filter { it.name != null && it.embedUrl != null && it.streamKey != null } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun sfQuality(q: String): Int = when (q) {
        "2160p" -> Qualities.P2160.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "540p" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    // Resolves a StreamFree embed to a playable link. Returns (url, isM3u8): a direct
    // m3u8 (isM3u8 = true) for hosted streams, or an external embed url to hand off to
    // loadExtractor (isM3u8 = false). Null if the stream isn't currently live.
    private suspend fun resolveStreamFree(key: String, embedUrl: String): Triple<String, Boolean, String>? {
        val ref = "$streamfreeUrl/"
        val sk = try {
            app.get("$streamfreeUrl/get-stream-key/$key", referer = ref).parsedSafe<SFStreamKey>()
        } catch (e: Exception) {
            null
        } ?: return null

        if (sk.isExternal == true && sk.externalUrl != null) return Triple(sk.externalUrl, false, "720p")

        val qualities = try {
            app.get("$streamfreeUrl/api/stream-status/$key", referer = ref).parsedSafe<SFStatus>()?.qualities
        } catch (e: Exception) {
            null
        }.orEmpty()
        // Match the page's getBestQuality() preference order.
        val quality = listOf("720p", "1080p", "2160p", "540p").firstOrNull { qualities[it] == true } ?: "720p"

        val tokens = try {
            val html = app.get(embedUrl, referer = ref).text
            val json = Regex("const _0x = (\\{.*?\\});").find(html)?.groupValues?.get(1) ?: return null
            AppUtils.parseJson<Map<String, SFToken>>(json)
        } catch (e: Exception) {
            return null
        }
        val token = tokens[quality] ?: return null
        if (token.t == null || token.e == null || token.n == null) return null

        val prefix = if ((sk.serverName ?: "origin") != "origin") "live-cdn" else "live"
        val url = "$streamfreeUrl/$prefix/$key$quality/index.m3u8?_t=${token.t}&_e=${token.e}&_n=${token.n}"
        // Only emit if the playlist is actually live (upcoming events 404 here).
        return try {
            if (app.get(url, referer = ref).text.contains("#EXTM3U")) Triple(url, true, quality) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun streamedPoster(match: APIMatch): String? = when {
        match.poster != null -> "$mainUrl${match.poster}"
        match.teams?.home?.badge != null -> "$apiUrl/images/badge/${match.teams.home.badge}.webp"
        else -> null
    }

    private fun cdnTitle(event: CdnEvent): String =
        event.event ?: listOfNotNull(event.homeTeam, event.awayTeam).joinToString(" vs ").ifBlank { "Live Event" }

    private fun cdnPoster(event: CdnEvent): String? = event.homeTeamImg ?: event.eventImg

    // Helper to fetch matches from streamed.pk
    private suspend fun fetchMatches(endpoint: String): List<APIMatch> {
        return try {
            app.get(endpoint).parsedSafe<Array<APIMatch>>()?.toList()?.filter { it.id != null && it.title != null } ?: emptyList()
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
                val id = match.id ?: return@mapNotNull null
                val title = match.title ?: return@mapNotNull null
                newLiveSearchResponse(title, "$mainUrl/match/$id") {
                    this.posterUrl = streamedPoster(match)
                }
            }
            if (list.isNotEmpty()) {
                val catName = category ?: "Other"
                homePageLists.add(HomePageList("${catName.replaceFirstChar { it.uppercase() }} [Streamed]", list))
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
            e.printStackTrace()
        }

        // Handle WF Streams
        try {
            val wfMatches = fetchWFMatches()
            wfMatches?.groupBy { it.sport ?: "Unknown" }?.forEach { (sport, matches) ->
                val matchList = mutableListOf<SearchResponse>()
                matches.forEach { match ->
                    if (match.matchId != null && !match.streams.isNullOrEmpty()) {
                        val title = match.title ?: "Live Event"
                        val posterUrl = match.poster?.let { "https://api.watchfooty.st$it" }
                        matchList.add(
                            newLiveSearchResponse(
                                name = "$title [WF]",
                                url = "https://wf.domains/${match.matchId}"
                            ) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
                if (matchList.isNotEmpty()) {
                    homePageLists.add(HomePageList("${sport.replaceFirstChar { it.uppercase() }} [WF]", matchList))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // StreamSports (cdnlivetv.tv) Backup Source
        try {
            fetchCdnEvents().forEach { (sport, events) ->
                val matchList = events.mapNotNull { event ->
                    val gameId = event.gameID ?: return@mapNotNull null
                    newLiveSearchResponse("${cdnTitle(event)} [StreamSports]", "https://cdn.domains/$gameId") {
                        this.posterUrl = cdnPoster(event)
                    }
                }
                if (matchList.isNotEmpty()) {
                    homePageLists.add(HomePageList("${sport.replaceFirstChar { it.uppercase() }} [StreamSports]", matchList))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // StreamFree (streamfree.top) Backup Source
        try {
            fetchSFStreams().groupBy { it.category ?: "Other" }.forEach { (category, streams) ->
                val matchList = streams.mapNotNull { stream ->
                    val key = stream.streamKey ?: return@mapNotNull null
                    newLiveSearchResponse("${stream.name} [StreamFree]", "https://sf.domains/$key") {
                        this.posterUrl = stream.thumbnailUrl
                    }
                }
                if (matchList.isNotEmpty()) {
                    homePageLists.add(HomePageList("${category.replaceFirstChar { it.uppercase() }} [StreamFree]", matchList))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            val title = match.title ?: return@filter false
            queryParts.all { title.contains(it, ignoreCase = true) }
        }.mapNotNull { match ->
            val id = match.id ?: return@mapNotNull null
            val title = match.title ?: return@mapNotNull null
            newLiveSearchResponse(title, "$mainUrl/match/$id") {
                this.posterUrl = streamedPoster(match)
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

        // Handle WF Streams search
        try {
            val wfMatches = fetchWFMatches()
            wfMatches?.forEach { match ->
                val title = match.title ?: "Live Event"
                if (title.contains(query, ignoreCase = true) && match.matchId != null && !match.streams.isNullOrEmpty()) {
                    val posterUrl = match.poster?.let { "https://api.watchfooty.st$it" }
                    results.add(
                        newLiveSearchResponse(
                            name = "$title [WF]",
                            url = "https://wf.domains/${match.matchId}"
                        ) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Search StreamSports (cdnlivetv)
        try {
            fetchCdnEvents().values.flatten().forEach { event ->
                val gameId = event.gameID ?: return@forEach
                val title = cdnTitle(event)
                if (title.contains(query, ignoreCase = true)) {
                    results.add(
                        newLiveSearchResponse("$title [StreamSports]", "https://cdn.domains/$gameId") {
                            this.posterUrl = cdnPoster(event)
                        }
                    )
                }
            }
        } catch (e: Exception) {}

        // Search StreamFree
        try {
            fetchSFStreams().forEach { stream ->
                val key = stream.streamKey ?: return@forEach
                val title = stream.name ?: return@forEach
                if (title.contains(query, ignoreCase = true)) {
                    results.add(
                        newLiveSearchResponse("$title [StreamFree]", "https://sf.domains/$key") {
                            this.posterUrl = stream.thumbnailUrl
                        }
                    )
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
                name = "$title [PPV]",
                url = url,
                dataUrl = foundStream!!.toJson()
            ) {
                this.posterUrl = posterUrl
                this.plot = title
            }
        }

        // Handle WF Streams
        if (url.startsWith("https://wf.domains/")) {
            val matchId = url.substringAfterLast("/")
            val wfMatches = fetchWFMatches()
            val match = wfMatches?.find { it.matchId == matchId } ?: return null
            
            val posterUrl = match.poster?.let { "https://api.watchfooty.st$it" }
            val title = match.title ?: "Live Stream"

            return newLiveStreamLoadResponse(
                name = "$title [WF]",
                url = url,
                dataUrl = match.toJson()
            ) {
                this.posterUrl = posterUrl
                this.plot = title
            }
        }

        // Handle StreamSports (cdnlivetv) Streams
        if (url.startsWith("https://cdn.domains/")) {
            val gameId = url.substringAfterLast("/")
            val event = fetchCdnEvents().values.flatten().find { it.gameID == gameId } ?: return null
            val title = cdnTitle(event)
            return newLiveStreamLoadResponse(
                name = "$title [StreamSports]",
                url = url,
                dataUrl = event.toJson()
            ) {
                this.posterUrl = cdnPoster(event)
                this.plot = title
            }
        }

        // Handle StreamFree Streams
        if (url.startsWith("https://sf.domains/")) {
            val key = url.substringAfterLast("/")
            val stream = fetchSFStreams().find { it.streamKey == key } ?: return null
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

        val posterUrl = streamedPoster(match)

        val sourceNames = match.sources?.mapNotNull { it.source }?.joinToString(", ") { src ->
            src.replaceFirstChar { it.uppercase() }
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

        // Handle WF Extract
        try {
            val match = AppUtils.parseJson<WFMatch>(data)
            if (match.matchId != null && !match.streams.isNullOrEmpty()) {
                // HD only. Also cap the count: a match can list 90+ feeds and each spawns
                // a WebView extractor. Prefer the match-specific sources over the "prime"
                // channel dump, whose per-channel feeds sometimes carry an unrelated event.
                val streams = match.streams
                    .filter { it.url != null && !"SD".equals(it.quality, ignoreCase = true) }
                    .sortedBy { it.source == "prime" }
                    .take(15)
                streams.forEach { stream ->
                    val name = listOfNotNull(stream.source, stream.quality, stream.language).joinToString(" - ")
                    loadExtractor(stream.url!!, "https://api.watchfooty.st/", subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = "WF",
                                name = "WF - $name",
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

        // Handle StreamSports (cdnlivetv) Extract
        try {
            val event = AppUtils.parseJson<CdnEvent>(data)
            if (event.gameID != null && !event.channels.isNullOrEmpty()) {
                // Cap channels: an event can carry 100+, and each spawns a WebView extractor.
                val channels = event.channels.filter { !it.url.isNullOrBlank() }.take(8)
                channels.forEach { channel ->
                    val chName = channel.channelName ?: "Channel"
                    loadExtractor(channel.url!!, "https://cdnlivetv.tv/", subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = "StreamSports",
                                name = "StreamSports - $chName",
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

        // Handle StreamFree Extract
        try {
            val stream = AppUtils.parseJson<SFStream>(data)
            val key = stream.streamKey
            if (stream.embedUrl != null && key != null) {
                val resolved = resolveStreamFree(key, stream.embedUrl)
                if (resolved != null) {
                    val (streamUrl, isM3u8, quality) = resolved
                    val label = "StreamFree - ${stream.name ?: "Live"}"
                    if (isM3u8) {
                        callback(
                            ExtractorLink(
                                source = "StreamFree",
                                name = label,
                                url = streamUrl,
                                referer = "$streamfreeUrl/",
                                quality = sfQuality(quality),
                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                headers = mapOf("Referer" to "$streamfreeUrl/")
                            )
                        )
                    } else {
                        // External embed on another host: hand off to the extractors.
                        loadExtractor(streamUrl, "$streamfreeUrl/", subtitleCallback) { link ->
                            callback(
                                ExtractorLink(
                                    source = "StreamFree",
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
                    // HD only (drop SD streams).
                    val streams = app.get("$apiUrl/stream/${source.source}/${source.id}")
                        .parsedSafe<Array<APIStream>>()?.toList()?.filter { it.hd } ?: emptyList()

                    for (stream in streams) {
                        try {
                            val langStr = stream.language ?: "Unknown"
                            val quality = Qualities.P1080.value
                            val sourceName = source.source?.replaceFirstChar { it.uppercase() } ?: "Unknown"
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

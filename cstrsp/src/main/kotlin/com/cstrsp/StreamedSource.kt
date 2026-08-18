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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("streamNo") val streamNo: Int? = null,
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("hd") val hd: Boolean = false,
    @JsonProperty("embedUrl") val embedUrl: String? = null,
    @JsonProperty("source") val source: String? = null
)

object StreamedSource {
    var mainUrl = "https://streamed.pk"
    var apiUrl = "https://streamed.pk/api"

    @Volatile private var isDomainChecked = false
    private val domainMutex = Mutex()
    private const val DOMAIN_PROBE_TIMEOUT_MS = 6_000L

    private val domains = listOf(
        "https://streamed.pk", "https://streamed.st", "https://streamed.is", "https://streamed.to", "https://streamed.cx"
    )

    fun streamedApiCandidates(endpoint: String): List<String> {
        val suffix = endpoint.substringAfter("/api", "")
        if (suffix.isBlank()) return listOf(endpoint)
        return (listOf(endpoint) + domains.map { "$it/api$suffix" }).distinct()
    }

    suspend fun checkAndGetDomain() {
        if (isDomainChecked) return
        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val winner = withTimeoutOrNull(DOMAIN_PROBE_TIMEOUT_MS) {
                coroutineScope {
                    val results = Channel<Pair<String, List<APIMatch>?>>(domains.size)
                    val jobs = domains.map { domain ->
                        launch {
                            val parsed = try {
                                val response = app.get("$domain/api/matches/live")
                                if (response.code !in 200..299) null
                                else response.parsedSafe<Array<APIMatch>>()?.toList()
                                    ?.filter { it.id != null && it.title != null }
                            } catch (e: Exception) {
                                null
                            }
                            results.send(domain to parsed)
                        }
                    }
                    var firstHealthy: Pair<String, List<APIMatch>?>? = null
                    repeat(domains.size) {
                        val result = results.receive()
                        if (result.second != null) {
                            firstHealthy = firstHealthy ?: result
                            if (result.second!!.isNotEmpty()) {
                                jobs.forEach { it.cancel() }
                                return@coroutineScope result
                            }
                        }
                    }
                    firstHealthy
                }
            }

            val domain = winner?.first ?: domains.first()
            mainUrl = domain
            apiUrl = "$domain/api"
            winner?.second?.let { CstrspCache.putCache("$apiUrl/matches/live", it) }
            isDomainChecked = true
        }
    }

    suspend fun fetchMatches(endpoint: String): List<APIMatch> = CstrspCache.cached(endpoint) {
        var empty: List<APIMatch>? = null
        for (candidate in streamedApiCandidates(endpoint)) {
            val parsed = try {
                app.get(candidate).parsedSafe<Array<APIMatch>>()?.toList()
                    ?.filter { it.id != null && it.title != null }
            } catch (e: Exception) {
                null
            }
            if (parsed == null) continue
            if (parsed.isNotEmpty()) return@cached parsed
            empty = empty ?: parsed
        }
        empty
    } ?: emptyList()

    private suspend fun fetchStreams(source: APISource): List<APIStream> {
        val sourceName = source.source ?: return emptyList()
        val sourceId = source.id ?: return emptyList()
        val path = "/stream/$sourceName/$sourceId"
        var empty: List<APIStream>? = null
        for (candidate in streamedApiCandidates("$apiUrl$path")) {
            val parsed = try {
                app.get(candidate).parsedSafe<Array<APIStream>>()?.toList()
            } catch (e: Exception) {
                null
            }
            if (parsed == null) continue
            if (parsed.isNotEmpty()) return parsed
            empty = empty ?: parsed
        }
        return empty.orEmpty()
    }

    fun streamedPoster(match: APIMatch): String? = when {
        match.poster != null -> "$mainUrl${match.poster}"
        match.teams?.home?.badge != null -> "$apiUrl/images/badge/${match.teams.home.badge}.webp"
        else -> null
    }

    suspend fun MainAPI.getHomeSections(): List<HomePageList> =
        fetchMatches("$apiUrl/matches/live")
            .groupBy { it.category ?: "Other" }
            .mapNotNull { (category, matches) ->
                val items = matches.mapNotNull { match ->
                    val id = match.id ?: return@mapNotNull null
                    val title = match.title ?: return@mapNotNull null
                    newLiveSearchResponse(title, "$mainUrl/match/$id") {
                        this.posterUrl = streamedPoster(match)
                    }
                }
                if (items.isEmpty()) null
                else HomePageList("${category.replaceFirstChar { it.uppercase() }} [Streamed]", items)
            }

    suspend fun MainAPI.search(matcher: QueryMatcher): List<SearchResponse> {
        val live = fetchMatches("$apiUrl/matches/live")
        val futureCutoff = System.currentTimeMillis() + 12 * 3_600_000L
        val allToday = fetchMatches("$apiUrl/matches/all-today")
            .filter { it.date == null || it.date < futureCutoff }
        val seen = HashSet<String>()
        return (live + allToday)
            .filter { m ->
                m.id != null && m.title != null && seen.add(m.id) &&
                    matcher.matches(m.title, m.category, m.teams?.home?.name, m.teams?.away?.name)
            }
            .mapNotNull { match ->
                val id = match.id ?: return@mapNotNull null
                val title = match.title ?: return@mapNotNull null
                newLiveSearchResponse(title, "$mainUrl/match/$id") {
                    this.posterUrl = streamedPoster(match)
                }
            }
    }

    suspend fun MainAPI.load(url: String): LoadResponse? {
        val matchId = url.substringAfterLast("/")
        var match = fetchMatches("$apiUrl/matches/live").find { it.id == matchId }
        var isLive = true

        if (match == null) {
            match = fetchMatches("$apiUrl/matches/all-today").find { it.id == matchId }
            isLive = false
        }

        if (match == null) return null

        val sourceNames = match.sources?.mapNotNull { it.source }?.sorted()?.joinToString(", ") { src ->
            src.replaceFirstChar { it.uppercase() }
        } ?: ""
        val sourceLabel = if (sourceNames.isNotEmpty()) " [$sourceNames]" else ""
        val liveLabel = if (!isLive) " [Upcoming]" else ""

        return newLiveStreamLoadResponse(
            name = "${match.title}$sourceLabel$liveLabel",
            url = url,
            dataUrl = (match.sources ?: emptyList<APISource>()).toJson()
        ) {
            this.posterUrl = streamedPoster(match)
            this.plot = if (isLive) "Live stream for ${match.title}" else "Upcoming: ${match.title}"
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = try {
            AppUtils.parseJson<List<APISource>>(data)
        } catch (e: Exception) {
            return false
        }

        val embeds = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        val available = fetchStreams(source)
                        val preferred = available.sortedByDescending { it.hd }.take(4)
                        preferred.mapNotNull { stream ->
                            stream.embedUrl?.takeIf { it.isNotBlank() }?.let { source to stream }
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        embeds.resolveConcurrently { (source, stream) ->
            val langStr = stream.language ?: "Unknown"
            val sourceName = source.source?.replaceFirstChar { it.uppercase() } ?: "Unknown"
            val hdTag = if (stream.hd) " [HD]" else " [SD]"
            val base = "$sourceName - $langStr - Stream ${stream.streamNo ?: "?"}$hdTag"
            loadExtractor(encodeUrlNonAscii(stream.embedUrl!!), "$mainUrl/", subtitleCallback) { link ->
                val resolvedQuality = when {
                    link.quality != Qualities.Unknown.value -> link.quality
                    stream.hd -> Qualities.P1080.value
                    else -> Qualities.P480.value
                }
                val labeled = withQualityLabel(base, resolvedQuality)
                callback.invoke(
                    ExtractorLink(
                        source = labeled,
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
}

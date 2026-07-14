package com.cstrsp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val CACHE_TTL_MS = 30_000L
private const val TRT_URL = "https://tv-trt1.medya.trt.com.tr/master.m3u8"
private const val TRT_POSTER =
    "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/TRT_1_logo_%282021-%29.svg/1280px-TRT_1_logo_%282021-%29.svg.png"

class Cstrsp : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "cstrsp"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    private var apiUrl = "https://streamed.pk/api"
    private val cdnApiUrl = "https://api.cdnlivetv.tv/api/v1"
    private val roxieUrl = "https://roxiestreams.su"
    // Last roxie CDN host that served a valid playlist. The site rotates m3u8 across a pool
    // of domains (domainsz56.txt) and only some answer at any moment (the rest are
    // Cloudflare-blocked), so we remember a working one and try it first next time.
    @Volatile private var roxieGoodDomain: String? = null
    private var isDomainChecked = false
    private val domains = listOf("https://streamed.pk", "https://streamed.st")

    // Short-lived response cache. A normal flow (home/search -> click -> play) hits the
    // same upstream list APIs three times within seconds; only the first call should pay
    // the network round-trip. Failures are never cached, so a flaky source retries on the
    // next call instead of negative-caching for the TTL.
    private val cacheMutex = Mutex()
    private val apiCache = HashMap<String, Pair<Long, Any>>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, fetch: suspend () -> T?): T? {
        cacheMutex.withLock {
            apiCache[key]?.let { (at, value) ->
                if (System.currentTimeMillis() - at < CACHE_TTL_MS) return value as T
            }
        }
        val fresh = try { fetch() } catch (e: Exception) { null } ?: return null
        cacheMutex.withLock { apiCache[key] = System.currentTimeMillis() to fresh }
        return fresh
    }

    private suspend fun putCache(key: String, value: Any) {
        cacheMutex.withLock { apiCache[key] = System.currentTimeMillis() to value }
    }

    private suspend fun checkAndGetDomain() {
        if (isDomainChecked) return
        for (domain in domains) {
            try {
                val response = app.get("$domain/api/matches/live")
                if (response.code in 200..299) {
                    mainUrl = domain
                    apiUrl = "$domain/api"
                    isDomainChecked = true
                    // The probe body IS the live-matches list; seed the cache so the
                    // fetch that immediately follows doesn't repeat the same request.
                    response.parsedSafe<Array<APIMatch>>()?.toList()
                        ?.filter { it.id != null && it.title != null }
                        ?.let { putCache("$apiUrl/matches/live", it) }
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
        // Unix seconds of the event window, plus a 24-7 flag (int 0/1 at stream level).
        // Used to keep not-yet-started/finished PPV events out of live results.
        @JsonProperty("starts_at") val startsAt: Long? = null,
        @JsonProperty("ends_at") val endsAt: Long? = null,
        @JsonProperty("always_live") val alwaysLive: Int? = null,
        @JsonProperty("substreams") val substreams: List<PPVSubstream>? = null
    )

    data class PPVCategory(
        @JsonProperty("category_name") val category_name: String? = null,
        @JsonProperty("category") val category: String? = null,
        // Category-level 24-7 flag is a JSON boolean (unlike the stream-level int).
        @JsonProperty("always_live") val alwaysLive: Boolean? = null,
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

    // roxiestreams.su has no API — it's server-rendered HTML. An event is a row in the
    // homepage "Upcoming Events" table; `path` is the relative link to its stream page.
    data class RoxieEvent(
        @JsonProperty("name") val name: String,
        @JsonProperty("path") val path: String
    )

    // One playable button on an event page. Two kinds:
    //  - "m3u8": a getRandomStream(streamPath, subdomain) call → a direct playlist at
    //    https://<subdomain>.<rotating-domain>/<streamPath>.
    //  - "raw":  a playIframePlayer('/raw/x') call → an embed page we WebView-sniff.
    data class RoxieSource(
        @JsonProperty("label") val label: String,
        @JsonProperty("kind") val kind: String,
        @JsonProperty("value") val value: String,
        @JsonProperty("subdomain") val subdomain: String = ""
    )

    // Carried in dataUrl from load() to loadLinks(): the event page path plus its scraped
    // buttons, so loadLinks resolves them without re-fetching the page.
    data class RoxieLoadData(
        @JsonProperty("name") val name: String,
        @JsonProperty("path") val path: String,
        @JsonProperty("sources") val sources: List<RoxieSource>
    )

    private val ppvDomains = listOf("api.ppv.to", "api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx")

    private suspend fun fetchPPVApi(): PPVResponse? = cached("ppv") {
        ppvDomains.firstNotNullOfOrNull { domain ->
            try {
                app.get("https://$domain/api/streams").parsedSafe<PPVResponse>()?.takeIf { it.streams != null }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun fetchWFMatches(): List<WFMatch> = cached("wf") {
        app.get("https://api.watchfooty.st/api/v1/matches/all").parsedSafe<Array<WFMatch>>()?.toList()
    } ?: emptyList()

    // Returns sport -> events, keeping only events that currently have a playable channel.
    private suspend fun fetchCdnEvents(): Map<String, List<CdnEvent>> = cached("cdn") {
        val res = app.get("$cdnApiUrl/events/sports/?user=cdnlivetv&plan=free").parsedSafe<CdnResponse>()
            ?: return@cached null
        val out = LinkedHashMap<String, List<CdnEvent>>()
        res.data?.forEach { (sport, value) ->
            val raw = value as? List<*> ?: return@forEach // skip scalar metadata keys
            // One serialize->parse round-trip for the whole sport array (they can hold
            // 1000+ events) instead of one per event.
            val events = try {
                AppUtils.parseJson<Array<CdnEvent>>(raw.toJson()).toList()
            } catch (e: Exception) {
                emptyList()
            }.filter { ev -> ev.gameID != null && ev.channels?.any { !it.url.isNullOrBlank() } == true }
            if (events.isNotEmpty()) out[sport] = events
        }
        pruneAmbiguousCdnChannels(out)
    } ?: emptyMap()

    // Many cdnlivetv channels (ESPN US, Sky Sports F1, Premiere BR...) are attached to dozens
    // of events at once. The underlying stream is the channel's *current* live broadcast, so a
    // channel listed under more than one currently-live event can't be pinned to this one and
    // would often resolve to an unrelated event's stream — the "unrelated sources" bug. Drop a
    // channel when it's shared by 2+ live events, keeping channels exclusive to a single live
    // event. When only one event is live nothing collides, so nothing is dropped.
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
                // Null-named channels can't be judged, so keep them.
                .map { ev -> ev.copy(channels = ev.channels?.filter { it.channelName == null || it.channelName !in ambiguous }) }
                .filter { ev -> ev.channels?.any { !it.url.isNullOrBlank() } == true }
            if (kept.isNotEmpty()) cleaned[sport] = kept
        }
        return cleaned
    }

    // Helper to fetch matches from streamed.pk
    private suspend fun fetchMatches(endpoint: String): List<APIMatch> = cached(endpoint) {
        app.get(endpoint).parsedSafe<Array<APIMatch>>()?.toList()?.filter { it.id != null && it.title != null }
    } ?: emptyList()

    // An event row in the homepage table: <td><a href="/path">Name</a></td>. Scoped to the
    // events table (see fetchRoxieEvents) so it never picks up the nav's category links.
    private val roxieEventRowRegex = Regex("href=\"(/[^\"]+)\"[^>]*>([^<]+)</a>")
    // A player button and its onclick handler.
    private val roxieButtonRegex = Regex("<button[^>]*onclick=\"([^\"]*)\"[^>]*>(.*?)</button>", RegexOption.DOT_MATCHES_ALL)
    // getRandomStream('tsn.m3u8', 'admin2') — second arg optional (JS default 'ataide0').
    private val roxieGetStreamRegex = Regex("getRandomStream\\(\\s*'([^']+)'(?:\\s*,\\s*'([^']+)')?\\s*\\)")
    // playIframePlayer('/raw/ctv')
    private val roxieRawRegex = Regex("playIframePlayer\\(\\s*'([^']+)'\\s*\\)")
    private val roxieTagRegex = Regex("<[^>]+>")

    // The homepage "Upcoming Events" table, one card per row. Cached; shared by home/search/load.
    private suspend fun fetchRoxieEvents(): List<RoxieEvent> = cached("roxie-events") {
        val html = app.get("$roxieUrl/", referer = "$roxieUrl/").text
        val table = html.substringAfter("id=\"eventsTable\"", "").substringBefore("</table>", "")
        if (table.isBlank()) return@cached null
        roxieEventRowRegex.findAll(table)
            .map { RoxieEvent(it.groupValues[2].trim(), it.groupValues[1].trim()) }
            .filter { it.name.isNotBlank() && it.path.isNotBlank() }
            .distinctBy { it.path }
            .toList()
            .takeIf { it.isNotEmpty() }
    } ?: emptyList()

    // The CDN host pool for direct m3u8 playback (newline-separated, with duplicates).
    private suspend fun fetchRoxieDomains(): List<String> = cached("roxie-domains") {
        app.get("$roxieUrl/domainsz56.txt", referer = "$roxieUrl/").text
            .lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .takeIf { it.isNotEmpty() }
    } ?: emptyList()

    // Scrapes one event page's player buttons into resolvable sources.
    private suspend fun fetchRoxieSources(path: String): List<RoxieSource> = cached("roxie-src-$path") {
        val html = app.get("$roxieUrl$path", referer = "$roxieUrl/").text
        roxieButtonRegex.findAll(html).mapNotNull { m ->
            val onclick = m.groupValues[1]
            val label = roxieTagRegex.replace(m.groupValues[2], "").trim().ifBlank { "Stream" }
            roxieGetStreamRegex.find(onclick)?.let {
                return@mapNotNull RoxieSource(label, "m3u8", it.groupValues[1], it.groupValues[2].ifBlank { "ataide0" })
            }
            roxieRawRegex.find(onclick)?.let {
                return@mapNotNull RoxieSource(label, "raw", it.groupValues[1])
            }
            null
        }.toList().takeIf { it.isNotEmpty() }
    } ?: emptyList()

    // Resolves an m3u8 source to a playable playlist url, or null. Tries the last-known-good
    // domain first, then the rest, accepting the first that returns a real HLS playlist —
    // Cloudflare-blocked domains answer 403 HTML, so a status/body check is required.
    private suspend fun resolveRoxieM3u8(source: RoxieSource, domains: List<String>): String? {
        for (domain in (listOfNotNull(roxieGoodDomain) + domains).distinct()) {
            val url = "https://${source.subdomain}.$domain/${source.value}"
            try {
                val res = app.get(url, referer = "$roxieUrl/", headers = mapOf("Origin" to roxieUrl))
                if (res.code == 200 && res.text.trimStart().startsWith("#EXTM")) {
                    roxieGoodDomain = domain
                    return url
                }
            } catch (e: Exception) {}
        }
        return null
    }

    // Event names carry spaces/parentheses, so key the card url by a url-safe encoding of the
    // event page path rather than the raw text.
    private fun roxieKey(path: String): String = android.util.Base64.encodeToString(
        path.toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
    )

    private fun roxiePathFromKey(key: String): String? = try {
        String(
            android.util.Base64.decode(key, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING),
            Charsets.UTF_8
        )
    } catch (e: Exception) {
        null
    }

    // Shorter display dimension (video height when played in landscape). Used to cap the
    // quality *variants* we offer to what the device can render. Int.MAX_VALUE on failure so
    // a lookup miss never hides anything.
    private fun deviceMaxHeight(): Int = try {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        minOf(dm.widthPixels, dm.heightPixels)
    } catch (_: Exception) { Int.MAX_VALUE }

    // Appends the resolved quality to a source label, e.g. "WF - alpha" -> "WF - alpha (1080p)",
    // using CloudStream's own int->label mapping so the name matches the quality badge exactly.
    // No-op when the quality is unknown, so we never fabricate a resolution we didn't detect.
    // The device-resolution cap is enforced as a filter in loadLinks, i.e. by dropping
    // above-device variants — NOT by relabeling, which used to mislabel genuine 1080p
    // streams as 720p on 720p panels. Labels always show the real stream.
    private fun withQualityLabel(base: String, quality: Int): String {
        val label = Qualities.getStringByInt(quality)
        return if (label.isEmpty()) base else "$base ($label)"
    }

    // WF's quality field is typically "HD" or "SD". "HD" in standard broadcast terminology
    // means 720p (Full HD / FHD is 1080p), so we map accordingly. When the extractor
    // detects the actual resolution from the playlist, that takes priority over this hint.
    private fun wfQuality(q: String?): Int = when (q?.trim()?.lowercase()) {
        "1080p" -> Qualities.P1080.value
        "hd" -> Qualities.P720.value
        "sd" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    // True when a WF match has at least one playable non-SD stream — i.e. a link that
    // loadLinks would actually keep. Matches whose only streams are SD (or that have no
    // usable streams) are hidden from search/home, since they'd resolve to nothing HD.
    // Mirrors the exact filter used in the WF branch of loadLinks so listing and playback
    // never disagree.
    private fun wfHasHd(match: WFMatch): Boolean =
        match.streams?.any { it.url != null && !"SD".equals(it.quality?.trim(), ignoreCase = true) } == true

    // --- "Is this event live right now?" per source ------------------------------
    // Search (and home) should only surface events that are actually in progress, not
    // scheduled/upcoming or finished ones. Each upstream exposes a different live signal.

    // WF status is "in" while a match is in progress; "pre" (upcoming), "post*"/finished,
    // "postponed"/"canceled"/"interrupted" are not playable-live. Accept "in" (and "live"
    // defensively); note "interrupted" must NOT be treated as live even though it starts
    // with "in", so this is an exact match, not a prefix.
    private fun isLiveWf(match: WFMatch): Boolean =
        match.status?.trim()?.lowercase().let { it == "in" || it == "live" }

    // cdnlivetv event status uses short codes: "NS" (not started/upcoming) and "CANC"
    // (cancelled) are the non-live ones we've observed; finished/postponed codes are added
    // for safety. Anything else (in-play codes, or an unknown/blank status on a channel that
    // has a playable feed) is treated as live so we never hide a genuinely live event.
    private val cdnNotLiveStatuses = setOf(
        "ns", "tbd", "canc", "cancl", "cancelled", "canceled", "pst", "postp", "postponed",
        "abd", "abandoned", "susp", "suspended", "wo", "awd", "ft", "aet", "pen", "fin", "finished", "ended"
    )
    private fun isLiveCdn(event: CdnEvent): Boolean {
        val s = event.status?.trim()?.lowercase() ?: return true
        return s.isEmpty() || s !in cdnNotLiveStatuses
    }

    // PPV exposes a [starts_at, ends_at] window (unix seconds) plus a 24-7 flag (boolean on
    // the category, int 0/1 on the stream). A stream is live now if it's a 24-7 channel, or
    // it has started and not yet ended. Most PPV entries at any moment are future events, so
    // this is what keeps upcoming PPV out of search/home. 30-min grace after ends_at absorbs
    // overruns; missing times fall through to "shown" rather than hiding an unknown.
    private fun isLivePpv(category: PPVCategory, stream: PPVStream): Boolean {
        if (category.alwaysLive == true || (stream.alwaysLive ?: 0) == 1) return true
        val now = System.currentTimeMillis() / 1000L
        val start = stream.startsAt ?: 0L
        val end = stream.endsAt ?: 0L
        if (start > 0L && now < start) return false          // not started yet -> upcoming
        if (end > 0L && now > end + 1800L) return false        // finished (with overrun grace)
        return true
    }

    private fun streamedPoster(match: APIMatch): String? = when {
        match.poster != null -> "$mainUrl${match.poster}"
        match.teams?.home?.badge != null -> "$apiUrl/images/badge/${match.teams.home.badge}.webp"
        else -> null
    }

    private fun cdnTitle(event: CdnEvent): String =
        event.event ?: listOfNotNull(event.homeTeam, event.awayTeam).joinToString(" vs ").ifBlank { "Live Event" }

    private fun cdnPoster(event: CdnEvent): String? = event.homeTeamImg ?: event.eventImg

    // --- Per-source item builders (shared by home + search) ------------------------

    private fun ppvPoster(stream: PPVStream): String? = stream.poster?.let {
        val encoded = android.util.Base64.encodeToString(
            it.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        "$mainUrl/api/images/proxy/$encoded.webp"
    }

    // Live now, has something to play, and has an id we can find again in load().
    private fun ppvListable(category: PPVCategory, stream: PPVStream): Boolean =
        stream.id != null && isLivePpv(category, stream) &&
            (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty())

    private fun ppvItem(stream: PPVStream): SearchResponse =
        newLiveSearchResponse("${stream.name ?: "Unknown Event"} [PPV]", "https://ppv.domains/${stream.id}") {
            this.posterUrl = ppvPoster(stream)
        }

    private fun wfPoster(match: WFMatch): String? = match.poster?.let { "https://api.watchfooty.st$it" }

    private fun wfListable(match: WFMatch): Boolean =
        match.matchId != null && wfHasHd(match) && isLiveWf(match)

    private fun wfItem(match: WFMatch): SearchResponse =
        newLiveSearchResponse("${match.title ?: "Live Event"} [WF]", "https://wf.domains/${match.matchId}") {
            this.posterUrl = wfPoster(match)
        }

    private fun cdnItem(event: CdnEvent): SearchResponse =
        newLiveSearchResponse("${cdnTitle(event)} [StreamSports]", "https://cdn.domains/${event.gameID}") {
            this.posterUrl = cdnPoster(event)
        }

    private fun roxieItem(event: RoxieEvent): SearchResponse =
        newLiveSearchResponse("${event.name} [Roxie]", "https://roxie.domains/${roxieKey(event.path)}") {}

    // Resolves candidates (WebView-based loadExtractor calls) with bounded concurrency
    // instead of one at a time. Each call can take up to ~15s to time out, so resolving a
    // 15-stream list sequentially can take minutes — by then, early links from
    // short-lived/signed CDN URLs are often already stale by the time the user picks one.
    // Each item runs independently so one failure/timeout doesn't block the others.
    private suspend fun <T> List<T>.resolveConcurrently(action: suspend (T) -> Unit) = coroutineScope {
        val semaphore = Semaphore(4)
        map { item ->
            async {
                semaphore.withPermit {
                    try {
                        action(item)
                    } catch (e: Exception) {
                        // Skip this candidate; others continue independently.
                    }
                }
            }
        }.awaitAll()
        Unit
    }

    // Isolates one source: a failure yields an empty list instead of killing the others.
    private suspend fun <T> safeList(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    // --- Turkish search support ---------------------------------------------------
    // Upstream APIs only return English titles ("Spain vs Portugal"), but Turkish users
    // search using Turkish names ("İspanya"). We fold Turkish letters/case, translate
    // common Turkish nation names to English, and also try devoiced spelling variants
    // (Turkish devoices word-final b/c/d/g to p/ç/t/k, e.g. kitap/kitabı, Bağdat) so
    // slightly different spellings still match.

    private val trCountryNames = mapOf(
        "ispanya" to "spain", "portekiz" to "portugal", "almanya" to "germany",
        "fransa" to "france", "ingiltere" to "england", "italya" to "italy",
        "hollanda" to "netherlands", "belcika" to "belgium", "hirvatistan" to "croatia",
        "sirbistan" to "serbia", "polonya" to "poland", "avusturya" to "austria",
        "isvicre" to "switzerland", "isvec" to "sweden", "norvec" to "norway",
        "danimarka" to "denmark", "finlandiya" to "finland", "yunanistan" to "greece",
        "rusya" to "russia", "ukrayna" to "ukraine", "romanya" to "romania",
        "bulgaristan" to "bulgaria", "macaristan" to "hungary", "cekya" to "czech republic",
        "slovakya" to "slovakia", "slovenya" to "slovenia", "karadag" to "montenegro",
        "arnavutluk" to "albania", "iskocya" to "scotland", "galler" to "wales",
        "irlanda" to "ireland", "izlanda" to "iceland", "turkiye" to "turkey",
        "amerika" to "usa", "brezilya" to "brazil", "arjantin" to "argentina",
        "meksika" to "mexico", "kolombiya" to "colombia", "sili" to "chile",
        "peru" to "peru", "uruguay" to "uruguay", "ekvador" to "ecuador",
        "fas" to "morocco", "misir" to "egypt", "cezayir" to "algeria",
        "tunus" to "tunisia", "nijerya" to "nigeria", "senegal" to "senegal",
        "gana" to "ghana", "kamerun" to "cameroon", "japonya" to "japan",
        "katar" to "qatar", "iran" to "iran", "irak" to "iraq",
        "avustralya" to "australia", "cin" to "china", "hindistan" to "india",
        "cad" to "chad", "urdun" to "jordan", "kanada" to "canada",
        "endonezya" to "indonesia", "tayland" to "thailand", "lubnan" to "lebanon",
        "umman" to "oman", "suriye" to "syria"
    )

    private val trPhraseNames = mapOf(
        "suudi arabistan" to "saudi arabia", "guney kore" to "south korea",
        "kuzey kore" to "north korea", "guney afrika" to "south africa",
        "yeni zelanda" to "new zealand", "kuzey makedonya" to "north macedonia",
        "bosna hersek" to "bosnia", "kuzey irlanda" to "northern ireland",
        "fildisi sahili" to "ivory coast", "birlesik arap emirlikleri" to "united arab emirates"
    )

    private val devoicingPairs = listOf('t' to 'd', 'p' to 'b', 'k' to 'g')

    // ASCII-folds Turkish letters/case (İ/I/ı, ş, ğ, ü, ö, ç) so comparisons never trip
    // over dotted/dotless I or diacritics.
    private fun trFold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'İ', 'I', 'ı' -> 'i'
                    'Ş', 'ş' -> 's'
                    'Ğ', 'ğ' -> 'g'
                    'Ü', 'ü' -> 'u'
                    'Ö', 'ö' -> 'o'
                    'Ç', 'ç' -> 'c'
                    else -> c.lowercaseChar()
                }
            )
        }
        return sb.toString()
    }

    private val nonAlnumRegex = Regex("[^a-z0-9]+")

    // Turkish-folds, lowercases, and collapses every run of non-alphanumeric characters
    // (spaces, the hyphen in "motor-sports", punctuation) down to a single space, so titles,
    // category/sport hints, synonym aliases, and the query all compare on the same footing.
    private fun normalizeText(s: String): String =
        trFold(s).replace(nonAlnumRegex, " ").trim()

    private fun devoicedVariants(word: String): Set<String> {
        if (word.isEmpty()) return setOf(word)
        val out = mutableSetOf(word)
        val last = word.last()
        for ((voiceless, voiced) in devoicingPairs) {
            if (last == voiceless) out.add(word.dropLast(1) + voiced)
            if (last == voiced) out.add(word.dropLast(1) + voiceless)
        }
        return out
    }

    // Every string worth testing a title against for a single (already-folded) query word:
    // itself, its devoiced spelling variants, and the English translation if it's a known
    // Turkish nation name (checked on the base word and on the devoiced variants, so e.g.
    // both "Bağdat" and a hypothetical "Bağdad" spelling resolve the same way).
    private fun searchVariants(word: String): Set<String> {
        val variants = devoicedVariants(word).toMutableSet()
        variants.toList().forEach { v -> trCountryNames[v]?.let { variants.add(it) } }
        return variants
    }

    // --- Synonym / alias groups ---------------------------------------------------
    // Searching any alias in a group matches items whose title OR category/sport/league
    // contains any other alias in the same group, so "f1", "formula", "formula 1",
    // "grand prix" (and Turkish "yaris") all find the same events. All aliases are stored
    // pre-normalized (ascii, lowercase, Turkish-folded, punctuation -> single space) to
    // match normalizeText(). English + Turkish live together so a Turkish query resolves
    // the English upstream text.
    //
    // Two deliberate patterns:
    //  - Generic sport words (futbol, basketbol, tenis, yaris...) include the upstream
    //    *category/sport* token (football, basketball, motor sports...), so they match a
    //    whole sport even when a title is only "Team A vs Team B".
    //  - Specific competitions (f1, champions league...) stay title-scoped: they carry no
    //    broad category token, so "f1" returns Formula 1 events, not every motorsport.
    private val synonymGroups: List<Set<String>> = listOf(
        setOf("football", "soccer", "futbol"),
        setOf("basketball", "basketbol"),
        setOf("nba"),
        setOf("american football", "nfl", "amerikan futbolu"),
        setOf("baseball", "beyzbol", "mlb"),
        setOf("hockey", "ice hockey", "buz hokeyi", "hokey", "nhl"),
        setOf("tennis", "tenis"),
        setOf("volleyball", "voleybol"),
        setOf("handball", "hentbol"),
        setOf("rugby", "ragbi"),
        setOf("cricket", "kriket"),
        setOf("golf"),
        setOf("darts", "dart"),
        setOf("snooker", "billiards", "bilardo"),
        setOf("boxing", "boks"),
        setOf("mma", "ufc", "fight", "fighting", "dovus"),
        setOf("wrestling", "wwe", "gures"),
        // Motorsport — specific series stay narrow (title-scoped). "gp" is deliberately
        // omitted: as a substring it hits "motoGP", so "f1" would wrongly match MotoGP.
        setOf("f1", "formula 1", "formula1", "formula one", "formula", "grand prix"),
        setOf("motogp", "moto gp", "moto2", "moto3"),
        setOf("nascar"),
        setOf("indycar", "indy car"),
        // Turkish generic "race" -> any motorsport, via the shared category token. Bare
        // "motor" is omitted (it hits club names like "Motor Lublin"); the "motor sports"
        // category token and the one-word "motorsport(s)" spellings cover the real data.
        setOf("yaris", "yarisi", "race", "racing", "motor sports", "motorsport", "motorsports"),
        // Competitions (title-scoped).
        setOf("champions league", "ucl", "sampiyonlar ligi", "sampiyonlar"),
        setOf("europa league", "uel", "avrupa ligi"),
        setOf("conference league", "konferans ligi"),
        setOf("premier league", "epl", "premier lig", "ingiltere ligi"),
        setOf("la liga", "laliga", "ispanya ligi"),
        setOf("serie a", "italya ligi"),
        setOf("bundesliga", "almanya ligi"),
        setOf("ligue 1", "fransa ligi"),
        setOf("super lig", "super league", "turkiye ligi"),
        setOf("world cup", "dunya kupasi", "mundial"),
        setOf("euro", "euros", "european championship", "avrupa sampiyonasi"),
        // Turkish generic "cup".
        setOf("kupa", "cup")
    )

    // alias -> the full set of aliases it can expand to. If an alias appears in more than
    // one group its expansions are unioned, so it reaches every group it belongs to.
    private val synonymIndex: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        for (group in synonymGroups) for (alias in group) {
            m.getOrPut(alias) { mutableSetOf() }.addAll(group)
        }
        m
    }

    // Precomputes the query side of matching once per search — folding, phrase translation,
    // tokenization, and the variant/synonym set of every token — instead of redoing all of
    // it for each of the (possibly thousands of) candidate titles.
    //
    // A title/category blob matches when every query token matches — where a token matches
    // via its own text, its Turkish devoiced/nation variants, or a synonym group it belongs
    // to. A whole-query synonym hit short-circuits first, so multi-word aliases like
    // "grand prix" or "champions league" match even though they tokenize into pieces.
    private inner class QueryMatcher(query: String) {
        private val q: String
        private val wholeQueryAliases: Set<String>?
        private val tokenVariants: List<Set<String>>

        init {
            var s = trFold(query)
            trPhraseNames.forEach { (tr, en) -> s = s.replace(tr, en) }
            q = s.replace(nonAlnumRegex, " ").trim()
            wholeQueryAliases = synonymIndex[q]
            tokenVariants = q.split(" ").filter { it.isNotBlank() }
                .map { part -> searchVariants(part) + (synonymIndex[part] ?: emptySet()) }
        }

        // Fields = title plus any category/sport/league hints. Feeding the category in is
        // what lets generic Turkish sport words (futbol, basketbol, yaris) match a whole
        // sport even when the title is just two team names.
        fun matches(vararg fields: String?): Boolean {
            if (q.isBlank()) return true
            val hay = normalizeText(fields.filterNotNull().joinToString(" "))
            // Whole-query alias: lets multi-word aliases match without being split into tokens.
            wholeQueryAliases?.let { group -> if (group.any { hay.contains(it) }) return true }
            if (tokenVariants.isEmpty()) return true
            return tokenVariants.all { variants -> variants.any { hay.contains(it) } }
        }
    }

    // --- Home ----------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndGetDomain()
        // All five upstreams fetched concurrently — one slow or dead source no longer
        // delays the whole home page. awaitAll preserves order, so sections stay stable:
        // Streamed, PPV, WF, StreamSports, Roxie.
        val sections = coroutineScope {
            listOf(
                async { safeList { streamedHomeSections() } },
                async { safeList { ppvHomeSections() } },
                async { safeList { wfHomeSections() } },
                async { safeList { cdnHomeSections() } },
                async { safeList { roxieHomeSections() } }
            ).awaitAll()
        }.flatten()
        return newHomePageResponse(sections)
    }

    private suspend fun streamedHomeSections(): List<HomePageList> =
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

    private suspend fun ppvHomeSections(): List<HomePageList> =
        fetchPPVApi()?.streams.orEmpty().mapNotNull { category ->
            val items = category.streams.orEmpty()
                .filter { ppvListable(category, it) }
                .map { ppvItem(it) }
            if (items.isEmpty()) null
            else HomePageList("${category.category_name ?: category.category ?: "Unknown"} [PPV]", items)
        }

    private suspend fun wfHomeSections(): List<HomePageList> =
        fetchWFMatches()
            .filter { wfListable(it) }
            .groupBy { it.sport ?: "Unknown" }
            .map { (sport, matches) ->
                HomePageList("${sport.replaceFirstChar { it.uppercase() }} [WF]", matches.map { wfItem(it) })
            }

    private suspend fun cdnHomeSections(): List<HomePageList> =
        fetchCdnEvents().mapNotNull { (sport, events) ->
            val items = events.filter { isLiveCdn(it) }.map { cdnItem(it) }
            if (items.isEmpty()) null
            else HomePageList("${sport.replaceFirstChar { it.uppercase() }} [StreamSports]", items)
        }

    // roxiestreams has a small hand-curated slate, so one flat [Roxie] section is enough.
    private suspend fun roxieHomeSections(): List<HomePageList> {
        val items = fetchRoxieEvents().map { roxieItem(it) }
        return if (items.isEmpty()) emptyList() else listOf(HomePageList("Live Events [Roxie]", items))
    }

    // --- Search ----------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndGetDomain()
        val matcher = QueryMatcher(query)
        val results = mutableListOf<SearchResponse>()

        results.add(
            newLiveSearchResponse(name = "TRT Yayını", url = TRT_URL) {
                this.posterUrl = TRT_POSTER
            }
        )

        // Same concurrency + ordering rationale as getMainPage.
        coroutineScope {
            listOf(
                async { safeList { searchStreamed(matcher) } },
                async { safeList { searchPpv(matcher) } },
                async { safeList { searchWf(matcher) } },
                async { safeList { searchCdn(matcher) } },
                async { safeList { searchRoxie(matcher) } }
            ).awaitAll()
        }.forEach { results.addAll(it) }

        return results
    }

    // Streamed.pk — live only. /matches/all-today also returns not-yet-started matches,
    // which we deliberately keep out of search results (only /matches/live).
    private suspend fun searchStreamed(matcher: QueryMatcher): List<SearchResponse> =
        fetchMatches("$apiUrl/matches/live")
            .distinctBy { it.id }
            .filter { it.title != null && matcher.matches(it.title, it.category) }
            .mapNotNull { match ->
                val id = match.id ?: return@mapNotNull null
                val title = match.title ?: return@mapNotNull null
                newLiveSearchResponse(title, "$mainUrl/match/$id") {
                    this.posterUrl = streamedPoster(match)
                }
            }

    private suspend fun searchPpv(matcher: QueryMatcher): List<SearchResponse> =
        fetchPPVApi()?.streams.orEmpty().flatMap { category ->
            category.streams.orEmpty()
                .filter { stream ->
                    ppvListable(category, stream) &&
                        matcher.matches(stream.name ?: "Unknown Event", category.category_name, category.category)
                }
                .map { ppvItem(it) }
        }

    private suspend fun searchWf(matcher: QueryMatcher): List<SearchResponse> =
        fetchWFMatches()
            .filter { match ->
                wfListable(match) && matcher.matches(match.title ?: "Live Event", match.sport, match.league)
            }
            .map { wfItem(it) }

    private suspend fun searchCdn(matcher: QueryMatcher): List<SearchResponse> =
        fetchCdnEvents().flatMap { (sport, events) ->
            events.filter { isLiveCdn(it) && matcher.matches(cdnTitle(it), sport) }.map { cdnItem(it) }
        }

    private suspend fun searchRoxie(matcher: QueryMatcher): List<SearchResponse> =
        fetchRoxieEvents()
            .filter { matcher.matches(it.name) }
            .map { roxieItem(it) }

    // --- Load ----------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        checkAndGetDomain()
        if (url == TRT_URL) {
            return newLiveStreamLoadResponse(
                name = "TRT Yayını",
                url = url,
                dataUrl = url
            ) {
                this.posterUrl = TRT_POSTER
                this.plot = "TRT Yayını Live Stream"
            }
        }

        // Handle PPV Streams
        if (url.startsWith("https://ppv.domains/")) {
            val streamId = url.substringAfterLast("/").toIntOrNull()
            val stream = fetchPPVApi()?.streams
                ?.firstNotNullOfOrNull { category -> category.streams?.find { it.id == streamId } }
                ?: return null
            val title = stream.name ?: "Live Stream"

            return newLiveStreamLoadResponse(
                name = "$title [PPV]",
                url = url,
                dataUrl = stream.toJson()
            ) {
                this.posterUrl = ppvPoster(stream)
                this.plot = title
            }
        }

        // Handle WF Streams
        if (url.startsWith("https://wf.domains/")) {
            val matchId = url.substringAfterLast("/")
            val match = fetchWFMatches().find { it.matchId == matchId } ?: return null
            if (!wfHasHd(match)) return null
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

        // Handle Roxie Streams — url is https://roxie.domains/<url-safe base64 event path>
        if (url.startsWith("https://roxie.domains/")) {
            val path = roxiePathFromKey(url.substringAfterLast("/")) ?: return null
            val sources = fetchRoxieSources(path)
            if (sources.isEmpty()) return null
            val name = fetchRoxieEvents().find { it.path == path }?.name ?: path.trim('/').replace('-', ' ')
            return newLiveStreamLoadResponse(
                name = "$name [Roxie]",
                url = url,
                dataUrl = RoxieLoadData(name, path, sources).toJson()
            ) {
                this.plot = name
            }
        }

        // Handle Streamed.pk (Main Source) URL format
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

    // --- Links ----------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        originalCallback: (ExtractorLink) -> Unit
    ): Boolean {
        checkAndGetDomain()
        val maxH = deviceMaxHeight()
        val callback: (ExtractorLink) -> Unit = { link ->
            try {
                val linkHeight = when (link.quality) {
                    Qualities.P2160.value -> 2160
                    Qualities.P1080.value -> 1080
                    Qualities.P720.value -> 720
                    Qualities.P480.value -> 480
                    Qualities.P360.value -> 360
                    Qualities.P240.value -> 240
                    Qualities.P144.value -> 144
                    else -> 0
                }
                val is4k = linkHeight >= 2160 || 
                           link.name.contains("4k", ignoreCase = true) || 
                           link.name.contains("2160", ignoreCase = true) ||
                           link.source.contains("4k", ignoreCase = true) ||
                           link.source.contains("2160", ignoreCase = true)
                val is1080p = linkHeight >= 1080 || 
                              link.name.contains("1080", ignoreCase = true) || 
                              link.name.contains("fhd", ignoreCase = true) ||
                              link.source.contains("1080", ignoreCase = true) ||
                              link.source.contains("fhd", ignoreCase = true)
                val is720p = linkHeight >= 720 || 
                             link.name.contains("720", ignoreCase = true) || 
                             link.name.contains("hd", ignoreCase = true) ||
                             link.source.contains("720", ignoreCase = true) ||
                             link.source.contains("hd", ignoreCase = true)

                val shouldFilter = (is4k && maxH < 2160) || (is1080p && maxH < 1080) || (is720p && maxH < 720)
                if (!shouldFilter) {
                    originalCallback(link)
                }
            } catch (e: Exception) {
                originalCallback(link)
            }
        }
        if (data == TRT_URL) {
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
                    iframes.add("Main" to mainIframe)
                }
                stream.substreams?.forEach { sub ->
                    val subIframe = sub.iframe ?: sub.uri_name?.let { "https://embedindia.st/embed/$it" }
                    if (subIframe != null) {
                        iframes.add((sub.source_tag ?: sub.name ?: sub.locale ?: "Substream") to subIframe)
                    }
                }

                iframes.resolveConcurrently { (name, iframeUrl) ->
                    loadExtractor(iframeUrl, "https://embedindia.st/", subtitleCallback) { link ->
                        callback(
                            ExtractorLink(
                                source = "PPV",
                                name = withQualityLabel("PPV - $name", link.quality),
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
                streams.resolveConcurrently { stream ->
                    // Build the label from source + language only; the resolution is appended
                    // from the *detected* quality below, not from WF's static quality hint
                    // (which is what was mislabeling 1080p feeds as 720p).
                    val base = listOfNotNull(stream.source, stream.language).joinToString(" - ").ifBlank { "Live" }
                    loadExtractor(stream.url!!, "https://api.watchfooty.st/", subtitleCallback) { link ->
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
                        callback(
                            ExtractorLink(
                                source = "WF",
                                name = "WF - $base",
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
        } catch (e: Exception) {}

        // Handle StreamSports (cdnlivetv) Extract
        try {
            val event = AppUtils.parseJson<CdnEvent>(data)
            if (event.gameID != null && !event.channels.isNullOrEmpty()) {
                // Cap channels: an event can carry 100+, and each spawns a WebView extractor.
                val channels = event.channels.filter { !it.url.isNullOrBlank() }.take(8)
                channels.resolveConcurrently { channel ->
                    val chName = channel.channelName ?: "Channel"
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
                return true
            }
        } catch (e: Exception) {}

        // Handle Roxie Extract — the event's buttons, already scraped by load().
        try {
            val load = AppUtils.parseJson<RoxieLoadData>(data)
            if (load.path.isNotBlank() && load.sources.isNotEmpty()) {
                val domains = fetchRoxieDomains()
                val eventRef = "$roxieUrl${load.path}"
                load.sources.resolveConcurrently { src ->
                    if (src.kind == "raw") {
                        // An obfuscated player page: hand off to the WebView sniffer.
                        loadExtractor("$roxieUrl${src.value}", eventRef, subtitleCallback) { link ->
                            callback(
                                ExtractorLink(
                                    source = "Roxie",
                                    name = withQualityLabel("Roxie - ${src.label}", link.quality),
                                    url = link.url,
                                    referer = link.referer,
                                    quality = link.quality,
                                    type = link.type,
                                    headers = link.headers,
                                    extractorData = link.extractorData
                                )
                            )
                        }
                    } else {
                        // Direct playlist: probe the domain pool for a live host.
                        val m3u8 = resolveRoxieM3u8(src, domains) ?: return@resolveConcurrently
                        callback(
                            ExtractorLink(
                                source = "Roxie",
                                name = "Roxie - ${src.label}",
                                url = m3u8,
                                referer = "$roxieUrl/",
                                quality = Qualities.Unknown.value,
                                type = com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8,
                                headers = mapOf("Referer" to "$roxieUrl/", "Origin" to roxieUrl)
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

        // Fetch every source's stream list first (cheap JSON calls, all in parallel), then
        // resolve the flattened embed list through ONE bounded pool. The old nested
        // resolveConcurrently could stack up to 4x4 concurrent WebViews.
        val embeds = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        app.get("$apiUrl/stream/${source.source}/${source.id}")
                            .parsedSafe<Array<APIStream>>()
                            ?.take(4) // Cap streams per source to prevent WebView extractors from timing out
                            ?.map { source to it } ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        embeds.resolveConcurrently { (source, stream) ->
            val langStr = stream.language ?: "Unknown"
            val sourceName = source.source?.replaceFirstChar { it.uppercase() } ?: "Unknown"
            val base = "$sourceName - $langStr - Stream ${stream.streamNo}"
            // Pass the embed URL to our WebView extractor (or built-in extractors)
            loadExtractor(stream.embedUrl, "$mainUrl/", subtitleCallback) { link ->
                // Trust the extractor's detected resolution (from the master playlist).
                // When it can't be determined we leave it Unknown rather than guessing
                // 720p, so we never label a stream with a resolution we didn't measure.
                val labeled = withQualityLabel(base, link.quality)
                callback.invoke(
                    ExtractorLink(
                        source = labeled,
                        name = labeled,
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

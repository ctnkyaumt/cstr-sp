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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    // streamfree.top (StreamFree)
    data class SFStream(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("league") val league: String? = null,
        @JsonProperty("stream_key") val streamKey: String? = null,
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
        // Unix seconds of the event start. Absent/0/past = a 24-7 channel (always live);
        // a future value marks a not-yet-started event we keep out of live results.
        @JsonProperty("match_timestamp") val matchTimestamp: Long? = null
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
    // The device-resolution cap is enforced as an *offer* filter (see resolveStreamFree),
    // i.e. by not listing redundant above-device variants — NOT by relabeling, which used to
    // mislabel genuine 1080p streams as 720p on 720p panels. Labels always show the real stream.
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

    // StreamFree: a 24-7 channel has no/zero/past match_timestamp (always live); a future
    // timestamp is a not-yet-started event. We can't detect "finished" (no end time), but
    // resolveStreamFree only yields links for a currently-live playlist, so stale entries
    // simply resolve to nothing — the important case here is hiding upcoming events.
    private fun isLiveSf(stream: SFStream): Boolean {
        val ts = stream.matchTimestamp ?: return true
        return ts <= 0L || ts * 1000L <= System.currentTimeMillis()
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

    // Resolves a StreamFree embed to playable links, one per live quality. Each entry is
    // (url, isM3u8, quality): a direct m3u8 (isM3u8 = true) for hosted streams, or an
    // external embed url to hand off to loadExtractor (isM3u8 = false). Empty if the
    // stream isn't currently live. The embed page's own player defaults to 720p but its
    // selector offers every quality, and each quality has its own signed URL — so emit
    // them all instead of just the default, otherwise the app is locked to 720p.
    private suspend fun resolveStreamFree(key: String, embedUrl: String): List<Triple<String, Boolean, String>> {
        val ref = "$streamfreeUrl/"
        val sk = try {
            app.get("$streamfreeUrl/get-stream-key/$key", referer = ref).parsedSafe<SFStreamKey>()
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        if (sk.isExternal == true && sk.externalUrl != null) return listOf(Triple(sk.externalUrl, false, "720p"))

        val qualities = try {
            app.get("$streamfreeUrl/api/stream-status/$key", referer = ref).parsedSafe<SFStatus>()?.qualities
        } catch (e: Exception) {
            null
        }.orEmpty()
        val maxH = deviceMaxHeight()
        fun fitsDevice(q: String) = (q.removeSuffix("p").toIntOrNull() ?: 0) <= maxH
        // Highest -> lowest, so the last element is always the lowest available quality.
        val live = listOf("2160p", "1080p", "720p", "540p").filter { qualities[it] == true }
        val tryQualities = if (live.isNotEmpty()) {
            // Offer filter: don't list variants above the device's resolution (a 1080p panel
            // has no use for the 2160p feed). But never drop the stream entirely — if nothing
            // fits, keep the single lowest available so the device can still downscale-play it.
            live.filter { fitsDevice(it) }.ifEmpty { listOf(live.last()) }
        } else {
            // Status endpoint can be empty right at stream start; probe the common qualities
            // (device-capped, with a floor) rather than dropping the stream.
            listOf("1080p", "720p").filter { fitsDevice(it) }.ifEmpty { listOf("720p") }
        }

        val tokens = try {
            val html = app.get(embedUrl, referer = ref).text
            val json = Regex("const _0x = (\\{.*?\\});").find(html)?.groupValues?.get(1) ?: return emptyList()
            AppUtils.parseJson<Map<String, SFToken>>(json)
        } catch (e: Exception) {
            return emptyList()
        }

        val prefix = if ((sk.serverName ?: "origin") != "origin") "live-cdn" else "live"
        val out = mutableListOf<Triple<String, Boolean, String>>()
        for (quality in tryQualities) {
            val token = tokens[quality] ?: continue
            if (token.t == null || token.e == null || token.n == null) continue
            val url = "$streamfreeUrl/$prefix/$key$quality/index.m3u8?_t=${token.t}&_e=${token.e}&_n=${token.n}"
            // Only emit if the playlist is actually live (upcoming events 404 here).
            try {
                if (app.get(url, referer = ref).text.contains("#EXTM3U")) out.add(Triple(url, true, quality))
            } catch (e: Exception) {}
        }
        return out
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

    // Turkish-folds, lowercases, and collapses every run of non-alphanumeric characters
    // (spaces, the hyphen in "motor-sports", punctuation) down to a single space, so titles,
    // category/sport hints, synonym aliases, and the query all compare on the same footing.
    private fun normalizeText(s: String): String =
        trFold(s).replace(Regex("[^a-z0-9]+"), " ").trim()

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

    // A title/category blob matches a query when every query token matches — where a token
    // matches via its own text, its Turkish devoiced/nation variants, or a synonym group it
    // belongs to. A whole-query synonym hit short-circuits first, so multi-word aliases like
    // "grand prix" or "champions league" match even though they tokenize into pieces.
    private fun titleMatchesQuery(title: String, query: String): Boolean {
        var q = trFold(query)
        trPhraseNames.forEach { (tr, en) -> q = q.replace(tr, en) }
        q = q.replace(Regex("[^a-z0-9]+"), " ").trim()
        if (q.isBlank()) return true
        val hay = normalizeText(title)

        // Whole-query alias: lets multi-word aliases match without being split into tokens.
        synonymIndex[q]?.let { group -> if (group.any { hay.contains(it) }) return true }

        val parts = q.split(" ").filter { it.isNotBlank() }
        if (parts.isEmpty()) return true
        return parts.all { part ->
            val variants = searchVariants(part) + (synonymIndex[part] ?: emptySet())
            variants.any { hay.contains(it) }
        }
    }

    // Builds the searchable blob for an item from its title plus any category/sport/league
    // hints, then matches the query against it. Feeding the category in is what lets generic
    // Turkish sport words (futbol, basketbol, yaris) match a whole sport even when the title
    // is just two team names.
    private fun matchesSearch(query: String, vararg fields: String?): Boolean =
        titleMatchesQuery(fields.filterNotNull().joinToString(" "), query)

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
                    
                    if (isLivePpv(category, stream) && (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty())) {
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
                    if (match.matchId != null && wfHasHd(match) && isLiveWf(match)) {
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
                    if (!isLiveCdn(event)) return@mapNotNull null
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
            fetchSFStreams().filter { isLiveSf(it) }.groupBy { it.category ?: "Other" }.forEach { (category, streams) ->
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

        // Search Streamed.pk — live only. /matches/all-today also returns not-yet-started
        // matches, which we deliberately keep out of search results (only /matches/live).
        val liveMatches = fetchMatches("$apiUrl/matches/live")
        val allMatches = liveMatches.distinctBy { it.id }

        results.addAll(allMatches.filter { match ->
            val title = match.title ?: return@filter false
            matchesSearch(query, title, match.category)
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

                    if (matchesSearch(query, title, category.category_name, category.category)) {
                        val posterUrl = stream.poster?.let {
                            val encoded = android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                            "$mainUrl/api/images/proxy/$encoded.webp"
                        }
                        
                        if (isLivePpv(category, stream) && (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty())) {
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
                if (matchesSearch(query, title, match.sport, match.league) && match.matchId != null && wfHasHd(match) && isLiveWf(match)) {
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
            fetchCdnEvents().forEach { (sport, events) ->
                events.forEach { event ->
                    val gameId = event.gameID ?: return@forEach
                    val title = cdnTitle(event)
                    if (isLiveCdn(event) && matchesSearch(query, title, sport)) {
                        results.add(
                            newLiveSearchResponse("$title [StreamSports]", "https://cdn.domains/$gameId") {
                                this.posterUrl = cdnPoster(event)
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {}

        // Search StreamFree
        try {
            fetchSFStreams().forEach { stream ->
                val key = stream.streamKey ?: return@forEach
                val title = stream.name ?: return@forEach
                if (isLiveSf(stream) && matchesSearch(query, title, stream.category, stream.league)) {
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
            if (!wfHasHd(match)) return null

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

        val sourceNames = match.sources?.mapNotNull { it.source }?.sorted()?.joinToString(", ") { src ->
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
                        val resolvedQuality = if (link.quality != Qualities.Unknown.value) link.quality else wfQuality(stream.quality)
                        callback(
                            ExtractorLink(
                                source = "WF",
                                name = withQualityLabel("WF - $base", resolvedQuality),
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

        // Handle StreamFree Extract
        try {
            val stream = AppUtils.parseJson<SFStream>(data)
            val key = stream.streamKey
            if (stream.embedUrl != null && key != null) {
                val label = stream.name ?: "Live"
                resolveStreamFree(key, stream.embedUrl).forEach { (streamUrl, isM3u8, quality) ->
                    if (isM3u8) {
                        // `quality` here is StreamFree's own authoritative quality string
                        // (e.g. "1080p") for this signed URL, so use it verbatim in the name.
                        callback(
                            ExtractorLink(
                                source = "StreamFree",
                                name = "StreamFree - $label ($quality)",
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
                                    name = withQualityLabel("StreamFree - $label", link.quality),
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
            sources.resolveConcurrently { source ->
                // HD only (drop SD streams).
                val streams = app.get("$apiUrl/stream/${source.source}/${source.id}")
                    .parsedSafe<Array<APIStream>>()?.toList()?.filter { it.hd } ?: emptyList()

                streams.resolveConcurrently { stream ->
                    val langStr = stream.language ?: "Unknown"
                    val sourceName = source.source?.replaceFirstChar { it.uppercase() } ?: "Unknown"
                    val base = "$sourceName - $langStr - Stream ${stream.streamNo}"
                    val embedUrl = stream.embedUrl
                    // Pass the embed URL to our WebView extractor (or built-in extractors)
                    loadExtractor(embedUrl, "$mainUrl/", subtitleCallback) { link ->
                        // Trust the extractor's detected resolution (from the master playlist).
                        // When it can't be determined we leave it Unknown rather than guessing
                        // 720p, so we never label a stream with a resolution we didn't measure.
                        val quality = link.quality
                        val labeled = withQualityLabel(base, quality)
                        callback.invoke(
                            ExtractorLink(
                                source = labeled,
                                name = labeled,
                                url = link.url,
                                referer = link.referer,
                                quality = quality,
                                type = link.type,
                                headers = link.headers,
                                extractorData = link.extractorData
                            )
                        )
                    }
                }
            }
        }

        return true
    }
}

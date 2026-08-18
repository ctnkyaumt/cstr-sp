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
import com.lagradost.cloudstream3.utils.CLEARKEY_UUID
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newDrmExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class RoxieEvent(
    @JsonProperty("name") val name: String,
    @JsonProperty("path") val path: String
)

data class RoxieSource(
    @JsonProperty("label") val label: String,
    @JsonProperty("kind") val kind: String,
    @JsonProperty("value") val value: String,
    @JsonProperty("subdomain") val subdomain: String = ""
)

data class RoxiePage(
    val sources: List<RoxieSource>,
    val domainsFile: String
)

data class RoxieLoadData(
    @JsonProperty("name") val name: String,
    @JsonProperty("path") val path: String,
    @JsonProperty("sources") val sources: List<RoxieSource>,
    @JsonProperty("domainsFile") val domainsFile: String = "domainsz58.txt"
)

object RoxieSourceProvider {
    private const val roxieUrl = "https://roxiestreams.su"
    private const val ROXIE_LIST_PROBE_TIMEOUT_MS = 5_000L

    @Volatile private var roxieGoodDomain: String? = null

    private val roxieEventRowRegex = Regex("href=\"(/[^\"]+)\"[^>]*>([^<]+)</a>")
    private val roxieButtonRegex = Regex("<button[^>]*onclick=\"([^\"]*)\"[^>]*>(.*?)</button>", RegexOption.DOT_MATCHES_ALL)
    private val roxieGetStreamRegex = Regex("getRandomStream\\(\\s*'([^']+)'(?:\\s*,\\s*'([^']+)')?\\s*\\)")
    private val roxieRawRegex = Regex("playIframePlayer\\(\\s*'([^']+)'\\s*\\)")
    private val roxieTagRegex = Regex("<[^>]+>")
    private val roxieDomainsFileRegex = Regex("domainsz\\d+\\.txt")
    private val roxieLoadCallRegex = Regex("\\.load\\(\\s*[\"']([^\"']+)[\"']")
    private val roxieManifestRegex = Regex("https?://[^\\s\"'<>]+\\.(?:mpd|m3u8)[^\\s\"'<>]*")
    private val roxieClearKeyRegex = Regex("[\"']([0-9a-fA-F]{32})[\"']\\s*:\\s*[\"']([0-9a-fA-F]{32})[\"']")
    private val roxieFnCallRegex = Regex("^\\s*([A-Za-z_$][\\w$]*)\\s*\\(\\s*\\)\\s*;?\\s*$")

    private val roxieCdnHeaders = mapOf(
        "Referer" to "$roxieUrl/",
        "Origin" to roxieUrl,
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site"
    )

    private val roxieCategories = listOf("/soccer", "/mlb", "/nba", "/nfl", "/nhl", "/fighting", "/motorsports")

    private fun jsFunctionBody(html: String, name: String): String? {
        val head = Regex("function\\s+${Regex.escape(name)}\\s*\\([^)]*\\)\\s*\\{").find(html)
            ?: return null
        var depth = 0
        var i = head.range.last
        val start = i + 1
        while (i < html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i)
                }
            }
            i++
            if (i - start > 4000) break
        }
        return null
    }

    private fun roxieSourcesFrom(
        onclick: String,
        label: String,
        html: String,
        nested: Boolean = false
    ): List<RoxieSource> {
        val out = mutableListOf<RoxieSource>()
        roxieGetStreamRegex.findAll(onclick).forEach {
            out.add(RoxieSource(label, "m3u8", it.groupValues[1], it.groupValues[2].ifBlank { "ataide0" }))
        }
        roxieRawRegex.findAll(onclick).forEach {
            out.add(RoxieSource(label, "raw", it.groupValues[1]))
        }
        if (out.isEmpty() && !nested) {
            roxieFnCallRegex.find(onclick)?.groupValues?.get(1)?.let { fn ->
                jsFunctionBody(html, fn)?.let { body ->
                    out.addAll(roxieSourcesFrom(body, label, html, nested = true))
                }
            }
        }
        return out
    }

    private fun hexToBase64Url(hex: String): String? {
        if (hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

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

    fun roxieItem(event: RoxieEvent): SearchResponse =
        newLiveSearchResponse("${event.name} [Roxie]", "https://roxie.domains/${roxieKey(event.path)}") {}

    suspend fun fetchRoxieEvents(): List<RoxieEvent> = CstrspCache.cached("roxie-events") {
        val pages = listOf("/") + roxieCategories
        val results = coroutineScope {
            pages.map { pagePath ->
                async {
                    try {
                        val html = app.get("$roxieUrl$pagePath", referer = "$roxieUrl/").text
                        val table = if (html.contains("id=\"eventsTable\"")) {
                            html.substringAfter("id=\"eventsTable\"", "").substringBefore("</table>", "")
                        } else html
                        roxieEventRowRegex.findAll(table)
                            .map { RoxieEvent(it.groupValues[2].trim(), it.groupValues[1].trim()) }
                            .filter { ev ->
                                ev.name.isNotBlank() && ev.path.isNotBlank() &&
                                    ev.path !in pages && ev.path != "/multiview" && !ev.path.endsWith(".txt")
                            }
                            .toList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        results.distinctBy { it.path }.takeIf { it.isNotEmpty() }
    } ?: emptyList()

    suspend fun fetchRoxieDomains(file: String): List<String> = CstrspCache.cached("roxie-domains-$file") {
        app.get("$roxieUrl/$file", referer = "$roxieUrl/").text
            .lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .takeIf { it.isNotEmpty() }
    } ?: emptyList()

    suspend fun fetchRoxiePage(path: String): RoxiePage = CstrspCache.cached("roxie-page-$path") {
        val html = app.get("$roxieUrl$path", referer = "$roxieUrl/").text
        val sources = roxieButtonRegex.findAll(html).flatMap { m ->
            val onclick = m.groupValues[1]
            val label = roxieTagRegex.replace(m.groupValues[2], "").trim().ifBlank { "Stream" }
            roxieSourcesFrom(onclick, label, html).asSequence()
        }.distinctBy { "${it.kind}|${it.value}|${it.subdomain}" }.toList()
        val domainsFile = roxieDomainsFileRegex.find(html)?.value ?: "domainsz58.txt"
        RoxiePage(sources, domainsFile).takeIf { sources.isNotEmpty() }
    } ?: RoxiePage(emptyList(), "domainsz58.txt")

    suspend fun fetchPlayableRoxieEvents(): List<RoxieEvent> = CstrspCache.cached("roxie-playable-events") {
        val events = fetchRoxieEvents()
        if (events.isEmpty()) return@cached null
        coroutineScope {
            val semaphore = Semaphore(4)
            events.map { event ->
                async {
                    semaphore.withPermit {
                        val page = fetchRoxiePage(event.path)
                        when {
                            page.sources.isEmpty() -> null
                            page.sources.any { it.kind == "raw" } -> event
                            else -> {
                                val domains = fetchRoxieDomains(page.domainsFile)
                                val playable = withTimeoutOrNull(ROXIE_LIST_PROBE_TIMEOUT_MS) {
                                    page.sources.asSequence()
                                        .filter { it.kind == "m3u8" }
                                        .firstNotNullOfOrNull { resolveRoxieM3u8(it, domains) }
                                }
                                event.takeIf { playable != null }
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    } ?: emptyList()

    private suspend fun resolveRoxieM3u8(source: RoxieSource, domains: List<String>): String? {
        for (domain in (listOfNotNull(roxieGoodDomain) + domains).distinct()) {
            val url = "https://${source.subdomain}.$domain/${source.value}"
            try {
                val res = app.get(url, referer = "$roxieUrl/", headers = roxieCdnHeaders)
                if (res.code == 200 && res.text.trimStart().startsWith("#EXTM")) {
                    roxieGoodDomain = domain
                    return url
                }
            } catch (e: Exception) {}
        }
        return null
    }

    suspend fun getHomeSections(): List<HomePageList> {
        val items = fetchPlayableRoxieEvents().map { roxieItem(it) }
        return if (items.isEmpty()) emptyList() else listOf(HomePageList("Live Events [Roxie]", items))
    }

    suspend fun search(matcher: QueryMatcher): List<SearchResponse> =
        fetchPlayableRoxieEvents()
            .filter { matcher.matches(it.name) }
            .map { roxieItem(it) }

    suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("https://roxie.domains/")) return null
        val path = roxiePathFromKey(url.substringAfterLast("/")) ?: return null
        val page = fetchRoxiePage(path)
        if (page.sources.isEmpty()) return null
        val name = fetchRoxieEvents().find { it.path == path }?.name ?: path.trim('/').replace('-', ' ')
        return newLiveStreamLoadResponse(
            name = "$name [Roxie]",
            url = url,
            dataUrl = RoxieLoadData(name, path, page.sources, page.domainsFile).toJson()
        ) {
            this.plot = name
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = try {
            AppUtils.parseJson<RoxieLoadData>(data)
        } catch (e: Exception) {
            return false
        }

        if (load.path.isNotBlank() && load.sources.isNotEmpty()) {
            val domains = fetchRoxieDomains(load.domainsFile)
            val eventRef = "$roxieUrl${load.path}"
            load.sources.resolveConcurrently { src ->
                if (src.kind == "raw") {
                    val rawUrl = if (src.value.startsWith("http")) src.value else "$roxieUrl${src.value}"
                    val page = app.get(rawUrl, referer = eventRef).text
                    val manifest = roxieLoadCallRegex.find(page)?.groupValues?.get(1)
                        ?: roxieManifestRegex.find(page)?.value
                    if (manifest == null) {
                        loadExtractor(rawUrl, eventRef, subtitleCallback) { link ->
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
                        return@resolveConcurrently
                    }
                    val headerVariants = listOf(
                        null to emptyMap<String, String>(),
                        "$roxieUrl/" to mapOf("Origin" to roxieUrl),
                        "$roxieUrl/" to roxieCdnHeaders
                    )
                    var pickedRef: String? = null
                    var pickedHeaders: Map<String, String> = emptyMap()
                    var accepted = false
                    var refused = false
                    for ((ref, extra) in headerVariants) {
                        val code = try {
                            app.get(manifest, referer = ref, headers = extra).code
                        } catch (e: Exception) {
                            null
                        }
                        if (code == null) continue
                        if (code !in 400..599) {
                            pickedRef = ref
                            pickedHeaders = extra
                            accepted = true
                            break
                        }
                        refused = true
                    }
                    if (!accepted && refused) return@resolveConcurrently
                    if (!accepted) {
                        pickedRef = "$roxieUrl/"
                        pickedHeaders = mapOf("Origin" to roxieUrl)
                    }
                    val linkType = if (manifest.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    val keys = roxieClearKeyRegex.find(page)
                    val kidB64 = keys?.let { hexToBase64Url(it.groupValues[1]) }
                    val keyB64 = keys?.let { hexToBase64Url(it.groupValues[2]) }
                    val link = if (kidB64 != null && keyB64 != null) {
                        newDrmExtractorLink(
                            source = "Roxie",
                            name = "Roxie - ${src.label}",
                            url = manifest,
                            type = linkType,
                            uuid = CLEARKEY_UUID
                        ) {
                            this.kid = kidB64
                            this.key = keyB64
                            this.referer = pickedRef ?: ""
                            this.headers = pickedHeaders
                        }
                    } else {
                        newExtractorLink(
                            source = "Roxie",
                            name = "Roxie - ${src.label}",
                            url = manifest,
                            type = linkType
                        ) {
                            this.referer = pickedRef ?: ""
                            this.headers = pickedHeaders
                        }
                    }
                    callback(link)
                } else {
                    val m3u8 = resolveRoxieM3u8(src, domains) ?: return@resolveConcurrently
                    callback(
                        ExtractorLink(
                            source = "Roxie",
                            name = "Roxie - ${src.label}",
                            url = m3u8,
                            referer = "$roxieUrl/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = roxieCdnHeaders
                        )
                    )
                }
            }
            return true
        }
        return false
    }
}

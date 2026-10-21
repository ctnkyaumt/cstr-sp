package com.cstrsp

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream

@JsonIgnoreProperties(ignoreUnknown = true)
data class RawStreamCornerItem(
    @JsonProperty("stream_id") val streamId: String? = null,
    @JsonProperty("game_id") val gameId: String? = null,
    @JsonProperty("event_name") val eventName: String? = null,
    @JsonProperty("home_team") val homeTeam: String? = null,
    @JsonProperty("away_team") val awayTeam: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("league") val league: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("start_time") val startTime: Any? = null,
    @JsonProperty("end_time") val endTime: Any? = null,
    @JsonProperty("timestamp") val timestamp: Any? = null,
    @JsonProperty("time_utc") val timeUtc: String? = null,
    @JsonProperty("time_et") val timeEt: String? = null,
    @JsonProperty("time") val time: String? = null,
    @JsonProperty("streams") val streams: List<StreamCornerStreamItem>? = null,
    @JsonProperty("live") val live: List<StreamCornerStreamItem>? = null,
    @JsonProperty("id") val id: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamCornerStreamItem(
    @JsonProperty("source_name") val sourceName: String? = null,
    @JsonProperty("embed_url") val embedUrl: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamCornerLoadData(
    @JsonProperty("provider") val provider: String? = null,
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("name") val name: String? = null
)

data class StreamCornerItem(
    val provider: String,
    val id: String,
    val name: String,
    val category: String,
    val league: String?,
    val poster: String?,
    val startTimeSec: Long?,
    val endTimeSec: Long?,
    val timeEt: String?,
    val nbaTime: String?,
    val isNba: Boolean,
    val homeTeam: String?,
    val awayTeam: String?
)

object StreamCornerSource {
    private const val BASE_URL = "https://streamcorner.st"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    private val ALL_PROVIDERS = setOf("admin", "mlb", "nfl", "nba", "alpha", "beta", "001")
    private val listMutex = Mutex()
    @Volatile private var cachedItems: List<StreamCornerItem> = emptyList()
    @Volatile private var lastFetchTime: Long = 0L

    private val HOOK_SCRIPT = """
    (function() {
        try {
            Object.defineProperty(window, 'Worker', { value: undefined, writable: false });
        } catch(e) {
            try { window.Worker = undefined; } catch(e2) {}
        }

        function getParam(url, key) {
            try {
                var m = new RegExp('[?&]' + key + '=([^&#]*)').exec(url);
                return m ? decodeURIComponent(m[1]) : '';
            } catch(e) {
                return '';
            }
        }

        var _origFetch = window.fetch;
        var _fetchChain = Promise.resolve();

        window.fetch = function(input, init) {
            var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '' + input);
            if (url && url.indexOf('workers.dev/corner') !== -1) {
                var p = getParam(url, 'p');
                var id = getParam(url, 'id');
                var tag = p;
                if (id) {
                    tag = tag ? (tag + ':' + id) : id;
                }

                var callerResolve, callerReject;
                var callerPromise = new Promise(function(res, rej) {
                    callerResolve = res;
                    callerReject = rej;
                });

                _fetchChain = _fetchChain.then(function() {
                    return _origFetch.apply(window, [input, init]).then(function(resp) {
                        if (resp && resp.ok) {
                            window.__scTag = tag;
                        }
                        callerResolve(resp);
                        return new Promise(function(settleResolve) {
                            setTimeout(settleResolve, 400);
                        });
                    }, function(err) {
                        callerReject(err);
                        return new Promise(function(settleResolve) {
                            setTimeout(settleResolve, 100);
                        });
                    });
                });

                return callerPromise;
            }
            return _origFetch.apply(this, arguments);
        };

        var _origParse = JSON.parse;
        JSON.parse = function(text, reviver) {
            try {
                if (typeof text === 'string' && text.length > 200) {
                    var trimmed = text.trim();
                    var first = trimmed.charAt(0);
                    if (first === '[' || first === '{') {
                        if (window.CstrspSC && typeof window.CstrspSC.onData === 'function') {
                            window.CstrspSC.onData(window.__scTag || '', text);
                        }
                    }
                }
            } catch(e) {}
            return _origParse.apply(this, arguments);
        };
    })();
    """.trimIndent()

    private class StreamCornerBridge(private val onDataReceived: (String, String) -> Unit) {
        @JavascriptInterface
        fun onData(tag: String, json: String) {
            onDataReceived(tag, json)
        }
    }

    private fun isStreamCornerHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == "streamcorner.st" ||
            h.startsWith("streamcorner.") ||
            h.contains(".streamcorner.") ||
            h.endsWith(".streamcorner.st")
    }

    private fun fetchAndInjectHtml(
        targetUrl: String,
        requestHeaders: Map<String, String>?,
        userAgent: String?
    ): WebResourceResponse? {
        var currentUrl = targetUrl
        var redirects = 0
        while (redirects < 5) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false

            requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) &&
                    !k.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    connection.setRequestProperty(k, v)
                }
            }
            val effectiveUa = userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            if (connection.getRequestProperty("User-Agent") == null) {
                connection.setRequestProperty("User-Agent", effectiveUa)
            }
            val cookie = android.webkit.CookieManager.getInstance().getCookie(currentUrl)
            if (!cookie.isNullOrEmpty()) {
                connection.setRequestProperty("Cookie", cookie)
            }

            connection.connect()
            val responseCode = connection.responseCode
            val setCookie = connection.getHeaderField("Set-Cookie")
            if (!setCookie.isNullOrEmpty()) {
                try {
                    android.webkit.CookieManager.getInstance().setCookie(currentUrl, setCookie)
                } catch (e: Exception) {}
            }

            if (responseCode in 300..399) {
                val loc = connection.getHeaderField("Location") ?: break
                val nextUri = URI(currentUrl).resolve(loc)
                currentUrl = nextUri.toString()
                redirects++
                connection.disconnect()
                continue
            }

            if (responseCode in 200..299) {
                val isGzip = connection.getHeaderField("Content-Encoding")?.equals("gzip", ignoreCase = true) == true
                val inStream = if (isGzip) GZIPInputStream(connection.inputStream) else connection.inputStream
                val rawHtml = inStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()

                val scriptTag = "<script>$HOOK_SCRIPT</script>"
                val headRegex = Regex("""<head[^>]*>""", RegexOption.IGNORE_CASE)
                val match = headRegex.find(rawHtml)
                val modifiedHtml = if (match != null) {
                    val idx = match.range.last + 1
                    rawHtml.substring(0, idx) + scriptTag + rawHtml.substring(idx)
                } else {
                    scriptTag + rawHtml
                }

                return WebResourceResponse(
                    "text/html",
                    "utf-8",
                    ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
                )
            } else {
                connection.disconnect()
                break
            }
        }
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun capture(
        pageUrl: String,
        timeoutMs: Long,
        done: (List<Pair<String, String>>) -> Boolean
    ): List<Pair<String, String>> {
        val ctx = CstrspContext.appContext ?: return emptyList()
        val captured = CopyOnWriteArrayList<Pair<String, String>>()
        lateinit var webView: WebView
        var cachedUserAgent: String? = null

        try {
            withContext(Dispatchers.Main) {
                webView = WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = false
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    cachedUserAgent = settings.userAgentString
                    webChromeClient = WebChromeClient()

                    addJavascriptInterface(StreamCornerBridge { tag, json ->
                        captured.add(tag to json)
                    }, "CstrspSC")

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val req = request ?: return true
                            val reqUri = req.url ?: return true
                            val scheme = reqUri.scheme?.lowercase() ?: return true
                            if (scheme != "http" && scheme != "https") return true

                            if (req.isForMainFrame) {
                                if (!isStreamCornerHost(reqUri.host)) return true
                            }
                            return false
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, urlStr: String?): Boolean {
                            val uri = runCatching { android.net.Uri.parse(urlStr) }.getOrNull() ?: return true
                            val scheme = uri.scheme?.lowercase() ?: return true
                            if (scheme != "http" && scheme != "https") return true
                            return !isStreamCornerHost(uri.host)
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val req = request ?: return null
                            val reqUri = req.url ?: return null
                            val reqUrl = reqUri.toString()

                            if (reqUrl.contains("disable-devtool", ignoreCase = true) ||
                                reqUrl.contains("popa_corner", ignoreCase = true) ||
                                reqUrl.contains("adsco.re", ignoreCase = true)
                            ) {
                                return WebResourceResponse(
                                    "text/javascript",
                                    "utf-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }

                            val method = req.method ?: "GET"
                            if (req.isForMainFrame && method.equals("GET", ignoreCase = true) && isStreamCornerHost(reqUri.host)) {
                                try {
                                    val injected = fetchAndInjectHtml(reqUrl, req.requestHeaders, cachedUserAgent)
                                    if (injected != null) return injected
                                } catch (e: Exception) {
                                    // On error return null
                                }
                            }

                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    loadUrl(pageUrl)
                }
            }

            var elapsed = 0L
            while (elapsed < timeoutMs) {
                delay(250)
                elapsed += 250
                val isDone = try {
                    done(captured.toList())
                } catch (e: Exception) {
                    false
                }
                if (isDone) {
                    break
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (e: Exception) {}
            }
        }

        return captured.toList()
    }

    private fun toSeconds(v: Any?): Long? = when (v) {
        is Number -> v.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }

    private fun parseJsonList(json: String): List<RawStreamCornerItem> {
        val trimmed = json.trim()
        return try {
            if (trimmed.startsWith("[")) {
                AppUtils.parseJson<Array<RawStreamCornerItem>>(trimmed).toList()
            } else if (trimmed.startsWith("{")) {
                listOfNotNull(AppUtils.parseJson<RawStreamCornerItem>(trimmed))
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseDetailPayload(json: String): RawStreamCornerItem? {
        val trimmed = json.trim()
        return try {
            if (trimmed.startsWith("[")) {
                AppUtils.parseJson<Array<RawStreamCornerItem>>(trimmed).firstOrNull()
            } else {
                AppUtils.parseJson<RawStreamCornerItem>(trimmed)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun convertRawItem(raw: RawStreamCornerItem, tag: String): StreamCornerItem? {
        val isNba = !raw.gameId.isNullOrBlank() || tag.equals("nba", ignoreCase = true)
        val id = if (isNba) {
            raw.gameId?.trim()?.ifEmpty { null } ?: raw.streamId?.trim()?.ifEmpty { null }
        } else {
            raw.streamId?.trim()?.ifEmpty { null }
        } ?: return null

        if (id.startsWith("msg-", ignoreCase = true)) return null

        val name = if (isNba) {
            val away = raw.awayTeam?.trim().orEmpty()
            val home = raw.homeTeam?.trim().orEmpty()
            if (away.isNotEmpty() && home.isNotEmpty()) {
                "$away @ $home"
            } else {
                raw.eventName?.trim()?.ifEmpty { null } ?: return null
            }
        } else {
            raw.eventName?.trim()?.ifEmpty { null } ?: return null
        }

        val provider = if (isNba) {
            "nba"
        } else {
            val p = tag.substringBefore(":").trim()
            if (p.isNotEmpty()) p else "stream"
        }

        val category = if (isNba) {
            "NBA"
        } else {
            raw.category?.trim()?.ifBlank { "Other" } ?: "Other"
        }

        val startSec = toSeconds(raw.startTime) ?: toSeconds(raw.timestamp)
        val endSec = toSeconds(raw.endTime)

        return StreamCornerItem(
            provider = provider,
            id = id,
            name = name,
            category = category,
            league = raw.league?.trim(),
            poster = raw.poster?.trim()?.ifEmpty { null },
            startTimeSec = startSec,
            endTimeSec = endSec,
            timeEt = raw.timeEt?.trim(),
            nbaTime = raw.time?.trim(),
            isNba = isNba,
            homeTeam = raw.homeTeam?.trim(),
            awayTeam = raw.awayTeam?.trim()
        )
    }

    private suspend fun doFetchAllItems(): List<StreamCornerItem> {
        val captured = capture("$BASE_URL/", 20_000L) { list ->
            val tags = list.map { it.first.substringBefore(":") }.toSet()
            ALL_PROVIDERS.all { it in tags }
        }
        if (captured.isEmpty()) return emptyList()

        val result = mutableListOf<StreamCornerItem>()
        for ((tag, json) in captured) {
            val rawList = parseJsonList(json)
            for (raw in rawList) {
                val item = convertRawItem(raw, tag) ?: continue
                result.add(item)
            }
        }
        return result.distinctBy { "${it.provider}:${it.id}" }
    }

    private suspend fun fetchAllItems(): List<StreamCornerItem> {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime < CACHE_TTL_MS && cachedItems.isNotEmpty()) {
            return cachedItems
        }
        return listMutex.withLock {
            val lockNow = System.currentTimeMillis()
            if (lockNow - lastFetchTime < CACHE_TTL_MS && cachedItems.isNotEmpty()) {
                return@withLock cachedItems
            }
            val items = try {
                doFetchAllItems()
            } catch (e: Exception) {
                emptyList()
            }
            if (items.isNotEmpty()) {
                cachedItems = items
                lastFetchTime = System.currentTimeMillis()
            }
            items.ifEmpty { cachedItems }
        }
    }

    private fun isLiveNow(item: StreamCornerItem, nowSec: Long): Boolean {
        if (item.isNba && item.nbaTime?.equals("Final", ignoreCase = true) == true) {
            return false
        }
        if (item.timeEt?.equals("LIVE", ignoreCase = true) == true) {
            return true
        }
        val start = item.startTimeSec ?: return false
        val startOk = start in (nowSec - 4 * 3600L)..(nowSec + 15 * 60L)
        val endOk = item.endTimeSec == null || nowSec <= (item.endTimeSec + 30 * 60L)
        return startOk && endOk
    }

    suspend fun getHomeSections(api: MainAPI): List<HomePageList> {
        val allItems = fetchAllItems()
        if (allItems.isEmpty()) return emptyList()
        val nowSec = System.currentTimeMillis() / 1000L

        val liveItems = allItems.filter { isLiveNow(it, nowSec) }
        return liveItems
            .groupBy { it.category.ifBlank { "Other" } }
            .mapNotNull { (category, items) ->
                val searchResponses = items.distinctBy { it.id }.mapNotNull { item ->
                    val title = "${item.name} [StreamCorner]"
                    val url = "https://streamcorner.domains/${item.provider}/${item.id}"
                    api.newLiveSearchResponse(title, url) {
                        this.posterUrl = item.poster
                    }
                }
                if (searchResponses.isEmpty()) null
                else HomePageList("$category [StreamCorner]", searchResponses)
            }
    }

    suspend fun search(api: MainAPI, matcher: QueryMatcher): List<SearchResponse> {
        val allItems = fetchAllItems()
        if (allItems.isEmpty()) return emptyList()

        return allItems
            .filter { item ->
                matcher.matches(
                    item.name,
                    item.category,
                    item.league,
                    item.homeTeam,
                    item.awayTeam
                )
            }
            .distinctBy { "${it.provider}:${it.id}" }
            .mapNotNull { item ->
                val title = "${item.name} [StreamCorner]"
                val url = "https://streamcorner.domains/${item.provider}/${item.id}"
                api.newLiveSearchResponse(title, url) {
                    this.posterUrl = item.poster
                }
            }
    }

    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (!url.startsWith("https://streamcorner.domains/")) return null
        val path = url.removePrefix("https://streamcorner.domains/").trim('/')
        val provider = path.substringBefore("/")
        val id = path.substringAfter("/")
        if (provider.isEmpty() || id.isEmpty()) return null

        var item = cachedItems.find { it.provider == provider && it.id == id }
        if (item == null) {
            fetchAllItems()
            item = cachedItems.find { it.provider == provider && it.id == id }
        }

        val name = item?.name ?: "Event"
        val title = "$name [StreamCorner]"
        val loadData = StreamCornerLoadData(
            provider = provider,
            id = id,
            name = name
        )

        return api.newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = loadData.toJson()
        ) {
            this.posterUrl = item?.poster
            this.plot = title
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = try {
            AppUtils.parseJson<StreamCornerLoadData>(data)
        } catch (e: Exception) {
            return false
        }
        val provider = load.provider ?: return false
        val id = load.id ?: return false

        val isNba = provider.equals("nba", ignoreCase = true)
        val pageUrl = if (isNba) {
            "$BASE_URL/nba/live/$id"
        } else {
            "$BASE_URL/stream/$provider/$id"
        }

        val captured = capture(pageUrl, 20_000L) { list ->
            list.any { (_, json) ->
                if (!json.contains(id)) return@any false
                val item = parseDetailPayload(json)
                item != null && (item.streamId == id || item.gameId == id || item.id == id)
            }
        }

        if (captured.isEmpty()) return false

        val streamItems = mutableListOf<StreamCornerStreamItem>()
        for ((_, json) in captured) {
            val item = parseDetailPayload(json) ?: continue
            if (item.streamId == id || item.gameId == id || item.id == id) {
                item.streams?.let { streamItems.addAll(it) }
                item.live?.let { streamItems.addAll(it) }
            }
        }

        val candidates = streamItems
            .filter { !it.embedUrl.isNullOrBlank() }
            .distinctBy { it.embedUrl }
            .take(8)

        if (candidates.isEmpty()) return false

        candidates.resolveConcurrently { streamItem ->
            val embedUrl = streamItem.embedUrl ?: return@resolveConcurrently
            val sName = streamItem.sourceName?.trim()?.ifEmpty { "Stream" } ?: "Stream"
            loadEmbed(embedUrl, "$BASE_URL/", subtitleCallback) { link ->
                val labeled = withQualityLabel("StreamCorner - $sName", link.quality)
                callback(
                    ExtractorLink(
                        source = "StreamCorner",
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

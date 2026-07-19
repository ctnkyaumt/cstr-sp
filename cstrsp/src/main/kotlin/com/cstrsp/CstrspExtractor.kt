package com.cstrsp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

open class CstrspExtractor(override val mainUrl: String, private val context: Context) : ExtractorApi() {
    override val name            = "Cstrsp Extractor (${mainUrl.substringAfter("://").substringBefore("/")})"
    override val requiresReferer = false

    // A playlist URL the sniffer saw, tagged with the order its request was issued in.
    // isMaster: true = confirmed master playlist (#EXT-X-STREAM-INF seen or "master" in
    // the URL), false = confirmed media/variant playlist, null = captured blind (URL
    // contained ".m3u", body never probed).
    private data class Candidate(
        val seq: Int,
        val url: String,
        val headers: Map<String, String>,
        val isMaster: Boolean?,
        val maxHeight: Int? = null
    )

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Local, not a class field: this extractor is registered once per domain and
        // reused, and loadLinks now resolves multiple candidate streams concurrently.
        // Several of them often share the same embed domain (e.g. one source's stream
        // numbers 1-6 can all route through embed.st), so a shared webView field would
        // have one call's cleanup destroy another call's in-flight WebView.
        lateinit var webView: WebView
        // Don't finish on the first playlist that resolves. HLS players request the
        // master playlist (all resolutions) before any single-quality variant, but when
        // the master's URL doesn't contain ".m3u" it is only detected via a slow network
        // probe, while the variant's ".m3u8" URL is captured instantly — so first-wins
        // locked playback to one quality (usually 720p) with no resolution switching.
        // Instead we collect every candidate for a short grace window and then pick a
        // confirmed master first, falling back to the earliest-requested candidate.
        val selectionDone = AtomicBoolean(false)
        val requestSeq = AtomicInteger(0)
        val candidates = ConcurrentLinkedQueue<Candidate>()
        val firstCaptureAt = AtomicLong(0L)
        // Captured on the main thread; shouldInterceptRequest runs on a background thread
        // where calling any WebView method (e.g. settings.userAgentString) would crash.
        var cachedUserAgent: String? = null
        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled                  = true
                    domStorageEnabled                  = true
                    javaScriptCanOpenWindowsAutomatically = true
                    loadWithOverviewMode               = true
                    useWideViewPort                    = true
                    allowFileAccess                    = true
                    builtInZoomControls                = true
                    displayZoomControls                = false
                    allowContentAccess                 = true
                    mediaPlaybackRequiresUserGesture   = false
                    mixedContentMode                   = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                cachedUserAgent = settings.userAgentString

                webViewClient = object : WebViewClient() {
                    // These embed pages are ad-heavy and some fire a forced top-frame
                    // navigation (e.g. to a data: URI) within a second of load, which tears
                    // down the page's own player script before it ever requests the real
                    // stream. Block anything that isn't a normal http(s) navigation so the
                    // player keeps running; this never affects the initial loadUrl() below,
                    // since shouldOverrideUrlLoading only fires for navigations the WebView
                    // itself initiates afterwards.
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme != null && scheme != "http" && scheme != "https"
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (selectionDone.get()) return super.shouldInterceptRequest(view, request)

                        val method = request?.method ?: "GET"
                        if (method.uppercase() != "GET") {
                            return super.shouldInterceptRequest(view, request)
                        }

                        @Suppress("NAME_SHADOWING") val reqUrl = request?.url.toString()
                        val isPlaylistUrl = reqUrl.contains(".m3u")
                        // Cheap URL pre-filter: static assets and media segments can never
                        // be a playlist, so don't spawn a probe thread for them — these
                        // ad-heavy embed pages fire hundreds of such requests.
                        if (!isPlaylistUrl && isStaticAsset(reqUrl)) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        val headers = request?.requestHeaders?.toMutableMap() ?: mutableMapOf()
                        // Issue order of the request, not completion order of the probe:
                        // this is what lets an earlier master beat a faster variant.
                        val seq = requestSeq.getAndIncrement()

                        // Add WebView cookies to the headers so ExoPlayer can use them
                        val cookie = android.webkit.CookieManager.getInstance().getCookie(reqUrl)
                        if (cookie != null) {
                            headers["Cookie"] = cookie
                        }

                        // Ensure User-Agent is present. Use the value cached on the main
                        // thread — never touch view.settings here (background thread).
                        if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
                            headers["User-Agent"] = cachedUserAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                        }

                        if (isPlaylistUrl) {
                            // Captured blind, without fetching the body: probing could consume
                            // a one-time playback token and break the URL for ExoPlayer.
                            // "master" in the URL is a safe hint; anything else stays unknown.
                            // No network involved, so no thread needed either.
                            val isMaster = if (reqUrl.contains("master", ignoreCase = true)) true else null
                            candidates.add(Candidate(seq, reqUrl, headers, isMaster, null))
                            firstCaptureAt.compareAndSet(0L, System.currentTimeMillis())
                        } else {
                            Thread {
                                probePlaylist(reqUrl, headers) { sourceUrl, outHeaders, isMaster, maxHeight ->
                                    if (!selectionDone.get()) {
                                        candidates.add(Candidate(seq, sourceUrl, outHeaders, isMaster, maxHeight))
                                        firstCaptureAt.compareAndSet(0L, System.currentTimeMillis())
                                    }
                                }
                            }.start()
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                if (referer != null) {
                    loadUrl(url, mapOf("Referer" to referer))
                } else {
                    loadUrl(url)
                }
            }
        }

        try {
            var waitTime = 0
            while (waitTime < 15000) {
                delay(200)
                waitTime += 200
                val first = firstCaptureAt.get()
                if (first == 0L) continue
                // A confirmed master is decisive. Otherwise hold a short grace window
                // from the first capture so a slower-probing master can still land.
                val hasMaster = candidates.any { it.isMaster == true }
                if (hasMaster || System.currentTimeMillis() - first >= GRACE_MS) {
                    break
                }
            }
            // Also reached on timeout: take whatever arrived even if the grace window
            // hadn't elapsed yet (a late capture is still better than none).
            selectionDone.set(true)
            val pageOrigin = runCatching {
                URL(url).let { "${it.protocol}://${it.host}" }
            }.getOrNull()
            // WebView strips Referer/Origin from requestHeaders for JS-issued (XHR/fetch)
            // requests, so the captured headers usually carry neither. Hotlink-protected CDNs
            // then reject ExoPlayer's request with 403 — surfacing as playback error 2004
            // (ERROR_CODE_IO_BAD_HTTP_STATUS) even though a link was found. Fill in what a
            // browser sends for a cross-origin media request. Anything the WebView did
            // provide wins.
            fun headersFor(c: Candidate): Pair<String, Map<String, String>> {
                val out = c.headers.toMutableMap()
                val ref = out.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                    ?: pageOrigin?.let { "$it/" } ?: url
                if (out.keys.none { it.equals("Referer", true) }) out["Referer"] = ref
                if (pageOrigin != null && out.keys.none { it.equals("Origin", true) }) {
                    out["Origin"] = pageOrigin
                }
                return ref to out
            }
            // Preference order: confirmed master, then blind-captured, then a known variant;
            // earliest-requested first within each tier.
            val ordered = (
                candidates.filter { it.isMaster == true }.sortedBy { it.seq } +
                    candidates.filter { it.isMaster == null }.sortedBy { it.seq } +
                    candidates.filter { it.isMaster == false }.sortedBy { it.seq }
                ).distinctBy { it.url }
            // Don't hand the player a URL the CDN refuses — that is exactly the 2004 the user
            // sees. Re-requesting a live HLS playlist is safe (every player polls them
            // continuously); media segments, which can be single-use, are never probed.
            val chosen = withContext(Dispatchers.IO) {
                ordered.firstOrNull { isServable(it.url, headersFor(it).second) }
                    ?: ordered.firstOrNull()
            }
            chosen?.let { c ->
                val (referer, outHeaders) = headersFor(c)
                callback.invoke(
                    ExtractorLink(
                        source  = this@CstrspExtractor.name,
                        name    = this@CstrspExtractor.name,
                        url     = c.url,
                        referer = referer,
                        quality = heightToQuality(c.maxHeight),
                        type    = ExtractorLinkType.M3U8,
                        headers = outHeaders
                    )
                )
            }
        } finally {
            selectionDone.set(true)
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                try {
                    webView.destroy()
                } catch (e: Exception) {}
            }
        }
    }

    // Probes a URL that *might* be a playlist served without ".m3u" in its path: fetches
    // the head of the body and captures it only when it turns out to be an HLS playlist.
    private fun probePlaylist(url: String, headers: Map<String, String>?, onResponseCaptured: (url: String, headers: Map<String, String>, isMaster: Boolean?, maxHeight: Int?) -> Unit) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            headers?.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            // Add WebView cookies
            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (cookies != null) {
                connection.setRequestProperty("Cookie", cookies)
            }

            connection.connect()

            val contentType = connection.contentType ?: ""
            val typeIsM3u8 = contentType.contains("mpegurl", ignoreCase = true)

            // Read the first chunk to (a) confirm it's a playlist when the Content-Type
            // is generic and (b) classify master vs variant: masters carry
            // #EXT-X-STREAM-INF entries, media playlists carry #EXTINF segments.
            // Masters are tiny, so 8KB is plenty to find the marker.
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val head = CharArray(8192)
            var total = 0
            while (total < head.size) {
                val n = reader.read(head, total, head.size - total)
                if (n <= 0) break
                total += n
            }
            reader.close()
            val body = if (total > 0) String(head, 0, total) else ""

            if (typeIsM3u8 || body.startsWith("#EXTM")) {
                val isMaster = body.contains("#EXT-X-STREAM-INF")
                val maxHeight = if (isMaster) {
                    RESOLUTION_REGEX.findAll(body)
                        .mapNotNull { it.groupValues[1].toIntOrNull() }
                        .maxOrNull()
                } else null
                onResponseCaptured(url, headers ?: mapOf(), isMaster, maxHeight)
            }
        } catch (e: Exception) {
            // Ignore connection errors
        }
    }

    companion object {
        private const val GRACE_MS = 2000L
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
        // Extensions that can never be an HLS playlist. ".ts"/".m4s"/".mp4" are media
        // segments; playlist URLs are caught earlier by the ".m3u" check.
        private val SKIP_EXTENSIONS = arrayOf(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif", ".ico",
            ".woff", ".woff2", ".ttf", ".otf", ".ts", ".m4s", ".mp4", ".webm",
            ".mp3", ".aac", ".wasm", ".map"
        )

        // True unless the server explicitly rejects the request. Only the status line is read
        // — the body is never consumed — and any network/TLS error counts as inconclusive so
        // a flaky probe can never hide a working stream.
        private fun isServable(target: String, headers: Map<String, String>): Boolean = try {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
            }
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            conn.disconnect()
            code !in 400..599
        } catch (e: Exception) {
            true
        }

        // Checked against the path only, so query strings ("logo.png?v=2") can't dodge it.
        private fun isStaticAsset(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#')
            return path.endsWith("/fetch") || SKIP_EXTENSIONS.any { path.endsWith(it, ignoreCase = true) }
        }

        fun heightToQuality(h: Int?): Int = when {
            h == null -> Qualities.Unknown.value
            h >= 2160 -> Qualities.P2160.value
            h >= 1080 -> Qualities.P1080.value
            h >= 720  -> Qualities.P720.value
            h >= 480  -> Qualities.P480.value
            h >= 360  -> Qualities.P360.value
            h >= 240  -> Qualities.P240.value
            else      -> Qualities.P144.value
        }
    }
}

package com.cstrsp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebChromeClient
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

    private data class Candidate(
        val seq: Int,
        val url: String,
        val headers: Map<String, String>,
        val isMaster: Boolean?,
        val maxHeight: Int? = null
    )

    private fun triggerPlayback(view: WebView?) {
        view?.evaluateJavascript(
            """
            (function() {
                try {
                    if (window.jwplayer && typeof window.jwplayer === 'function') {
                        try { window.jwplayer().play(); } catch(e){}
                    }
                    const videos = document.querySelectorAll('video');
                    videos.forEach(function(v) {
                        try {
                            v.muted = true;
                            v.play();
                        } catch(e) {}
                    });
                    const selectors = [
                        '.jw-display-icon-display',
                        '.jw-icon-playback',
                        '.jw-preview',
                        '.player-poster',
                        '#player',
                        'button.play',
                        '.play-btn',
                        '[class*="play"]',
                        '[id*="play"]'
                    ];
                    selectors.forEach(function(sel) {
                        const el = document.querySelector(sel);
                        if (el) {
                            try { el.click(); } catch(e) {}
                        }
                    });
                } catch(e) {}
            })();
            """.trimIndent(),
            null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        lateinit var webView: WebView
        val selectionDone = AtomicBoolean(false)
        val requestSeq = AtomicInteger(0)
        val candidates = ConcurrentLinkedQueue<Candidate>()
        val firstCaptureAt = AtomicLong(0L)
        var cachedUserAgent: String? = null

        withContext(Dispatchers.Main) {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled                  = true
                    domStorageEnabled                  = true
                    databaseEnabled                    = true
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
                webChromeClient = WebChromeClient()

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        if (scheme != null && scheme != "http" && scheme != "https") return true

                        if (request?.isForMainFrame == true) {
                            val requested = request.url.toString().substringBefore('#')
                            val initial = url.substringBefore('#')
                            if (requested != initial) return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        triggerPlayback(view)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (selectionDone.get()) return super.shouldInterceptRequest(view, request)

                        val method = request?.method ?: "GET"
                        if (method.uppercase() != "GET") {
                            return super.shouldInterceptRequest(view, request)
                        }

                        @Suppress("NAME_SHADOWING") val reqUrl = request?.url.toString()
                        val isPlaylistUrl = reqUrl.contains(".m3u", ignoreCase = true) ||
                            reqUrl.contains(".mpd", ignoreCase = true) ||
                            reqUrl.contains("/hls/", ignoreCase = true) ||
                            reqUrl.contains("manifest", ignoreCase = true) ||
                            reqUrl.contains("playlist", ignoreCase = true)

                        if (!isPlaylistUrl && isStaticAsset(reqUrl)) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        val headers = request?.requestHeaders?.toMutableMap() ?: mutableMapOf()
                        val seq = requestSeq.getAndIncrement()

                        val cookie = android.webkit.CookieManager.getInstance().getCookie(reqUrl)
                        if (cookie != null) {
                            headers["Cookie"] = cookie
                        }

                        if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
                            headers["User-Agent"] = cachedUserAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                        }

                        if (isPlaylistUrl) {
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
                delay(250)
                waitTime += 250

                if (waitTime % 1000 == 0) {
                    withContext(Dispatchers.Main) {
                        try {
                            triggerPlayback(webView)
                        } catch (e: Exception) {}
                    }
                }

                val first = firstCaptureAt.get()
                if (first == 0L) continue
                val hasMaster = candidates.any { it.isMaster == true }
                if (hasMaster || System.currentTimeMillis() - first >= GRACE_MS) {
                    break
                }
            }
            selectionDone.set(true)
            val pageOrigin = runCatching {
                URL(url).let { "${it.protocol}://${it.host}" }
            }.getOrNull()

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

            val ordered = (
                candidates.filter { it.isMaster == true }.sortedBy { it.seq } +
                    candidates.filter { it.isMaster == null }.sortedBy { it.seq } +
                    candidates.filter { it.isMaster == false }.sortedBy { it.seq }
                ).distinctBy { it.url }

            ordered.firstOrNull()?.let { c ->
                val (ref, outHeaders) = headersFor(c)
                callback.invoke(
                    ExtractorLink(
                        source  = this@CstrspExtractor.name,
                        name    = this@CstrspExtractor.name,
                        url     = c.url,
                        referer = ref,
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

    private fun probePlaylist(url: String, headers: Map<String, String>?, onResponseCaptured: (url: String, headers: Map<String, String>, isMaster: Boolean?, maxHeight: Int?) -> Unit) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            headers?.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            if (headers?.keys?.none { it.equals("Referer", true) } == true) {
                connection.setRequestProperty("Referer", mainUrl)
            }
            if (headers?.keys?.none { it.equals("Origin", true) } == true) {
                runCatching { URL(mainUrl).let { "${it.protocol}://${it.host}" } }.getOrNull()?.let {
                    connection.setRequestProperty("Origin", it)
                }
            }

            val cookieManager = android.webkit.CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (cookies != null) {
                connection.setRequestProperty("Cookie", cookies)
            }

            connection.connect()

            val contentType = connection.contentType ?: ""
            val typeIsM3u8 = contentType.contains("mpegurl", ignoreCase = true)

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
        private val SKIP_EXTENSIONS = arrayOf(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".gif", ".ico",
            ".woff", ".woff2", ".ttf", ".otf", ".ts", ".m4s", ".mp4", ".webm",
            ".mp3", ".aac", ".wasm", ".map"
        )

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

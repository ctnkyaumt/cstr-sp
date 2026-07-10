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
import java.util.concurrent.atomic.AtomicBoolean

open class CstrspExtractor(override val mainUrl: String, private val context: Context) : ExtractorApi() {
    override val name            = "Cstrsp Extractor (${mainUrl.substringAfter("://").substringBefore("/")})"
    override val requiresReferer = false

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // Local, not a class field: this extractor is registered once per domain and
        // reused, and loadLinks now resolves multiple candidate streams concurrently.
        // Several of them often share the same embed domain (e.g. one source's stream
        // numbers 1-6 can all route through embed.st), so a shared webView field would
        // have one call's cleanup destroy another call's in-flight WebView.
        lateinit var webView: WebView
        // AtomicBoolean: shouldInterceptRequest fires on a fresh background thread per
        // request, so a plain var here is a TOCTOU race — two in-flight requests (e.g. a
        // master playlist and a variant playlist landing back-to-back) can both pass the
        // "not done yet" check and both invoke callback before either write is visible.
        val isDone = AtomicBoolean(false)
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
                        if (isDone.get()) return super.shouldInterceptRequest(view, request)
                        
                        val method = request?.method ?: "GET"
                        if (method.uppercase() != "GET") {
                            return super.shouldInterceptRequest(view, request)
                        }

                        @Suppress("NAME_SHADOWING") val reqUrl = request?.url.toString()
                        val headers = request?.requestHeaders?.toMutableMap() ?: mutableMapOf()
                        
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

                        Thread {
                            fetchAndCheckResponse(reqUrl, headers) { sourceUrl, outHeaders ->
                                if (isDone.compareAndSet(false, true)) {
                                    callback.invoke(
                                        ExtractorLink(
                                            source  = this@CstrspExtractor.name,
                                            name    = this@CstrspExtractor.name,
                                            url     = sourceUrl,
                                            referer = outHeaders["Referer"] ?: outHeaders["referer"] ?: mainUrl,
                                            quality = Qualities.Unknown.value,
                                            type    = ExtractorLinkType.M3U8,
                                            headers = outHeaders
                                        )
                                    )
                                }
                            }
                        }.start()

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
            while (!isDone.get() && waitTime < 15000) {
                delay(200)
                waitTime += 200
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                try {
                    webView.destroy()
                } catch (e: Exception) {}
            }
        }
    }

    private fun fetchAndCheckResponse(url: String, headers: Map<String, String>?, onResponseCaptured: (url: String, headers: Map<String, String>) -> Unit) {
        if (url.contains(".m3u")) {
            onResponseCaptured(url, headers ?: mapOf())
            return
        }
        
        // Skip obvious static assets and API endpoints that use GET
        if (url.endsWith(".js") || url.endsWith(".css") || url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".webp") || url.endsWith(".svg") || url.endsWith(".gif") || url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ts") || url.endsWith("/fetch")) {
            return
        }

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
            
            // Fast Content-Type check
            val contentType = connection.contentType ?: ""
            if (contentType.contains("mpegurl", ignoreCase = true) || contentType.contains("application/x-mpegurl", ignoreCase = true)) {
                onResponseCaptured(url, headers ?: mapOf())
                return
            }
            
            // Read only first few bytes to check for #EXTM3U
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val firstLine = reader.readLine()
            if (firstLine != null && firstLine.startsWith("#EXTM")) {
                onResponseCaptured(url, headers ?: mapOf())
            }
            reader.close()
        } catch (e: Exception) {
            // Ignore connection errors
        }
    }
}

package com.cstrsp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
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
    private lateinit var webView: WebView

    // JS interface to receive intercepted URLs from injected scripts
    class StreamInterceptor(private val callback: (String) -> Unit) {
        @JavascriptInterface
        fun onStreamFound(url: String) {
            callback(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val foundStream = AtomicBoolean(false)

        val emitLink = { sourceUrl: String, reqHeaders: Map<String, String> ->
            if (foundStream.compareAndSet(false, true)) {
                callback.invoke(
                    ExtractorLink(
                        source  = this@CstrspExtractor.name,
                        name    = this@CstrspExtractor.name,
                        url     = sourceUrl,
                        referer = reqHeaders["Referer"] ?: reqHeaders["referer"] ?: mainUrl,
                        quality = Qualities.Unknown.value,
                        type    = ExtractorLinkType.M3U8,
                        headers = reqHeaders
                    )
                )
            }
        }

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
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // Add JS interface so injected scripts can send back the m3u8 URL
                addJavascriptInterface(
                    StreamInterceptor { streamUrl ->
                        emitLink(streamUrl, mapOf("Referer" to mainUrl))
                    },
                    "StreamInterceptor"
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        // Inject JS to hook into Clappr and JWPlayer source setup
                        view?.evaluateJavascript("""
                            (function() {
                                // Hook into Clappr Player if it exists
                                if (typeof Clappr !== 'undefined') {
                                    var origPlayer = Clappr.Player;
                                    Clappr.Player = function(options) {
                                        if (options && options.source) {
                                            StreamInterceptor.onStreamFound(options.source);
                                        }
                                        if (options && options.sources && options.sources.length > 0) {
                                            StreamInterceptor.onStreamFound(options.sources[0]);
                                        }
                                        return new origPlayer(options);
                                    };
                                    Clappr.Player.prototype = origPlayer.prototype;
                                }
                                
                                // Hook into JWPlayer if it exists
                                if (typeof jwplayer !== 'undefined') {
                                    var origJw = jwplayer;
                                    window.jwplayer = function() {
                                        var instance = origJw.apply(this, arguments);
                                        var origSetup = instance.setup;
                                        instance.setup = function(config) {
                                            if (config && config.sources && config.sources.length > 0) {
                                                StreamInterceptor.onStreamFound(config.sources[0].file || config.sources[0].src || '');
                                            }
                                            if (config && config.file) {
                                                StreamInterceptor.onStreamFound(config.file);
                                            }
                                            if (config && config.playlist && config.playlist.length > 0) {
                                                var item = config.playlist[0];
                                                if (item.sources && item.sources.length > 0) {
                                                    StreamInterceptor.onStreamFound(item.sources[0].file || '');
                                                } else if (item.file) {
                                                    StreamInterceptor.onStreamFound(item.file);
                                                }
                                            }
                                            return origSetup.call(this, config);
                                        };
                                        return instance;
                                    };
                                    Object.keys(origJw).forEach(function(k) { window.jwplayer[k] = origJw[k]; });
                                }
                                
                                // Also hook fetch and XMLHttpRequest to catch m3u8 URLs
                                var origFetch = window.fetch;
                                window.fetch = function() {
                                    var fetchUrl = arguments[0];
                                    if (typeof fetchUrl === 'string' && (fetchUrl.indexOf('.m3u8') !== -1 || fetchUrl.indexOf('.m3u') !== -1)) {
                                        StreamInterceptor.onStreamFound(fetchUrl);
                                    }
                                    return origFetch.apply(this, arguments);
                                };
                                
                                var origOpen = XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open = function() {
                                    var xhrUrl = arguments[1];
                                    if (typeof xhrUrl === 'string' && (xhrUrl.indexOf('.m3u8') !== -1 || xhrUrl.indexOf('.m3u') !== -1)) {
                                        StreamInterceptor.onStreamFound(xhrUrl);
                                    }
                                    return origOpen.apply(this, arguments);
                                };
                            })();
                        """.trimIndent(), null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        if (foundStream.get()) return super.shouldInterceptRequest(view, request)
                        
                        val method = request?.method ?: "GET"
                        if (method.uppercase() != "GET") {
                            return super.shouldInterceptRequest(view, request)
                        }

                        @Suppress("NAME_SHADOWING") val url = request?.url.toString()
                        val headers = request?.requestHeaders

                        // Quick URL pattern check — if the URL itself looks like an m3u8, emit immediately
                        if (isM3u8Url(url)) {
                            emitLink(url, headers ?: mapOf())
                            return super.shouldInterceptRequest(view, request)
                        }
                        
                        // Skip obvious static assets
                        if (isStaticAsset(url)) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        Thread {
                            fetchAndCheckResponse(url, headers) { sourceUrl, hdrs ->
                                emitLink(sourceUrl, hdrs)
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

        // Wait up to 20 seconds, but exit early if we found a stream
        for (i in 0 until 40) {
            if (foundStream.get()) break
            delay(500)
        }

        // Clean up WebView
        withContext(Dispatchers.Main) {
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (e: Exception) {}
        }
    }

    private fun isM3u8Url(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".m3u") ||
               lower.contains("/playlist") && lower.contains("vipstreams") ||
               lower.contains("/master") && lower.contains("vipstreams")
    }

    private fun isStaticAsset(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".js") || lower.endsWith(".css") || 
               lower.endsWith(".png") || lower.endsWith(".jpg") || 
               lower.endsWith(".jpeg") || lower.endsWith(".webp") || 
               lower.endsWith(".svg") || lower.endsWith(".gif") || 
               lower.endsWith(".woff") || lower.endsWith(".woff2") || 
               lower.endsWith(".ttf") || lower.endsWith(".ico") ||
               lower.endsWith(".ts") || lower.endsWith("/fetch") ||
               lower.contains("tag.min.js") || lower.contains("ad.html") ||
               lower.contains("google") || lower.contains("facebook") ||
               lower.contains("analytics")
    }

    private fun fetchAndCheckResponse(url: String, headers: Map<String, String>?, onResponseCaptured: (url: String, headers: Map<String, String>) -> Unit) {
        if (isM3u8Url(url)) {
            onResponseCaptured(url, headers ?: mapOf())
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
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val cookies = cookieManager.getCookie(url)
                if (cookies != null) {
                    connection.setRequestProperty("Cookie", cookies)
                }
            } catch (e: Exception) {}
            
            connection.connect()
            
            // Fast Content-Type check
            val contentType = connection.contentType ?: ""
            if (contentType.contains("mpegurl", ignoreCase = true) || contentType.contains("application/x-mpegurl", ignoreCase = true)) {
                onResponseCaptured(url, headers ?: mapOf())
                connection.disconnect()
                return
            }
            
            // Read only first few bytes to check for #EXTM3U
            try {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val firstLine = reader.readLine()
                if (firstLine != null && firstLine.startsWith("#EXTM")) {
                    onResponseCaptured(url, headers ?: mapOf())
                }
                reader.close()
            } catch (e: Exception) {}
            
            connection.disconnect()
        } catch (e: Exception) {
            // Ignore connection errors
        }
    }
}

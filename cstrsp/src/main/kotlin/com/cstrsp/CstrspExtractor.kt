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

open class CstrspExtractor(override val mainUrl: String, private val context: Context) : ExtractorApi() {
    override val name            = "Cstrsp Extractor (${mainUrl.substringAfter("://").substringBefore("/")})"
    override val requiresReferer = false
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
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
                    userAgentString                    = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0"
                }

                evaluateJavascript(
                    """
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });
                    Object.defineProperty(navigator, 'language', { get: () => 'en-US' });
                    window.chrome = { runtime: {} };
                    """.trimIndent()
                ) {}

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        @Suppress("NAME_SHADOWING") val url = request?.url.toString()
                        val headers = request?.requestHeaders

                        Thread {
                            fetchAndCheckResponse(url, headers) { sourceUrl, headers ->
                                callback.invoke(
                                    ExtractorLink(
                                        source  = this@CstrspExtractor.name,
                                        name    = this@CstrspExtractor.name,
                                        url     = sourceUrl,
                                        referer = headers["Referer"] ?: headers["referer"] ?: mainUrl,
                                        quality = Qualities.Unknown.value,
                                        type    = ExtractorLinkType.M3U8,
                                        headers = headers
                                    )
                                )
                            }
                        }.start()

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(url)
            }
        }

        delay(10_000)
    }

    private fun fetchAndCheckResponse(url: String, headers: Map<String, String>?, onResponseCaptured: (url: String, headers: Map<String, String>) -> Unit) {
        if (url.contains(".m3u")) {
            onResponseCaptured(url, headers ?: mapOf())
            return
        }
        
        // Skip obvious static assets
        if (url.endsWith(".js") || url.endsWith(".css") || url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".webp") || url.endsWith(".svg") || url.endsWith(".gif") || url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ts")) {
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

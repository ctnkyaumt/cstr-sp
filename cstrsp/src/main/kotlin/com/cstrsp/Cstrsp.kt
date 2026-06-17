package com.cstrsp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Cstrsp : MainAPI() {
    override var mainUrl = "https://strmd.link"
    override var name = "cstrsp"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        mainUrl to "Live"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Fetch strmd.link and find the online element
        val document = app.get(mainUrl).document
        
        // Find all a elements that have a text-green-500 class indicating online
        val aElements = document.select("body > div:first-child > div > div > a").toList()
        
        var targetUrl: String? = null
        for (a in aElements) {
            val h2 = a.selectFirst("div > h2.text-green-500")
            if (h2 != null) {
                targetUrl = fixUrlNull(a.attr("href"))
                break
            }
        }
        
        if (targetUrl == null) {
            return newHomePageResponse(request.name, emptyList())
        }
        
        // Load targetUrl and extract cards
        val targetDoc = app.get(targetUrl).document
        val searchResponses = mutableListOf<SearchResponse>()
        
        // Cards are located in similar paths
        // According to user: body > div:nth-child(1) > div:nth-child(1) > div > div.h-full.mt-2.p-1 > div:nth-child(4) > div > div > div > a
        val cards = targetDoc.select("div.h-full.mt-2.p-1 > div > div > div > div > a").toList()
        
        for (card in cards) {
            val titleElem = card.selectFirst("h1")
            val imgElem = card.selectFirst("img")
            
            val title = titleElem?.text() ?: titleElem?.attr("title") ?: continue
            val href = fixUrlNull(card.attr("href")) ?: continue
            val posterUrl = fixUrlNull(imgElem?.attr("src"))
            
            searchResponses.add(
                newLiveSearchResponse(title, href, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }

        return newHomePageResponse(request.name, searchResponses)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Perform the same logic as getMainPage but filter by query
        val document = app.get(mainUrl).document
        val aElements = document.select("body > div:first-child > div > div > a").toList()
        
        var targetUrl: String? = null
        for (a in aElements) {
            val h2 = a.selectFirst("div > h2.text-green-500")
            if (h2 != null) {
                targetUrl = fixUrlNull(a.attr("href"))
                break
            }
        }
        
        if (targetUrl == null) {
            return emptyList()
        }
        
        val targetDoc = app.get(targetUrl).document
        val searchResponses = mutableListOf<SearchResponse>()
        
        val cards = targetDoc.select("div.h-full.mt-2.p-1 > div > div > div > div > a").toList()
        
        for (card in cards) {
            val titleElem = card.selectFirst("h1")
            val imgElem = card.selectFirst("img")
            
            val title = titleElem?.text() ?: titleElem?.attr("title") ?: continue
            if (!title.contains(query, ignoreCase = true)) continue
            
            val href = fixUrlNull(card.attr("href")) ?: continue
            val posterUrl = fixUrlNull(imgElem?.attr("src"))
            
            searchResponses.add(
                newLiveSearchResponse(title, href, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }

        return searchResponses
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Provide the same url back to loadLinks to extract streams
        // The title can be retrieved from the page
        val title = document.selectFirst("title")?.text() ?: "Live Stream"

        return newLiveStreamLoadResponse(title, url, url) {
            // Optional: posterUrl etc.
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // data is the url of the details page
        val document = app.get(data).document
        
        // Find p-2 a tags
        val p2Links = document.select("div.p-2 > a").toList()
        
        for (link in p2Links) {
            val href = fixUrlNull(link.attr("href")) ?: continue
            
            // Try loading this sub-page
            try {
                val subPage = app.get(href).document
                
                // Find iframe or #player source
                val iframe = subPage.selectFirst("iframe")
                val iframeSrc = fixUrlNull(iframe?.attr("src"))
                
                if (iframeSrc != null) {
                    // It could be an M3U8 directly, or an embed.
                    // For now, let's just use it as M3U8 if we detect it, or rely on extractors.
                    // Based on user note: deeper element might be #player > div.jw-wrapper > ... > video
                    // Let's load the iframeSrc to see if it's an M3U8 or if we can extract JWPlayer source
                    try {
                        val iframeHtml = app.get(iframeSrc).text
                        // Extract m3u8 using regex from script blocks
                        val m3u8Regex = Regex("[\"'](https?://[^\"']+\\.m3u8.*?)[\"']")
                        val matchResult = m3u8Regex.find(iframeHtml)
                        if (matchResult != null) {
                            val streamUrl = matchResult.groupValues[1]
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - Stream",
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf("Referer" to iframeSrc)
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        } else {
                            // If no m3u8 regex match, maybe the iframeSrc is a well-known embed
                            // Let Cloudstream generic extractor try
                            loadExtractor(iframeSrc, data, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        // Ignore extraction errors and continue to next
                    }
                }
            } catch (e: Exception) {
                // Try next
            }
        }
        return true
    }
}

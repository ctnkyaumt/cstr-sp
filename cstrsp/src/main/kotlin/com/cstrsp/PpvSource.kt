package com.cstrsp

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

data class PPVSourceItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("data") val data: String? = null
)

data class PPVStreamDetailResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("data") val data: PPVStream? = null
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
    @JsonProperty("uri") val uri: String? = null,
    @JsonProperty("tag") val tag: String? = null,
    @JsonProperty("source_tag") val source_tag: String? = null,
    @JsonProperty("locale") val locale: String? = null,
    @JsonProperty("sources") val sources: List<PPVSourceItem>? = null,
    @JsonProperty("starts_at") val startsAt: Long? = null,
    @JsonProperty("ends_at") val endsAt: Long? = null,
    @JsonProperty("always_live") val alwaysLive: Int? = null,
    @JsonProperty("substreams") val substreams: List<PPVSubstream>? = null
)

data class PPVCategory(
    @JsonProperty("category_name") val category_name: String? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("always_live") val alwaysLive: Boolean? = null,
    @JsonProperty("streams") val streams: List<PPVStream>? = null
)

data class PPVResponse(
    @JsonProperty("streams") val streams: List<PPVCategory>? = null
)

object PpvSource {
    private const val PPV_BASE = "https://ppv.st"
    private val ppvDomains = listOf("api.ppv.st", "api.ppv.cx", "api.ppv.lc", "api.ppv.to", "api.ppv.is")

    suspend fun fetchPPVApi(): PPVResponse? = CstrspCache.cached("ppv") {
        ppvDomains.firstNotNullOfOrNull { domain ->
            try {
                app.get("https://$domain/api/streams", referer = "$PPV_BASE/").parsedSafe<PPVResponse>()?.takeIf { it.streams != null }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun fetchPPVStream(streamId: Int): PPVStream? = CstrspCache.cached("ppv-stream-$streamId") {
        ppvDomains.firstNotNullOfOrNull { domain ->
            try {
                app.get("https://$domain/api/streams/$streamId", referer = "$PPV_BASE/").parsedSafe<PPVStreamDetailResponse>()?.data
            } catch (e: Exception) {
                null
            }
        }
    }

    fun isLivePpv(category: PPVCategory, stream: PPVStream): Boolean {
        if (category.alwaysLive == true || (stream.alwaysLive ?: 0) == 1) return true
        val now = System.currentTimeMillis() / 1000L
        val start = stream.startsAt ?: 0L
        val end = stream.endsAt ?: 0L
        if (start > 0L && now < start - 1800L) return false
        if (end > 0L && now > end + 1800L) return false
        return true
    }

    fun ppvPoster(stream: PPVStream): String? = stream.poster?.let {
        if (it.startsWith("http")) it else "$PPV_BASE$it"
    }

    fun ppvListable(category: PPVCategory, stream: PPVStream): Boolean =
        stream.id != null && isLivePpv(category, stream) &&
            (stream.iframe != null || stream.uri_name != null || stream.uri != null || !stream.sources.isNullOrEmpty() || !stream.substreams.isNullOrEmpty())

    fun ppvItem(api: MainAPI, stream: PPVStream): SearchResponse =
        api.newLiveSearchResponse("${stream.name ?: "Unknown Event"} [PPV]", "https://ppv.domains/${stream.id}") {
            this.posterUrl = ppvPoster(stream)
        }

    suspend fun getHomeSections(api: MainAPI): List<HomePageList> =
        fetchPPVApi()?.streams.orEmpty().mapNotNull { category ->
            val items = category.streams.orEmpty()
                .filter { ppvListable(category, it) }
                .map { ppvItem(api, it) }
            if (items.isEmpty()) null
            else HomePageList("${category.category_name ?: category.category ?: "Unknown"} [PPV]", items)
        }

    suspend fun search(api: MainAPI, matcher: QueryMatcher): List<SearchResponse> {
        val now = System.currentTimeMillis() / 1000L
        val seen = HashSet<String>()
        return fetchPPVApi()?.streams.orEmpty().flatMap { category ->
            category.streams.orEmpty()
                .filter { stream ->
                    val id = stream.id ?: return@filter false
                    val title = stream.name ?: "Unknown Event"
                    val end = stream.endsAt ?: 0L
                    val notEnded = category.alwaysLive == true || (stream.alwaysLive ?: 0) == 1 || end == 0L || now <= end + 1800L
                    notEnded && seen.add("$id-$title") &&
                        matcher.matches(
                            title,
                            stream.source_tag,
                            stream.tag
                        )
                }
                .map { ppvItem(api, it) }
        }
    }

    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (!url.startsWith("https://ppv.domains/")) return null
        val streamId = url.substringAfterLast("/").toIntOrNull() ?: return null
        val stream = fetchPPVApi()?.streams
            ?.firstNotNullOfOrNull { category -> category.streams?.find { it.id == streamId } }
            ?: fetchPPVStream(streamId)
            ?: return null
        val title = stream.name ?: "Live Stream"

        return api.newLiveStreamLoadResponse(
            name = "$title [PPV]",
            url = url,
            dataUrl = stream.toJson()
        ) {
            this.posterUrl = ppvPoster(stream)
            this.plot = title
        }
    }

    private fun normalizeIframeUrl(iframe: String?, uriName: String?): String? = when {
        !iframe.isNullOrBlank() -> when {
            iframe.startsWith("http://") || iframe.startsWith("https://") -> iframe
            iframe.startsWith("//") -> "https:$iframe"
            iframe.startsWith("/") -> "https://embedindia.st$iframe"
            else -> "https://embedindia.st/$iframe"
        }
        !uriName.isNullOrBlank() -> "https://embedindia.st/embed/$uriName"
        else -> null
    }

    private fun buildStreamLabel(sourceTag: String, locale: String?): String {
        val cleanTag = sourceTag.trim()
        val cleanLocale = locale?.trim()?.uppercase()
        return if (!cleanLocale.isNullOrEmpty() && !cleanTag.contains(cleanLocale, ignoreCase = true)) {
            "$cleanTag [$cleanLocale]"
        } else {
            cleanTag
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = try {
            AppUtils.parseJson<PPVStream>(data)
        } catch (e: Exception) {
            return false
        }

        val streamId = stream.id ?: return false
        val fullStream = if (stream.iframe.isNullOrBlank() && stream.sources.isNullOrEmpty() && stream.substreams.isNullOrEmpty() && stream.uri_name.isNullOrBlank() && stream.uri.isNullOrBlank()) {
            fetchPPVStream(streamId) ?: stream
        } else {
            stream
        }

        val iframes = mutableListOf<Pair<String, String>>()
        val mainIframe = fullStream.iframe
            ?: fullStream.sources?.firstOrNull { it.type == "iframe" || it.data?.contains("embed") == true }?.data
            ?: fullStream.sources?.firstOrNull()?.data
        val mainUri = fullStream.uri_name ?: fullStream.uri
        normalizeIframeUrl(mainIframe, mainUri)?.let {
            val label = buildStreamLabel(fullStream.source_tag ?: "Main", fullStream.locale)
            iframes.add(label to it)
        }
        if (mainIframe != null && mainIframe.contains("?") && !mainUri.isNullOrBlank()) {
            normalizeIframeUrl(null, mainUri)?.let { cleanUrl ->
                if (cleanUrl != mainIframe) {
                    val label = buildStreamLabel(fullStream.source_tag ?: "Main", fullStream.locale)
                    iframes.add("$label (Direct)" to cleanUrl)
                }
            }
        }

        fullStream.substreams?.forEach { sub ->
            normalizeIframeUrl(sub.iframe, sub.uri_name)?.let {
                val label = buildStreamLabel(sub.source_tag ?: sub.name ?: "Substream", sub.locale)
                iframes.add(label to it)
            }
            if (sub.iframe != null && sub.iframe.contains("?") && !sub.uri_name.isNullOrBlank()) {
                normalizeIframeUrl(null, sub.uri_name)?.let { cleanUrl ->
                    if (cleanUrl != sub.iframe) {
                        val label = buildStreamLabel(sub.source_tag ?: sub.name ?: "Substream", sub.locale)
                        iframes.add("$label (Direct)" to cleanUrl)
                    }
                }
            }
        }

        if (iframes.isEmpty()) return false

        iframes.distinctBy { it.second }.resolveConcurrently { (name, iframeUrl) ->
            loadExtractor(encodeUrlNonAscii(iframeUrl), "$PPV_BASE/", subtitleCallback) { link ->
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
}


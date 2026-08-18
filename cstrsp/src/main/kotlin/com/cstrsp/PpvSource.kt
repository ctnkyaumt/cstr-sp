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
    private val ppvDomains = listOf("api.ppv.st", "api.ppv.is", "api.ppv.lc", "api.ppv.cx", "api.ppv.to")

    suspend fun fetchPPVApi(): PPVResponse? = CstrspCache.cached("ppv") {
        ppvDomains.firstNotNullOfOrNull { domain ->
            try {
                app.get("https://$domain/api/streams").parsedSafe<PPVResponse>()?.takeIf { it.streams != null }
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
        if (start > 0L && now < start) return false
        if (end > 0L && now > end + 1800L) return false
        return true
    }

    fun ppvPoster(stream: PPVStream): String? = stream.poster?.let {
        val encoded = android.util.Base64.encodeToString(
            it.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        "${StreamedSource.mainUrl}/api/images/proxy/$encoded.webp"
    }

    fun ppvListable(category: PPVCategory, stream: PPVStream): Boolean =
        stream.id != null && isLivePpv(category, stream) &&
            (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty())

    fun MainAPI.ppvItem(stream: PPVStream): SearchResponse =
        newLiveSearchResponse("${stream.name ?: "Unknown Event"} [PPV]", "https://ppv.domains/${stream.id}") {
            this.posterUrl = ppvPoster(stream)
        }

    suspend fun MainAPI.getHomeSections(): List<HomePageList> =
        fetchPPVApi()?.streams.orEmpty().mapNotNull { category ->
            val items = category.streams.orEmpty()
                .filter { ppvListable(category, it) }
                .map { ppvItem(it) }
            if (items.isEmpty()) null
            else HomePageList("${category.category_name ?: category.category ?: "Unknown"} [PPV]", items)
        }

    suspend fun MainAPI.search(matcher: QueryMatcher): List<SearchResponse> =
        fetchPPVApi()?.streams.orEmpty().flatMap { category ->
            category.streams.orEmpty()
                .filter { stream ->
                    ppvListable(category, stream) &&
                        matcher.matches(stream.name ?: "Unknown Event", category.category_name, category.category)
                }
                .map { ppvItem(it) }
        }

    suspend fun MainAPI.load(url: String): LoadResponse? {
        if (!url.startsWith("https://ppv.domains/")) return null
        val streamId = url.substringAfterLast("/").toIntOrNull()
        val stream = fetchPPVApi()?.streams
            ?.firstNotNullOfOrNull { category -> category.streams?.find { it.id == streamId } }
            ?: return null
        val title = stream.name ?: "Live Stream"

        return newLiveStreamLoadResponse(
            name = "$title [PPV]",
            url = url,
            dataUrl = stream.toJson()
        ) {
            this.posterUrl = ppvPoster(stream)
            this.plot = title
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

        if (stream.id != null && (stream.iframe != null || stream.uri_name != null || !stream.substreams.isNullOrEmpty())) {
            val iframes = mutableListOf<Pair<String, String>>()
            val mainIframe = stream.iframe ?: stream.uri_name?.let { "https://embedindia.st/embed/$it" }
            if (mainIframe != null) {
                iframes.add("Main" to mainIframe)
            }
            stream.substreams?.forEach { sub ->
                val subIframe = sub.iframe ?: sub.uri_name?.let { "https://embedindia.st/embed/$it" }
                if (subIframe != null) {
                    iframes.add((sub.source_tag ?: sub.name ?: sub.locale ?: "Substream") to subIframe)
                }
            }

            iframes.resolveConcurrently { (name, iframeUrl) ->
                loadExtractor(encodeUrlNonAscii(iframeUrl), "https://embedindia.st/", subtitleCallback) { link ->
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
        return false
    }
}

package com.cstrsp

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Cstrsp : MainAPI() {
    override var mainUrl: String
        get() = StreamedSource.mainUrl
        set(value) {
            StreamedSource.mainUrl = value
        }
    override var name = "cstrsp"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        StreamedSource.checkAndGetDomain()
        val sections = coroutineScope {
            listOf(
                async { safeList { with(StreamedSource) { getHomeSections() } } },
                async { safeList { with(StreamfreeSource) { getHomeSections() } } },
                async { safeList { with(PpvSource) { getHomeSections() } } },
                async { safeList { with(WatchFootySource) { getHomeSections() } } },
                async { safeList { with(StreamSportsSource) { getHomeSections() } } },
                async { safeList { with(RoxieSourceProvider) { getHomeSections() } } }
            ).awaitAll()
        }.flatten()
        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        StreamedSource.checkAndGetDomain()
        val matcher = QueryMatcher(query)
        val results = mutableListOf<SearchResponse>()

        results.add(with(TrtSource) { searchItem() })

        coroutineScope {
            listOf(
                async { safeList { with(StreamedSource) { search(matcher) } } },
                async { safeList { with(StreamfreeSource) { search(matcher) } } },
                async { safeList { with(PpvSource) { search(matcher) } } },
                async { safeList { with(WatchFootySource) { search(matcher) } } },
                async { safeList { with(StreamSportsSource) { search(matcher) } } },
                async { safeList { with(RoxieSourceProvider) { search(matcher) } } }
            ).awaitAll()
        }.forEach { results.addAll(it) }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        StreamedSource.checkAndGetDomain()
        with(TrtSource) { load(url) }?.let { return it }
        with(StreamfreeSource) { load(url) }?.let { return it }
        with(PpvSource) { load(url) }?.let { return it }
        with(WatchFootySource) { load(url) }?.let { return it }
        with(StreamSportsSource) { load(url) }?.let { return it }
        with(RoxieSourceProvider) { load(url) }?.let { return it }
        return with(StreamedSource) { load(url) }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        StreamedSource.checkAndGetDomain()
        if (TrtSource.loadLinks(data, callback)) return true
        if (StreamfreeSource.loadLinks(data, subtitleCallback, callback)) return true
        if (PpvSource.loadLinks(data, subtitleCallback, callback)) return true
        if (WatchFootySource.loadLinks(data, subtitleCallback, callback)) return true
        if (StreamSportsSource.loadLinks(data, subtitleCallback, callback)) return true
        if (RoxieSourceProvider.loadLinks(data, subtitleCallback, callback)) return true
        return StreamedSource.loadLinks(data, subtitleCallback, callback)
    }
}

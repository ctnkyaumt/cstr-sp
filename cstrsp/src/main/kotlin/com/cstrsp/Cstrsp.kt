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
                async { safeList { StreamedSource.getHomeSections() } },
                async { safeList { StreamfreeSource.getHomeSections() } },
                async { safeList { PpvSource.getHomeSections() } },
                async { safeList { WatchFootySource.getHomeSections() } },
                async { safeList { StreamSportsSource.getHomeSections() } },
                async { safeList { RoxieSourceProvider.getHomeSections() } }
            ).awaitAll()
        }.flatten()
        return newHomePageResponse(sections)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        StreamedSource.checkAndGetDomain()
        val matcher = QueryMatcher(query)
        val results = mutableListOf<SearchResponse>()

        results.add(TrtSource.searchItem())

        coroutineScope {
            listOf(
                async { safeList { StreamedSource.search(matcher) } },
                async { safeList { StreamfreeSource.search(matcher) } },
                async { safeList { PpvSource.search(matcher) } },
                async { safeList { WatchFootySource.search(matcher) } },
                async { safeList { StreamSportsSource.search(matcher) } },
                async { safeList { RoxieSourceProvider.search(matcher) } }
            ).awaitAll()
        }.forEach { results.addAll(it) }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        StreamedSource.checkAndGetDomain()
        TrtSource.load(url)?.let { return it }
        StreamfreeSource.load(url)?.let { return it }
        PpvSource.load(url)?.let { return it }
        WatchFootySource.load(url)?.let { return it }
        StreamSportsSource.load(url)?.let { return it }
        RoxieSourceProvider.load(url)?.let { return it }
        return StreamedSource.load(url)
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

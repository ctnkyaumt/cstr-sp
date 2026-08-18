package com.cstrsp

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

object TrtSource {
    const val TRT_URL = "https://tv-trt1.medya.trt.com.tr/master.m3u8"
    const val TRT_POSTER =
        "https://upload.wikimedia.org/wikipedia/commons/thumb/8/85/TRT_1_logo_%282021-%29.svg/1280px-TRT_1_logo_%282021-%29.svg.png"

    fun searchItem(): SearchResponse =
        newLiveSearchResponse(name = "TRT Yayını", url = TRT_URL) {
            this.posterUrl = TRT_POSTER
        }

    fun load(url: String): LoadResponse? {
        if (url != TRT_URL) return null
        return newLiveStreamLoadResponse(
            name = "TRT Yayını",
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = TRT_POSTER
            this.plot = "TRT Yayını Live Stream"
        }
    }

    fun loadLinks(data: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (data != TRT_URL) return false
        callback.invoke(
            ExtractorLink(
                source = "TRT",
                name = "TRT Yayını",
                url = data,
                referer = "",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }
}

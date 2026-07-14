package com.cstrsp

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CstrspPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Cstrsp())
        registerExtractorAPI(CstrspExtractor("https://embed.st/", context))
        registerExtractorAPI(CstrspExtractor("https://embedme.top/", context))
        registerExtractorAPI(CstrspExtractor("https://strwish.com/", context))
        registerExtractorAPI(CstrspExtractor("https://cdnlivetv.tv/", context))
        registerExtractorAPI(CstrspExtractor("https://embedindia.st/", context))
        registerExtractorAPI(CstrspExtractor("https://sportsembed.su/", context))
        registerExtractorAPI(CstrspExtractor("https://roxiestreams.su/", context))
    }
}

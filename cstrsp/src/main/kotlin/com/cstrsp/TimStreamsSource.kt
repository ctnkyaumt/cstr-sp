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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class TimStream(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("vip") val vip: Boolean = false
)

data class TimEvent(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("genre") val genre: Int? = null,
    @JsonProperty("sub_genre") val subGenre: Int? = null,
    @JsonProperty("time") val time: String? = null,
    @JsonProperty("streams") val streams: List<TimStream>? = null
)

data class TimGenre(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null
)

data class TimLiveUpcomingResponse(
    @JsonProperty("events") val events: List<TimEvent>? = null,
    @JsonProperty("genres") val genres: List<TimGenre>? = null
)

data class TimChannel(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("genre") val genre: Int? = null,
    @JsonProperty("flag") val flag: String? = null,
    @JsonProperty("streams") val streams: List<TimStream>? = null
)

data class TimChannelsResponse(
    @JsonProperty("channels") val channels: List<TimChannel>? = null
)

data class TimLoadData(
    @JsonProperty("name") val name: String,
    @JsonProperty("streams") val streams: List<TimStream>
)

object TimStreamsSource {
    private const val TIM_BASE = "https://timst.top"

    private val channelGenres = mapOf(
        1 to "Entertainment",
        2 to "Sports",
        3 to "Cartoons",
        4 to "Movies and Live Shows",
        5 to "News/Politics"
    )

    private fun parseEventTime(timeStr: String?): Long? {
        if (timeStr.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("America/New_York")
            }
            sdf.parse(timeStr.trim())?.time
        } catch (e: Exception) {
            null
        }
    }

    suspend fun fetchEvents(): TimLiveUpcomingResponse? = CstrspCache.cached("timst-events") {
        app.get("$TIM_BASE/api/live-upcoming", referer = "$TIM_BASE/").parsedSafe<TimLiveUpcomingResponse>()
    }

    suspend fun fetchChannels(): List<TimChannel> = CstrspCache.cached("timst-channels") {
        app.get("$TIM_BASE/api/channels", referer = "$TIM_BASE/").parsedSafe<TimChannelsResponse>()?.channels
    } ?: emptyList()

    suspend fun getHomeSections(api: MainAPI): List<HomePageList> {
        val now = System.currentTimeMillis()
        val fourHours = 4 * 3600 * 1000L
        val thirtyMin = 30 * 60 * 1000L

        val eventsResp = fetchEvents()
        val genreNames = eventsResp?.genres.orEmpty().associate { it.id to it.name }

        val liveEvents = eventsResp?.events.orEmpty().filter { event ->
            val t = parseEventTime(event.time) ?: return@filter false
            t in (now - fourHours)..(now + thirtyMin) &&
                event.streams.orEmpty().any { !it.vip && !it.url.isNullOrBlank() }
        }

        val eventSections = liveEvents.groupBy { event ->
            genreNames[event.genre] ?: "Live Events"
        }.mapNotNull { (genreName, evs) ->
            val items = evs.mapNotNull { ev ->
                val slug = ev.url ?: return@mapNotNull null
                api.newLiveSearchResponse("${ev.name ?: "Event"} [TimStreams]", "https://timst.domains/event/$slug") {
                    this.posterUrl = ev.logo
                }
            }
            if (items.isEmpty()) null
            else HomePageList("$genreName [TimStreams]", items)
        }

        val sportsChannels = fetchChannels().filter { ch ->
            ch.genre == 2 && ch.streams.orEmpty().any { !it.vip && !it.url.isNullOrBlank() }
        }
        val channelItems = sportsChannels.mapNotNull { ch ->
            val slug = ch.url ?: return@mapNotNull null
            api.newLiveSearchResponse("${ch.name ?: "Channel"} [TimStreams]", "https://timst.domains/channel/$slug") {
                this.posterUrl = ch.logo
            }
        }
        val channelSection = if (channelItems.isNotEmpty()) {
            listOf(HomePageList("Sports Channels [TimStreams]", channelItems))
        } else {
            emptyList()
        }

        return eventSections + channelSection
    }

    suspend fun search(api: MainAPI, matcher: QueryMatcher): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val eventsResp = fetchEvents()
        val genreNames = eventsResp?.genres.orEmpty().associate { it.id to it.name }

        eventsResp?.events.orEmpty().forEach { ev ->
            val slug = ev.url ?: return@forEach
            val hasStreams = ev.streams.orEmpty().any { !it.vip && !it.url.isNullOrBlank() }
            if (hasStreams && matcher.matches(ev.name, genreNames[ev.genre])) {
                results.add(
                    api.newLiveSearchResponse("${ev.name ?: "Event"} [TimStreams]", "https://timst.domains/event/$slug") {
                        this.posterUrl = ev.logo
                    }
                )
            }
        }

        fetchChannels().forEach { ch ->
            val slug = ch.url ?: return@forEach
            val hasStreams = ch.streams.orEmpty().any { !it.vip && !it.url.isNullOrBlank() }
            if (hasStreams && matcher.matches(ch.name, channelGenres[ch.genre])) {
                results.add(
                    api.newLiveSearchResponse("${ch.name ?: "Channel"} [TimStreams]", "https://timst.domains/channel/$slug") {
                        this.posterUrl = ch.logo
                    }
                )
            }
        }

        return results
    }

    suspend fun load(api: MainAPI, url: String): LoadResponse? {
        if (!url.startsWith("https://timst.domains/")) return null
        val path = url.removePrefix("https://timst.domains/")
        val kind = path.substringBefore("/")
        val slug = path.substringAfter("/")

        return when (kind) {
            "event" -> {
                val ev = fetchEvents()?.events?.find { it.url == slug } ?: return null
                val validStreams = ev.streams.orEmpty().filter { !it.vip && !it.url.isNullOrBlank() }
                if (validStreams.isEmpty()) return null
                val title = "${ev.name ?: "Event"} [TimStreams]"
                api.newLiveStreamLoadResponse(
                    name = title,
                    url = url,
                    dataUrl = TimLoadData(ev.name ?: "Event", validStreams).toJson()
                ) {
                    this.posterUrl = ev.logo
                    this.plot = title
                }
            }
            "channel" -> {
                val ch = fetchChannels().find { it.url == slug } ?: return null
                val validStreams = ch.streams.orEmpty().filter { !it.vip && !it.url.isNullOrBlank() }
                if (validStreams.isEmpty()) return null
                val title = "${ch.name ?: "Channel"} [TimStreams]"
                api.newLiveStreamLoadResponse(
                    name = title,
                    url = url,
                    dataUrl = TimLoadData(ch.name ?: "Channel", validStreams).toJson()
                ) {
                    this.posterUrl = ch.logo
                    this.plot = title
                }
            }
            else -> null
        }
    }

    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = try {
            AppUtils.parseJson<TimLoadData>(data)
        } catch (e: Exception) {
            return false
        }

        val streams = load.streams.filter { !it.vip && !it.url.isNullOrBlank() }
        if (streams.isEmpty()) return false

        streams.resolveConcurrently { stream ->
            val streamUrl = stream.url ?: return@resolveConcurrently
            val sName = stream.name ?: "Stream"
            loadEmbed(streamUrl, "$TIM_BASE/", subtitleCallback) { link ->
                val labeled = withQualityLabel("TimStreams - $sName", link.quality)
                callback(
                    ExtractorLink(
                        source = "TimStreams",
                        name = labeled,
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

package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import com.github.parrotxray.lavabili.plugin.LavabiliPlugin
import com.github.parrotxray.lavabili.plugin.BilibiliConfig
import com.github.parrotxray.lavabili.util.CookieRefreshManager
import com.github.topi314.lavasearch.AudioSearchManager
import com.github.topi314.lavasearch.result.AudioSearchResult
import com.github.topi314.lavasearch.result.BasicAudioSearchResult
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.DataOutput
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class BilibiliAudioSourceManager(private val config: BilibiliConfig? = null) : AudioSourceManager, AudioSearchManager {
    val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)

    val httpInterface: HttpInterface
    private var playlistPageCountConfig: Int = -1

    // Minimum audio results before skipping video fallback
    private val audioResultsThreshold = 5

    init {
        val httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()
        val httpContextFilter = BilibiliHttpContextFilter(config, null)
        httpInterfaceManager.setHttpContextFilter(httpContextFilter)
        httpInterface = httpInterfaceManager.`interface`
        val updatedFilter = BilibiliHttpContextFilter(config, httpInterface)
        httpInterfaceManager.setHttpContextFilter(updatedFilter)

        when {
            config?.canRefreshCookies == true -> {
                try {
                    val cookieRefreshManager = CookieRefreshManager(config, httpInterface)
                    if (cookieRefreshManager.shouldRefreshCookies()) {
                        log.info("Detected cookies need refresh on startup, starting automatic refresh...")
                        val result = cookieRefreshManager.refreshCookies()
                        if (result.success) {
                            log.info("Cookie refreshed successfully! Please check the new configuration in the logs and restart the service to use the new cookie.")
                        } else {
                            log.warn("Cookie refresh failed: ${result.message}")
                        }
                    } else {
                        log.info("Cookie check: current cookie state is normal")
                    }
                } catch (e: Exception) {
                    log.warn("Failed to check cookie state: ${e.message}")
                }
            }
            config?.isAuthenticated == true -> {
                log.info("Using fixed cookie authentication mode (ac_time_value not configured, cannot auto-refresh)")
            }
        }
    }

    override fun getSourceName(): String {
        return "bilibili"
    }

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {
        log.debug("DEBUG: reference.identifier: ${reference.identifier}")

        // Handle bilisearch: prefix for search functionality
        if (reference.identifier.startsWith("bilisearch:")) {
            if (config?.allowSearch != true) {
                log.debug("Bilibili search is disabled in configuration")
                return BasicAudioPlaylist("Bilibili Search Disabled", emptyList(), null, true)
            }
            val searchQuery = reference.identifier.substring("bilisearch:".length).trim()
            log.debug("DEBUG: Bilibili search query: $searchQuery")
            return searchBilibili(searchQuery)
        }

        // Handle b23.tv short URLs by resolving them first
        val resolvedUrl = if (reference.identifier.contains("b23.tv")) {
            resolveShortUrl(reference.identifier)
        } else {
            reference.identifier
        }

        log.debug("DEBUG: resolved URL: $resolvedUrl")

        val matcher = URL_PATTERN.matcher(resolvedUrl)
        if (matcher.find()) {
            when (matcher.group("type")) {
                "video" -> {
                    log.debug("DEBUG: type: video")
                    val bvid = matcher.group("id")
                    val page = extractPageParameter(resolvedUrl)
                    log.debug("DEBUG: extracted page parameter: $page")

                    val type: String? = when (matcher.group("audioType")) {
                        "av" -> "av"
                        else -> null
                    }

                    var response: CloseableHttpResponse
                    if (type != null) {
                        val aid = bvid.removePrefix("av")
                        response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?aid=$aid"))
                    } else {
                        response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?bvid=$bvid"))
                    }

                    log.debug("DEBUG: attempt GET with URL: ${BASE_URL}x/web-interface/view?bvid=$bvid")
                    val responseJson = JsonBrowser.parse(response.entity.content)
                    val statusCode = responseJson.get("code").`as`(Int::class.java)
                    log.debug("DEBUG: statusCode: $statusCode")

                    if (statusCode != 0) {
                        val message = responseJson.get("message").text() ?: "Unknown error"
                        log.debug("Failed to load video: $message (code: $statusCode)")
                        return AudioReference.NO_TRACK
                    }

                    val trackData = responseJson.get("data")
                    val pagesCount = trackData.get("pages").values().size
                    val hasPageParameter = page > 0

                    return if (pagesCount > 1) {
                        if (hasPageParameter) loadVideoFromAnthology(trackData, page - 1)
                        else loadVideoAnthology(trackData, 0)
                    } else {
                        loadVideo(trackData)
                    }
                }
                "audio" -> {
                    val type = when (matcher.group("audioType")) {
                        "am" -> "menu"
                        "au" -> "song"
                        else -> return AudioReference.NO_TRACK
                    }
                    val sid = matcher.group("audioId")

                    val response = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/$type/info?sid=$sid"))
                    val responseJson = JsonBrowser.parse(response.entity.content)
                    val statusCode = responseJson.get("code").`as`(Int::class.java)

                    if (statusCode != 0) {
                        val message = responseJson.get("message").text() ?: "Unknown error"
                        log.warn("Failed to load audio: $message (code: $statusCode)")
                        return AudioReference.NO_TRACK
                    }

                    return when (type) {
                        "song" -> loadAudio(responseJson.get("data"))
                        "menu" -> loadAudioPlaylist(responseJson.get("data"))
                        else -> AudioReference.NO_TRACK
                    }
                }
            }
        }
        return null
    }

    override fun loadSearch(query: String, types: Set<AudioSearchResult.Type>): AudioSearchResult? {
        if (!query.startsWith("bilisearch:")) return null
        if (config?.allowSearch != true) {
            log.debug("Bilibili search is disabled in configuration, returning empty LavaSearch result")
            return BasicAudioSearchResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        val searchQuery = query.substring("bilisearch:".length).trim()
        log.debug("DEBUG: Bilibili LavaSearch query: $searchQuery")

        val searchPlaylist = searchBilibili(searchQuery)
        val tracks = searchPlaylist?.tracks ?: emptyList()
        val resultTracks = if (types.contains(AudioSearchResult.Type.TRACK)) tracks else emptyList()

        return BasicAudioSearchResult(resultTracks, emptyList(), emptyList(), emptyList(), emptyList())
    }

    // -------------------------------------------------------------------------
    // Smart search: runs audio + music-category video queries in parallel,
    // merges results with audio/MV tracks first, deduplicates by identifier.
    // -------------------------------------------------------------------------

    private fun searchBilibili(query: String): AudioPlaylist? {
        return try {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            log.debug("DEBUG: Starting audio-first Bilibili search for: $query")

            // Step 1: search the audio platform
            val audioTracks = searchAudioTracks(encodedQuery)
            log.debug("DEBUG: Audio search returned ${audioTracks.size} tracks")

            // Step 2: only fall back to music-category video search if audio
            // results are below the threshold
            val finalTracks: List<AudioTrack> = if (audioTracks.size >= audioResultsThreshold) {
                log.debug("DEBUG: Audio results sufficient (${audioTracks.size} >= $audioResultsThreshold), skipping video fallback")
                audioTracks
            } else {
                log.debug("DEBUG: Audio results insufficient (${audioTracks.size} < $audioResultsThreshold), running video fallback")
                val videoTracks = searchVideoTracks(encodedQuery)

                // Append video tracks that are not already in the audio results
                val seenIds = audioTracks.mapTo(mutableSetOf()) { it.info.identifier }
                val merged = ArrayList<AudioTrack>(audioTracks)
                for (track in videoTracks) {
                    if (seenIds.add(track.info.identifier)) merged.add(track)
                }
                log.debug("DEBUG: Fallback merged — audio: ${audioTracks.size}, video added: ${merged.size - audioTracks.size}, total: ${merged.size}")
                merged
            }

            BasicAudioPlaylist("Bilibili Search: $query", finalTracks, null, true)

        } catch (e: Exception) {
            log.error("Error during Bilibili search", e)
            BasicAudioPlaylist("Bilibili Search Results", emptyList(), null, true)
        }
    }

    /**
     * Searches the Bilibili Audio platform (bilibili.com/audio).
     * These are pure audio uploads — usually official releases, covers and instrumentals.
     * Endpoint: /audio/music-service-c/web/song/search
     */
    private fun searchAudioTracks(encodedQuery: String): List<AudioTrack> {
        return try {
            val url = "${BASE_URL}audio/music-service-c/web/song/search?search_type=video&keyword=$encodedQuery&page=1&pagesize=15&order=1"
            log.debug("DEBUG: Audio search URL: $url")

            val response = httpInterface.execute(HttpGet(url))
            val json = JsonBrowser.parse(response.entity.content)

            if (json.get("code").`as`(Int::class.java) != 0) {
                log.debug("Audio search returned non-zero code: ${json.get("code").text()}")
                return emptyList()
            }

            val items = json.get("data").get("data").values()
            val tracks = ArrayList<AudioTrack>()

            for (item in items) {
                try {
                    val sid = item.get("id")?.asLong(0)?.toString() ?: continue
                    val title = cleanHtmlTags(item.get("title")?.text() ?: continue)
                    val author = cleanHtmlTags(item.get("uname")?.text() ?: continue)
                    val duration = item.get("duration")?.asLong(0) ?: 0L
                    val cover = item.get("cover")?.text()

                    tracks.add(BilibiliAudioTrack(
                        AudioTrackInfo(
                            title, author,
                            duration * 1000L,
                            "au$sid",
                            false,
                            "https://www.bilibili.com/audio/au$sid",
                            cover,
                            if (cover != null) "" else null
                        ),
                        BilibiliAudioTrack.TrackType.AUDIO,
                        sid,
                        null,
                        this
                    ))
                } catch (e: Exception) {
                    log.warn("Failed to parse audio search item", e)
                }
            }

            log.debug("DEBUG: Audio search found ${tracks.size} tracks")
            tracks
        } catch (e: Exception) {
            log.warn("Audio search request failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Searches Bilibili videos filtered to the Music category (tids_1=3).
     * tids_1=3 is the top-level Music section, which includes official MVs,
     * original songs, covers and instrumental performances — no gaming/vlogs.
     * Ordered by play count (click) so well-known tracks surface first.
     */
    private fun searchVideoTracks(encodedQuery: String): List<AudioTrack> {
        return try {
            // tids_1=3 → Music category; order=click → most played first
            val baseUrl = if (config?.isAuthenticated == true) {
                "${BASE_URL}x/web-interface/wbi/search/type"
            } else {
                "${BASE_URL}x/web-interface/search/type"
            }
            val url = "$baseUrl?search_type=video&keyword=$encodedQuery&page=1&page_size=20&order=click&tids_1=3"
            log.debug("DEBUG: Music video search URL: $url")

            val response = httpInterface.execute(HttpGet(url))
            val json = JsonBrowser.parse(response.entity.content)

            val statusCode = json.get("code").`as`(Int::class.java)
            if (statusCode != 0) {
                log.warn("Music video search failed: code=$statusCode msg=${json.get("message").text()}")
                when (statusCode) {
                    -412 -> log.error("Search blocked (-412): cookies required or expired")
                    -403 -> log.error("Access forbidden (-403): rate limited or banned")
                }
                return emptyList()
            }

            val items = json.get("data").get("result").values()
            val tracks = ArrayList<AudioTrack>()

            for (item in items) {
                try {
                    val bvid = item.get("bvid")?.text() ?: continue
                    val title = cleanHtmlTags(item.get("title")?.text() ?: continue)
                    val author = cleanHtmlTags(item.get("author")?.text() ?: continue)
                    val duration = parseDuration(item.get("duration")?.text())
                    val pic = item.get("pic")?.text()

                    tracks.add(BilibiliAudioTrack(
                        AudioTrackInfo(
                            title, author, duration, bvid, false,
                            "https://www.bilibili.com/video/$bvid",
                            pic, if (pic != null) "" else null
                        ),
                        BilibiliAudioTrack.TrackType.VIDEO,
                        bvid,
                        item.get("cid")?.asLong(0) ?: 0L,
                        this
                    ))
                } catch (e: Exception) {
                    log.warn("Failed to parse video search item", e)
                }
            }

            log.debug("DEBUG: Music video search found ${tracks.size} tracks")
            tracks
        } catch (e: Exception) {
            log.warn("Music video search request failed: ${e.message}")
            emptyList()
        }
    }

    private fun extractPageParameter(url: String): Int {
        return try {
            val pageRegex = Regex("[?&]p=(\\d+)")
            pageRegex.find(url)?.groupValues?.get(1)?.toInt() ?: 0
        } catch (e: Exception) {
            log.debug("Failed to extract page parameter from URL: $url", e)
            0
        }
    }

    private fun parseDuration(duration: String?): Long {
        if (duration == null) return 0L
        return try {
            val parts = duration.split(":")
            when (parts.size) {
                2 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
                3 -> (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000
                else -> 0L
            }
        } catch (e: Exception) { 0L }
    }

    private fun cleanHtmlTags(text: String): String =
        text.replace(Regex("<[^>]*>"), "").trim()

    private fun resolveShortUrl(shortUrl: String): String {
        return try {
            val response = httpInterface.execute(HttpGet(shortUrl))
            val location = response.getFirstHeader("Location")?.value
            if (location != null && location.contains("bilibili.com")) location
            else response.getFirstHeader("Content-Location")?.value ?: shortUrl
        } catch (e: Exception) {
            log.warn("Failed to resolve short URL: $shortUrl", e)
            shortUrl
        }
    }

    fun setPlaylistPageCount(count: Int): BilibiliAudioSourceManager {
        playlistPageCountConfig = count
        return this
    }

    private fun loadVideo(trackData: JsonBrowser): AudioTrack {
        val bvid = trackData.get("bvid").`as`(String::class.java)
        log.debug("DEBUG: ${trackData.text()}")
        val artworkUrl: String? = trackData.get("pic").text() ?: trackData.get("first_frame").text()

        return BilibiliAudioTrack(
            AudioTrackInfo(
                trackData.get("title").`as`(String::class.java),
                trackData.get("owner").get("name").`as`(String::class.java),
                trackData.get("duration").asLong(0) * 1000,
                bvid, false, getVideoUrl(bvid),
                artworkUrl, if (artworkUrl != null) "" else null
            ),
            BilibiliAudioTrack.TrackType.VIDEO, bvid, trackData.get("cid").asLong(0), this
        )
    }

    private fun loadVideoFromAnthology(trackData: JsonBrowser, pageIndex: Int): AudioTrack {
        log.debug("DEBUG: Loading single track from anthology, page: $pageIndex")
        val author = trackData.get("owner").get("name").`as`(String::class.java)
        val bvid = trackData.get("bvid").`as`(String::class.java)
        val pages = trackData.get("pages").values()

        if (pageIndex < 0 || pageIndex >= pages.size) {
            log.warn("Invalid page index: $pageIndex, total pages: ${pages.size}")
            return loadVideo(trackData)
        }

        val pageData = pages[pageIndex]
        val artworkUrl: String? = trackData.get("pic").text() ?: trackData.get("first_frame").text()

        return BilibiliAudioTrack(
            AudioTrackInfo(
                pageData.get("part").`as`(String::class.java), author,
                pageData.get("duration").asLong(0) * 1000, bvid, false,
                getVideoUrl(bvid, pageData.get("page").`as`(Int::class.java)),
                artworkUrl, if (artworkUrl != null) "" else null
            ),
            BilibiliAudioTrack.TrackType.VIDEO, bvid, pageData.get("cid").asLong(0), this
        )
    }

    private fun loadVideoAnthology(trackData: JsonBrowser, selectedPage: Int): AudioPlaylist {
        log.debug("DEBUG: ${trackData.text()}")
        val playlistName = trackData.get("title").`as`(String::class.java)
        val author = trackData.get("owner").get("name").`as`(String::class.java)
        val bvid = trackData.get("bvid").`as`(String::class.java)
        val tracks = ArrayList<AudioTrack>()

        for (item in trackData.get("pages").values()) {
            val artworkUrl: String? = trackData.get("pic").text() ?: trackData.get("first_frame").text()
            tracks.add(BilibiliAudioTrack(
                AudioTrackInfo(
                    item.get("part").`as`(String::class.java), author,
                    item.get("duration").asLong(0) * 1000, bvid, false,
                    getVideoUrl(bvid, item.get("page").`as`(Int::class.java)),
                    artworkUrl, if (artworkUrl != null) "" else null
                ),
                BilibiliAudioTrack.TrackType.VIDEO, bvid, item.get("cid").asLong(0), this
            ))
        }

        val selectedTrack = if (selectedPage in 0 until tracks.size) tracks[selectedPage] else null
        return BasicAudioPlaylist(playlistName, tracks, selectedTrack, false)
    }

    private fun loadAudio(trackData: JsonBrowser): AudioTrack {
        val sid = trackData.get("statistic").get("sid").asLong(0).toString()
        log.debug("DEBUG: ${trackData.text()}")

        return BilibiliAudioTrack(
            AudioTrackInfo(
                trackData.get("title").`as`(String::class.java),
                trackData.get("uname").`as`(String::class.java),
                trackData.get("duration").asLong(0) * 1000,
                "au$sid", false, getAudioUrl("au$sid")
            ),
            BilibiliAudioTrack.TrackType.AUDIO, sid, null, this
        )
    }

    private fun loadAudioPlaylist(playlistData: JsonBrowser): AudioPlaylist {
        log.debug("DEBUG: ${playlistData.text()}")
        val playlistName = playlistData.get("title").`as`(String::class.java)
        val sid = playlistData.get("statistic").get("sid").asLong(0).toString()

        val response = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=1&ps=100"))
        val responseJson = JsonBrowser.parse(response.entity.content)
        val tracksData = responseJson.get("data").get("data").values()
        val tracks = ArrayList<AudioTrack>()

        var curPage = responseJson.get("data").get("curPage").`as`(Int::class.java)
        val pageCount = responseJson.get("data").get("pageCount").`as`(Int::class.java).let {
            if (playlistPageCountConfig == -1) it
            else if (it <= playlistPageCountConfig) it
            else playlistPageCountConfig
        }

        while (curPage <= pageCount) {
            val responsePage = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/song/of-menu?sid=$sid&pn=${++curPage}&ps=100"))
            val responseJsonPage = JsonBrowser.parse(responsePage.entity.content)
            tracksData.addAll(responseJsonPage.get("data").get("data").values())
        }

        for (track in tracksData) { tracks.add(loadAudio(track)) }
        return BasicAudioPlaylist(playlistName, tracks, null, false)
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean = true

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        track as BilibiliAudioTrack
        DataFormatTools.writeNullableText(output, track.type.toString())
        DataFormatTools.writeNullableText(output, track.id)
        DataFormatTools.writeNullableText(output, track.cid.toString())
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        val inputString = DataFormatTools.readNullableText(input)
        log.debug("DEBUG: $inputString")
        val trackType = when (inputString) {
            "VIDEO" -> BilibiliAudioTrack.TrackType.VIDEO
            "AUDIO" -> BilibiliAudioTrack.TrackType.AUDIO
            else -> throw IllegalArgumentException("ERROR: Must be VIDEO or AUDIO")
        }
        return BilibiliAudioTrack(trackInfo, trackType, DataFormatTools.readNullableText(input), DataFormatTools.readNullableText(input).toLong(), this)
    }

    override fun shutdown() {}

    companion object {
        const val BASE_URL = "https://api.bilibili.com/"

        private val URL_PATTERN = Pattern.compile(
            "^https?://(?:(?:www|m)\\.)?(?:bilibili\\.com|b23\\.tv)/(?<type>video|audio)/(?<id>(?:(?<audioType>am|au|av)?(?<audioId>[0-9]+))|[A-Za-z0-9]+)/?(?:\\?.*)?$"
        )

        private fun getVideoUrl(id: String, page: Int? = null): String =
            "https://www.bilibili.com/video/$id${if (page != null) "?p=$page" else ""}"

        private fun getAudioUrl(id: String): String = "https://www.bilibili.com/audio/$id"
    }
}

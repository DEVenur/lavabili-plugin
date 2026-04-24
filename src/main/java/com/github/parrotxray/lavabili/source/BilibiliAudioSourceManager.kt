package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.DataFormatTools
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.track.*
import com.github.parrotxray.lavabili.plugin.LavabiliPlugin
import com.github.parrotxray.lavabili.plugin.BilibiliConfig
import com.github.parrotxray.lavabili.util.CookieRefreshManager
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.DataOutput
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

const val BILIBILI_SEARCH_PREFIX = "bilisearch:"

class BilibiliAudioSourceManager(private val config: BilibiliConfig? = null) : AudioSourceManager {

    val log: Logger = LoggerFactory.getLogger(LavabiliPlugin::class.java)
    val httpInterface: HttpInterface

    private var playlistPageCountConfig: Int = -1

    init {
        val httpInterfaceManager = HttpClientTools.createDefaultThreadLocalManager()
        val httpContextFilter = BilibiliHttpContextFilter(config, null)
        httpInterfaceManager.setHttpContextFilter(httpContextFilter)
        httpInterface = httpInterfaceManager.`interface`
        httpInterfaceManager.setHttpContextFilter(BilibiliHttpContextFilter(config, httpInterface))

        if (config?.canRefreshCookies == true) {
            try {
                val manager = CookieRefreshManager(config, httpInterface)
                if (manager.shouldRefreshCookies()) {
                    log.info("Refreshing cookies...")
                    manager.refreshCookies()
                }
            } catch (e: Exception) {
                log.warn("Cookie refresh failed: ${e.message}")
            }
        }
    }

    override fun getSourceName(): String = "bilibili"

    override fun loadItem(manager: AudioPlayerManager, reference: AudioReference): AudioItem? {

        if (reference.identifier.startsWith(BILIBILI_SEARCH_PREFIX)) {
            val query = reference.identifier.removePrefix(BILIBILI_SEARCH_PREFIX)
            return searchBilibili(query)
        }

        val resolvedUrl = if (reference.identifier.contains("b23.tv")) {
            resolveShortUrl(reference.identifier)
        } else reference.identifier

        val matcher = URL_PATTERN.matcher(resolvedUrl)

        if (!matcher.find()) return null

        return when (matcher.group("type")) {
            "video" -> handleVideo(matcher, resolvedUrl)
            "audio" -> handleAudio(matcher)
            else -> null
        }
    }

    // ================= SEARCH =================

    fun searchBilibili(query: String): BasicAudioPlaylist {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "${BASE_URL}x/web-interface/search/type?search_type=video&keyword=$encoded"

        val response = httpInterface.execute(HttpGet(url))
        val json = JsonBrowser.parse(response.entity.content)

        val tracks = ArrayList<AudioTrack>()

        for (item in json.get("data").get("result").values()) {
            val bvid = item.get("bvid").text() ?: continue

            tracks.add(
                BilibiliAudioTrack(
                    AudioTrackInfo(
                        clean(item.get("title").text()),
                        clean(item.get("author").text()),
                        parseDuration(item.get("duration").text()),
                        bvid,
                        false,
                        getVideoUrl(bvid)
                    ),
                    BilibiliAudioTrack.TrackType.VIDEO,
                    bvid,
                    item.get("cid").asLong(0),
                    this
                )
            )
        }

        return BasicAudioPlaylist("Bilibili Search: $query", tracks, null, true)
    }

    // ================= LYRICS HELPER =================

    fun fetchSubtitles(bvid: String, cid: Long): List<Triple<Long, Long, String>>? {
        val url = "${BASE_URL}x/player/wbi/v2?bvid=$bvid&cid=$cid"
        val resp = httpInterface.execute(HttpGet(url))
        val json = JsonBrowser.parse(resp.entity.content)

        val subs = json.get("data").get("subtitle").get("subtitles").values()
        if (subs.isEmpty()) return null

        var subUrl = subs.first().get("subtitle_url").text() ?: return null
        if (subUrl.startsWith("//")) subUrl = "https:$subUrl"

        val subResp = httpInterface.execute(HttpGet(subUrl))
        val body = JsonBrowser.parse(subResp.entity.content).get("body").values()

        return body.map {
            val from = (it.get("from").asDouble(0.0) * 1000).toLong()
            val to = (it.get("to").asDouble(0.0) * 1000).toLong()
            val text = it.get("content").text() ?: ""
            Triple(from, to, text)
        }
    }

    // ================= VIDEO =================

    private fun handleVideo(matcher: java.util.regex.Matcher, url: String): AudioItem {
        val bvid = matcher.group("id")

        val response = httpInterface.execute(HttpGet("${BASE_URL}x/web-interface/view?bvid=$bvid"))
        val json = JsonBrowser.parse(response.entity.content)

        val data = json.get("data")

        return BilibiliAudioTrack(
            AudioTrackInfo(
                data.get("title").text(),
                data.get("owner").get("name").text(),
                data.get("duration").asLong(0) * 1000,
                bvid,
                false,
                getVideoUrl(bvid)
            ),
            BilibiliAudioTrack.TrackType.VIDEO,
            bvid,
            data.get("cid").asLong(0),
            this
        )
    }

    private fun handleAudio(matcher: java.util.regex.Matcher): AudioItem {
        val sid = matcher.group("audioId")

        val response = httpInterface.execute(HttpGet("${BASE_URL}audio/music-service-c/web/song/info?sid=$sid"))
        val json = JsonBrowser.parse(response.entity.content)

        val data = json.get("data")

        return BilibiliAudioTrack(
            AudioTrackInfo(
                data.get("title").text(),
                data.get("uname").text(),
                data.get("duration").asLong(0) * 1000,
                "au$sid",
                false,
                getAudioUrl("au$sid")
            ),
            BilibiliAudioTrack.TrackType.AUDIO,
            sid,
            null,
            this
        )
    }

    // ================= UTILS =================

    private fun clean(text: String?): String =
        text?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""

    private fun parseDuration(d: String?): Long {
        if (d == null) return 0
        val p = d.split(":")
        return when (p.size) {
            2 -> (p[0].toLong() * 60 + p[1].toLong()) * 1000
            3 -> (p[0].toLong() * 3600 + p[1].toLong() * 60 + p[2].toLong()) * 1000
            else -> 0
        }
    }

    private fun resolveShortUrl(url: String): String {
        val resp = httpInterface.execute(HttpGet(url))
        return resp.getFirstHeader("Location")?.value ?: url
    }

    override fun isTrackEncodable(track: AudioTrack): Boolean = true

    override fun encodeTrack(track: AudioTrack, output: DataOutput) {
        track as BilibiliAudioTrack
        DataFormatTools.writeNullableText(output, track.type.toString())
        DataFormatTools.writeNullableText(output, track.id)
        DataFormatTools.writeNullableText(output, track.cid?.toString())
    }

    override fun decodeTrack(trackInfo: AudioTrackInfo, input: DataInput): AudioTrack {
        val type = DataFormatTools.readNullableText(input)
        val id = DataFormatTools.readNullableText(input)
        val cid = DataFormatTools.readNullableText(input)?.toLong() ?: 0L

        return BilibiliAudioTrack(
            trackInfo,
            if (type == "VIDEO") BilibiliAudioTrack.TrackType.VIDEO else BilibiliAudioTrack.TrackType.AUDIO,
            id,
            cid,
            this
        )
    }

    override fun shutdown() {}

    companion object {
        const val BASE_URL = "https://api.bilibili.com/"

        private val URL_PATTERN = Pattern.compile(
            "^https?://.*bilibili\\.com/(?<type>video|audio)/(?<id>[^/?]+)"
        )

        private fun getVideoUrl(id: String): String =
            "https://www.bilibili.com/video/$id"

        private fun getAudioUrl(id: String): String =
            "https://www.bilibili.com/audio/$id"
    }
}

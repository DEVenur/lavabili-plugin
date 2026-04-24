package com.github.parrotxray.lavabili.source

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface
import com.sedmelluq.discord.lavaplayer.track.*
import com.github.parrotxray.lavabili.plugin.BilibiliConfig
import com.github.topi314.lavasearch.api.AudioSearchManager
import com.github.topi314.lavasearch.api.AudioSearchResult
import com.github.topi314.lavalyrics.api.AudioLyricsManager
import com.github.topi314.lavalyrics.api.AudioLyrics
import org.apache.http.client.methods.HttpGet
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

const val BILIBILI_SEARCH_PREFIX = "bilisearch:"

class BilibiliAudioSourceManager(
    private val config: BilibiliConfig? = null
) : AudioSourceManager, AudioSearchManager, AudioLyricsManager {

    private val log = LoggerFactory.getLogger(javaClass)
    private val httpInterface: HttpInterface =
        HttpClientTools.createDefaultThreadLocalManager().`interface`

    override fun getSourceName(): String = "bilibili"

    // =======================
    // 🔎 LAVASEARCH
    // =======================

    override fun isSearchProviderCompatible(query: String): Boolean {
        return query.startsWith(BILIBILI_SEARCH_PREFIX)
    }

    override fun loadSearchResult(
        query: String,
        types: Set<AudioSearchResult.Type>
    ): AudioSearchResult {

        if (!isSearchProviderCompatible(query)) {
            return emptySearch()
        }

        val search = query.removePrefix(BILIBILI_SEARCH_PREFIX).trim()
        val playlist = searchBilibili(search)

        val tracks = playlist?.tracks ?: emptyList()

        return object : AudioSearchResult {
            override fun getTracks(): List<AudioTrack> = tracks
            override fun getPlaylists(): List<AudioPlaylist> =
                if (tracks.isNotEmpty()) listOf(playlist!!) else emptyList()

            override fun getAlbums(): List<AudioPlaylist> = emptyList()
            override fun getArtists(): List<AudioPlaylist> = emptyList()
            override fun getTexts(): List<AudioSearchResult.Text> = emptyList()
        }
    }

    private fun emptySearch() = object : AudioSearchResult {
        override fun getTracks() = emptyList<AudioTrack>()
        override fun getPlaylists() = emptyList<AudioPlaylist>()
        override fun getAlbums() = emptyList<AudioPlaylist>()
        override fun getArtists() = emptyList<AudioPlaylist>()
        override fun getTexts() = emptyList<AudioSearchResult.Text>()
    }

    // =======================
    // 🎤 LAVALYRICS
    // =======================

    override fun isLyricsProviderCompatible(track: AudioTrack): Boolean {
        return track.sourceManager?.sourceName == "bilibili"
    }

    override fun loadLyrics(track: AudioTrack): AudioLyrics? {
        val bili = track as? BilibiliAudioTrack ?: return null

        val bvid = bili.id ?: return null
        val cid = bili.cid ?: return null

        return try {
            val url = "${BASE_URL}x/player/v2?bvid=$bvid&cid=$cid"
            val json = JsonBrowser.parse(httpInterface.execute(HttpGet(url)).entity.content)

            val subs = json["data"]["subtitle"]["subtitles"].values()
            if (subs.isEmpty()) return null

            val subUrl = subs.first()["subtitle_url"].text() ?: return null
            val finalUrl = if (subUrl.startsWith("//")) "https:$subUrl" else subUrl

            val body = JsonBrowser.parse(
                httpInterface.execute(HttpGet(finalUrl)).entity.content
            )["body"].values()

            val lines = body.map {
                val start = (it["from"].asDouble(0.0) * 1000).toLong()
                val end = (it["to"].asDouble(0.0) * 1000).toLong()
                val text = it["content"].text() ?: ""

                AudioLyrics.Line(start, end - start, text)
            }

            AudioLyrics(
                sourceName = "bilibili",
                provider = "bilibili",
                text = null,
                lines = lines
            )
        } catch (e: Exception) {
            log.warn("Lyrics error", e)
            null
        }
    }

    // =======================
    // 🎵 LOAD ITEM
    // =======================

    override fun loadItem(
        manager: AudioPlayerManager,
        reference: AudioReference
    ): AudioItem? {

        if (reference.identifier.startsWith(BILIBILI_SEARCH_PREFIX)) {
            val query = reference.identifier.removePrefix(BILIBILI_SEARCH_PREFIX)
            return searchBilibili(query)
        }

        return null
    }

    // =======================
    // 🔍 SEARCH CORE
    // =======================

    private fun searchBilibili(query: String): BasicAudioPlaylist? {
        return try {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val url = "${BASE_URL}x/web-interface/search/type?search_type=video&keyword=$encoded"

            val json = JsonBrowser.parse(httpInterface.execute(HttpGet(url)).entity.content)

            val results = json["data"]["result"].values()
            val tracks = mutableListOf<AudioTrack>()

            for (item in results) {
                val bvid = item["bvid"].text() ?: continue
                val title = item["title"].text() ?: "unknown"
                val author = item["author"].text() ?: "unknown"

                tracks.add(
                    BilibiliAudioTrack.simple(
                        title,
                        author,
                        bvid,
                        this
                    )
                )
            }

            BasicAudioPlaylist("Bilibili: $query", tracks, null, true)
        } catch (e: Exception) {
            log.error("Search error", e)
            null
        }
    }

    override fun shutdown() {}

    companion object {
        const val BASE_URL = "https://api.bilibili.com/"
    }
}

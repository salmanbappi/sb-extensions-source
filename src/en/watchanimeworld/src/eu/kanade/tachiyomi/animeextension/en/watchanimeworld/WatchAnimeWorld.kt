package eu.kanade.tachiyomi.animeextension.en.watchanimeworld

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Serializable
data class ServerItem(
    val language: String? = null,
    val link: String? = null,
)

@Serializable
data class ZephyrResponse(
    val videoSource: String? = null,
    val hls: Boolean? = null,
)

class WatchAnimeWorld : Source() {

    override val name = "WatchAnimeWorld"

    override val baseUrl = "https://watchanimeworld.net"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".latest-ep-swiper-slide article.post, article.post.movies, article.post, .swiper-slide article, div.items article.item").map { element ->
            parseAnimeFromElement(element)
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(".pagination a.next, .pagination .nav-links a.next, a.next") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?post_type=episodes&paged=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        GET("$baseUrl/page/$page/?s=$query", headers)
    } else {
        popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".entry-title, h1.entry-title")?.text()?.trim() ?: ""
            description = document.selectFirst(".description p, .wp-content p, #info p")?.text()?.trim() ?: ""
            genre = document.select(".genres a, .sgeneros a").joinToString { it.text().trim() }
            thumbnail_url = document.selectFirst("article.post img, .poster img")?.attr("abs:src")
                ?: document.selectFirst("article.post img, .poster img")?.attr("abs:data-src") ?: ""
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val urlPath = response.request.url.encodedPath

        if (urlPath.contains("/movies/")) {
            return listOf(
                SEpisode.create().apply {
                    url = urlPath
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        episodes.addAll(parseEpisodesFromHtml(document))

        // Check for other seasons
        val seasonLinks = document.select(".choose-season .sel-temp a")
        if (seasonLinks.size > 1) {
            val postId = seasonLinks.firstOrNull()?.attr("data-post")
            if (!postId.isNullOrEmpty()) {
                val currentSeason = document.selectFirst(".n_s")?.text()?.trim()?.toIntOrNull() ?: 1

                val otherSeasonEpisodes = seasonLinks.filter { link ->
                    val seasonNum = link.attr("data-season").toIntOrNull() ?: 0
                    seasonNum != currentSeason
                }.parallelCatchingFlatMapBlocking { link ->
                    val seasonNum = link.attr("data-season")
                    val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$seasonNum&post=$postId"
                    try {
                        val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
                        val ajaxHtml = ajaxResponse.bodyString()
                        val ajaxDoc = org.jsoup.Jsoup.parseBodyFragment(ajaxHtml)
                        parseEpisodesFromHtml(ajaxDoc)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                episodes.addAll(otherSeasonEpisodes)
            }
        }

        return episodes.sortedBy { it.episode_number }
    }

    private fun parseEpisodesFromHtml(document: Document): List<SEpisode> {
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)
        return document.select("#episode_by_temp li, li").mapNotNull { li ->
            val article = li.selectFirst("article.episodes") ?: return@mapNotNull null
            val link = article.selectFirst("a.lnk-blk") ?: return@mapNotNull null
            val numEpi = article.selectFirst("span.num-epi")?.text()?.trim() ?: ""
            val titleText = article.selectFirst(".entry-title")?.text()?.trim() ?: ""

            val match = EPISODE_NUM_REGEX.find(numEpi)
            val episodeNum = match?.groupValues?.get(2)?.toFloatOrNull() ?: 0f

            SEpisode.create().apply {
                url = link.attr("abs:href").substringAfter(baseUrl)
                name = if (titleText.isNotBlank()) titleText else "Episode $episodeNum"
                episode_number = episodeNum

                val img = article.selectFirst("img")
                val imgUrl = img?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("abs:src") ?: ""
                preview_url = if (showThumbnails && imgUrl.isNotBlank()) imgUrl else null
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(baseUrl + episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        val html = response.bodyString()
        val doc = org.jsoup.Jsoup.parse(html, response.request.url.toString())
        val videos = mutableListOf<Video>()
        val episodeUrl = response.request.url.toString()

        // 1. Extract /api/player1.php?data= encoded server list
        val player1Match = PLAYER1_REGEX.find(html)
        if (player1Match != null) {
            try {
                val encodedData = player1Match.groupValues[1]
                val decodedBytes = android.util.Base64.decode(encodedData, android.util.Base64.DEFAULT)
                val decodedStr = String(decodedBytes, Charsets.UTF_8)
                val servers = json.decodeFromString<List<ServerItem>>(decodedStr)

                val serverVideos = servers.parallelCatchingFlatMapBlocking { server ->
                    val link = server.link ?: return@parallelCatchingFlatMapBlocking emptyList()
                    val lang = server.language ?: "Unknown"
                    try {
                        if (
                            "abysscdn.com" in link || "hydraxcdn.biz" in link || "short.icu" in link ||
                            "embedplayabyss.top" in link || "abyssplayer.com" in link || "playabyss.top" in link ||
                            "short.ink" in link || "abyss" in lang.lowercase() || "hydrax" in lang.lowercase()
                        ) {
                            return@parallelCatchingFlatMapBlocking AbyssExtractor(client, playlistUtils)
                                .videosFromUrl(link, referer = episodeUrl)
                        }

                        val serverResponse = client.newCall(
                            GET(link, headers.newBuilder().set("Referer", episodeUrl).build()),
                        ).execute()
                        val serverHtml = serverResponse.bodyString()

                        val m3u8Matches = M3U8_REGEX.findAll(serverHtml).map { it.value }.toList()
                        val distinctM3u8s = m3u8Matches.distinct()

                        distinctM3u8s.flatMap { m3u8Url ->
                            val lang = server.language ?: "Unknown"
                            if (m3u8Url.contains("master.m3u8") || m3u8Url.contains("playlist.m3u8")) {
                                try {
                                    playlistUtils.extractFromHls(
                                        playlistUrl = m3u8Url,
                                        referer = link,
                                        masterHeaders = headers.newBuilder().set("Referer", link).build(),
                                        videoHeaders = headers.newBuilder().set("Referer", link).build(),
                                        videoNameGen = { quality -> "$lang - $quality" },
                                    )
                                } catch (e: Exception) {
                                    listOf(
                                        Video(
                                            videoUrl = m3u8Url,
                                            videoTitle = "$lang - Auto",
                                            headers = headers.newBuilder().set("Referer", link).build(),
                                        ),
                                    )
                                }
                            } else {
                                listOf(
                                    Video(
                                        videoUrl = m3u8Url,
                                        videoTitle = "$lang - Auto",
                                        headers = headers.newBuilder().set("Referer", link).build(),
                                    ),
                                )
                            }
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                videos.addAll(serverVideos)
            } catch (e: Exception) {
                // Ignore decoding/parsing errors
            }
        }

        // 2. Extract Zephyrflick player sources
        val zephyrIframes = doc.select("iframe").mapNotNull {
            it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src").takeIf { s -> s.isNotBlank() }
        }.filter { "zephyrflick" in it }

        zephyrIframes.forEach { iframeUrl ->
            val zephyrMatch = ZEPHYR_HASH_REGEX.find(iframeUrl)
            if (zephyrMatch != null) {
                try {
                    val videoId = zephyrMatch.groupValues[1]
                    val formBody = okhttp3.FormBody.Builder()
                        .add("data", videoId)
                        .add("do", "getVideo")
                        .build()

                    val zephyrHeaders = headers.newBuilder()
                        .set("Referer", "https://play.zephyrflick.top/")
                        .set("Origin", "https://play.zephyrflick.top")
                        .set("X-Requested-With", "XMLHttpRequest")
                        .build()

                    val zephyrResponse = client.newCall(
                        okhttp3.Request.Builder()
                            .url("https://play.zephyrflick.top/player/index.php")
                            .post(formBody)
                            .headers(zephyrHeaders)
                            .build(),
                    ).execute()

                    val zephyrData = json.decodeFromString<ZephyrResponse>(zephyrResponse.bodyString())
                    val streamUrl = zephyrData.videoSource

                    if (!streamUrl.isNullOrEmpty()) {
                        // Extract subtitles if present
                        val subtitles = mutableListOf<Track>()
                        try {
                            val playerPageResponse = client.newCall(
                                GET(iframeUrl, zephyrHeaders),
                            ).execute()
                            val playerHtml = playerPageResponse.bodyString()
                            val subtitleMatch = SUBTITLE_REGEX.find(playerHtml)
                            if (subtitleMatch != null) {
                                val subtitleData = subtitleMatch.groupValues[1]
                                subtitleData.split("\n").map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                                    val partsMatch = SUBTITLE_LINE_REGEX.find(line)
                                    if (partsMatch != null) {
                                        val langName = partsMatch.groupValues[1]
                                        val subUrl = partsMatch.groupValues[2].trim()
                                        subtitles.add(Track(subUrl, langName))
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }

                        val streamHeaders = headers.newBuilder()
                            .set("Referer", "https://play.zephyrflick.top/")
                            .set("Origin", "https://play.zephyrflick.top")
                            .build()

                        if (streamUrl.contains(".m3u8")) {
                            try {
                                val hlsVideos = playlistUtils.extractFromHls(
                                    playlistUrl = streamUrl,
                                    referer = "https://play.zephyrflick.top/",
                                    masterHeaders = streamHeaders,
                                    videoHeaders = streamHeaders,
                                    videoNameGen = { quality -> "Zephyrflick - $quality" },
                                    subtitleList = subtitles,
                                )
                                videos.addAll(hlsVideos)
                            } catch (e: Exception) {
                                videos.add(
                                    Video(
                                        videoUrl = streamUrl,
                                        videoTitle = "Zephyrflick - Auto",
                                        headers = streamHeaders,
                                        subtitleTracks = subtitles,
                                    ),
                                )
                            }
                        } else {
                            videos.add(
                                Video(
                                    videoUrl = streamUrl,
                                    videoTitle = "Zephyrflick - Auto",
                                    headers = streamHeaders,
                                    subtitleTracks = subtitles,
                                ),
                            )
                        }
                    }
                } catch (e: Exception) {
                    // Ignore zephyrflick resolution errors
                }
            }
        }

        return videos
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality) }
                .thenByDescending { it.videoTitle.contains("1080p") }
                .thenByDescending { it.videoTitle.contains("720p") }
                .thenByDescending { it.videoTitle.contains("480p") }
                .thenByDescending { it.videoTitle.contains("Auto") },
        )
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = PREF_SHOW_THUMBNAILS_TITLE,
            summary = PREF_SHOW_THUMBNAILS_SUMMARY,
            default = true,
        )
    }

    // ============================ Utilities ===============================

    private fun parseAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleElement = element.selectFirst(".entry-title, h2, h3")
        title = titleElement?.text()?.trim() ?: ""

        val linkElement = element.selectFirst("a.lnk-blk, a")
        val linkUrl = linkElement?.attr("abs:href") ?: ""
        val relativeUrl = linkUrl.substringAfter(baseUrl)

        if (relativeUrl.contains("/episode/")) {
            val slug = relativeUrl.trim('/').substringAfterLast('/')
            val match = EPISODE_SLUG_REGEX.find(slug)
            if (match != null) {
                val animeSlug = match.groupValues[1]
                url = "/series/$animeSlug/"
            } else {
                url = relativeUrl
            }
        } else {
            url = relativeUrl
        }

        val imgElement = element.selectFirst("img")
        thumbnail_url = imgElement?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("abs:src") ?: ""
    }

    companion object {
        private val EPISODE_NUM_REGEX by lazy { Regex("""(\d+)x(\d+)""") }
        private val PLAYER1_REGEX by lazy { Regex("""/api/player1\.php\?data=([A-Za-z0-9+/=]+)""") }
        private val M3U8_REGEX by lazy { Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE) }
        private val ZEPHYR_HASH_REGEX by lazy { Regex("""/video/([a-f0-9]+)""") }
        private val SUBTITLE_REGEX by lazy { Regex("""var playerjsSubtitle = "([^"]+)"""") }
        private val SUBTITLE_LINE_REGEX by lazy { Regex("""\[([^\]]+)\](.+)""") }
        private val EPISODE_SLUG_REGEX by lazy { Regex("""^(.+?)-(\d+)(?:x|-)(\d+)$""", RegexOption.IGNORE_CASE) }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"
        private const val PREF_SHOW_THUMBNAILS_TITLE = "Show episode thumbnails"
        private const val PREF_SHOW_THUMBNAILS_SUMMARY = "Fetch and display images in the episode list."
    }
}

package eu.kanade.tachiyomi.animeextension.en.vegamovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class Vegamovies : Source() {

    override val name = "Vegamovies"

    override val baseUrl = "https://vegamoviess.you"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    private fun parseAnimeListPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article.post-item").mapNotNull { element ->
            val linkEl = element.selectFirst("h3.entry-title a, a.blog-img") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val titleText = linkEl.attr("title").ifEmpty { linkEl.text() }
            val imgEl = element.selectFirst("img.blog-picture, img")
            val imgUrl = imgEl?.attr("abs:src")?.ifEmpty { imgEl?.attr("src") }

            SAnime.create().apply {
                title = titleText.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = imgUrl
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.select(".wp-pagenavi a[href*=/page/${page + 1}/]").isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/page/$page/?s=$encodedQuery"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val titleText = doc.selectFirst("h3.entry-title, h1.entry-title, h3")?.text() ?: anime.title
        val thumbnail = doc.selectFirst("div.entry-content img[src*=/uploads/], img.blog-picture")?.attr("abs:src")
            ?: anime.thumbnail_url

        val bodyText = doc.select("div.entry-content").text()

        val genreText = Regex("""Genres:\s*([^\n<]+)""", RegexOption.IGNORE_CASE)
            .find(bodyText)?.groupValues?.get(1)?.trim()

        val scoreText = Regex("""IMDb Rating:\s*([^\n<]+)""", RegexOption.IGNORE_CASE)
            .find(bodyText)?.groupValues?.get(1)?.trim()

        val synopsisText = Regex("""Movie-SYNOPSIS/PLOT:\s*([^\n<]+)""", RegexOption.IGNORE_CASE)
            .find(bodyText)?.groupValues?.get(1)?.trim() ?: ""

        return anime.apply {
            title = titleText
            thumbnail_url = thumbnail
            genre = genreText
            status = SAnime.COMPLETED
            initialized = true
            description = buildString {
                if (!scoreText.isNullOrBlank()) {
                    append("★ IMDb: $scoreText\n\n")
                }
                if (synopsisText.isNotBlank()) {
                    append(synopsisText)
                } else if (bodyText.isNotBlank()) {
                    append(bodyText.take(500))
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val content = doc.selectFirst("div.entry-content, main") ?: doc
        val dwdLinks = content.select("a[href*=nexdrive], a[href*=vcloud], a[href*=fast-dl], a[href*=vgmlinks], a.btn")

        dwdLinks.forEachIndexed { index, a ->
            val href = a.attr("abs:href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@forEachIndexed

            val text = a.text().trim()
            val parentText = a.parent()?.text() ?: ""
            val fullText = "$text $parentText"

            val qualityMatch = Regex("""(480p|720p|1080p|2160p|4k|ep\s*\d+|episode\s*\d+)""", RegexOption.IGNORE_CASE)
                .findAll(fullText).map { it.value }.toList().lastOrNull() ?: ""

            val sizeMatch = Regex("""\[([\d\.]+(?:MB|GB))\]""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1) ?: ""

            val epName = buildString {
                if (qualityMatch.isNotBlank()) append(qualityMatch.uppercase())
                if (sizeMatch.isNotBlank()) {
                    if (isNotEmpty()) append(" ")
                    append("[$sizeMatch]")
                }
            }.ifEmpty { text.ifEmpty { "Download Link ${index + 1}" } }

            episodes.add(
                SEpisode.create().apply {
                    name = epName
                    setUrlWithoutDomain(href)
                    episode_number = (episodes.size + 1).toFloat()
                    scanlator = if (fullText.contains("Dual", ignoreCase = true)) "Dual Audio" else "Sub / Dub"
                },
            )
        }

        if (episodes.isEmpty()) {
            episodes.add(
                SEpisode.create().apply {
                    name = "Full Movie / Watch Stream"
                    setUrlWithoutDomain(anime.url)
                    episode_number = 1f
                },
            )
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val targetUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"

        if (targetUrl.contains("nexdrive", ignoreCase = true)) {
            val req = GET(targetUrl, headersBuilder().set("Referer", "$baseUrl/").build())
            val response = client.newCall(req).execute()
            val doc = response.asJsoup()
            val hosters = mutableListOf<Hoster>()

            doc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.contains("fast-dl", ignoreCase = true)) {
                    hosters.add(Hoster("G-Direct (Fast 10Gbps)", href))
                } else if (href.contains("vcloud", ignoreCase = true)) {
                    hosters.add(Hoster("V-Cloud (Resumable)", href))
                } else if (href.contains("vgmlinks", ignoreCase = true)) {
                    hosters.add(Hoster("VGMLINKS", href))
                } else if (href.contains("filepress", ignoreCase = true)) {
                    hosters.add(Hoster("Filepress", href))
                } else if (href.contains("gdtot", ignoreCase = true)) {
                    hosters.add(Hoster("GDToT", href))
                } else if (href.contains("dropgalaxy", ignoreCase = true)) {
                    hosters.add(Hoster("DropGalaxy", href))
                }
            }

            if (hosters.isNotEmpty()) {
                return hosters
            }
        }

        return listOf(Hoster("Default Server", targetUrl))
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val videoList = mutableListOf<Video>()

        try {
            if (url.contains("fast-dl", ignoreCase = true)) {
                val postReq = Request.Builder()
                    .url(url)
                    .post(FormBody.Builder().build())
                    .headers(headersBuilder().set("Referer", url).build())
                    .build()
                val resp = client.newCall(postReq).execute()
                val doc = resp.asJsoup()

                doc.select("a[href*=googleusercontent], a:contains(Download)").forEach { a ->
                    val videoUrl = a.attr("abs:href")
                    if (videoUrl.isNotBlank() && videoUrl.startsWith("http")) {
                        videoList.add(
                            Video(
                                videoUrl = videoUrl,
                                videoTitle = "${hoster.hosterName} - Direct Stream",
                                headers = headersBuilder().set("Referer", url).build(),
                            ),
                        )
                    }
                }
            } else if (url.contains("vcloud", ignoreCase = true)) {
                val postReq = Request.Builder()
                    .url(url)
                    .post(FormBody.Builder().build())
                    .headers(headersBuilder().set("Referer", url).build())
                    .build()
                val resp = client.newCall(postReq).execute()
                val doc = resp.asJsoup()

                doc.select("a[href]").forEach { a ->
                    val videoUrl = a.attr("abs:href")
                    if (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8") || videoUrl.contains("googleusercontent")) {
                        videoList.add(
                            Video(
                                videoUrl = videoUrl,
                                videoTitle = "${hoster.hosterName} - Direct Stream",
                                headers = headersBuilder().set("Referer", url).build(),
                            ),
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        if (videoList.isEmpty()) {
            runCatching {
                videoList.addAll(universalExtractor.videosFromUrl(url, headersBuilder().set("Referer", url).build()))
            }
        }

        return videoList
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, "1080p") ?: "1080p"
        val prefServer = preferences.getString(PREF_SERVER_KEY, "G-Direct") ?: "G-Direct"

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) },
        )
    }

    // ============================ Recommendations ========================
    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("article.post-item, div.recent-posts li").mapNotNull { element ->
            val linkEl = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href.contains("#")) return@mapNotNull null

            SAnime.create().apply {
                title = linkEl.attr("title").ifEmpty { linkEl.text() }.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = "1080p",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = "G-Direct",
            summary = "%s",
            entries = listOf("G-Direct (Fast 10Gbps)", "V-Cloud (Resumable)", "VGMLINKS", "Universal"),
            entryValues = listOf("G-Direct", "V-Cloud", "VGMLINKS", "Universal"),
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SERVER_KEY = "pref_server"
    }
}

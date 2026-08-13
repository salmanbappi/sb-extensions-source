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
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

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
        val animeList = doc.select("article.post-item, div.post-item").mapNotNull { element ->
            val linkEl = element.selectFirst("h3.entry-title a, a.blog-img, a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@mapNotNull null

            val titleText = linkEl.attr("title").ifEmpty { linkEl.text() }
            val imgEl = element.selectFirst("img.blog-picture, img")
            val imgUrl = imgEl?.attr("abs:src")?.ifEmpty { imgEl.attr("src") }

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

        val titleText = doc.selectFirst("h1.entry-title, h3.entry-title")?.text()?.trim() ?: anime.title
        val thumbnail = doc.selectFirst("div.entry-content img[src*=/covers/], div.entry-content img[src*=/uploads/], img.blog-picture")?.attr("abs:src")
            ?: anime.thumbnail_url

        val content = doc.selectFirst("div.entry-content")
        val paragraphs = content?.select("p, div")?.map { it.text().trim() }?.filter { it.isNotBlank() } ?: emptyList()

        var langText: String? = null
        var genreText: String? = null
        var scoreText: String? = null
        var qualityText: String? = null
        var plotText: String? = null

        paragraphs.forEach { p ->
            if (p.contains("Language:", ignoreCase = true) || p.contains("Audio:", ignoreCase = true)) {
                langText = p.substringAfter(":").trim()
            }
            if (p.contains("Genres:", ignoreCase = true) || p.contains("Genre:", ignoreCase = true)) {
                genreText = p.substringAfter(":").trim()
            }
            if (p.contains("IMDb Rating:", ignoreCase = true) || p.contains("IMDB:", ignoreCase = true)) {
                scoreText = p.substringAfter(":").trim()
            }
            if (p.contains("Quality:", ignoreCase = true)) {
                qualityText = p.substringAfter(":").trim()
            }
            if (p.length > 50 && !p.contains("Vegamovies", ignoreCase = true) && !p.contains("Download", ignoreCase = true) && !p.contains("Language", ignoreCase = true) && !p.contains("Quality", ignoreCase = true)) {
                if (plotText == null) {
                    plotText = p
                }
            }
        }

        return anime.apply {
            title = titleText
            thumbnail_url = thumbnail
            genre = genreText ?: doc.select("div.entry-content a[href*=/category/], div.entry-content a[href*=/genre/]").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED
            initialized = true
            description = buildString {
                if (!scoreText.isNullOrBlank()) {
                    append("### ⭐ IMDb Rating: **$scoreText**\n\n")
                }
                if (!langText.isNullOrBlank()) {
                    append("🔊 **Audio / Language**: `$langText`  \n")
                }
                if (!qualityText.isNullOrBlank()) {
                    append("🎥 **Quality**: `$qualityText`  \n")
                }
                if (!genreText.isNullOrBlank()) {
                    append("🏷️ **Genres**: *$genreText*  \n")
                }
                if (isNotEmpty()) append("\n")
                if (!plotText.isNullOrBlank()) {
                    append("### 📖 Plot Synopsis\n")
                    append("> $plotText\n")
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val content = doc.selectFirst("div.entry-content") ?: doc
        val dwdLinks = content.select("a[href*=nexdrive], a[href*=vcloud], a[href*=fast-dl], a[href*=vgmlinks], a[href*=filepress], a[href*=gdtot], a.btn")

        dwdLinks.forEachIndexed { index, a ->
            val href = a.attr("abs:href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#") || href.contains("telegram")) return@forEachIndexed

            val text = a.text().trim()
            val parentText = a.parent()?.text() ?: ""
            val fullText = "$text $parentText"

            val qualityMatch = Regex("""(480p|720p|1080p|2160p|4k|HEVC)""", RegexOption.IGNORE_CASE)
                .findAll(fullText).map { it.value }.toList().lastOrNull() ?: ""

            val sizeMatch = Regex("""\[([\d\.]+\s*(?:MB|GB))\]""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1) ?: ""

            val epName = buildString {
                if (qualityMatch.isNotBlank()) append(qualityMatch.uppercase())
                if (sizeMatch.isNotBlank()) {
                    if (isNotEmpty()) append(" ")
                    append("[$sizeMatch]")
                }
            }.ifEmpty { text.ifEmpty { "Download Server ${index + 1}" } }

            val audioTag = when {
                fullText.contains("Dual Audio", ignoreCase = true) -> "Dual Audio (Hindi + English)"
                fullText.contains("Multi Audio", ignoreCase = true) -> "Multi Audio"
                fullText.contains("Hindi", ignoreCase = true) -> "Hindi Audio"
                else -> "Subbed / Original Audio"
            }

            episodes.add(
                SEpisode.create().apply {
                    name = epName
                    setUrlWithoutDomain(href)
                    episode_number = (episodes.size + 1).toFloat()
                    scanlator = audioTag
                },
            )
        }

        if (episodes.isEmpty()) {
            episodes.add(
                SEpisode.create().apply {
                    name = "Full Movie / Stream"
                    setUrlWithoutDomain(anime.url)
                    episode_number = 1f
                    scanlator = "Multi Server"
                },
            )
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val targetUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"

        if (targetUrl.contains("nexdrive", ignoreCase = true) || targetUrl.contains("vgmlinks", ignoreCase = true)) {
            val req = GET(targetUrl, headersBuilder().set("Referer", "$baseUrl/").build())
            val response = runCatching { client.newCall(req).execute() }.getOrNull()
            if (response != null) {
                val doc = response.asJsoup()
                val hosters = mutableListOf<Hoster>()

                doc.select("a[href]").forEach { a ->
                    val href = a.attr("abs:href")
                    if (href.contains("fast-dl", ignoreCase = true)) {
                        hosters.add(Hoster("Fast 10Gbps Server", href))
                    } else if (href.contains("vcloud", ignoreCase = true)) {
                        hosters.add(Hoster("V-Cloud Direct Server", href))
                    } else if (href.contains("filepress", ignoreCase = true)) {
                        hosters.add(Hoster("Filepress Cloud", href))
                    } else if (href.contains("gdtot", ignoreCase = true)) {
                        hosters.add(Hoster("GDToT Server", href))
                    } else if (href.contains("dropgalaxy", ignoreCase = true)) {
                        hosters.add(Hoster("DropGalaxy Cloud", href))
                    } else if (href.contains("dood", ignoreCase = true)) {
                        hosters.add(Hoster("DoodStream Server", href))
                    } else if (href.contains("filemoon", ignoreCase = true)) {
                        hosters.add(Hoster("Filemoon Server", href))
                    } else if (href.contains("streamtape", ignoreCase = true)) {
                        hosters.add(Hoster("StreamTape Server", href))
                    } else if (href.contains("streamwish", ignoreCase = true)) {
                        hosters.add(Hoster("StreamWish Server", href))
                    }
                }

                if (hosters.isNotEmpty()) {
                    return hosters
                }
            }
        }

        return listOf(
            Hoster("Fast 10Gbps Direct", targetUrl),
            Hoster("V-Cloud Stream", targetUrl),
            Hoster("Universal Stream Engine", targetUrl),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val videoList = mutableListOf<Video>()

        try {
            when {
                url.contains("dood", ignoreCase = true) -> videoList.addAll(doodExtractor.videosFromUrl(url))
                url.contains("filemoon", ignoreCase = true) -> videoList.addAll(filemoonExtractor.videosFromUrl(url))
                url.contains("streamtape", ignoreCase = true) -> videoList.addAll(streamtapeExtractor.videosFromUrl(url))
                url.contains("streamwish", ignoreCase = true) -> videoList.addAll(streamwishExtractor.videosFromUrl(url))
            }

            if (videoList.isEmpty()) {
                val req = GET(url, headersBuilder().set("Referer", url).build())
                val resp = runCatching { client.newCall(req).execute() }.getOrNull()
                if (resp != null) {
                    val html = resp.body.string()
                    val doc = org.jsoup.Jsoup.parse(html, url)

                    doc.select("a[href], button[data-url]").forEach { el ->
                        val link = el.attr("abs:href").ifEmpty { el.attr("data-url") }
                        if (link.contains(".m3u8") || link.contains(".mp4") || link.contains("googleusercontent") || link.contains(".mkv")) {
                            videoList.add(
                                Video(
                                    videoUrl = link,
                                    videoTitle = "${hoster.hosterName} - Direct Stream",
                                    headers = headersBuilder().set("Referer", url).build(),
                                    subtitleTracks = emptyList(),
                                ),
                            )
                        }
                    }

                    if (videoList.isEmpty()) {
                        val postReq = Request.Builder()
                            .url(url)
                            .post(FormBody.Builder().build())
                            .headers(headersBuilder().set("Referer", url).build())
                            .build()
                        val postResp = runCatching { client.newCall(postReq).execute() }.getOrNull()
                        if (postResp != null) {
                            val postDoc = postResp.asJsoup()
                            postDoc.select("a[href]").forEach { a ->
                                val directLink = a.attr("abs:href")
                                if (directLink.startsWith("http") && !directLink.contains("telegram")) {
                                    videoList.add(
                                        Video(
                                            videoUrl = directLink,
                                            videoTitle = "${hoster.hosterName} - Stream Link",
                                            headers = headersBuilder().set("Referer", url).build(),
                                            subtitleTracks = emptyList(),
                                        ),
                                    )
                                }
                            }
                        }
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
        val prefServer = preferences.getString(PREF_SERVER_KEY, "Fast 10Gbps") ?: "Fast 10Gbps"

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
            default = "Fast 10Gbps",
            summary = "%s",
            entries = listOf("Fast 10Gbps Direct", "V-Cloud Direct Server", "Universal Stream Engine"),
            entryValues = listOf("Fast 10Gbps", "V-Cloud", "Universal"),
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SERVER_KEY = "pref_server"
    }
}

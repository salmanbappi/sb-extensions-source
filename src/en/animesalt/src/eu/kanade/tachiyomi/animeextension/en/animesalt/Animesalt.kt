package eu.kanade.tachiyomi.animeextension.en.animesalt

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.UrlUtils
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class Animesalt : Source() {

    override val name = "AnimeSalt"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Shared Video Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/series/" else "$baseUrl/series/page/$page/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/category/status/ongoing/" else "$baseUrl/category/status/ongoing/page/$page/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            if (page == 1) "$baseUrl/?s=$encodedQuery" else "$baseUrl/page/$page/?s=$encodedQuery"
        } else {
            var targetPath = ""
            for (filter in filters) {
                when (filter) {
                    is Filters.GenreFilter -> {
                        if (!filter.isDefault()) {
                            targetPath = "category/${filter.toUriPart()}"
                            break
                        }
                    }

                    is Filters.LanguageFilter -> {
                        if (!filter.isDefault()) {
                            targetPath = "category/${filter.toUriPart()}"
                            break
                        }
                    }

                    is Filters.TypeFilter -> {
                        if (!filter.isDefault()) {
                            val part = filter.toUriPart()
                            targetPath = if (part.startsWith("type/")) "category/$part" else part
                            break
                        }
                    }

                    else -> {}
                }
            }

            if (targetPath.isBlank()) {
                if (page == 1) "$baseUrl/series/" else "$baseUrl/series/page/$page/"
            } else {
                if (targetPath.contains("?")) {
                    val pathPart = targetPath.substringBefore("?")
                    val queryPart = targetPath.substringAfter("?")
                    if (page == 1) {
                        "$baseUrl/$pathPart?$queryPart"
                    } else {
                        "$baseUrl/$pathPart" + "page/$page/?$queryPart"
                    }
                } else {
                    val normalizedPath = targetPath.trimEnd('/')
                    if (page == 1) {
                        "$baseUrl/$normalizedPath/"
                    } else {
                        "$baseUrl/$normalizedPath/page/$page/"
                    }
                }
            }
        }

        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.TypeFilter(),
        Filters.LanguageFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.post, .movies-list article, .post.dfx").mapNotNull { element ->
            val link = element.selectFirst(".entry-title a, a.lnk-blk, a") ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            val titleText = element.selectFirst(".entry-title, h2, h3")?.text()?.trim() ?: link.text().trim()
            if (titleText.isBlank() || titleText.contains("\${item.title}")) return@mapNotNull null

            val img = element.selectFirst(".post-thumbnail img, figure img, img")
            val thumb = img?.let {
                it.attr("abs:data-src").ifBlank {
                    it.attr("abs:data-lazy-src").ifBlank {
                        it.attr("abs:src")
                    }
                }
            }?.takeIf { !it.startsWith("data:") }

            SAnime.create().apply {
                title = titleText
                setUrlWithoutDomain(href)
                thumbnail_url = thumb?.let { UrlUtils.fixUrl(it, baseUrl) }
                fetch_type = FetchType.Episodes
            }
        }
        val hasNext = doc.selectFirst(".pagination .next, a.next, .nav-links .next") != null
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val titleText = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: anime.title
        val synopsis = doc.selectFirst("#overview-text p, #overview-text, .overviewCss, .description")?.text()?.trim()
        val genres = doc.select("a[href*='/category/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val statusRaw = doc.select(".status, .Qlty, a[href*='/category/status/']").text()

        val img = doc.selectFirst(".post-thumbnail img, figure img, img.lazyload")
        val thumb = img?.let {
            it.attr("abs:data-src").ifBlank {
                it.attr("abs:data-lazy-src").ifBlank {
                    it.attr("abs:src")
                }
            }
        }?.takeIf { !it.startsWith("data:") }

        return SAnime.create().apply {
            title = titleText
            thumbnail_url = (thumb ?: anime.thumbnail_url)?.let { UrlUtils.fixUrl(it, baseUrl) }
            genre = genres.ifBlank { null }
            description = synopsis
            status = when {
                statusRaw.contains("Ongoing", ignoreCase = true) || doc.select("a[href*='/category/status/ongoing']").isNotEmpty() -> SAnime.ONGOING
                statusRaw.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url.contains("/movies/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    setUrlWithoutDomain(anime.url)
                },
            )
        }

        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val seasonButtons = doc.select(".season-buttons .season-btn[data-post][data-season]")
        val episodes = mutableListOf<SEpisode>()

        if (seasonButtons.isNotEmpty()) {
            for (btn in seasonButtons) {
                val seasonNum = btn.attr("data-season").toIntOrNull() ?: 1
                val postId = btn.attr("data-post")

                val seasonDoc = if (seasonNum == 1 || btn.hasClass("active")) {
                    doc
                } else {
                    val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$seasonNum&post=$postId"
                    client.newCall(GET(ajaxUrl, headers)).execute().asJsoup()
                }

                val seasonEps = parseEpisodesFromDoc(seasonDoc, seasonNum)
                episodes.addAll(seasonEps)
            }
        } else {
            episodes.addAll(parseEpisodesFromDoc(doc, 1))
        }

        return episodes.reversed()
    }

    private fun parseEpisodesFromDoc(doc: Document, seasonNum: Int): List<SEpisode> {
        return doc.select("li:has(article.episodes), article.episodes, .episodes-list li").mapIndexedNotNull { idx, element ->
            val link = element.selectFirst("a.lnk-blk, a") ?: return@mapIndexedNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapIndexedNotNull null

            val rawNum = element.selectFirst(".num-epi")?.text()?.trim()
            val epNum = rawNum?.filter { it.isDigit() || it == '.' }?.toFloatOrNull() ?: (idx + 1).toFloat()
            val rawTitle = element.selectFirst(".entry-title, h2")?.text()?.trim() ?: "Episode ${epNum.toInt()}"
            val cleanTitle = rawTitle.replace(Regex("^Private:\\s*", RegexOption.IGNORE_CASE), "").trim()

            val epNumber = if (seasonNum > 1) {
                ((seasonNum - 1) * 100 + epNum.toInt()).toFloat()
            } else {
                epNum
            }

            val displayName = if (seasonNum > 1) {
                "Season $seasonNum Episode ${epNum.toInt()}: $cleanTitle"
            } else {
                "Episode ${epNum.toInt()}: $cleanTitle"
            }

            SEpisode.create().apply {
                name = displayName
                episode_number = epNumber
                setUrlWithoutDomain(href)
            }
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.newCall(GET("$baseUrl${episode.url}", headers)).execute().asJsoup()
        val hosters = mutableListOf<Hoster>()

        // 1. Direct AS-CDN / FirePlayer iframes
        val cdnIframes = doc.select(".video.aa-tb iframe, iframe[src*='as-cdn'], iframe[data-src*='as-cdn'], iframe[src*='/video/'], iframe[data-src*='/video/']")
        cdnIframes.forEachIndexed { idx, iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.contains("multi-lang-plyr")) {
                hosters.add(
                    Hoster(
                        hosterName = if (idx == 0) "FirePlayer (Multi-Audio)" else "FirePlayer Server ${idx + 1}",
                        hosterUrl = src,
                    ),
                )
            }
        }

        // 2. Multi-Language Player iframes
        val multiLangIframes = doc.select("iframe[src*='multi-lang-plyr'], iframe[data-src*='multi-lang-plyr']")
        for (iframe in multiLangIframes) {
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                hosters.add(
                    Hoster(
                        hosterName = "Multi-Language Streams",
                        hosterUrl = src,
                    ),
                )
            }
        }

        if (hosters.isEmpty()) {
            // Fallback: check any iframe on page
            doc.select("iframe").forEachIndexed { idx, iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() && !src.startsWith("about:") && !src.startsWith("javascript:")) {
                    hosters.add(
                        Hoster(
                            hosterName = "Server ${idx + 1}",
                            hosterUrl = src,
                        ),
                    )
                }
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterUrl = hoster.hosterUrl
        val videos = mutableListOf<Video>()

        if (hosterUrl.contains("/video/") || hosterUrl.contains("as-cdn")) {
            // AS-CDN / FirePlayer extractor
            val videoId = Regex("""/video/([a-zA-Z0-9]+)""").find(hosterUrl)?.groupValues?.get(1)
            val host = runCatching { hosterUrl.toHttpUrl().host }.getOrNull() ?: "as-cdn21.top"
            val scheme = runCatching { hosterUrl.toHttpUrl().scheme }.getOrNull() ?: "https"
            val playerBaseUrl = "$scheme://$host"

            if (videoId != null) {
                val postUrl = "$playerBaseUrl/player/index.php?data=$videoId&do=getVideo"
                val formBody = FormBody.Builder()
                    .add("hash", videoId)
                    .add("r", "$baseUrl/")
                    .build()

                val playerHeaders = headers.newBuilder()
                    .set("Referer", hosterUrl)
                    .set("Origin", playerBaseUrl)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .build()

                val res = client.newCall(POST(postUrl, playerHeaders, formBody)).execute()
                val resBody = res.bodyString()
                val jsonObj = runCatching { json.parseToJsonElement(resBody).jsonObject }.getOrNull()
                val masterUrl = jsonObj?.get("videoSource")?.jsonPrimitive?.content ?: jsonObj?.get("securedLink")?.jsonPrimitive?.content

                if (!masterUrl.isNullOrBlank()) {
                    val hlsVideos = playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = "$playerBaseUrl/",
                        videoNameGen = { quality -> quality },
                    )
                    videos.addAll(hlsVideos)
                }
            }
        } else if (hosterUrl.contains("multi-lang-plyr")) {
            // Decode base64 data query param
            val encodedData = hosterUrl.toHttpUrl().queryParameter("data")
            if (!encodedData.isNullOrBlank()) {
                val decodedJson = runCatching {
                    val raw = Base64.decode(encodedData, Base64.DEFAULT)
                    String(raw, Charsets.UTF_8)
                }.getOrNull()

                if (!decodedJson.isNullOrBlank()) {
                    val array = runCatching { json.parseToJsonElement(decodedJson).jsonArray }.getOrNull()
                    array?.forEach { item ->
                        val obj = item.jsonObject
                        val lang = obj["language"]?.jsonPrimitive?.content ?: "Unknown"
                        val link = obj["link"]?.jsonPrimitive?.content ?: return@forEach

                        val extracted = when {
                            link.contains("dood") || link.contains("ds2play") ->
                                doodExtractor.videosFromUrl(link)

                            link.contains("streamtape") ->
                                streamtapeExtractor.videoFromUrl(link)?.let { listOf(it) } ?: emptyList()

                            link.contains("filemoon") || link.contains("moonplayer") ->
                                filemoonExtractor.videosFromUrl(link, prefix = "$lang - ")

                            link.endsWith(".m3u8") || link.contains(".m3u8?") ->
                                playlistUtils.extractFromHls(link, referer = "$baseUrl/", videoNameGen = { q -> "$q [$lang]" })

                            else ->
                                universalExtractor.videosFromUrl(link, headers, prefix = "$lang - ")
                        }
                        videos.addAll(extracted)
                    }
                }
            }
        } else {
            // Generic fallback
            val extracted = universalExtractor.videosFromUrl(hosterUrl, headers, prefix = "${hoster.hosterName} - ")
            videos.addAll(extracted)
        }

        return videos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://animesalt.link"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

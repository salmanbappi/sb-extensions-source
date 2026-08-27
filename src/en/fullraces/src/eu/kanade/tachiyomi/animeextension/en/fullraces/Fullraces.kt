package eu.kanade.tachiyomi.animeextension.en.fullraces

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.byseextractor.ByseExtractor
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Fullraces : Source() {

    override val name = "Fullraces"

    override val baseUrl = "https://fullraces.com"

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
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val byseExtractor by lazy { ByseExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/?page$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/?page$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.isNotBlank()) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/search/?q=$encodedQuery&p=$page"
        val response = client.newCall(GET(url, headers)).execute()
        parseSearchPage(response)
    } else {
        var path = ""
        filters.forEach { filter ->
            if (filter is Filters.SeriesFilter && !filter.isDefault()) {
                path = filter.toUriPart()
            }
        }
        val url = when {
            path.isBlank() -> if (page == 1) "$baseUrl/" else "$baseUrl/?page$page"
            path.contains("/watch/") -> if (page == 1) "$baseUrl$path" else "$baseUrl$path-$page"
            else -> if (page == 1) "$baseUrl$path" else "$baseUrl$path?page$page"
        }
        val response = client.newCall(GET(url, headers)).execute()
        parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.SeriesFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.short_item, div[id^=entryID]").mapNotNull { element ->
            val link = element.selectFirst("h3 a, .poster a") ?: return@mapNotNull null
            val titleText = element.selectFirst("h3 a")?.text()?.trim() ?: link.text().trim()
            val href = link.attr("href")
            if (titleText.isBlank() || href.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                title = titleText
                setUrlWithoutDomain(href)
                thumbnail_url = element.selectFirst(".poster img, img")?.let { img ->
                    val src = img.attr("src").ifBlank { img.attr("data-src") }
                    if (src.startsWith("/")) "$baseUrl$src" else src
                }
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst("a.swchItem-next, a.swchItem:contains(»)") != null ||
            doc.select("a.swchItem").any { it.attr("href").contains("page") }

        return AnimesPage(animes, hasNext)
    }

    private fun parseSearchPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.statvidp, .eBlock").mapNotNull { element ->
            val link = element.selectFirst(".eTitle a, .tit33fdsq a, a.btn, a") ?: return@mapNotNull null
            val titleText = element.selectFirst(".eTitle, .tit33fdsq")?.text()?.trim() ?: link.text().trim()
            val href = link.attr("href")
            if (titleText.isBlank() || href.isBlank() || href == "/search/") return@mapNotNull null

            SAnime.create().apply {
                title = titleText
                setUrlWithoutDomain(href)
                thumbnail_url = element.selectFirst("img")?.let { img ->
                    val src = img.attr("src").ifBlank { img.attr("data-src") }
                    if (src.startsWith("/")) "$baseUrl$src" else src
                }
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst("a.swchItem-next, a.swchItem:contains(»)") != null ||
            doc.select("a.swchItem").any { it.attr("href").contains("p=") }

        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val synopsis = doc.select("div.full_content p, div.short_descr p").text().trim()
        val category = doc.selectFirst(".speedbar a:last-of-type, .short_cat a")?.text()?.trim()

        return SAnime.create().apply {
            title = anime.title.ifBlank { doc.selectFirst("h1.h_title, .full_content h1")?.text() ?: "" }
            thumbnail_url = anime.thumbnail_url ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            genre = category
            status = SAnime.COMPLETED
            description = synopsis
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val dateStr = doc.selectFirst(".gameplayer .gp-when, .speedbar")?.text() ?: anime.title
        val dateUpload = parseDate(dateStr) ?: parseDate(anime.title) ?: 0L

        return listOf(
            SEpisode.create().apply {
                name = "Full Race Replay"
                setUrlWithoutDomain(anime.url)
                episode_number = 1f
                date_upload = dateUpload
            },
        )
    }

    private fun parseDate(text: String): Long? {
        val match = DATE_REGEX.find(text) ?: return null
        val cleanDate = match.groupValues[1].replace(",", "")
        return runCatching { DATE_FORMAT.parse(cleanDate)?.time }.getOrNull()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.newCall(GET("$baseUrl${episode.url}", headers)).execute().asJsoup()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val hosters = mutableListOf<Hoster>()
        val seenUrls = mutableSetOf<String>()

        fun addHoster(name: String, rawUrl: String) {
            val url = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
            if (!url.startsWith("http") || url in seenUrls) return
            seenUrls.add(url)

            val cleanName = name.replace("&middot;", "·").replace("&nbsp;", " ").trim()
            if (excludedServers.any { it.equals(cleanName, ignoreCase = true) }) return

            hosters.add(Hoster(hosterName = cleanName, hosterUrl = url))
        }

        // 1. GamePlayer tabs
        doc.select(".gameplayer .gp-bar a.gp-src").forEach { el ->
            val href = el.attr("href")
            val title = el.selectFirst("b")?.text() ?: "Server"
            addHoster(title, href)
        }

        // 2. Iframes in document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                val derivedName = when {
                    src.contains("ok.ru") -> "OK.ru"
                    src.contains("dailymotion.com") -> "Dailymotion"
                    src.contains("bysesukior") || src.contains("filemoon") -> "Filemoon"
                    src.contains("streamtape") -> "StreamTape"
                    else -> "Main Player"
                }
                addHoster(derivedName, src)
            }
        }

        // 3. Fallback content links
        val knownPatterns = listOf(
            "ok.ru", "dailymotion.com", "bysesukior.com", "filemoon",
            "streamtape", "dood", "ds2play", "mp4upload", "mixdrop",
            "streamwish", ".m3u8", ".mp4",
        )
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href")
            if (knownPatterns.any { href.contains(it, ignoreCase = true) }) {
                val text = a.text().trim()
                val name = when {
                    text.isNotBlank() && text.length <= 25 && !text.equals("Watch", ignoreCase = true) -> text
                    href.contains("ok.ru") -> "OK.ru"
                    href.contains("dailymotion.com") -> "Dailymotion"
                    href.contains("bysesukior") || href.contains("filemoon") -> "Filemoon"
                    href.contains("streamtape") -> "StreamTape"
                    else -> "Mirror"
                }
                addHoster(name, href)
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val prefix = "${hoster.hosterName} - "

        val videos = runCatching {
            when {
                url.contains("ok.ru") -> okruExtractor.videosFromUrl(url, prefix = prefix)

                url.contains("dailymotion.com") -> dailymotionExtractor.videosFromUrl(url, prefix = prefix)

                url.contains("bysesukior") || url.contains("byse") -> byseExtractor.videosFromUrl(url.replace("/d/", "/e/"), prefix = prefix)

                url.contains("filemoon") || url.contains("moonplayer") -> filemoonExtractor.videosFromUrl(url, prefix = prefix, headers = headers)

                url.contains("streamtape") -> streamtapeExtractor.videoFromUrl(url)?.let { listOf(it) } ?: emptyList()

                url.contains("dood") || url.contains("ds2play") -> doodExtractor.videosFromUrl(url)

                url.endsWith(".m3u8") || url.contains(".m3u8?") -> playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "$prefix$quality" },
                )

                else -> universalExtractor.videosFromUrl(url, headers, prefix = prefix)
            }
        }.getOrDefault(emptyList())

        return videos.sortVideos()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Auto", "OK.ru", "Dailymotion", "Filemoon", "StreamTape"),
            entryValues = listOf("auto", "OK.ru", "Dailymotion", "Filemoon", "StreamTape"),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p", "Auto / Best"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide",
            entries = listOf("OK.ru", "Dailymotion", "Filemoon", "StreamTape"),
            entryValues = listOf("OK.ru", "Dailymotion", "Filemoon", "StreamTape"),
            default = emptySet(),
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { if (prefServer != "auto") it.videoTitle.contains(prefServer, ignoreCase = true) else false }
                .thenByDescending {
                    if (prefQuality.equals("auto", ignoreCase = true)) {
                        it.videoTitle.contains("Auto", ignoreCase = true)
                    } else {
                        it.videoTitle.contains(prefQuality, ignoreCase = true)
                    }
                }
                .thenByDescending { it.resolution },
        )
    }

    companion object {
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private val DATE_FORMAT = SimpleDateFormat("MMMM d yyyy", Locale.US)
        private val DATE_REGEX = Regex("([A-Za-z]+ \\d{1,2},? \\d{4})")
    }
}

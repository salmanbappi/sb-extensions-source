package eu.kanade.tachiyomi.animeextension.en.animostream

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animostream.extractors.AbyssExtractor
import eu.kanade.tachiyomi.animeextension.en.animostream.extractors.EmbedSeekExtractor
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
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class Animostream : Source() {

    override val name = "Animostream"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 5, period = 1.seconds)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // Video Extractors
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val abyssExtractor by lazy { AbyssExtractor(client, playlistUtils) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val embedSeekExtractor by lazy { EmbedSeekExtractor(client, playlistUtils) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // Thread-safe pagination URL caches for Blogger LiteSpot dynamic pagination
    private val popularNextUrlCache = ConcurrentHashMap<Int, String>()
    private val latestNextUrlCache = ConcurrentHashMap<Int, String>()
    private val searchNextUrlCache = ConcurrentHashMap<Int, String>()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/" else (popularNextUrlCache[page] ?: "$baseUrl/"), headers)

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page == 1) popularNextUrlCache.clear()
        val response = client.newCall(popularAnimeRequest(page)).execute()
        return parseAnimeListPage(response, popularNextUrlCache, page)
    }

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request = GET(if (page == 1) "$baseUrl/" else (latestNextUrlCache[page] ?: "$baseUrl/"), headers)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page == 1) latestNextUrlCache.clear()
        val response = client.newCall(latestUpdatesRequest(page)).execute()
        return parseAnimeListPage(response, latestNextUrlCache, page)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            if (page == 1) {
                "$baseUrl/search?q=${query.trim()}"
            } else {
                searchNextUrlCache[page] ?: "$baseUrl/search?q=${query.trim()}"
            }
        } else {
            var selectedGenre = ""
            var selectedLetter = ""
            filters.forEach { filter ->
                when (filter) {
                    is Filters.GenreFilter -> if (!filter.isDefault()) selectedGenre = filter.toUriPart()
                    is Filters.LetterFilter -> if (!filter.isDefault()) selectedLetter = filter.toUriPart()
                    else -> {}
                }
            }

            val targetLabel = selectedLetter.ifBlank { selectedGenre }
            if (targetLabel.isNotBlank()) {
                if (page == 1) {
                    "$baseUrl/search/label/$targetLabel"
                } else {
                    searchNextUrlCache[page] ?: "$baseUrl/search/label/$targetLabel"
                }
            } else {
                if (page == 1) {
                    "$baseUrl/"
                } else {
                    searchNextUrlCache[page] ?: "$baseUrl/"
                }
            }
        }
        return GET(url, headers)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (page == 1) {
            searchNextUrlCache.clear()
        }
        val response = client.newCall(searchAnimeRequest(page, query, filters)).execute()
        return parseAnimeListPage(response, searchNextUrlCache, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.GenreFilter(),
        Filters.LetterFilter(),
    )

    private fun parseAnimeListPage(
        response: Response,
        nextUrlCache: ConcurrentHashMap<Int, String>,
        page: Int,
    ): AnimesPage {
        val doc = response.asJsoup()
        val animeElements = doc.select("article.blog-post, article.hentry, .main-posts article, .Blog article, article")

        val animes = animeElements.mapNotNull { element ->
            val titleEl = element.selectFirst("h2.entry-title a, h1.entry-title a, .entry-title-link, .entry-title")
            val title = titleEl?.text()?.trim() ?: ""
            if (title.isBlank()) return@mapNotNull null

            val linkEl = element.selectFirst("a.entry-title-link, a.entry-image-wrap, h2.entry-title a, a")
            val rawHref = linkEl?.attr("href") ?: ""
            if (rawHref.isBlank()) return@mapNotNull null

            val rawThumb = element.selectFirst("span.entry-thumb")?.attr("data-image")
                ?: element.selectFirst("img")?.attr("data-src")
                ?: element.selectFirst("img")?.attr("src")

            val thumbUrl = upgradeImageQuality(rawThumb)

            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(rawHref)
                thumbnail_url = thumbUrl
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }

        val loadMoreEl = doc.selectFirst("a.blog-pager-older-link, a.load-more, #litespot-pro-load-more-link, .blog-pager a")
        val dataLoad = loadMoreEl?.attr("data-load")?.takeIf { it.isNotBlank() }

        val hasNext = if (dataLoad != null) {
            nextUrlCache[page + 1] = dataLoad
            true
        } else {
            false
        }

        return AnimesPage(animes, hasNext)
    }

    private fun upgradeImageQuality(thumb: String?): String? {
        if (thumb.isNullOrBlank()) return null
        return thumb.replace(Regex("""/w\d+-h\d+[^/]+/"""), "/s1600/")
            .replace(Regex("""/s\d+[^/]+/"""), "/s1600/")
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val title = doc.selectFirst("h1.entry-title, .entry-title")?.text()?.trim() ?: anime.title

        val rawPoster = doc.selectFirst("img#mainPoster, .home-only-image, img.home-only-image, meta[property='og:image']")?.let {
            it.attr("src").ifBlank { it.attr("content") }
        }
        val poster = upgradeImageQuality(rawPoster) ?: anime.thumbnail_url

        val genres = doc.select(".related-tag, .post-labels a, a[rel='tag'], .entry-tags a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString()

        val hiddenSynopsis = doc.selectFirst(".post-body > div[style*='display:none'], .post-body > div[style*='display: none']")?.text()?.trim()
        val metaDesc = doc.selectFirst("meta[name='description']")?.attr("content")?.trim()

        val descriptionText = buildString {
            if (!hiddenSynopsis.isNullOrBlank()) {
                append(hiddenSynopsis)
                append("\n\n")
            }
            if (!metaDesc.isNullOrBlank() && metaDesc != hiddenSynopsis) {
                append(metaDesc)
            }
        }.trim()

        val isCompleted = descriptionText.contains("Complete", ignoreCase = true) ||
            title.contains("Movie", ignoreCase = true) ||
            anime.url.contains("movie", ignoreCase = true)
        val isOngoing = descriptionText.contains("EP", ignoreCase = true) ||
            descriptionText.contains("Added", ignoreCase = true) ||
            descriptionText.contains("Ongoing", ignoreCase = true)

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = poster
            genre = genres.ifBlank { null }
            status = when {
                isCompleted -> SAnime.COMPLETED
                isOngoing -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            description = descriptionText.ifBlank { null }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val html = response.body.string()

        val episodes = mutableListOf<SEpisode>()

        // 1. Check for series animeData JSON
        val animeDataMatch = Regex("""var\s+animeData\s*=\s*(\{.*?\});\s*(?:var|let|const|\n)""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (animeDataMatch != null) {
            val jsonStr = animeDataMatch.groupValues[1]
            try {
                val animeData = JSONObject(jsonStr)
                val seasonKeys = animeData.keys().asSequence().toList().sorted()
                val totalSeasons = seasonKeys.size

                for (sKey in seasonKeys) {
                    val seasonObj = animeData.optJSONObject(sKey) ?: continue
                    val epArray = seasonObj.optJSONArray("episodes") ?: continue
                    val seasonNum = sKey.removePrefix("s").removePrefix("S").toIntOrNull() ?: 1

                    for (i in 0 until epArray.length()) {
                        val epStr = epArray.optString(i, "${i + 1}")
                        val epFloat = epStr.toFloatOrNull() ?: (i + 1).toFloat()

                        val epNumber = if (seasonNum == 1) {
                            epFloat
                        } else {
                            seasonNum + (epFloat / 1000f)
                        }

                        val epName = if (totalSeasons > 1) {
                            "Season $seasonNum Episode $epStr"
                        } else {
                            "Episode $epStr"
                        }

                        episodes.add(
                            SEpisode.create().apply {
                                name = epName
                                episode_number = epNumber
                                url = "${anime.url}#season=$sKey&ep=$i"
                                scanlator = "Multi Audio [Hindi / Eng]"
                                date_upload = 0L
                            },
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Movie or single-post fallback
        if (episodes.isEmpty()) {
            val hasMovieLink = html.contains("abyssLink") ||
                html.contains("streamtapeLink") ||
                html.contains("earnvidsLink") ||
                html.contains("class=\"dubBtn\"") ||
                html.contains("id=\"player\"")

            if (hasMovieLink) {
                episodes.add(
                    SEpisode.create().apply {
                        name = "Full Movie"
                        episode_number = 1.0f
                        url = "${anime.url}#movie"
                        scanlator = "Multi Audio [Hindi / Eng]"
                        date_upload = 0L
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val postPath = episode.url.substringBefore("#")
        val response = client.newCall(GET("$baseUrl$postPath", headers)).execute()
        val html = response.body.string()

        val hosters = mutableListOf<Hoster>()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()

        if (episode.url.contains("#season=")) {
            val seasonKey = episode.url.substringAfter("#season=").substringBefore("&")
            val epIdx = episode.url.substringAfter("&ep=").toIntOrNull() ?: 0

            val animeDataMatch = Regex("""var\s+animeData\s*=\s*(\{.*?\});\s*(?:var|let|const|\n)""", RegexOption.DOT_MATCHES_ALL).find(html)
            if (animeDataMatch != null) {
                val jsonStr = animeDataMatch.groupValues[1]
                try {
                    val animeData = JSONObject(jsonStr)
                    val seasonObj = animeData.optJSONObject(seasonKey)
                    if (seasonObj != null) {
                        val serverKeys = seasonObj.keys().asSequence().filter { it != "episodes" }.toList()
                        for (srv in serverKeys) {
                            val links = seasonObj.optJSONArray(srv) ?: continue
                            val link = links.optString(epIdx, "")
                            if (link.isNotBlank()) {
                                val hosterName = mapHosterName(srv, link)
                                if (hosterName !in excludedServers) {
                                    hosters.add(Hoster(hosterName = hosterName, hosterUrl = link))
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            // Movie extraction
            val linkMatches = Regex("""var\s+(\w+Link)\s*=\s*["']([^"']+)["']""").findAll(html)
            for (match in linkMatches) {
                val varName = match.groupValues[1]
                val link = match.groupValues[2]
                if (link.isNotBlank()) {
                    val hosterName = mapHosterName(varName, link)
                    if (hosterName !in excludedServers && hosters.none { it.hosterUrl == link }) {
                        hosters.add(Hoster(hosterName = hosterName, hosterUrl = link))
                    }
                }
            }
        }

        return hosters
    }

    private fun mapHosterName(keyOrVar: String, url: String): String = when {
        keyOrVar.contains("abyss", ignoreCase = true) || url.contains("abyss") -> "Abyss"
        url.contains("embedseek") -> "EmbedSeek"
        keyOrVar.contains("earnvids", ignoreCase = true) || url.contains("morencius") || url.contains("streamwish") -> "StreamWish"
        keyOrVar.contains("streamtape", ignoreCase = true) || url.contains("streamtape") -> "StreamTape"
        url.contains("dood") -> "DoodStream"
        url.contains("filemoon") -> "FileMoon"
        else -> keyOrVar.removeSuffix("Link").replaceFirstChar { it.uppercase() }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val name = hoster.hosterName

        val videos = when {
            name == "Abyss" || url.contains("abyss") -> {
                abyssExtractor.videosFromUrl(url, referer = "$baseUrl/")
            }

            name == "EmbedSeek" || url.contains("embedseek") -> {
                embedSeekExtractor.videosFromUrl(url, referer = "$baseUrl/")
            }

            name == "StreamWish" || url.contains("morencius") || url.contains("streamwish") -> {
                streamWishExtractor.videosFromUrl(url, prefix = "StreamWish")
            }

            url.contains("streamtape") -> {
                streamtapeExtractor.videoFromUrl(url)?.let { listOf(it) } ?: emptyList()
            }

            url.contains("dood") -> {
                doodExtractor.videosFromUrl(url)
            }

            url.contains("filemoon") -> {
                filemoonExtractor.videosFromUrl(url, prefix = "FileMoon - ")
            }

            else -> {
                universalExtractor.videosFromUrl(url, headers)
            }
        }

        return videos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { it.videoTitle.contains("1080p") }
                .thenByDescending { it.videoTitle.contains("720p") }
                .thenByDescending { it.videoTitle.contains("480p") },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080p", "720p", "480p"),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "preferred_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://www.animostream.xyz"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_EXCLUDE_SERVERS_KEY = "excluded_servers"
    }
}

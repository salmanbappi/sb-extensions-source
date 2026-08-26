package eu.kanade.tachiyomi.animeextension.en.twodhive

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.twodhive.TwoDHiveFilters.CatalogFilter
import eu.kanade.tachiyomi.animeextension.en.twodhive.TwoDHiveFilters.GenreFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder

class TwoDHive : Source() {

    override val name = "2Dhive"
    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
    }
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val extractors by lazy { TwoDHiveExtractors(client, headers, json, playlistUtils) }

    // ============================== Popular ==============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/?list=top&page=$page#anime-list", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("a[href^=/anime?anime=], a[href*=/anime?anime=]")
            .distinctBy { it.attr("href") }
            .mapNotNull { element ->
                val href = element.attr("href")
                val title = element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: element.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val cover = element.selectFirst("img")?.let { img ->
                    img.attr("src").ifBlank { img.attr("data-src") }
                } ?: ""

                SAnime.create().apply {
                    this.url = href
                    this.title = title
                    this.thumbnail_url = cover
                }
            }

        val hasNextPage = document.selectFirst("a:contains(Next), a[href*=&page=]") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest ==============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?list=latest&page=$page#anime-list", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ==============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            return GET("$baseUrl/api/search?q=$encodedQuery", headers)
        }

        var genre = ""
        var catalog = "top"
        for (filter in filters) {
            when (filter) {
                is GenreFilter -> genre = filter.selected
                is CatalogFilter -> catalog = filter.selected
                else -> {}
            }
        }

        return if (genre.isNotBlank()) {
            val encodedGenre = URLEncoder.encode(genre, "UTF-8")
            GET("$baseUrl/genre?genre=$encodedGenre", headers)
        } else if (catalog == "movies") {
            GET("$baseUrl/movies?page=$page", headers)
        } else {
            GET("$baseUrl/?list=$catalog&page=$page#anime-list", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val contentType = response.header("Content-Type") ?: ""
        if (contentType.contains("application/json") || response.request.url.encodedPath.contains("/api/search")) {
            val searchDto = runCatching {
                response.parseAs<SearchResponseDto>()
            }.getOrNull()

            val animeList = searchDto?.results?.mapNotNull { dto ->
                val malId = dto.id ?: return@mapNotNull null
                SAnime.create().apply {
                    url = "/anime?anime=$malId"
                    title = dto.englishTitle?.takeIf { it.isNotBlank() } ?: dto.title ?: "Anime $malId"
                    thumbnail_url = dto.coverImageUrl?.takeIf { it.isNotBlank() }
                        ?: dto.imageUrl
                        ?: dto.smallImageUrl
                }
            } ?: emptyList()

            return AnimesPage(animeList, false)
        }

        return popularAnimeParse(response)
    }

    // ============================== Details ==============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime = animeDetailsParse(response.asJsoup())

    private fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        anime.title = title

        val cover = document.selectFirst("img[alt*='poster'], .aspect-\[2\/3\] img, .aspect-\[3\/4\] img")
            ?.let { img -> img.attr("src").ifBlank { img.attr("data-src") } }
        if (!cover.isNullOrBlank()) {
            anime.thumbnail_url = cover
        }

        val synopsis = document.select("p.text-haze, p.leading-6").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
        anime.description = synopsis

        val genres = document.select("a[href^=/genre?genre=]").map { it.text().trim() }
            .filter { it.isNotBlank() }
        anime.genre = genres.joinToString(", ")

        val chips = document.select(".detail-chip").map { it.text().trim() }
        val statusText = chips.firstOrNull { it.contains("Finished", true) || it.contains("Releasing", true) || it.contains("Currently", true) }
        anime.status = when {
            statusText?.contains("Finished", true) == true -> SAnime.COMPLETED
            statusText?.contains("Releasing", true) == true || statusText?.contains("Currently", true) == true -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

        anime.initialized = true
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()
        val malId = Regex("""anime=(\d+)""").find(currentUrl)?.groupValues?.get(1)
            ?: Regex("""/anime/(\d+)""").find(currentUrl)?.groupValues?.get(1)
            ?: return emptyList()

        var totalEpisodes = document.select(".detail-chip")
            .firstOrNull { it.text().contains("EP ", ignoreCase = true) }
            ?.text()?.replace(Regex("""[^\d]"""), "")?.toIntOrNull()

        if (totalEpisodes == null || totalEpisodes <= 0) {
            val trackerResp = runCatching {
                client.newCall(GET("$baseUrl/api/anime/episodes?id=$malId", headers)).execute().body.string()
            }.getOrNull()
            if (!trackerResp.isNullOrBlank()) {
                val trackerDto = runCatching { trackerDto }.getOrNull()
                totalEpisodes = trackerDto?.episodes
            }
        }

        val total = totalEpisodes ?: 1

        val episodes = mutableListOf<SEpisode>()
        for (epNum in total downTo 1) {
            episodes.add(
                SEpisode.create().apply {
                    this.name = "Episode $epNum"
                    this.episode_number = epNum.toFloat()
                    this.url = "/episode?anime=$malId&ep_num=$epNum"
                },
            )
        }

        return episodes
    }

    // ============================== Videos ==============================
    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val requestUrl = response.request.url.toString()
        val malId = Regex("""anime=(\d+)""").find(requestUrl)?.groupValues?.get(1) ?: return emptyList()
        val epNum = Regex("""ep_num=(\d+)""").find(requestUrl)?.groupValues?.get(1) ?: return emptyList()

        val videos = mutableListOf<Video>()
        val types = listOf("sub", "dub")

        for (type in types) {
            // 1. BabaStream (Direct MP4 / OK.ru)
            runCatching {
                videos.addAll(extractors.extractBabaStream(malId, epNum, type))
            }

            // 2. MegaPlay (Multi-quality HLS + WebVTT tracks)
            runCatching {
                videos.addAll(extractors.extractMegaPlay(malId, epNum, type))
            }
        }

        return videos.sortVideos()
    }

    // ============================== Video Sorting & Preferences ==============================
    private fun List<Video>.sortVideos(): List<Video> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareBy(
                { it.videoTitle.contains(prefServer, true).not() },
                { it.videoTitle.contains(prefAudio, true).not() },
                { it.videoTitle.contains(prefQuality, true).not() },
            ),
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Base URL Override"
            setDefaultValue(DEFAULT_BASE_URL)
            summary = "Current: %s"
            dialogTitle = "Base URL Override"
            dialogMessage = "Override the default domain if 2dhive changes its URL."
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = (newValue as String).trim().removeSuffix("/")
                preferences.edit().putString(key, newUrl).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = arrayOf("MegaPlay", "BabaStream")
            entryValues = arrayOf("MegaPlay", "BabaStream")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred Audio"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("Sub", "Dub")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    override fun getFilterList(): AnimeFilterList = TwoDHiveFilters.FILTER_LIST

    companion object {
        private const val DEFAULT_BASE_URL = "https://2dhive.com"
        private const val PREF_DOMAIN_KEY = "pref_base_url"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "MegaPlay"

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "Sub"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

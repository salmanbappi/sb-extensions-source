package eu.kanade.tachiyomi.animeextension.en.flixer

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.getPreferencesLazy
import extensions.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class Flixer :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Flixer"

    override val baseUrl = "https://flixer.gd"

    private val apiBaseUrl = "https://plsdontscrapemelove.flixer.gd/api/tmdb"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$apiBaseUrl/trending/all/day?page=$page", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$apiBaseUrl/discover/movie?page=$page&sort_by=primary_release_date.desc&vote_count.gte=10", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val request = if (query.isNotBlank()) {
            GET("$apiBaseUrl/search/multi?query=${Uri.encode(query)}&page=$page", headers)
        } else {
            var mediaType = "trending"
            var sortBy = "popularity.desc"
            val genreIds = mutableListOf<String>()

            for (filter in filters) {
                when (filter) {
                    is Filters.MediaTypeFilter -> mediaType = filter.selected

                    is Filters.SortFilter -> sortBy = filter.selected

                    is Filters.GenreFilter -> {
                        filter.state.forEach { check ->
                            if (check.state) genreIds.add(check.value)
                        }
                    }

                    else -> {}
                }
            }

            val endpoint = when (mediaType) {
                "movie" -> {
                    val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${genreIds.joinToString(",")}" else ""
                    "$apiBaseUrl/discover/movie?page=$page&sort_by=$sortBy$genreParam"
                }

                "tv" -> {
                    val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${genreIds.joinToString(",")}" else ""
                    "$apiBaseUrl/discover/tv?page=$page&sort_by=$sortBy$genreParam"
                }

                else -> {
                    "$apiBaseUrl/trending/all/day?page=$page"
                }
            }
            GET(endpoint, headers)
        }

        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val endpoint = if (isMovie) "$apiBaseUrl/movie/$id" else "$apiBaseUrl/tv/$id"

        return try {
            val response = client.newCall(GET(endpoint, headers)).execute()
            if (isMovie) {
                val details = response.parseAs<MovieDetailsDto>(json)
                details.toSAnime(anime.url)
            } else {
                val details = response.parseAs<TvDetailsDto>(json)
                details.toSAnime(anime.url)
            }
        } catch (_: Exception) {
            anime
        }.apply {
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")

        if (isMovie) {
            return listOf(
                SEpisode.create().apply {
                    name = "Full Movie"
                    episode_number = 1.0f
                    url = if (anime.url.startsWith("/")) anime.url else "/movie/$id"
                },
            )
        }

        val episodeList = mutableListOf<SEpisode>()
        try {
            val tvResponse = client.newCall(GET("$apiBaseUrl/tv/$id", headers)).execute()
            val tvDetails = tvResponse.parseAs<TvDetailsDto>(json)
            val seasons = tvDetails.seasons ?: emptyList()
            val validSeasons = seasons.filter {
                val sNum = it.season_number ?: 0
                sNum > 0 && (it.episode_count ?: 0) > 0
            }.ifEmpty {
                seasons.filter { (it.episode_count ?: 0) > 0 }
            }

            for (season in validSeasons) {
                val seasonNum = season.season_number ?: 1
                val count = season.episode_count ?: 1
                var loadedFromApi = false

                try {
                    val seasonRes = client.newCall(GET("$apiBaseUrl/tv/$id/season/$seasonNum", headers)).execute()
                    val seasonDetails = seasonRes.parseAs<SeasonDetailsDto>(json)
                    val eps = seasonDetails.episodes ?: emptyList()
                    if (eps.isNotEmpty()) {
                        eps.forEach { ep ->
                            episodeList.add(ep.toSEpisode(id.toLong(), seasonNum))
                        }
                        loadedFromApi = true
                    }
                } catch (_: Exception) {}

                if (!loadedFromApi && count > 0) {
                    for (epNum in 1..count) {
                        episodeList.add(
                            SEpisode.create().apply {
                                name = "S$seasonNum E$epNum - Episode $epNum"
                                episode_number = epNum.toFloat()
                                url = "/tv/$id?season=$seasonNum&episode=$epNum"
                                scanlator = "Season $seasonNum"
                            },
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return listOf(
                SEpisode.create().apply {
                    name = "Full Movie / Episode 1"
                    episode_number = 1.0f
                    url = "/movie/$id"
                },
            )
        }

        if (episodeList.isEmpty()) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1.0f
                    url = "/tv/$id?season=1&episode=1"
                },
            )
        }

        return episodeList.distinctBy { it.url }.reversed()
    }

    // ============================ 2-Tier Hoster Folders (7 Servers) =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val isMovie = episode.url.contains("movie")
        val id = if (isMovie) {
            episode.url.substringAfterLast("/").substringBefore("?")
        } else {
            episode.url.substringAfter("/tv/").substringBefore("?")
        }

        val parsedUri = Uri.parse("https://dummy.com${episode.url}")
        val season = parsedUri.getQueryParameter("season") ?: "1"
        val ep = parsedUri.getQueryParameter("episode") ?: "1"

        // 7 distinct server folders
        val servers = if (isMovie) {
            listOf(
                Hoster(hosterName = "Server 1 (Ares - VidSrc)", hosterUrl = "https://vidsrc.to/embed/movie/$id"),
                Hoster(hosterName = "Server 2 (Balder - VidLink)", hosterUrl = "https://vidlink.pro/movie/$id"),
                Hoster(hosterName = "Server 3 (Circe - VidFast)", hosterUrl = "https://vidfast.pro/movie/$id"),
                Hoster(hosterName = "Server 4 (Dionysus - Vidrock)", hosterUrl = "https://vidrock.ru/movie/$id"),
                Hoster(hosterName = "Server 5 (Eros - 2Embed)", hosterUrl = "https://www.2embed.cc/embed/$id"),
                Hoster(hosterName = "Server 6 (Freya - Smashy)", hosterUrl = "https://embed.smashystream.com/playere.php?tmdb=$id"),
                Hoster(hosterName = "Server 7 (Gaia - MultiEmbed)", hosterUrl = "https://multiembed.mov/?video_id=$id&tmdb=1"),
            )
        } else {
            listOf(
                Hoster(hosterName = "Server 1 (Ares - VidSrc)", hosterUrl = "https://vidsrc.to/embed/tv/$id/$season/$ep"),
                Hoster(hosterName = "Server 2 (Balder - VidLink)", hosterUrl = "https://vidlink.pro/tv/$id/$season/$ep"),
                Hoster(hosterName = "Server 3 (Circe - VidFast)", hosterUrl = "https://vidfast.pro/tv/$id/$season/$ep"),
                Hoster(hosterName = "Server 4 (Dionysus - Vidrock)", hosterUrl = "https://vidrock.ru/tv/$id/$season/$ep"),
                Hoster(hosterName = "Server 5 (Eros - 2Embed)", hosterUrl = "https://www.2embed.cc/embedtv/$id&s=$season&e=$ep"),
                Hoster(hosterName = "Server 6 (Freya - Smashy)", hosterUrl = "https://embed.smashystream.com/playere.php?tmdb=$id&season=$season&episode=$ep"),
                Hoster(hosterName = "Server 7 (Gaia - MultiEmbed)", hosterUrl = "https://multiembed.mov/?video_id=$id&tmdb=1&s=$season&e=$ep"),
            )
        }

        return orderHostersByPref(servers)
    }

    private fun orderHostersByPref(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    // ============================ Inside Folder: Quality & Stream Selection =============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val videoList = mutableListOf<Video>()

        try {
            when {
                url.contains("vidsrc") -> {
                    try {
                        videoList.addAll(vidsrcExtractor.videosFromUrl(url, hosterName = ""))
                    } catch (_: Exception) {}
                }

                url.contains("filemoon") -> {
                    try {
                        videoList.addAll(filemoonExtractor.videosFromUrl(url))
                    } catch (_: Exception) {}
                }

                url.contains("dood") -> {
                    try {
                        videoList.addAll(doodExtractor.videosFromUrl(url))
                    } catch (_: Exception) {}
                }

                url.contains("streamtape") -> {
                    try {
                        videoList.addAll(streamTapeExtractor.videosFromUrl(url))
                    } catch (_: Exception) {}
                }
            }

            if (videoList.isEmpty()) {
                try {
                    videoList.addAll(universalExtractor.videosFromUrl(url, headers))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (videoList.isEmpty()) {
            videoList.add(
                Video(
                    videoUrl = url,
                    videoTitle = "Auto (Web Stream)",
                    headers = headers,
                ),
            )
        }

        // Clean up redundant prefixes if any
        val cleanedList = videoList.map { v ->
            val cleanTitle = v.videoTitle.trim().removePrefix("-").trim().ifBlank { "Auto" }
            Video(
                videoUrl = v.videoUrl,
                videoTitle = cleanTitle,
                headers = v.headers,
                subtitleTracks = v.subtitleTracks,
                audioTracks = v.audioTracks,
            )
        }

        return cleanedList.sortVideos()
    }

    // ============================== Settings / Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Preferred Server",
            summary = "%s",
            entries = listOf(
                "Server 1 (Ares)",
                "Server 2 (Balder)",
                "Server 3 (Circe)",
                "Server 4 (Dionysus)",
                "Server 5 (Eros)",
                "Server 6 (Freya)",
                "Server 7 (Gaia)",
            ),
            entryValues = listOf("Ares", "Balder", "Circe", "Dionysus", "Eros", "Freya", "Gaia"),
            default = PREF_HOSTER_DEFAULT,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p", "Auto"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"),
            default = PREF_QUALITY_DEFAULT,
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(qualityPref, ignoreCase = true) }
                .thenByDescending { getVideoQualityWeight(it.videoTitle) },
        )
    }

    private fun getVideoQualityWeight(title: String): Int {
        val lower = title.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160p") -> 4000
            lower.contains("1080p") -> 1080
            lower.contains("720p") -> 720
            lower.contains("480p") -> 480
            lower.contains("360p") -> 360
            lower.contains("hevc") || lower.contains("x265") -> 50
            lower.contains("av1") -> 60
            lower.contains("10-bit") || lower.contains("hdr") -> 10
            else -> 0
        }
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "Ares"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

package eu.kanade.tachiyomi.animeextension.en.watchroo

import android.net.Uri
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

class Watchroo :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Watchroo"

    override val baseUrl = "https://watchroo.com"

    override val lang = "en"

    override val supportsLatest = true

    private val apiUrl = "https://api.themoviedb.org/3"

    /**
     * TMDB API key used by watchroo.com's own client config
     * (/_next/static/chunks/3qw0sofwx0l6d.js). The site embeds a bearer token
     * whose JWT `aud` claim is this key; the site fetches all metadata from the
     * TMDB API v3 with that account credential.
     */
    private val tmdbApiKey = "c54b6269f7ed68656edbde358128bfb6"

    // ============================== moviesapi.to ==============================
    // Watchroo's player iframes moviesapi.to and that SPA resolves streams through
    // its "vidora" API. We call that API directly.
    private val moviesApiBaseUrl = "https://moviesapi.to"
    private val xPlayerKey = "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"

    private val moviesApiHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36")
            .add("Referer", "$moviesApiBaseUrl/")
            .add("Origin", moviesApiBaseUrl)
            .add("x-player-key", xPlayerKey)
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")

    private val playlistUtils by lazy { PlaylistUtils(client, moviesApiHeaders) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$apiUrl/trending/all/day?page=$page&api_key=$tmdbApiKey", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET(
            "$apiUrl/discover/movie?page=$page&sort_by=primary_release_date.desc&vote_count.gte=10&api_key=$tmdbApiKey",
            headers,
        )
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val request = if (query.isNotBlank()) {
            GET("$apiUrl/search/multi?query=${Uri.encode(query)}&page=$page&api_key=$tmdbApiKey", headers)
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

            val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${genreIds.joinToString(",")}" else ""
            val endpoint = when (mediaType) {
                "movie" -> "$apiUrl/discover/movie?page=$page&sort_by=$sortBy$genreParam&api_key=$tmdbApiKey"
                "tv" -> "$apiUrl/discover/tv?page=$page&sort_by=$sortBy$genreParam&api_key=$tmdbApiKey"
                else -> "$apiUrl/trending/all/day?page=$page&api_key=$tmdbApiKey"
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
        val endpoint = if (isMovie) {
            "$apiUrl/movie/$id?api_key=$tmdbApiKey"
        } else {
            "$apiUrl/tv/$id?api_key=$tmdbApiKey"
        }

        return try {
            val response = client.newCall(GET(endpoint, headers)).execute()
            if (isMovie) {
                response.parseAs<MovieDetailsDto>(json).toSAnime(anime.url)
            } else {
                response.parseAs<TvDetailsDto>(json).toSAnime(anime.url)
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
                    episode_number = 1f
                    url = "$anime.url#movie"
                    scanlator = "Movie"
                },
            )
        }

        val showId = id.toLongOrNull() ?: return emptyList()
        val episodes = mutableListOf<SEpisode>()

        val details = try {
            client.newCall(GET("$apiUrl/tv/$showId?api_key=$tmdbApiKey", headers)).execute()
                .parseAs<TvDetailsDto>(json)
        } catch (_: Exception) {
            null
        }
        val seasonNumbers = (details?.seasons ?: emptyList())
            .mapNotNull { it.season_number }
            .filter { it >= 1 }
            .sorted()
        val multiSeason = seasonNumbers.any { it > 1 }

        seasonNumbers.forEach { seasonNum ->
            try {
                val season = client.newCall(GET("$apiUrl/tv/$showId/season/$seasonNum?api_key=$tmdbApiKey", headers)).execute()
                    .parseAs<SeasonDetailsDto>(json)
                (season.episodes ?: emptyList()).forEach { ep ->
                    val epNum = ep.episode_number ?: 1
                    val episodeNumber = if (multiSeason) ((seasonNum - 1) * 100 + epNum).toFloat() else epNum.toFloat()
                    episodes.add(ep.toSEpisode(showId, seasonNum, episodeNumber))
                }
            } catch (_: Exception) {
                // skip season on error
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================== Hosters ===============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = listOf(
            Hoster(
                hosterName = "MoviesAPI",
                hosterUrl = "moviesapi:${episodeVidoraPath(episode)}",
            ),
            Hoster(
                hosterName = "Watchroo Player",
                hosterUrl = "watchroo:${watchrooPlayerUrl(episode)}",
            ),
        )

        val preferred = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return if (preferred == PREF_HOSTER_DEFAULT) hosters else hosters.filter { it.hosterName == preferred }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl
        val videos = when {
            rawUrl.startsWith("moviesapi:") -> extractMoviesApi(rawUrl.removePrefix("moviesapi:"))
            rawUrl.startsWith("watchroo:") -> extractWatchrooPlayer(rawUrl.removePrefix("watchroo:"))
            else -> emptyList()
        }
        return videos.sortVideos()
    }

    // ============================ Stream Extraction ============================

    /**
     * Calls the vidora API that powers https://moviesapi.to (the embedded player
     * Watchroo uses). Returns an empty list when the title is not available
     * ({"result": false, "message": "Movie not yet encoded"}) so playback can
     * fall through to the next hoster.
     */
    private suspend fun extractMoviesApi(path: String): List<Video> {
        val vidoraUrl = "$moviesApiBaseUrl/api/vidora/v1$path"
        val response = try {
            client.newCall(GET(vidoraUrl, moviesApiHeaders)).execute()
        } catch (_: Exception) {
            return emptyList()
        }
        if (!response.isSuccessful) return emptyList()

        val dto = try {
            response.parseAs<VidoraResponseDto>(json)
        } catch (_: Exception) {
            return emptyList()
        }
        if (dto.result != true) return emptyList()

        val videos = mutableListOf<Video>()
        (dto.sources ?: emptyList()).forEach { source ->
            val streamUrl = source.url ?: return@forEach
            val subtitleTracks = (source.tracks ?: emptyList()).mapNotNull { track ->
                val file = track.file ?: return@mapNotNull null
                Track(file, track.label ?: "Unknown")
            }
            try {
                val extracted = playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "$moviesApiBaseUrl/",
                    masterHeaders = moviesApiHeaders,
                    videoHeaders = moviesApiHeaders,
                    videoNameGen = { quality -> "MoviesAPI - $quality" },
                    subtitleList = subtitleTracks,
                )
                videos.addAll(extracted)
            } catch (_: Exception) {
                videos.add(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "MoviesAPI (HLS)",
                        headers = moviesApiHeaders,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        }
        return videos
    }

    /**
     * Fallback: loads Watchroo's own player page (which iframes moviesapi.to)
     * in a WebView and sniffs the media request. Slower than the direct API.
     */
    private suspend fun extractWatchrooPlayer(playerUrl: String): List<Video> = try {
        universalExtractor.videosFromUrl(playerUrl, headers, "Watchroo")
    } catch (_: Exception) {
        emptyList()
    }

    // ============================== URL Helpers ==============================
    // episode.url shapes (permanent anchors, never contain ephemeral tokens):
    //   movie: "/movie/550#movie"
    //   tv:    "/tv/94997#season=1&ep=1"
    private fun episodeVidoraPath(episode: SEpisode): String {
        val base = episode.url.substringBefore("#")
        val anchor = episode.url.substringAfter("#", "")
        if (anchor == "movie") return base
        val season = ANCHOR_SEASON.find(anchor)?.groupValues?.get(1) ?: return base
        val ep = ANCHOR_EPISODE.find(anchor)?.groupValues?.get(1) ?: return base
        return "$base/$season/$ep"
    }

    private fun watchrooPlayerUrl(episode: SEpisode): String {
        val base = episode.url.substringBefore("#")
        val anchor = episode.url.substringAfter("#", "")
        val id = base.substringAfterLast("/")
        return if (anchor == "movie") {
            "$baseUrl/player?type=movie&id=$id"
        } else {
            val season = ANCHOR_SEASON.find(anchor)?.groupValues?.get(1).orEmpty()
            val ep = ANCHOR_EPISODE.find(anchor)?.groupValues?.get(1).orEmpty()
            "$baseUrl/player?type=tv&id=$id&season=$season&episode=$ep"
        }
    }

    // ============================== Preferences ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Preferred Server",
            summary = "%s",
            entries = listOf(
                "All Servers",
                "MoviesAPI",
                "Watchroo Player",
            ),
            entryValues = listOf(
                "All Servers",
                "MoviesAPI",
                "Watchroo Player",
            ),
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
        private const val PREF_HOSTER_DEFAULT = "All Servers"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val ANCHOR_SEASON = Regex("season=(\\d+)")
        private val ANCHOR_EPISODE = Regex("ep=(\\d+)")
    }
}

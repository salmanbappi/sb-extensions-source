package eu.kanade.tachiyomi.animeextension.en.oneshows

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.getPreferencesLazy
import extensions.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Oneshows : Source(), ConfigurableAnimeSource {

    override val name = "1Shows"

    override val baseUrl = "https://www.1shows.org"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 2, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/trending/tv/day?page=$page", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/discover/tv?page=$page&sort_by=first_air_date.desc", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val request = if (query.isNotBlank()) {
            GET("$baseUrl/api/search/query?query=${Uri.encode(query)}&page=$page", headers)
        } else {
            var mediaType = "tv"
            var sortBy = "popularity.desc"
            val genreIds = mutableListOf<String>()

            for (filter in filters) {
                when (filter) {
                    is Filters.TypeFilter -> {
                        when (filter.toUriPart()) {
                            "movie" -> mediaType = "movie"
                            "anime_tv" -> {
                                mediaType = "tv"
                                genreIds.add("16")
                            }
                            "anime_movie" -> {
                                mediaType = "movie"
                                genreIds.add("16")
                            }
                            else -> mediaType = "tv"
                        }
                    }
                    is Filters.SortFilter -> {
                        val sortVal = filter.toUriPart()
                        sortBy = if (sortVal == "date.desc") {
                            if (mediaType == "movie") "primary_release_date.desc" else "first_air_date.desc"
                        } else {
                            sortVal
                        }
                    }
                    is Filters.GenreFilter -> {
                        genreIds.addAll(filter.getIncluded())
                    }
                    else -> {}
                }
            }

            val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${genreIds.distinct().joinToString(",")}" else ""
            GET("$baseUrl/api/discover/$mediaType?page=$page&sort_by=$sortBy$genreParam", headers)
        }

        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.SortFilter(),
        Filters.GenreFilter(),
    )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val endpoint = if (isMovie) "$baseUrl/api/movie/$id" else "$baseUrl/api/tv/$id"

        val response = client.newCall(GET(endpoint, headers)).execute()
        return if (isMovie) {
            val details = response.parseAs<MovieDetailsDto>(json)
            details.toSAnime(anime.url)
        } else {
            val details = response.parseAs<TvDetailsDto>(json)
            details.toSAnime(anime.url)
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
                    name = "Movie"
                    episode_number = 1.0f
                    url = "/movie/$id"
                },
            )
        }

        val tvResponse = client.newCall(GET("$baseUrl/api/tv/$id", headers)).execute()
        val tvDetails = tvResponse.parseAs<TvDetailsDto>(json)
        val validSeasons = (tvDetails.seasons ?: emptyList()).filter {
            val sNum = it.season_number ?: 0
            sNum > 0 && (it.episode_count ?: 0) > 0
        }

        val episodeList = mutableListOf<SEpisode>()
        coroutineScope {
            val seasonDeferreds = validSeasons.map { season ->
                async(Dispatchers.IO) {
                    val seasonNum = season.season_number ?: 1
                    try {
                        val seasonRes = client.newCall(GET("$baseUrl/api/tv/$id/season/$seasonNum", headers)).execute()
                        val seasonDetails = seasonRes.parseAs<SeasonDetailsDto>(json)
                        (seasonDetails.episodes ?: emptyList()).map { ep ->
                            val epNum = ep.episode_number ?: 1
                            SEpisode.create().apply {
                                name = "S$seasonNum E$epNum - ${ep.name ?: "Episode $epNum"}"
                                episode_number = epNum.toFloat()
                                date_upload = parseDate(ep.air_date)
                                url = "/tv/$id?season=$seasonNum&episode=$epNum"
                                scanlator = "Season $seasonNum"
                            }
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            seasonDeferreds.awaitAll().forEach {
                episodeList.addAll(it)
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================
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

        return if (isMovie) {
            listOf(
                Hoster(hosterName = "Vidzee (Direct HLS)", hosterUrl = "https://player.vidzee.wtf/embed/movie/$id"),
                Hoster(hosterName = "VidLink", hosterUrl = "https://vidlink.pro/movie/$id"),
                Hoster(hosterName = "VidFast", hosterUrl = "https://vidfast.pro/movie/$id"),
                Hoster(hosterName = "Viduki", hosterUrl = "https://www.viduki.net/1/movie/$id"),
                Hoster(hosterName = "VidRock", hosterUrl = "https://vidrock.ru/movie/$id"),
            )
        } else {
            listOf(
                Hoster(hosterName = "Vidzee (Direct HLS)", hosterUrl = "https://player.vidzee.wtf/embed/tv/$id/$season/$ep"),
                Hoster(hosterName = "VidLink", hosterUrl = "https://vidlink.pro/tv/$id/$season/$ep"),
                Hoster(hosterName = "VidFast", hosterUrl = "https://vidfast.pro/tv/$id/$season/$ep"),
                Hoster(hosterName = "Viduki", hosterUrl = "https://www.viduki.net/1/tv/$id/$season/$ep"),
                Hoster(hosterName = "VidRock", hosterUrl = "https://vidrock.ru/tv/$id/$season/$ep"),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl
        val embedHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "$baseUrl/")
            .build()

        return try {
            universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = hoster.hosterName)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)

        return sortedWith(
            compareByDescending<Video> { hoster != null && it.videoTitle.contains(hoster, ignoreCase = true) }
                .thenByDescending { quality != null && it.videoTitle.contains(quality, ignoreCase = true) },
        )
    }

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Preferred Server"
            entries = arrayOf("Vidzee", "VidLink", "VidFast", "Viduki", "VidRock")
            entryValues = arrayOf("Vidzee", "VidLink", "VidFast", "Viduki", "VidRock")
            default = PREF_HOSTER_DEFAULT
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Auto")
            entryValues = arrayOf("1080", "720", "480", "360", "Auto")
            default = PREF_QUALITY_DEFAULT
            summary = "%s"
        }.also(screen::addPreference)
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "Vidzee"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class SearchResponseDto(
    val page: Int? = null,
    val results: List<MediaItemDto>? = null,
    val total_pages: Int? = null,
    val total_results: Int? = null,
)

@Serializable
data class MediaItemDto(
    val id: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val media_type: String? = null,
    val first_air_date: String? = null,
    val release_date: String? = null,
    val vote_average: Double? = null,
) {
    fun toSAnime(): SAnime? {
        val itemId = id ?: return null
        val itemTitle = title ?: name ?: return null
        val type = media_type ?: if (first_air_date != null) "tv" else "movie"
        return SAnime.create().apply {
            this.title = itemTitle
            this.url = "/$type/$itemId"
            this.thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.description = overview
            this.status = SAnime.UNKNOWN
            this.fetch_type = FetchType.Episodes
        }
    }
}

@Serializable
data class MovieDetailsDto(
    val id: Long? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val status: String? = null,
    val genres: List<GenreDto>? = null,
    val production_companies: List<CompanyDto>? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime = SAnime.create().apply {
        this.title = this@MovieDetailsDto.title ?: ""
        this.url = fallbackUrl
        this.thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        this.description = overview
        this.genre = genres?.mapNotNull { it.name }?.joinToString()
        this.author = production_companies?.mapNotNull { it.name }?.joinToString()
        this.status = when (this@MovieDetailsDto.status?.lowercase()) {
            "released" -> SAnime.COMPLETED
            "in production", "planned" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        this.fetch_type = FetchType.Episodes
    }
}

@Serializable
data class TvDetailsDto(
    val id: Long? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val status: String? = null,
    val genres: List<GenreDto>? = null,
    val production_companies: List<CompanyDto>? = null,
    val seasons: List<SeasonDto>? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime = SAnime.create().apply {
        this.title = this@TvDetailsDto.name ?: ""
        this.url = fallbackUrl
        this.thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        this.description = overview
        this.genre = genres?.mapNotNull { it.name }?.joinToString()
        this.author = production_companies?.mapNotNull { it.name }?.joinToString()
        this.status = when (this@TvDetailsDto.status?.lowercase()) {
            "returning series", "in production" -> SAnime.ONGOING
            "ended", "canceled" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        this.fetch_type = FetchType.Episodes
    }
}

@Serializable
data class SeasonDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val episode_count: Int? = null,
    val air_date: String? = null,
    val poster_path: String? = null,
)

@Serializable
data class SeasonDetailsDto(
    val id: String? = null,
    val season_number: Int? = null,
    val episodes: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val still_path: String? = null,
    val vote_average: Double? = null,
)

@Serializable
data class GenreDto(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class CompanyDto(
    val id: Int? = null,
    val name: String? = null,
)

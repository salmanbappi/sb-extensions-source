package eu.kanade.tachiyomi.animeextension.en.watchroo

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

private fun parseDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return 0L
    return try {
        dateFormat.parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

@Serializable
data class SearchResponseDto(
    val page: Int? = null,
    val total_pages: Int? = null,
    val results: List<TmdbItemDto>? = null,
)

@Serializable
data class TmdbItemDto(
    val id: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val media_type: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val genre_ids: List<Int>? = null,
) {
    fun toSAnime(): SAnime? {
        val itemId = id ?: return null
        val isMovie = media_type == "movie" || (title != null && media_type != "tv")
        val displayTitle = title ?: name ?: "Unknown"
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""

        return SAnime.create().apply {
            this.title = displayTitle
            this.url = if (isMovie) "/movie/$itemId" else "/tv/$itemId"
            this.thumbnail_url = fullImageUrl
            this.description = overview
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
    val vote_average: Double? = null,
    val runtime: Int? = null,
    val genres: List<GenreDto>? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime {
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""

        return SAnime.create().apply {
            this.title = this@MovieDetailsDto.title ?: "Movie"
            this.url = fallbackUrl
            this.thumbnail_url = fullImageUrl
            this.description = overview
            this.genre = genres?.mapNotNull { it.name }?.joinToString(", ")
            this.status = when (this@MovieDetailsDto.status) {
                "Released" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
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
    val vote_average: Double? = null,
    val number_of_seasons: Int? = null,
    val number_of_episodes: Int? = null,
    val genres: List<GenreDto>? = null,
    val seasons: List<SeasonSummaryDto>? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime {
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""

        return SAnime.create().apply {
            this.title = this@TvDetailsDto.name ?: "TV Show"
            this.url = fallbackUrl
            this.thumbnail_url = fullImageUrl
            this.description = overview
            this.genre = genres?.mapNotNull { it.name }?.joinToString(", ")
            this.status = when (this@TvDetailsDto.status) {
                "Ended", "Canceled" -> SAnime.COMPLETED
                "Returning Series", "In Production" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }
}

@Serializable
data class SeasonSummaryDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val episode_count: Int? = null,
    val name: String? = null,
    val air_date: String? = null,
    val poster_path: String? = null,
)

@Serializable
data class SeasonDetailsDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val episodes: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: Long? = null,
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val still_path: String? = null,
    val vote_average: Double? = null,
) {
    /**
     * @param episodeNumber zero-base episode number: plain ep number for single-season
     * shows, ((season - 1) * 100 + ep) for multi-season shows.
     */
    fun toSEpisode(showId: Long, seasonNum: Int, episodeNumber: Float): SEpisode {
        val epNum = episode_number ?: 1
        val epName = name ?: "Episode $epNum"
        val thumbPath = still_path
        return SEpisode.create().apply {
            this.name = "S$seasonNum E$epNum - $epName"
            this.episode_number = episodeNumber
            this.date_upload = parseDate(air_date)
            this.url = "/tv/$showId#season=$seasonNum&ep=$epNum"
            this.scanlator = "Season $seasonNum"
            this.preview_url = if (!thumbPath.isNullOrBlank()) "$TMDB_IMAGE_BASE$thumbPath" else null
        }
    }
}

@Serializable
data class GenreDto(
    val id: Int? = null,
    val name: String? = null,
)

// ============================== Vidora (moviesapi.to) ==============================

@Serializable
data class VidoraResponseDto(
    val result: Boolean? = null,
    val type: String? = null,
    val title: String? = null,
    val tmdb_id: Int? = null,
    val imdb_id: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val view_type: String? = null,
    val view_id: Int? = null,
    val sources: List<VidoraSourceDto>? = null,
)

@Serializable
data class VidoraSourceDto(
    val file_code: String? = null,
    val url: String? = null,
    val source: String? = null,
    val tracks: List<VidoraTrackDto>? = null,
)

@Serializable
data class VidoraTrackDto(
    val file: String? = null,
    val label: String? = null,
)

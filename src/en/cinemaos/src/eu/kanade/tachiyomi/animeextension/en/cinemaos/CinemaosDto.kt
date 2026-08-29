package eu.kanade.tachiyomi.animeextension.en.cinemaos

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
    val total_results: Int? = null,
    val results: List<TmdbItemDto>? = null,
)

@Serializable
data class TmdbItemDto(
    val id: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val original_title: String? = null,
    val original_name: String? = null,
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
        val displayTitle = title ?: name ?: original_title ?: original_name ?: "Unknown"
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""

        return SAnime.create().apply {
            this.title = displayTitle
            this.url = if (isMovie) "/watch/movie/$itemId" else "/watch/tv/$itemId"
            this.thumbnail_url = fullImageUrl
            this.description = overview
        }
    }
}

@Serializable
data class MovieDetailsDto(
    val id: Long? = null,
    val title: String? = null,
    val original_title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val status: String? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null,
    val runtime: Int? = null,
    val genres: List<GenreDto>? = null,
    val videos: VideoResultsDto? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime {
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""
        val releaseYear = (release_date ?: "").take(4)
        val score = vote_average
        val statusStr = status
        val trailerKey = videos?.results?.firstOrNull {
            it.site.equals("YouTube", true) && it.type.equals("Trailer", true)
        }?.key

        return SAnime.create().apply {
            this.title = this@MovieDetailsDto.title ?: this@MovieDetailsDto.original_title ?: "Movie"
            this.url = fallbackUrl
            this.thumbnail_url = fullImageUrl
            this.genre = genres?.mapNotNull { it.name }?.joinToString(", ")
            this.status = when (statusStr) {
                "Released" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            this.description = buildString {
                if (score != null && score > 0.0) {
                    val stars = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} ${"%.2f".format(score)}")
                    if (vote_count != null && vote_count > 0) append(" ($vote_count votes)")
                    append("\n\n")
                }
                if (!overview.isNullOrBlank()) append(overview)
                if (runtime != null && runtime > 0) append("\n\nDuration: ${runtime}m")
                if (releaseYear.isNotBlank()) append("\nYear: $releaseYear")
                if (!statusStr.isNullOrBlank()) append("\nStatus: $statusStr")
                if (!trailerKey.isNullOrBlank()) {
                    append("\n\n[Trailer](https://www.youtube.com/watch?v=$trailerKey)")
                }
            }.trim()
            this.initialized = true
        }
    }
}

@Serializable
data class TvDetailsDto(
    val id: Long? = null,
    val name: String? = null,
    val original_name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val last_air_date: String? = null,
    val status: String? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null,
    val number_of_seasons: Int? = null,
    val number_of_episodes: Int? = null,
    val genres: List<GenreDto>? = null,
    val seasons: List<SeasonSummaryDto>? = null,
    val videos: VideoResultsDto? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime {
        val imagePath = poster_path ?: backdrop_path
        val fullImageUrl = if (!imagePath.isNullOrBlank()) "$TMDB_IMAGE_BASE$imagePath" else ""
        val releaseYear = (first_air_date ?: "").take(4)
        val score = vote_average
        val statusStr = status
        val trailerKey = videos?.results?.firstOrNull {
            it.site.equals("YouTube", true) && it.type.equals("Trailer", true)
        }?.key

        return SAnime.create().apply {
            this.title = this@TvDetailsDto.name ?: this@TvDetailsDto.original_name ?: "TV Show"
            this.url = fallbackUrl
            this.thumbnail_url = fullImageUrl
            this.genre = genres?.mapNotNull { it.name }?.joinToString(", ")
            this.status = when (statusStr) {
                "Ended", "Canceled" -> SAnime.COMPLETED
                "Returning Series", "In Production" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            this.description = buildString {
                if (score != null && score > 0.0) {
                    val stars = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} ${"%.2f".format(score)}")
                    if (vote_count != null && vote_count > 0) append(" ($vote_count votes)")
                    append("\n\n")
                }
                if (!overview.isNullOrBlank()) append(overview)
                if (number_of_seasons != null) append("\n\nSeasons: $number_of_seasons")
                if (number_of_episodes != null) append(" • Episodes: $number_of_episodes")
                if (releaseYear.isNotBlank()) append("\nYear: $releaseYear")
                if (!statusStr.isNullOrBlank()) append("\nStatus: $statusStr")
                if (!trailerKey.isNullOrBlank()) {
                    append("\n\n[Trailer](https://www.youtube.com/watch?v=$trailerKey)")
                }
            }.trim()
            this.initialized = true
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
    fun toSEpisode(showId: Long, seasonNum: Int): SEpisode {
        val epNum = episode_number ?: 1
        val epName = name ?: "Episode $epNum"
        val stillUrl = if (!still_path.isNullOrBlank()) "$TMDB_IMAGE_BASE$still_path" else ""
        return SEpisode.create().apply {
            this.name = "S${seasonNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')} - $epName"
            this.episode_number = ((seasonNum - 1) * 100 + epNum).toFloat()
            this.date_upload = parseDate(air_date)
            this.url = "/watch/tv/$showId/$seasonNum/$epNum"
            this.scanlator = "Season $seasonNum"
            if (stillUrl.isNotBlank()) this.preview_url = stillUrl
            if (!overview.isNullOrBlank()) this.summary = overview
        }
    }
}

@Serializable
data class GenreDto(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class VideoResultsDto(
    val results: List<VideoItemDto>? = null,
)

@Serializable
data class VideoItemDto(
    val key: String? = null,
    val site: String? = null,
    val type: String? = null,
    val name: String? = null,
)

@Serializable
data class SheguDownloadsDto(
    val links: List<SheguLinkDto>? = null,
)

@Serializable
data class SheguLinkDto(
    val source: String? = null,
    val name: String? = null,
    val quality: Int? = null,
    val url: String? = null,
    val size: String? = null,
    val provider: String? = null,
)

@Serializable
data class SheguServersResponseDto(
    val servers: List<SheguServerItemDto>? = null,
)

@Serializable
data class SheguServerItemDto(
    val name: String? = null,
    val status: String? = null,
    val language: String? = null,
    val description: String? = null,
    val `4k`: Boolean? = null,
)

@Serializable
data class SubtitleDto(
    val file: String? = null,
    val url: String? = null,
    val label: String? = null,
    val display: String? = null,
    val language: String? = null,
)

package eu.kanade.tachiyomi.animeextension.en.cinejoy

import kotlinx.serialization.Serializable

@Serializable
data class TmdbMediaListDto(
    val page: Int? = null,
    val total_pages: Int? = null,
    val results: List<TmdbMediaDto>? = null,
)

@Serializable
data class TmdbMediaDto(
    val id: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val original_title: String? = null,
    val original_name: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val media_type: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null,
)

@Serializable
data class TmdbDetailsDto(
    val id: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val status: String? = null,
    val vote_average: Double? = null,
    val vote_count: Int? = null,
    val genres: List<TmdbGenreDto>? = null,
    val videos: TmdbVideosDto? = null,
    val seasons: List<TmdbSeasonDto>? = null,
)

@Serializable
data class TmdbGenreDto(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class TmdbVideosDto(
    val results: List<TmdbVideoDto>? = null,
)

@Serializable
data class TmdbVideoDto(
    val key: String? = null,
    val site: String? = null,
    val type: String? = null,
)

@Serializable
data class TmdbSeasonDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val episode_count: Int? = null,
    val poster_path: String? = null,
)

@Serializable
data class TmdbSeasonDetailsDto(
    val episodes: List<TmdbEpisodeDto>? = null,
)

@Serializable
data class TmdbEpisodeDto(
    val id: Long? = null,
    val episode_number: Int? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val still_path: String? = null,
    val vote_average: Double? = null,
)

@Serializable
data class SheguServersResponseDto(
    val servers: List<SheguServerDto>? = null,
)

@Serializable
data class SheguServerDto(
    val name: String? = null,
    val status: String? = null,
    val language: String? = null,
    val description: String? = null,
    val `4k`: Boolean? = null,
)

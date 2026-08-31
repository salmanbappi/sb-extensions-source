package eu.kanade.tachiyomi.animeextension.en.onetouchtv

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponseDto<T>(
    val success: Boolean? = null,
    val status: String? = null,
    val code: Int? = null,
    val result: T? = null,
)

@Serializable
data class ContentItemDto(
    val id: String? = null,
    val title: String? = null,
    val image: String? = null,
    val poster: String? = null,
    val country: String? = null,
    val type: String? = null,
    val year: String? = null,
    val status: String? = null,
    val isSub: Boolean? = null,
    val rating: String? = null,
    val popularity: Double? = null,
    val description: String? = null,
)

@Serializable
data class ContentDetailDto(
    val id: String? = null,
    val title: String? = null,
    val image: String? = null,
    val poster: String? = null,
    val country: String? = null,
    val type: String? = null,
    val otherTitles: List<String>? = null,
    val year: String? = null,
    val rating: String? = null,
    val popularity: Double? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    val description: String? = null,
    val status: String? = null,
    val releaseDate: String? = null,
    val aired_start: String? = null,
    val aired_end: String? = null,
    val isSub: Boolean? = null,
    val trailerUrl: String? = null,
    val director: String? = null,
    val screenwriter: String? = null,
    val actors: List<ActorDto>? = null,
    val episodes: List<EpisodeDto>? = null,
)

@Serializable
data class ActorDto(
    val id: String? = null,
    val name: String? = null,
    val image: String? = null,
)

@Serializable
data class EpisodeDto(
    val id: String? = null,
    val episode: String? = null,
    val rating: String? = null,
    val votes: String? = null,
    val identifier: String? = null,
    val playId: String? = null,
    val isSub: Boolean? = null,
    val released_at: String? = null,
)

@Serializable
data class EpisodeStreamDto(
    val sources: List<SourceDto>? = null,
    val track: List<TrackDto>? = null,
)

@Serializable
data class SourceDto(
    val type: String? = null,
    val contentId: String? = null,
    val id: String? = null,
    val name: String? = null,
    val quality: String? = null,
    val url: String? = null,
)

@Serializable
data class TrackDto(
    val file: String? = null,
    val kind: String? = null,
    val name: String? = null,
    val code: String? = null,
    val format: String? = null,
    val default: Boolean? = null,
)

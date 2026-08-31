package eu.kanade.tachiyomi.animeextension.en.onetouchtv

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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
    val rating: JsonElement? = null,
    val popularity: JsonElement? = null,
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
    val rating: JsonElement? = null,
    val popularity: JsonElement? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
    val description: String? = null,
    val status: String? = null,
    val releaseDate: String? = null,
    val aired_start: String? = null,
    val aired_end: String? = null,
    val isSub: Boolean? = null,
    val trailerUrl: String? = null,
    val director: JsonElement? = null,
    val screenwriter: JsonElement? = null,
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
    val episode: JsonElement? = null,
    val rating: JsonElement? = null,
    val votes: JsonElement? = null,
    val identifier: String? = null,
    val playId: JsonElement? = null,
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
    val headers: Map<String, String>? = null,
)

@Serializable
data class TrackDto(
    val file: String? = null,
    val kind: String? = null,
    val default: Boolean? = null,
    val name: String? = null,
    val sourceFormat: String? = null,
    val format: String? = null,
    val code: String? = null,
)

fun JsonElement?.asStringOrNull(): String? = when (this) {
    is JsonPrimitive -> content.takeIf { it.isNotBlank() }
    else -> null
}

fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
    is JsonPrimitive -> listOf(content).filter { it.isNotBlank() }
    else -> emptyList()
}

package eu.kanade.tachiyomi.animeextension.en.twodhive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResponseDto(
    val results: List<SearchAnimeDto>? = null,
)

@Serializable
data class SearchAnimeDto(
    val id: Int? = null,
    val title: String? = null,
    val englishTitle: String? = null,
    val imageUrl: String? = null,
    val coverImageUrl: String? = null,
    val smallImageUrl: String? = null,
    val score: Double? = null,
    val type: String? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val year: Int? = null,
)

@Serializable
data class EpisodeTrackerDto(
    val episodes: Int? = null,
    val nextAiring: NextAiringDto? = null,
)

@Serializable
data class NextAiringDto(
    val airingAt: Long? = null,
    val episode: Int? = null,
)

@Serializable
data class BabaConfigDto(
    val mal: String? = null,
    val ep: String? = null,
    val sub: String? = null,
    val sid: String? = null,
    val pk: String? = null,
)

@Serializable
data class BabaResolveResponseDto(
    val d: String? = null,
)

@Serializable
data class BabaDecryptedPayloadDto(
    val t: String? = null,
    val u: String? = null,
    val m: String? = null,
)

@Serializable
data class MegaPlaySourcesDto(
    val sources: MegaPlayFileDto? = null,
    val tracks: List<MegaPlayTrackDto>? = null,
)

@Serializable
data class MegaPlayFileDto(
    val file: String? = null,
)

@Serializable
data class MegaPlayTrackDto(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = false,
)

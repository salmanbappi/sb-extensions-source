package eu.kanade.tachiyomi.animeextension.en.twodhive

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
    val cap: String? = null,
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
    val tracks: List<BabaTrackDto>? = null,
)

@Serializable
data class BabaTrackDto(
    val label: String? = null,
    val u: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class BabaVerifyResponseDto(
    val t: String? = null,
    val m: String? = null,
)

@Serializable
data class CapChallengeResponseDto(
    val challenge: CapChallengeDto? = null,
    val token: String? = null,
    val expires: Long? = null,
)

@Serializable
data class CapChallengeDto(
    val c: Int? = null,
    val s: Int? = null,
    val d: Int? = null,
)

@Serializable
data class CapRedeemRequestDto(
    val token: String? = null,
    val solutions: List<Int>? = null,
)

@Serializable
data class CapRedeemResponseDto(
    val success: Boolean? = null,
    val token: String? = null,
    val expires: Long? = null,
    val error: String? = null,
)

@Serializable
data class MegaPlaySourcesDto(
    val sources: MegaPlayFileDto? = null,
    val tracks: List<MegaPlayTrackDto>? = null,
    val enc: String? = null,
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

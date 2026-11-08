package eu.kanade.tachiyomi.animeextension.en.gotaku

import eu.kanade.tachiyomi.animesource.model.SAnime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ======================== Filters (/api/v1/filters) =======================
@Serializable
data class FiltersResponseDto(val data: FiltersDataDto = FiltersDataDto())

@Serializable
data class FiltersDataDto(
    val genres: List<IdNameDto> = emptyList(),
    val themes: List<IdNameDto> = emptyList(),
    val types: List<ValueLabelDto> = emptyList(),
    val statuses: List<ValueLabelDto> = emptyList(),
    val seasons: List<ValueLabelDto> = emptyList(),
    val ratings: List<ValueLabelDto> = emptyList(),
    val years: List<ValueLabelDto> = emptyList(),
    val countries: List<ValueLabelDto> = emptyList(),
)

@Serializable
data class IdNameDto(val id: Int = 0, val name: String = "")

@Serializable
data class ValueLabelDto(val value: String = "", val label: String = "")

// ========================== Browse (/api/v1/titles) =======================
@Serializable
data class TitlesResponseDto(
    val data: List<TitleDto> = emptyList(),
    val meta: MetaDto? = null,
)

@Serializable
data class MetaDto(
    val page: Int = 1,
    val limit: Int = 28,
    @SerialName("has_more") val hasMore: Boolean = false,
    val total: Int = 0,
)

@Serializable
data class TitleDto(
    val id: String = "",
    val name: String = "",
    @SerialName("poster_url") val posterUrl: String? = null,
    val format: String? = null,
    @SerialName("is_adult") val isAdult: Boolean? = null,
    val episodes: EpisodesInfoDto? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = this@TitleDto.id
        title = this@TitleDto.name
        thumbnail_url = posterUrl?.takeIf { it.isNotBlank() }
    }
}

@Serializable
data class EpisodesInfoDto(
    @SerialName("latest_sub") val latestSub: Int? = null,
    @SerialName("latest_dub") val latestDub: Int? = null,
    val total: Int? = null,
)

// ===================== Details (/api/v1/titles/{id}) ======================
@Serializable
data class DetailsResponseDto(val data: DetailsDataDto = DetailsDataDto())

@Serializable
data class SeasonDto(
    val id: String = "",
    val name: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val url: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("is_current") val isCurrent: Boolean? = null,
)

@Serializable
data class DetailsDataDto(
    val title: TitleDetailDto = TitleDetailDto(),
    val relations: List<RelatedTitleDto> = emptyList(),
    val recommendations: List<RelatedTitleDto> = emptyList(),
    val seasons: List<SeasonDto> = emptyList(),
)

@Serializable
data class TitleDetailDto(
    val id: String = "",
    val name: String = "",
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    val synopsis: String? = null,
    val format: String? = null,
    @SerialName("age_rating") val ageRating: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    @SerialName("mal_score") val malScore: Double? = null,
    @SerialName("is_adult") val isAdult: Boolean? = null,
    val episodes: EpisodesInfoDto? = null,
    @SerialName("alt_names") val altNames: List<String> = emptyList(),
    val status: String? = null,
    val season: String? = null,
    val aired: String? = null,
    val ended: String? = null,
    val source: String? = null,
    val country: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int? = null,
    @SerialName("ratings_avg") val ratingsAvg: Double? = null,
    val links: Map<String, String?> = emptyMap(),
    @SerialName("anilist_id") val anilistId: Int? = null,
    val genres: List<IdNameDto> = emptyList(),
    val themes: List<IdNameDto> = emptyList(),
    val studios: List<IdNameDto> = emptyList(),
    val producers: List<IdNameDto> = emptyList(),
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = id
        title = name
        thumbnail_url = posterUrl?.takeIf { it.isNotBlank() }
        status = parseStatus(this@TitleDetailDto.status)
        background_url = (backdropUrl ?: bannerUrl)?.takeIf { it.isNotBlank() }
        genre = (genres + themes).map { it.name }.filter { it.isNotBlank() }
            .joinToString(", ").takeIf { it.isNotBlank() }
        author = studios.mapNotNull { it.name.takeIf(String::isNotBlank) }
            .joinToString(", ").takeIf { it.isNotBlank() }
    }
}

private fun parseStatus(status: String?): Int = when (status?.lowercase()) {
    "finished", "completed" -> SAnime.COMPLETED
    "releasing", "airing" -> SAnime.ONGOING
    "not_yet_released", "unreleased" -> SAnime.ONGOING
    "cancelled" -> SAnime.CANCELLED
    "hiatus", "on_hiatus" -> SAnime.ON_HIATUS
    else -> SAnime.UNKNOWN
}

@Serializable
data class RelatedTitleDto(
    val id: String? = null,
    val name: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val format: String? = null,
    val relation: String? = null,
)

// ======================= Decrypted (?k=N) payloads ========================
@Serializable
data class GotakuEnvelope<T>(val data: T? = null)

@Serializable
data class AudioFlagsDto(
    @SerialName("hard_sub") val hardSub: Boolean = false,
    @SerialName("soft_sub") val softSub: Boolean = false,
    val dub: Boolean = false,
)

@Serializable
data class EmbedDto(
    val url: String? = null,
    val skip: SkipDto? = null,
)

@Serializable
data class SkipDto(
    val intro: List<Double>? = null,
    val outro: List<Double>? = null,
)

@Serializable
data class EpisodeDto(
    val id: String = "",
    val number: Double? = null,
    val label: String? = null,
    val name: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("aired_at") val airedAt: String? = null,
    @SerialName("is_filler") val isFiller: Boolean = false,
    val sub: Boolean = false,
    val dub: Boolean = false,
)

// =============== Manifest (decrypted via ManifestHandshake) ===============
@Serializable
data class ManifestDto(
    val source: String? = null,
    @SerialName("keySeed") val keySeed: String? = null,
    val token: String? = null,
    @SerialName("expiresAt") val expiresAt: Long? = null,
    val obf: ObfDto? = null,
    val subtitles: List<ManifestSubtitleDto>? = null,
)

@Serializable
data class ObfDto(
    @SerialName("segmentBytes") val segmentBytes: Int = 0,
    val version: Int = 1,
)

@Serializable
data class ManifestSubtitleDto(
    val url: String? = null,
    val language: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val name: String? = null,
)

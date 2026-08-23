package eu.kanade.tachiyomi.animeextension.all.sankanime

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────── Root wrapper ───────────────────────────────────
// Real shape: { data: { data: { movie: [...], totalPage: N } } }
@Serializable
data class ApiResponseDto<T>(
    val data: T? = null,
    val success: Boolean? = null,
    val message: String? = null,
)

@Serializable
data class DataWrapperDto<T>(
    val data: T? = null,
)

// ─────────────────────────── Anime list ─────────────────────────────────────
// Used by /popular, /new, /hot, /foryou, /search, /genre, /year, /type
@Serializable
data class AnimeListDataDto(
    val movie: List<AnimeItemDto>? = null,
    @SerialName("totalPage") val totalPage: Int? = null,
    @SerialName("total_page") val totalPageAlt: Int? = null,
    val total: Int? = null,
)

fun AnimeListDataDto.getTotalPages(): Int = totalPage ?: totalPageAlt ?: 1

@Serializable
data class AnimeItemDto(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
    val thumbnail: String? = null,
    val cover: String? = null,
    val image: String? = null,
    val status: String? = null,
    val type: String? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val score: String? = null,
    val year: String? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        val animeSlug = this@AnimeItemDto.slug ?: this@AnimeItemDto.id ?: ""
        url = animeSlug
        title = this@AnimeItemDto.title ?: this@AnimeItemDto.japaneseTitle ?: "Unknown"
        thumbnail_url = this@AnimeItemDto.thumbnail
            ?: this@AnimeItemDto.cover
            ?: this@AnimeItemDto.image
        description = this@AnimeItemDto.synopsis ?: this@AnimeItemDto.description
        status = when (this@AnimeItemDto.status?.lowercase()) {
            "ongoing", "airing", "currently airing" -> SAnime.ONGOING
            "completed", "finished", "finished airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        genre = this@AnimeItemDto.genre?.joinToString(", ")
        fetch_type = FetchType.Episodes
    }
}

// ─────────────────────────── Anime detail ───────────────────────────────────
// Used by /detail/{slug} — returns movie object with episode[] array embedded
@Serializable
data class AnimeDetailDataDto(
    val movie: AnimeDetailDto? = null,
)

@Serializable
data class AnimeDetailDto(
    val id: String? = null,
    val slug: String? = null,
    val title: String? = null,
    @SerialName("japanese_title") val japaneseTitle: String? = null,
    val thumbnail: String? = null,
    val cover: String? = null,
    val image: String? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val status: String? = null,
    val type: String? = null,
    val genre: List<String>? = null,
    val studio: List<String>? = null,
    val score: String? = null,
    val year: String? = null,
    val season: String? = null,
    val episode: List<EpisodeItemDto>? = null,
) {
    fun toSAnime(fallback: SAnime? = null): SAnime = SAnime.create().apply {
        val animeSlug = this@AnimeDetailDto.slug ?: this@AnimeDetailDto.id ?: fallback?.url ?: ""
        url = animeSlug
        title = this@AnimeDetailDto.title ?: this@AnimeDetailDto.japaneseTitle ?: fallback?.title ?: "Unknown"
        thumbnail_url = this@AnimeDetailDto.thumbnail
            ?: this@AnimeDetailDto.cover
            ?: this@AnimeDetailDto.image
            ?: fallback?.thumbnail_url

        val desc = this@AnimeDetailDto.synopsis ?: this@AnimeDetailDto.description
        val descBuilder = StringBuilder()
        if (!desc.isNullOrBlank()) descBuilder.append(desc).append("\n\n")
        this@AnimeDetailDto.japaneseTitle?.let { descBuilder.append("Japanese: $it\n") }
        this@AnimeDetailDto.year?.let { descBuilder.append("Year: $it\n") }
        this@AnimeDetailDto.season?.let { descBuilder.append("Season: $it\n") }
        this@AnimeDetailDto.score?.let { descBuilder.append("Score: $it\n") }
        description = descBuilder.toString().trim().ifEmpty { fallback?.description }

        genre = (this@AnimeDetailDto.genre)?.joinToString(", ") ?: fallback?.genre
        author = this@AnimeDetailDto.studio?.joinToString(", ")

        status = when (this@AnimeDetailDto.status?.lowercase()) {
            "ongoing", "airing", "currently airing" -> SAnime.ONGOING
            "completed", "finished", "finished airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
        initialized = true
    }
}

// ─────────────────────────── Episode ────────────────────────────────────────
// Embedded in AnimeDetailDto.episode[] — fields: index, id, title
@Serializable
data class EpisodeItemDto(
    val id: String? = null,
    val index: Int? = null,
    @SerialName("episode_no") val episodeNo: Int? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val image: String? = null,
    val synopsis: String? = null,
    val overview: String? = null,
    val filler: Boolean? = null,
) {
    fun toSEpisode(animeSlug: String): SEpisode = SEpisode.create().apply {
        val epNum = this@EpisodeItemDto.index ?: this@EpisodeItemDto.episodeNo ?: 1
        val streamId = this@EpisodeItemDto.id ?: ""
        episode_number = epNum.toFloat()
        // url = "animeSlug#streamId" so we can call /stream/{id} directly
        url = "$animeSlug#$streamId"

        val titleText = this@EpisodeItemDto.title
        val fillerTag = if (this@EpisodeItemDto.filler == true) " [Filler]" else ""
        name = if (!titleText.isNullOrBlank() && !titleText.equals("Episode $epNum", ignoreCase = true)) {
            "Episode $epNum: $titleText$fillerTag"
        } else {
            "Episode $epNum$fillerTag"
        }
        (this@EpisodeItemDto.thumbnail ?: this@EpisodeItemDto.image)?.let { preview_url = it }
        (this@EpisodeItemDto.synopsis ?: this@EpisodeItemDto.overview)?.let { summary = it }
    }
}

// ─────────────────────────── Stream ─────────────────────────────────────────
// /stream/{id} → response.data.data = StreamResultDto
@Serializable
data class StreamResultDto(
    val title: String? = null,
    val link: String? = null,
    val url: String? = null,
    val file: String? = null,
    val iframe: String? = null,
    val server: String? = null,
    val type: String? = null,
    val tracks: List<SubtitleTrackDto>? = null,
    val sources: List<StreamSourceDto>? = null,
    val subtitle: List<SubtitleTrackDto>? = null,
) {
    fun getStreamUrl(): String? = link ?: url ?: file
        ?: sources?.firstOrNull()?.file
        ?: sources?.firstOrNull()?.url
}

@Serializable
data class StreamSourceDto(
    val file: String? = null,
    val url: String? = null,
    val label: String? = null,
    val type: String? = null,
)

@Serializable
data class SubtitleTrackDto(
    val file: String? = null,
    @SerialName("url") val trackUrl: String? = null,
    val label: String? = null,
    val kind: String? = null,
    @SerialName("default") val isDefault: Boolean? = null,
) {
    fun resolveUrl(): String? = file ?: trackUrl
}

package eu.kanade.tachiyomi.animeextension.all.sankanime

import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponseDto<T>(
    val success: Boolean? = null,
    val results: T? = null,
)

@Serializable
data class AnimeListResultDto(
    val totalPages: Int? = null,
    val data: List<AnimeItemDto>? = null,
)

@Serializable
data class AnimeItemDto(
    val id: String? = null,
    val data_id: String? = null,
    val number: String? = null,
    val poster: String? = null,
    val title: String? = null,
    val japanese_title: String? = null,
    val description: String? = null,
    val tvInfo: TvInfoDto? = null,
    val adultContent: Boolean? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        val animeId = this@AnimeItemDto.id ?: this@AnimeItemDto.data_id ?: ""
        url = animeId
        title = this@AnimeItemDto.title ?: this@AnimeItemDto.japanese_title ?: "Unknown"
        thumbnail_url = this@AnimeItemDto.poster
        description = this@AnimeItemDto.description
        status = SAnime.UNKNOWN
        fetch_type = FetchType.Episodes
    }
}

@Serializable
data class TvInfoDto(
    val showType: String? = null,
    val eps: String? = null,
    val sub: String? = null,
    val dub: String? = null,
    val quality: String? = null,
    val releaseDate: String? = null,
)

@Serializable
data class AnimeInfoResultDto(
    val data: AnimeDetailDto? = null,
)

@Serializable
data class AnimeDetailDto(
    val id: String? = null,
    val data_id: String? = null,
    val title: String? = null,
    val japanese_title: String? = null,
    val titles: TitlesDto? = null,
    val poster: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val status: String? = null,
    val releaseDate: String? = null,
    val studios: List<String>? = null,
    val showType: String? = null,
    val rating: String? = null,
    val adultContent: Boolean? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        url = this@AnimeDetailDto.id ?: this@AnimeDetailDto.data_id ?: ""
        title = this@AnimeDetailDto.title ?: this@AnimeDetailDto.japanese_title ?: "Unknown"
        thumbnail_url = this@AnimeDetailDto.poster

        val descBuilder = StringBuilder()
        this@AnimeDetailDto.description?.let { descBuilder.append(it).append("\n\n") }
        this@AnimeDetailDto.japanese_title?.let { descBuilder.append("Japanese: ").append(it).append("\n") }
        this@AnimeDetailDto.titles?.en?.let { descBuilder.append("English: ").append(it).append("\n") }
        this@AnimeDetailDto.showType?.let { descBuilder.append("Type: ").append(it).append("\n") }
        this@AnimeDetailDto.releaseDate?.let { descBuilder.append("Release: ").append(it).append("\n") }
        this@AnimeDetailDto.rating?.let { descBuilder.append("Rating: ").append(it).append("\n") }

        description = descBuilder.toString().trim()
        genre = this@AnimeDetailDto.genres?.joinToString(", ")
        author = this@AnimeDetailDto.studios?.joinToString(", ")

        status = when (this@AnimeDetailDto.status?.lowercase()) {
            "releasing", "ongoing", "airing" -> SAnime.ONGOING
            "completed", "finished" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
        initialized = true
    }
}

@Serializable
data class TitlesDto(
    @SerialName("x-jat")
    val xJat: String? = null,
    val en: String? = null,
    val ja: String? = null,
)

@Serializable
data class EpisodeListResultDto(
    val totalEpisodes: Int? = null,
    val episodes: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val episode_no: Int? = null,
    val id: String? = null,
    val title: String? = null,
    val japanese_title: String? = null,
    val filler: Boolean? = null,
) {
    fun toSEpisode(animeId: String): SEpisode = SEpisode.create().apply {
        val epNum = this@EpisodeItemDto.episode_no ?: 1
        episode_number = epNum.toFloat()
        url = "$animeId#ep=$epNum"

        val titleText = this@EpisodeItemDto.title
        val fillerTag = if (this@EpisodeItemDto.filler == true) " [Filler]" else ""
        name = if (!titleText.isNullOrBlank() && !titleText.equals("Episode $epNum", ignoreCase = true)) {
            "Episode $epNum: $titleText$fillerTag"
        } else {
            "Episode $epNum$fillerTag"
        }
    }
}

@Serializable
data class ServerItemDto(
    val type: String? = null,
    val data_id: String? = null,
    val server_id: String? = null,
    val serverName: String? = null,
)

@Serializable
data class StreamResultDto(
    val title: String? = null,
    val japanese_title: String? = null,
    val streamingLink: StreamingLinkDto? = null,
    val iframe: String? = null,
    val server: String? = null,
)

@Serializable
data class StreamingLinkDto(
    val id: String? = null,
    val type: String? = null,
    val link: LinkFileDto? = null,
    val tracks: List<SubtitleTrackDto>? = null,
    val intro: SkipIntervalDto? = null,
    val outro: SkipIntervalDto? = null,
    val server: String? = null,
    val iframe: String? = null,
)

@Serializable
data class LinkFileDto(
    val file: String? = null,
    val type: String? = null,
)

@Serializable
data class SubtitleTrackDto(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class SkipIntervalDto(
    val start: Int? = null,
    val end: Int? = null,
)

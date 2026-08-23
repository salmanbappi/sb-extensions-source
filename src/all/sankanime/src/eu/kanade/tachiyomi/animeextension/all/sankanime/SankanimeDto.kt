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
    val rating: String? = null,
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
    val animeInfo: AnimeInfoDto? = null,
) {
    fun toSAnime(fallbackAnime: SAnime? = null): SAnime = SAnime.create().apply {
        url = this@AnimeDetailDto.id ?: this@AnimeDetailDto.data_id ?: fallbackAnime?.url ?: ""
        title = this@AnimeDetailDto.title ?: this@AnimeDetailDto.japanese_title ?: fallbackAnime?.title ?: "Unknown"
        thumbnail_url = this@AnimeDetailDto.poster ?: fallbackAnime?.thumbnail_url

        val overviewText = this@AnimeDetailDto.animeInfo?.overview
            ?: this@AnimeDetailDto.description
            ?: fallbackAnime?.description

        val descBuilder = StringBuilder()
        if (!overviewText.isNullOrBlank()) {
            descBuilder.append(overviewText).append("\n\n")
        }
        this@AnimeDetailDto.japanese_title?.let { descBuilder.append("Japanese: ").append(it).append("\n") }
        this@AnimeDetailDto.titles?.en?.let { descBuilder.append("English: ").append(it).append("\n") }
        (this@AnimeDetailDto.animeInfo?.premiered ?: this@AnimeDetailDto.releaseDate)?.let {
            descBuilder.append("Premiered: ").append(it).append("\n")
        }
        this@AnimeDetailDto.animeInfo?.aired?.let {
            descBuilder.append("Aired: ").append(it).append("\n")
        }
        this@AnimeDetailDto.animeInfo?.duration?.let {
            descBuilder.append("Duration: ").append(it).append("\n")
        }
        (this@AnimeDetailDto.animeInfo?.tvInfo?.rating ?: this@AnimeDetailDto.rating)?.let {
            descBuilder.append("Rating: ").append(it).append("\n")
        }

        description = descBuilder.toString().trim()
        genre = (this@AnimeDetailDto.animeInfo?.genres ?: this@AnimeDetailDto.genres)?.joinToString(", ")
        author = (this@AnimeDetailDto.animeInfo?.studios ?: this@AnimeDetailDto.studios)?.joinToString(", ")

        val rawStatus = this@AnimeDetailDto.animeInfo?.status ?: this@AnimeDetailDto.status
        status = when (rawStatus?.lowercase()) {
            "currently airing", "releasing", "ongoing", "airing" -> SAnime.ONGOING
            "finished airing", "completed", "finished" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
        initialized = true
    }
}

@Serializable
data class AnimeInfoDto(
    @SerialName("Genres") val genres: List<String>? = null,
    @SerialName("Producers") val producers: List<String>? = null,
    @SerialName("Studios") val studios: List<String>? = null,
    @SerialName("Aired") val aired: String? = null,
    @SerialName("Premiered") val premiered: String? = null,
    @SerialName("Duration") val duration: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("bannerImage") val bannerImage: String? = null,
    @SerialName("tvInfo") val tvInfo: TvInfoDto? = null,
)

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
    val overview: String? = null,
    val image: String? = null,
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
        this@EpisodeItemDto.image?.let { preview_url = it }
        this@EpisodeItemDto.overview?.let { summary = it }
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

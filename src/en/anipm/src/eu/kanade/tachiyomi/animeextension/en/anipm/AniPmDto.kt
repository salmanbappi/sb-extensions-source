package eu.kanade.tachiyomi.animeextension.en.anipm

import eu.kanade.tachiyomi.animesource.model.SAnime
import extensions.utils.UrlUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class AnimeCatalogResponseDto(
    val items: List<AnimeItemDto>? = null,
    val hasNextPage: Boolean? = null,
)

@Serializable
data class AnimeListResponseDto(
    val items: List<AnimeItemDto>? = null,
    val hasNextPage: Boolean? = null,
)

@Serializable
data class AnimeItemDto(
    val id: JsonPrimitive? = null,
    val source: String? = null,
    val title: String? = null,
    val native: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val synopsis: String? = null,
    val studios: List<String>? = null,
) {
    fun toSAnime(baseUrl: String): SAnime = SAnime.create().apply {
        val src = source?.lowercase() ?: "settlar"
        val sid = id?.content ?: ""
        url = if (src == "anilist") "/ani/$sid" else "/anime/$sid"
        title = this@AnimeItemDto.title ?: ""
        thumbnail_url = poster?.let { UrlUtils.fixUrl(it, baseUrl) }
        background_url = banner?.let { UrlUtils.fixUrl(it, baseUrl) }
        description = synopsis
        genre = genres?.joinToString(", ")
        status = when (this@AnimeItemDto.status?.lowercase()) {
            "currently airing", "airing", "ongoing" -> SAnime.ONGOING
            "finished airing", "finished", "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        author = studios?.firstOrNull()
    }
}

@Serializable
data class AnimeDetailDto(
    val id: JsonPrimitive? = null,
    val source: String? = null,
    val title: String? = null,
    val native: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val status: String? = null,
    val genres: List<String>? = null,
    val studios: List<String>? = null,
    val synopsis: String? = null,
    val episodes: List<EpisodeItemDto>? = null,
) {
    fun toSAnime(baseUrl: String): SAnime = SAnime.create().apply {
        val src = source?.lowercase() ?: "settlar"
        val sid = id?.content ?: ""
        url = if (src == "anilist") "/ani/$sid" else "/anime/$sid"
        title = this@AnimeDetailDto.title ?: ""
        thumbnail_url = poster?.let { UrlUtils.fixUrl(it, baseUrl) }
        background_url = banner?.let { UrlUtils.fixUrl(it, baseUrl) }
        description = synopsis
        genre = genres?.joinToString(", ")
        status = when (this@AnimeDetailDto.status?.lowercase()) {
            "currently airing", "airing", "ongoing" -> SAnime.ONGOING
            "finished airing", "finished", "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        author = studios?.firstOrNull()
    }
}

@Serializable
data class EpisodeItemDto(
    val number: Float? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val sub: Boolean? = null,
    val dub: Boolean? = null,
    val aired: String? = null,
)

@Serializable
data class PlaybackBootstrapDto(
    val settlarSelection: String? = null,
)

@Serializable
data class SettlarSessionDto(
    val embedUrl: String? = null,
    val expiresAt: Long? = null,
    val provider: String? = null,
)

@Serializable
data class EmbedPlayerSessionDto(
    val source: String? = null,
    val subtitles: List<SubtitleTrackDto>? = null,
    val keyProof: String? = null,
)

@Serializable
data class SubtitleTrackDto(
    val url: String? = null,
    val label: String? = null,
    val srclang: String? = null,
    val default: Boolean? = null,
)

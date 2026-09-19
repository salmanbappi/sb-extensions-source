package eu.kanade.tachiyomi.animeextension.en.animepahe.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponseDto<T>(
    @SerialName("current_page")
    val currentPage: Int = 0,
    @SerialName("last_page")
    val lastPage: Int = 0,
    @EncodeDefault
    @SerialName("data")
    val items: List<T> = emptyList(),
)

@Serializable
data class LatestAnimeDto(
    @SerialName("anime_id") val id: Int = 0,
    @SerialName("anime_session") val session: String = "",
    @SerialName("anime_title") val title: String = "",
    val snapshot: String = "",
    val fansub: String? = null,
)

@Serializable
data class SearchResultDto(
    val id: Int = 0,
    val title: String = "",
    val poster: String = "",
    val session: String = "",
)

@Serializable
data class EpisodeDto(
    @SerialName("created_at") val createdAt: String = "",
    val session: String = "",
    @SerialName("episode") val episodeNumber: Float = 0f,
    @SerialName("anime_id") val animeId: Int = 0,
)

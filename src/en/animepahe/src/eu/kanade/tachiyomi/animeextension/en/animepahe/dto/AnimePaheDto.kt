package eu.kanade.tachiyomi.animeextension.en.animepahe.dto

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponseDto<T>(
    @SerialName("current_page")
    val currentPage: Int? = null,
    @SerialName("last_page")
    val lastPage: Int? = null,
    @EncodeDefault
    @SerialName("data")
    val items: List<T> = emptyList(),
)

@Serializable
data class LatestAnimeDto(
    @SerialName("anime_title")
    val title: String? = null,
    val snapshot: String? = null,
    @SerialName("anime_id")
    val id: Int? = null,
    val session: String? = null,
    val fansub: String? = null,
)

@Serializable
data class SearchResultDto(
    val title: String? = null,
    val poster: String? = null,
    val id: Int? = null,
    val session: String? = null,
)

@Serializable
data class EpisodeDto(
    @SerialName("created_at")
    val createdAt: String? = null,
    val session: String? = null,
    @SerialName("episode")
    val episodeNumber: Float? = null,
    @SerialName("anime_id")
    val animeId: Int = 0,
)

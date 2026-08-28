package eu.kanade.tachiyomi.animeextension.en.subdubanime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BlakiteResponse(
    val success: Boolean? = null,
    val data: BlakiteData? = null,
)

@Serializable
data class BlakiteData(
    val movies: Map<String, AnimeEntry> = emptyMap(),
    val series: Map<String, AnimeEntry> = emptyMap(),
    val dramas: Map<String, AnimeEntry> = emptyMap(),
)

@Serializable
data class AnimeEntry(
    val tmdbId: String? = null,
    val originalTmdbId: String? = null,
    val title: String? = null,
    val language: String? = null,
    val type: String? = null,
    val status: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    @SerialName("TMDB_DATA") val tmdbData: TmdbData? = null,
    @SerialName("IMAGES") val images: Images? = null,
    val seasons: Map<String, SeasonInfo>? = null,
)

@Serializable
data class TmdbData(
    val genres: List<String>? = null,
    val synopsis: String? = null,
    val overview: String? = null,
    val rating: String? = null,
    val releaseDate: String? = null,
    val keywords: List<String>? = null,
    val trailer: String? = null,
)

@Serializable
data class Images(
    val poster: String? = null,
    val backdrop: String? = null,
)

@Serializable
data class SeasonInfo(
    val seasonNumber: Int? = null,
    val status: String? = null,
    val totalEpisodes: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

// ============================== Stream API DTOs ==============================

@Serializable
data class StreamResponse(
    val success: Boolean? = null,
    val data: StreamData? = null,
    val error: String? = null,
)

@Serializable
data class StreamData(
    val animeTitle: String? = null,
    val tmdbId: String? = null,
    val type: String? = null,
    val language: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String? = null,
    val dataId: String? = null,
    val qid: Int? = null,
    val quality: String? = null,
    val format: String? = null,
    val ranges: String? = null,
    val poster: String? = null,
)

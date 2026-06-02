package eu.kanade.tachiyomi.animeextension.all.fmftp.dto

import kotlinx.serialization.Serializable

@Serializable
data class FmFtpResponse(
    val total: Int? = null,
    val pages: Int? = null,
    val current_page: Int? = null,
    val limit: Int? = null,
    val data: List<FmFtpContent>? = null,
)

@Serializable
data class FmFtpContent(
    val id: Int,
    val title: String,
    val original_title: String? = null,
    val year: Int? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val genre: String? = null,
    val overview: String? = null,
    val views: Int? = null,
    val online_rating: Double? = null,
    val Library: FmFtpLibrary? = null,
    val episodes: List<FmFtpEpisode>? = null,
)

@Serializable
data class FmFtpLibrary(
    val id: Int,
    val name: String,
    val type: String,
)

@Serializable
data class FmFtpEpisode(
    val id: Int,
    val name: String? = null,
    val season_number: Int? = null,
    val episode_number: Int? = null,
    val runtime: Int? = null,
    val still_path: String? = null,
    val overview: String? = null,
)

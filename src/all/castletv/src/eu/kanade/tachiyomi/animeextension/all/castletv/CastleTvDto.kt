package eu.kanade.tachiyomi.animeextension.all.castletv

import kotlinx.serialization.Serializable

@Serializable
data class CastleApiResponse(
    val code: Int,
    val msg: String,
    val data: String? = null
)

@Serializable
data class SecurityKeyResponse(
    val code: Int,
    val msg: String,
    val data: String
)

@Serializable
data class DecryptedResponse(
    val code: Int,
    val msg: String,
    val data: HomePageData
)

@Serializable
data class HomePageData(
    val page: Int? = null,
    val pages: Int? = null,
    val size: Int? = null,
    val total: Int? = null,
    val rows: List<HomePageRow>? = null
)

@Serializable
data class HomePageRow(
    val id: Long? = null,
    val name: String? = null,
    val coverImage: String? = null,
    val coverImageHeight: Int? = null,
    val coverImageWidth: Int? = null,
    val type: Int? = null,
    val redirectType: Int? = null,
    val briefIntroduction: String? = null,
    val contents: List<ContentItem>? = null
)

@Serializable
data class ContentItem(
    val title: String? = null,
    val coverImage: String? = null,
    val redirectType: Int? = null,
    val redirectId: Long? = null,
    val movieType: Int? = null,
    val score: Double? = null,
    val publishTime: Long? = null,
    val heat: Int? = null,
    val order: Int? = null,
    val unlockPlayback: Boolean? = null,
    val languages: List<String>? = null,
    val excludeChannelIds: List<String>? = null,
    val memberLevel: Int? = null,
    val standardExpireTime: Long? = null,
    val indiaResolutionLabel: String? = null,
    val standardNewExpireTime: Long? = null,
    val countdownHourNew: Int? = null,
    val countdownHour: Int? = null,
    val serverTime: Long? = null
)

@Serializable
data class MovieDetailsResponse(
    val code: Int,
    val msg: String,
    val data: MovieDetails
)

@Serializable
data class MovieDetails(
    val id: Long? = null,
    val title: String? = null,
    val score: Double? = null,
    val movieType: Int? = null,
    val movieTypeName: String? = null,
    val coverHorizontalImage: String? = null,
    val coverVerticalImage: String? = null,
    val unlockPlayback: Boolean? = null,
    val seasonDescription: String? = null,
    val languages: List<String>? = null,
    val lastEpisodeCount: Int? = null,
    val serverTime: Long? = null,
    val totalNumber: Int? = null,
    val woolUser: Boolean? = null,
    val briefIntroduction: String? = null,
    val publishTime: Long? = null,
    val tags: List<String>? = null,
    val countries: List<String>? = null,
    val isAuthorized: Boolean? = null,
    val originalTitle: String? = null,
    val directors: List<Person>? = null,
    val actors: List<Person>? = null,
    val episodes: List<ApiEpisode>? = null,
    val seasonNumber: Int? = null,
    val updateNumber: Int? = null,
    val watchCount: Long? = null,
    val commentTotal: Int? = null,
    val previewTime: Int? = null,
    val seasons: List<Season>? = null,
    val audioTags: List<String>? = null,
    val countryIds: List<Long>? = null,
    val tagIds: List<Long>? = null,
    val resolution: Int? = null,
    val indiaResolutionLabel: String? = null,
    val titbits: List<Titbit>? = null
)

@Serializable
data class Person(
    val id: Long? = null,
    val name: String? = null,
    val avatar: String? = null
)

@Serializable
data class ApiEpisode(
    val id: Long? = null,
    val title: String? = null,
    val number: Int? = null,
    val coverImage: String? = null,
    val duration: Int? = null,
    val videos: List<VideoQuality>? = null,
    val playResolution: Int? = null,
    val mobileTrafficPlayResolution: Int? = null,
    val tracks: List<Track>? = null,
    val onlineTime: Long? = null
)

@Serializable
data class VideoQuality(
    val resolution: Int? = null,
    val resolutionDescription: String? = null,
    val size: Long? = null,
    val premiumProPermission: Boolean? = null
)

@Serializable
data class Track(
    val languageId: Int? = null,
    val languageName: String? = null,
    val abbreviate: String? = null,
    val isDefault: Boolean? = null,
    val existIndividualVideo: Boolean? = null,
    val order: Int? = null,
    val index: Int? = null
)

@Serializable
data class Season(
    val movieId: Long? = null,
    val number: Int? = null,
    val description: String? = null,
    val isCurrent: Boolean? = null
)

@Serializable
data class Titbit(
    val id: String? = null,
    val name: String? = null,
    val videoCategory: Int? = null,
    val coverImage: String? = null
)

@Serializable
data class SearchApiResponse(
    val code: Int,
    val msg: String,
    val data: SearchData
)

@Serializable
data class SearchData(
    val page: Int? = null,
    val pages: Int? = null,
    val size: Int? = null,
    val total: Int? = null,
    val rows: List<SearchResultItem>? = null
)

@Serializable
data class SearchResultItem(
    val id: Long? = null,
    val title: String? = null,
    val score: Double? = null,
    val movieType: Int? = null,
    val movieTypeName: String? = null,
    val coverHorizontalImage: String? = null,
    val coverVerticalImage: String? = null,
    val unlockPlayback: Boolean? = null,
    val seasonDescription: String? = null,
    val languages: List<String>? = null,
    val lastEpisodeCount: Int? = null,
    val serverTime: Long? = null,
    val woolUser: Boolean? = null,
    val briefIntroduction: String? = null,
    val publishTime: Long? = null,
    val tags: List<String>? = null,
    val countries: List<String>? = null
)

@Serializable
data class VideoResponse(
    val code: Int,
    val msg: String,
    val data: VideoData
)

@Serializable
data class VideoData(
    val videoUrl: String? = null,
    val expireTime: Long? = null,
    val isPreview: Boolean? = null,
    val videos: List<VideoQuality>? = null,
    val subtitles: List<SubtitleData>? = null,
    val inBlacklist: Boolean? = null,
    val permissionDenied: Boolean? = null
)

@Serializable
data class SubtitleData(
    val languageId: Int? = null,
    val abbreviate: String? = null,
    val title: String? = null,
    val url: String? = null,
    val isDefault: Boolean? = null,
    val isAI: Int? = null
)

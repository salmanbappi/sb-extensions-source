package eu.kanade.tachiyomi.animeextension.en.meguanime

import kotlinx.serialization.Serializable

// ============================== AniList GraphQL Models ==============================

@Serializable
data class GraphQLRequest(
    val query: String? = null,
    val variables: GraphQLVariables? = null,
)

@Serializable
data class GraphQLVariables(
    val id: Int? = null,
    val page: Int? = null,
    val perPage: Int? = 24 = null,
    val search: String? = null,
    val sort: List<String>? = null,
    val genres: List<String>? = null,
    val format: List<String>? = null,
    val status: List<String>? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
)

@Serializable
data class AnilistGraphQLResponse(
    val data: AnilistData? = null,
)

@Serializable
data class AnilistData(
    val Page: AnilistPage? = null,
    val Media: AnilistMedia? = null,
)

@Serializable
data class AnilistPage(
    val pageInfo: PageInfo? = null,
    val media: List<AnilistMedia> = emptyList(),
)

@Serializable
data class PageInfo(
    val hasNextPage: Boolean? = false = null,
    val total: Int? = 0 = null,
)

@Serializable
data class AnilistMedia(
    val id: Int? = null,
    val idMal: Int? = null,
    val title: MediaTitle? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val episodes: Int? = null,
    val status: String? = null,
    val format: String? = null,
    val duration: Int? = null,
    val countryOfOrigin: String? = null,
    val studios: Studios? = null,
    val trailer: Trailer? = null,
    val nextAiringEpisode: NextAiringEpisode? = null,
    val airingSchedule: AiringSchedule? = null,
    val streamingEpisodes: List<StreamingEpisode>? = null,
)

@Serializable
data class MediaTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class CoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class Studios(
    val nodes: List<StudioNode>? = null,
)

@Serializable
data class StudioNode(
    val name: String? = null,
)

@Serializable
data class Trailer(
    val id: String? = null,
    val site: String? = null,
)

@Serializable
data class NextAiringEpisode(
    val episode: Int? = null,
    val airingAt: Long? = null,
)

@Serializable
data class AiringSchedule(
    val nodes: List<AiringNode>? = null,
)

@Serializable
data class AiringNode(
    val episode: Int? = null,
    val airingAt: Long? = null,
)

@Serializable
data class StreamingEpisode(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
)

// ============================== Kitsu Models ==============================

@Serializable
data class KitsuMappingResponse(
    val data: List<KitsuMappingData> = emptyList(),
)

@Serializable
data class KitsuMappingData(
    val id: String? = null,
    val relationships: KitsuMappingRelationships? = null,
)

@Serializable
data class KitsuMappingRelationships(
    val item: KitsuItemRelationship? = null,
)

@Serializable
data class KitsuItemRelationship(
    val links: KitsuLinks? = null,
)

@Serializable
data class KitsuLinks(
    val related: String? = null,
)

@Serializable
data class KitsuAnimeResponse(
    val data: KitsuAnimeData? = null,
)

@Serializable
data class KitsuAnimeData(
    val id: String? = null,
)

@Serializable
data class KitsuEpisodesResponse(
    val data: List<KitsuEpisodeData>? = null,
)

@Serializable
data class KitsuEpisodeData(
    val id: String? = null,
    val attributes: KitsuEpisodeAttributes? = null,
)

@Serializable
data class KitsuEpisodeAttributes(
    val number: Int? = null,
    val canonicalTitle: String? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val thumbnail: KitsuThumbnail? = null,
    val airdate: String? = null,
)

@Serializable
data class KitsuThumbnail(
    val original: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)

// ============================== MeguAnime API Models ==============================

@Serializable
data class MiruroResponse(
    val source: String? = null,
    val sources: List<MiruroSource>? = null,
    val providers: List<MiruroProvider>? = null,
    val tracks: List<MeguTrack>? = null,
)

@Serializable
data class MiruroProvider(
    val id: String? = null,
    val label: String? = null,
    val source: String? = null,
    val tracks: List<MeguTrack>? = null,
)

@Serializable
data class MiruroSource(
    val source: String? = null,
    val name: String? = null,
    val tracks: List<MeguTrack>? = null,
)

@Serializable
data class MegaplayResponse(
    val source: String? = null,
    val tracks: List<MeguTrack>? = null,
)

@Serializable
data class KiwiResponse(
    val source: String? = null,
    val qualities: List<KiwiQuality>? = null,
    val tracks: List<MeguTrack>? = null,
)

@Serializable
data class KiwiQuality(
    val label: String? = null,
    val source: String? = null,
)

@Serializable
data class DubResponse(
    val source: String? = null,
    val sources: List<DubSource>? = null,
    val tracks: List<MeguTrack>? = null,
)

@Serializable
data class DubSource(
    val source: String? = null,
)

@Serializable
data class MeguTrack(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = false = null,
)

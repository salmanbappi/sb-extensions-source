package eu.kanade.tachiyomi.animeextension.all.animex

import kotlinx.serialization.Serializable

@Serializable
data class GraphQLRequest(
    val query: String? = null,
    val variables: GraphQLVariables? = null,
)

@Serializable
data class GraphQLVariables(
    val filter: AnimeCatalogFilterInput? = null,
    val sort: List<AnimeSortInput>? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val anilistId: Int? = null,
)

@Serializable
data class AnimeCatalogFilterInput(
    val query: String? = null,
    val statusIn: List<String>? = null,
    val seasonIn: List<String>? = null,
    val seasonYearMin: Int? = null,
    val seasonYearMax: Int? = null,
    val formatIn: List<String>? = null,
    val genres: List<String>? = null,
)

@Serializable
data class AnimeSortInput(
    val field: String? = null,
    val direction: String? = null,
)

@Serializable
data class CatalogAnimeResponse(
    val data: CatalogAnimeData? = null,
)

@Serializable
data class CatalogAnimeData(
    val catalogAnime: CatalogAnimeContent? = null,
)

@Serializable
data class CatalogAnimeContent(
    val items: List<AnimeCatalogItem> = emptyList(),
)

@Serializable
data class CoverImage(
    val extraLarge: String? = null,
    val color: String? = null,
)

@Serializable
data class AnimeCatalogItem(
    val id: String = "",
    val anilistId: Int = 0,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val status: String? = null,
    val format: String? = null,
    val averageScore: Double? = null,
    val popularity: Int? = null,
    val episodeCount: Int? = null,
    val seasonYear: Int? = null,
    val season: String? = null,
    val color: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class GetAnimeResponse(
    val data: GetAnimeData? = null,
)

@Serializable
data class GetAnimeData(
    val anime: AnimeDetailsItem? = null,
)

@Serializable
data class AnimeDetailsItem(
    val id: String = "",
    val anilistId: Int = 0,
    val malId: Int? = null,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val description: String? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val status: String? = null,
    val format: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Double? = null,
    val seasonYear: Int? = null,
    val season: String? = null,
)

@Serializable
data class RestEpisode(
    val number: Float? = null,
    val titles: EpisodeTitles? = null,
    val img: String? = null,
    val isFiller: Boolean? = null,
    val hasSub: Boolean? = null,
    val hasDub: Boolean? = null,
)

@Serializable
data class EpisodeTitles(
    val en: String? = null,
)

@Serializable
data class ServersResponse(
    val subProviders: List<ProviderItem> = emptyList(),
    val dubProviders: List<ProviderItem> = emptyList(),
)

@Serializable
data class ProviderItem(
    val id: String? = null,
    val default: Boolean? = null,
    val tip: String? = null,
    val type: String? = null,
    val url: String? = null,
)

@Serializable
data class SourcesResponse(
    val sources: List<SourceItem> = emptyList(),
    val tracks: List<TrackItem>? = null,
    val headers: Map<String, String>? = null,
    val chapters: List<ChapterItem>? = null,
)

@Serializable
data class SourceItem(
    val url: String? = null,
    val quality: String? = null,
    val type: String? = null,
)

@Serializable
data class TrackItem(
    val id: String? = null,
    val url: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class ChapterItem(
    val title: String? = null,
    val start: Double? = null,
    val end: Double? = null,
)

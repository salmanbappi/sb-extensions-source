package eu.kanade.tachiyomi.animeextension.en.anikuro

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class Anikuro : Source() {

    override val name = "AniKuro"

    override val baseUrl = "https://anikuro.ru"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    private val jsonSerializer = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    companion {
        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = "ALL"

        private const val PREF_PROVIDER_KEY = "pref_provider"
        private const val PREF_PROVIDER_DEFAULT = "ALL"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/api/v1/discovery/search?sort=POPULARITY_DESC&page=$page", headers)).execute()
        return parseAnimeSearchPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/api/v1/discovery/search?sort=TRENDING_DESC&page=$page", headers)).execute()
        return parseAnimeSearchPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$baseUrl/api/v1/discovery/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }
            filters.forEach { filter ->
                when (filter) {
                    is Filters.SortFilter -> {
                        if (!filter.isDefault()) addQueryParameter("sort", filter.selectedValue())
                    }
                    is Filters.FormatFilter -> {
                        if (!filter.isDefault()) addQueryParameter("format", filter.selectedValue())
                    }
                    is Filters.StatusFilter -> {
                        if (!filter.isDefault()) addQueryParameter("status", filter.selectedValue())
                    }
                    is Filters.SeasonFilter -> {
                        if (!filter.isDefault()) addQueryParameter("season", filter.selectedValue())
                    }
                    is Filters.GenreFilter -> {
                        filter.selectedGenres().forEach { genre ->
                            addQueryParameter("genres", genre)
                        }
                    }
                    else -> {}
                }
            }
        }

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseAnimeSearchPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply to browse and search results"),
        Filters.SortFilter(),
        Filters.FormatFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeSearchPage(response: Response): AnimesPage {
        val dto = jsonSerializer.decodeFromString<SearchResponseDto>(response.body.string())
        val items = dto.data?.items ?: emptyList()
        val animeList = items.map { item ->
            SAnime.create().apply {
                url = "/watch/${item.id}"
                title = item.title?.userPreferred
                    ?: item.title?.english
                    ?: item.title?.romaji
                    ?: item.title?.native
                    ?: ""
                thumbnail_url = item.coverImage?.extraLarge
                    ?: item.coverImage?.large
                    ?: item.images?.cover
                    ?: item.banner
                fetch_type = FetchType.Episodes
            }
        }
        val perPage = dto.meta?.filters?.perPage ?: 20
        val hasNextPage = items.size >= perPage
        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val response = client.newCall(GET("$baseUrl/api/v1/anime/$id/full", headers)).execute()
        val dto = jsonSerializer.decodeFromString<AnimeDetailResponseDto>(response.body.string())
        val data = dto.data ?: return anime

        return SAnime.create().apply {
            url = anime.url
            title = data.title?.userPreferred
                ?: data.title?.english
                ?: data.title?.romaji
                ?: data.title?.native
                ?: anime.title
            thumbnail_url = data.images?.cover
                ?: data.coverImage?.extraLarge
                ?: anime.thumbnail_url
            genre = data.genres?.joinToString(", ")
            author = data.studio
            status = when (data.status?.uppercase()) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "NOT_YET_RELEASED" -> SAnime.LICENSED
                "CANCELLED" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                data.averageScore?.let { score ->
                    if (score > 0) {
                        val stars = (score / 20).coerceIn(0, 5)
                        append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} ${"%.1f".format(score / 10.0)} / 10\n\n")
                    }
                }
                if (!data.description.isNullOrBlank()) {
                    append(data.description)
                }
                if (!data.format.isNullOrBlank()) append("\n\nFormat: ${data.format}")
                if (!data.season.isNullOrBlank() && data.seasonYear != null) append("\nSeason: ${data.season} ${data.seasonYear}")
                if (!data.studio.isNullOrBlank()) append("\nStudio: ${data.studio}")
                if (!data.status.isNullOrBlank()) append("\nStatus: ${data.status}")
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val response = client.newCall(GET("$baseUrl/api/v1/anime/$id/episodes", headers)).execute()
        val dto = jsonSerializer.decodeFromString<EpisodesResponseDto>(response.body.string())
        val epList = dto.data?.episodes ?: emptyList()

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)

        return epList.map { ep ->
            val numDisplay = ep.displayNumber ?: ep.number?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "1"
            SEpisode.create().apply {
                url = "/watch/$id/$numDisplay"
                name = if (!ep.title.isNullOrBlank()) "Ep. $numDisplay: ${ep.title}" else "Episode $numDisplay"
                episode_number = ep.number ?: 0f
                summary = ep.description ?: ep.overview
                preview_url = ep.thumbnail ?: ep.image
                fillermark = ep.filler == true
                scanlator = ep.variants?.joinToString(" / ") { it.replaceFirstChar(Char::titlecase) }
                date_upload = ep.airedAt?.let {
                    runCatching { dateFormat.parse(it)?.time }.getOrNull()
                } ?: 0L
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("/").filter { it.isNotBlank() }
        val animeId = parts.getOrNull(1) ?: return emptyList()
        val epNum = parts.getOrNull(2) ?: "1"

        val providers = listOf(
            ProviderEndpoint("AniKuro", "$baseUrl/api/v1/animepower/video/$animeId/$epNum"),
            ProviderEndpoint("Anikoto", "$baseUrl/api/v1/sources/anikoto/$animeId:$epNum"),
            ProviderEndpoint("AnimiX", "$baseUrl/api/v1/sources/animix/$animeId:$epNum"),
            ProviderEndpoint("Senshi", "$baseUrl/api/v1/sources/senshi/$animeId:$epNum"),
            ProviderEndpoint("AnimePahe", "$baseUrl/api/v1/sources/animepahe/$animeId:$epNum"),
            ProviderEndpoint("AllAnime", "$baseUrl/api/v1/sources/allanime/$animeId:$epNum"),
            ProviderEndpoint("ReAnime", "$baseUrl/api/v1/sources/reanime/$animeId:$epNum"),
            ProviderEndpoint("AnimeDao", "$baseUrl/api/v1/sources/animedao/$animeId:$epNum"),
            ProviderEndpoint("AnimeGG", "$baseUrl/api/v1/animegg/video/$animeId/$epNum"),
            ProviderEndpoint("AniDB", "$baseUrl/api/v1/anidb/video/$animeId/$epNum"),
            ProviderEndpoint("AnimeDunya", "$baseUrl/api/v1/animedunya/video/$animeId/$epNum"),
            ProviderEndpoint("AnimeVerse", "$baseUrl/api/v1/animeverse/video/$animeId/$epNum"),
        )

        val prefProvider = preferences.getString(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT) ?: PREF_PROVIDER_DEFAULT
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT

        return providers.parallelCatchingFlatMapBlocking { provider ->
            if (prefProvider != "ALL" && !provider.name.equals(prefProvider, ignoreCase = true)) {
                return@parallelCatchingFlatMapBlocking emptyList()
            }

            val req = GET(provider.url, headers)
            val res = runCatching { client.newCall(req).execute() }.getOrNull()
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            if (!res.isSuccessful) return@parallelCatchingFlatMapBlocking emptyList()

            val bodyStr = res.body.string()
            val dto = runCatching { jsonSerializer.decodeFromString<ProviderResponseDto>(bodyStr) }.getOrNull()
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            val normalizedList = dto.data?.normalized ?: emptyList()

            normalizedList.flatMap { norm ->
                val variantStr = norm.variant?.uppercase() ?: "SUB"
                if (prefType != "ALL" && variantStr != prefType) {
                    return@flatMap emptyList()
                }

                val subTracks = norm.subtitles?.mapNotNull { sub ->
                    val subUrl = sub.url ?: return@mapNotNull null
                    Track(subUrl, sub.label ?: sub.lang ?: "Subtitle")
                } ?: emptyList()

                val headersBuilder = headers.newBuilder()
                norm.headers?.forEach { (k, v) ->
                    headersBuilder.set(k, v)
                }
                val vidHeaders = headersBuilder.build()

                norm.sources?.mapNotNull { src ->
                    val streamUrl = src.url ?: return@mapNotNull null
                    val qualityLabel = src.quality ?: "Default"
                    val titleStr = "${provider.name} ($variantStr) - $qualityLabel"
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = titleStr,
                        headers = vidHeaders,
                        subtitleTracks = subTracks,
                    )
                } ?: emptyList()
            }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        val prefProvider = preferences.getString(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT) ?: PREF_PROVIDER_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefType, ignoreCase = true) }
                .thenByDescending { if (prefProvider != "ALL") it.videoTitle.contains(prefProvider, ignoreCase = true) else true }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
        )
    }

    // ============================ Recommendations ========================
    fun relatedAnimeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        return GET("$baseUrl/api/v1/anime/$id/full", headers)
    }

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val dto = jsonSerializer.decodeFromString<AnimeDetailResponseDto>(response.body.string())
        val data = dto.data ?: return emptyList()
        val items = (data.recommendations ?: emptyList()) + (data.relations ?: emptyList())

        return items.distinctBy { it.id }.mapNotNull { item ->
            val itemId = item.id ?: return@mapNotNull null
            SAnime.create().apply {
                url = "/watch/$itemId"
                title = item.title?.userPreferred
                    ?: item.title?.english
                    ?: item.title?.romaji
                    ?: item.title?.native
                    ?: ""
                thumbnail_url = item.coverImage?.extraLarge
                    ?: item.coverImage?.large
                    ?: item.images?.cover
                fetch_type = FetchType.Episodes
            }
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio / Type",
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
            entries = listOf("All", "Sub", "Dub"),
            entryValues = listOf("ALL", "SUB", "DUB"),
        )

        screen.addListPreference(
            key = PREF_PROVIDER_KEY,
            title = "Preferred Provider / Server",
            default = PREF_PROVIDER_DEFAULT,
            summary = "%s",
            entries = listOf(
                "All Servers",
                "AniKuro",
                "Anikoto",
                "AnimiX",
                "Senshi",
                "AnimePahe",
                "AllAnime",
                "ReAnime",
                "AnimeDao",
                "AnimeGG",
                "AniDB",
                "AnimeDunya",
                "AnimeVerse",
            ),
            entryValues = listOf(
                "ALL",
                "AniKuro",
                "Anikoto",
                "AnimiX",
                "Senshi",
                "AnimePahe",
                "AllAnime",
                "ReAnime",
                "AnimeDao",
                "AnimeGG",
                "AniDB",
                "AnimeDunya",
                "AnimeVerse",
            ),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p", "Default"),
            entryValues = listOf("1080", "720", "480", "360", "Default"),
        )
    }

    // ============================ Data Classes ============================
    private data class ProviderEndpoint(val name: String, val url: String)

    @Serializable
    private data class SearchResponseDto(
        val ok: Boolean? = null,
        val data: SearchDataDto? = null,
        val meta: MetaDto? = null,
    )

    @Serializable
    private data class SearchDataDto(
        val items: List<AnimeItemDto>? = null,
    )

    @Serializable
    private data class MetaDto(
        val count: Int? = null,
        val filters: FiltersMetaDto? = null,
    )

    @Serializable
    private data class FiltersMetaDto(
        val page: Int? = null,
        val perPage: Int? = null,
    )

    @Serializable
    private data class AnimeItemDto(
        val id: Int? = null,
        val title: TitleDto? = null,
        val images: ImagesDto? = null,
        val coverImage: CoverImageDto? = null,
        val banner: String? = null,
    )

    @Serializable
    private data class TitleDto(
        val userPreferred: String? = null,
        val english: String? = null,
        val romaji: String? = null,
        val native: String? = null,
    )

    @Serializable
    private data class ImagesDto(
        val cover: String? = null,
        val banner: String? = null,
        val thumbnail: String? = null,
    )

    @Serializable
    private data class CoverImageDto(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null,
    )

    @Serializable
    private data class AnimeDetailResponseDto(
        val ok: Boolean? = null,
        val data: AnimeDetailDataDto? = null,
    )

    @Serializable
    private data class AnimeDetailDataDto(
        val id: Int? = null,
        val title: TitleDto? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val images: ImagesDto? = null,
        val coverImage: CoverImageDto? = null,
        val averageScore: Int? = null,
        val status: String? = null,
        val format: String? = null,
        val season: String? = null,
        val seasonYear: Int? = null,
        val studio: String? = null,
        val recommendations: List<AnimeItemDto>? = null,
        val relations: List<AnimeItemDto>? = null,
    )

    @Serializable
    private data class EpisodesResponseDto(
        val ok: Boolean? = null,
        val data: EpisodeDataDto? = null,
    )

    @Serializable
    private data class EpisodeDataDto(
        val episodes: List<EpisodeItemDto>? = null,
    )

    @Serializable
    private data class EpisodeItemDto(
        val id: String? = null,
        val number: Float? = null,
        val displayNumber: String? = null,
        val title: String? = null,
        val image: String? = null,
        val thumbnail: String? = null,
        val description: String? = null,
        val overview: String? = null,
        val airedAt: String? = null,
        val filler: Boolean? = null,
        val variants: List<String>? = null,
    )

    @Serializable
    private data class ProviderResponseDto(
        val ok: Boolean? = null,
        val data: ProviderDataDto? = null,
    )

    @Serializable
    private data class ProviderDataDto(
        val provider: String? = null,
        val label: String? = null,
        val normalized: List<NormalizedVariantDto>? = null,
    )

    @Serializable
    private data class NormalizedVariantDto(
        val variant: String? = null,
        val sources: List<SourceItemDto>? = null,
        val subtitles: List<SubtitleItemDto>? = null,
        val headers: Map<String, String>? = null,
    )

    @Serializable
    private data class SourceItemDto(
        val url: String? = null,
        val quality: String? = null,
        val type: String? = null,
        @SerialName("isM3U8")
        val isM3U8: Boolean? = null,
    )

    @Serializable
    private data class SubtitleItemDto(
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
    )
}

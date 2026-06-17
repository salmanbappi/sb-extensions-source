package eu.kanade.tachiyomi.animeextension.all.seanime

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import extensions.utils.delegate
import extensions.utils.parseAs
import extensions.utils.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.security.MessageDigest

// ============================== DATA TRANSFER OBJECTS (DTOs) ==============================

@Serializable
data class LibraryCollectionDto(
    val lists: List<LibraryCollectionListDto> = emptyList(),
)

@Serializable
data class LibraryCollectionListDto(
    val type: String? = null,
    val status: String? = null,
    val entries: List<LibraryCollectionEntryDto> = emptyList(),
)

@Serializable
data class LibraryCollectionEntryDto(
    val mediaId: Int,
    val media: BaseMediaDto,
)

@Serializable
data class BaseMediaDto(
    val id: Int,
    val title: MediaTitleDto? = null,
    val coverImage: MediaCoverImageDto? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val season: String? = null,
)

@Serializable
data class MediaTitleDto(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
)

@Serializable
data class MediaCoverImageDto(
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class AnimeEntryResponseDto(
    val data: AnimeEntryDataDto,
)

@Serializable
data class AnimeEntryDataDto(
    val mediaId: Int,
    val media: BaseMediaDto,
    val episodes: List<EpisodeDto> = emptyList(),
    val downloadInfo: DownloadInfoDto? = null,
)

@Serializable
data class DownloadInfoDto(
    val episodesToDownload: List<DownloadInfoEpisodeDto> = emptyList(),
)

@Serializable
data class DownloadInfoEpisodeDto(
    val episodeNumber: Int,
    val aniDBEpisode: String = "",
    val episode: EpisodeDto,
)

@Serializable
data class EpisodeDto(
    val episodeNumber: Int,
    val displayTitle: String? = null,
    val episodeTitle: String? = null,
    val isDownloaded: Boolean = false,
    val localFile: LocalFileDto? = null,
    val episodeMetadata: EpisodeMetadataDto? = null,
)

@Serializable
data class LocalFileDto(
    val path: String,
)

@Serializable
data class EpisodeMetadataDto(
    val summary: String? = null,
    val image: String? = null,
)

@Serializable
data class OnlineEpisodeListResponseDto(
    val data: OnlineEpisodeListResponseDataDto,
)

@Serializable
data class OnlineEpisodeListResponseDataDto(
    val episodes: List<OnlineEpisodeDto> = emptyList(),
)

@Serializable
data class OnlineEpisodeDto(
    val id: String? = null,
    val number: Int,
    val url: String? = null,
    val title: String = "",
    val image: String? = null,
    val description: String? = null,
)

@Serializable
data class OnlineEpisodeSourceDto(
    val data: OnlineEpisodeSourceDataDto,
)

@Serializable
data class OnlineEpisodeSourceDataDto(
    val number: Int,
    val videoSources: List<OnlineVideoSourceDto> = emptyList(),
)

@Serializable
data class OnlineVideoSourceDto(
    val url: String,
    val server: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class SeanimeExtensionListResponseDto(
    val data: List<SeanimeExtensionDto> = emptyList(),
)

@Serializable
data class SeanimeExtensionDto(
    val id: String,
    val name: String? = null,
)

@Serializable
data class AniListResponse(
    val data: AniListData,
)

@Serializable
data class AniListData(
    val Page: AniListPage,
)

@Serializable
data class AniListPage(
    val media: List<AniListMedia>,
    val pageInfo: AniListPageInfo? = null,
)

@Serializable
data class AniListPageInfo(
    val hasNextPage: Boolean = false,
    val currentPage: Int = 1,
)

@Serializable
data class AniListMedia(
    val id: Int,
    val title: MediaTitleDto? = null,
    val coverImage: MediaCoverImageDto? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val format: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val episodes: Int? = null,
    val averageScore: Int? = null,
)

// ============================== FILTERS ==============================

class StatusFilter :
    AnimeFilter.Select<String>(
        "Status",
        arrayOf("Any", "Currently Airing", "Finished", "Not Yet Aired", "Cancelled"),
        0,
    ) {
    private val statusValues = arrayOf("Any", "Currently Airing", "Finished", "Not Yet Aired", "Cancelled")
    val toAniList
        get() = when (statusValues[state]) {
            "Currently Airing" -> "RELEASING"
            "Finished" -> "FINISHED"
            "Not Yet Aired" -> "NOT_YET_RELEASED"
            "Cancelled" -> "CANCELLED"
            else -> null
        }
}

class FormatFilter :
    AnimeFilter.Select<String>(
        "Format",
        arrayOf("Any", "TV", "TV Short", "Movie", "Special", "OVA", "ONA", "Music"),
        0,
    ) {
    private val formatValues = arrayOf("Any", "TV", "TV Short", "Movie", "Special", "OVA", "ONA", "Music")
    val toAniList
        get() = when (formatValues[state]) {
            "TV" -> "TV"
            "TV Short" -> "TV_SHORT"
            "Movie" -> "MOVIE"
            "Special" -> "SPECIAL"
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "Music" -> "MUSIC"
            else -> null
        }
}

class SeasonFilter :
    AnimeFilter.Select<String>(
        "Season",
        arrayOf("Any", "Winter", "Spring", "Summer", "Fall"),
        0,
    ) {
    private val seasonValues = arrayOf("Any", "Winter", "Spring", "Summer", "Fall")
    val toAniList
        get() = when (seasonValues[state]) {
            "Winter" -> "WINTER"
            "Spring" -> "SPRING"
            "Summer" -> "SUMMER"
            "Fall" -> "FALL"
            else -> null
        }
}

class SeasonYearFilter : AnimeFilter.Text("Season Year", "")

class SortFilter :
    AnimeFilter.Select<String>(
        "Sort By",
        arrayOf("Popularity", "Score", "Trending", "Newest", "Title"),
        0,
    ) {
    private val sortValues = arrayOf("Popularity", "Score", "Trending", "Newest", "Title")
    val toAniList
        get() = when (sortValues[state]) {
            "Score" -> "SCORE_DESC"
            "Trending" -> "TRENDING_DESC"
            "Newest" -> "START_DATE_DESC"
            "Title" -> "TITLE_ROMAJI"
            else -> "POPULARITY_DESC"
        }
}

class GenreFilter(genres: Array<String>) :
    AnimeFilter.Select<String>(
        "Genre",
        genres,
        0,
    )

// ============================== MAIN EXTENSION CLASS ==============================

class Seanime :
    Source(),
    UnmeteredSource,
    ConfigurableAnimeSource {

    override val name = "Seanime"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 85274903847291047L

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL, DEFAULT_BASE_URL)!!.removeSuffix("/")

    override val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private var cachedHeaders: okhttp3.Headers? = null
    private var lastPassword = ""

    private fun getSeanimeHeaders(): okhttp3.Headers {
        val password = preferences.getString(PREF_SERVER_PASSWORD, DEFAULT_SERVER_PASSWORD)!!
        if (cachedHeaders != null && password == lastPassword) {
            return cachedHeaders!!
        }
        val builder = okhttp3.Headers.Builder()
        builder.add("User-Agent", "Aniyomi-Seanime-Extension")
        if (password.isNotBlank()) {
            val token = sha256(password)
            builder.add("X-Seanime-Token", token)
        }
        lastPassword = password
        cachedHeaders = builder.build()
        return cachedHeaders!!
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseSeasonNumber(title: String): Double {
        val regex = Regex("Season\\s+(\\d+)|S(\\d+)", RegexOption.IGNORE_CASE)
        val match = regex.find(title)
        if (match != null) {
            val group1 = match.groups[1]?.value
            val group2 = match.groups[2]?.value
            return (group1 ?: group2)?.toDoubleOrNull() ?: 1.0
        }
        return 1.0
    }

    private fun LibraryCollectionEntryDto.toSAnime(): SAnime = SAnime.create().apply {
        val animeTitle = media.title?.userPreferred
            ?: media.title?.english
            ?: media.title?.romaji
            ?: "Anime $mediaId"
        title = animeTitle
        thumbnail_url = media.coverImage?.large ?: media.coverImage?.medium
        description = media.description
        genre = media.genres.joinToString(", ")
        status = when (media.status?.uppercase()) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        season_number = parseSeasonNumber(animeTitle)
        url = "library:$mediaId"
        initialized = true
    }

    private fun AniListMedia.toSAnime(): SAnime = SAnime.create().apply {
        val animeTitle = this@toSAnime.title?.userPreferred
            ?: this@toSAnime.title?.english
            ?: this@toSAnime.title?.romaji
            ?: "Anime $id"
        title = animeTitle
        thumbnail_url = coverImage?.large
        description = this@toSAnime.description
        genre = genres.joinToString(", ")
        status = when (this@toSAnime.status?.uppercase()) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        season_number = parseSeasonNumber(animeTitle)
        url = "anilist:$id"
        initialized = true
    }

    // ============================== SOURCE INTERFACE OVERRIDES ==============================

    /**
     * Popular = AniList trending/popular anime (always has results, no local library needed)
     */
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val showLibrary = preferences.getBoolean(PREF_SHOW_LIBRARY_IN_BROWSE, DEFAULT_SHOW_LIBRARY_IN_BROWSE)

        if (showLibrary) {
            val headers = getSeanimeHeaders()
            val response = client.newCall(GET("$baseUrl/api/v1/library/collection", headers)).await()
            if (response.isSuccessful) {
                val collection = response.parseAs<LibraryCollectionDto>(json)
                val animeList = collection.lists.flatMap { list ->
                    list.entries.map { it.toSAnime() }
                }.distinctBy { it.url }
                // If library is non-empty, show it; otherwise fall back to AniList popular
                if (animeList.isNotEmpty()) {
                    return AnimesPage(animeList, false)
                }
            } else {
                response.close()
            }
        }

        // Fall back to AniList popular
        return fetchAniListPage(page, sortBy = "POPULARITY_DESC")
    }

    /**
     * Latest = Currently airing anime from AniList (always has results)
     */
    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchAniListPage(page, sortBy = "START_DATE_DESC", status = "RELEASING")

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val formatFilter = filters.filterIsInstance<FormatFilter>().firstOrNull()
        val seasonFilter = filters.filterIsInstance<SeasonFilter>().firstOrNull()
        val seasonYearFilter = filters.filterIsInstance<SeasonYearFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()

        val mode = preferences.getString(PREF_STREAMING_MODE, DEFAULT_STREAMING_MODE)!!

        // If in local mode AND query is set AND no other filters, search local library
        if (query.isNotBlank() && mode != MODE_ONLINE) {
            val headers = getSeanimeHeaders()
            val response = client.newCall(GET("$baseUrl/api/v1/library/collection", headers)).await()
            if (response.isSuccessful) {
                val collection = response.parseAs<LibraryCollectionDto>(json)
                val filteredList = collection.lists.flatMap { list ->
                    list.entries.map { it.toSAnime() }
                }.distinctBy { it.url }.filter { anime ->
                    anime.title.contains(query, ignoreCase = true) ||
                        (anime.genre?.contains(query, ignoreCase = true) == true)
                }
                if (filteredList.isNotEmpty()) {
                    return AnimesPage(filteredList, false)
                }
            } else {
                response.close()
            }
        }

        // Use AniList for broader search (always works)
        return fetchAniListPage(
            page = page,
            query = query.ifBlank { null },
            sortBy = sortFilter?.toAniList ?: "POPULARITY_DESC",
            status = statusFilter?.toAniList,
            format = formatFilter?.toAniList,
            season = seasonFilter?.toAniList,
            seasonYear = seasonYearFilter?.state?.toIntOrNull(),
            genre = if ((genreFilter?.state ?: 0) > 0) GENRE_LIST.getOrNull((genreFilter?.state ?: 0)) else null,
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filter results using AniList"),
        SortFilter(),
        StatusFilter(),
        FormatFilter(),
        GenreFilter(arrayOf("Any") + GENRE_LIST),
        SeasonFilter(),
        SeasonYearFilter(),
    )

    private suspend fun fetchAniListPage(
        page: Int,
        query: String? = null,
        sortBy: String = "POPULARITY_DESC",
        status: String? = null,
        format: String? = null,
        season: String? = null,
        seasonYear: Int? = null,
        genre: String? = null,
    ): AnimesPage {
        val graphQLQuery = buildString {
            append("query (")
            append("\$page: Int, \$perPage: Int, ")
            if (query != null) append("\$search: String, ")
            if (status != null) append("\$status: MediaStatus, ")
            if (format != null) append("\$format: MediaFormat, ")
            if (season != null) append("\$season: MediaSeason, ")
            if (seasonYear != null) append("\$seasonYear: Int, ")
            if (genre != null) append("\$genre: String, ")
            append("\$sort: [MediaSort]")
            append(") {\n")
            append("  Page(page: \$page, perPage: \$perPage) {\n")
            append("    pageInfo { hasNextPage currentPage }\n")
            append("    media(\n")
            append("      type: ANIME\n")
            if (query != null) append("      search: \$search\n")
            if (status != null) append("      status: \$status\n")
            if (format != null) append("      format: \$format\n")
            if (season != null) append("      season: \$season\n")
            if (seasonYear != null) append("      seasonYear: \$seasonYear\n")
            if (genre != null) append("      genre: \$genre\n")
            append("      sort: \$sort\n")
            append("      isAdult: false\n")
            append("    ) {\n")
            append("      id\n")
            append("      title { userPreferred english romaji }\n")
            append("      coverImage { large }\n")
            append("      description\n")
            append("      genres\n")
            append("      status\n")
            append("      format\n")
            append("      season\n")
            append("      seasonYear\n")
            append("      episodes\n")
            append("      averageScore\n")
            append("    }\n")
            append("  }\n")
            append("}")
        }

        // Build request body manually to properly serialize sort as a JSON array
        val variablesParts = buildList {
            add("\"page\":$page")
            add("\"perPage\":20")
            add("\"sort\":[\"$sortBy\"]")
            if (query != null) add("\"search\":${jsonString(query)}")
            if (status != null) add("\"status\":\"$status\"")
            if (format != null) add("\"format\":\"$format\"")
            if (season != null) add("\"season\":\"$season\"")
            if (seasonYear != null) add("\"seasonYear\":$seasonYear")
            if (genre != null) add("\"genre\":${jsonString(genre)}")
        }
        val variablesJson = "{${variablesParts.joinToString(",")}}"

        // Escape the query for embedding in JSON string
        val escapedQuery = graphQLQuery
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

        val bodyRaw = "{\"query\":\"$escapedQuery\",\"variables\":$variablesJson}"

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = bodyRaw.toRequestBody(mediaType)

        val response = client.newCall(
            okhttp3.Request.Builder()
                .url("https://graphql.anilist.co")
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build(),
        ).await()

        if (response.isSuccessful) {
            val aniListResponse = response.parseAs<AniListResponse>(json)
            val list = aniListResponse.data.Page.media.map { it.toSAnime() }
            val hasNext = aniListResponse.data.Page.pageInfo?.hasNextPage ?: (list.size >= 20)
            return AnimesPage(list, hasNext)
        } else {
            response.close()
            throw Exception("Failed to fetch from AniList (Code: ${response.code})")
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = anime.url

        // If this is a local library entry
        if (url.startsWith("library:")) {
            val mediaId = url.removePrefix("library:").toInt()
            val headers = getSeanimeHeaders()
            val response = client.newCall(GET("$baseUrl/api/v1/library/anime-entry/$mediaId", headers)).await()
            if (response.isSuccessful) {
                val entryDto = response.parseAs<AnimeEntryResponseDto>(json)
                val media = entryDto.data.media
                return SAnime.create().apply {
                    val animeTitle = media.title?.userPreferred
                        ?: media.title?.english
                        ?: media.title?.romaji
                        ?: "Anime $mediaId"
                    title = animeTitle
                    thumbnail_url = media.coverImage?.large ?: media.coverImage?.medium
                    description = media.description
                    genre = media.genres.joinToString(", ")
                    status = when (media.status?.uppercase()) {
                        "FINISHED" -> SAnime.COMPLETED
                        "RELEASING" -> SAnime.ONGOING
                        else -> SAnime.UNKNOWN
                    }
                    season_number = parseSeasonNumber(animeTitle)
                    this.url = "library:$mediaId"
                    initialized = true
                }
            } else {
                response.close()
                return anime
            }
        }

        // AniList entry - details are already populated
        return anime
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = anime.url
        val mode = preferences.getString(PREF_STREAMING_MODE, DEFAULT_STREAMING_MODE)!!
        val headers = getSeanimeHeaders()

        // Resolve mediaId - for anilist entries, try library first, then online
        val mediaId: Int = when {
            url.startsWith("library:") -> url.removePrefix("library:").toInt()
            url.startsWith("anilist:") -> url.removePrefix("anilist:").toInt()
            else -> url.toIntOrNull() ?: throw Exception("Invalid anime URL: $url")
        }

        if (mode == MODE_ONLINE) {
            val dubbed = preferences.getBoolean(PREF_DUBBED, DEFAULT_DUBBED)

            // 1. Fetch installed online stream providers
            val providers = try {
                val response = client.newCall(GET("$baseUrl/api/v1/extensions/list/onlinestream-provider", headers)).await()
                if (response.isSuccessful) {
                    response.parseAs<SeanimeExtensionListResponseDto>(json).data
                } else {
                    response.close()
                    emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }

            if (providers.isEmpty()) {
                throw Exception("No online streaming extensions installed in Seanime. Please install one in your Seanime server.")
            }

            // 2. Concurrently fetch episode lists from all providers
            val episodesLists = withContext(Dispatchers.IO) {
                providers.map { provider ->
                    async {
                        try {
                            val body = buildJsonObject {
                                put("mediaId", mediaId)
                                put("dubbed", dubbed)
                                put("provider", provider.id)
                            }.toRequestBody(json)

                            val response = client.newCall(POST("$baseUrl/api/v1/onlinestream/episode-list", headers, body)).await()
                            if (response.isSuccessful) {
                                val epListResponse = response.parseAs<OnlineEpisodeListResponseDto>(json)
                                provider to epListResponse.data.episodes
                            } else {
                                response.close()
                                null
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (episodesLists.isEmpty()) {
                throw Exception("Failed to fetch episode list from any online provider.")
            }

            // 3. Find the provider that has the most episodes
            val bestEntry = episodesLists.maxByOrNull { it.second.size }
                ?: throw Exception("No episodes found from any online provider.")

            val bestEpisodes = bestEntry.second

            return bestEpisodes.map { ep ->
                SEpisode.create().apply {
                    this.url = "online:$mediaId:${ep.number}"

                    val epName = "Episode ${ep.number}"
                    val epSubTitle = ep.title

                    name = if (!epSubTitle.isNullOrBlank() && epSubTitle.trim() != epName.trim()) {
                        if (epSubTitle.contains("Episode ${ep.number}", ignoreCase = true) ||
                            epSubTitle.contains("Ep. ${ep.number}", ignoreCase = true) ||
                            epSubTitle.contains(epName, ignoreCase = true)
                        ) {
                            epSubTitle
                        } else {
                            "$epName - $epSubTitle"
                        }
                    } else {
                        epName
                    }

                    episode_number = ep.number.toFloat()
                    summary = ep.description
                    preview_url = ep.image
                }
            }.reversed()
        } else if (mode == MODE_LOCAL) {
            val response = client.newCall(GET("$baseUrl/api/v1/library/anime-entry/$mediaId", headers)).await()
            if (response.isSuccessful) {
                val entryDto = response.parseAs<AnimeEntryResponseDto>(json)
                val allEpisodes = entryDto.data.episodes
                val localEpisodes = allEpisodes.filter { it.isDownloaded }

                if (localEpisodes.isEmpty()) {
                    throw Exception("No downloaded episodes found in library. Switch to 'Torrent Stream' or 'Online Stream' mode in extension settings.")
                }

                return localEpisodes.map { ep ->
                    SEpisode.create().apply {
                        this.url = "local:${ep.localFile?.path}"
                        val epName = ep.displayTitle ?: "Episode ${ep.episodeNumber}"
                        val epSubTitle = ep.episodeTitle
                        name = if (!epSubTitle.isNullOrBlank()) "$epName - $epSubTitle" else epName
                        episode_number = ep.episodeNumber.toFloat()
                        summary = ep.episodeMetadata?.summary
                        preview_url = ep.episodeMetadata?.image
                    }
                }.reversed()
            } else {
                response.close()
                throw Exception("This title is not in your Seanime library. Add it to your library or switch to 'Online Stream' mode in extension settings.")
            }
        } else { // MODE_TORRENT
            val response = client.newCall(GET("$baseUrl/api/v1/library/anime-entry/$mediaId", headers)).await()
            if (response.isSuccessful) {
                val entryDto = response.parseAs<AnimeEntryResponseDto>(json)

                val episodesList = entryDto.data.downloadInfo?.episodesToDownload
                if (!episodesList.isNullOrEmpty()) {
                    return episodesList.map { downloadInfoEp ->
                        val ep = downloadInfoEp.episode
                        SEpisode.create().apply {
                            val aniDBEp = downloadInfoEp.aniDBEpisode
                            this.url = "torrent:$mediaId:${ep.episodeNumber}:$aniDBEp"
                            val epName = ep.displayTitle ?: "Episode ${ep.episodeNumber}"
                            val epSubTitle = ep.episodeTitle
                            name = if (!epSubTitle.isNullOrBlank()) "$epName - $epSubTitle" else epName
                            episode_number = ep.episodeNumber.toFloat()
                            summary = ep.episodeMetadata?.summary
                            preview_url = ep.episodeMetadata?.image
                        }
                    }.reversed()
                }

                // Fall back to entryDto.data.episodes
                val allEpisodes = entryDto.data.episodes
                if (allEpisodes.isEmpty()) {
                    throw Exception("No episodes found for this title. Try switching to 'Online Stream' mode in extension settings.")
                }

                return allEpisodes.map { ep ->
                    SEpisode.create().apply {
                        this.url = "torrent:$mediaId:${ep.episodeNumber}:${ep.episodeNumber}"
                        val epName = ep.displayTitle ?: "Episode ${ep.episodeNumber}"
                        val epSubTitle = ep.episodeTitle
                        name = if (!epSubTitle.isNullOrBlank()) "$epName - $epSubTitle" else epName
                        episode_number = ep.episodeNumber.toFloat()
                        summary = ep.episodeMetadata?.summary
                        preview_url = ep.episodeMetadata?.image
                    }
                }.reversed()
            } else {
                response.close()
                throw Exception("Failed to fetch details from Seanime server. Make sure the server is running and accessible.")
            }
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlStr = episode.url
        val headers = getSeanimeHeaders()

        when {
            urlStr.startsWith("local:") -> {
                val filePath = urlStr.removePrefix("local:")
                val encodedPath = URLEncoder.encode(filePath, "UTF-8")
                val videoUrl = "$baseUrl/api/v1/mediastream/file?path=$encodedPath"
                return listOf(Video(videoUrl = videoUrl, videoTitle = "Local Server (Direct Stream)", headers = headers))
            }

            urlStr.startsWith("torrent:") -> {
                val parts = urlStr.removePrefix("torrent:").split(":")
                val mediaId = parts[0].toInt()
                val episodeNumber = parts[1].toInt()
                val aniDBEpisode = parts[2]

                val body = buildJsonObject {
                    put("mediaId", mediaId)
                    put("episodeNumber", episodeNumber)
                    put("aniDBEpisode", aniDBEpisode)
                    put("autoSelect", true)
                    put("playbackType", "default")
                }.toRequestBody(json)

                val response = client.newCall(POST("$baseUrl/api/v1/torrentstream/start", headers, body)).await()
                if (response.isSuccessful) {
                    response.close()
                    val streamUrl = "$baseUrl/api/v1/torrentstream/stream/video.mp4"
                    return listOf(Video(videoUrl = streamUrl, videoTitle = "Torrent Stream (Seanime Auto-Select)", headers = headers))
                } else {
                    response.close()
                    throw Exception("Failed to start torrent stream (Code: ${response.code})")
                }
            }

            urlStr.startsWith("online:") -> {
                val parts = urlStr.removePrefix("online:").split(":")
                val mediaId = parts[0].toInt()
                val episodeNumber = parts[1].toInt()
                val dubbed = preferences.getBoolean(PREF_DUBBED, DEFAULT_DUBBED)

                // 1. Fetch installed online stream providers
                val providers = try {
                    val response = client.newCall(GET("$baseUrl/api/v1/extensions/list/onlinestream-provider", headers)).await()
                    if (response.isSuccessful) {
                        response.parseAs<SeanimeExtensionListResponseDto>(json).data
                    } else {
                        response.close()
                        emptyList()
                    }
                } catch (e: Exception) {
                    emptyList()
                }

                if (providers.isEmpty()) {
                    throw Exception("No online streaming extensions installed in Seanime.")
                }

                // 2. Concurrently fetch video sources from all providers
                val videoList = withContext(Dispatchers.IO) {
                    providers.map { provider ->
                        async {
                            try {
                                val body = buildJsonObject {
                                    put("episodeNumber", episodeNumber)
                                    put("mediaId", mediaId)
                                    put("provider", provider.id)
                                    put("dubbed", dubbed)
                                }.toRequestBody(json)

                                val response = client.newCall(POST("$baseUrl/api/v1/onlinestream/episode-source", headers, body)).await()
                                if (response.isSuccessful) {
                                    val sourceDto = response.parseAs<OnlineEpisodeSourceDto>(json)
                                    provider to sourceDto.data.videoSources
                                } else {
                                    response.close()
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                val allVideos = videoList.flatMap { (provider, videoSources) ->
                    val providerName = provider.name ?: provider.id.replaceFirstChar { it.uppercase() }
                    videoSources.flatMap { vs ->
                        val videoHeaders = okhttp3.Headers.Builder().apply {
                            headers.forEach { (name, value) -> add(name, value) }
                            vs.headers.forEach { (k, v) -> set(k, v) }
                        }.build()

                        val formattedTitle = "[$providerName] ${vs.server} (${vs.quality})"
                        listOf(Video(videoUrl = vs.url, videoTitle = formattedTitle, headers = videoHeaders))
                    }
                }

                if (allVideos.isEmpty()) {
                    throw Exception("No video sources found for this episode.")
                }

                val preferredQuality = preferences.getString(PREF_PREFERRED_QUALITY, DEFAULT_PREFERRED_QUALITY)!!
                val preferredServer = preferences.getString(PREF_PREFERRED_SERVER, DEFAULT_PREFERRED_SERVER)!!

                return allVideos.sortedWith(
                    compareByDescending<Video> { it.videoTitle.contains(preferredQuality, ignoreCase = true) }
                        .thenByDescending { preferredServer.isNotBlank() && it.videoTitle.contains(preferredServer, ignoreCase = true) },
                )
            }

            else -> return emptyList()
        }
    }

    private suspend fun okhttp3.Call.await(): Response = withContext(Dispatchers.IO) { execute() }

    /** Safely encodes a string as a JSON string literal including surrounding quotes */
    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    // ============================== PREFERENCES SETUP ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // ── Server Connection ───────────────────────────────────────────
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "[Server] URL"
            summary = preferences.getString(PREF_BASE_URL, DEFAULT_BASE_URL)
                ?.let { "Current: $it" }
                ?: DEFAULT_BASE_URL
            setDefaultValue(DEFAULT_BASE_URL)
            dialogTitle = "Seanime Server URL"
            dialogMessage = "Enter the full URL of your Seanime server.\nExample: http://192.168.1.10:43211"
            setOnPreferenceChangeListener { pref, newValue ->
                val newUrl = (newValue as String).trim().removeSuffix("/")
                pref.summary = "Current: $newUrl"
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SERVER_PASSWORD
            title = "[Server] Password"
            summary =
                if (preferences.getString(PREF_SERVER_PASSWORD, DEFAULT_SERVER_PASSWORD).isNullOrBlank()) {
                    "Not set (leave blank for no password)"
                } else {
                    "Password is set (tap to change)"
                }
            setDefaultValue(DEFAULT_SERVER_PASSWORD)
            dialogTitle = "Server Password"
            dialogMessage = "Leave empty if your Seanime server has no password."
            setOnPreferenceChangeListener { pref, newValue ->
                val v = (newValue as String).trim()
                pref.summary =
                    if (v.isBlank()) "Not set (leave blank for no password)" else "Password is set (tap to change)"
                cachedHeaders = null
                true
            }
        }.also(screen::addPreference)

        // ── Browsing ────────────────────────────────────────────────────
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LIBRARY_IN_BROWSE
            title = "[Browse] Show Library in Popular Tab"
            summary =
                "ON: shows your Seanime library (falls back to AniList trending if empty). OFF: always shows AniList trending."
            setDefaultValue(DEFAULT_SHOW_LIBRARY_IN_BROWSE)
        }.also(screen::addPreference)

        // ── Streaming Mode ──────────────────────────────────────────────
        ListPreference(screen.context).apply {
            key = PREF_STREAMING_MODE
            title = "[Playback] Streaming Mode"
            entries =
                arrayOf(
                    "Local Files — play downloaded files from your library",
                    "Torrent Stream — stream via Seanime's torrent client",
                    "Online Stream — stream from online providers",
                )
            entryValues = arrayOf(MODE_LOCAL, MODE_TORRENT, MODE_ONLINE)
            setDefaultValue(DEFAULT_STREAMING_MODE)
            summary = "%s"
        }.also(screen::addPreference)

        // ── Online Stream Settings ──────────────────────────────────────

        ListPreference(screen.context).apply {
            key = PREF_PREFERRED_QUALITY
            title = "[Online] Preferred Quality"
            entries = arrayOf("Source (Best)", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("Source", "1080p", "720p", "480p", "360p")
            setDefaultValue(DEFAULT_PREFERRED_QUALITY)
            summary = "%s"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PREFERRED_SERVER
            title = "[Online] Preferred Server Keyword"
            summary = preferences.getString(PREF_PREFERRED_SERVER, DEFAULT_PREFERRED_SERVER).let {
                if (it.isNullOrBlank()) "Not set" else "Current: $it"
            }
            setDefaultValue(DEFAULT_PREFERRED_SERVER)
            dialogTitle = "Preferred Server"
            dialogMessage = "Keyword matching your preferred server name."
            setOnPreferenceChangeListener { pref, newValue ->
                val value = (newValue as String).trim()
                pref.summary = if (value.isBlank()) "Not set" else "Current: $value"
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DUBBED
            title = "[Online] Dubbed Audio"
            summary = "Request dubbed versions of episodes when available"
            setDefaultValue(DEFAULT_DUBBED)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_BASE_URL = "pref_base_url"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:43211"

        private const val PREF_SERVER_PASSWORD = "pref_server_password"
        private const val DEFAULT_SERVER_PASSWORD = ""

        private const val PREF_STREAMING_MODE = "pref_streaming_mode"
        private const val MODE_LOCAL = "local"
        private const val MODE_TORRENT = "torrent"
        private const val MODE_ONLINE = "online"
        private const val DEFAULT_STREAMING_MODE = MODE_TORRENT

        private const val PREF_PREFERRED_QUALITY = "pref_preferred_quality"
        private const val DEFAULT_PREFERRED_QUALITY = "Source"

        private const val PREF_PREFERRED_SERVER = "pref_preferred_server"
        private const val DEFAULT_PREFERRED_SERVER = ""

        private const val PREF_DUBBED = "pref_dubbed"
        private const val DEFAULT_DUBBED = false

        private const val PREF_SHOW_LIBRARY_IN_BROWSE = "pref_show_library_in_browse"
        private const val DEFAULT_SHOW_LIBRARY_IN_BROWSE = false

        private val GENRE_LIST = arrayOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy",
            "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological",
            "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
        )
    }
}

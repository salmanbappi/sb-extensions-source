package eu.kanade.tachiyomi.animeextension.all.animex

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Animex :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Animex"
    override val baseUrl = "https://animex.one"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 7391826450193847201L

    private val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(AnimexInterceptor(network.client.cookieJar))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://animex.one/")
        .add("Origin", "https://animex.one")
        .add("Accept", "application/json, text/plain, */*")

    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    private fun getPreferredServer(): String = preferences.getString("pref_preferred_server", "Hoshi") ?: "Hoshi"

    // ============================== POPULAR / LATEST ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = """
                query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
                  catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
                    items {
                      id
                      anilistId
                      titleRomaji
                      titleEnglish
                      coverImage
                      bannerImage
                      backdropUrl
                      description
                      status
                      format
                      averageScore
                      popularity
                      episodeCount
                      seasonYear
                      season
                      color
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(
                sort = listOf(AnimeSortInput("POPULARITY", "DESC")),
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.animex.one/graphql", headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = """
                query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
                  catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
                    items {
                      id
                      anilistId
                      titleRomaji
                      titleEnglish
                      coverImage
                      bannerImage
                      backdropUrl
                      description
                      status
                      format
                      averageScore
                      popularity
                      episodeCount
                      seasonYear
                      season
                      color
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(
                sort = listOf(AnimeSortInput("CREATED_AT", "DESC")),
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.animex.one/graphql", headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== SEARCH / DISCOVERY ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var selectedSort = listOf(AnimeSortInput("POPULARITY", "DESC"))
        var selectedGenres: List<String>? = null
        var selectedFormats: List<String>? = null
        var selectedStatus: List<String>? = null
        var selectedSeason: List<String>? = null
        var selectedYear: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    val sortVal = filter.toValue()
                    selectedSort = listOf(
                        when (sortVal) {
                            "TRENDING_DESC" -> AnimeSortInput("TRENDING", "DESC")
                            "POPULARITY_DESC" -> AnimeSortInput("POPULARITY", "DESC")
                            "SCORE_DESC" -> AnimeSortInput("AVERAGE_SCORE", "DESC")
                            "FAVOURITES_DESC" -> AnimeSortInput("FAVOURITES", "DESC")
                            "START_DATE_DESC" -> AnimeSortInput("CREATED_AT", "DESC")
                            "START_DATE" -> AnimeSortInput("CREATED_AT", "ASC")
                            else -> AnimeSortInput("POPULARITY", "DESC")
                        },
                    )
                }

                is StatusFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        selectedStatus = listOf(value)
                    }
                }

                is SeasonFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        selectedSeason = listOf(value)
                    }
                }

                is FormatFilter -> {
                    val formats = filter.state
                        .filter { it.state }
                        .map { it.value }
                    if (formats.isNotEmpty()) {
                        selectedFormats = formats
                    }
                }

                is GenreFilter -> {
                    val genres = filter.state
                        .filter { it.state }
                        .map { it.value }
                    if (genres.isNotEmpty()) {
                        selectedGenres = genres
                    }
                }

                is YearFilter -> {
                    val value = filter.state
                    if (value.isNotBlank()) {
                        selectedYear = value.toIntOrNull()
                    }
                }

                else -> {}
            }
        }

        val filterInput = AnimeCatalogFilterInput(
            query = if (query.isNotBlank()) query else null,
            statusIn = selectedStatus,
            seasonIn = selectedSeason,
            seasonYearMin = selectedYear,
            seasonYearMax = selectedYear,
            formatIn = selectedFormats,
            genres = selectedGenres,
        )

        val queryBody = GraphQLRequest(
            query = """
                query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
                  catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
                    items {
                      id
                      anilistId
                      titleRomaji
                      titleEnglish
                      coverImage
                      bannerImage
                      backdropUrl
                      description
                      status
                      format
                      averageScore
                      popularity
                      episodeCount
                      seasonYear
                      season
                      color
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(
                filter = filterInput,
                sort = selectedSort,
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )

        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.animex.one/graphql", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        val result = json.decodeFromString<CatalogAnimeResponse>(responseBody)
        val animeList = result.data.catalogAnime.items.map { item ->
            SAnime.create().apply {
                url = "/anime/${item.anilistId}/${item.id}"
                title = item.titleEnglish ?: item.titleRomaji ?: "Unknown Title"
                thumbnail_url = item.coverImage?.extraLarge
                description = item.description
                genre = item.genres.joinToString()
            }
        }
        return AnimesPage(animeList, animeList.size >= 30)
    }

    // ============================== ANIME DETAILS ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val urlParts = anime.url.split("/")
        val anilistId = urlParts.getOrNull(2)?.toIntOrNull()
            ?: anime.url.substringAfter("/anime/").substringBefore("/").toIntOrNull()
            ?: throw Exception("Invalid anime URL: ${anime.url}")

        val queryBody = GraphQLRequest(
            query = """
                query GetAnime(${'$'}anilistId: Int) {
                  anime(anilistId: ${'$'}anilistId) {
                    id
                    anilistId
                    malId
                    titleRomaji
                    titleEnglish
                    description
                    coverImage
                    bannerImage
                    status
                    format
                    genres
                    averageScore
                    seasonYear
                    season
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(anilistId = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.animex.one/graphql", headers, body)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        val result = json.decodeFromString<GetAnimeResponse>(responseBody)
        val anime = result.data.anime ?: throw Exception("Anime not found")

        return SAnime.create().apply {
            title = anime.titleEnglish ?: anime.titleRomaji ?: "Unknown Title"
            thumbnail_url = anime.coverImage?.extraLarge
            description = buildString {
                anime.description?.let { append(it) }
                anime.averageScore?.let { append("\n\nScore: $it/100") }
                anime.seasonYear?.let { append("\nYear: $it") }
                anime.season?.let { append("\nSeason: $it") }
            }
            genre = anime.genres.joinToString()
            status = when (anime.status?.uppercase()) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "NOT_YET_RELEASED", "CANCELLED", "HIATUS" -> SAnime.UNKNOWN
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== EPISODE LIST ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val urlParts = anime.url.split("/")
        val slug = urlParts.getOrNull(3)
            ?: throw Exception("Could not extract slug from URL: ${anime.url}")
        return GET("https://pp.animex.one/rest/api/episodes?id=$slug", headers)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val request = episodeListRequest(anime)
        val response = client.newCall(request).execute()
        return episodeListParse(response, anime)
    }

    private fun episodeListParse(response: Response, anime: SAnime): List<SEpisode> {
        val urlParts = anime.url.split("/")
        val slug = urlParts.getOrNull(3)
            ?: throw Exception("Could not extract slug from URL: ${anime.url}")
        return episodeListParseWithSlug(response, slug)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = response.request.url.queryParameter("id")
            ?: throw Exception("Could not extract slug from request URL: ${response.request.url}")
        return episodeListParseWithSlug(response, slug)
    }

    private fun episodeListParseWithSlug(response: Response, slug: String): List<SEpisode> {
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val responseBody = response.body.string()
        val restEpisodes = json.decodeFromString<List<RestEpisode>>(responseBody)
        val episodes = restEpisodes.map { episode ->
            SEpisode.create().apply {
                name = "Episode ${episode.number}${episode.titles?.en?.let { ": $it" } ?: ""}"
                episode_number = episode.number.toFloat()
                url = "/watch/$slug/${episode.number}"
            }
        }
        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================== VIDEO LIST (SOURCES) ==============================

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val parts = episode.url.split("/")
        val slug = parts.getOrNull(2) ?: throw Exception("Invalid episode URL: ${episode.url}")
        val epNum = parts.getOrNull(3) ?: throw Exception("Invalid episode URL: ${episode.url}")
        val preferredType = preferences.getString("pref_preferred_type", "soft") ?: "soft"

        val serversRequest = GET("https://pp.animex.one/rest/api/servers?id=$slug&epNum=$epNum", headers)
        val serversResponse = client.newCall(serversRequest).execute()
        if (!serversResponse.isSuccessful) {
            serversResponse.close()
            return emptyList()
        }

        val serversData = json.decodeFromString<ServersResponse>(serversResponse.body.string())
        val providers = if (preferredType == "dub") serversData.dubProviders else serversData.subProviders
        val videos = mutableListOf<Video>()

        providers.forEach { provider ->
            val providerId = provider.id
            val apiType = if (preferredType == "dub") "dub" else "sub"
            val sourcesRequest = GET("https://pp.animex.one/rest/api/sources?id=$slug&epNum=$epNum&type=$apiType&providerId=$providerId", headers)
            try {
                val sourcesResponse = client.newCall(sourcesRequest).execute()
                if (sourcesResponse.isSuccessful) {
                    val sourcesData = json.decodeFromString<SourcesResponse>(sourcesResponse.body.string())
                    val subtitleTracks = sourcesData.tracks?.map { track ->
                        Track(absoluteUrl(track.url), track.label ?: track.lang ?: "English")
                    } ?: emptyList()

                    val videoHeaders = headersBuilder().apply {
                        sourcesData.headers?.forEach { (key, value) ->
                            set(key, value)
                        }
                    }.build()

                    sourcesData.sources.forEach { source ->
                        val streamUrl = absoluteUrl(source.url)
                        val providerName = providerId.uppercase()
                        val quality = source.quality ?: "Auto"
                        val categoryLabel = preferredType.uppercase()
                        val subStyle = if (preferredType == "dub") {
                            ""
                        } else {
                            when {
                                provider.tip?.contains("soft sub", ignoreCase = true) == true -> " [Soft Subs]"
                                provider.tip?.contains("hard sub", ignoreCase = true) == true -> " [Hard Subs]"
                                else -> ""
                            }
                        }

                        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                            try {
                                val playlistUtils = PlaylistUtils(client, headers)
                                val playlistVideos = playlistUtils.extractFromHls(
                                    playlistUrl = streamUrl,
                                    referer = videoHeaders.get("Referer") ?: "https://animex.one/",
                                    masterHeaders = videoHeaders,
                                    videoHeaders = videoHeaders,
                                    videoNameGen = { hlsQuality ->
                                        val parsedQuality = if (hlsQuality == "Video") quality else hlsQuality
                                        "$providerName: $parsedQuality ($categoryLabel)$subStyle"
                                    },
                                    subtitleList = subtitleTracks,
                                )
                                videos.addAll(playlistVideos)
                            } catch (e: Exception) {
                                val qualityLabel = "$providerName: $quality ($categoryLabel)$subStyle"
                                videos.add(
                                    Video(
                                        streamUrl,
                                        qualityLabel,
                                        streamUrl,
                                        headers = videoHeaders,
                                        subtitleTracks = subtitleTracks,
                                    ),
                                )
                            }
                        } else {
                            val qualityLabel = "$providerName: $quality ($categoryLabel)$subStyle"
                            videos.add(
                                Video(
                                    streamUrl,
                                    qualityLabel,
                                    streamUrl,
                                    headers = videoHeaders,
                                    subtitleTracks = subtitleTracks,
                                ),
                            )
                        }
                    }
                } else {
                    sourcesResponse.close()
                }
            } catch (e: Exception) {
                // Ignore individual provider errors
            }
        }

        val preferredServer = getPreferredServer()
        videos.sortWith(
            compareBy<Video> { video ->
                if (preferredType == "soft" || preferredType == "hard") {
                    val hasPreferredType = video.quality.contains(preferredType, ignoreCase = true)
                    if (hasPreferredType) 0 else 1
                } else {
                    0
                }
            }.thenBy { video ->
                val isPreferredServer = video.quality.contains(preferredServer, ignoreCase = true)
                if (isPreferredServer) 0 else 1
            }
        )

        return videos
    }

    // ============================== FILTERS ==============================

    override fun getFilterList() = AnimeFilterList(
        SortFilter(),
        StatusFilter(),
        SeasonFilter(),
        FormatFilter(),
        GenreFilter(),
        YearFilter(),
    )

    open class UriPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toValue() = vals[state].second
    }

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Trending", "TRENDING_DESC"),
                Pair("Popularity", "POPULARITY_DESC"),
                Pair("Average Score", "SCORE_DESC"),
                Pair("Favourites", "FAVOURITES_DESC"),
                Pair("Newest", "START_DATE_DESC"),
                Pair("Oldest", "START_DATE"),
                Pair("Title A-Z", "TITLE_ENGLISH"),
                Pair("Title Z-A", "TITLE_ENGLISH_DESC"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("Any", ""),
                Pair("Finished", "FINISHED"),
                Pair("Releasing", "RELEASING"),
                Pair("Not Yet Released", "NOT_YET_RELEASED"),
                Pair("Cancelled", "CANCELLED"),
                Pair("Hiatus", "HIATUS"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("Any", ""),
                Pair("Winter", "WINTER"),
                Pair("Spring", "SPRING"),
                Pair("Summer", "SUMMER"),
                Pair("Fall", "FALL"),
            ),
        )

    class FormatFilter :
        AnimeFilter.Group<FormatCheckBox>(
            "Formats",
            listOf(
                FormatCheckBox("TV Show", "TV"),
                FormatCheckBox("TV Short", "TV_SHORT"),
                FormatCheckBox("Movie", "MOVIE"),
                FormatCheckBox("Special", "SPECIAL"),
                FormatCheckBox("OVA", "OVA"),
                FormatCheckBox("ONA", "ONA"),
                FormatCheckBox("Music Video", "MUSIC"),
            ),
        )

    class FormatCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    class GenreFilter :
        AnimeFilter.Group<GenreCheckBox>(
            "Genres",
            listOf(
                GenreCheckBox("Action", "Action"),
                GenreCheckBox("Adventure", "Adventure"),
                GenreCheckBox("Comedy", "Comedy"),
                GenreCheckBox("Drama", "Drama"),
                GenreCheckBox("Ecchi", "Ecchi"),
                GenreCheckBox("Fantasy", "Fantasy"),
                GenreCheckBox("Horror", "Horror"),
                GenreCheckBox("Mahou Shoujo", "Mahou Shoujo"),
                GenreCheckBox("Mecha", "Mecha"),
                GenreCheckBox("Music", "Music"),
                GenreCheckBox("Mystery", "Mystery"),
                GenreCheckBox("Psychological", "Psychological"),
                GenreCheckBox("Romance", "Romance"),
                GenreCheckBox("Sci-Fi", "Sci-Fi"),
                GenreCheckBox("Slice of Life", "Slice of Life"),
                GenreCheckBox("Sports", "Sports"),
                GenreCheckBox("Supernatural", "Supernatural"),
                GenreCheckBox("Thriller", "Thriller"),
            ),
        )

    class GenreCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

    class YearFilter : AnimeFilter.Text("Release Year")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "pref_preferred_type"
            title = "Preferred Type"
            entries = arrayOf("Soft Sub", "Hard Sub", "Dub")
            entryValues = arrayOf("soft", "hard", "dub")
            setDefaultValue("soft")
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "pref_preferred_server"
            title = "Preferred Server"
            entries = arrayOf("Beep", "Mimi", "Vee", "Yuki", "Neko", "Mochi", "Uwu")
            entryValues = arrayOf("beep", "mimi", "vee", "yuki", "neko", "mochi", "uwu")
            setDefaultValue("beep")
            summary = "%s"
        }.also { screen.addPreference(it) }
    }
}

// ============================== SESSION INTERCEPTOR ==============================

/**
 * Animex uses a session cookie (similar to Anivix's movix_session).
 * We perform a homepage "handshake" before any /api/ call if no session cookie exists.
 * The actual cookie name may need updating once verified via browser DevTools.
 */
class AnimexInterceptor(private val cookieJar: CookieJar) : Interceptor {
    companion object {
        private const val SESSION_COOKIE = "_amx_id"
        private const val BASE_URL = "https://animex.one"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("/api/")) {
            val httpUrl = request.url
            val cookies = cookieJar.loadForRequest(httpUrl)
            val hasSessionCookie = cookies.any { it.name == SESSION_COOKIE }

            if (!hasSessionCookie) {
                synchronized(this) {
                    val freshCookies = cookieJar.loadForRequest(httpUrl)
                    if (!freshCookies.any { it.name == SESSION_COOKIE }) {
                        val slug = httpUrl.queryParameter("id")
                        val epNum = httpUrl.queryParameter("epNum")
                        val handshakeUrl = if (slug != null && epNum != null) {
                            "$BASE_URL/watch/$slug-episode-$epNum"
                        } else {
                            BASE_URL
                        }

                        val handshakeClient = OkHttpClient.Builder()
                            .cookieJar(cookieJar)
                            .build()

                        val handshakeRequest = Request.Builder()
                            .url(handshakeUrl)
                            .header("User-Agent", USER_AGENT)
                            .build()

                        try {
                            handshakeClient.newCall(handshakeRequest).execute().close()
                        } catch (e: Exception) {
                            // Ignore handshake errors — proceed anyway
                        }
                    }
                }
            }
        }

        return chain.proceed(request)
    }
}

// ============================== DATA SERIALIZATION MODELS ==============================

@Serializable
data class GraphQLRequest(
    val query: String,
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
    val field: String,
    val direction: String,
)

@Serializable
data class CatalogAnimeResponse(
    val data: CatalogAnimeData,
)

@Serializable
data class CatalogAnimeData(
    val catalogAnime: CatalogAnimeContent,
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
    val id: String,
    val anilistId: Int,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val status: String? = null,
    val format: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val episodeCount: Int? = null,
    val seasonYear: Int? = null,
    val season: String? = null,
    val color: String? = null,
    val genres: List<String> = emptyList(),
)

@Serializable
data class GetAnimeResponse(
    val data: GetAnimeData,
)

@Serializable
data class GetAnimeData(
    val anime: AnimeDetailsItem?,
)

@Serializable
data class AnimeDetailsItem(
    val id: String,
    val anilistId: Int,
    val malId: Int? = null,
    val titleRomaji: String? = null,
    val titleEnglish: String? = null,
    val description: String? = null,
    val coverImage: CoverImage? = null,
    val bannerImage: String? = null,
    val status: String? = null,
    val format: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val seasonYear: Int? = null,
    val season: String? = null,
)

@Serializable
data class RestEpisode(
    val number: Int,
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
    val id: String,
    val default: Boolean? = null,
    val tip: String? = null,
)

@Serializable
data class SourcesResponse(
    val sources: List<SourceItem> = emptyList(),
    val tracks: List<TrackItem>? = null,
    val headers: Map<String, String>? = null,
)

@Serializable
data class SourceItem(
    val url: String,
    val quality: String? = null,
    val type: String? = null,
)

@Serializable
data class TrackItem(
    val id: String? = null,
    val url: String,
    val lang: String? = null,
    val label: String? = null,
    val kind: String? = null,
    val default: Boolean? = null,
)

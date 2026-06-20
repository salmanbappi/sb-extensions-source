package eu.kanade.tachiyomi.animeextension.all.anivix

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
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
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

class Anivix : Source() {

    override val name = "Anivix"
    override val baseUrl = "https://anivix.cc"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 1287389172635928174L

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .addInterceptor(AnivixInterceptor(network.client.cookieJar))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://anivix.cc/")

    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    private fun getPreferredCategory(): String = preferences.getString("pref_anime_category", "sub") ?: "sub"

    private fun getPreferredServer(): String = preferences.getString("pref_preferred_server", "Hoshi") ?: "Hoshi"

    // ============================== POPULAR / LATEST ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = """
                query(${'$'}page: Int) {
                  Page(page: ${'$'}page, perPage: 30) {
                    media(sort: [TRENDING_DESC], type: ANIME, isAdult: false) {
                      id
                      idMal
                      title { english romaji }
                      coverImage { large extraLarge }
                      description(asHtml: false)
                      status
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(page = page),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = """
                query(${'$'}page: Int) {
                  Page(page: ${'$'}page, perPage: 30) {
                    media(sort: [START_DATE_DESC], type: ANIME, isAdult: false) {
                      id
                      idMal
                      title { english romaji }
                      coverImage { large extraLarge }
                      description(asHtml: false)
                      status
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(page = page),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== SEARCH / DISCOVERY ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var selectedSort: List<String> = listOf("TRENDING_DESC")
        var selectedGenres: List<String> = emptyList()
        var selectedFormats: List<String> = emptyList()
        var selectedStatus: List<String>? = null
        var selectedSeason: String? = null
        var selectedYear: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    selectedSort = listOf(filter.toValue())
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
                        selectedSeason = value
                    }
                }

                is FormatFilter -> {
                    selectedFormats = filter.state
                        .filter { it.state }
                        .map { it.value }
                }

                is GenreFilter -> {
                    selectedGenres = filter.state
                        .filter { it.state }
                        .map { it.value }
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

        val queryBody = GraphQLRequest(
            query = """
                query(${'$'}page: Int, ${'$'}search: String, ${'$'}sort: [MediaSort], ${'$'}genres: [String], ${'$'}format: [MediaFormat], ${'$'}status: [MediaStatus], ${'$'}season: MediaSeason, ${'$'}seasonYear: Int) {
                  Page(page: ${'$'}page, perPage: 30) {
                    media(search: ${'$'}search, sort: ${'$'}sort, genre_in: ${'$'}genres, format_in: ${'$'}format, status_in: ${'$'}status, season: ${'$'}season, seasonYear: ${'$'}seasonYear, type: ANIME, isAdult: false) {
                      id
                      idMal
                      title { english romaji }
                      coverImage { large extraLarge }
                      description(asHtml: false)
                      status
                      genres
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(
                page = page,
                search = if (query.isNotBlank()) query else null,
                sort = selectedSort,
                genres = selectedGenres.ifEmpty { null },
                format = selectedFormats.ifEmpty { null },
                status = selectedStatus,
                season = selectedSeason,
                seasonYear = selectedYear,
            ),
        )

        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        val anilistRes = json.decodeFromString<AnilistGraphQLResponse>(responseBody)
        val animeList = anilistRes.data.Page.media.map { media ->
            SAnime.create().apply {
                url = "/anime/${media.id}"
                title = media.title.english ?: media.title.romaji ?: "Unknown Title"
                thumbnail_url = media.coverImage.large ?: media.coverImage.extraLarge
                description = media.description
                genre = media.genres.joinToString()
            }
        }
        return AnimesPage(animeList, animeList.isNotEmpty())
    }

    // ============================== ANIME DETAILS ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val epUrl = anime.url
        val anilistId = epUrl.substringAfter("/anime/").toInt()
        val queryBody = GraphQLRequest(
            query = """
                query(${'$'}id: Int) {
                  Page(page: 1, perPage: 1) {
                    media(id: ${'$'}id) {
                      id
                      idMal
                      title { english romaji }
                      coverImage { large extraLarge }
                      description(asHtml: false)
                      status
                      genres
                      averageScore
                      startDate { year }
                    }
                  }
                }
            """.trimIndent(),
            variables = GraphQLVariables(id = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        val anilistRes = json.decodeFromString<AnilistGraphQLResponse>(responseBody)
        val media = anilistRes.data.Page.media.firstOrNull() ?: throw Exception("Anime not found")

        return SAnime.create().apply {
            title = media.title.english ?: media.title.romaji ?: "Unknown Title"
            thumbnail_url = media.coverImage.large ?: media.coverImage.extraLarge
            description = media.description
            genre = media.genres.joinToString()
            status = when (media.status) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== EPISODE LIST ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val anilistId = anime.url.substringAfter("/anime/")
        return GET("$baseUrl/api/anime/episodes/$anilistId", headers)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val anilistId = anime.url.substringAfter("/anime/")
        val request = GET("$baseUrl/api/anime/episodes/$anilistId", headers)
        val response = client.newCall(request).execute()
        val episodes = mutableListOf<SEpisode>()

        if (response.isSuccessful) {
            val animeRes = json.decodeFromString<AnimeEpisodesResponse>(response.body.string())
            animeRes.episodes.forEach { episode ->
                episodes.add(
                    SEpisode.create().apply {
                        name = "Episode ${episode.number}: ${episode.title ?: "Episode ${episode.number}"}"
                        episode_number = episode.number.toFloat()
                        url = "/anime/${episode.episodeId}"
                    },
                )
            }
            episodes.sortByDescending { it.episode_number }
        } else {
            response.close()
        }

        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    // ============================== VIDEO LIST (SOURCES) ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val episodeId = episode.url.substringAfter("/anime/")
        val category = getPreferredCategory()
        return GET("$baseUrl/api/anime/stream?episodeId=$episodeId&category=$category", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseBody = response.body.string()
        val videos = mutableListOf<Video>()
        val animeStream = json.decodeFromString<AnimeStreamResponse>(responseBody)

        animeStream.sources.forEach { source ->
            val streamUrl = absoluteUrl(source.url)
            val subtitleTracks = source.tracks.map { track ->
                Track(absoluteUrl(track.url), track.label ?: track.lang ?: "English")
            }
            try {
                val extractedVideos = playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "${source.label} - $quality" },
                    subtitleList = subtitleTracks,
                )
                videos.addAll(extractedVideos)
            } catch (e: Exception) {
                videos.add(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "${source.label} (HLS)",
                        headers = headers,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        }

        val preferredServer = getPreferredServer()
        videos.sortBy { !it.videoTitle.contains(preferredServer, ignoreCase = true) }

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
                Pair("Title", "TITLE_ENGLISH_DESC"),
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
            key = "pref_anime_category"
            title = "Preferred Anime Category"
            entries = arrayOf("Subbed", "Dubbed")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue("sub")
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "pref_preferred_server"
            title = "Preferred Server"
            entries = arrayOf("Hoshi", "Kuma", "Kaze", "TryEmbed")
            entryValues = arrayOf("Hoshi", "Kuma", "Kaze", "TryEmbed")
            setDefaultValue("Hoshi")
            summary = "%s"
        }.also { screen.addPreference(it) }
    }
}

// ============================== SESSION INTERCEPTOR ==============================

class AnivixInterceptor(private val cookieJar: CookieJar) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("/api/")) {
            val httpUrl = request.url
            val cookies = cookieJar.loadForRequest(httpUrl)
            val hasSessionCookie = cookies.any { it.name == "movix_session" }

            if (!hasSessionCookie) {
                synchronized(this) {
                    val freshCookies = cookieJar.loadForRequest(httpUrl)
                    if (!freshCookies.any { it.name == "movix_session" }) {
                        val handshakeClient = OkHttpClient.Builder()
                            .cookieJar(cookieJar)
                            .build()

                        val handshakeRequest = Request.Builder()
                            .url("https://anivix.cc")
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .build()

                        try {
                            handshakeClient.newCall(handshakeRequest).execute().close()
                        } catch (e: Exception) {
                            // Ignore handshake errors
                        }
                    }
                }
            }
        }

        val response = chain.proceed(request)

        if (url.contains("/api/") && response.code == 403 && response.headers("Set-Cookie").any { it.contains("movix_session") }) {
            response.close()
            return chain.proceed(request)
        }

        return response
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
    val page: Int? = null,
    val search: String? = null,
    val sort: List<String>? = null,
    val genres: List<String>? = null,
    val format: List<String>? = null,
    val status: List<String>? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val id: Int? = null,
)

@Serializable
data class AnimeEpisodesResponse(
    val episodes: List<AnimeEpisode> = emptyList(),
)

@Serializable
data class AnimeEpisode(
    val episodeId: String,
    val number: Int,
    val title: String? = null,
)

@Serializable
data class AnimeStreamResponse(
    val sources: List<AnimeSource> = emptyList(),
    val subtitles: List<AnimeSubtitle> = emptyList(),
    val defaultSource: String? = null,
)

@Serializable
data class AnimeSource(
    val id: String,
    val url: String,
    val label: String,
    val tracks: List<AnimeSubtitle> = emptyList(),
)

@Serializable
data class AnimeSubtitle(
    val url: String,
    val lang: String? = null,
    val label: String? = null,
)

@Serializable
data class AnilistGraphQLResponse(
    val data: AnilistData,
)

@Serializable
data class AnilistData(
    val Page: AnilistPage,
)

@Serializable
data class AnilistPage(
    val media: List<AnilistMedia> = emptyList(),
)

@Serializable
data class AnilistMedia(
    val id: Int,
    val idMal: Int? = null,
    val title: AnilistTitle,
    val coverImage: AnilistCoverImage,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val startDate: AnilistDate? = null,
)

@Serializable
data class AnilistTitle(
    val english: String? = null,
    val romaji: String? = null,
)

@Serializable
data class AnilistCoverImage(
    val large: String? = null,
    val extraLarge: String? = null,
)

@Serializable
data class AnilistDate(
    val year: Int? = null,
)

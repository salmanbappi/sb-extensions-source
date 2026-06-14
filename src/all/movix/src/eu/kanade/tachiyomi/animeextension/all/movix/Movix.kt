package eu.kanade.tachiyomi.animeextension.all.movix

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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.net.URLEncoder

class Movix :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "MOVIX"
    override val baseUrl = "https://hdmovix.cc"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419901L

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
        .addInterceptor(MovixInterceptor(network.client.cookieJar))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://hdmovix.cc/")

    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    private fun getPreferredCategory(): String = preferences.getString("pref_anime_category", "sub") ?: "sub"

    private fun getPreferredServer(): String = preferences.getString("pref_preferred_server", "Hoshi") ?: "Hoshi"

    // ============================== POPULAR / LATEST ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/tmdb/movie/popular?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/tmdb/trending/all/week?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== SEARCH / DISCOVERY ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val selectedType = typeFilter?.state ?: 0 // 0: Movies, 1: TV Shows, 2: Anime

        var sortVal = "trending"
        var selectedLang: String? = null
        var selectedYear: String? = null
        var selectedGenres: List<GenreCheckBox> = emptyList()
        var selectedStatus: String? = null
        var selectedSeason: String? = null
        var selectedFormats: List<String> = emptyList()

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    sortVal = filter.toValue()
                }

                is LanguageFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        selectedLang = value
                    }
                }

                is YearFilter -> {
                    val value = filter.state
                    if (value.isNotBlank()) {
                        selectedYear = value
                    }
                }

                is GenreFilter -> {
                    selectedGenres = filter.state.filter { it.state }
                }

                is StatusFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        selectedStatus = value
                    }
                }

                is SeasonFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        selectedSeason = value
                    }
                }

                is FormatFilter -> {
                    selectedFormats = filter.state.filter { it.state }.map { it.value }
                }

                else -> {}
            }
        }

        if (selectedType == 2) {
            // Anime (Anilist GraphQL)
            val sortParam = when (sortVal) {
                "popularity" -> listOf("POPULARITY_DESC")
                "date" -> listOf("START_DATE_DESC")
                "rating" -> listOf("SCORE_DESC")
                "title" -> listOf("TITLE_ENGLISH_DESC")
                else -> listOf("TRENDING_DESC")
            }
            val genresList = selectedGenres.mapNotNull { it.animeVal }
            val yearInt = selectedYear?.toIntOrNull()

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
                    sort = sortParam,
                    genres = genresList.ifEmpty { null },
                    format = selectedFormats.ifEmpty { null },
                    status = selectedStatus?.let { listOf(it) },
                    season = selectedSeason,
                    seasonYear = yearInt,
                ),
            )
            val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
            return POST("https://graphql.anilist.co", headers, body)
        } else {
            // Movie or TV Show (TMDB)
            return if (query.isNotBlank()) {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val path = if (selectedType == 0) "movie" else "tv"
                GET("$baseUrl/api/tmdb/search/$path?query=$encodedQuery&page=$page", headers)
            } else {
                val sortParam = when (sortVal) {
                    "popularity" -> "popularity.desc"
                    "date" -> if (selectedType == 0) "release_date.desc" else "first_air_date.desc"
                    "rating" -> "vote_average.desc"
                    "title" -> "original_title.asc"
                    else -> "popularity.desc"
                }
                val genresList = selectedGenres.mapNotNull { if (selectedType == 0) it.movieVal else it.tvVal }
                val genreParam = if (genresList.isNotEmpty()) genresList.joinToString(",") else null

                val path = if (selectedType == 0) "movie" else "tv"
                var url = "$baseUrl/api/tmdb/discover/$path?page=$page"
                if (sortParam != null) url += "&sort_by=$sortParam"
                if (selectedLang != null) url += "&with_original_language=$selectedLang"
                if (selectedYear != null) {
                    val yearParam = if (selectedType == 0) "primary_release_year" else "first_air_date_year"
                    url += "&$yearParam=$selectedYear"
                }
                if (genreParam != null) url += "&with_genres=$genreParam"

                GET(url, headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        val requestUrl = response.request.url.toString()

        return if (requestUrl.contains("graphql.anilist.co")) {
            // Parse Anilist GraphQL response
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
            AnimesPage(animeList, animeList.isNotEmpty())
        } else {
            // Parse TMDB response
            val tmdbPage = json.decodeFromString<TmdbPage>(responseBody)
            val animeList = tmdbPage.results
                .filter { it.media_type != "person" }
                .map { tmdbResult ->
                    SAnime.create().apply {
                        val isTv = tmdbResult.media_type == "tv" || (tmdbResult.first_air_date != null && tmdbResult.title == null)
                        val type = if (isTv) "tv" else "movie"
                        url = "/$type/${tmdbResult.id}"
                        title = tmdbResult.title ?: tmdbResult.name ?: "Unknown Title"
                        thumbnail_url = tmdbResult.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                        description = tmdbResult.overview
                    }
                }
            val hasNextPage = tmdbPage.page < tmdbPage.total_pages
            AnimesPage(animeList, hasNextPage)
        }
    }

    // ============================== ANIME DETAILS ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val epUrl = anime.url
        return when {
            epUrl.startsWith("/movie/") -> {
                val tmdbId = epUrl.substringAfter("/movie/")
                GET("$baseUrl/api/tmdb/movie/$tmdbId?append_to_response=external_ids", headers)
            }

            epUrl.startsWith("/tv/") -> {
                val tmdbId = epUrl.substringAfter("/tv/")
                GET("$baseUrl/api/tmdb/tv/$tmdbId?append_to_response=external_ids", headers)
            }

            epUrl.startsWith("/anime/") -> {
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
                POST("https://graphql.anilist.co", headers, body)
            }

            else -> throw Exception("Invalid anime details URL: $epUrl")
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        val requestUrl = response.request.url.toString()

        return SAnime.create().apply {
            if (requestUrl.contains("graphql.anilist.co")) {
                // Parse Anilist details
                val anilistRes = json.decodeFromString<AnilistGraphQLResponse>(responseBody)
                val media = anilistRes.data.Page.media.firstOrNull() ?: throw Exception("Anime not found")
                title = media.title.english ?: media.title.romaji ?: "Unknown Title"
                thumbnail_url = media.coverImage.large ?: media.coverImage.extraLarge
                description = media.description
                genre = media.genres.joinToString()
                status = when (media.status) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            } else if (requestUrl.contains("/tmdb/movie/")) {
                // Parse TMDB Movie Details
                val movie = json.decodeFromString<TmdbMovieDetails>(responseBody)
                title = movie.title
                thumbnail_url = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                description = movie.overview
                genre = movie.genres.joinToString { it.name }
                status = SAnime.COMPLETED
            } else if (requestUrl.contains("/tmdb/tv/")) {
                // Parse TMDB TV Details
                val tv = json.decodeFromString<TmdbTvDetails>(responseBody)
                title = tv.name
                thumbnail_url = tv.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                description = tv.overview
                genre = tv.genres.joinToString { it.name }
                status = if (tv.in_production == false) SAnime.COMPLETED else SAnime.ONGOING
            }
        }
    }

    // ============================== EPISODE LIST ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val epUrl = anime.url
        return when {
            epUrl.startsWith("/movie/") -> {
                val tmdbId = epUrl.substringAfter("/movie/")
                GET("$baseUrl/api/tmdb/movie/$tmdbId?append_to_response=external_ids", headers)
            }

            epUrl.startsWith("/tv/") -> {
                val tmdbId = epUrl.substringAfter("/tv/")
                GET("$baseUrl/api/tmdb/tv/$tmdbId?append_to_response=external_ids", headers)
            }

            epUrl.startsWith("/anime/") -> {
                val anilistId = epUrl.substringAfter("/anime/")
                GET("$baseUrl/api/anime/episodes/$anilistId", headers)
            }

            else -> throw Exception("Invalid episode list URL: $epUrl")
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val epUrl = anime.url
        val episodes = mutableListOf<SEpisode>()

        if (epUrl.startsWith("/movie/")) {
            val tmdbId = epUrl.substringAfter("/movie/")
            episodes.add(
                SEpisode.create().apply {
                    name = "Play Movie"
                    episode_number = 1f
                    url = "/movie/$tmdbId"
                },
            )
        } else if (epUrl.startsWith("/tv/")) {
            val tmdbId = epUrl.substringAfter("/tv/")
            val request = GET("$baseUrl/api/tmdb/tv/$tmdbId?append_to_response=external_ids", headers)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return emptyList()
            }
            val tv = json.decodeFromString<TmdbTvDetails>(response.body.string())
            val semaphore = Semaphore(5)

            coroutineScope {
                val deferredEpisodes = tv.seasons
                    .filter { it.season_number > 0 }
                    .map { season ->
                        async {
                            semaphore.withPermit {
                                try {
                                    val seasonUrl = "$baseUrl/api/tmdb/tv/${tv.id}/season/${season.season_number}"
                                    val seasonResponse = client.newCall(GET(seasonUrl, headers)).execute()
                                    if (seasonResponse.isSuccessful) {
                                        val seasonDetails = json.decodeFromString<TmdbSeasonDetails>(seasonResponse.body.string())
                                        seasonDetails.episodes.map { episode ->
                                            SEpisode.create().apply {
                                                name = "S${season.season_number} E${episode.episode_number}: ${episode.name ?: "Episode ${episode.episode_number}"}"
                                                episode_number = episode.episode_number.toFloat() + (season.season_number * 1000f)
                                                url = "/tv/${tv.id}/${season.season_number}/${episode.episode_number}"
                                            }
                                        }
                                    } else {
                                        emptyList()
                                    }
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            }
                        }
                    }
                episodes.addAll(deferredEpisodes.awaitAll().flatten())
            }
            episodes.sortByDescending { it.episode_number }
        } else if (epUrl.startsWith("/anime/")) {
            val anilistId = epUrl.substringAfter("/anime/")
            val request = GET("$baseUrl/api/anime/episodes/$anilistId", headers)
            val response = client.newCall(request).execute()
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
        }

        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    // ============================== VIDEO LIST (SOURCES) ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val epUrl = episode.url
        return when {
            epUrl.startsWith("/movie/") -> {
                val tmdbId = epUrl.substringAfter("/movie/")
                GET("$baseUrl/api/vidlink/movie/$tmdbId", headers)
            }

            epUrl.startsWith("/tv/") -> {
                val parts = epUrl.substringAfter("/tv/").split("/")
                val tmdbId = parts[0]
                val season = parts[1]
                val epNum = parts[2]
                GET("$baseUrl/api/vidlink/tv/$tmdbId/$season/$epNum", headers)
            }

            epUrl.startsWith("/anime/") -> {
                val episodeId = epUrl.substringAfter("/anime/")
                val category = getPreferredCategory()
                GET("$baseUrl/api/anime/stream?episodeId=$episodeId&category=$category", headers)
            }

            else -> throw Exception("Invalid video request URL: $epUrl")
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseBody = response.body.string()
        val requestUrl = response.request.url.toString()
        val videos = mutableListOf<Video>()

        if (requestUrl.contains("/vidlink/")) {
            val vidLink = json.decodeFromString<VidLinkResponse>(responseBody)
            if (vidLink.success && vidLink.url != null) {
                val streamUrl = absoluteUrl(vidLink.url)
                videos.add(
                    Video(
                        streamUrl,
                        "VidLink (HLS)",
                        streamUrl,
                        headers = headers,
                    ),
                )
            }
        } else if (requestUrl.contains("/anime/stream")) {
            val animeStream = json.decodeFromString<AnimeStreamResponse>(responseBody)
            animeStream.sources.forEach { source ->
                val streamUrl = absoluteUrl(source.url)
                val subtitleTracks = source.tracks.map { track ->
                    Track(absoluteUrl(track.url), track.label ?: track.lang ?: "English")
                }
                videos.add(
                    Video(
                        streamUrl,
                        "${source.label} (HLS)",
                        streamUrl,
                        headers = headers,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
            val preferredServer = getPreferredServer()
            videos.sortBy { !it.quality.contains(preferredServer, ignoreCase = true) }
        }

        return videos
    }

    // ============================== FILTERS ==============================

    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
        LanguageFilter(),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        SeasonFilter(),
        FormatFilter(),
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

    class TypeFilter :
        AnimeFilter.Select<String>(
            "Content Type",
            arrayOf("Movies", "TV Shows", "Anime"),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Trending / Popular", "trending"),
                Pair("Popularity", "popularity"),
                Pair("Release Date", "date"),
                Pair("Rating / Score", "rating"),
                Pair("Title", "title"),
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Original Language (Movies/TV)",
            arrayOf(
                Pair("Any", ""),
                Pair("English", "en"),
                Pair("Japanese", "ja"),
                Pair("Korean", "ko"),
                Pair("Spanish", "es"),
                Pair("French", "fr"),
                Pair("Chinese", "zh"),
                Pair("Hindi", "hi"),
                Pair("Italian", "it"),
                Pair("German", "de"),
                Pair("Russian", "ru"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Anime Status",
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
            "Anime Season",
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
            "Anime Formats",
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
                GenreCheckBox("Action", "28", "10759", "Action"),
                GenreCheckBox("Adventure", "12", "10759", "Adventure"),
                GenreCheckBox("Animation", "16", "16", null),
                GenreCheckBox("Comedy", "35", "35", "Comedy"),
                GenreCheckBox("Crime", "80", "80", null),
                GenreCheckBox("Documentary", "99", "99", null),
                GenreCheckBox("Drama", "18", "18", "Drama"),
                GenreCheckBox("Family", "10751", "10751", null),
                GenreCheckBox("Fantasy", "14", "10765", "Fantasy"),
                GenreCheckBox("History", "36", null, null),
                GenreCheckBox("Horror", "27", null, "Horror"),
                GenreCheckBox("Mystery", "9648", "9648", "Mystery"),
                GenreCheckBox("Romance", "10749", null, "Romance"),
                GenreCheckBox("Sci-Fi & Fantasy", "878,14", "10765", "Sci-Fi"),
                GenreCheckBox("Thriller", "53", null, "Thriller"),
                GenreCheckBox("Western", "37", "37", null),
                GenreCheckBox("Psychological", null, null, "Psychological"),
                GenreCheckBox("Slice of Life", null, null, "Slice of Life"),
                GenreCheckBox("Sports", null, null, "Sports"),
                GenreCheckBox("Supernatural", null, null, "Supernatural"),
            ),
        )

    class GenreCheckBox(name: String, val movieVal: String?, val tvVal: String?, val animeVal: String?) : AnimeFilter.CheckBox(name)

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

class MovixInterceptor(private val cookieJar: CookieJar) : Interceptor {
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
                            .url("https://hdmovix.cc")
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
data class TmdbPage(
    val page: Int,
    val results: List<TmdbResult> = emptyList(),
    val total_pages: Int = 1,
    val total_results: Int = 0,
)

@Serializable
data class TmdbResult(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val poster_path: String? = null,
    val media_type: String? = null,
    val overview: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    val vote_average: Float? = null,
)

@Serializable
data class TmdbTvDetails(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val poster_path: String? = null,
    val in_production: Boolean? = null,
    val seasons: List<TmdbSeason> = emptyList(),
)

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String,
)

@Serializable
data class TmdbSeason(
    val season_number: Int,
    val episode_count: Int,
    val name: String? = null,
)

@Serializable
data class TmdbSeasonDetails(
    val season_number: Int,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbEpisode(
    val episode_number: Int,
    val name: String? = null,
    val season_number: Int,
)

@Serializable
data class TmdbMovieDetails(
    val id: Int,
    val title: String,
    val overview: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val poster_path: String? = null,
)

@Serializable
data class VidLinkResponse(
    val success: Boolean,
    val url: String? = null,
    val ref: String? = null,
    val sig: String? = null,
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

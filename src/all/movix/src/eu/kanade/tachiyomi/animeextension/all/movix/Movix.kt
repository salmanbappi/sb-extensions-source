package eu.kanade.tachiyomi.animeextension.all.movix

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(MovixInterceptor(network.client.cookieJar))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://hdmovix.cc/")

    // ============================== POPULAR / LATEST ==============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/api/tmdb/movie/popular?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage =
        searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/api/tmdb/trending/all/week?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage =
        searchAnimeParse(response)

    // ============================== SEARCH / DISCOVERY ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val selectedType = typeFilter?.state ?: 0 // 0: Movies, 1: TV Shows, 2: Anime

        return if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            when (selectedType) {
                0 -> GET("$baseUrl/api/tmdb/search/movie?query=$encodedQuery&page=$page", headers)
                1 -> GET("$baseUrl/api/tmdb/search/tv?query=$encodedQuery&page=$page", headers)
                else -> {
                    // Anime Anilist Search
                    val queryBody = """
                        {
                          "query": "query(${'$'}search: String) { Page(page: $page, perPage: 30) { media(search: ${'$'}search, type: ANIME, isAdult: false) { id idMal title { english romaji } coverImage { large extraLarge } description(asHtml: false) status genres } } }",
                          "variables": { "search": "$query" }
                        }
                    """.trimIndent()
                    val body = queryBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                    POST("https://graphql.anilist.co", headers, body)
                }
            }
        } else {
            // Browsing/Discovering popular lists
            when (selectedType) {
                0 -> GET("$baseUrl/api/tmdb/movie/popular?page=$page", headers)
                1 -> GET("$baseUrl/api/tmdb/tv/popular?page=$page", headers)
                else -> {
                    // Anime Anilist Popular
                    val queryBody = """
                        {
                          "query": "query { Page(page: $page, perPage: 30) { media(sort: TRENDING_DESC, type: ANIME, isAdult: false) { id idMal title { english romaji } coverImage { large extraLarge } description(asHtml: false) status genres } } }"
                        }
                    """.trimIndent()
                    val body = queryBody.toRequestBody("application/json; charset=utf-8".toMediaType())
                    POST("https://graphql.anilist.co", headers, body)
                }
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
                val queryBody = """
                    {
                      "query": "query(${'$'}id: Int) { Page(page: 1, perPage: 1) { media(id: ${'$'}id) { id idMal title { english romaji } coverImage { large extraLarge } description(asHtml: false) status genres averageScore startDate { year } } } }",
                      "variables": { "id": $anilistId }
                    }
                """.trimIndent()
                val body = queryBody.toRequestBody("application/json; charset=utf-8".toMediaType())
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

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseBody = response.body.string()
        val requestUrl = response.request.url.toString()
        val episodes = mutableListOf<SEpisode>()

        if (requestUrl.contains("/tmdb/movie/")) {
            // Movie: single episode
            val movie = json.decodeFromString<TmdbMovieDetails>(responseBody)
            episodes.add(
                SEpisode.create().apply {
                    name = "Play Movie"
                    episode_number = 1f
                    url = "/movie/${movie.id}"
                }
            )
        } else if (requestUrl.contains("/tmdb/tv/")) {
            // TV: Parse details and fetch all episodes from seasons synchronously
            val tv = json.decodeFromString<TmdbTvDetails>(responseBody)
            tv.seasons
                .filter { it.season_number > 0 }
                .forEach { season ->
                    try {
                        val seasonUrl = "$baseUrl/api/tmdb/tv/${tv.id}/season/${season.season_number}"
                        val seasonResponse = client.newCall(GET(seasonUrl, headers)).execute()
                        if (seasonResponse.isSuccessful) {
                            val seasonDetails = json.decodeFromString<TmdbSeasonDetails>(seasonResponse.body.string())
                            seasonDetails.episodes.forEach { episode ->
                                episodes.add(
                                    SEpisode.create().apply {
                                        name = "S${season.season_number} E${episode.episode_number}: ${episode.name ?: "Episode ${episode.episode_number}"}"
                                        episode_number = episode.episode_number.toFloat() + (season.season_number * 1000f)
                                        url = "/tv/${tv.id}/${season.season_number}/${episode.episode_number}"
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore errors for individual seasons
                    }
                }
            episodes.sortByDescending { it.episode_number }
        } else if (requestUrl.contains("/anime/episodes/")) {
            // Anime: parse list of episodes
            val animeRes = json.decodeFromString<AnimeEpisodesResponse>(responseBody)
            animeRes.episodes.forEach { episode ->
                episodes.add(
                    SEpisode.create().apply {
                        name = "Episode ${episode.number}: ${episode.title ?: "Episode ${episode.number}"}"
                        episode_number = episode.number.toFloat()
                        url = "/anime/${episode.episodeId}"
                    }
                )
            }
            episodes.sortByDescending { it.episode_number }
        }

        return episodes
    }

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
                GET("$baseUrl/api/anime/stream?episodeId=$episodeId&category=sub", headers)
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
                val streamUrl = "$baseUrl${vidLink.url}"
                videos.add(
                    Video(
                        streamUrl,
                        "VidLink (HLS)",
                        streamUrl,
                        headers = headers
                    )
                )
            }
        } else if (requestUrl.contains("/anime/stream")) {
            val animeStream = json.decodeFromString<AnimeStreamResponse>(responseBody)
            animeStream.sources.forEach { source ->
                val streamUrl = "$baseUrl${source.url}"
                val subtitleTracks = source.tracks.map { track ->
                    Track("$baseUrl${track.url}", track.label ?: track.lang ?: "English")
                }
                videos.add(
                    Video(
                        streamUrl,
                        "${source.label} (HLS)",
                        streamUrl,
                        headers = headers,
                        subtitleTracks = subtitleTracks
                    )
                )
            }
        }

        return videos
    }

    // ============================== FILTERS ==============================

    override fun getFilterList() = AnimeFilterList(
        TypeFilter()
    )

    private class TypeFilter : AnimeFilter.Select<String>(
        "Content Type",
        arrayOf("Movies", "TV Shows", "Anime")
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}

// ============================== SESSION INTERCEPTOR ==============================

class MovixInterceptor(private val cookieJar: CookieJar) : Interceptor {
    @Volatile
    private var hasSession = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("/api/")) {
            val httpUrl = request.url
            val cookies = cookieJar.loadForRequest(httpUrl)
            val hasSessionCookie = cookies.any { it.name == "movix_session" }

            if (!hasSessionCookie && !hasSession) {
                val handshakeClient = OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .build()

                val handshakeRequest = Request.Builder()
                    .url("https://hdmovix.cc")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()

                try {
                    handshakeClient.newCall(handshakeRequest).execute().use { response ->
                        if (response.isSuccessful) {
                            hasSession = true
                        }
                    }
                } catch (e: Exception) {
                    // Ignore handshake errors
                }
            }
        }

        return chain.proceed(request)
    }
}

// ============================== DATA SERIALIZATION MODELS ==============================

@Serializable
data class TmdbPage(
    val page: Int,
    val results: List<TmdbResult> = emptyList(),
    val total_pages: Int = 1,
    val total_results: Int = 0
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
    val vote_average: Float? = null
)

@Serializable
data class TmdbTvDetails(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val poster_path: String? = null,
    val in_production: Boolean? = null,
    val seasons: List<TmdbSeason> = emptyList()
)

@Serializable
data class TmdbGenre(
    val id: Int,
    val name: String
)

@Serializable
data class TmdbSeason(
    val season_number: Int,
    val episode_count: Int,
    val name: String? = null
)

@Serializable
data class TmdbSeasonDetails(
    val season_number: Int,
    val episodes: List<TmdbEpisode> = emptyList()
)

@Serializable
data class TmdbEpisode(
    val episode_number: Int,
    val name: String? = null,
    val season_number: Int
)

@Serializable
data class TmdbMovieDetails(
    val id: Int,
    val title: String,
    val overview: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val poster_path: String? = null
)

@Serializable
data class VidLinkResponse(
    val success: Boolean,
    val url: String? = null,
    val ref: String? = null,
    val sig: String? = null
)

@Serializable
data class AnimeEpisodesResponse(
    val episodes: List<AnimeEpisode> = emptyList()
)

@Serializable
data class AnimeEpisode(
    val episodeId: String,
    val number: Int,
    val title: String? = null
)

@Serializable
data class AnimeStreamResponse(
    val sources: List<AnimeSource> = emptyList(),
    val subtitles: List<AnimeSubtitle> = emptyList(),
    val defaultSource: String? = null
)

@Serializable
data class AnimeSource(
    val id: String,
    val url: String,
    val label: String,
    val tracks: List<AnimeSubtitle> = emptyList()
)

@Serializable
data class AnimeSubtitle(
    val url: String,
    val lang: String? = null,
    val label: String? = null
)

@Serializable
data class AnilistGraphQLResponse(
    val data: AnilistData
)

@Serializable
data class AnilistData(
    val Page: AnilistPage
)

@Serializable
data class AnilistPage(
    val media: List<AnilistMedia> = emptyList()
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
    val startDate: AnilistDate? = null
)

@Serializable
data class AnilistTitle(
    val english: String? = null,
    val romaji: String? = null
)

@Serializable
data class AnilistCoverImage(
    val large: String? = null,
    val extraLarge: String? = null
)

@Serializable
data class AnilistDate(
    val year: Int? = null
)

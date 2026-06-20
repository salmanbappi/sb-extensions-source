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
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

class Movix : Source() {

    override val name = "MOVIX"
    override val baseUrl = "https://hdmovix.cc"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419901L

    private val playlistUtils by lazy { PlaylistUtils(hlsClient, headers) }

    private fun getPreferredServer(): String = preferences.getString("pref_preferred_server", "VidLink") ?: "VidLink"
    private fun getPreferredQuality(): String = preferences.getString("pref_preferred_quality", "1080p") ?: "1080p"

    override val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(MovixInterceptor(network.client.cookieJar))
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequestsPerHost = 30
                maxRequests = 100
            },
        )
        .build()

    private val hlsClient by lazy {
        client.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private val apiClient by lazy {
        client.newBuilder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://hdmovix.cc/")

    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) url else "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"

    // ============================== POPULAR / LATEST ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/tmdb/movie/popular?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/tmdb/trending/all/week?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== SEARCH / DISCOVERY ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val selectedType = typeFilter?.state ?: 0 // 0: Movies, 1: TV Shows

        return if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            when (selectedType) {
                0 -> GET("$baseUrl/api/tmdb/search/movie?query=$encodedQuery&page=$page", headers)
                else -> GET("$baseUrl/api/tmdb/search/tv?query=$encodedQuery&page=$page", headers)
            }
        } else {
            var selectedSort: String? = "popularity.desc"
            var selectedLang: String? = null
            var selectedYear: String? = null
            var selectedGenres: String? = null

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        selectedSort = filter.toValue()
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
                        val genresList = filter.state
                            .filter { it.state }
                            .mapNotNull { if (selectedType == 0) it.movieVal else it.tvVal }
                        if (genresList.isNotEmpty()) {
                            selectedGenres = genresList.joinToString(",")
                        }
                    }

                    else -> {}
                }
            }

            val path = if (selectedType == 0) "movie" else "tv"
            var url = "$baseUrl/api/tmdb/discover/$path?page=$page"
            if (selectedSort != null) url += "&sort_by=$selectedSort"
            if (selectedLang != null) url += "&with_original_language=$selectedLang"
            if (selectedYear != null) {
                val yearParam = if (selectedType == 0) "primary_release_year" else "first_air_date_year"
                url += "&$yearParam=$selectedYear"
            }
            if (selectedGenres != null) url += "&with_genres=$selectedGenres"

            GET(url, headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
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
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== DETAILS ==============================

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

            else -> throw Exception("Invalid details URL: $epUrl")
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        val requestUrl = response.request.url.toString()

        return SAnime.create().apply {
            if (requestUrl.contains("/tmdb/movie/")) {
                val movie = json.decodeFromString<TmdbMovieDetails>(responseBody)
                title = movie.title
                thumbnail_url = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                description = movie.overview
                genre = movie.genres.joinToString { it.name }
                status = SAnime.COMPLETED
            } else if (requestUrl.contains("/tmdb/tv/")) {
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
        }

        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    // ============================== VIDEO LIST (SOURCES) ==============================

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val epUrl = episode.url
        val isTv = epUrl.startsWith("/tv/")
        val (tmdbId, season, episodeNum) = if (isTv) {
            val parts = epUrl.substringAfter("/tv/").split("/")
            Triple(parts[0], parts[1], parts[2])
        } else {
            Triple(epUrl.substringAfter("/movie/"), "", "")
        }

        val servers = listOf(
            ServerInfo("VidLink", "/api/vidlink"),
            ServerInfo("AutoEmbed", "/api/autoembed"),
            ServerInfo("VidKing (EN)", "/api/en"),
            ServerInfo("VidKing (ES)", "/api/es"),
            ServerInfo("VaPlayer", "/api/vaplayer"),
            ServerInfo("Xpass", "/api/xpass"),
            ServerInfo("VKMovie", "/api/vkmovie"),
        )

        val results = coroutineScope {
            servers.map { server ->
                async {
                    try {
                        val request = GET(
                            if (isTv) {
                                "$baseUrl${server.apiPath}/tv/$tmdbId/$season/$episodeNum"
                            } else {
                                "$baseUrl${server.apiPath}/movie/$tmdbId"
                            },
                            headers,
                        )
                        val response = apiClient.newCall(request).execute()
                        if (response.isSuccessful) {
                            val body = response.body.string()
                            parseServerVideos(body, server.name)
                        } else {
                            response.close()
                            emptyList()
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val preferredServer = getPreferredServer()
        val preferredQuality = getPreferredQuality()

        return results.sortedWith(
            compareBy(
                { !it.videoTitle.contains(preferredServer, ignoreCase = true) },
                { !it.videoTitle.contains(preferredQuality, ignoreCase = true) },
            ),
        )
    }

    private suspend fun parseServerVideos(responseBody: String, serverName: String): List<Video> = coroutineScope {
        val deferredVideos = mutableListOf<Deferred<List<Video>>>()
        val seenUrls = mutableSetOf<String>()

        fun addExtraction(url: String, label: String) {
            val absUrl = absoluteUrl(url)
            if (absUrl.isNotBlank() && seenUrls.add(absUrl)) {
                deferredVideos.add(async { extractVideos(absUrl, label) })
            }
        }

        try {
            val res = json.decodeFromString<ServerResponse>(responseBody)
            if (res.success == true || res.url != null) {
                // 1. Check direct qualities map (e.g. vidking, xpass)
                if (res.qualities != null && res.qualities.isNotEmpty()) {
                    res.qualities.forEach { (quality, qUrl) ->
                        addExtraction(qUrl, "$serverName - $quality")
                    }
                }

                // 2. Check stream_urls list (e.g. vaplayer)
                if (res.stream_urls != null && res.stream_urls.isNotEmpty()) {
                    res.stream_urls.forEachIndexed { index, qUrl ->
                        addExtraction(qUrl, "$serverName - Mirror ${index + 1}")
                    }
                }

                // 3. Check sources as Object Map (e.g. VidKing ES)
                val sourceMap = if (res.sources != null && res.sources is JsonObject) {
                    try {
                        json.decodeFromJsonElement<Map<String, String>>(res.sources)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                if (sourceMap != null && sourceMap.isNotEmpty()) {
                    sourceMap.forEach { (quality, qUrl) ->
                        addExtraction(qUrl, "$serverName - $quality")
                    }
                }

                // 4. Check sources as Array List (e.g. vkmovie, autoembed)
                val sourceList = if (res.sources != null && res.sources is JsonArray) {
                    try {
                        json.decodeFromJsonElement<List<SourceItem>>(res.sources)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                if (sourceList != null && sourceList.isNotEmpty()) {
                    sourceList.forEach { source ->
                        val baseLabel = source.label ?: source.server ?: "Mirror"
                        var label = "$serverName ($baseLabel)"

                        val details = mutableListOf<String>()
                        if (!source.language.isNullOrEmpty()) {
                            details.add(source.language)
                        } else if (!source.flag.isNullOrEmpty()) {
                            details.add(source.flag.uppercase())
                        }
                        if (!source.title.isNullOrEmpty()) {
                            details.add(source.title)
                        }
                        if (details.isNotEmpty()) {
                            label += " [${details.joinToString(" - ")}]"
                        }

                        val sUrl = source.url ?: source.link ?: source.stream_url
                        if (sUrl != null) {
                            if (source.qualities != null && source.qualities.isNotEmpty()) {
                                source.qualities.forEach { (quality, qUrl) ->
                                    addExtraction(qUrl, "$label - $quality")
                                }
                            } else {
                                addExtraction(sUrl, label)
                            }
                        }
                    }
                }

                // 5. Fallback/Standard single url (e.g. vidlink, autoembed)
                val fallbackUrl = res.url ?: res.stream_url
                if (fallbackUrl != null && deferredVideos.isEmpty()) {
                    addExtraction(fallbackUrl, serverName)
                }
            }
        } catch (e: Exception) {
            // ignore parsing errors for this server
        }
        deferredVideos.awaitAll().flatten()
    }

    private fun extractVideos(playlistUrl: String, label: String): List<Video> {
        val absUrl = absoluteUrl(playlistUrl)
        return try {
            if (absUrl.contains(".m3u8") || absUrl.contains("hls-proxy")) {
                playlistUtils.extractFromHls(
                    playlistUrl = absUrl,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "$label - $quality" },
                )
            } else {
                listOf(Video(videoUrl = absUrl, videoTitle = label, headers = headers))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ============================== FILTERS ==============================

    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
        LanguageFilter(),
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

    class TypeFilter :
        AnimeFilter.Select<String>(
            "Content Type",
            arrayOf("Movies", "TV Shows"),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popularity Descending", "popularity.desc"),
                Pair("Popularity Ascending", "popularity.asc"),
                Pair("Release Date Descending", "release_date.desc"),
                Pair("Release Date Ascending", "release_date.asc"),
                Pair("Vote Average Descending", "vote_average.desc"),
                Pair("Vote Average Ascending", "vote_average.asc"),
                Pair("Original Title Descending", "original_title.desc"),
                Pair("Original Title Ascending", "original_title.asc"),
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Original Language",
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

    class GenreFilter :
        AnimeFilter.Group<GenreCheckBox>(
            "Genres",
            listOf(
                GenreCheckBox("Action", "28", "10759"),
                GenreCheckBox("Adventure", "12", "10759"),
                GenreCheckBox("Animation", "16", "16"),
                GenreCheckBox("Comedy", "35", "35"),
                GenreCheckBox("Crime", "80", "80"),
                GenreCheckBox("Documentary", "99", "99"),
                GenreCheckBox("Drama", "18", "18"),
                GenreCheckBox("Family", "10751", "10751"),
                GenreCheckBox("Fantasy", "14", "10765"),
                GenreCheckBox("History", "36", null),
                GenreCheckBox("Horror", "27", null),
                GenreCheckBox("Kids", null, "10762"),
                GenreCheckBox("Music", "10402", null),
                GenreCheckBox("Mystery", "9648", "9648"),
                GenreCheckBox("News", null, "10763"),
                GenreCheckBox("Reality", null, "10764"),
                GenreCheckBox("Romance", "10749", null),
                GenreCheckBox("Sci-Fi & Fantasy", "878,14", "10765"),
                GenreCheckBox("Soap", null, "10766"),
                GenreCheckBox("Talk", null, "10767"),
                GenreCheckBox("Thriller", "53", null),
                GenreCheckBox("TV Movie", "10770", null),
                GenreCheckBox("War & Politics", "10752", "10768"),
                GenreCheckBox("Western", "37", "37"),
            ),
        )

    class GenreCheckBox(name: String, val movieVal: String?, val tvVal: String?) : AnimeFilter.CheckBox(name)

    class YearFilter : AnimeFilter.Text("Release Year")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverList = arrayOf(
            "VidLink",
            "AutoEmbed",
            "VidKing (EN)",
            "VidKing (ES)",
            "VaPlayer",
            "Xpass",
            "VKMovie",
        )
        ListPreference(screen.context).apply {
            key = "pref_preferred_server"
            title = "Preferred Server"
            entries = serverList
            entryValues = serverList
            setDefaultValue("VidLink")
            summary = "%s"
        }.also { screen.addPreference(it) }

        val qualityList = arrayOf("1080p", "720p", "480p", "360p")
        ListPreference(screen.context).apply {
            key = "pref_preferred_quality"
            title = "Preferred Quality"
            entries = qualityList
            entryValues = qualityList
            setDefaultValue("1080p")
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
data class ServerResponse(
    val success: Boolean? = null,
    val url: String? = null,
    val stream_url: String? = null,
    val stream_urls: List<String>? = null,
    val qualities: Map<String, String>? = null,
    val sources: JsonElement? = null,
)

@Serializable
data class SourceItem(
    val url: String? = null,
    val link: String? = null,
    val stream_url: String? = null,
    val qualities: Map<String, String>? = null,
    val label: String? = null,
    val server: String? = null,
    val language: String? = null,
    val flag: String? = null,
    val title: String? = null,
)

private data class ServerInfo(val name: String, val apiPath: String)

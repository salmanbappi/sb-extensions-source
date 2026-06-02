package eu.kanade.tachiyomi.animeextension.all.nagordola

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

@OptIn(ExperimentalSerializationApi::class)
class Nagordola : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Nagordola"

    override val baseUrl = "https://cdn.nagordola.com.bd"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 5181466391484419845L

    override val client: okhttp3.OkHttpClient = super.client.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 100
            maxRequestsPerHost = 100
        })
        .build()

    private val json: Json by injectLazy()

    private val omdbJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val preferences: SharedPreferences by lazy {
        uy.kohesive.injekt.Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_POSTER_SOURCE
            title = "Poster Source"
            entries = arrayOf("OMDb", "TMDb")
            entryValues = arrayOf("omdb", "tmdb")
            summary = "%s"
            setDefaultValue("tmdb")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_TMDB_API_KEY
            title = "TMDb API Key"
            summary = "Used for TMDb posters. Get one at themoviedb.org"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_OMDB_API_KEY
            title = "OMDb API Key"
            summary = "Used for OMDb posters. Get one at omdbapi.com"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    private val enrichmentSemaphore = kotlinx.coroutines.sync.Semaphore(10)

    private suspend fun enrichAnimes(animes: List<SAnime>) {
        val source = preferences.getString(PREF_POSTER_SOURCE, "tmdb")
        if (source != "tmdb") return

        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return

        kotlinx.coroutines.withTimeoutOrNull(3000) {
            coroutineScope {
                animes.map { anime ->
                    async {
                        enrichmentSemaphore.withPermit {
                            fetchPosterFromTMDb(anime, apiKey)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun fetchPoster(anime: SAnime, apiKey: String) {
        val cacheKey = "poster_omdb_${anime.title.hashCode()}"
        val cachedPoster = preferences.getString(cacheKey, null)

        if (cachedPoster != null) {
            anime.thumbnail_url = cachedPoster
            return
        }

        try {
            val cleanTitle = anime.title.replace(Regex("""\(?\d{4}\)?"""), "").trim()
            val url = "https://www.omdbapi.com/?apikey=$apiKey&t=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}"
            val response = client.newCall(eu.kanade.tachiyomi.network.GET(url)).awaitSuccess()
            val body = response.body?.string().orEmpty()
            val omdb = omdbJson.decodeFromString<OMDbResponse>(body)

            if (omdb.Response == "True" && !omdb.Poster.isNullOrBlank() && omdb.Poster != "N/A") {
                anime.thumbnail_url = omdb.Poster
                preferences.edit().putString(cacheKey, omdb.Poster).apply()
            }
        } catch (e: Exception) {
            Log.e("Nagordola", "OMDb lookup failed: ${e.message}")
        }
    }

    private suspend fun fetchPosterFromTMDb(anime: SAnime, apiKey: String) {
        val cacheKey = "poster_tmdb_${anime.title.hashCode()}"
        val cachedPoster = preferences.getString(cacheKey, null)

        if (cachedPoster != null) {
            anime.thumbnail_url = cachedPoster
            return
        }

        try {
            val cleanTitle = anime.title.replace(Regex("""\(?\d{4}\)?"""), "").trim()
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}"
            val response = client.newCall(eu.kanade.tachiyomi.network.GET(url)).awaitSuccess()
            val body = response.body?.string().orEmpty()
            val tmdb = omdbJson.decodeFromString<TMDbResponse>(body)

            val posterPath = tmdb.results?.firstOrNull()?.poster_path
            if (!posterPath.isNullOrBlank()) {
                val posterUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                anime.thumbnail_url = posterUrl
                preferences.edit().putString(cacheKey, posterUrl).apply()
            }
        } catch (e: Exception) {
            Log.e("Nagordola", "TMDb lookup failed: ${e.message}")
        }
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).awaitSuccess()
        return popularAnimeParse(response).also { enrichAnimes(it.animes) }
    }

    override fun popularAnimeRequest(page: Int): Request {
        return searchAnimeRequest(page, "", getFilterList())
    }

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    // =============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).awaitSuccess()
        return latestUpdatesParse(response).also { enrichAnimes(it.animes) }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val payload = buildJsonObject {
            put("path", "/movies/movies-english")
            put("page", page)
            put("per_page", 30)
            put("sort", "modified")
            put("order_by", "modified")
            put("reverse", true)
        }
        return POST("$baseUrl/api/fs/list", headers, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotEmpty()) {
            val payload = buildJsonObject {
                put("parent", "/")
                put("keywords", query)
                put("scope", 0)
                put("page", page)
                put("per_page", 30)
            }
            val request = POST("$baseUrl/api/fs/search", headers, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            return client.newCall(request).awaitSuccess().use(::searchAnimeParse).also { enrichAnimes(it.animes) }
        }
        return super.getSearchAnime(page, query, filters).also { enrichAnimes(it.animes) }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterPath = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.let {
            categories[it.state].path
        } ?: "/movies/movies-english"

        val payload = buildJsonObject {
            put("path", filterPath)
            put("page", page)
            put("per_page", 30)
        }
        return POST("$baseUrl/api/fs/list", headers, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body?.string().orEmpty()
        if (response.request.url.encodedPath.endsWith("search")) {
            val res = json.decodeFromString<AListResponse<AListSearchResponse>>(body)
            val animeList = res.data?.content?.filter { it.is_dir }?.map {
                SAnime.create().apply {
                    title = it.name
                    url = "${it.parent}/${it.name}".replace("//", "/")
                }
            } ?: emptyList()
            return AnimesPage(animeList, false)
        } else {
            val res = json.decodeFromString<AListResponse<AListListResponse>>(body)
            val currentPath = json.decodeFromString<AListPathPayload>(response.request.body.bodyString()).path
            val animeList = res.data?.content?.filter { it.is_dir }?.map {
                SAnime.create().apply {
                    title = it.name
                    url = "$currentPath/${it.name}".replace("//", "/")
                }
            } ?: emptyList()
            return AnimesPage(animeList, (res.data?.total ?: 0) > PAGE_LIMIT * 30) // Simplified pagination check
        }
    }

    private fun RequestBody?.bodyString(): String {
        val buffer = okio.Buffer()
        this?.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Serializable
    private data class AListPathPayload(val path: String)

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val source = preferences.getString(PREF_POSTER_SOURCE, "tmdb")
        if (source == "omdb") {
            val apiKey = preferences.getString(PREF_OMDB_API_KEY, "") ?: ""
            if (apiKey.isNotBlank()) fetchPoster(anime, apiKey)
        } else {
            val apiKey = preferences.getString(PREF_TMDB_API_KEY, "5cd49aeaf94161b1e7badb23820f6ea9") ?: "5cd49aeaf94161b1e7badb23820f6ea9"
            if (apiKey.isNotBlank()) fetchPosterFromTMDb(anime, apiKey)
        }
        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        parseDirectory(anime.url, episodes, 0)
        return episodes.distinctBy { it.url }.sortedBy { it.name }.reversed()
    }

    private suspend fun parseDirectory(path: String, episodes: MutableList<SEpisode>, depth: Int) {
        if (depth > 3) return // Depth limit to prevent infinite loops

        val payload = buildJsonObject {
            put("path", path)
        }
        val request = POST("$baseUrl/api/fs/list", headers, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        val response = client.newCall(request).awaitSuccess()
        val res = json.decodeFromString<AListResponse<AListListResponse>>(response.body?.string().orEmpty())

        res.data?.content?.forEach { file ->
            if (file.is_dir) {
                parseDirectory("$path/${file.name}", episodes, depth + 1)
            } else if (isVideoFile(file.name)) {
                episodes.add(
                    SEpisode.create().apply {
                        name = file.name
                        url = "$path/${file.name}".replace("//", "/")
                        episode_number = parseEpisodeNumber(file.name)
                    },
                )
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    private fun parseEpisodeNumber(name: String): Float {
        return fileNameRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: fallbackNumberRegex.find(name)?.groupValues?.get(1)?.toFloatOrNull()
            ?: -1f
    }

    private val fileNameRegex = Regex("""(?i)(?:s\d+e|e|part|ep|episode|第)\s?(\d+)""", RegexOption.IGNORE_CASE)
    private val fallbackNumberRegex = Regex("""(\d+)""")

    private fun isVideoFile(fileName: String): Boolean {
        return fileName.lowercase().let {
            it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".avi") || it.endsWith(".webm")
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val payload = buildJsonObject {
            put("path", episode.url)
        }
        return POST("$baseUrl/api/fs/get", headers, payload.toString().toRequestBody(JSON_MEDIA_TYPE))
    }

    override fun videoListParse(response: Response): List<Video> {
        val res = json.decodeFromString<AListResponse<AListGetFile>>(response.body?.string().orEmpty())
        val videoUrl = res.data?.raw_url ?: return emptyList()
        return listOf(Video(videoUrl, "Direct", videoUrl))
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(categories.map { it.name }.toTypedArray()),
    )

    private class CategoryFilter(categories: Array<String>) : AnimeFilter.Select<String>("Category", categories)

    private data class Category(val name: String, val path: String)

    private val categories = listOf(
        Category("Movies: English", "/movies/movies-english"),
        Category("Movies: Hindi", "/movies/movies-hindi"),
        Category("Movies: Hindi Dubbed", "/movies/movies-hindi-dubbed"),
        Category("Movies: Animation", "/movies/animations-english"),
        Category("Movies: Asian", "/movies/movies-asian"),
        Category("Movies: Bangla", "/movies/movies-bangla"),
        Category("Movies: Foreign", "/movies/movies-foreign"),
        Category("Movies: Korean", "/movies/movies-korean"),
        Category("Movies: Malayalam", "/movies/movies-malayalam"),
        Category("Movies: Tamil", "/movies/movies-tamil"),
        Category("Movies: Telugu", "/movies/movies-telugu"),
        Category("TV Shows: English", "/tv-series/tvshows-english"),
        Category("TV Shows: Hindi", "/tv-series/tvshows-hindi"),
        Category("TV Shows: Hindi Dubbed", "/tv-series/tvshows-hindi-dubbed"),
        Category("TV Shows: Bangla", "/tv-series/tvshows-bangla"),
        Category("TV Shows: Korean", "/tv-series/tvshows-korean"),
        Category("TV Shows: Foreign", "/tv-series/tvshows-foreign"),
        Category("Anime: TV Shows", "/anime/tvshows-anime"),
        Category("Anime: Movies", "/anime/movies-anime"),
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val PAGE_LIMIT = 1 // Simplified
        private const val PREF_OMDB_API_KEY = "omdb_api_key"
        private const val PREF_TMDB_API_KEY = "tmdb_api_key"
        private const val PREF_POSTER_SOURCE = "poster_source"
    }
}

@Serializable
data class OMDbResponse(
    val Response: String,
    val Poster: String? = null,
    val Error: String? = null
)

@Serializable
data class TMDbResponse(
    val results: List<TMDbResult>? = null
)

@Serializable
data class TMDbResult(
    val poster_path: String? = null
)

@Serializable
data class AListResponse<T>(val code: Int, val message: String, val data: T? = null)

@Serializable
data class AListFile(val name: String, val size: Long, val is_dir: Boolean, val thumb: String = "")

@Serializable
data class AListListResponse(val content: List<AListFile>? = emptyList(), val total: Int = 0)

@Serializable
data class AListSearchFile(val parent: String, val name: String, val is_dir: Boolean)

@Serializable
data class AListSearchResponse(val content: List<AListSearchFile>? = emptyList(), val total: Int = 0)

@Serializable
data class AListGetFile(val raw_url: String)

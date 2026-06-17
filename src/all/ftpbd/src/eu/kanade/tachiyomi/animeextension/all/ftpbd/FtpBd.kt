package eu.kanade.tachiyomi.animeextension.all.ftpbd

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import extensions.utils.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class FtpBd(
    override val name: String,
    override val baseUrl: String,
    override val id: Long,
    private val rootSegment: String,
    private val popularPath: String,
    private val searchPaths: List<String>,
    private val serverCategories: Array<String>,
) : Source() {

    private val baseDomain: String
        get() = try {
            baseUrl.toHttpUrl().host.let { h -> if (h.contains(".") && !h.first().isDigit()) h.substring(h.indexOf(".") + 1) else h }
        } catch (e: Exception) {
            "ftpbd.net"
        }

    override val lang = "all"

    override val supportsLatest = true


    private val omdbJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val unsafeTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val unsafeSslSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf<TrustManager>(unsafeTrustManager), SecureRandom())
    }.socketFactory

    private val unsafeBaseClient: OkHttpClient = network.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(unsafeSslSocketFactory, unsafeTrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    private val cm by lazy { CookieManager(unsafeBaseClient) }

    override val client: OkHttpClient = unsafeBaseClient.newBuilder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 100
                maxRequestsPerHost = 100
            },
        )
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val host = try {
                request.url.host
            } catch (e: Exception) {
                ""
            }

            if (host.contains(baseDomain)) {
                val newRequest = request.newBuilder()
                    .apply {
                        val cookie = cm.getCookiesHeaders(url)
                        removeHeader("User-Agent")
                        addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        if (cookie.isNotBlank()) {
                            removeHeader("Cookie")
                            addHeader("Cookie", cookie)
                        }
                    }
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(request)
            }
        }
        .build()

    private fun getGlobalHeaders(): Headers = Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    }.build()

    private suspend fun enrichAnimes(animes: List<SAnime>) {
        val source = preferences.getString(PREF_POSTER_SOURCE, "tmdb")
        if (source != "tmdb") return

        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return

        kotlinx.coroutines.withTimeoutOrNull(3000) {
            coroutineScope {
                animes.take(40).map { anime ->
                    async {
                        enrichmentSemaphore.withPermit {
                            fetchPosterFromTMDb(anime, apiKey)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    data class TitleYear(val title: String, val year: String?)

    private fun extractTitleYear(rawTitle: String): TitleYear {
        val yearRegex = Regex("""(?:\(|\b)(\d{4})(?:\)|\b)""")
        val yearMatch = yearRegex.find(rawTitle)
        val year = yearMatch?.groupValues?.get(1)

        var cleanTitle = rawTitle
            .replace(yearRegex, "")
            .replace("-", " ")
            .replace(".", " ")
            .replace(Regex("""\b(1080p|720p|480p|4k|uhd|bluray|brrip|webrip|webdl|hdtv|x264|x265|h264|h265|hevc|dual audio|multi|eng|hindi|sub|dub|reencoded|TV Documentary|TV Series|TV Mini Series|Complete Series|Complete)\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""\(.*?\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return TitleYear(cleanTitle, year)
    }

    private suspend fun fetchPoster(anime: SAnime, apiKey: String) {
        val cacheKey = "poster_omdb_${anime.title.hashCode()}"
        val cachedPoster = preferences.getString(cacheKey, null)

        if (cachedPoster != null) {
            anime.thumbnail_url = cachedPoster
            return
        }

        try {
            val (title, year) = extractTitleYear(anime.title)
            var url = "https://www.omdbapi.com/?apikey=$apiKey&t=${URLEncoder.encode(title, "UTF-8")}"
            if (year != null) url += "&y=$year"

            val response = client.newCall(GET(url)).awaitSuccess()
            val body = response.body?.string().orEmpty()
            val omdb = omdbJson.decodeFromString<OMDbResponse>(body)

            if (omdb.Response == "True" && !omdb.Poster.isNullOrBlank() && omdb.Poster != "N/A") {
                anime.thumbnail_url = omdb.Poster
                preferences.edit().putString(cacheKey, omdb.Poster).apply()
            }
        } catch (e: Exception) {
            Log.e("FtpBd", "OMDb lookup failed: ${e.message}")
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
            val (title, year) = extractTitleYear(anime.title)
            var url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=${URLEncoder.encode(title, "UTF-8")}"
            if (year != null) url += "&year=$year"

            val response = client.newCall(GET(url)).awaitSuccess()
            val body = response.body?.string().orEmpty()
            val tmdb = omdbJson.decodeFromString<TMDbResponse>(body)

            val posterPath = tmdb.results?.firstOrNull()?.poster_path
            if (!posterPath.isNullOrBlank()) {
                val posterUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                anime.thumbnail_url = posterUrl
                preferences.edit().putString(cacheKey, posterUrl).apply()
            }
        } catch (e: Exception) {
            Log.e("FtpBd", "TMDb lookup failed: ${e.message}")
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        var u = url.trim()
        val lastHttp = u.lastIndexOf("http://", ignoreCase = true)
        val lastHttps = u.lastIndexOf("https://", ignoreCase = true)
        val lastProtocol = Math.max(lastHttp, lastHttps)
        if (lastProtocol > 0) u = u.substring(lastProtocol)
        u = u.replace(Regex("http(s)?://http(s)?://", RegexOption.IGNORE_CASE), "http$1://")
        return u.replace(" ", "%20")
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(fixUrl(anime.url), getGlobalHeaders())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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

    private val directoryCache = mutableMapOf<String, List<SAnime>>()

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = popularAnimeRequest(page)
        return getCachedAnimesPage(request, page)
    }

    private suspend fun getCachedAnimesPage(request: Request, page: Int): AnimesPage {
        val cacheKey = request.url.toString()
        if (page == 1) directoryCache.remove(cacheKey)

        val allAnimes = directoryCache[cacheKey] ?: fetchAnimesStreaming(request).also {
            if (it.isNotEmpty()) directoryCache[cacheKey] = it.sortedWith(compareBy { anime -> anime.title.naturalOrder() }).reversed()
        }

        if (allAnimes.isEmpty()) return AnimesPage(emptyList(), false)

        val itemsPerPage = 25
        val chunk = allAnimes.chunked(itemsPerPage)
        val currentPageItems = chunk.getOrNull(page - 1) ?: emptyList()
        val hasNextPage = page < chunk.size

        return AnimesPage(currentPageItems, hasNextPage).also { enrichAnimes(it.animes) }
    }

    private suspend fun fetchAnimesStreaming(request: Request): List<SAnime> {
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val animeList = mutableListOf<SAnime>()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val source = response.body?.source() ?: return@withContext emptyList()
                    val linkRegex = Regex("""href="([^"]+)"[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)

                    var line: String?
                    while (source.readUtf8Line().also { line = it } != null) {
                        linkRegex.findAll(line!!).forEach { match ->
                            val href = match.groupValues[1]
                            var title = match.groupValues[2].trim()

                            if (isIgnored(title) || href.contains("?") || href.startsWith("http")) return@forEach
                            if (title.endsWith("/")) title = title.removeSuffix("/")

                            val absoluteUrl = if (href.startsWith("/")) {
                                "${baseUrl.toHttpUrl().scheme}://${baseUrl.toHttpUrl().host}$href"
                            } else {
                                response.request.url.toString().removeSuffix("/") + "/" + href.removePrefix("/")
                            }

                            animeList.add(
                                SAnime.create().apply {
                                    this.title = title
                                    this.url = fixUrl(absoluteUrl)
                                    this.thumbnail_url = ""
                                },
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("FtpBd", "Streaming failed: ${e.message}")
            }
            animeList
        }
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/$popularPath", getGlobalHeaders())

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun isIgnored(text: String): Boolean {
        val ignored = listOf("Parent Directory", "modern browsers", "Name", "Last modified", "Size", "Description", "Index of", "JavaScript", "powered by", "_h5ai")
        return ignored.any { text.contains(it, ignoreCase = true) }
    }

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = latestUpdatesRequest(page)
        return getCachedAnimesPage(request, page)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) {
            val request = searchAnimeRequest(page, query, filters)
            return getCachedAnimesPage(request, page)
        }

        val allResults = fetchRecursiveSearch(query)

        val itemsPerPage = 25
        val chunk = allResults.chunked(itemsPerPage)
        val currentPageItems = chunk.getOrNull(page - 1) ?: emptyList()
        val hasNextPage = page < chunk.size

        return AnimesPage(currentPageItems, hasNextPage).also { enrichAnimes(it.animes) }
    }

    private suspend fun fetchRecursiveSearch(query: String): List<SAnime> {
        return coroutineScope {
            val semaphore = Semaphore(15)
            val results = searchPaths.map { path ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val pathUrl = if (path.startsWith("http")) path else "$baseUrl/${path.removePrefix("/")}"
                            val response = client.newCall(GET(pathUrl, getGlobalHeaders())).execute()
                            if (!response.isSuccessful) return@withPermit emptyList<SAnime>()
                            val doc = response.asJsoup()
                            parseSearchDocument(doc, query)
                        } catch (e: Exception) {
                            emptyList<SAnime>()
                        }
                    }
                }
            }.awaitAll().flatten().distinctBy { it.url }
            sortByTitle(results, query)
        }
    }

    private fun parseSearchDocument(document: Document, query: String): List<SAnime> {
        val animeList = mutableListOf<SAnime>()
        val normalizedQuery = query.lowercase()

        document.select("td.fb-n a, div.entry-content a, table tr a").forEach { link ->
            var title = link.text().trim()
            if (title.isBlank() || isIgnored(title)) return@forEach
            if (title.endsWith("/")) title = title.removeSuffix("/")

            if (title.lowercase().contains(normalizedQuery)) {
                val url = link.attr("abs:href")
                if (url.contains("?") || url.endsWith("..")) return@forEach

                animeList.add(
                    SAnime.create().apply {
                        this.title = title
                        this.url = fixUrl(url)
                        this.thumbnail_url = ""
                    },
                )
            }
        }
        return animeList
    }

    private fun sortByTitle(list: List<SAnime>, query: String): List<SAnime> {
        val normalizedQuery = query.lowercase()
        return list.sortedWith(
            compareByDescending<SAnime> {
                if (it.title.lowercase() == normalizedQuery) {
                    2.0
                } else if (it.title.lowercase().startsWith(normalizedQuery)) {
                    1.5
                } else if (it.title.lowercase().contains(normalizedQuery)) {
                    1.0
                } else {
                    0.0
                }
            }.thenByDescending { it.title.naturalOrder() },
        )
    }

    private fun String.naturalOrder(): String = Regex("""\d+""").replace(this) { it.value.padStart(12, '0') }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cat = (filters[1] as CategorySelect).state
        val year = (filters[3] as YearSelect).state
        val lang = (filters[5] as LanguageSelect).state

        var path = when (name) {
            "FTPBD (Movies)" -> when (cat) {
                0 -> "FTP-3/Hindi%20Movies/"
                1 -> "FTP-3/Bangla%20Collection/"
                2 -> "FTP-3/South%20Indian%20Movies/"
                3 -> "FTP-3/Hindi%20TV%20Series/"
                4 -> "FTP-3/South%20Indian%20TV%20Serias/"
                5 -> "FTP-3/Foreign%20Language%20Movies/"
                6 -> "FTP-3/Hindi%20Movies/Hindi-4K-Movies/"
                7 -> "FTP-3/%5BToday%27s%20Upload%5D/"
                else -> "FTP-3/"
            }

            "FTPBD (English)" -> when (cat) {
                0 -> "FTP-2/English%20Movies/"
                1 -> "FTP-2/English%20Movies/English-Movies-4K/"
                2 -> "FTP-2/English%20Movies/Dual-Audio/"
                3 -> "FTP-2/English%20Movies/IMDB%20TOP%20250/"
                4 -> "FTP-2/3D%20Movies/"
                else -> "FTP-2/"
            }

            "FTPBD (Anime)" -> when (cat) {
                0 -> "FTP-5/Anime--Cartoon-TV-Series/"
                1 -> "FTP-5/Animation%20Movies/"
                2 -> "FTP-5/Documentary/"
                else -> "FTP-5/"
            }

            "FTPBD (Series & Tutorial)" -> when (cat) {
                0 -> "FTP-4/English-Foreign-TV-Series/"
                1 -> "FTP-4/Tutorial/"
                else -> "FTP-4/"
            }

            "FTPBD (Sports)" -> when (cat) {
                0 -> "FTP-7/WWE%20Wrestling/"
                1 -> "FTP-7/All%20Elite%20Wrestling%20%28AEW%29/"
                2 -> "FTP-7/Ultimate%20Fighting%20Championship%20%28UFC%29/"
                3 -> "FTP-7/Awards--TV-Shows/"
                else -> "FTP-7/"
            }

            else -> ""
        }

        if (name == "FTPBD (Movies)" && cat == 5 && lang > 0) {
            path = "FTP-3/Foreign%20Language%20Movies/${FilterData.LANGUAGES[lang].replace(" ", "%20")}/"
        }

        var url = "$baseUrl/${path.removePrefix("/")}"
        if (year > 0) {
            val rawYear = FilterData.YEARS[year]
            val formattedYear = if (name == "FTPBD (Anime)" && cat == 1) {
                if (rawYear == "1990-&-Before") {
                    "(2000)%20%26%20Before"
                } else if (rawYear == "2001--2010") {
                    "(2001--2010)"
                } else {
                    "($rawYear)"
                }
            } else {
                rawYear
            }
            url += "${formattedYear.replace(" ", "%20").replace("&", "%26")}/"
        }
        return GET(url, getGlobalHeaders())
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ===============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val source = preferences.getString(PREF_POSTER_SOURCE, "tmdb")
        if (source == "omdb") {
            val apiKey = preferences.getString(PREF_OMDB_API_KEY, "") ?: ""
            if (apiKey.isNotBlank()) fetchPoster(anime, apiKey)
        } else {
            val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
            if (apiKey.isNotBlank()) fetchPosterFromTMDb(anime, apiKey)
        }
        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            status = SAnime.UNKNOWN
            thumbnail_url = ""
            description = document.select("p.storyline, .entry-content p").text().trim().ifBlank { "No description available." }
            genre = document.select("div.ganre-wrapper a, .entry-content a[href*='/category/']").joinToString { it.text().trim() }
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val currentUrl = fixUrl(anime.url)
        val cacheKey = "cache_" + currentUrl.hashCode()

        val cachedData = preferences.getString(cacheKey, null)
        if (cachedData != null) {
            return cachedData.split("|").filter { it.contains(">>") }.map {
                SEpisode.create().apply {
                    val parts = it.split(">>")
                    url = parts[0]
                    name = parts[1]
                }
            }
        }

        val response = client.newCall(GET(currentUrl, getGlobalHeaders())).awaitSuccess()
        val episodes = getDirectoryEpisodes(response.asJsoup())

        val serializable = episodes.joinToString("|") { "${it.url}>>${it.name}" }
        preferences.edit().putString(cacheKey, serializable).apply()

        return episodes
    }

    private suspend fun getDirectoryEpisodes(document: Document): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val depth = if (name == "FTPBD (Anime)") 5 else 3
        parseDirectoryRecursive(document, depth, episodes, mutableSetOf())

        return episodes.sortedWith(compareBy { it.name.naturalOrder() }).reversed()
    }

    private suspend fun parseDirectoryRecursive(document: Document, depth: Int, episodes: MutableList<SEpisode>, visited: MutableSet<String>) {
        val currentUrl = document.location()
        if (!visited.add(currentUrl)) return

        document.select("a[href]").forEach { link ->
            val href = link.attr("abs:href").ifBlank {
                val r = link.attr("href")
                if (r.startsWith("http")) r else currentUrl.removeSuffix("/") + "/" + r.removePrefix("/")
            }
            val text = link.text().trim()
            if (isIgnored(text) || href.contains("?")) return@forEach

            val lowerHref = href.lowercase()
            if (listOf(".mkv", ".mp4", ".avi", ".ts", ".m4v", ".webm", ".mov").any { lowerHref.endsWith(it) }) {
                episodes.add(
                    SEpisode.create().apply {
                        this.name = text
                        this.url = href
                        this.episode_number = -1f
                    },
                )
            } else if (depth > 0 && href.endsWith("/") && !href.contains("_h5ai")) {
                try {
                    val subDoc = client.newCall(GET(href, getGlobalHeaders())).awaitSuccess().asJsoup()
                    parseDirectoryRecursive(subDoc, depth - 1, episodes, visited)
                } catch (e: Exception) {}
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoUrl = fixUrl(episode.url)
        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", baseUrl + "/")
            .build()
        return listOf(Video(videoUrl = videoUrl, videoTitle = "Direct Video", headers = headers))
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("--- Category ---"),
        CategorySelect(serverCategories),
        AnimeFilter.Header("--- Year ---"),
        YearSelect(),
        AnimeFilter.Header("--- Language (Foreign Lang. only) ---"),
        LanguageSelect(),
    )

    class CategorySelect(categories: Array<String>) : AnimeFilter.Select<String>("Select Category", categories)
    class YearSelect : AnimeFilter.Select<String>("Select Year", FilterData.YEARS)
    class LanguageSelect : AnimeFilter.Select<String>("Select Language", FilterData.LANGUAGES)

    private val enrichmentSemaphore = Semaphore(10)

    private class CookieManager(private val client: OkHttpClient) {
        private var cookies = mutableMapOf<String, List<Cookie>>()
        private val lock = Any()

        fun getCookiesHeaders(url: String): String {
            val host = try {
                url.toHttpUrl().host
            } catch (e: Exception) {
                return ""
            }
            val currentCookies = synchronized(lock) {
                cookies[host] ?: fetchCookies(url).also { cookies[host] = it }
            }
            return currentCookies.joinToString("; ") { "${it.name}=${it.value}" }
        }

        private fun fetchCookies(url: String): List<Cookie> {
            val hostUrl = try {
                val u = url.toHttpUrl()
                "${u.scheme}://${u.host}/".toHttpUrl()
            } catch (e: Exception) {
                return emptyList()
            }

            val req = Request.Builder()
                .url(hostUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            return try {
                val res = client.newBuilder().followRedirects(false).build().newCall(req).execute()
                val cookieList = Cookie.parseAll(hostUrl, res.headers)
                res.close()
                cookieList
            } catch (e: IOException) {
                emptyList()
            }
        }
    }
    companion object {
        private const val PREF_OMDB_API_KEY = "omdb_api_key"
        private const val PREF_TMDB_API_KEY = "tmdb_api_key"
        private const val PREF_POSTER_SOURCE = "poster_source"
    }
}

@Serializable
data class OMDbResponse(
    val Response: String,
    val Poster: String? = null,
    val Error: String? = null,
)

@Serializable
data class TMDbResponse(
    val results: List<TMDbResult>? = null,
)

@Serializable
data class TMDbResult(
    val poster_path: String? = null,
)

package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

// --- Constants ---
private const val PREF_TMDB_API_KEY = "tmdb_api_key"
private const val PREF_USE_TMDB_COVERS = "use_tmdb_covers"
private const val IMAGE_PROBE_MARKER = "a_AL_.jpg"
private const val IMAGE_PROBE_MARKER_2 = "a_VL_.jpg"
private const val IMAGE_PROBE_MARKER_3 = "a_V1_.jpg"
private const val IMAGE_PROBE_MARKER_4 = "a0_AL_.jpg"
private const val IMAGE_PROBE_MARKER_5 = "a0_VL_.jpg"
private const val IMAGE_PROBE_MARKER_6 = "a11.jpg"
private const val IMAGE_PROBE_MARKER_7 = "a22.jpg"
private const val IMAGE_PROBE_MARKER_8 = "a4e.jpg"
private const val IMAGE_PROBE_MARKER_9 = "afull.jpg"
private const val FALLBACK_IMAGE = "poster.jpg"

private val IP_HTTP_REGEX = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\s*http""")
private val DOUBLE_PROTOCOL_REGEX = Regex("""http(s)?://http(s)?://""")
private val MULTI_SLASH_REGEX = Regex("""(?<!:)/{2,}""")

private val FILE_EXT_REGEX = Regex("""\.(mkv|mp4|avi|flv)$""", RegexOption.IGNORE_CASE)
private val SEPARATOR_REGEX = Regex("""[._]""", RegexOption.IGNORE_CASE)
private val EPISODE_S_E_REGEX = Regex("""\s+S\d+E\d+.*""", RegexOption.IGNORE_CASE)
private val SEASON_REGEX = Regex("""\s+S\d+.*""", RegexOption.IGNORE_CASE)
private val EPISODE_TEXT_REGEX = Regex("""\s+(?:Episode|Ep)\s*\d+.*""", RegexOption.IGNORE_CASE)
private val YEAR_REGEX = Regex("""\s+[\[\(]?\d{4}[\]\)]?.*""", RegexOption.IGNORE_CASE)
private val QUALITY_REGEX = Regex("""\s+(720p|1080p|WEB-DL|BluRay|HDRip|HDTC|HDCAM|ESub|Dual Audio).*""", RegexOption.IGNORE_CASE)
private val DASH_REGEX = Regex("""\s+-\s+\d+\s+.*""", RegexOption.IGNORE_CASE)

class DhakaFlix2(
    override val name: String,
    override val baseUrl: String,
    override val id: Long,
    private val serverPath: String,
    private val serverCategories: Array<String>,
) : Source() {

    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .addInterceptor(ImageInterceptor())
        .build()

    private inner class ImageInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url.toString()

            // Dynamic Referer: Provie the image's own parent directory to bypass hotlink protection
            val parentFolder = if (url.contains("/")) url.substringBeforeLast("/") + "/" else baseUrl

            val imageHeaders = request.headers.newBuilder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                .set("Referer", parentFolder)
                .build()

            var response = chain.proceed(request.newBuilder().headers(imageHeaders).build())
            if (response.isSuccessful && response.header("Content-Type")?.startsWith("image") == true) return response

            val markers = listOf(IMAGE_PROBE_MARKER, IMAGE_PROBE_MARKER_2, IMAGE_PROBE_MARKER_3, IMAGE_PROBE_MARKER_4, IMAGE_PROBE_MARKER_5, IMAGE_PROBE_MARKER_6, IMAGE_PROBE_MARKER_7, IMAGE_PROBE_MARKER_8, IMAGE_PROBE_MARKER_9, FALLBACK_IMAGE)
            var currentUrl = url

            for (i in 0 until markers.size - 1) {
                if (currentUrl.contains(markers[i])) {
                    response.close()
                    currentUrl = currentUrl.replace(markers[i], markers[i + 1])
                    val fallbackParent = if (currentUrl.contains("/")) currentUrl.substringBeforeLast("/") + "/" else baseUrl
                    response = chain.proceed(
                        request.newBuilder()
                            .url(fixUrl(currentUrl))
                            .header("Referer", fallbackParent)
                            .headers(imageHeaders)
                            .build(),
                    )
                    if (response.isSuccessful && response.header("Content-Type")?.startsWith("image") == true) return response
                }
            }
            return response
        }
    }

    private val searchCache = mutableMapOf<String, List<SAnime>>()
    private val cacheTime = mutableMapOf<String, Long>()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_TMDB_API_KEY
            title = "TMDb API Key"
            summary = "Used for high-quality covers. Get one at themoviedb.org"
            setDefaultValue("")
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_TMDB_COVERS
            title = "Use TMDb Covers"
            summary = "Fetch high-quality covers from TMDb. If disabled, NO images will load for speed."
            setDefaultValue(false)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "clear_tmdb_cache"
            title = "Clear TMDb Cache"
            summary = "Clears all cached TMDb poster URLs"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    val editor = preferences.edit()
                    preferences.all.keys.filter { it.startsWith("tmdb_cover_") }.forEach { editor.remove(it) }
                    editor.apply()
                    android.widget.Toast.makeText(screen.context, "TMDb Cache Cleared", android.widget.Toast.LENGTH_SHORT).show()
                    this.isChecked = false
                }
                false
            }
        }.also { screen.addPreference(it) }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        val u = url.trim()

        val sb = StringBuilder()
        for (c in u) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c == '/' || c == ':' || c == '.' || c == '-' || c == '_' || c == '~' ||
                c == '%' || c == '?' || c == '=' || c == '#' || c == '@' || c == '+' || c == ',' ||
                c == '&' || c == '(' || c == ')' || c == '[' || c == ']' || c == '\'' || c == '!' || c == '*' || c == ';'
            ) {
                sb.append(c)
            } else {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    sb.append(String.format("%%%02X", b))
                }
            }
        }
        return sb.toString()
    }

    private fun formatThumbUrl(url: String): String {
        if (url.isBlank()) return ""
        val fixed = fixUrl(url)
        return if (fixed.contains("?")) "$fixed&cb=1" else "$fixed?cb=1"
    }

    private val enrichmentSemaphore = Semaphore(5)

    private suspend fun enrichAnimes(animes: List<SAnime>) {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""

        withContext(Dispatchers.IO) {
            withTimeoutOrNull(20000) {
                coroutineScope {
                    animes.take(25).map { anime ->
                        async {
                            enrichmentSemaphore.withPermit {
                                // 1. Try TMDb first if enabled
                                if (useTmdb && apiKey.isNotBlank()) {
                                    val tmdbCover = fetchTmdbImage(anime.title)
                                    if (tmdbCover != null) {
                                        anime.thumbnail_url = tmdbCover
                                        return@withPermit
                                    }
                                }

                                // 2. SMART RESOLVE: Fetch folder HTML and scan for thumbnails WITHOUT loading the whole page into memory
                                try {
                                    val response = client.newCall(GET(fixUrl(anime.url), headers)).execute()
                                    if (response.isSuccessful) {
                                        val source = response.body?.source()
                                        if (source != null) {
                                            var foundThumbUrl: String? = null
                                            var firstAnyImageUrl: String? = null
                                            val thumbRegex = Regex("href=\"([^\"]+(?:a11|a22|a4e|afull|a_al|a0_al|a_vl|a0_vl|a_v1)[^\"]*\\.(?:jpg|jpeg|png|webp))\"", RegexOption.IGNORE_CASE)
                                            val anyImageRegex = Regex("href=\"([^\"]+\\.(?:jpg|jpeg|png|webp))\"", RegexOption.IGNORE_CASE)
                                            val excludeRegex = Regex("parent|icon|menu|nav|/_h5ai/", RegexOption.IGNORE_CASE)

                                            var bytesRead = 0L
                                            val maxScanBytes = 512 * 1024L // 512KB limit

                                            while (bytesRead < maxScanBytes) {
                                                val line = source.readUtf8Line() ?: break
                                                bytesRead += line.length

                                                // Check for high-quality thumb first
                                                val thumbMatch = thumbRegex.find(line)
                                                if (thumbMatch != null) {
                                                    foundThumbUrl = thumbMatch.groupValues[1]
                                                    break // STOP IMMEDIATELY
                                                }

                                                // Keep track of any image as fallback
                                                if (firstAnyImageUrl == null) {
                                                    val anyMatch = anyImageRegex.find(line)
                                                    if (anyMatch != null) {
                                                        val url = anyMatch.groupValues[1]
                                                        if (!excludeRegex.containsMatchIn(url)) {
                                                            firstAnyImageUrl = url
                                                        }
                                                    }
                                                }
                                            }

                                            val finalThumbUrl = foundThumbUrl ?: firstAnyImageUrl
                                            if (finalThumbUrl != null) {
                                                val baseUrl = response.request.url.toString()
                                                val absoluteUrl = response.request.url.resolve(finalThumbUrl)?.toString() ?: ""
                                                if (absoluteUrl.isNotEmpty()) {
                                                    anime.thumbnail_url = formatThumbUrl(absoluteUrl)
                                                }
                                            }
                                        }
                                    }
                                    response.close()
                                } catch (e: Exception) {}
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private fun fetchTmdbImage(title: String): String? {
        val cacheKey = "tmdb_cover_".plus(title.hashCode())
        val cached = preferences.getString(cacheKey, null)
        if (cached != null) return cached.takeIf { it.isNotEmpty() }
        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return null

        val cleanTitle = cleanTitleForTmdb(title)
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$cleanTitle".toHttpUrlOrNull() ?: return null

        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val bodyStr = response.body?.string() ?: return null
                val results = JSONObject(bodyStr).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val path = results.getJSONObject(0).optString("poster_path")
                    if (path.isNotEmpty() && path != "null") {
                        val thumb = "https://image.tmdb.org/t/p/w500$path"
                        preferences.edit().putString(cacheKey, thumb).apply()
                        thumb
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanTitleForTmdb(title: String): String {
        var t = title.replace(FILE_EXT_REGEX, "")
        t = t.replace(SEPARATOR_REGEX, " ")
        t = t.replace(EPISODE_S_E_REGEX, "")
        t = t.replace(SEASON_REGEX, "")
        t = t.replace(EPISODE_TEXT_REGEX, "")
        t = t.replace(YEAR_REGEX, "")
        t = t.replace(QUALITY_REGEX, "")
        t = t.replace(DASH_REGEX, "")
        return t.trim()
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).execute()
        return popularAnimeParse(response).also { enrichAnimes(it.animes) }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).execute()
        return latestUpdatesParse(response).also { enrichAnimes(it.animes) }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isEmpty()) return super.getSearchAnime(page, query, filters)

        val now = System.currentTimeMillis()
        if (searchCache.containsKey(query) && now - (cacheTime[query] ?: 0) < 1800000) {
            return AnimesPage(searchCache[query]!!, false)
        }

        val results = withContext(Dispatchers.IO) {
            val paths = mutableListOf("/$serverPath/")
            if (serverPath == "DHAKA-FLIX-9") {
                paths.add("/$serverPath/Anime & Cartoon TV Series/Anime-TV Series \u2665  A  \u2014  F/")
                paths.add("/$serverPath/Anime & Cartoon TV Series/Anime-TV Series \u2665  G  \u2014  M/")
                paths.add("/$serverPath/Anime & Cartoon TV Series/Anime-TV Series \u2666  N  \u2014  S/")
                paths.add("/$serverPath/Anime & Cartoon TV Series/Anime-TV Series \u2666  T  \u2014  Z/")
                paths.add("/$serverPath/Anime & Cartoon TV Series/Anime-TV Series \u2605  0  \u2014  9/")
                paths.add("/$serverPath/Anime & Cartoon Movies/")
            }
            if (serverPath == "DHAKA-FLIX-12") {
                paths.add("/$serverPath/TV-WEB-Series/TV Series \u2665  A  \u2014  L/")
                paths.add("/$serverPath/TV-WEB-Series/TV Series \u2666  M  \u2014  R/")
                paths.add("/$serverPath/TV-WEB-Series/TV Series \u2666  S  \u2014  Z/")
                paths.add("/$serverPath/TV-WEB-Series/TV Series \u2605  0  \u2014  9/")
                paths.add("/$serverPath/Hindi Movies/")
            }

            val deferredResults = paths.map { path ->
                async {
                    try {
                        searchSingleServer(baseUrl, serverPath, path, query)
                    } catch (e: Exception) {
                        emptyList<SAnime>()
                    }
                }
            }
            val allAnime = deferredResults.awaitAll().flatten().distinctBy { it.url }
            sortByTitle(collapseResults(allAnime), query)
        }

        if (results.isNotEmpty()) {
            searchCache[query] = results
            cacheTime[query] = now
        }

        return AnimesPage(results, false).also { enrichAnimes(it.animes) }
    }

    private fun collapseResults(list: List<SAnime>): List<SAnime> {
        val folders = list.filter { it.url.endsWith("/") }.map { it.url }.toSet()
        if (folders.isEmpty()) return list
        return list.filter {
            if (it.url.endsWith("/")) return@filter true
            folders.none { folderUrl -> it.url.startsWith(folderUrl) }
        }
    }

    private fun searchSingleServer(baseUrl: String, serverName: String, path: String, query: String): List<SAnime> {
        val searchUrl = "$baseUrl/$serverName/"
        val jsonPayload = JSONObject().apply {
            put("action", "get")
            put(
                "search",
                JSONObject().apply {
                    put("href", path)
                    put("pattern", query)
                    put("ignorecase", true)
                },
            )
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val fastClient = client.newBuilder()
            .readTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val response = try {
            fastClient.newCall(POST(searchUrl, headers, body)).execute()
        } catch (e: Exception) {
            return emptyList()
        }

        val bodyString = response.body?.string() ?: return emptyList()
        response.close()

        if (baseUrl.contains("172.16.50.7")) {
            val res = mutableListOf<SAnime>()
            Server7Parser.parseServer7Response(bodyString, baseUrl, serverName, res, query)
            return res.filter {
                it.title.startsWith(query, true) || diceCoefficient(it.title.lowercase(), query.lowercase()) > 0.15
            }
        }

        val results = mutableListOf<SAnime>()
        try {
            val json = JSONObject(bodyString)
            val searchArr = json.optJSONArray("search") ?: return emptyList()

            for (i in 0 until searchArr.length()) {
                val item = searchArr.getJSONObject(i)
                val href = item.getString("href").replace('\\', '/')
                val cleanHrefForTitle = href.trimEnd('/')
                val rawTitle = cleanHrefForTitle.substringAfterLast("/")
                val title = try {
                    URLDecoder.decode(rawTitle, "UTF-8").trim()
                } catch (e: Exception) {
                    rawTitle.trim()
                }

                if (title.isBlank() || isIgnored(title, query)) continue
                if (!title.startsWith(query, true) && diceCoefficient(title.lowercase(), query.lowercase()) < 0.15) continue

                val anime = SAnime.create().apply {
                    this.title = title
                    val finalHref = if (href.startsWith("/")) href else "/$href"
                    this.url = "$baseUrl$finalHref"
                    this.thumbnail_url = if (this.url.endsWith("/")) getFolderThumb(this.url) else ""
                }
                results.add(anime)
            }
        } catch (e: Exception) {}
        return results
    }

    private fun isIgnored(text: String, query: String = ""): Boolean {
        val ignored = listOf("Parent Directory", "modern browsers", "Name", "Last modified", "Size", "Description", "Index of", "JavaScript", "powered by", "_h5ai")
        if (ignored.any { text.contains(it, ignoreCase = true) }) return true

        if (query.isEmpty()) return false

        val uploaderTags = listOf("-Pahe", "-QxR", "-YIFY", "-RARBG")
        if (uploaderTags.any { text.endsWith(it, ignoreCase = true) || text.contains("$it.") || text.contains("$it ") }) {
            val cleanQuery = query.trim().removePrefix("-")
            if (cleanQuery.isNotEmpty() && uploaderTags.any { it.removePrefix("-").equals(cleanQuery, ignoreCase = true) }) {
                return false
            }
            return true
        }
        return false
    }

    private fun sortByTitle(list: List<SAnime>, query: String): List<SAnime> = list.sortedByDescending {
        var score = diceCoefficient(it.title.lowercase(), query.lowercase())
        if (it.title.startsWith(query, true)) score = 1.0
        if (it.url.endsWith("/")) score += 0.5
        score
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        val n1 = s1.length
        val n2 = s2.length
        if (n1 == 0 || n2 == 0) return 0.0
        val bigrams1 = HashSet<String>()
        for (i in 0 until n1 - 1) bigrams1.add(s1.substring(i, i + 2))
        var intersection = 0
        for (i in 0 until n2 - 1) {
            val bigram = s2.substring(i, i + 2)
            if (bigrams1.contains(bigram)) intersection++
        }
        return (2.0 * intersection) / (n1 + n2 - 2).coerceAtLeast(1)
    }

    private fun getFolderThumb(url: String): String {
        if (!url.endsWith("/")) return ""
        return fixUrl("$url$IMAGE_PROBE_MARKER")
    }

    override fun popularAnimeRequest(page: Int): Request {
        val path = when {
            baseUrl.contains("50.14") -> "$serverPath/Hindi%20Movies/(2026)/"
            baseUrl.contains("50.12") -> "$serverPath/TV-WEB-Series/TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20L/"
            baseUrl.contains("50.9") -> "$serverPath/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20F/"
            baseUrl.contains("50.7") -> "$serverPath/English%20Movies/(2026)/"
            else -> ""
        }
        return GET("$baseUrl/$path", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select("div.card")

        // Extract all image links from this page once
        val pageImageLinks = document.select("a").filter {
            val href = it.attr("href").lowercase()
            href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".png") || href.endsWith(".webp")
        }

        val animeList = if (cards.isNotEmpty()) {
            cards.mapNotNull { card ->
                val link = card.selectFirst("h5 a") ?: return@mapNotNull null
                SAnime.create().apply {
                    title = link.text().trim()
                    url = fixUrl(link.attr("abs:href"))
                    val thumbElement = card.selectFirst("img[src~=(?i)a11|a22|a4e|afull|a_al|a0_al|a_vl|a0_vl|a_v1], img:not([src~=(?i)back|parent|icon|/icons/|menu|nav|folder|fallback|/_h5ai/])")
                    val thumbUrl = thumbElement?.let {
                        it.attr("abs:data-src").ifEmpty { it.attr("abs:data-lazy-src").ifEmpty { it.attr("abs:src") } }
                    } ?: ""
                    thumbnail_url = if (thumbUrl.isNotEmpty()) formatThumbUrl(thumbUrl) else ""
                }
            }
        } else {
            document.select("a").mapNotNull { element ->
                val titleStr = element.text().trim()
                val href = element.attr("abs:href")
                if (titleStr.isNotEmpty() && !isIgnored(titleStr) && !href.contains("?") && !href.endsWith("../")) {
                    SAnime.create().apply {
                        title = if (titleStr.endsWith("/")) titleStr.dropLast(1) else titleStr
                        url = fixUrl(href)

                        // UNIVERSAL SMART DETECTION: Look for an image in the SAME directory
                        val currentDir = if (href.endsWith("/")) href else href.substringBeforeLast("/") + "/"
                        val foundThumb = pageImageLinks.find {
                            val imgHref = it.attr("abs:href")
                            imgHref.startsWith(currentDir) &&
                                imgHref.lowercase().contains(Regex("a11|a22|a4e|afull|a_al|a0_al|a_vl|a0_vl|a_v1"))
                        } ?: pageImageLinks.find { it.attr("abs:href").startsWith(currentDir) }

                        thumbnail_url = if (foundThumb != null) formatThumbUrl(foundThumb.attr("abs:href")) else getFolderThumb(url)
                    }
                } else {
                    null
                }
            }
        }
        runBlocking { enrichAnimes(animeList) }
        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("--- Category ---"),
        DhakaFlixSelect("Select Category", serverCategories),
        DhakaFlixSelect("Select Year", FilterData.YEARS),
        DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET),
        DhakaFlixSelect("Select Language", FilterData.LANGUAGES),
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotEmpty()) {
        GET("$baseUrl/$query", headers)
    } else {
        GET(fixUrl(Filters.getUrl(baseUrl, serverPath, filters)), headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (useTmdb) {
            try {
                fetchTmdbImage(anime.title)?.let { anime.thumbnail_url = it }
            } catch (e: Exception) {}
        }
        return anime
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(fixUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val html = document.select("script").html()
        val isMovie = html.contains("/m/lazyload/")

        return SAnime.create().apply {
            status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING
            genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
            description = document.selectFirst("p.storyline")?.text()?.trim() ?: ""

            // 1. Look for images already rendered as tags
            val thumbElement = document.selectFirst("img[src~=(?i)a11|a22|a4e|afull|a_al|a0_al|a_vl|a0_vl|a_v1], img:not([src~=(?i)back|parent|icon|/icons/|menu|nav|folder|fallback|/_h5ai/])")

            var thumbUrl = thumbElement?.let {
                it.attr("abs:data-src").ifEmpty { it.attr("abs:data-lazy-src").ifEmpty { it.attr("abs:src") } }
            } ?: ""

            // 2. TRUE SMART DETECTION: Look for thumbnail files in the links list (h5ai fallback)
            if (thumbUrl.isEmpty()) {
                val allLinks = document.select("a")

                // Try to find known good names first
                val foundThumb = allLinks.find {
                    val href = it.attr("href").lowercase()
                    href.contains(Regex("a11|a22|a4e|afull|a_al|a0_al|a_vl|a0_vl|a_v1")) &&
                        (href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".png") || href.endsWith(".webp"))
                }

                // If no known name, just take the FIRST image link available (excluding icons/parent)
                val firstAnyImage = if (foundThumb == null) {
                    allLinks.find {
                        val href = it.attr("href").lowercase()
                        (href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".png") || href.endsWith(".webp")) &&
                            !href.contains(Regex("parent|icon|menu|nav|/_h5ai/"))
                    }
                } else {
                    null
                }

                // USE abs:href DIRECTLY - Do NOT pass through fixUrl to avoid re-encoding issues
                thumbUrl = (foundThumb ?: firstAnyImage)?.attr("abs:href") ?: ""
            }

            // 3. Last resort - fallback guessing
            if (thumbUrl.isEmpty()) {
                thumbUrl = document.selectFirst("""a[href~=(?i)\.(jpg|jpeg|png|webp)]:not([href~=(?i)back|parent|icon|menu])""")?.attr("abs:href") ?: ""
            }
            if (thumbUrl.isEmpty() && response.request.url.toString().endsWith("/")) {
                thumbUrl = getFolderThumb(response.request.url.toString())
            }

            thumbnail_url = if (thumbUrl.isNotEmpty()) formatThumbUrl(thumbUrl) else ""
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(45000) {
            val document = client.newCall(GET(fixUrl(anime.url), headers)).execute().asJsoup()
            val html = document.select("script").html()
            val episodes = when {
                html.contains("/m/lazyload/") -> getMovieMedia(document)

                html.contains("/s/lazyload/") -> {
                    val extracted = extractEpisodes(document)
                    val seasonLinks = document.select("""a[href*=/s/lazyload/], a[href*=dir=], a:matches((?i)Season|Special)""").map { it.attr("abs:href") }.distinct()
                    if (seasonLinks.size > 1) {
                        val deferredEpisodes = seasonLinks.map {
                            async {
                                try {
                                    val seasonDoc = client.newCall(GET(fixUrl(it), headers)).execute().asJsoup()
                                    val seasonExtracted = extractEpisodes(seasonDoc)
                                    if (seasonExtracted.isNotEmpty()) {
                                        sortEpisodes(seasonExtracted)
                                    } else {
                                        parseDir(it, 2, seasonDoc)
                                    }
                                } catch (e: Exception) {
                                    emptyList<SEpisode>()
                                }
                            }
                        }
                        deferredEpisodes.awaitAll().flatten().distinctBy { it.url }
                    } else if (extracted.isNotEmpty()) {
                        sortEpisodes(extracted)
                    } else {
                        parseDirectoryRecursive(document)
                    }
                }

                else -> parseDirectoryRecursive(document)
            }
            if (episodes.isEmpty()) throw Exception("No results found")
            episodes.distinctBy { it.url }
        } ?: emptyList()
    }

    private fun extractEpisodes(document: Document): List<EpisodeData> {
        return document.select("div.card, div.episode-item, div.download-link").mapNotNull { element ->
            val titleElement = element.selectFirst("h5") ?: return@mapNotNull null
            val rawName = titleElement.ownText()
            val name = rawName.split("&nbsp;", "\u00A0").first().trim()
            val url = titleElement.selectFirst("a")?.attr("abs:href") ?: ""
            val q = element.selectFirst("h5 .badge-fill")?.text()?.let {
                Regex("""(\d+\.\d+ [GM]B|\d+ [GM]B).*""").replace(it, "$1")
            } ?: ""
            val episodeName = element.selectFirst("h4")?.ownText()?.trim() ?: ""
            val size = element.selectFirst("h4 .badge-outline")?.text()?.trim() ?: ""
            if (name.isNotEmpty() && url.isNotEmpty()) EpisodeData(name, url, q, episodeName, size) else null
        }
    }

    private fun getMovieMedia(document: Document): List<SEpisode> {
        val url = document.select("div.col-md-12 a.btn, .movie-buttons a, a[href*=/m/lazyload/], a[href*=/s/lazyload/], .download-link a").lastOrNull()?.attr("abs:href")?.replace(" ", "%20") ?: ""
        val q = document.select(".badge-wrapper .badge-fill").lastOrNull()?.text()?.replace("|", "")?.trim() ?: ""
        return listOf(
            SEpisode.create().apply {
                this.url = url
                this.name = "Movie"
                this.episode_number = 1f
                this.scanlator = q
            },
        )
    }

    private val semaphore = Semaphore(5)
    private suspend fun parseDirectoryRecursive(document: Document): List<SEpisode> = parseDir(document.location(), 4, document)

    private suspend fun parseDir(url: String, depth: Int, initialDoc: Document? = null): List<SEpisode> {
        if (depth < 0) return emptyList()
        val doc = initialDoc ?: try {
            client.newCall(GET(url, headers)).execute().asJsoup()
        } catch (e: Exception) {
            return emptyList()
        }
        val currentHttpUrl = doc.location().toHttpUrlOrNull() ?: return emptyList()
        val fileEpisodes = mutableListOf<SEpisode>()
        val subDirs = mutableListOf<String>()
        doc.select("a").forEach { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            if (href.contains("..") || href.startsWith("?") || isIgnored(text)) return@forEach
            val absUrl = currentHttpUrl.resolve(href)?.toString() ?: return@forEach
            if (isVideoFile(href)) {
                fileEpisodes.add(
                    SEpisode.create().apply {
                        this.url = absUrl
                        val decodedName = try {
                            URLDecoder.decode(text, "UTF-8")
                        } catch (e: Exception) {
                            text
                        }
                        this.name = decodedName
                        this.episode_number = parseEpisodeNumber(decodedName)
                    },
                )
            } else if (href.endsWith("/") || absUrl.endsWith("/")) {
                subDirs.add(absUrl)
            }
        }

        val subDirEpisodes = coroutineScope {
            subDirs.map { async(Dispatchers.IO) { semaphore.withPermit { parseDir(it, depth - 1) } } }.awaitAll().flatten()
        }
        return (fileEpisodes + subDirEpisodes).distinctBy { it.url }.sortedBy { it.name }.reversed()
    }

    private fun isVideoFile(href: String): Boolean {
        val h = href.lowercase()
        return listOf(".mkv", ".mp4", ".avi", ".ts", ".m4v", ".webm", ".mov").any { h.endsWith(it) || h.contains("$it?") }
    }

    private fun sortEpisodes(list: List<EpisodeData>): List<SEpisode> = list.sortedWith(compareBy<EpisodeData> { parseEpisodeNumber(it.seasonEpisode) }.thenBy { it.seasonEpisode }).map {
        SEpisode.create().apply {
            url = it.videoUrl
            name = if (it.seasonEpisode.isNotEmpty()) "${it.seasonEpisode} - ${it.episodeName}".trim() else it.episodeName
            episode_number = parseEpisodeNumber(it.seasonEpisode)
            scanlator = "${it.quality} ${it.size}".trim()
        }
    }.reversed()

    private fun parseEpisodeNumber(text: String): Float = try {
        val res = Regex("""(?i)(?:Episode|Ep|E|Vol)\.?\s*(\d+(\.\d+)?)""").find(text)
        if (res != null) {
            res.groupValues[1].toFloatOrNull() ?: 0f
        } else {
            Regex("""(\d+(\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        }
    } catch (e: Exception) {
        0f
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = fixUrl(episode.url)
        val httpUrl = url.toHttpUrlOrNull()
        val referer = httpUrl?.let { "${it.scheme}://${it.host}/" } ?: "$baseUrl/"
        return listOf(Video(videoUrl = url, videoTitle = "Video", headers = headersBuilder().add("Referer", referer).build()))
    }
    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")
    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    data class EpisodeData(val seasonEpisode: String, val videoUrl: String, val quality: String, val episodeName: String, val size: String)
}

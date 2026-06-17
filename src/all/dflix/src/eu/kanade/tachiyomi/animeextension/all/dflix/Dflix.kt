package eu.kanade.tachiyomi.animeextension.all.dflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
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
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class Dflix : Source() {

    override val name = "Dflix"

    override val baseUrl = "https://dflix.discoveryftp.net"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 5181466391484419844L

    private val baseClient: OkHttpClient = super.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override val client: OkHttpClient = baseClient.newBuilder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 100
                maxRequestsPerHost = 100
            },
        )
        .addInterceptor { chain ->
            val request = chain.request()
            var response = chain.proceed(request)

            val url = response.request.url.encodedPath
            var isLoginPage = url.contains("login/destroy") || url.contains("login/index") || url.contains("login/demo")

            var needsRefresh = isLoginPage
            if (!needsRefresh && response.isSuccessful && request.header("X-Dflix-Retry") == null) {
                val bodyString = response.peekBody(1024 * 20).string()
                if (bodyString.contains("login/demo") && !bodyString.contains("login/destroy")) {
                    needsRefresh = true
                }
            }

            if (needsRefresh && request.header("X-Dflix-Retry") == null) {
                response.close()
                cm.refreshCookies()
                val newRequest = request.newBuilder()
                    .removeHeader("Cookie")
                    .addHeader("Cookie", cm.getCookiesHeaders())
                    .addHeader("X-Dflix-Retry", "true")
                    .build()
                response = chain.proceed(newRequest)
            }
            response
        }
        .build()

    private val cm by lazy { CookieManager(baseClient) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "*/*")
        .add("Referer", "$baseUrl/")
        .add("X-Requested-With", "XMLHttpRequest")
        .apply {
            val cookies = cm.getCookiesHeaders()
            if (cookies.isNotEmpty()) {
                add("Cookie", cookies)
            }
        }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        val u = url.trim().replace(" ", "%20")
        val fullUrl = if (u.startsWith("http")) {
            u
        } else if (u.startsWith("/")) {
            "$baseUrl$u"
        } else {
            "$baseUrl/$u"
        }
        return highResUrl(fullUrl)
    }

    private fun highResUrl(url: String): String {
        // DiscoveryFTP uses several patterns for images:
        // 1. imagesX.discoveryftp.net/media/{size}/...
        // 2. ext-img.discoveryftp.net/{size}//...
        // 55, 100, 300 are blurry. 1080 is supported across all and is sharp.
        return url.replace(Regex("""/media/\d+/"""), "/media/1080/")
            .replace(Regex("""discoveryftp\.net/\d+//"""), "discoveryftp.net/1080//")
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = withContext(Dispatchers.IO) {
        if (query.isNotEmpty()) {
            val movies = fetchAnimeByType(query, "m")
            val series = fetchAnimeByType(query, "s")
            val combined = sortByTitle(movies + series, query)
            AnimesPage(combined, false)
        } else {
            val url = Filters.getUrl(query, filters, page)
            val response = client.newCall(GET(fixUrl(url), headers)).execute()
            popularAnimeParse(response)
        }
    }

    private fun sortByTitle(list: List<SAnime>, query: String): List<SAnime> = list.sortedByDescending { diceCoefficient(it.title.lowercase(), query.lowercase()) }

    private fun diceCoefficient(s1: String, s2: String): Double {
        if (s1.length < 2 || s2.length < 2) return 0.0
        val s1Bigrams = HashSet<String>()
        for (i in 0 until s1.length - 1) {
            s1Bigrams.add(s1.substring(i, i + 2))
        }
        var intersect = 0
        for (i in 0 until s2.length - 1) {
            val bigram = s2.substring(i, i + 2)
            if (s1Bigrams.remove(bigram)) {
                intersect++
            }
        }
        return 2.0 * intersect / (s1.length + s2.length - 2)
    }

    private suspend fun fetchAnimeByType(query: String, type: String): List<SAnime> {
        val formBody = okhttp3.FormBody.Builder()
            .add("term", query)
            .add("types", type)
            .build()

        val request = okhttp3.Request.Builder()
            .url("$baseUrl/search")
            .post(formBody)
            .headers(headers)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val document = response.asJsoup()
            response.close()

            document.select("div.moviesearchiteam a, a:has(div.fcard), a:has(div.card), div.card a").map { element ->
                val card = element.selectFirst("div.p-1, div.fcard, div.card") ?: element
                SAnime.create().apply {
                    url = fixUrl(element.attr("href"))
                    thumbnail_url = fixUrl(element.selectFirst("img")?.attr("src") ?: "")
                    var titleText = card.selectFirst("div.searchtitle, div.ftitle, h3, .ftitle")?.text()
                        ?: element.selectFirst(".ftitle, .searchtitle")?.text()
                        ?: "Unknown"
                    if (type == "m") {
                        val details = card.selectFirst("div.searchdetails, div.fdetails")?.text() ?: ""
                        if (details.contains("4K", ignoreCase = true)) {
                            titleText += " 4K"
                        }
                    }
                    title = titleText
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(fixUrl(Filters.getUrl(query, filters, page)), headers)

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/m/recent/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.card a.cfocus, a:has(div.fcard), a:has(div.card), div.moviesearchiteam a")
            .map { animeFromElement(it) }
            .filter { it.title != "Unknown" }

        val hasNextPage = document.select("a.page-link:contains(Next)").isNotEmpty() ||
            (animeList.size >= 20 && !response.request.url.encodedPath.contains("/search"))

        return AnimesPage(animeList, hasNextPage)
    }

    private fun animeFromElement(element: Element): SAnime {
        val card = element.selectFirst("div.fcard, div.card, div.details") ?: element.parent()
        return SAnime.create().apply {
            var titleText = card?.selectFirst("div.details h3, div.ftitle, h3, .ftitle")?.text() ?: "Unknown"
            val poster = element.selectFirst("div.poster, div.fcard, .card, img")
            if (poster != null) {
                val attrTitle = poster.attr("title")
                if (attrTitle.contains("4K", ignoreCase = true)) {
                    titleText += " 4K"
                }
            }
            title = titleText
            url = fixUrl(element.attr("href"))
            thumbnail_url = fixUrl(element.selectFirst("img")?.attr("src") ?: "")
        }
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {}

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.encodedPath

        // 1. Get first 12 cast members
        val castLinks = document.select("a[href*=/people/index/]")
            .map { it.attr("abs:href") }
            .distinct()
            .take(12)

        if (castLinks.isEmpty()) {
            // Fallback to basic selector if no cast found
            return document.select("#movie-related a, div.row:has(h3:contains(Similar)) a, div.row:has(h3:contains(Related)) a, .moviesearchiteam a")
                .map { animeFromElement(it) }
                .filter { it.title != "Unknown" }
                .distinctBy { it.url }
        }

        // 2. Fetch all cast pages in parallel and aggregate movies they worked on
        val relatedItems = runBlocking(Dispatchers.IO) {
            castLinks.map { url ->
                async {
                    try {
                        val personDoc = client.newCall(GET(url, headers)).execute().asJsoup()
                        personDoc.select("h3:contains(Also Worked On) + div.row a[href*=/view/]")
                            .map { element ->
                                animeFromElement(element)
                            }
                    } catch (e: Exception) {
                        emptyList<SAnime>()
                    }
                }
            }.awaitAll().flatten()
        }

        // 3. Find "similarities" (items that appear most frequently across different cast members)
        return relatedItems
            .filter { it.title != "Unknown" && !it.url.contains(currentUrl) }
            .groupBy { it.url }
            .toList()
            .sortedByDescending { it.second.size } // Most common items first
            .map { it.second.first() } // Unique items
            .take(24)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList() = Filters.getFilterList()

    override fun animeDetailsRequest(anime: SAnime): Request = GET(fixUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val mediaType = getMediaType(document)

        return if (mediaType == "m") getMovieDetails(document) else getSeriesDetails(document)
    }

    private fun getMediaType(document: Document): String {
        val html = document.select("script").html()
        return if (html.contains("/m/lazyload/") || html.contains("/m/lazy_load/")) "m" else "s"
    }

    private fun getMovieDetails(document: Document) = SAnime.create().apply {
        status = SAnime.COMPLETED
        thumbnail_url = highResUrl(document.selectFirst("figure.movie-detail-banner img")?.attr("abs:src")?.replace(" ", "%20") ?: "")
        genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
        description = document.selectFirst("p.storyline")?.text()?.trim() ?: ""
    }

    private fun getSeriesDetails(document: Document) = SAnime.create().apply {
        status = SAnime.ONGOING
        thumbnail_url = highResUrl(document.selectFirst("div.movie-detail-banner img")?.attr("abs:src")?.replace(" ", "%20") ?: "")
        genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
        description = document.selectFirst("p.storyline")?.text()?.trim() ?: ""
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        val response = client.newCall(GET(fixUrl(anime.url), headers)).execute()
        val document = response.asJsoup()
        val mediaType = getMediaType(document)

        if (mediaType == "m") {
            getMovieMedia(document)
        } else {
            val seasonLinks = document.select("tbody tr th.card a[href^='/s/view/']")
                .map { it.attr("href") }
                .reversed()

            val episodes = if (seasonLinks.isEmpty()) {
                extractEpisodes(document)
            } else {
                val semaphore = Semaphore(3)
                coroutineScope {
                    val allLinks = (listOf(anime.url) + seasonLinks).distinct()
                    allLinks.map { link ->
                        async {
                            semaphore.withPermit {
                                val res = client.newCall(GET(fixUrl(link), headers)).execute()
                                val doc = res.asJsoup()
                                res.close()
                                extractEpisodes(doc)
                            }
                        }
                    }.awaitAll().flatten()
                }
            }
            sortEpisodes(episodes)
        }
    }

    private fun extractEpisodes(document: Document): List<EpisodeData> {
        return document.select("div.card").mapNotNull { element ->
            val titleElement = element.selectFirst("h5") ?: return@mapNotNull null
            val rawTitle = titleElement.ownText().trim()
            if (rawTitle.isEmpty()) return@mapNotNull null

            val seasonEpisode = rawTitle.split("&nbsp;").first().trim()
            val url = element.selectFirst("h5 a")?.attr("href")?.trim() ?: ""
            val qualityText = element.selectFirst("h5 .badge-fill")?.text() ?: ""
            val quality = sizeRegex.replace(qualityText, "$1").trim()
            val epName = element.selectFirst("h4")?.ownText()?.trim() ?: ""
            val size = element.selectFirst("h4 .badge-outline")?.text()?.trim() ?: ""

            if (seasonEpisode.isNotEmpty() && url.isNotEmpty()) {
                EpisodeData(seasonEpisode, url, quality, epName, size)
            } else {
                null
            }
        }
    }

    private fun getMovieMedia(document: Document): List<SEpisode> {
        val linkElement = document.select("div.col-md-12 a.btn").lastOrNull()
        val url = linkElement?.attr("href")?.let { it.replace(" ", "%20") } ?: ""
        val quality = document.select(".badge-wrapper .badge-fill").lastOrNull()?.text()?.replace("|", "•")?.trim() ?: ""

        return listOf(
            SEpisode.create().apply {
                this.url = url
                this.name = "Movie"
                this.episode_number = 1f
                this.scanlator = quality
            },
        )
    }

    private fun sortEpisodes(list: List<EpisodeData>): List<SEpisode> {
        val distinctList = list.distinctBy { it.videoUrl }
        return distinctList.sortedWith(
            compareByDescending<EpisodeData> { it.seasonNumber }
                .thenByDescending { it.episodeNumber },
        ).mapIndexed { index, it ->
            SEpisode.create().apply {
                url = it.videoUrl
                name = "${it.seasonEpisode} - ${it.episodeName}".trim()
                episode_number = (distinctList.size - index).toFloat()
                scanlator = "${it.quality}  •  ${it.size}"
            }
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // Only pass UA and Referer to avoid player incompatibility with complex headers
        val videoHeaders = Headers.Builder()
            .add("User-Agent", headers["User-Agent"]!!)
            .add("Referer", "$baseUrl/")
            .add("Cookie", cm.getCookiesHeaders())
            .build()
        return listOf(Video(videoUrl = fixUrl(episode.url), videoTitle = "Video", headers = videoHeaders))
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")
    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    data class EpisodeData(
        val seasonEpisode: String,
        val videoUrl: String,
        val quality: String,
        val episodeName: String,
        val size: String,
    ) {
        val seasonNumber: Int = SEASON_PATTERN.find(seasonEpisode)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val episodeNumber: Int = EPISODE_PATTERN.find(seasonEpisode)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    class CookieManager(private val client: OkHttpClient) {
        private val cookieUrl = "https://dflix.discoveryftp.net/login/demo".toHttpUrl()

        @Volatile
        private var cookies: List<Cookie> = emptyList()
        private val lock = Any()

        fun getCookiesHeaders(): String {
            if (cookies.isEmpty()) {
                synchronized(lock) {
                    if (cookies.isEmpty()) {
                        cookies = fetchCookies()
                    }
                }
            }
            return cookies.joinToString("; ") { "${it.name}=${it.value}" }
        }

        fun refreshCookies() {
            synchronized(lock) {
                cookies = fetchCookies()
            }
        }

        private fun fetchCookies(): List<Cookie> {
            val jar = object : okhttp3.CookieJar {
                val cookieList = mutableListOf<Cookie>()
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookieList.addAll(cookies)
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
            }

            val cookieClient = client.newBuilder()
                .followRedirects(true)
                .cookieJar(jar)
                .build()

            val req = Request.Builder()
                .url(cookieUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://dflix.discoveryftp.net/")
                .build()

            return try {
                val res = cookieClient.newCall(req).execute()
                res.close()
                jar.cookieList.distinctBy { it.name }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    companion object {
        private val SEASON_PATTERN = Regex("""S(\d+)""")
        private val EPISODE_PATTERN = Regex("""EP (\d+)""")
        private val sizeRegex = Regex(""".*\s(\d+\.\d+\s+MB)$""")
    }
}

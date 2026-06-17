package eu.kanade.tachiyomi.animeextension.all.iccftp

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import extensions.utils.addEditTextPreference
import extensions.utils.delegate
import extensions.utils.get
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class IccFtp :
    Source(),
    ConfigurableAnimeSource {

    override val name = "ICC FTP"
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 84726193058274619L

    override val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String by preferences.delegate("session_id", "")

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

    private val sessionInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val originalUrl = originalRequest.url
        val urlString = originalUrl.toString()

        val request = originalRequest.newBuilder()
            .apply {
                val cookie = cm.getCookiesHeaders(urlString)
                removeHeader("User-Agent")
                addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                if (cookie.isNotBlank()) {
                    removeHeader("Cookie")
                    addHeader("Cookie", cookie)
                }
            }
            .build()

        if (originalUrl.encodedPath == "/" && originalUrl.querySize == 0) {
            return@Interceptor chain.proceed(request)
        }

        val newUrl = if (originalUrl.queryParameter("session") == null && !originalUrl.encodedPath.contains("command.php")) {
            if (sessionId.isBlank()) fetchSessionId()
            request.url.newBuilder().addQueryParameter("session", sessionId).build()
        } else {
            request.url
        }

        val finalRequest = request.newBuilder().url(newUrl).build()
        val response = chain.proceed(finalRequest)

        if (response.request.url.encodedPath.contains("vfw.php") || (response.code == 302 && response.header("Location")?.contains("vfw.php") == true)) {
            response.close()
            sessionId = ""
            fetchSessionId()
            val retryUrl = originalUrl.newBuilder().setQueryParameter("session", sessionId).build()
            return@Interceptor chain.proceed(request.newBuilder().url(retryUrl).build())
        }

        response
    }

    override val client: OkHttpClient = unsafeBaseClient.newBuilder()
        .addInterceptor(sessionInterceptor)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Synchronized
    private fun fetchSessionId() {
        if (sessionId.isNotBlank()) return
        try {
            val response = unsafeBaseClient.newCall(GET(baseUrl)).execute()
            val finalUrl = response.request.url
            response.close()
            val session = finalUrl.queryParameter("session")
            if (session != null) sessionId = session
        } catch (e: Exception) {}
    }

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

    // Popular = Top Slider items (Page 1 only)
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page > 1) return AnimesPage(emptyList(), false)
        val response = client.newCall(GET("$baseUrl/dashboard.php?category=0")).execute()
        val doc = response.asJsoup()
        val items = doc.select("div#post-slider-multipost div.item")
        val animeList = items.mapNotNull { item ->
            val link = item.parent() ?: return@mapNotNull null
            val url = link.attr("href")
            val imgStyle = item.selectFirst("div.img")?.attr("style") ?: ""
            val imgSrc = imgStyle.substringAfter("url('").substringBefore("')")
            val title = item.selectFirst("div.title span")?.text()

            if (url.isNotEmpty() && !title.isNullOrEmpty()) {
                SAnime.create().apply {
                    this.url = if (url.contains("session=")) {
                        url
                    } else if (url.contains("?")) {
                        "$url&session=$sessionId"
                    } else {
                        "$url?session=$sessionId"
                    }
                    this.title = title
                    this.thumbnail_url = if (imgSrc.isNotEmpty()) "$baseUrl/$imgSrc" else null
                }
            } else {
                null
            }
        }
        return AnimesPage(animeList, false)
    }

    // Latest = Grid items with working infinite scroll
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = if (page == 1) {
            GET("$baseUrl/dashboard.php?category=0")
        } else {
            val body = FormBody.Builder()
                .add("cpage", page.toString())
                .add("category", "0")
                .build()
            POST("$baseUrl/command.php", body = body)
        }
        val response = client.newCall(request).execute()
        return parseAnimeList(response.asJsoup(), true)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            if (page > 1) return AnimesPage(emptyList(), false)
            val mediaType = "application/x-www-form-urlencoded".toMediaType()
            val body = "cSearch=$query".toRequestBody(mediaType)
            val response = client.newCall(POST("$baseUrl/command.php", body = body)).execute()
            val searchJson = try {
                response.parseAs<List<SearchJsonItem>>(json)
            } catch (e: Exception) {
                emptyList()
            }
            val animeList = searchJson.map { item ->
                SAnime.create().apply {
                    this.url = "player.php?play=${item.id}&session=$sessionId"
                    this.title = item.name ?: ""
                    this.thumbnail_url = "$baseUrl/files/${item.image}"
                }
            }
            return AnimesPage(animeList, false)
        }

        var category = "0"
        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> if (filter.toValue() != "0") category = filter.toValue()
                is TVShowFilter -> if (filter.toValue() != "0") category = filter.toValue()
                is OtherFilter -> if (filter.toValue() != "0") category = filter.toValue()
                else -> {}
            }
        }

        // Use dashboard.php for categories
        val request = if (page == 1) {
            GET("$baseUrl/dashboard.php?category=$category")
        } else {
            val body = FormBody.Builder()
                .add("cpage", page.toString())
                .add("category", category)
                .build()
            POST("$baseUrl/command.php", body = body)
        }
        val response = client.newCall(request).execute()
        return parseAnimeList(response.asJsoup(), true)
    }

    private fun parseAnimeList(document: Document, hasNext: Boolean): AnimesPage {
        val items = document.select("div.post, div.post-item, div.post-wrapper > a, div.item > a")
        val animeList = mutableListOf<SAnime>()
        val seenUrls = mutableSetOf<String>()

        items.forEach { item ->
            val url = if (item.tagName() == "a") item.attr("href") else item.selectFirst("a")?.attr("href") ?: ""
            if (url.isBlank()) return@forEach

            val normalizedUrl = url.substringBefore("&session=").substringBefore("?session=")
            if (seenUrls.contains(normalizedUrl)) return@forEach

            // Skip slider items if present in the document
            if (item.parents().any { it.id() == "post-slider-multipost" }) return@forEach

            val img = item.selectFirst("img") ?: item.selectFirst("div.img")
            val title = img?.attr("alt") ?: item.selectFirst("div.title")?.text() ?: item.attr("title") ?: item.selectFirst("span")?.text()

            if (!title.isNullOrEmpty()) {
                animeList.add(
                    SAnime.create().apply {
                        this.url = if (url.contains("session=")) {
                            url
                        } else if (url.contains("?")) {
                            "$url&session=$sessionId"
                        } else {
                            "$url?session=$sessionId"
                        }
                        this.title = title
                        val imgSrc = img?.attr("src") ?: img?.attr("style")?.substringAfter("url('")?.substringBefore("')")
                        this.thumbnail_url = if (imgSrc != null) {
                            if (imgSrc.startsWith("http")) imgSrc else "$baseUrl/$imgSrc"
                        } else {
                            null
                        }
                    },
                )
                seenUrls.add(normalizedUrl)
            }
        }
        return AnimesPage(animeList, hasNext && animeList.isNotEmpty())
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = if (anime.url.contains("session=")) {
            anime.url
        } else if (anime.url.contains("?")) {
            "${anime.url}&session=$sessionId"
        } else {
            "${anime.url}?session=$sessionId"
        }
        val doc = client.newCall(GET("$baseUrl/$url")).execute().asJsoup()
        val table = doc.select(".table > tbody:nth-child(1)")
        return anime.apply {
            description = table.select("tr:contains(Plot) td:last-child").text()
            genre = table.select("tr:contains(Genre) td:last-child").text()
            author = table.select("tr:nth-child(1)").text()
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = if (anime.url.contains("session=")) {
            anime.url
        } else if (anime.url.contains("?")) {
            "${anime.url}&session=$sessionId"
        } else {
            "${anime.url}?session=$sessionId"
        }
        val doc = client.newCall(GET("$baseUrl/$url")).execute().asJsoup()
        val downloadEpisode = doc.select(".btn-group > ul > li")

        if (downloadEpisode.isEmpty()) {
            val videoLink = doc.selectFirst("video source")?.attr("src")
                ?: doc.selectFirst("a.btn:contains(DOWNLOAD)")?.attr("href")
                ?: doc.selectFirst("a.btn")?.attr("href") ?: ""

            return listOf(
                SEpisode.create().apply {
                    name = "Play Movie"
                    this.url = videoLink
                    episode_number = 1F
                },
            )
        }

        return downloadEpisode.mapIndexed { index, it ->
            val link = it.select("a").attr("href")
            val nameText = it.select("a").text()
            val span = it.select("span").text()
            SEpisode.create().apply {
                this.name = nameText.replace(span, "").trim()
                this.url = link
                this.episode_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        if (episode.url.isBlank()) throw IOException("Video URL is empty")
        val videoUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl/${episode.url}"
        return listOf(Video(videoUrl = videoUrl, videoTitle = "Direct"))
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Use only one filter at a time"),
        CategoryFilter(),
        TVShowFilter(),
        OtherFilter(),
    )

    private open class SelectFilter(name: String, val items: Array<Pair<String, String>>) : AnimeFilter.Select<String>(name, items.map { it.first }.toTypedArray()) {
        fun toValue() = items[state].second
    }

    private class CategoryFilter :
        SelectFilter(
            "Movies",
            arrayOf(
                "None" to "0", "3D" to "9", "4K" to "74", "Animated" to "33", "Anime" to "83", "Bangla (BD)" to "59",
                "Bangla (Kolkata)" to "60", "Chinese" to "76", "Documentaries" to "41", "Dual Audio" to "43",
                "English" to "19", "Exclusive Full-HD" to "44", "Hindi" to "2", "Indonesian" to "79",
                "Japanese" to "80", "Korean" to "75", "Other Foreign" to "64", "Pakistani" to "77",
                "Punjabi" to "71", "South Indian (Hindi Dub)" to "32", "South Indian" to "73",
            ),
        )

    private class TVShowFilter :
        SelectFilter(
            "TV Shows",
            arrayOf(
                "None" to "0", "Awards" to "38", "Bangla Drama" to "34", "Bangla Telefilm" to "35",
                "Serials (Animation)" to "82", "Serials (Anime)" to "78", "Serials (Bangla)" to "39",
                "Serials (Documentaries)" to "81", "Serials (Dual Audio)" to "72", "Serials (English)" to "36",
                "Serials (Hindi)" to "37", "Serials (Others)" to "70", "WWE" to "52",
            ),
        )

    private class OtherFilter :
        SelectFilter(
            "Others",
            arrayOf(
                "None" to "0", "E-Books" to "68", "Kids (Cartoon)" to "66", "Learning" to "53",
            ),
        )

    @Serializable data class SearchJsonItem(val id: String? = null, val image: String? = null, val name: String? = null)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addEditTextPreference(
            key = PREF_DOMAIN_KEY,
            default = PREF_DOMAIN_DEFAULT,
            title = "Base URL",
            summary = "The base URL for the ICC FTP server",
            getSummary = { it },
            validate = { it.startsWith("http://") || it.startsWith("https://") },
            validationMessage = { "The URL must start with http:// or https://" },
        )
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "http://10.16.100.244"
    }
}

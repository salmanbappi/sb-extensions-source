package eu.kanade.tachiyomi.animeextension.en.zinkmovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

class ZinkMovies : Source() {

    override val name = "ZinkMovies"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // Shared Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/trending/page/$page/" else "$baseUrl/trending/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/movies/page/$page/" else "$baseUrl/movies/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/page/$page/?s=$encodedQuery"
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimeListPage(response, page)
        }

        var categoryPath = ""
        for (filter in filters) {
            when (filter) {
                is Filters.CategoryFilter -> {
                    if (!filter.isDefault()) {
                        categoryPath = filter.toUriPart()
                    }
                }
                else -> {}
            }
        }

        val targetPath = categoryPath.ifBlank { "movies" }
        val url = if (page > 1) "$baseUrl/$targetPath/page/$page/" else "$baseUrl/$targetPath/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.CategoryFilter(),
    )

    private fun parseAnimeListPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article.item, div.items article, article[id^=post-]").mapNotNull { element ->
            val linkEl = element.selectFirst("div.poster a, .data h3 a, h3 a, a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@mapNotNull null

            val imgEl = element.selectFirst("div.poster img, img")
            val imgUrl = imgEl?.attr("data-lazy-src")?.ifEmpty { imgEl.attr("abs:src").ifEmpty { imgEl.attr("src") } }

            val rawTitle = imgEl?.attr("alt")?.ifEmpty { linkEl.text() } ?: linkEl.text()
            val cleanTitle = cleanAnimeTitle(rawTitle)

            SAnime.create().apply {
                title = cleanTitle
                setUrlWithoutDomain(href)
                thumbnail_url = imgUrl
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.select(".horizontal-pagination a.next-page, .pagination a.next-page, a.next-page, a.page-numbers:contains(${page + 1})").isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    private fun cleanAnimeTitle(title: String): String {
        return title
            .replace(Regex("""\s*\{[^}]*\}"""), "")
            .replace(Regex("""\s*(Dual Audio|Multi Audio|Hindi Dubbed|Hindi Movie|CR WEB-DL|WEB-DL|BluRay|HDTC|ESubs|MSubs|NF).*""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { title.trim() }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        val rawTitle = doc.selectFirst("div.data h1, h1")?.text() ?: anime.title
        val posterEl = doc.selectFirst("div.poster img, .sheader .poster img, img[itemprop=image]")
        val posterUrl = posterEl?.attr("data-lazy-src")?.ifEmpty { posterEl.attr("abs:src").ifEmpty { posterEl.attr("src") } }

        val synopsis = doc.selectFirst("div.wp-content, div[itemprop=description], #info .wp-content")?.text()
            ?.substringBefore("Multi Audio")
            ?.substringBefore("Dual Audio")
            ?.substringBefore("Download")
            ?.trim() ?: ""

        val ratingText = doc.selectFirst(".starstruck-rating span.dt_rating_vgs, span[itemprop=ratingValue]")?.text()
        val score = ratingText?.toDoubleOrNull()
        val releaseDate = doc.selectFirst("span.date, span[itemprop=dateCreated]")?.text() ?: ""
        val country = doc.selectFirst("span.country")?.text() ?: ""

        return SAnime.create().apply {
            title = cleanAnimeTitle(rawTitle)
            thumbnail_url = posterUrl ?: anime.thumbnail_url
            genre = doc.select("div.sgeneros a, .genres a").joinToString { it.text() }
            status = SAnime.COMPLETED
            initialized = true

            description = buildString {
                if (score != null && score > 0.0) {
                    val full = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(score)}\n\n")
                }
                if (synopsis.isNotBlank()) append("$synopsis\n\n")
                if (releaseDate.isNotBlank()) append("Released: $releaseDate\n")
                if (country.isNotBlank()) append("Country: $country\n")
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val episodes = mutableListOf<SEpisode>()

        // 1. Check for LinkStore batch buttons (TV Shows)
        val linkStoreButtons = doc.select("a[href*=linkstore.zinkcloud.net], a[href*=linkstore.]")

        if (linkStoreButtons.isNotEmpty()) {
            val seasonEpisodes = linkStoreButtons.parallelCatchingFlatMap { btn ->
                val linkStoreUrl = btn.attr("href")
                val btnText = btn.text().trim()
                val seasonNum = Regex("""(?i)Season\s*0*(\d+)""").find(btnText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val qualityLabel = Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(btnText)?.groupValues?.get(1)?.uppercase() ?: ""

                val lsDoc = client.newCall(GET(linkStoreUrl, headers)).execute().asJsoup()
                lsDoc.select("a[href*=/file/]").mapNotNull { epLink ->
                    val epHref = epLink.attr("href")
                    val epText = epLink.text().trim()
                    if (epHref.isBlank() || epText.contains("Zip", ignoreCase = true)) return@mapNotNull null

                    val epNum = Regex("""(?i)EPISODE\s*-\s*0*(\d+)""").find(epText)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: Regex("""(?i)E0*(\d+)""").find(epText)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: 1f

                    val epSize = Regex("""\(([^)]+)\)""").find(epText)?.groupValues?.get(1) ?: ""

                    SEpisode.create().apply {
                        name = buildString {
                            append("Season $seasonNum - Episode ${epNum.toInt()}")
                            if (qualityLabel.isNotBlank()) append(" [$qualityLabel]")
                            if (epSize.isNotBlank()) append(" ($epSize)")
                        }
                        url = epHref
                        episode_number = epNum + (seasonNum - 1) * 1000
                        scanlator = qualityLabel.ifBlank { null }
                    }
                }
            }
            if (seasonEpisodes.isNotEmpty()) {
                return seasonEpisodes.distinctBy { it.name }
            }
        }

        // 2. Direct File Buttons (Movies)
        val fileButtons = doc.select("a[href*=/file/], div.movie-button-container a, a.movie-simple-button")
        if (fileButtons.isNotEmpty()) {
            fileButtons.forEachIndexed { idx, btn ->
                val fileHref = btn.attr("href")
                if (fileHref.isBlank() || !fileHref.contains("/file/")) return@forEachIndexed
                val btnText = btn.text().trim()
                val quality = Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(btnText)?.groupValues?.get(1)?.uppercase() ?: ""

                episodes.add(
                    SEpisode.create().apply {
                        name = if (btnText.isNotBlank()) btnText else "Movie - Option ${idx + 1}"
                        url = fileHref
                        episode_number = (idx + 1).toFloat()
                        scanlator = quality.ifBlank { null }
                    },
                )
            }
        }

        return episodes
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val fileUrl = episode.url
        val fileId = fileUrl.substringAfterLast("/file/").substringBefore("?").substringBefore("/")
        if (fileId.isBlank()) return emptyList()

        val hostUrl = fileUrl.toHttpUrlOrNull()
        val baseCloud = if (hostUrl != null) "${hostUrl.scheme}://${hostUrl.host}" else "https://new4.zinkcloud.net"

        val videoList = mutableListOf<Video>()

        try {
            // Step 1: Request masked route token via AJAX
            val tokenUrl = "$baseCloud/ajax_generate_token.php?random_id=$fileId"
            val formBody = FormBody.Builder()
                .add("random_id", fileId)
                .build()

            val tokenReq = Request.Builder()
                .url(tokenUrl)
                .post(formBody)
                .headers(headers.newBuilder().set("Referer", fileUrl).set("X-Requested-With", "XMLHttpRequest").build())
                .build()

            val tokenResp = client.newCall(tokenReq).execute()
            val tokenJsonStr = tokenResp.body.string()
            tokenResp.close()

            val tokenJson = json.parseToJsonElement(tokenJsonStr).jsonObject
            val token = tokenJson["token"]?.jsonPrimitive?.content ?: ""

            if (token.isBlank()) return emptyList()

            // Step 2: Fetch DL Page
            val dlUrl = "$baseCloud/dl/$token"
            val dlResp = client.newCall(GET(dlUrl, headers.newBuilder().set("Referer", fileUrl).build())).execute()
            val dlHtml = dlResp.body.string()
            dlResp.close()

            val dlDoc = Jsoup.parse(dlHtml, dlUrl)

            // Extract Server Handler URL
            val serverHandlerUrl = Regex("""const\s+SERVER_HANDLER_URL\s*=\s*["']([^"']+)["']""")
                .find(dlHtml)?.groupValues?.get(1) ?: "https://new4.zinkcloud.net/server-handler.php"

            // Step 3: Query server-handler for worker, hubcloud, and mirrors
            val targetServers = listOf("worker", "hubcloud", "gdflix", "filepress")

            targetServers.forEach { serverName ->
                try {
                    val reqJson = """{"server":"$serverName","random_id":"$fileId"}"""
                    val postReq = Request.Builder()
                        .url(serverHandlerUrl)
                        .post(reqJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .headers(headers.newBuilder().set("Referer", dlUrl).set("X-Requested-With", "XMLHttpRequest").build())
                        .build()

                    val sResp = client.newCall(postReq).execute()
                    val sJsonStr = sResp.body.string()
                    sResp.close()

                    val sObj = json.parseToJsonElement(sJsonStr).jsonObject
                    val isSuccess = sObj["success"]?.jsonPrimitive?.booleanOrNull ?: false
                    val resultUrl = sObj["url"]?.jsonPrimitive?.content ?: ""

                    if (isSuccess && resultUrl.isNotBlank()) {
                        when (serverName) {
                            "worker" -> {
                                videoList.add(
                                    Video(
                                        videoUrl = resultUrl,
                                        videoTitle = "Fast Cloud (Direct Worker)",
                                        headers = headers,
                                    ),
                                )
                            }
                            "hubcloud" -> {
                                videoList.addAll(resolveHubCloud(resultUrl))
                            }
                            else -> {
                                if (resultUrl.endsWith(".mp4") || resultUrl.endsWith(".mkv") || resultUrl.contains(".m3u8")) {
                                    videoList.add(
                                        Video(
                                            videoUrl = resultUrl,
                                            videoTitle = serverName.replaceFirstChar { it.uppercase() },
                                            headers = headers,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // skip individual server failures
                }
            }

            // Step 4: Parse direct anchor links inside the DL page
            dlDoc.select("a.btn[href], a[class*=btn][href]").forEach { a ->
                val linkHref = a.attr("abs:href").ifEmpty { a.attr("href") }
                val linkText = a.text().lowercase()

                if (linkHref.isBlank() || linkHref == dlUrl || linkHref.startsWith("whatsapp") || linkHref.startsWith("javascript")) return@forEach

                when {
                    linkHref.contains("hubcloud") -> {
                        videoList.addAll(resolveHubCloud(linkHref))
                    }
                    linkHref.contains("filepress") || linkHref.contains("filebee") -> {
                        // FilePress mirror
                    }
                    linkHref.endsWith(".mp4") || linkHref.endsWith(".mkv") || linkHref.contains(".m3u8") -> {
                        videoList.add(
                            Video(
                                videoUrl = linkHref,
                                videoTitle = a.text().trim().ifBlank { "Direct Mirror" },
                                headers = headers,
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return videoList.distinctBy { it.videoUrl }
    }

    private fun resolveHubCloud(hubCloudUrl: String): List<Video> {
        val list = mutableListOf<Video>()
        try {
            val resp = client.newCall(GET(hubCloudUrl, headers)).execute()
            val html = resp.body.string()
            resp.close()

            val doc = Jsoup.parse(html, hubCloudUrl)
            val downloadHref = doc.selectFirst("a[href*=gamerxyt.com/hubcloud.php], a#download")?.attr("abs:href")
                ?: doc.selectFirst("a#download")?.attr("href")
                ?: ""

            if (downloadHref.isNotBlank()) {
                val resp2 = client.newCall(GET(downloadHref, headers.newBuilder().set("Referer", hubCloudUrl).build())).execute()
                val doc2 = resp2.asJsoup()
                resp2.close()

                val headerQuality = doc2.selectFirst("div.card-header")?.text()?.let { text ->
                    Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(text)?.groupValues?.get(1)?.uppercase()
                } ?: ""

                doc2.select("a.btn, a[class*=btn]").forEach { btn ->
                    val href = btn.attr("abs:href").ifEmpty { btn.attr("href") }
                    val label = btn.text().lowercase()

                    when {
                        label.contains("fsl") || label.contains("download file") -> {
                            val directLink = if (href.contains("r2.cloudflarestorage.com")) href else getRedirectUrl(href, downloadHref)
                            if (directLink.isNotBlank()) {
                                list.add(
                                    Video(
                                        videoUrl = directLink,
                                        videoTitle = "HubCloud (FSL)" + if (headerQuality.isNotBlank()) " - $headerQuality" else "",
                                        headers = headers,
                                    ),
                                )
                            }
                        }
                        label.contains("10gbps") || label.contains("10 gbps") -> {
                            try {
                                val gpdlResp = client.newCall(GET(href, headers)).execute()
                                val finalUrl = gpdlResp.request.url.toString()
                                gpdlResp.close()

                                if (finalUrl.contains("gamerxyt.com/dl.php?link=")) {
                                    val direct = URLDecoder.decode(finalUrl.substringAfter("dl.php?link="), "UTF-8")
                                    if (direct.isNotBlank()) {
                                        list.add(
                                            Video(
                                                videoUrl = direct,
                                                videoTitle = "HubCloud (10Gbps)" + if (headerQuality.isNotBlank()) " - $headerQuality" else "",
                                                headers = headers,
                                            ),
                                        )
                                    }
                                } else if (finalUrl.contains("googleusercontent.com")) {
                                    list.add(
                                        Video(
                                            videoUrl = finalUrl,
                                            videoTitle = "HubCloud (10Gbps)" + if (headerQuality.isNotBlank()) " - $headerQuality" else "",
                                            headers = headers,
                                        ),
                                    )
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                        label.contains("pixeldrain") || label.contains("pixel") -> {
                            val pxlMatch = Regex("""var\s+pxl\s*=\s*"([^"]+)"""").find(doc2.html())
                            val realLink = pxlMatch?.groupValues?.get(1) ?: href
                            val fileId = realLink.substringAfterLast("/u/").substringAfterLast("/file/").substringBefore("?")
                            if (fileId.isNotBlank()) {
                                list.add(
                                    Video(
                                        videoUrl = "https://pixeldrain.com/api/file/$fileId?download",
                                        videoTitle = "HubCloud (Pixeldrain)" + if (headerQuality.isNotBlank()) " - $headerQuality" else "",
                                        headers = headers,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    private fun getRedirectUrl(url: String, referer: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .headers(headers.newBuilder().set("Referer", referer).build())
                .build()
            val resp = client.newCall(req).execute()
            val finalUrl = resp.request.url.toString()
            resp.close()
            finalUrl
        } catch (e: Exception) {
            url
        }
    }

    // ============================ Preferences & Sorting ===================
    override fun List<Video>.sortVideos(): List<Video> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Fast Cloud", "HubCloud", "Auto"),
            entryValues = listOf("Fast Cloud", "HubCloud", "auto"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from playback",
            entries = listOf("Fast Cloud", "HubCloud"),
            entryValues = listOf("Fast Cloud", "HubCloud"),
            default = emptySet(),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://new2.zinkmovies.mobi"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Fast Cloud"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

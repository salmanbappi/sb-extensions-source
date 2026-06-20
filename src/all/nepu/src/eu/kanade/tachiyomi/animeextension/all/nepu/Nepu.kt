package eu.kanade.tachiyomi.animeextension.all.nepu

import android.app.Application
import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class Nepu : ParsedAnimeHttpSource() {

    override val name = "Nepu"

    override val baseUrl = "https://nepu.to"

    override val lang = "all"

    override val supportsLatest = true

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterName = "Default", hosterUrl = episode.url))

    override val id: Long = 5181466391484419855L

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host

            if (host.contains("tmdb.org")) {
                return@addInterceptor chain.proceed(request.newBuilder().removeHeader("Referer").build())
            }

            val requestBuilder = request.newBuilder()
            var injectedCustomCookies = false

            if (host.contains("nepu.to")) {
                val cookieHeader = getSavedCookiesHeader()
                if (cookieHeader.isNotEmpty()) {
                    requestBuilder.header("Cookie", cookieHeader)
                    injectedCustomCookies = true
                }

                val savedUserAgent = getSavedUserAgent()
                if (!savedUserAgent.isNullOrBlank()) {
                    requestBuilder.header("User-Agent", savedUserAgent)
                }
            }

            val finalRequest = requestBuilder.build()
            var response = try {
                chain.proceed(finalRequest)
            } catch (e: Exception) {
                if (host.contains("nepu.to")) {
                    warmupWebViewSession()
                    chain.proceed(finalRequest)
                } else {
                    throw e
                }
            }

            if (host.contains("nepu.to") && (response.code == 403 || response.code == 503)) {
                response.close()
                warmupWebViewSession()
                response = chain.proceed(finalRequest)
            }

            response
        }
        .build()

    override fun headersBuilder(): okhttp3.Headers.Builder {
        val builder = super.headersBuilder()
            .set("Referer", "$baseUrl/")
        val savedUserAgent = getSavedUserAgent()
        if (!savedUserAgent.isNullOrBlank()) {
            builder.set("User-Agent", savedUserAgent)
        } else {
            builder.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }
        return builder
    }

    private var cachedCookieData: JSONObject? = null
    private var lastCookieRead: Long = 0

    private fun getSavedCookieData(): JSONObject? {
        val now = System.currentTimeMillis()
        if (cachedCookieData != null && now - lastCookieRead < 60000) {
            return cachedCookieData
        }

        val context = Injekt.get<Application>()
        var file = File(context.getExternalFilesDir(null), "cookies.json")
        if (!file.exists()) {
            file = File("/storage/emulated/0/Download/Serious/cookies.json")
            if (!file.exists()) {
                val sdcardFile = File("/sdcard/Download/Serious/cookies.json")
                if (!sdcardFile.exists()) return null
                file = sdcardFile
            }
        }
        return try {
            val data = JSONObject(file.readText())
            cachedCookieData = data
            lastCookieRead = now
            data
        } catch (_: Exception) {
            null
        }
    }

    private fun getSavedUserAgent(): String? = getSavedCookieData()?.optString("userAgent")

    private fun getSavedCookiesHeader(): String {
        val data = getSavedCookieData() ?: return ""
        val cookiesArray = data.optJSONArray("cookies") ?: return ""
        val cookieList = mutableListOf<String>()
        for (i in 0 until cookiesArray.length()) {
            val cookieObj = cookiesArray.optJSONObject(i) ?: continue
            val name = cookieObj.optString("name")
            val value = cookieObj.optString("value")
            if (name.isNotEmpty() && value.isNotEmpty()) {
                cookieList.add("$name=$value")
            }
        }
        return cookieList.joinToString("; ")
    }

    private val sessionWarmedUp = java.util.concurrent.atomic.AtomicBoolean(false)

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun warmupWebViewSession() {
        if (!sessionWarmedUp.compareAndSet(false, true)) return

        val latch = java.util.concurrent.CountDownLatch(1)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        mainHandler.post {
            try {
                val context = Injekt.get<Application>()
                val wv = android.webkit.WebView(context)
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true

                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(wv, true)

                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        mainHandler.postDelayed({
                            runCatching {
                                view?.stopLoading()
                                view?.destroy()
                            }
                            latch.countDown()
                        }, 5000L)
                    }
                }
                wv.loadUrl("$baseUrl/")
            } catch (_: Exception) {
                latch.countDown()
                sessionWarmedUp.set(false)
            }
        }

        try {
            if (!latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                sessionWarmedUp.set(false)
            }
        } catch (_: InterruptedException) {
            sessionWarmedUp.set(false)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/discovery".toHttpUrl().newBuilder().apply {
            addQueryParameter("filter", "null")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun popularAnimeSelector(): String = ".list-movie, .list-episode"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: element
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst(".list-title")?.text()?.trim()
            ?: element.selectFirst(".jws-post-title, h2, h3, .title, .name")?.text()
            ?: element.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: ""
        thumbnail_url = element.extractImageUrl()

        fetch_type = FetchType.Episodes
    }

    override fun popularAnimeNextPageSelector(): String? = "ul.pagination a.page-link:contains(Next)"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        val hasNextPage = document.selectFirst("ul.pagination a.page-link:contains(Next)") != null
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val filterJson = JSONObject().apply {
            put("sorting", "newest")
        }
        val url = "$baseUrl/discovery".toHttpUrl().newBuilder().apply {
            addQueryParameter("filter", filterJson.toString())
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotEmpty()) {
            val homeResponse = client.newCall(GET(baseUrl, headers)).execute()
            val homeDoc = homeResponse.asJsoup()
            val token = homeDoc.selectFirst("input[name=_TOKEN]")?.attr("value")
                ?: throw Exception("Failed to find search token")

            val searchBody = okhttp3.FormBody.Builder()
                .add("_TOKEN", token)
                .add("_ACTION", "search")
                .add("q", query)
                .build()

            val searchRequest = Request.Builder()
                .url("$baseUrl/search")
                .post(searchBody)
                .headers(headers)
                .addHeader("Origin", baseUrl)
                .addHeader("Referer", "$baseUrl/")
                .build()

            val response = client.newCall(searchRequest).execute()
            val pageResults = searchAnimeParse(response)

            val filteredList = pageResults.animes.sortedByDescending {
                diceCoefficient(it.title.lowercase(), query.lowercase())
            }

            return AnimesPage(filteredList, pageResults.hasNextPage)
        }

        val filterJson = JSONObject()
        var hasFilter = false

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        filterJson.put("type", value)
                        hasFilter = true
                    }
                }

                is GenreFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        filterJson.put("category", value)
                        hasFilter = true
                    }
                }

                is ImdbFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        filterJson.put("imdb", value)
                        hasFilter = true
                    }
                }

                is QualityFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        filterJson.put("quality", value)
                        hasFilter = true
                    }
                }

                is ReleasedFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        filterJson.put("released", value)
                        hasFilter = true
                    }
                }

                is SortingFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) {
                        filterJson.put("sorting", value)
                        hasFilter = true
                    }
                }

                else -> {}
            }
        }

        val url = "$baseUrl/discovery".toHttpUrl().newBuilder().apply {
            addQueryParameter("filter", if (hasFilter) filterJson.toString() else "null")
            addQueryParameter("page", page.toString())
        }.build()

        val response = client.newCall(GET(url, headers)).execute()
        return popularAnimeParse(response)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        val sheader = document.selectFirst("div.sheader, div.detail-content, .detail-header, .app-section")
        title = sheader?.selectFirst("div.data > h1, div.caption h1, h1")?.text()
            ?: document.selectFirst("h1.title, .entry-title, .m-title, .jws-post-title, h1")?.text() ?: ""

        description = document.selectFirst("div#info p, .description, .entry-content p, .storyline, #edit-2, div.detail div.text, meta[name='description'], meta[property='og:description']")?.let {
            if (it.tagName() == "meta") it.attr("content") else it.text()
        }
        genre = document.select("div.sgeneros a, .genres a, .entry-content .genre a, .ganre-wrapper a, div.video-attr:contains(Genre) a").joinToString { it.text() }
        status = SAnime.UNKNOWN
        thumbnail_url = sheader?.extractImageUrl() ?: document.selectFirst("meta[property='og:image']")?.attr("content") ?: ""

        fetch_type = FetchType.Episodes
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = ".episodes.tab-content a, .tab-pane a, ul.episodios li, .list-episodes a, .ep-item, .episode-item, a[href*='/episode/'], a[href*='/movie/'], a[href*='/show/'], a[href*='/serie/']"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        val epTitle = element.selectFirst("span, .name, .ep-title, .episode")?.text() ?: element.text()
        name = epTitle.trim().ifEmpty { "Episode 1" }
        episode_number = parseEpisodeNumber(name)

        val thumbnail = element.extractImageUrl()
        if (thumbnail.isNotEmpty()) {
            preview_url = thumbnail
        }
        val desc = element.selectFirst(".storyline, .description, .summary, .plot, .ep-desc, .ep-story, p:not(.date)")?.text()?.trim()
        if (!desc.isNullOrEmpty() && desc != name && desc != epTitle) {
            summary = desc
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val url = response.request.url.toString()

        if (url.contains("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    setUrlWithoutDomain(url)
                    episode_number = 1f
                },
            )
        }

        var seasons = doc.select("div.season-list div.tab-pane, div#seasons > div, div.tab-pane")
        if (seasons.isEmpty()) {
            seasons = doc.select("div.episodes")
        }

        val episodeList = mutableListOf<SEpisode>()

        var totalEpisodeCount = 1f

        if (seasons.isNotEmpty()) {
            seasons.forEach { season ->
                val seasonId = season.attr("id")
                var seasonName = (
                    if (seasonId.isNotEmpty()) {
                        doc.selectFirst("a[href='#$seasonId']")?.text()
                            ?: doc.selectFirst("button[data-bs-target='#$seasonId']")?.text()
                            ?: doc.selectFirst("button[data-target='#$seasonId']")?.text()
                    } else {
                        null
                    }
                    )
                    ?: season.selectFirst(".se-q .title")?.text()
                    ?: season.selectFirst("span.title")?.text()
                    ?: season.selectFirst("span.se-t")?.text()
                    ?: season.selectFirst("h2, h3, .season-title")?.text()
                    ?: ""
                seasonName = seasonName.trimEnd('/').trim()
                if (seasonName.toIntOrNull() != null) {
                    seasonName = "Season $seasonName"
                }

                val episodes = season.select("a").filter { it.attr("href").contains("/episode/") || it.attr("href").contains("/serie/") || it.attr("href").contains("/show/") || it.attr("href").contains("/movie/") }

                val seasonEpisodes = episodes.map { element ->
                    episodeFromElement(element).apply {
                        name = if (seasonName.isNotBlank()) "$seasonName - $name" else name
                    }
                }.sortedBy { it.episode_number }

                seasonEpisodes.forEach { episode ->
                    episode.episode_number = totalEpisodeCount++
                    episodeList.add(episode)
                }
            }
        }

        if (episodeList.isEmpty()) {
            val episodes = doc.select(episodeListSelector()).filter { it.attr("href").contains("/episode/") || it.attr("href").contains("/serie/") || it.attr("href").contains("/show/") || it.attr("href").contains("/movie/") }
            if (episodes.isNotEmpty()) {
                val sortedEpisodes = episodes.map { episodeFromElement(it) }.sortedBy { it.episode_number }
                sortedEpisodes.forEach { episode ->
                    episode.episode_number = totalEpisodeCount++
                    episodeList.add(episode)
                }
            }
        }

        // Movie fallback
        if (episodeList.isEmpty()) {
            val playButton = doc.selectFirst("a[href*='/episode/'], a[href*='/movie/'], a[href*='/serie/'], a[href*='/show/'], a.btn-play, a.watch-now, .play-btn a, a:contains(Watch Now)")
            if (playButton != null) {
                episodeList.add(
                    SEpisode.create().apply {
                        name = "Movie"
                        setUrlWithoutDomain(playButton.attr("href"))
                        episode_number = 1f
                    },
                )
            }
        }

        return episodeList.distinctBy { it.url }.reversed()
    }

    private fun parseEpisodeNumber(text: String): Float = Regex("""(?i)(?:Episode|Ep|E|Vol|Temporada)\.?\s*(\d+(\.\d+)?)""").find(text)
        ?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

    private fun buildVideoHeaders(videoUrl: String, refererUrl: String): okhttp3.Headers {
        val referer = refererUrl.takeIf { it.startsWith("http") } ?: "$baseUrl/"

        val origin = try {
            val parsed = refererUrl.toHttpUrl()
            "${parsed.scheme}://${parsed.host}"
        } catch (_: Exception) {
            baseUrl
        }

        val builder = headers.newBuilder()
            .set("Referer", referer)
            .set("Origin", origin)
            .set("Accept", "*/*")

        val isVideoOnBaseUrl = try {
            val videoHost = videoUrl.toHttpUrl().host
            val baseHost = baseUrl.toHttpUrl().host
            videoHost.endsWith(baseHost)
        } catch (_: Exception) {
            false
        }

        if (isVideoOnBaseUrl) {
            val cookieJarCookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookieJarCookies.isNotEmpty() && cookieJarCookies.contains("cf_clearance")) {
                builder.set("Cookie", cookieJarCookies)
            }
        }

        return builder.build()
    }

    override fun seasonListSelector(): String = throw UnsupportedOperationException()
    override fun seasonFromElement(element: org.jsoup.nodes.Element): SAnime = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response, hoster: Hoster): List<Video> {
        val document = response.asJsoup()
        val videoList = java.util.Collections.synchronizedList(mutableListOf<Video>())
        val pageUrl = response.request.url.toString()
        val token = document.selectFirst("input[name=_TOKEN]")?.attr("value")

        val servers = document.select(".btn-service")
        val pool = Executors.newFixedThreadPool(5)
        val futures = servers.map { btn ->
            pool.submit {
                val embedId = btn.attr("data-embed")
                var name = btn.selectFirst(".source-selected")?.text()
                    ?: btn.selectFirst(".name")?.text()
                    ?: btn.text()
                name = name.trim().ifEmpty { "Server" }

                if (embedId.isNotEmpty()) {
                    val postBodyBuilder = okhttp3.FormBody.Builder().add("id", embedId)
                    if (token != null) postBodyBuilder.add("_TOKEN", token)
                    val postBody = postBodyBuilder.build()

                    val request = Request.Builder()
                        .url("$baseUrl/ajax/embed")
                        .post(postBody)
                        .headers(headers)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", pageUrl)
                        .header("Origin", baseUrl)
                        .header("Accept", "*/*")
                        .build()

                    try {
                        client.newCall(request).execute().use { embedResponse ->
                            if (!embedResponse.isSuccessful) return@submit
                            val embedHtml = embedResponse.body.string()

                            var extractedUrl: String? = null

                            // Try to parse JSON first (Standard Dooplay)
                            try {
                                val jsonMatch = Regex("""["']?(?:embed_url|link|url|file)["']?\s*:\s*["']([^"']+)["']""").find(embedHtml)
                                if (jsonMatch != null) {
                                    val url = jsonMatch.groupValues[1].replace("\\/", "/")
                                    if (url.contains("<iframe")) {
                                        extractedUrl = Jsoup.parse(url).selectFirst("iframe")?.attr("abs:src")
                                    } else {
                                        extractedUrl = url
                                    }
                                }
                            } catch (_: Exception) {}

                            if (extractedUrl.isNullOrBlank()) {
                                val embedDoc = Jsoup.parse(embedHtml, pageUrl)
                                extractedUrl = embedDoc.selectFirst("iframe")?.attr("abs:src")
                                    ?: embedDoc.selectFirst("video source")?.attr("abs:src")
                                    ?: Regex("""servedUrl\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
                                        ?.replace("\\/", "/")?.replace("\\u0026", "&")
                                    ?: Regex("""file"?\s*:\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
                                        ?.replace("\\/", "/")
                            }

                            fun sanitize(url: String): String = when {
                                url.startsWith("http") -> url
                                url.startsWith("//") -> "https:$url"
                                url.startsWith("/") -> "$baseUrl$url"
                                else -> "$baseUrl/$url"
                            }.trim()

                            fun extractVideos(finalUrl: String, name: String, refererContext: String) {
                                if (!finalUrl.contains(".")) return
                                val mediaHeaders = buildVideoHeaders(finalUrl, refererContext)

                                if (finalUrl.contains(".mp4") || finalUrl.contains(".m3u8")) {
                                    videoList.add(Video(videoUrl = finalUrl, videoTitle = name, headers = mediaHeaders))
                                } else {
                                    try {
                                        when {
                                            finalUrl.contains("dood") -> videoList.addAll(DoodExtractor(client).videosFromUrl(finalUrl, "DoodStream"))

                                            finalUrl.contains("filemoon") || finalUrl.contains("fmoon") -> videoList.addAll(FilemoonExtractor(client).videosFromUrl(finalUrl, "Filemoon", mediaHeaders))

                                            finalUrl.contains("vidmoly") -> videoList.addAll(VidMolyExtractor(client, mediaHeaders).videosFromUrl(finalUrl, "VidMoly"))

                                            finalUrl.contains("vidhide") || finalUrl.contains("guccihide") || finalUrl.contains("streamhide") -> videoList.addAll(VidHideExtractor(client, mediaHeaders).videosFromUrl(finalUrl, { "VidHide - $it" }))

                                            finalUrl.contains("voe") -> videoList.addAll(VoeExtractor(client, mediaHeaders).videosFromUrl(finalUrl, "Voe"))

                                            finalUrl.contains("streamtape") -> videoList.addAll(StreamTapeExtractor(client).videosFromUrl(finalUrl, "StreamTape"))

                                            else -> {
                                                val extracted = UniversalExtractor(client).videosFromUrl(finalUrl, mediaHeaders, prefix = name)
                                                if (extracted.isNotEmpty()) videoList.addAll(extracted)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }

                            if (!extractedUrl.isNullOrBlank()) {
                                val sanitized = sanitize(extractedUrl)
                                val refererContext = if (sanitized.contains(".mp4") || sanitized.contains(".m3u8")) pageUrl else sanitized

                                // Pre-fetch M3U8 content before it gets deleted by the server
                                if (sanitized.contains(".m3u8")) {
                                    try {
                                        val m3u8Response = client.newCall(GET(sanitized, buildVideoHeaders(sanitized, refererContext))).execute()
                                        if (m3u8Response.isSuccessful) {
                                            val content = m3u8Response.body.string()
                                            if (content.startsWith("#EXTM3U")) {
                                                m3u8Cache[sanitized] = content
                                            }
                                        }
                                        m3u8Response.close()
                                    } catch (_: Exception) {}
                                }

                                extractVideos(sanitized, name, refererContext)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        futures.forEach {
            try {
                it.get(10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }
        pool.shutdown()

        if (videoList.isEmpty()) {
            document.select("div#player iframe, .embed-code iframe, div.source-box iframe, .player-iframe, iframe[src*='embed']").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotBlank() && !src.contains("index.html")) {
                    val videoHeaders = buildVideoHeaders(src, src)
                    if (src.contains(".mp4") || src.contains(".m3u8")) {
                        videoList.add(Video(videoUrl = src, videoTitle = "Video", headers = videoHeaders))
                    } else {
                        try {
                            when {
                                src.contains("dood") -> videoList.addAll(DoodExtractor(client).videosFromUrl(src, "DoodStream"))

                                src.contains("filemoon") || src.contains("fmoon") -> videoList.addAll(FilemoonExtractor(client).videosFromUrl(src, "Filemoon", videoHeaders))

                                src.contains("vidmoly") -> videoList.addAll(VidMolyExtractor(client, videoHeaders).videosFromUrl(src, "VidMoly"))

                                src.contains("vidhide") || src.contains("guccihide") || src.contains("streamhide") -> videoList.addAll(VidHideExtractor(client, videoHeaders).videosFromUrl(src, { "VidHide - $it" }))

                                src.contains("voe") -> videoList.addAll(VoeExtractor(client, videoHeaders).videosFromUrl(src, "Voe"))

                                src.contains("streamtape") -> videoList.addAll(StreamTapeExtractor(client).videosFromUrl(src, "StreamTape"))

                                else -> {
                                    val extracted = UniversalExtractor(client).videosFromUrl(src, videoHeaders, prefix = "Video")
                                    if (extracted.isNotEmpty()) videoList.addAll(extracted)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        return videoList.distinctBy { it.videoUrl }.map { video ->
            val videoUrl = video.videoUrl ?: return@map video
            val proxiedUrl = getProxyUrl(videoUrl, video.headers)
            Video(videoUrl = proxiedUrl, videoTitle = video.videoTitle, subtitleTracks = video.subtitleTracks, audioTracks = video.audioTracks)
        }
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        AnimeFilter.Separator(),
        ImdbFilter(),
        AnimeFilter.Separator(),
        QualityFilter(),
        AnimeFilter.Separator(),
        ReleasedFilter(),
        AnimeFilter.Separator(),
        SortingFilter(),
    )

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Type",
            arrayOf("All", "Movies", "TV Shows"),
        ) {
        fun toValue() = when (state) {
            1 -> "movie"
            2 -> "serie"
            else -> ""
        }
    }

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Category",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun toValue() = GENRES[state].second
    }

    private class ImdbFilter :
        AnimeFilter.Select<String>(
            "IMDb Rating",
            IMDB_RATING.map { it.first }.toTypedArray(),
        ) {
        fun toValue() = IMDB_RATING[state].second
    }

    private class QualityFilter :
        AnimeFilter.Select<String>(
            "Quality",
            QUALITY.map { it.first }.toTypedArray(),
        ) {
        fun toValue() = QUALITY[state].second
    }

    private class ReleasedFilter :
        AnimeFilter.Select<String>(
            "Released",
            RELEASE_YEARS.map { it.first }.toTypedArray(),
        ) {
        fun toValue() = RELEASE_YEARS[state].second
    }

    private class SortingFilter :
        AnimeFilter.Select<String>(
            "Sorting",
            SORTING.map { it.first }.toTypedArray(),
        ) {
        fun toValue() = SORTING[state].second
    }

    // ============================== Utils ==============================

    private fun Element.extractImageUrl(): String {
        val imageElements = select("[data-src], [data-lazy-src], [src]")
        for (el in imageElements) {
            val src = el.attr("abs:data-src")
                .ifEmpty { el.attr("data-src") }
                .ifEmpty { el.attr("abs:data-lazy-src") }
                .ifEmpty { el.attr("data-lazy-src") }
                .ifEmpty { el.attr("abs:src") }
                .ifEmpty { el.attr("src") }
            if (src.isNotEmpty() && !src.contains("sprite.svg") && !src.endsWith(".js") && !src.endsWith(".css")) {
                return src
            }
        }

        val styleElement = selectFirst("[style*='url(']")
        if (styleElement != null) {
            val style = styleElement.attr("style")
            if (style.contains("url(")) {
                val url = Regex("""url\(\s*['"]?([^'")\s>]+)""").find(style)?.groupValues?.get(1)
                    ?: style.substringAfter("url(").substringBefore(")")

                val cleanedUrl = url.replace("&quot;", "")
                    .replace("\"", "")
                    .replace("'", "")
                    .replace(")", "")
                    .trim()

                if (cleanedUrl.isNotEmpty()) {
                    val absoluteUrl = if (cleanedUrl.startsWith("http")) {
                        cleanedUrl
                    } else if (cleanedUrl.startsWith("//")) {
                        "https:$cleanedUrl"
                    } else {
                        "https://${baseUrl.substringAfter("://")}/${cleanedUrl.removePrefix("/")}"
                    }
                    return absoluteUrl.replace(" ", "%20")
                }
            }
        }

        val img = selectFirst("img")
        return img?.attr("abs:src")?.ifEmpty { img.attr("abs:data-src") }?.ifEmpty { img.attr("abs:data-lazy-src") } ?: ""
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

    private fun getProxyUrl(targetUrl: String, headers: okhttp3.Headers?): String = Companion.getProxyUrl(this, targetUrl, headers)

    companion object {
        private var proxy: LocalProxy? = null

        val m3u8Cache = java.util.concurrent.ConcurrentHashMap<String, String>()

        @Synchronized
        fun getProxyUrl(source: Nepu, targetUrl: String, headers: okhttp3.Headers?): String {
            if (proxy == null) {
                proxy = LocalProxy(source.client, source.baseUrl) { null }
            }
            return proxy!!.getProxyUrl(targetUrl, headers)
        }

        private val GENRES = arrayOf(
            "Category" to "",
            "3D" to "32",
            "4K" to "31",
            "Action" to "1",
            "Action & Adventure" to "21",
            "Adventure" to "2",
            "Animation" to "3",
            "AnimeDubMovie" to "36",
            "AnimeDubSerie" to "34",
            "AnimeDubSeries" to "37",
            "AnimeSubMovie" to "35",
            "AnimeSubSerie" to "33",
            "Comedy" to "4",
            "Crime" to "5",
            "Documentary" to "6",
            "Drama" to "7",
            "Family" to "8",
            "Fantasy" to "9",
            "History" to "10",
            "Horror" to "11",
            "Kids" to "22",
            "Movies" to "28",
            "Music" to "12",
            "Musical" to "30",
            "Mystery" to "13",
            "News" to "25",
            "Reality" to "24",
            "Romance" to "14",
            "Sci-Fi & Fantasy" to "20",
            "Science Fiction" to "15",
            "Soap" to "27",
            "Talk" to "26",
            "Thriller" to "16",
            "TV Movie" to "17",
            "TV Shows" to "29",
            "War" to "18",
            "War & Politics" to "23",
            "Western" to "19",
        )

        private val IMDB_RATING = arrayOf(
            "IMDb Rating" to "",
            "4 and over" to "4",
            "5 and over" to "5",
            "6 and over" to "6",
            "7 and over" to "7",
            "8 and over" to "8",
            "9 and over" to "9",
        )

        private val RELEASE_YEARS = arrayOf(
            "Released" to "",
            "2010 - 2026" to "2010-2026",
            "2000 - 2009" to "2000-2009",
            "1990 - 1999" to "1990-1999",
            "1980 - 1989" to "1980-1989",
        )

        private val QUALITY = arrayOf(
            "Quality" to "",
            "HD" to "HD",
            "Ultra HD" to "Ultra HD",
            "SD" to "SD",
            "CAM" to "CAM",
        )

        private val SORTING = arrayOf(
            "Newest" to "newest",
            "Popular" to "popular",
            "Released" to "released",
            "IMDb" to "imdb",
        )
    }
}

class LocalProxy(
    private val client: okhttp3.OkHttpClient,
    private val baseUrl: String,
    private val userAgentProvider: () -> String?,
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    init {
        try {
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            executor.execute {
                while (serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    fun getProxyUrl(targetUrl: String, headers: okhttp3.Headers?): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(headersStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun handleSocket(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (!path.startsWith("/proxy")) {
                sendError(socket, 404, "Not Found")
                return
            }

            val httpUrl = ("http://127.0.0.1$path").toHttpUrl()
            val encodedUrl = httpUrl.queryParameter("url")
            val encodedHeaders = httpUrl.queryParameter("headers") ?: ""

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains(".m3u8")
            val isCdnRequest = targetUrl.contains("vr-cdn.com")

            val targetHeaders = okhttp3.Headers.Builder()
            if (encodedHeaders.isNotEmpty() && !isCdnRequest) {
                val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders.set(headerParts[0].trim(), headerParts[1].trim())
                    }
                }
            }

            val savedUA = userAgentProvider()
            if (targetHeaders.get("User-Agent").isNullOrEmpty() && !savedUA.isNullOrBlank()) {
                targetHeaders.set("User-Agent", savedUA)
            }

            if (isCdnRequest) {
                targetHeaders.set("Referer", "$baseUrl/")
                targetHeaders.set("Origin", baseUrl)
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true) && !isM3u8Request) {
                        targetHeaders.set(name, value)
                    }
                }
            }

            val request = Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders)
            }
        } catch (e: Exception) {
            try {
                sendError(socket, 500, e.message ?: "Internal Error")
            } catch (_: Exception) {}
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String) {
        val out = socket.getOutputStream()
        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            try {
                // Check if we have the content in cache (pre-fetched)
                val cachedContent = Nepu.m3u8Cache[targetUrl]
                val bodyString = cachedContent ?: response.body.string()

                val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
                modifiedContentBytes = modifiedContent.toByteArray()

                // Remove from cache after first use to save memory
                if (cachedContent != null) Nepu.m3u8Cache.remove(targetUrl)
            } catch (e: Exception) {}
        }

        out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                (name.equals("Content-Length", ignoreCase = true) && isM3u8)
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray())
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray())
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
        } else {
            out.write("Content-Type: video/mp2t\r\n".toByteArray())
        }
        out.write("Connection: close\r\n\r\n".toByteArray())

        if (isM3u8 && modifiedContentBytes != null) {
            out.write(modifiedContentBytes)
        } else {
            response.body.byteStream().use { input ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
        }
        out.flush()
    }

    private val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        var resolvedUri = resolveUrl(playlistUrl, uriValue)
                        if (resolvedUri.contains("/_nepu_hls/")) {
                            val base64 = resolvedUri.substringAfterLast("/")
                            resolvedUri = try {
                                String(Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                            } catch (_: Exception) {
                                resolvedUri
                            }
                        }
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else if (!trimmed.startsWith("#EXT-X-PLAYLIST-TYPE")) {
                    builder.append(trimmed)
                }
            } else {
                var resolvedUri = resolveUrl(playlistUrl, trimmed)
                if (resolvedUri.contains("/_nepu_hls/")) {
                    val base64 = resolvedUri.substringAfterLast("/")
                    resolvedUri = try {
                        String(Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                    } catch (_: Exception) {
                        resolvedUri
                    }
                }
                builder.append(getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders))
            }
            builder.append("\n")
        }

        if (content.contains("#EXTINF") && !content.contains("#EXT-X-STREAM-INF")) {
            val result = builder.toString()
            return if (!result.contains("#EXT-X-ENDLIST")) {
                result.replace("#EXTM3U\n", "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n") + "#EXT-X-ENDLIST"
            } else {
                result.replace("#EXTM3U\n", "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n")
            }
        }

        return builder.toString()
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: text/plain\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(message.toByteArray())
        out.flush()
    }
}

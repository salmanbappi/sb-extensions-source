package eu.kanade.tachiyomi.animeextension.all.nepu

import android.annotation.SuppressLint
import android.app.Application
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import extensions.utils.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Nepu : Source() {

    override val name = "Nepu"

    override val baseUrl = "https://nepu.io"

    override val lang = "all"

    override val supportsLatest = true

    private val defaultUserAgent by lazy {
        try {
            android.webkit.WebSettings.getDefaultUserAgent(Injekt.get<Application>())
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
    }

    override fun headersBuilder(): okhttp3.Headers.Builder = super.headersBuilder()
        .set("User-Agent", defaultUserAgent)
        .set("Referer", "$baseUrl/")

    private val cfCookie = listOf("cf", "clearance").joinToString("_")

    fun getBestCookie(): String {
        try {
            val cookieManager = CookieManager.getInstance()
            val managerCookies = cookieManager.getCookie(baseUrl)
            if (!managerCookies.isNullOrEmpty() && managerCookies.contains(cfCookie)) {
                return managerCookies
            }
        } catch (_: Exception) {}

        try {
            val cookieJarCookies = client.cookieJar.loadForRequest(baseUrl.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }
            if (cookieJarCookies.isNotEmpty() && cookieJarCookies.contains(cfCookie)) {
                return cookieJarCookies
            }
        } catch (_: Exception) {}

        return try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.getCookie(baseUrl) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun solveCloudflare(url: String) {
        try {
            val getRequest = Request.Builder()
                .url(url)
                .headers(headers)
                .build()
            client.newCall(getRequest).execute().close()
        } catch (_: Exception) {}
    }

    override val id: Long = 5181466391484419855L

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.url.host.contains("tmdb.org")) {
                return@addInterceptor chain.proceed(builder.removeHeader("Referer").build())
            }
            if (request.url.host.endsWith("nepu.io")) {
                builder.header("Cookie", getBestCookie())
                builder.header("User-Agent", defaultUserAgent)
            }
            chain.proceed(builder.build())
        }
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/discovery".toHttpUrl().newBuilder().apply {
            addQueryParameter("filter", "null")
            addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    private fun popularAnimeSelector(): String = ".list-movie, .list-episode"

    private fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
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

    private fun popularAnimeNextPageSelector(): String? = "ul.pagination a.page-link:contains(Next)"

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

    private fun latestUpdatesSelector(): String = popularAnimeSelector()

    private fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    private fun searchAnimeSelector(): String = popularAnimeSelector()

    private fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

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

    override fun animeDetailsRequest(anime: SAnime): Request = GET(UrlUtils.fixUrl(anime.url, baseUrl), headers)

    override fun animeDetailsParse(response: Response): SAnime = animeDetailsParse(response.asJsoup())

    fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
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
        initialized = true
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET(UrlUtils.fixUrl(anime.url, baseUrl), headers)

    private fun episodeListSelector(): String = ".episodes.tab-content a, .tab-pane a, ul.episodios li, .list-episodes a, .ep-item, .episode-item, a[href*='/episode/'], a[href*='/movie/'], a[href*='/show/'], a[href*='/serie/']"

    private fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
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
            builder.set("Cookie", getBestCookie())
        }

        return builder.build()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(UrlUtils.fixUrl(episode.url, baseUrl), headers)

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(
            hosterName = "Default",
            hosterUrl = UrlUtils.fixUrl(episode.url, baseUrl),
        ),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episode = SEpisode.create().apply {
            url = hoster.hosterUrl
        }
        return getVideoList(episode)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val pageUrl = UrlUtils.fixUrl(episode.url, baseUrl)

        // 1. Try fast HTTP extraction
        val directVideos = try {
            val response = withContext(Dispatchers.IO) {
                client.newCall(GET(pageUrl, headers)).execute()
            }
            extractVideosFromResponse(response)
        } catch (_: Exception) {
            emptyList()
        }

        if (directVideos.isNotEmpty()) {
            return directVideos
        }

        // 2. Fallback to WebView interception to solve Cloudflare on /ajax/embed and capture live streams
        val capturedUrls = withContext(Dispatchers.IO) {
            extractVideoUrlsViaWebView(pageUrl)
        }

        val fallbackList = java.util.Collections.synchronizedList(mutableListOf<Video>())
        for (rawUrl in capturedUrls) {
            val videoHeaders = buildVideoHeaders(rawUrl, pageUrl)
            try {
                when {
                    rawUrl.contains(".m3u8") || rawUrl.contains(".mp4") || rawUrl.contains("/ajax/hls") || rawUrl.contains("/hls") -> {
                        val queryT = try {
                            rawUrl.toHttpUrl().queryParameter("t")
                        } catch (_: Exception) {
                            null
                        }
                        if (!queryT.isNullOrEmpty()) tToken = queryT
                        fallbackList.add(Video(videoUrl = rawUrl, videoTitle = "Nepu", headers = videoHeaders))
                    }

                    rawUrl.contains("dood") -> fallbackList.addAll(DoodExtractor(client).videosFromUrl(rawUrl, "DoodStream"))

                    rawUrl.contains("filemoon") || rawUrl.contains("fmoon") -> fallbackList.addAll(FilemoonExtractor(client).videosFromUrl(rawUrl, "Filemoon", videoHeaders))

                    rawUrl.contains("vidmoly") -> fallbackList.addAll(VidMolyExtractor(client, videoHeaders).videosFromUrl(rawUrl, "VidMoly"))

                    rawUrl.contains("vidhide") || rawUrl.contains("guccihide") || rawUrl.contains("streamhide") -> fallbackList.addAll(VidHideExtractor(client, videoHeaders).videosFromUrl(rawUrl) { "VidHide - $it" })

                    rawUrl.contains("voe") -> fallbackList.addAll(VoeExtractor(client, videoHeaders).videosFromUrl(rawUrl, "Voe"))

                    rawUrl.contains("streamtape") -> fallbackList.addAll(StreamTapeExtractor(client).videosFromUrl(rawUrl, "StreamTape"))

                    else -> {
                        val extracted = UniversalExtractor(client).videosFromUrl(rawUrl, videoHeaders, prefix = "Nepu")
                        if (extracted.isNotEmpty()) fallbackList.addAll(extracted)
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback to UniversalExtractor on pageUrl if still empty
        if (fallbackList.isEmpty()) {
            try {
                val extracted = UniversalExtractor(client).videosFromUrl(pageUrl, buildVideoHeaders(pageUrl, pageUrl), prefix = "Nepu")
                if (extracted.isNotEmpty()) fallbackList.addAll(extracted)
            } catch (_: Exception) {}
        }

        return fallbackList.filter { !it.videoUrl.isNullOrBlank() }.distinctBy { it.videoUrl }.map { video ->
            val videoUrl = video.videoUrl!!
            val needsProxy = videoUrl.contains("nepu.io") || videoUrl.contains("vr-cdn.com") || videoUrl.contains("/_nepu_hls/") || videoUrl.contains("/ajax/hls")
            if (needsProxy) {
                val proxiedUrl = getProxyUrl(videoUrl, video.headers)
                Video(
                    videoUrl = proxiedUrl,
                    videoTitle = video.videoTitle,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                )
            } else {
                video
            }
        }
    }

    private val videoUrlPatterns = listOf(
        ".m3u8", ".mp4", "/ajax/hls", "/hls", "/_nepu_hls/", "manifest",
        "dood", "filemoon", "fmoon", "vidmoly", "vidhide", "guccihide",
        "streamhide", "voe", "streamtape", "vr-cdn.com",
    )

    private fun isVideoUrl(url: String): Boolean = videoUrlPatterns.any { url.contains(it, ignoreCase = true) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun extractVideoUrlsViaWebView(pageUrl: String): List<String> {
        val capturedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val latch = CountDownLatch(1)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var webView: WebView? = null

        handler.post {
            try {
                val context = Injekt.get<Application>()
                val wv = WebView(context)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.databaseEnabled = true
                wv.settings.mediaPlaybackRequiresUserGesture = false
                wv.settings.userAgentString = defaultUserAgent

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(wv, true)

                wv.addJavascriptInterface(
                    object {
                        @android.webkit.JavascriptInterface
                        fun onData(url: String, data: String) {
                            try {
                                val servedUrlRegex = Regex("""var (?:servedUrl|plainManifestUrl|opaqueManifestUrl)\s*=\s*"([^"]+)"""")
                                var rawUrl = servedUrlRegex.find(data)?.groupValues?.get(1)
                                if (rawUrl.isNullOrEmpty()) {
                                    rawUrl = Regex("""["']?(?:file|embed_url|link|url)["']?\s*:\s*["']([^"']+)["']""").find(data)?.groupValues?.get(1)
                                }
                                if (!rawUrl.isNullOrEmpty()) {
                                    val cleanUrl = rawUrl.replace("\\u0026", "&").replace("\\/", "/").trim()
                                    capturedUrls.add(UrlUtils.fixUrl(cleanUrl, baseUrl))
                                    latch.countDown()
                                }
                                val matchFilename = Regex("""var hlsFileName\s*=\s*"([^"]+)"""").find(data)
                                val matchNonce = Regex("""var playerNonce\s*=\s*"([^"]+)"""").find(data)
                                if (matchFilename != null) hlsFile = matchFilename.groupValues[1]
                                if (matchNonce != null) playerNonce = matchNonce.groupValues[1]
                            } catch (_: Exception) {}
                        }
                    },
                    "androidBridge",
                )

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (isVideoUrl(url) && !url.startsWith("blob:")) {
                            capturedUrls.add(url)
                            latch.countDown()
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val currentTitle = view?.title ?: ""
                        if (currentTitle.contains("Just a moment", ignoreCase = true) || url?.contains("__cf_chl") == true) {
                            return
                        }

                        val jsHook = """
                            (function() {
                                if (window.__hooked) return;
                                window.__hooked = true;
                                var origOpen = XMLHttpRequest.prototype.open;
                                var origSend = XMLHttpRequest.prototype.send;
                                XMLHttpRequest.prototype.open = function(m, u) {
                                    this.__url = u;
                                    return origOpen.apply(this, arguments);
                                };
                                XMLHttpRequest.prototype.send = function() {
                                    this.addEventListener('load', function() {
                                        if (this.__url && (this.__url.indexOf('ajax/embed') !== -1 || this.__url.indexOf('ajax/hls') !== -1)) {
                                            try { window.androidBridge.onData(this.__url, this.responseText); } catch(e) {}
                                        }
                                    });
                                    return origSend.apply(this, arguments);
                                };
                                var origFetch = window.fetch;
                                if (origFetch) {
                                    window.fetch = function() {
                                        var args = arguments;
                                        var u = (args[0] && args[0].url) || args[0] || '';
                                        return origFetch.apply(this, args).then(function(res) {
                                            if (typeof u === 'string' && (u.indexOf('ajax/embed') !== -1 || u.indexOf('ajax/hls') !== -1)) {
                                                res.clone().text().then(function(txt) {
                                                    try { window.androidBridge.onData(u, txt); } catch(e) {}
                                                }).catch(function() {});
                                            }
                                            return res;
                                        });
                                    };
                                }
                                var btn = document.querySelector('a#videoSource, .btn-service.active, .btn-service, [data-embed]');
                                if (btn) { try { btn.click(); } catch(e) {} }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsHook, null)

                        val jsExtract = """
                            (function() {
                                var f = (typeof hlsFileName !== 'undefined') ? hlsFileName : '';
                                var n = (typeof playerNonce !== 'undefined') ? playerNonce : '';
                                var u = (typeof servedUrl !== 'undefined') ? servedUrl : ((typeof plainManifestUrl !== 'undefined') ? plainManifestUrl : '');
                                var src = '';
                                var iframe = document.querySelector('iframe');
                                if (iframe) src = iframe.src || '';
                                var video = document.querySelector('video source, video');
                                if (video) src = video.src || '';
                                return JSON.stringify({ f: f, n: n, u: u, src: src });
                            })()
                        """.trimIndent()

                        view?.evaluateJavascript(jsExtract) { resultJson ->
                            try {
                                if (!resultJson.isNullOrBlank() && resultJson != "null") {
                                    val cleanJson = if (resultJson.startsWith("\"") && resultJson.endsWith("\"")) {
                                        org.json.JSONTokener(resultJson).nextValue().toString()
                                    } else {
                                        resultJson
                                    }
                                    val obj = JSONObject(cleanJson)
                                    val f = obj.optString("f")
                                    val n = obj.optString("n")
                                    val u = obj.optString("u")
                                    val src = obj.optString("src")

                                    if (f.isNotEmpty()) hlsFile = f
                                    if (n.isNotEmpty()) playerNonce = n
                                    if (u.isNotEmpty()) {
                                        val cleanU = u.replace("\\u0026", "&").replace("\\/", "/").trim()
                                        capturedUrls.add(UrlUtils.fixUrl(cleanU, baseUrl))
                                    }
                                    if (src.isNotEmpty() && !src.contains("about:") && !src.contains("javascript:")) {
                                        capturedUrls.add(UrlUtils.fixUrl(src, baseUrl))
                                    }
                                }
                            } catch (_: Exception) {}
                            if (capturedUrls.isNotEmpty()) {
                                latch.countDown()
                            } else {
                                handler.postDelayed({ latch.countDown() }, 8000)
                            }
                        }
                    }
                }

                wv.loadUrl(pageUrl)
            } catch (e: Exception) {
                latch.countDown()
            }
        }

        latch.await(20, TimeUnit.SECONDS)

        handler.post {
            try {
                webView?.stopLoading()
                webView?.destroy()
                webView = null
            } catch (_: Exception) {}
        }

        return capturedUrls.distinct()
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    private suspend fun extractVideosFromResponse(response: Response): List<Video> {
        val videoList = java.util.Collections.synchronizedList(mutableListOf<Video>())
        val pageUrl = response.request.url.toString()
        val document = response.asJsoup()

        val embedElements = document.select("a#videoSource, .btn-service, [data-embed], .server, .servers li, .play-server, [data-id]")
        val embedIds = embedElements.mapNotNull {
            val id = it.attr("data-embed").ifEmpty { it.attr("data-id") }.trim()
            id.takeIf(String::isNotEmpty)
        }.distinct()

        for (embedId in embedIds) {
            try {
                val postBody = okhttp3.FormBody.Builder()
                    .add("id", embedId)
                    .build()

                val postHeaders = headers.newBuilder()
                    .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Referer", pageUrl)
                    .set("Origin", baseUrl)
                    .build()

                val postRequest = Request.Builder()
                    .url("$baseUrl/ajax/embed")
                    .post(postBody)
                    .headers(postHeaders)
                    .build()

                var embedResponse = withContext(Dispatchers.IO) { client.newCall(postRequest).execute() }
                if (embedResponse.code == 403) {
                    embedResponse.close()
                    solveCloudflare(pageUrl)
                    embedResponse = withContext(Dispatchers.IO) { client.newCall(postRequest).execute() }
                }
                val responseBody = embedResponse.body.string()

                val servedUrlRegex = Regex("""var (?:servedUrl|plainManifestUrl|opaqueManifestUrl)\s*=\s*"([^"]+)"""")
                var rawServedUrl = servedUrlRegex.find(responseBody)?.groupValues?.get(1)

                if (rawServedUrl.isNullOrEmpty()) {
                    rawServedUrl = Regex("""["']?(?:file|embed_url|link|url)["']?\s*:\s*["']([^"']+)["']""").find(responseBody)?.groupValues?.get(1)
                }

                if (rawServedUrl.isNullOrEmpty()) {
                    val embedDoc = org.jsoup.Jsoup.parse(responseBody, pageUrl)
                    rawServedUrl = embedDoc.selectFirst("iframe")?.attr("abs:src")
                        ?.ifEmpty { embedDoc.selectFirst("iframe")?.attr("src") }
                        ?: embedDoc.selectFirst("video source")?.attr("abs:src")
                        ?: embedDoc.selectFirst("video source")?.attr("src")
                }

                if (!rawServedUrl.isNullOrEmpty()) {
                    val cleanUrl = rawServedUrl.replace("\\u0026", "&").replace("\\/", "/").trim()
                    val servedUrl = UrlUtils.fixUrl(cleanUrl, baseUrl)

                    val matchFilename = Regex("""var hlsFileName\s*=\s*"([^"]+)"""").find(responseBody)
                    val matchNonce = Regex("""var playerNonce\s*=\s*"([^"]+)"""").find(responseBody)

                    if (matchFilename != null) hlsFile = matchFilename.groupValues[1]
                    if (matchNonce != null) playerNonce = matchNonce.groupValues[1]
                    val queryT = try {
                        servedUrl.toHttpUrl().queryParameter("t")
                    } catch (_: Exception) {
                        null
                    }
                    if (!queryT.isNullOrEmpty()) tToken = queryT

                    val videoHeaders = buildVideoHeaders(servedUrl, pageUrl)
                    if (servedUrl.contains(".m3u8") || servedUrl.contains(".mp4") || servedUrl.contains("/ajax/hls") || servedUrl.contains("/hls")) {
                        videoList.add(Video(videoUrl = servedUrl, videoTitle = "Nepu", headers = videoHeaders))
                    } else {
                        when {
                            servedUrl.contains("dood") -> videoList.addAll(DoodExtractor(client).videosFromUrl(servedUrl, "DoodStream"))

                            servedUrl.contains("filemoon") || servedUrl.contains("fmoon") -> videoList.addAll(FilemoonExtractor(client).videosFromUrl(servedUrl, "Filemoon", videoHeaders))

                            servedUrl.contains("vidmoly") -> videoList.addAll(VidMolyExtractor(client, videoHeaders).videosFromUrl(servedUrl, "VidMoly"))

                            servedUrl.contains("vidhide") || servedUrl.contains("guccihide") || servedUrl.contains("streamhide") -> videoList.addAll(VidHideExtractor(client, videoHeaders).videosFromUrl(servedUrl) { "VidHide - $it" })

                            servedUrl.contains("voe") -> videoList.addAll(VoeExtractor(client, videoHeaders).videosFromUrl(servedUrl, "Voe"))

                            servedUrl.contains("streamtape") -> videoList.addAll(StreamTapeExtractor(client).videosFromUrl(servedUrl, "StreamTape"))

                            else -> {
                                val extracted = UniversalExtractor(client).videosFromUrl(servedUrl, videoHeaders, prefix = "Nepu")
                                if (extracted.isNotEmpty()) videoList.addAll(extracted)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        document.select("div#player iframe, .embed-code iframe, div.source-box iframe, .player-iframe, iframe[src*='embed'], iframe").forEach { iframe ->
            val rawSrc = iframe.attr("abs:src")
                .ifEmpty { iframe.attr("src") }
                .ifEmpty { iframe.attr("abs:data-src") }
                .ifEmpty { iframe.attr("data-src") }
            if (rawSrc.isNotBlank() && !rawSrc.contains("index.html") && !rawSrc.startsWith("about:") && !rawSrc.startsWith("javascript:")) {
                val src = UrlUtils.fixUrl(rawSrc, baseUrl)
                val videoHeaders = buildVideoHeaders(src, src)
                if (src.contains(".mp4") || src.contains(".m3u8")) {
                    videoList.add(Video(videoUrl = src, videoTitle = "Video", headers = videoHeaders))
                } else {
                    try {
                        when {
                            src.contains("dood") -> videoList.addAll(DoodExtractor(client).videosFromUrl(src, "DoodStream"))

                            src.contains("filemoon") || src.contains("fmoon") -> videoList.addAll(FilemoonExtractor(client).videosFromUrl(src, "Filemoon", videoHeaders))

                            src.contains("vidmoly") -> videoList.addAll(VidMolyExtractor(client, videoHeaders).videosFromUrl(src, "VidMoly"))

                            src.contains("vidhide") || src.contains("guccihide") || src.contains("streamhide") -> videoList.addAll(VidHideExtractor(client, videoHeaders).videosFromUrl(src) { "VidHide - $it" })

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

        return videoList.filter { !it.videoUrl.isNullOrBlank() }.distinctBy { it.videoUrl }.map { video ->
            val videoUrl = video.videoUrl!!
            val needsProxy = videoUrl.contains("nepu.io") || videoUrl.contains("vr-cdn.com") || videoUrl.contains("/_nepu_hls/") || videoUrl.contains("/ajax/hls")
            if (needsProxy) {
                val proxiedUrl = getProxyUrl(videoUrl, video.headers)
                Video(
                    videoUrl = proxiedUrl,
                    videoTitle = video.videoTitle,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                )
            } else {
                video
            }
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    private fun getProxyUrl(targetUrl: String, headers: okhttp3.Headers?): String = Companion.getProxyUrl(this, targetUrl, headers)

    companion object {
        private var proxy: LocalProxy? = null

        val m3u8Cache = java.util.concurrent.ConcurrentHashMap<String, String>()

        var hlsFile = ""
        var playerNonce = ""
        var tToken = ""

        @Synchronized
        fun getProxyUrl(source: Nepu, targetUrl: String, headers: okhttp3.Headers?): String {
            if (proxy == null || proxy!!.isClosed) {
                proxy = LocalProxy(source, source.client, source.baseUrl) {
                    source.headers.get("User-Agent")
                }
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
    private val source: Nepu,
    private val client: okhttp3.OkHttpClient,
    private val baseUrl: String,
    private val userAgentProvider: () -> String?,
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    val isClosed: Boolean
        get() = serverSocket == null || serverSocket?.isClosed == true

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
        if (port == 0) return targetUrl
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
        var targetUrl = ""
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

            targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            if (targetUrl.contains("/_nepu_hls/")) {
                try {
                    val parsedUrl = targetUrl.toHttpUrl()
                    val pathSegments = parsedUrl.pathSegments
                    val sessionId = pathSegments[1]
                    val g = pathSegments[2].toIntOrNull() ?: 0
                    val uB64 = pathSegments.subList(3, pathSegments.size).joinToString("/")

                    val keyBody = okhttp3.FormBody.Builder()
                        .add("f", Nepu.hlsFile)
                        .add("s", sessionId)
                        .add("t", Nepu.tToken)
                        .add("n", Nepu.playerNonce)
                        .add("g", g.toString())
                        .build()

                    val savedUA = userAgentProvider() ?: source.headers.get("User-Agent")
                    val keyHeadersBuilder = okhttp3.Headers.Builder()
                        .set("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .set("X-Requested-With", "XMLHttpRequest")
                        .set("Referer", "$baseUrl/")
                        .set("Origin", baseUrl)
                        .set("Cookie", source.getBestCookie())
                    if (!savedUA.isNullOrBlank()) {
                        keyHeadersBuilder.set("User-Agent", savedUA)
                    }
                    val keyHeaders = keyHeadersBuilder.build()

                    val keyRequest = Request.Builder()
                        .url("$baseUrl/ajax/hlskey")
                        .post(keyBody)
                        .headers(keyHeaders)
                        .build()

                    client.newCall(keyRequest).execute().use { keyResp ->
                        val keyJson = org.json.JSONObject(keyResp.body.string())
                        if (keyJson.optBoolean("ok") && keyJson.has("k")) {
                            val k = keyJson.getString("k")
                            val decryptedUrl = decryptAesGcm(uB64, k)
                            if (decryptedUrl.isNotEmpty()) {
                                targetUrl = if (decryptedUrl.startsWith("http")) {
                                    decryptedUrl
                                } else {
                                    "$baseUrl/${decryptedUrl.removePrefix("/")}"
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            val isM3u8Request = targetUrl.contains(".m3u8") || targetUrl.contains("/ajax/hls") || path.contains(".m3u8") || path.contains("playlist.m3u8")

            val targetHeaders = okhttp3.Headers.Builder()
            if (encodedHeaders.isNotEmpty()) {
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

            if (targetHeaders.get("Referer").isNullOrEmpty()) {
                targetHeaders.set("Referer", "$baseUrl/")
            }
            if (targetUrl.contains("nepu.io")) {
                if (targetHeaders.get("Origin").isNullOrEmpty()) {
                    targetHeaders.set("Origin", baseUrl)
                }
                if (targetHeaders.get("Cookie").isNullOrEmpty()) {
                    targetHeaders.set("Cookie", source.getBestCookie())
                }
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
        val isM3u8 = targetUrl.contains(".m3u8") || targetUrl.contains("/ajax/hls") || response.header("Content-Type")?.contains("mpegurl") == true

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
            val contentType = response.header("Content-Type") ?: "video/mp2t"
            out.write("Content-Type: $contentType\r\n".toByteArray())
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
                            try {
                                val decoded = String(Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                                    resolvedUri = decoded
                                }
                            } catch (_: Exception) {}
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
                    try {
                        val decoded = String(Base64.decode(base64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                        if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                            resolvedUri = decoded
                        }
                    } catch (_: Exception) {}
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

    private fun decryptAesGcm(uB64: String, keyB64: String): String = try {
        val keyBytes = Base64.decode(keyB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val uBytes = Base64.decode(uB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        val iv = uBytes.copyOfRange(0, 12)
        val ciphertext = uBytes.copyOfRange(12, uBytes.size)

        val secretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(ciphertext)
        String(decryptedBytes, Charsets.UTF_8)
    } catch (e: Exception) {
        ""
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

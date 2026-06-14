package eu.kanade.tachiyomi.animeextension.all.netmirror

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NetMirror :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "NetMirror"

    override val baseUrl = "https://net11.cc"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 5181466391484419888L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(CloudflareInterceptor(network.client))
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host

            var response = try {
                chain.proceed(request)
            } catch (e: Exception) {
                if (host.contains("net52.cc") || host.contains("net11.cc")) {
                    sessionWarmedUp.set(false)
                    warmupWebViewSession()
                    chain.proceed(request)
                } else {
                    throw e
                }
            }

            if ((host.contains("net52.cc") || host.contains("net11.cc")) && (response.code == 403 || response.code == 503)) {
                response.close()
                sessionWarmedUp.set(false)
                warmupWebViewSession()
                response = chain.proceed(request)
            }

            response
        }
        .build()

    override fun headersBuilder(): okhttp3.Headers.Builder = super.headersBuilder()
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")

    private val sessionWarmedUp = AtomicBoolean(false)

    private fun copyCookiesToOkHttp() {
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            val urlsToCopy = listOf(baseUrl, "https://net52.cc")
            for (url in urlsToCopy) {
                val rawCookies = cookieManager.getCookie(url) ?: continue
                val httpUrl = url.toHttpUrl()
                val cookies = rawCookies.split(";").mapNotNull {
                    okhttp3.Cookie.parse(httpUrl, it.trim())
                }
                client.cookieJar.saveFromResponse(httpUrl, cookies)
                android.util.Log.d("NetMirrorWebView", "Copied cookies for $url: $rawCookies")
            }
        } catch (e: Exception) {
            android.util.Log.e("NetMirrorWebView", "Failed to copy cookies", e)
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun warmupWebViewSession() {
        if (!sessionWarmedUp.compareAndSet(false, true)) return

        val latch = CountDownLatch(1)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var webView: android.webkit.WebView? = null

        mainHandler.post {
            try {
                val context = Injekt.get<Application>()
                val wv = android.webkit.WebView(context)
                webView = wv
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.databaseEnabled = true
                wv.settings.useWideViewPort = true
                wv.settings.loadWithOverviewMode = false
                wv.settings.userAgentString = USER_AGENT

                val cm = android.webkit.CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(wv, true)

                wv.addJavascriptInterface(
                    object {
                        @android.webkit.JavascriptInterface
                        fun leave() {
                            latch.countDown()
                        }
                    },
                    "NetMirrorJSI",
                )

                wv.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        android.util.Log.d("NetMirrorWebView", "Page finished: $url, title: ${view?.title}")
                        if (url != null && !url.contains("verify2") && !url.contains("challenge")) {
                            latch.countDown()
                            return
                        }

                        view?.evaluateJavascript(
                            """
                            (function() {
                                var checkInterval = setInterval(() => {
                                    if (document.querySelector("#challenge-form") == null &&
                                        document.querySelector("#challenge-stage") == null &&
                                        !document.title.includes("Just a moment")) {
                                        clearInterval(checkInterval);
                                        NetMirrorJSI.leave();
                                    } else {
                                        const simpleChallenge = document.querySelector("#challenge-stage > div > input[type='button']")
                                        if (simpleChallenge != null) simpleChallenge.click()

                                        const turnstile = document.querySelector("div.hcaptcha-box > iframe")
                                        if (turnstile != null) {
                                            const button = turnstile.contentWindow.document.querySelector("input[type='checkbox']")
                                            if (button != null) button.click()
                                        }
                                    }
                                }, 1000);
                            })();
                            """.trimIndent(),
                        ) {}
                    }
                }
                wv.loadUrl("$baseUrl/home")
            } catch (e: Exception) {
                android.util.Log.e("NetMirrorWebView", "Error in WebView warmup", e)
                latch.countDown()
                sessionWarmedUp.set(false)
            }
        }

        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                android.util.Log.w("NetMirrorWebView", "Timeout waiting for WebView warmup")
                sessionWarmedUp.set(false)
            } else {
                android.util.Log.d("NetMirrorWebView", "WebView warmup completed successfully")
                copyCookiesToOkHttp()
            }
        } catch (_: InterruptedException) {
            sessionWarmedUp.set(false)
        } finally {
            mainHandler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                } catch (_: Exception) {}
            }
        }
    }

    private fun ensureSession() {
        if (!sessionWarmedUp.get()) {
            warmupWebViewSession()
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        ensureSession()
        return GET("$baseUrl/pv/homepage.php", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = JSONObject(response.body.string())
        val ids = mutableListOf<String>()

        val slider = json.optJSONArray("slider")
        if (slider != null) {
            for (i in 0 until slider.length()) {
                val item = slider.getJSONObject(i)
                val id = item.optString("id")
                if (id.isNotEmpty()) ids.add(id)
            }
        }

        val post = json.optJSONArray("post")
        if (post != null) {
            for (i in 0 until post.length()) {
                val cat = post.getJSONObject(i)
                val catIds = cat.optString("ids")
                if (catIds.isNotEmpty()) {
                    ids.addAll(catIds.split(","))
                }
            }
        }

        val uniqueIds = ids.distinct().take(60)

        val pool = Executors.newFixedThreadPool(10)
        val futures = uniqueIds.map { id ->
            pool.submit(
                java.util.concurrent.Callable<SAnime?> {
                    runCatching {
                        val res = client.newCall(GET("$baseUrl/pv/mini-modal-info.php?id=$id", headers)).execute()
                        if (res.isSuccessful) {
                            val info = JSONObject(res.body.string())
                            val anime = SAnime.create()
                            anime.title = info.optString("title")
                            anime.url = id
                            anime.thumbnail_url = "https://imgcdn.kim/pv/341/$id.jpg"
                            anime.description = info.optString("desc")
                            anime
                        } else {
                            null
                        }
                    }.getOrNull()
                },
            )
        }

        val animes = futures.mapNotNull {
            try {
                it.get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
        }
        pool.shutdown()

        return AnimesPage(animes, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        ensureSession()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/pv/search.php?s=$encodedQuery", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val json = JSONObject(response.body.string())
        val searchResult = json.optJSONArray("searchResult") ?: return AnimesPage(emptyList(), false)

        val animeList = mutableListOf<SAnime>()
        for (i in 0 until searchResult.length()) {
            val item = searchResult.getJSONObject(i)
            val id = item.optString("id")
            val titleText = item.optString("t")
            if (id.isNotEmpty() && titleText.isNotEmpty()) {
                val anime = SAnime.create()
                anime.title = titleText
                anime.url = id
                anime.thumbnail_url = "https://imgcdn.kim/pv/341/$id.jpg"
                animeList.add(anime)
            }
        }
        return AnimesPage(animeList, false)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        ensureSession()
        return GET("$baseUrl/pv/post.php?id=${anime.url}", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val json = JSONObject(response.body.string())
        val id = response.request.url.queryParameter("id") ?: ""
        val anime = SAnime.create()
        anime.title = json.optString("title")
        anime.genre = json.optString("genre")
        anime.author = json.optString("creator").ifEmpty { json.optString("writer") }
        anime.artist = json.optString("director")
        anime.status = SAnime.UNKNOWN
        anime.thumbnail_url = "https://imgcdn.kim/pv/341/$id.jpg"

        val description = StringBuilder()
        json.optString("desc").takeIf { it.isNotEmpty() }?.let {
            description.append(it).append("\n\n")
        }

        val details = mutableListOf<String>()

        json.optString("year").takeIf { it.isNotEmpty() }?.let {
            details.add("Year: $it")
        }
        json.optString("match").takeIf { it.isNotEmpty() }?.let {
            details.add("Rating: $it")
        }
        json.optString("runtime").takeIf { it.isNotEmpty() }?.let {
            details.add("Runtime: $it")
        }
        json.optString("hdsd").takeIf { it.isNotEmpty() }?.let {
            details.add("Quality: $it")
        }
        json.optString("ua").takeIf { it.isNotEmpty() }?.let {
            details.add("Age Rating: $it")
        }
        json.optString("studio").takeIf { it.isNotEmpty() }?.let {
            details.add("Studio: $it")
        }
        json.optString("cast").takeIf { it.isNotEmpty() }?.let {
            details.add("Cast: $it")
        }

        if (details.isNotEmpty()) {
            description.append(details.joinToString("\n"))
        }

        anime.description = description.toString()
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val json = JSONObject(response.body.string())
        val type = json.optString("type")
        val id = response.request.url.queryParameter("id") ?: ""

        if (type == "m") {
            val sEpisode = SEpisode.create()
            sEpisode.name = "Movie"
            sEpisode.url = id
            sEpisode.episode_number = 1f
            return listOf(sEpisode)
        }

        val episodeList = mutableListOf<SEpisode>()
        val seriesId = id

        val seasons = json.optJSONArray("season")
        if (seasons != null && seasons.length() > 0) {
            val pool = Executors.newFixedThreadPool(5)
            val futures = (0 until seasons.length()).map { idx ->
                pool.submit {
                    val seasonObj = seasons.getJSONObject(idx)
                    val seasonId = seasonObj.optString("id")
                    val seasonName = "Season ${seasonObj.optString("s")}"

                    var page = 1
                    var hasNext = true
                    while (hasNext && page <= 3) {
                        try {
                            val url = "$baseUrl/pv/episodes.php?s=$seasonId&series=$seriesId&page=$page"
                            val res = client.newCall(GET(url, headers)).execute()
                            if (res.isSuccessful) {
                                val resJson = JSONObject(res.body.string())
                                val eps = resJson.optJSONArray("episodes")
                                if (eps != null && eps.length() > 0) {
                                    synchronized(episodeList) {
                                        for (k in 0 until eps.length()) {
                                            val ep = eps.getJSONObject(k)
                                            val epId = ep.optString("id")
                                            val epTitle = ep.optString("t")
                                            val epNumStr = ep.optString("ep").replace("E", "")
                                            val epNum = epNumStr.toFloatOrNull() ?: 1.0f

                                            val sEpisode = SEpisode.create()
                                            sEpisode.name = "$seasonName - $epTitle"
                                            sEpisode.url = epId
                                            sEpisode.episode_number = epNum
                                            episodeList.add(sEpisode)
                                        }
                                    }
                                    val nextPage = resJson.optInt("nextPage", -1)
                                    if (nextPage > page) {
                                        page = nextPage
                                    } else {
                                        hasNext = false
                                    }
                                } else {
                                    hasNext = false
                                }
                            } else {
                                hasNext = false
                            }
                        } catch (e: Exception) {
                            hasNext = false
                        }
                    }
                }
            }
            futures.forEach {
                try {
                    it.get(15, TimeUnit.SECONDS)
                } catch (_: Exception) {}
            }
            pool.shutdown()
        } else {
            val eps = json.optJSONArray("episodes")
            if (eps != null) {
                for (i in 0 until eps.length()) {
                    val ep = eps.getJSONObject(i)
                    val epId = ep.optString("id")
                    val epTitle = ep.optString("t")
                    val epNum = ep.optString("ep").replace("E", "").toFloatOrNull() ?: 1.0f
                    val sEpisode = SEpisode.create()
                    sEpisode.name = epTitle
                    sEpisode.url = epId
                    sEpisode.episode_number = epNum
                    episodeList.add(sEpisode)
                }
            }
        }

        return episodeList.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        ensureSession()
        val tm = System.currentTimeMillis() / 1000
        return GET("$baseUrl/pv/playlist.php?id=${episode.url}&tm=$tm", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonStr = response.body.string()
        val videoList = mutableListOf<Video>()

        try {
            val jsonArray = JSONArray(jsonStr)
            if (jsonArray.length() > 0) {
                val playlistObj = jsonArray.getJSONObject(0)
                val sources = playlistObj.optJSONArray("sources")
                val tracks = playlistObj.optJSONArray("tracks")

                val subtitleTracks = mutableListOf<eu.kanade.tachiyomi.animesource.model.Track>()
                if (tracks != null) {
                    for (i in 0 until tracks.length()) {
                        val track = tracks.getJSONObject(i)
                        val file = track.optString("file")
                        val label = track.optString("label")
                        val kind = track.optString("kind")
                        if (kind == "captions" && file.isNotEmpty() && label.isNotEmpty()) {
                            val absoluteFile = when {
                                file.startsWith("//") -> "https:$file"
                                file.startsWith("http") -> file
                                else -> "$baseUrl$file"
                            }
                            subtitleTracks.add(eu.kanade.tachiyomi.animesource.model.Track(absoluteFile, label))
                        }
                    }
                }

                if (sources != null) {
                    val videoHeaders = headersBuilder()
                        .set("Referer", "$baseUrl/")
                        .build()

                    for (i in 0 until sources.length()) {
                        val source = sources.getJSONObject(i)
                        val file = source.optString("file")
                        val label = source.optString("label")
                        if (file.isNotEmpty()) {
                            val absoluteUrl = if (file.startsWith("http")) file else "$baseUrl$file"
                            val proxiedUrl = getProxyUrl(absoluteUrl, videoHeaders)
                            videoList.add(Video(proxiedUrl, label, proxiedUrl, headers = videoHeaders, subtitleTracks = subtitleTracks))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NetMirror", "Failed to parse video list. Response: $jsonStr", e)
        }

        android.util.Log.d("NetMirror", "Getting the video right: parsed ${videoList.size} video(s)")
        return videoList.sortVideos()
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("720p")
            summary = "%s"
        }.also(screen::addPreference)
    }

    private fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, "720p") ?: "720p"
        return sortedWith(
            compareBy { video ->
                val videoQuality = video.quality
                if (videoQuality.contains(quality)) {
                    0
                } else {
                    1
                }
            },
        )
    }

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    private fun getProxyUrl(targetUrl: String, headers: okhttp3.Headers?): String = Companion.getProxyUrl(this, targetUrl, headers)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val PREF_QUALITY_KEY = "preferred_quality"

        private var proxy: NetMirrorProxy? = null

        @Synchronized
        fun getProxyUrl(source: NetMirror, targetUrl: String, headers: okhttp3.Headers?): String {
            if (proxy == null) {
                proxy = NetMirrorProxy(source.client, source.baseUrl, USER_AGENT)
            }
            return proxy!!.getProxyUrl(targetUrl, headers)
        }
    }
}

class NetMirrorProxy(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val userAgent: String,
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
                    } catch (e: Exception) {
                        android.util.Log.e("NetMirrorProxy", "Accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("NetMirrorProxy", "Init error", e)
        }
    }

    private fun getProxyExtension(targetUrl: String): String {
        val uri = try {
            targetUrl.toHttpUrl()
        } catch (_: Exception) {
            null
        }
        val lastPathSegment = uri?.pathSegments?.lastOrNull() ?: ""
        return when {
            targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl") -> "playlist.m3u8"

            lastPathSegment.endsWith(".mp4") || targetUrl.contains(".mp4") -> "video.mp4"

            lastPathSegment.endsWith(".mkv") || targetUrl.contains(".mkv") -> "video.mkv"

            lastPathSegment.endsWith(".webm") || targetUrl.contains(".webm") -> "video.webm"

            lastPathSegment.endsWith(".m4s") || targetUrl.contains(".m4s") -> "segment.m4s"

            lastPathSegment.endsWith(".m4v") || targetUrl.contains(".m4v") -> "segment.m4v"

            lastPathSegment.endsWith(".m4a") || targetUrl.contains(".m4a") -> "segment.m4a"

            lastPathSegment.contains(".") -> {
                val suffix = lastPathSegment.substringAfterLast(".")
                if (suffix.length in 2..4 && suffix.all { it.isLetterOrDigit() }) {
                    "file.$suffix"
                } else {
                    "segment.ts"
                }
            }

            else -> "segment.ts"
        }
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
        val ext = getProxyExtension(targetUrl)
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
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains(".m3u8")

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

            if (targetHeaders.get("User-Agent").isNullOrEmpty()) {
                targetHeaders.set("User-Agent", userAgent)
            }

            // Always add Referer/Origin for NetMirror CDN requests
            targetHeaders.set("Referer", "$baseUrl/")
            targetHeaders.set("Origin", baseUrl)

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

            android.util.Log.d("NetMirrorProxy", "Getting the video right: forwarding proxy request to CDN: $targetUrl")
            val request = Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders)
            }
        } catch (e: Exception) {
            android.util.Log.e("NetMirrorProxy", "Socket error for targetUrl=$targetUrl", e)
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
        val contentType = response.header("Content-Type")
        val isM3u8 = targetUrl.contains(".m3u8") || contentType?.contains("mpegurl", ignoreCase = true) == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            try {
                val bodyString = response.body.string()
                val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
                modifiedContentBytes = modifiedContent.toByteArray()
            } catch (e: Exception) {
                android.util.Log.e("NetMirrorProxy", "sendResponse m3u8 modify error", e)
            }
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
            if (!contentType.isNullOrEmpty()) {
                out.write("Content-Type: $contentType\r\n".toByteArray())
            } else {
                val proxyExt = getProxyExtension(targetUrl)
                val fallbackType = when {
                    proxyExt.endsWith(".mp4") -> "video/mp4"
                    proxyExt.endsWith(".m4s") -> "video/iso.segment"
                    proxyExt.endsWith(".m4v") -> "video/x-m4v"
                    proxyExt.endsWith(".m4a") -> "audio/mp4"
                    proxyExt.endsWith(".mkv") -> "video/x-matroska"
                    proxyExt.endsWith(".webm") -> "video/webm"
                    else -> "video/mp2t"
                }
                out.write("Content-Type: $fallbackType\r\n".toByteArray())
            }
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
            var trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val cleanUriValue = if (uriValue.startsWith("https://.")) uriValue.replace("https://.", "https://s13.") else uriValue
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolveUrl(playlistUrl, cleanUriValue), encodedHeaders)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else if (!trimmed.startsWith("#EXT-X-PLAYLIST-TYPE")) {
                    builder.append(trimmed)
                }
            } else {
                if (trimmed.startsWith("https://.")) {
                    trimmed = trimmed.replace("https://.", "https://s13.")
                }
                builder.append(getProxyUrlWithEncodedHeaders(resolveUrl(playlistUrl, trimmed), encodedHeaders))
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
        val ext = getProxyExtension(targetUrl)
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

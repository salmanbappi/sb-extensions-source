package eu.kanade.tachiyomi.animeextension.all.netmirror

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NetMirror :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "NetMirror"

    override val baseUrl = "https://net52.cc"

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
                    warmupWebViewSession()
                    chain.proceed(request)
                } else {
                    throw e
                }
            }

            if ((host.contains("net52.cc") || host.contains("net11.cc")) && (response.code == 403 || response.code == 503)) {
                response.close()
                warmupWebViewSession()
                response = chain.proceed(request)
            }

            response
        }
        .build()

    override fun headersBuilder(): okhttp3.Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

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
                wv.loadUrl("$baseUrl/home")
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

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/pv/homepage.php", headers)

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

        val uniqueIds = ids.distinct()

        val pool = java.util.concurrent.Executors.newFixedThreadPool(10)
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
                it.get(10, java.util.concurrent.TimeUnit.SECONDS)
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

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/pv/post.php?id=${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val json = JSONObject(response.body.string())
        val id = response.request.url.queryParameter("id") ?: ""
        val anime = SAnime.create()
        anime.title = json.optString("title")
        anime.description = json.optString("desc")
        anime.genre = json.optString("genre")
        anime.author = json.optString("creator").ifEmpty { json.optString("writer") }
        anime.artist = json.optString("director")
        anime.status = SAnime.UNKNOWN
        anime.thumbnail_url = "https://imgcdn.kim/pv/341/$id.jpg"
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
            val pool = java.util.concurrent.Executors.newFixedThreadPool(5)
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
                                    hasNext = nextPage > page
                                    page = nextPage
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
                    it.get(15, java.util.concurrent.TimeUnit.SECONDS)
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
        val tm = System.currentTimeMillis() / 1000
        return GET("$baseUrl/pv/playlist.php?id=${episode.url}&tm=$tm", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonStr = response.body.string()
        val videoList = mutableListOf<Video>()

        try {
            val jsonArray = org.json.JSONArray(jsonStr)
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
                            val absoluteFile = if (file.startsWith("//")) "https:$file" else file
                            subtitleTracks.add(eu.kanade.tachiyomi.animesource.model.Track(absoluteFile, label))
                        }
                    }
                }

                if (sources != null) {
                    val videoHeaders = headersBuilder()
                        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()

                    var parsedAny = false
                    for (i in 0 until sources.length()) {
                        val source = sources.getJSONObject(i)
                        val file = source.optString("file")
                        val label = source.optString("label")
                        if (file.isNotEmpty()) {
                            val absoluteUrl = "$baseUrl$file"
                            if (!parsedAny && i == 0) {
                                try {
                                    val playlistResponse = client.newCall(GET(absoluteUrl, headers)).execute()
                                    if (playlistResponse.isSuccessful) {
                                        val playlistContent = playlistResponse.body.string()
                                        if (playlistContent.contains("#EXT-X-STREAM-INF:")) {
                                            val lines = playlistContent.lines()
                                            var lastResolution = "Video"
                                            for (line in lines) {
                                                val trimmed = line.trim()
                                                if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                                                    val resRegex = """RESOLUTION=(\d+x\d+)""".toRegex()
                                                    val match = resRegex.find(trimmed)
                                                    lastResolution = if (match != null) {
                                                        val res = match.groupValues[1]
                                                        if (res.contains("1920x1080")) {
                                                            "1080p"
                                                        } else if (res.contains("1280x720")) {
                                                            "720p"
                                                        } else if (res.contains("854x480")) {
                                                            "480p"
                                                        } else {
                                                            res
                                                        }
                                                    } else {
                                                        "Video"
                                                    }
                                                } else if (trimmed.startsWith("http") && trimmed.contains(".m3u8")) {
                                                    videoList.add(
                                                        Video(trimmed, lastResolution, trimmed, headers = videoHeaders, subtitleTracks = subtitleTracks),
                                                    )
                                                    parsedAny = true
                                                }
                                            }
                                        }
                                    }
                                    playlistResponse.close()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            if (!parsedAny) {
                                videoList.add(Video(absoluteUrl, label, absoluteUrl, headers = videoHeaders, subtitleTracks = subtitleTracks))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return videoList
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {}
}

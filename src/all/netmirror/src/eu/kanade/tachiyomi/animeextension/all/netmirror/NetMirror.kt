package eu.kanade.tachiyomi.animeextension.all.netmirror

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.TimeUnit

class NetMirror : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        CNCVerseSource("Netflix", "nf", "", 5181466391484419888L),
        CNCVerseSource("Prime Video", "pv", "", 5181466391484419889L),
        CNCVerseSource("Hotstar", "hs", "", 5181466391484419890L),
        CNCVerseSource("Disney", "dp", "disney", 5181466391484419891L),
        CNCVerseSource("Marvel", "dp", "marvel", 5181466391484419892L),
        CNCVerseSource("Star Wars", "dp", "starwars", 5181466391484419893L),
        CNCVerseSource("Pixar", "dp", "pixar", 5181466391484419894L),
    )
}

class CNCVerseSource(
    override val name: String,
    private val ott: String,
    private val studio: String,
    override val id: Long,
) : Source() {

    override val baseUrl = "https://net77.cc"
    override val lang = "all"
    override val supportsLatest = false

    private val ottPath: String
        get() = when (ott) {
            "nf" -> ""
            "pv" -> "pv"
            else -> "hs"
        }

    private fun getPosterUrl(id: String): String = when (ott) {
        "nf" -> "https://imgcdn.kim/poster/v/$id.jpg"
        "pv" -> "https://imgcdn.kim/pv/v/$id.jpg"
        else -> "https://imgcdn.kim/hs/v/$id.jpg"
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        // Inject auth cookie for net77.cc requests and handle verify redirects
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("net77.cc") || url.contains("net11.cc")) {
                var cookieVal = getBypassCookie()
                if (cookieVal.isNotEmpty()) {
                    var cookieHeader = buildString {
                        append("t_hash_t=$cookieVal")
                        append("; ott=$ott")
                        append("; hd=on")
                        if (studio.isNotEmpty()) {
                            append("; studio=$studio")
                        }
                    }
                    val refererUrl = "$baseUrl/home"
                    var newRequest = request.newBuilder()
                        .header("Cookie", cookieHeader)
                        .header("Referer", refererUrl)
                        .build()
                    var response = chain.proceed(newRequest)

                    if (response.code == 302 || response.request.url.toString().contains("verify")) {
                        response.close()
                        clearBypassCookie()
                        cookieVal = getBypassCookie(force = true)
                        if (cookieVal.isNotEmpty()) {
                            cookieHeader = buildString {
                                append("t_hash_t=$cookieVal")
                                append("; ott=$ott")
                                append("; hd=on")
                                if (studio.isNotEmpty()) {
                                    append("; studio=$studio")
                                }
                            }
                            newRequest = request.newBuilder()
                                .header("Cookie", cookieHeader)
                                .header("Referer", refererUrl)
                                .build()
                            response = chain.proceed(newRequest)
                        }
                    }
                    return@addInterceptor response
                }
            }
            chain.proceed(request)
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            val response = chain.proceed(request)

            if (response.isSuccessful && (url.contains("/hls/") || url.contains(".m3u8")) && !url.contains("nm-cdn")) {
                val originalBody = response.body?.string() ?: ""
                val epIdFromUrl = url.substringAfter("/hls/").substringBefore(".m3u8").substringBefore("?").substringAfterLast("/")
                val fixedBody = originalBody
                    .replace("s21.freecdn4.top", "s23.nm-cdn9.top")
                    .replace("s21.nm-cdn4.top", "s23.nm-cdn9.top")
                    .replace("freecdn4.top", "nm-cdn9.top")
                    .replace("nm-cdn4.top", "nm-cdn9.top")
                    .replace("nm-cdn.top", "nm-cdn9.top")
                    .replace("220884", epIdFromUrl)
                val mediaType = response.body?.contentType()
                return@addInterceptor response.newBuilder()
                    .body(okhttp3.ResponseBody.create(mediaType, fixedBody))
                    .build()
            }

            response
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains(".m3u8") || url.contains(".jpg") || url.contains(".ts") || url.contains(".vtt") || url.contains("nm-cdn")) {
                val newRequest = request.newBuilder()
                    .header("Referer", "https://net52.cc/")
                    .header("Cookie", "hd=on")
                    .build()
                return@addInterceptor chain.proceed(newRequest)
            }
            chain.proceed(request)
        }
        .build()

    override fun headersBuilder(): okhttp3.Headers.Builder = super.headersBuilder()
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .set("Accept-Language", "en-IN,en-US;q=0.9,en;q=0.8")
        .set("Cache-Control", "max-age=0")
        .set("Connection", "keep-alive")
        .set("sec-ch-ua", "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"")
        .set("sec-ch-ua-mobile", "?0")
        .set("sec-ch-ua-platform", "\"Android\"")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .set("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0")
        .set("X-Requested-With", "XMLHttpRequest")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val path = when (ott) {
            "nf" -> "series"
            "pv" -> "series"
            else -> "series"
        }
        return GET("$baseUrl/$path", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = org.jsoup.Jsoup.parse(response.body.string())
        val animeList = mutableListOf<SAnime>()
        // Desktop pages expose data-post IDs on .open-modal and .slider-item elements
        val elements = document.select("[data-post]")
        for (element in elements) {
            val id = element.attr("data-post").trim()
            if (id.isEmpty()) continue
            val anime = SAnime.create()
            // Title comes from aria-label on nested link or alt on img; fall back to ID
            val title = element.selectFirst("a[aria-label]")?.attr("aria-label")
                ?.takeIf { it.isNotBlank() && it != "Loading" }
                ?: element.selectFirst("img")?.attr("alt")
                    ?.takeIf { it.isNotBlank() }
                ?: id
            anime.title = title
            anime.url = id
            anime.thumbnail_url = getPosterUrl(id)
            animeList.add(anime)
        }
        return AnimesPage(animeList.distinctBy { it.url }, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        // Desktop search.php is accessible directly (no /mobile/ redirect to dead net50.cc)
        return GET("$baseUrl/search.php?s=$encodedQuery&t=${System.currentTimeMillis() / 1000}", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val json = response.body.string()
        val jsonObject = JSONObject(json)
        val searchResult = jsonObject.optJSONArray("searchResult") ?: return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        for (i in 0 until searchResult.length()) {
            val item = searchResult.getJSONObject(i)
            val id = item.optString("id")
            val title = item.optString("t")
            if (id.isNotEmpty()) {
                val anime = SAnime.create()
                anime.title = title
                anime.url = id
                anime.thumbnail_url = getPosterUrl(id)
                animeList.add(anime)
            }
        }
        return AnimesPage(animeList, false)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        // Desktop post.php is accessible directly — /mobile/ paths redirect to dead net50.cc
        return GET("$baseUrl/post.php?id=${anime.url}&t=${System.currentTimeMillis() / 1000}", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val json = response.body.string()
        val data = JSONObject(json)
        val anime = SAnime.create()
        anime.title = data.optString("title")

        val genreVal = data.optString("genre")
        if (genreVal.isNotEmpty()) {
            anime.genre = genreVal.split(",").joinToString { it.trim() }
        }

        anime.author = data.optString("director").ifEmpty { data.optString("cast") }
        anime.status = SAnime.UNKNOWN

        val desc = StringBuilder()
        data.optString("desc").takeIf { it.isNotEmpty() }?.let {
            desc.append(it).append("\n\n")
        }
        val details = mutableListOf<String>()
        data.optString("year").takeIf { it.isNotEmpty() }?.let {
            details.add("Year: $it")
        }
        data.optString("ua").takeIf { it.isNotEmpty() }?.let {
            details.add("Age Rating: $it")
        }
        data.optString("runtime").takeIf { it.isNotEmpty() }?.let {
            details.add("Runtime: $it")
        }
        data.optString("match").takeIf { it.isNotEmpty() }?.let {
            details.add("Rating: $it")
        }
        if (details.isNotEmpty()) {
            desc.append(details.joinToString("\n"))
        }
        anime.description = desc.toString()

        val id = response.request.url.queryParameter("id") ?: ""
        anime.thumbnail_url = getPosterUrl(id)
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val json = response.body.string()
        val data = JSONObject(json)
        val episodes = mutableListOf<SEpisode>()
        val episodesArray = data.optJSONArray("episodes")
        val isMovie = episodesArray == null || episodesArray.length() == 0 || episodesArray.isNull(0)

        val id = response.request.url.queryParameter("id") ?: ""

        if (isMovie) {
            val sEpisode = SEpisode.create()
            sEpisode.name = "Movie"
            sEpisode.episode_number = 1.0f
            sEpisode.url = id
            episodes.add(sEpisode)
        } else {
            for (i in 0 until episodesArray.length()) {
                if (episodesArray.isNull(i)) continue
                val ep = episodesArray.getJSONObject(i)
                val epId = ep.optString("id")
                val epTitle = ep.optString("t")
                val epNumStr = ep.optString("ep").replace("E", "")
                val epNum = epNumStr.toFloatOrNull() ?: 1.0f
                val seasonStr = ep.optString("s").replace("S", "")

                val sEpisode = SEpisode.create()
                sEpisode.name = "S$seasonStr E$epNum - $epTitle"
                sEpisode.url = epId
                sEpisode.episode_number = epNum
                episodes.add(sEpisode)
            }

            val nextPageShow = data.optInt("nextPageShow", 0)
            if (nextPageShow == 1) {
                val nextPageSeason = data.optString("nextPageSeason")
                episodes.addAll(getEpisodes(id, nextPageSeason, 2))
            }

            val seasonsArray = data.optJSONArray("season")
            if (seasonsArray != null) {
                for (i in 0 until seasonsArray.length() - 1) {
                    val seasonObj = seasonsArray.getJSONObject(i)
                    val seasonId = seasonObj.optString("id")
                    episodes.addAll(getEpisodes(id, seasonId, 1))
                }
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private fun getEpisodes(eid: String, sid: String, page: Int): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var pg = page
        while (true) {
            // Desktop episodes.php — /mobile/ paths redirect to dead net50.cc
            val url = "$baseUrl/episodes.php?s=$sid&series=$eid&t=${System.currentTimeMillis() / 1000}&page=$pg"
            val request = GET(url, headers)
            val response = try {
                client.newCall(request).execute()
            } catch (e: Exception) {
                break
            }
            if (!response.isSuccessful) break
            val json = response.body.string()
            val jsonObj = try {
                JSONObject(json)
            } catch (e: Exception) {
                break
            }
            val epsArray = jsonObj.optJSONArray("episodes") ?: break
            if (epsArray.length() == 0) break

            for (i in 0 until epsArray.length()) {
                if (epsArray.isNull(i)) continue
                val ep = epsArray.getJSONObject(i)
                val epId = ep.optString("id")
                val epTitle = ep.optString("t")
                val epNumStr = ep.optString("ep").replace("E", "")
                val epNum = epNumStr.toFloatOrNull() ?: 1.0f
                val seasonStr = ep.optString("s").replace("S", "")

                val sEpisode = SEpisode.create()
                sEpisode.name = "S$seasonStr E$epNum - $epTitle"
                sEpisode.url = epId
                sEpisode.episode_number = epNum
                episodes.add(sEpisode)
            }
            if (jsonObj.optInt("nextPageShow", 0) == 0) break
            pg++
        }
        return episodes
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val formBody = FormBody.Builder()
            .add("id", episode.url)
            .build()

        val cookieVal = getBypassCookie()
        val cookieHeader = buildString {
            if (cookieVal.isNotEmpty()) {
                append("t_hash_t=$cookieVal; ")
            }
            append("ott=$ott; ")
            append("hd=on")
            if (studio.isNotEmpty()) {
                append("; studio=$studio")
            }
        }

        val requestHeaders = headers.newBuilder()
            .set("Cookie", cookieHeader)
            .set("Referer", "$baseUrl/home")
            .build()

        return Request.Builder()
            .url("$baseUrl/play.php")
            .headers(requestHeaders)
            .post(formBody)
            .build()
    }

    override fun videoListParse(response: Response): List<Video> {
        val json = response.body.string()
        val jsonObj = try {
            JSONObject(json)
        } catch (e: Exception) {
            JSONObject()
        }
        val hToken = jsonObj.optString("h")

        if (hToken.isEmpty()) {
            return emptyList()
        }

        val episodeId = response.request.body?.let { body ->
            if (body is FormBody) {
                (0 until body.size).firstOrNull { body.name(it) == "id" }?.let { body.value(it) }
            } else {
                null
            }
        } ?: ""

        val cookieVal = getBypassCookie()
        val cookieHeader = buildString {
            if (cookieVal.isNotEmpty()) {
                append("t_hash_t=$cookieVal; ")
            }
            append("ott=$ott; ")
            append("hd=on")
            if (studio.isNotEmpty()) {
                append("; studio=$studio")
            }
        }

        val requestHeaders = headers.newBuilder()
            .set("Cookie", cookieHeader)
            .set("Referer", "$baseUrl/home")
            .build()

        val playerDomains = listOf(baseUrl)
        var workingDomain = baseUrl
        var dataTime = ""
        var dataH = ""

        for (domain in playerDomains) {
            val iframeRequest = Request.Builder()
                .url("$domain/play.php?id=$episodeId&$hToken")
                .headers(requestHeaders)
                .build()

            try {
                client.newCall(iframeRequest).execute().use { iframeResponse ->
                    if (iframeResponse.isSuccessful) {
                        val html = iframeResponse.body.string()
                        val foundTime = Regex("""data-time=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""
                        val foundH = Regex("""data-h=["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: ""
                        if (foundTime.isNotEmpty()) {
                            dataTime = foundTime
                            dataH = foundH
                            workingDomain = domain
                        }
                    }
                }
                if (dataTime.isNotEmpty()) break
            } catch (e: Exception) {
                // Try next domain
            }
        }

        val finalH = if (dataH.isNotEmpty()) dataH else hToken

        val playlistUrl = "$workingDomain/playlist.php?id=$episodeId&t=&tm=$dataTime&h=$finalH"
        val playlistRequest = Request.Builder()
            .url(playlistUrl)
            .headers(requestHeaders)
            .build()

        val playlistJson = try {
            client.newCall(playlistRequest).execute().use { it.body.string() }
        } catch (e: Exception) {
            return emptyList()
        }

        val playlistArray = try {
            JSONArray(playlistJson)
        } catch (e: Exception) {
            return emptyList()
        }
        if (playlistArray.length() == 0) return emptyList()

        val firstItem = playlistArray.getJSONObject(0)
        val sources = firstItem.optJSONArray("sources") ?: return emptyList()
        if (sources.length() == 0) return emptyList()

        val videoLinkFile = sources.optJSONObject(0)?.optString("file") ?: ""
        val cleanToken = finalH.removePrefix("in=").substringBefore("&")
        var videoLink = ""
        if (videoLinkFile.isNotEmpty()) {
            val fixedVideoLinkFile = videoLinkFile.replace("unknown::db", cleanToken)
            videoLink = if (fixedVideoLinkFile.startsWith("http")) fixedVideoLinkFile else "$workingDomain$fixedVideoLinkFile"
        }

        if (videoLink.isEmpty() || videoLink.contains("unknown")) {
            // NewTV API Fallback
            try {
                val newTvPlayerReq = Request.Builder()
                    .url("https://tv.imgcdn.kim/newtv/player.php?id=$episodeId")
                    .header("X-Requested-With", "NetmirrorNewTV v1.0")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0")
                    .header("Ott", ott)
                    .build()
                client.newCall(newTvPlayerReq).execute().use { newTvResp ->
                    if (newTvResp.isSuccessful) {
                        val newTvJson = JSONObject(newTvResp.body.string())
                        val link = newTvJson.optString("video_link")
                        if (link.isNotEmpty()) {
                            videoLink = if (cleanToken.isNotEmpty() && !link.contains("in=")) "$link?in=$cleanToken" else link
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore fallback error
            }
        }

        val playlistUtils = PlaylistUtils(client, headers)

        val masterHeadersGen = { baseHeaders: Headers, ref: String ->
            val headers = playlistUtils.generateMasterHeaders(baseHeaders, ref)
            headers.newBuilder().apply {
                set("Referer", "https://net52.cc/")
                if (cookieVal.isNotEmpty()) {
                    set("Cookie", "t_hash_t=$cookieVal; ott=$ott; hd=on" + if (studio.isNotEmpty()) "; studio=$studio" else "")
                }
            }.build()
        }

        val videoHeadersGen = { baseHeaders: Headers, ref: String, videoUrl: String ->
            val headers = playlistUtils.generateMasterHeaders(baseHeaders, ref)
            headers.newBuilder().apply {
                set("Referer", "https://net52.cc/")
                if (cookieVal.isNotEmpty()) {
                    set("Cookie", "t_hash_t=$cookieVal; ott=$ott; hd=on" + if (studio.isNotEmpty()) "; studio=$studio" else "")
                }
            }.build()
        }

        val videos = try {
            playlistUtils.extractFromHls(
                playlistUrl = videoLink,
                referer = "$workingDomain/play.php",
                masterHeadersGen = masterHeadersGen,
                videoHeadersGen = videoHeadersGen,
                videoNameGen = { "$name - $it" },
            )
        } catch (e: Exception) {
            emptyList()
        }

        val finalVideos = if (videos.isEmpty() && videoLink.isNotEmpty()) {
            val vHeaders = masterHeadersGen(headers, "$workingDomain/play.php")
            listOf(
                Video(
                    url = videoLink,
                    quality = "$name - Direct Stream",
                    videoUrl = videoLink,
                    headers = vHeaders,
                )
            )
        } else {
            videos
        }

        return finalVideos.map { video ->
            if (video.subtitleTracks.isEmpty()) {
                video
            } else {
                Video(
                    url = video.videoUrl,
                    quality = video.videoTitle,
                    videoUrl = video.videoUrl,
                    subtitleTracks = video.subtitleTracks.map { track ->
                        if (track.url.endsWith(".m3u8")) {
                            Track(track.url.substringBeforeLast(".m3u8") + ".vtt", track.lang)
                        } else {
                            track
                        }
                    },
                    audioTracks = video.audioTracks,
                    headers = video.headers,
                )
            }
        }.sortVideos()
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        androidx.preference.ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("1080p")
            summary = "%s"
        }.also(screen::addPreference)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p") ?: "1080p"
        return sortedWith(
            compareBy { video ->
                val videoQuality = video.videoTitle
                if (videoQuality.contains(quality)) {
                    0
                } else {
                    1
                }
            },
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    companion object {
        private const val MAIN_BASE_URL = "https://net77.cc"

        private val sharedPreferences: SharedPreferences by lazy {
            Injekt.get<Application>().getSharedPreferences("cncverse_shared_prefs", 0)
        }

        private var cookieValue = ""
        private var cookieTimestamp = 0L

        private val verifyClient by lazy {
            OkHttpClient.Builder().followRedirects(false).build()
        }

        @Synchronized
        private fun getBypassCookie(force: Boolean = false): String {
            val now = System.currentTimeMillis()
            val savedCookie = if (force) null else sharedPreferences.getString("nf_cookie", null)
            val savedTimestamp = if (force) 0L else sharedPreferences.getLong("nf_cookie_timestamp", 0L)

            if (!savedCookie.isNullOrEmpty() && now - savedTimestamp < 54_000_000) {
                cookieValue = savedCookie
                cookieTimestamp = savedTimestamp
                return savedCookie
            }

            try {
                val formBody = FormBody.Builder()
                    .add("g-recaptcha-response", UUID.randomUUID().toString())
                    .build()

                val request = Request.Builder()
                    .url("$MAIN_BASE_URL/verify.php")
                    .post(formBody)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Encoding", "gzip, deflate, br, zstd")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cache-Control", "max-age=0")
                    .header("Connection", "keep-alive")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", MAIN_BASE_URL)
                    .header("Referer", "$MAIN_BASE_URL/verify2")
                    .header("sec-ch-ua", "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                    .build()

                verifyClient.newCall(request).execute().use { response ->
                    val setCookieHeaders = response.headers("Set-Cookie")
                    for (header in setCookieHeaders) {
                        if (header.startsWith("t_hash_t=")) {
                            val cookie = header.substringAfter("t_hash_t=").substringBefore(";")
                            if (cookie.isNotEmpty()) {
                                cookieValue = cookie
                                cookieTimestamp = now
                                sharedPreferences.edit()
                                    .putString("nf_cookie", cookie)
                                    .putLong("nf_cookie_timestamp", now)
                                    .apply()
                                return cookie
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Silently fallback to cached or empty cookie
            }
            return cookieValue
        }

        @Synchronized
        private fun clearBypassCookie() {
            cookieValue = ""
            cookieTimestamp = 0L
            sharedPreferences.edit()
                .remove("nf_cookie")
                .remove("nf_cookie_timestamp")
                .apply()
        }

        private fun decodeBase64(value: String): String = String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
    }
}

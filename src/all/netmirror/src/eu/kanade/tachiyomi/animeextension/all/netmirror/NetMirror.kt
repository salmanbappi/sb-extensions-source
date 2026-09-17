package eu.kanade.tachiyomi.animeextension.all.netmirror

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import extensions.utils.Source
import extensions.utils.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

private const val TAG = "NetMirror"
private const val DEFAULT_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 12; RMX2117 Build/SP1A.210812.016; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.55 Mobile Safari/537.36 /OS.Gatu v3.0"
private const val APP_REQUESTED_WITH = "app.netmirror.netmirrornew"

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

    override val baseUrl = "https://net52.cc"
    override val lang = "all"
    override val supportsLatest = false

    private val mobileHomeUrl: String
        get() = "$baseUrl/mobile/home?app=1"

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

    /**
     * Cookie header for site requests: this source's own bypass cookie **plus** whatever the
     * app's cookie jar holds for the host — notably the `cf_clearance` that
     * [CloudflareInterceptor] solved. Replacing the header outright would throw the clearance
     * away and force a fresh WebView challenge on literally every request.
     */
    private fun siteCookieHeader(url: HttpUrl, cookieVal: String): String {
        val parts = mutableListOf<String>()
        if (cookieVal.isNotEmpty()) parts.add("t_hash_t=$cookieVal")
        parts.add("ott=$ott")
        parts.add("hd=on")
        if (studio.isNotEmpty()) parts.add("studio=$studio")
        network.client.cookieJar.loadForRequest(url)
            .filter { cookie -> parts.none { it.startsWith("${cookie.name}=") } }
            .forEach { cookie -> parts.add("${cookie.name}=${cookie.value}") }
        return parts.joinToString("; ")
    }

    private fun mediaCookieHeader(url: HttpUrl): String {
        val parts = mutableListOf("hd=on")
        network.client.cookieJar.loadForRequest(url)
            .filter { cookie -> cookie.name != "hd" }
            .forEach { cookie -> parts.add("${cookie.name}=${cookie.value}") }
        return parts.joinToString("; ")
    }

    /** True when a response is an HTML page (verification/ad wall) instead of expected JSON. */
    private fun isHtmlResponse(response: Response): Boolean {
        if (response.header("Content-Type").orEmpty().contains("html", ignoreCase = true)) return true
        return try {
            response.peekBody(256).string().trimStart().startsWith("<")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Determines whether the server returned the verification / ad wall instead of real content.
     * For HTML pages like /mobile/home, HTML is expected and only rejected if it lacks content trays
     * or contains the ad wall prompt ("We Need Support").
     * For .php API endpoints (search.php, post.php, playlist.php, episodes.php), HTML means
     * the session was rejected since JSON was expected.
     */
    private fun isVerificationWall(url: String, response: Response): Boolean {
        if (response.code == 302 || response.request.url.toString().contains("verify")) {
            return true
        }
        if (url.contains("/mobile/home")) {
            val peek = try {
                response.peekBody(32768).string()
            } catch (e: Exception) {
                ""
            }
            if (peek.contains("<title>Home - Android Mobile</title>", ignoreCase = true) ||
                peek.contains("tray-container") ||
                peek.contains("<article")
            ) {
                return false
            }
            return true
        }
        if (url.contains(".php")) {
            return isHtmlResponse(response)
        }
        return false
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (!url.contains("net52.cc") && !url.contains("net11.cc")) {
                return@addInterceptor chain.proceed(request)
            }

            val refererUrl = if (url.contains("/mobile/")) mobileHomeUrl else "$baseUrl/home"
            var cookieVal = getBypassCookie()
            var response = chain.proceed(
                request.newBuilder()
                    .header("Cookie", siteCookieHeader(request.url, cookieVal))
                    .header("Referer", refererUrl)
                    .build(),
            )

            // Retry once if the response hit the verification / ad wall
            val rejected = isVerificationWall(url, response)
            if (rejected) {
                Log.w(TAG, "bypass cookie rejected or missing for $url (HTTP ${response.code}) — refreshing")
                response.close()
                clearBypassCookie()
                cookieVal = getBypassCookie(force = true)
                if (cookieVal.isNotEmpty()) {
                    response = chain.proceed(
                        request.newBuilder()
                            .header("Cookie", siteCookieHeader(request.url, cookieVal))
                            .header("Referer", refererUrl)
                            .build(),
                    )
                }
            }
            response
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains(".m3u8") || url.contains(".vtt")) {
                val existingCookie = request.header("Cookie")
                val cookie = if (!existingCookie.isNullOrEmpty()) {
                    existingCookie
                } else {
                    mediaCookieHeader(request.url)
                }
                return@addInterceptor chain.proceed(
                    request.newBuilder()
                        .header("Cookie", cookie)
                        .build(),
                )
            }
            chain.proceed(request)
        }
        .addInterceptor(CloudflareInterceptor(network.client, DEFAULT_USER_AGENT))
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
        .set("User-Agent", DEFAULT_USER_AGENT)
        .set("X-Requested-With", "XMLHttpRequest")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/mobile/home?app=1", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = org.jsoup.Jsoup.parse(response.body.string())
        val animeList = mutableListOf<SAnime>()
        val articles = document.select(".tray-container article, #top10 .top10-post")
        for (element in articles) {
            val id = element.selectFirst("a")?.attr("data-post")
                ?: element.attr("data-post")
                ?: continue
            if (id.isEmpty()) continue

            val title = element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: element.selectFirst("img")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: element.selectFirst("a")?.attr("title")?.takeIf { it.isNotBlank() }
                ?: element.selectFirst(".card-title")?.text()?.takeIf { it.isNotBlank() }
                ?: element.selectFirst("h3")?.text()?.takeIf { it.isNotBlank() }
                ?: ""

            val img = element.selectFirst("img")
            val thumbnail = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: getPosterUrl(id)

            val anime = SAnime.create().apply {
                this.title = title
                this.url = id
                this.thumbnail_url = thumbnail
            }
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
        val path = if (ottPath.isEmpty()) "search.php" else "$ottPath/search.php"
        return GET("$baseUrl/mobile/$path?s=$encodedQuery&t=${System.currentTimeMillis() / 1000}", headers)
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
        val path = if (ottPath.isEmpty()) "post.php" else "$ottPath/post.php"
        return GET("$baseUrl/mobile/$path?id=${anime.url}&t=${System.currentTimeMillis() / 1000}", headers)
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
            val path = if (ottPath.isEmpty()) "episodes.php" else "$ottPath/episodes.php"
            val url = "$baseUrl/mobile/$path?s=$sid&series=$eid&t=${System.currentTimeMillis() / 1000}&page=$pg"
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

    /*
     * NetMirror publishes per-episode streams through the mobile playlist endpoint
     * (`/mobile/playlist.php`, `/mobile/pv/playlist.php`, `/mobile/hs/playlist.php`) — the same
     * endpoint the reference "CNC Verse Mobile" plugin uses.
     *
     * NetMirror requires a valid `t_hash_t` session cookie to generate genuine playback tokens.
     * Without it, `playlist.php` generates `/mobile/hls/<id>.m3u8?in=unknown::db`, which returns
     * HTTP 404 Apache Not Found on NetMirror's CDN.
     *
     * The bypass flow scrapes `data-addhash` from `mobile/home?app=1`, pings `userver.net52.cc`,
     * and polls `mobile/verify2.php` until the server-side ad verification timer settles with
     * `{"statusup":"All Done"}` and issues the 12-hour `t_hash_t` cookie.
     */

    private fun videoListHeaders(): Headers = headers.newBuilder()
        .set("Accept", "*/*")
        .set("Referer", mobileHomeUrl)
        .set("User-Agent", DEFAULT_USER_AGENT)
        .set("X-Requested-With", APP_REQUESTED_WITH)
        .set("Sec-Fetch-Dest", "empty")
        .set("Sec-Fetch-Mode", "cors")
        .set("Sec-Fetch-Site", "same-origin")
        .removeAll("Sec-Fetch-User")
        .removeAll("Upgrade-Insecure-Requests")
        .build()

    private fun videoListUrl(episode: SEpisode): String {
        val path = if (ottPath.isEmpty()) "playlist.php" else "$ottPath/playlist.php"
        val title = java.net.URLEncoder.encode(episode.name.orEmpty(), "UTF-8")
        return "$baseUrl/mobile/$path?id=${episode.url}&t=$title&tm=${System.currentTimeMillis() / 1000}"
    }

    override fun videoListRequest(episode: SEpisode): Request = GET(videoListUrl(episode), videoListHeaders())

    override suspend fun getVideoList(episode: SEpisode): List<Video> = try {
        val request = videoListRequest(episode)
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        videoListParse(response)
    } catch (e: Throwable) {
        Log.e(TAG, "getVideoList error for episode ${episode.url}", e)
        emptyList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val requestUrl = response.request.url.toString()
        val rawBody = response.body.string()
        Log.i(
            TAG,
            "videoListParse: HTTP ${response.code} ct=${response.header("Content-Type").orEmpty()} " +
                "len=${rawBody.length} hasCookie=${getBypassCookie().isNotEmpty()}",
        )
        var playlist = parsePlaylist(rawBody)

        if (playlist == null) {
            Log.w(TAG, "Invalid playlist or bypass cookie expired, forcing refresh...")
            clearBypassCookie()
            getBypassCookie(force = true)
            playlist = try {
                client.newCall(GET(requestUrl, videoListHeaders())).execute().use { retry ->
                    val retryBody = retry.body.string()
                    Log.i(TAG, "playlist retry: HTTP ${retry.code} len=${retryBody.length}")
                    parsePlaylist(retryBody)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Retry playlist.php request failed", e)
                null
            }
        }

        if (playlist == null) {
            val reason = if (rawBody.contains("in=unknown::db")) "invalid session token" else "non-JSON body"
            displayToast("NetMirror: no playlist (HTTP ${response.code}, len=${rawBody.length}, $reason)")
            return emptyList()
        }

        Log.i(TAG, "playlist parsed: ${playlist.length()} items")
        return buildVideos(playlist)
    }

    private fun parsePlaylist(body: String): JSONArray? = try {
        if (body.contains("in=unknown::db")) {
            Log.w(TAG, "parsePlaylist: body contains in=unknown::db (session token rejected)")
            null
        } else {
            JSONArray(body).takeIf { it.length() > 0 }
        }
    } catch (e: Exception) {
        Log.w(TAG, "parsePlaylist: not JSON (${e.javaClass.simpleName}: ${e.message}) len=${body.length}")
        null
    }

    private fun buildVideos(playlist: JSONArray): List<Video> {
        val cookieHeader = buildString {
            val cookieVal = getBypassCookie()
            if (cookieVal.isNotEmpty()) {
                append("t_hash_t=$cookieVal; ")
            }
            append("ott=$ott; hd=on")
            if (studio.isNotEmpty()) {
                append("; studio=$studio")
            }
        }

        val sources = mutableListOf<Pair<String, String>>()
        val subtitleTracks = mutableListOf<Track>()

        for (i in 0 until playlist.length()) {
            val item = playlist.optJSONObject(i) ?: continue

            item.optJSONArray("sources")?.let { array ->
                for (j in 0 until array.length()) {
                    val source = array.optJSONObject(j) ?: continue
                    val file = source.optString("file").replace("\\", "")
                    if (file.isEmpty()) continue
                    val sourceUrl = UrlUtils.fixUrl(file, baseUrl)
                    if (sources.none { it.second == sourceUrl }) {
                        sources.add(source.optString("label") to sourceUrl)
                    }
                }
            }

            item.optJSONArray("tracks")?.let { array ->
                for (j in 0 until array.length()) {
                    val track = array.optJSONObject(j) ?: continue
                    if (!track.optString("kind").equals("captions", true)) continue
                    val file = track.optString("file").replace("\\", "")
                    val trackUrl = UrlUtils.fixUrl(file, baseUrl)
                    if (trackUrl.isEmpty() || subtitleTracks.any { it.url == trackUrl }) continue
                    subtitleTracks.add(Track(trackUrl, track.optString("label").ifEmpty { "Subtitle" }))
                }
            }
        }

        if (sources.isEmpty()) {
            Log.w(TAG, "buildVideos: playlist contained no sources")
            displayToast("NetMirror: playlist contained no sources (${playlist.length()} items)")
            return emptyList()
        }

        Log.i(TAG, "buildVideos: ${sources.size} sources, ${subtitleTracks.size} subtitle tracks")
        for ((label, sourceUrl) in sources) {
            val host = runCatching { java.net.URI(sourceUrl).host }.getOrNull() ?: "?"
            Log.d(TAG, "source: label='$label' host=$host")
        }

        val playlistUtils = PlaylistUtils(client, headers)

        val masterHeadersGen: (Headers, String) -> Headers = { baseHeaders, ref ->
            playlistUtils.generateMasterHeaders(baseHeaders, ref).newBuilder()
                .set("Cookie", cookieHeader)
                .set("User-Agent", DEFAULT_USER_AGENT)
                .set("X-Requested-With", APP_REQUESTED_WITH)
                .set("Referer", mobileHomeUrl)
                .set("Accept", "*/*")
                .build()
        }

        val videoHeadersGen: (Headers, String, String) -> Headers = { baseHeaders, ref, _ ->
            playlistUtils.generateMasterHeaders(baseHeaders, ref).newBuilder()
                .set("Cookie", cookieHeader)
                .set("User-Agent", DEFAULT_USER_AGENT)
                .set("X-Requested-With", APP_REQUESTED_WITH)
                .set("Referer", mobileHomeUrl)
                .set("Accept", "*/*")
                .build()
        }

        val videos = mutableListOf<Video>()
        val seen = mutableSetOf<String>()

        for ((label, sourceUrl) in sources) {
            val qualityParam = QUALITY_PARAM_REGEX.find(sourceUrl)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            val fallbackName = qualityParam ?: label.ifEmpty { "Video" }

            val extracted = try {
                playlistUtils.extractFromHls(
                    playlistUrl = sourceUrl,
                    referer = mobileHomeUrl,
                    masterHeadersGen = masterHeadersGen,
                    videoHeadersGen = videoHeadersGen,
                    videoNameGen = { quality -> if (quality == "Video") fallbackName else quality },
                    subtitleList = subtitleTracks,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract from HLS $sourceUrl", e)
                emptyList()
            }

            val expanded = extracted.ifEmpty {
                listOf(
                    Video(
                        videoUrl = sourceUrl,
                        videoTitle = fallbackName,
                        headers = videoHeadersGen(headers, mobileHomeUrl, sourceUrl),
                        resolution = qualityParam?.let { RESOLUTION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull() },
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }

            for (video in expanded) {
                if (!seen.add(IN_PARAM_REGEX.replace(video.videoUrl, ""))) continue
                videos.add(
                    Video(
                        videoUrl = video.videoUrl,
                        videoTitle = video.videoTitle,
                        headers = video.headers,
                        resolution = RESOLUTION_REGEX.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull(),
                        subtitleTracks = video.subtitleTracks,
                        audioTracks = video.audioTracks,
                    ),
                )
            }
        }

        val result = videos.sortVideos()
        Log.i(TAG, "buildVideos: returning ${result.size} videos")
        return result
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
        private val ADDHASH_REGEX = Regex("""data-addhash="([^"]+)"""")
        private val QUALITY_PARAM_REGEX = Regex("""[?&]q=([^&]+)""")
        private val RESOLUTION_REGEX = Regex("""(\d{3,4})p""")
        private val IN_PARAM_REGEX = Regex("""[?&]in=[^&]*""")

        private val sharedPreferences: SharedPreferences by lazy {
            Injekt.get<Application>().getSharedPreferences("cncverse_shared_prefs", 0)
        }

        private var cookieValue = ""
        private var cookieTimestamp = 0L

        @Synchronized
        private fun getBypassCookie(force: Boolean = false): String {
            val now = System.currentTimeMillis()
            val savedCookie = if (force) null else sharedPreferences.getString("nf_cookie", null)
            val savedTimestamp = if (force) 0L else sharedPreferences.getLong("nf_cookie_timestamp", 0L)

            if (!savedCookie.isNullOrEmpty() && (savedCookie.contains("::") || savedCookie.contains("%3A%3A")) && now - savedTimestamp < 43_200_000L) {
                cookieValue = savedCookie
                cookieTimestamp = savedTimestamp
                return savedCookie
            }

            try {
                Log.d(TAG, "Starting NetMirror bypass flow...")

                val network = Injekt.get<NetworkHelper>()
                val bypassClient = network.client.newBuilder()
                    .addInterceptor(CloudflareInterceptor(network.client, DEFAULT_USER_AGENT))
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                // Step 1: Scrape data-addhash from mobile home
                val homeRequest = Request.Builder()
                    .url("https://net52.cc/mobile/home?app=1")
                    .header("User-Agent", DEFAULT_USER_AGENT)
                    .header("X-Requested-With", APP_REQUESTED_WITH)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val homeHtml = bypassClient.newCall(homeRequest).execute().use { it.body.string() }
                val addhash = ADDHASH_REGEX.find(homeHtml)?.groupValues?.get(1).orEmpty()
                if (addhash.isEmpty()) {
                    Log.w(TAG, "Failed to scrape data-addhash from mobile/home")
                    return cookieValue
                }
                Log.d(TAG, "Scraped addhash: $addhash")

                // Step 2: Handshake ping to userver (fire and ignore, quick timeout)
                try {
                    val pingClient = OkHttpClient.Builder()
                        .connectTimeout(3, TimeUnit.SECONDS)
                        .readTimeout(3, TimeUnit.SECONDS)
                        .build()
                    val pingRequest = Request.Builder()
                        .url("https://userver.net52.cc/?hee5=$addhash&a=y&t=${System.currentTimeMillis()}")
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("X-Requested-With", APP_REQUESTED_WITH)
                        .build()
                    pingClient.newCall(pingRequest).execute().close()
                } catch (e: Exception) {
                    Log.d(TAG, "userver ping note: ${e.message}")
                }

                // Step 3: Poll verify2.php until "All Done"
                val formBody = FormBody.Builder()
                    .add("verify", addhash)
                    .build()

                val pollClient = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                for (attempt in 1..10) {
                    SystemClock.sleep(if (attempt == 1) 4000L else 5000L)

                    val verifyRequest = Request.Builder()
                        .url("https://net52.cc/mobile/verify2.php")
                        .post(formBody)
                        .header("User-Agent", DEFAULT_USER_AGENT)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .header("Referer", "https://net52.cc/mobile/home?app=1")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .build()

                    val (bodyStr, setCookieHeaders) = try {
                        pollClient.newCall(verifyRequest).execute().use { resp ->
                            resp.body.string() to resp.headers.values("Set-Cookie")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "verify2 poll error (attempt $attempt): ${e.message}")
                        "" to emptyList<String>()
                    }

                    Log.d(TAG, "verify2 attempt $attempt: $bodyStr")

                    if (bodyStr.contains("\"statusup\":\"All Done\"") || bodyStr.contains("All Done")) {
                        for (header in setCookieHeaders) {
                            if (header.contains("t_hash_t=")) {
                                val cookie = header.substringAfter("t_hash_t=").substringBefore(";")
                                if (cookie.isNotEmpty()) {
                                    cookieValue = cookie
                                    cookieTimestamp = System.currentTimeMillis()
                                    sharedPreferences.edit()
                                        .putString("nf_cookie", cookie)
                                        .putLong("nf_cookie_timestamp", cookieTimestamp)
                                        .apply()
                                    Log.i(TAG, "Successfully acquired bypass cookie t_hash_t on attempt $attempt")
                                    return cookie
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring bypass cookie", e)
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
    }
}

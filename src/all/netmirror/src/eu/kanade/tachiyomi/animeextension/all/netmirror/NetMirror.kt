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
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.network.GET
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import java.util.concurrent.TimeUnit

class NetMirror : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        CNCVerseSource("CNCVerse (Netflix)", "nf", "", 5181466391484419888L),
        CNCVerseSource("CNCVerse (Prime Video)", "pv", "", 5181466391484419889L),
        CNCVerseSource("CNCVerse (Hotstar)", "hs", "", 5181466391484419890L),
        CNCVerseSource("CNCVerse (Disney)", "dp", "disney", 5181466391484419891L),
        CNCVerseSource("CNCVerse (Marvel)", "dp", "marvel", 5181466391484419892L),
        CNCVerseSource("CNCVerse (Star Wars)", "dp", "starwars", 5181466391484419893L),
        CNCVerseSource("CNCVerse (Pixar)", "dp", "pixar", 5181466391484419894L)
    )
}

class CNCVerseSource(
    override val name: String,
    private val ott: String,
    private val studio: String,
    override val id: Long
) : AnimeHttpSource(), ConfigurableAnimeSource {

    override val baseUrl = "https://net52.cc"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val ottPath: String
        get() = when (ott) {
            "nf" -> ""
            "pv" -> "pv"
            else -> "hs"
        }

    private fun getPosterUrl(id: String): String {
        return when (ott) {
            "nf" -> "https://imgcdn.kim/poster/v/$id.jpg"
            "pv" -> "https://imgcdn.kim/pv/v/$id.jpg"
            else -> "https://imgcdn.kim/hs/v/$id.jpg"
        }
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            if (url.contains("net52.cc") || url.contains("net11.cc")) {
                val cookieVal = getBypassCookie()
                if (cookieVal.isNotEmpty()) {
                    val cookieHeader = buildString {
                        append("t_hash_t=$cookieVal")
                        append("; ott=$ott")
                        append("; hd=on")
                        if (studio.isNotEmpty()) {
                            append("; studio=$studio")
                        }
                    }
                    val newRequest = request.newBuilder()
                        .header("Cookie", cookieHeader)
                        .build()
                    return@addInterceptor chain.proceed(newRequest)
                }
            }
            chain.proceed(request)
        }
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.toString().contains(".m3u8")) {
                val newRequest = request.newBuilder()
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
        return GET("$baseUrl/mobile/home?app=1", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = org.jsoup.Jsoup.parse(response.body.string())
        val animeList = mutableListOf<SAnime>()
        val articles = document.select(".tray-container article, #top10 .top10-post")
        for (element in articles) {
            val id = element.selectFirst("a")?.attr("data-post") ?: element.attr("data-post") ?: continue
            val title = element.selectFirst("img")?.attr("alt")
                ?: element.selectFirst(".card-title")?.text()
                ?: element.selectFirst("h3")?.text()
                ?: ""
            if (id.isNotEmpty()) {
                val anime = SAnime.create()
                anime.title = title
                anime.url = id
                anime.thumbnail_url = getPosterUrl(id)
                animeList.add(anime)
            }
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
                for (i in 0 until seasonsArray.length()) {
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

    override fun videoListRequest(episode: SEpisode): Request {
        val apiBase = getApiUrl()
        val url = "$apiBase/newtv/player.php?id=${episode.url}"
        
        val ottValue = if (ott == "dp") "hs" else ott
        val headers = Headers.Builder()
            .add("Ott", ottValue)
            .add("Usertoken", "")
            .add("Cache-Control", "no-cache, no-store, must-revalidate")
            .add("Pragma", "no-cache")
            .add("Expires", "0")
            .add("X-Requested-With", "NetmirrorNewTV v1.0")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0")
            .add("Accept", "application/json, text/plain, */*")
            .build()
            
        return GET(url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val json = response.body.string()
        val jsonObj = JSONObject(json)
        val status = jsonObj.optString("status")
        val videoLink = jsonObj.optString("video_link")
        val referer = jsonObj.optString("referer")
        
        if (status != "ok" || videoLink.isEmpty()) {
            return emptyList()
        }
        
        val videoHeaders = Headers.Builder()
            .set("Referer", referer.ifEmpty { getApiUrl() })
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0")
            .build()
            
        return listOf(
            Video(videoLink, "CNCVerse", videoLink, headers = videoHeaders)
        ).sortVideos()
    }

    override fun videoUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ============================== Preferences ===========================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        androidx.preference.ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue("720p")
            summary = "%s"
        }.also(screen::addPreference)
    }

    private fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p") ?: "720p"
        return sortedWith(
            compareBy { video ->
                val videoQuality = video.quality
                if (videoQuality.contains(quality)) {
                    0
                } else {
                    1
                }
            }
        )
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    companion object {
        private val sharedPreferences: SharedPreferences by lazy {
            Injekt.get<Application>().getSharedPreferences("cncverse_shared_prefs", 0)
        }

        private var resolvedApiUrl = ""

        @Synchronized
        private fun getApiUrl(): String {
            if (resolvedApiUrl.isNotEmpty()) return resolvedApiUrl
            
            val newTvBaseHeaders = mapOf(
                "Cache-Control" to "no-cache, no-store, must-revalidate",
                "Pragma" to "no-cache",
                "Expires" to "0",
                "X-Requested-With" to "NetmirrorNewTV v1.0",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
                "Accept" to "application/json, text/plain, */*"
            )

            val newTvDomains = listOf(
                "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
                "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
                "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
            )

            val directClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            for (encoded in newTvDomains) {
                val base = decodeBase64(encoded).trimEnd('/')
                try {
                    val request = Request.Builder()
                        .url("$base/checknewtv.php")
                        .apply {
                            newTvBaseHeaders.forEach { (k, v) -> addHeader(k, v) }
                        }
                        .build()
                    
                    directClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = response.body.string()
                            val tokenHash = JSONObject(json).optString("token_hash")
                            if (tokenHash.isNotEmpty()) {
                                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                                return resolvedApiUrl
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Try next domain
                }
            }
            throw Exception("Failed to resolve NewTV API base URL")
        }

        private var cookieValue = ""
        private var cookieTimestamp = 0L

        @Synchronized
        private fun getBypassCookie(): String {
            val now = System.currentTimeMillis()
            val savedCookie = sharedPreferences.getString("nf_cookie", null)
            val savedTimestamp = sharedPreferences.getLong("nf_cookie_timestamp", 0L)
            
            if (!savedCookie.isNullOrEmpty() && now - savedTimestamp < 54_000_000) {
                cookieValue = savedCookie
                cookieTimestamp = savedTimestamp
                return savedCookie
            }

            try {
                val formBody = FormBody.Builder()
                    .add("g-recaptcha-response", UUID.randomUUID().toString())
                    .build()

                val directClient = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://net52.cc/verify.php")
                    .post(formBody)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", "https://net22.cc")
                    .header("Referer", "https://net22.cc/verify2")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                    .build()

                directClient.newCall(request).execute().use { response ->
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
                e.printStackTrace()
            }
            return cookieValue
        }

        private fun decodeBase64(value: String): String {
            return String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
        }
    }
}

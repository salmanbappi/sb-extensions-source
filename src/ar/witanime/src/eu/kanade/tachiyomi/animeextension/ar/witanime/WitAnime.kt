package eu.kanade.tachiyomi.animeextension.ar.witanime

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.witanime.extractors.SharedExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import extensions.utils.parseAs
import extensions.utils.toJsonString
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Duration.Companion.seconds

class WitAnime : Source() {

    override val name = "WIT ANIME"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(network.client))
            .rateLimit(permits = 2, period = 2.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", CloudflareInterceptor.DEFAULT_USER_AGENT)
        .add("sec-ch-ua", "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not_A Brand\";v=\"24\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Windows\"")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    // Extractors
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val sharedExtractor by lazy { SharedExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/browse/page/$page"
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()

        val animeList = doc.select("a[href*='/anime/'], a[href*='/movie/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href = el.attr("href")
                val title = el.selectFirst("h3")?.text()?.ifBlank { null }
                    ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: return@mapNotNull null
                val thumb = el.selectFirst("img")?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("src") }
                }
                SAnime.create().apply {
                    this.title = title
                    thumbnail_url = thumb
                    this.url = href.removePrefix(baseUrl)
                    fetch_type = FetchType.Episodes
                }
            }

        val hasNext = doc.selectFirst("a[href*='page/${page + 1}'], a[href*='page=${page + 1}']") != null
        return AnimesPage(animeList, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val req = Request.Builder()
            .url("$baseUrl/new-episodes?page=$page")
            .headers(headers)
            .addHeader("Accept", "application/json, text/plain, */*")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Referer", "$baseUrl/")
            .build()

        val response = runCatching { client.newCall(req).execute() }.getOrNull() ?: return AnimesPage(emptyList(), false)
        val jsonObject = runCatching { response.parseAs<JsonObject>() }.getOrNull()

        if (jsonObject != null) {
            val html = jsonObject["html"]?.jsonPrimitive?.contentOrNull ?: ""
            val hasOlder = jsonObject["hasOlder"]?.jsonPrimitive?.booleanOrNull ?: false
            val doc = org.jsoup.Jsoup.parseBodyFragment(html, baseUrl)

            val animeList = doc.select("a[href*='/watch/']")
                .distinctBy { extractSlug(it.attr("href")) }
                .mapNotNull { el ->
                    val href = el.attr("href")
                    val slug = extractSlug(href)
                    val isMovie = href.contains("/watch/movie/")
                    val animePath = if (isMovie) "/movie/$slug" else "/anime/$slug"
                    val title = el.selectFirst("h3")?.text()?.ifBlank { null }
                        ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                        ?: return@mapNotNull null
                    val thumb = el.selectFirst("img")?.let { img ->
                        img.attr("abs:src").ifBlank { img.attr("src") }
                    }
                    SAnime.create().apply {
                        this.title = title
                        thumbnail_url = thumb
                        this.url = animePath
                        fetch_type = FetchType.Episodes
                    }
                }
            return AnimesPage(animeList, hasOlder)
        }

        return AnimesPage(emptyList(), false)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("page", page.toString())
            }.build().toString()
        } else {
            "$baseUrl/browse".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                for (filter in filters) {
                    when (filter) {
                        is StatusFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("status", filter.toUriPart())
                        }

                        is TypeFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("type", filter.toUriPart())
                        }

                        is SortFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("sort", filter.toUriPart())
                        }

                        is SeasonFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("season", filter.toUriPart())
                        }

                        is YearFilter -> if (filter.toUriPart().isNotBlank()) {
                            addQueryParameter("year", filter.toUriPart())
                        }

                        is GenreFilter -> {
                            filter.getSelectedValues().forEach { genre ->
                                addQueryParameter("genres[]", genre)
                            }
                        }

                        else -> {}
                    }
                }
            }.build().toString()
        }

        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val animeList = doc.select("a[href*='/anime/'], a[href*='/movie/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { el ->
                val href = el.attr("href")
                val title = el.selectFirst("h3")?.text()?.ifBlank { null }
                    ?: el.selectFirst("img")?.attr("alt")?.ifBlank { null }
                    ?: return@mapNotNull null
                val thumb = el.selectFirst("img")?.let { img ->
                    img.attr("abs:src").ifBlank { img.attr("src") }
                }
                SAnime.create().apply {
                    this.title = title
                    thumbnail_url = thumb
                    this.url = href.removePrefix(baseUrl)
                    fetch_type = FetchType.Episodes
                }
            }

        val hasNext = doc.selectFirst("a[href*='page=${page + 1}'], a[href*='page/${page + 1}']") != null
        return AnimesPage(animeList, hasNext)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("استخدم التصفية عند ترك حقل البحث فارغاً"),
        StatusFilter(),
        TypeFilter(),
        SortFilter(),
        SeasonFilter(),
        YearFilter(),
        GenreFilter(),
    )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val title = doc.selectFirst("h1")?.text()?.ifBlank { null } ?: anime.title
        val thumb = doc.selectFirst("img[src*='posters']")?.let { img ->
            img.attr("abs:src").ifBlank { img.attr("src") }
        } ?: anime.thumbnail_url

        val synopsis = doc.selectFirst("p.leading-relaxed")?.text()?.trim() ?: ""
        val statusText = doc.selectFirst("span.bg-neutral-800")?.text() ?: ""
        val score = doc.selectFirst("span.backdrop-blur-sm, span[dir='ltr']:contains(.)")?.text()?.trim()?.toDoubleOrNull()
        val genres = doc.select("div.flex.flex-wrap.gap-2 span.rounded-full")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return SAnime.create().apply {
            this.title = title
            thumbnail_url = thumb
            genre = genres.joinToString(", ")
            status = when {
                statusText.contains("مستمر") || statusText.contains("يعرض") -> SAnime.ONGOING
                statusText.contains("مكتمل") || statusText.contains("منتهي") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                if (score != null && score > 0.0) {
                    append("⭐ $score\n\n")
                }
                if (synopsis.isNotBlank()) {
                    append(synopsis)
                }
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val episodes = mutableListOf<SEpisode>()

        val epElements = doc.select("div[data-episode-list] a.group.rounded-xl[href*='/watch/']").distinctBy { it.attr("href") }
        if (epElements.isNotEmpty()) {
            epElements.forEach { el ->
                val href = el.attr("href")
                val epNum = href.substringAfterLast("/").toFloatOrNull() ?: 0f
                val epName = el.selectFirst("span, div:contains(الحلقة)")?.text()?.trim()
                    ?: el.text().trim().ifBlank { "الحلقة ${epNum.toInt()}" }
                episodes.add(
                    SEpisode.create().apply {
                        this.url = href.removePrefix(baseUrl)
                        name = epName
                        episode_number = epNum
                    },
                )
            }
        } else {
            val genericEps = doc.select("div[data-episode-list] a[href*='/watch/']").distinctBy { it.attr("href") }
            if (genericEps.isNotEmpty()) {
                genericEps.forEach { el ->
                    val href = el.attr("href")
                    val epNum = href.substringAfterLast("/").toFloatOrNull() ?: 0f
                    episodes.add(
                        SEpisode.create().apply {
                            this.url = href.removePrefix(baseUrl)
                            name = "الحلقة ${epNum.toInt()}"
                            episode_number = epNum
                        },
                    )
                }
            } else {
                val movieBtn = doc.selectFirst("a[href*='/watch/movie/'], a.group\\/cta[href*='/watch/'], a[href*='/watch/']")
                if (movieBtn != null) {
                    val href = movieBtn.attr("href")
                    episodes.add(
                        SEpisode.create().apply {
                            this.url = href.removePrefix(baseUrl)
                            name = "الحلقة 1"
                            episode_number = 1f
                        },
                    )
                }
            }
        }

        return if (episodes.size > 1 && episodes.first().episode_number < episodes.last().episode_number) {
            episodes.reversed()
        } else {
            episodes
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val cleanEpUrl = "/" + episode.url.removePrefix(cleanBaseUrl).trimStart('/')
        val watchUrl = "$cleanBaseUrl$cleanEpUrl"
        val httpUrl = watchUrl.toHttpUrl()

        val watchResp = client.newCall(GET(watchUrl, headers)).execute()
        if (!watchResp.isSuccessful) {
            val code = watchResp.code
            watchResp.close()
            throw Exception("فشل تحميل صفحة الحلقة (HTTP $code)")
        }

        val respCookieHeaders = watchResp.headers("Set-Cookie")
        val parsedCookies = respCookieHeaders.mapNotNull { Cookie.parse(httpUrl, it) }
        if (parsedCookies.isNotEmpty()) {
            runCatching { client.cookieJar.saveFromResponse(httpUrl, parsedCookies) }
        }

        val jarCookies = runCatching {
            client.cookieJar.loadForRequest(httpUrl).joinToString("; ") { "${it.name}=${it.value}" }
        }.getOrNull().orEmpty()
        val rawRespCookies = respCookieHeaders.joinToString("; ") { it.substringBefore(";") }
        val combinedCookies = buildString {
            if (jarCookies.isNotBlank()) append(jarCookies)
            if (rawRespCookies.isNotBlank()) {
                if (isNotEmpty()) append("; ")
                append(rawRespCookies)
            }
        }

        val doc = watchResp.asJsoup()

        // 1. Check for legacy WordPress servers list if present
        val legacyServers = doc.select("ul#episode-servers li a")
        if (legacyServers.isNotEmpty()) {
            return legacyServers.distinctBy { it.text().substringBefore(" -") }.map { el ->
                val encoded = el.attr("data-url").takeUnless { it.isBlank() }
                    ?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() }
                    ?: el.attr("onclick").substringAfter("'").substringBefore("'")
                        .let { runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrNull() ?: it }
                Hoster(
                    hosterName = el.text().trim(),
                    hosterUrl = encoded,
                )
            }
        }

        // 2. Modern watchPlayer JSON manifest
        val csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: ""
        val htmlContent = doc.html()
        val rawSourcesUrl = SOURCES_REGEX.find(htmlContent)?.let { match ->
            (1..4).firstNotNullOfOrNull { idx -> match.groups[idx]?.value }
        }?.replace("\\/", "/")?.trim()

        val sourcesPath = when {
            !rawSourcesUrl.isNullOrBlank() -> {
                if (rawSourcesUrl.startsWith("http")) rawSourcesUrl else "$cleanBaseUrl/${rawSourcesUrl.trimStart('/')}"
            }
            else -> "$cleanBaseUrl/${cleanEpUrl.trim('/')}/sources"
        }

        val reqBuilder = Request.Builder()
            .url(sourcesPath)
            .post("".toRequestBody(null))
            .headers(headers)
            .header("Referer", watchUrl)
            .header("Origin", cleanBaseUrl)
            .header("X-CSRF-TOKEN", csrfToken)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")

        if (combinedCookies.isNotBlank()) {
            reqBuilder.header("Cookie", combinedCookies)
        }

        val sourcesResp = client.newCall(reqBuilder.build()).execute()
        if (!sourcesResp.isSuccessful) {
            val code = sourcesResp.code
            sourcesResp.close()
            throw Exception("فشل جلب خوادم الحلقة (HTTP $code)")
        }

        val postCookieHeaders = sourcesResp.headers("Set-Cookie")
        val parsedPostCookies = postCookieHeaders.mapNotNull { Cookie.parse(sourcesPath.toHttpUrl(), it) }
        if (parsedPostCookies.isNotEmpty()) {
            runCatching { client.cookieJar.saveFromResponse(sourcesPath.toHttpUrl(), parsedPostCookies) }
        }
        val rawPostCookies = postCookieHeaders.joinToString("; ") { it.substringBefore(";") }
        val sessionCookies = buildString {
            if (combinedCookies.isNotBlank()) append(combinedCookies)
            if (rawPostCookies.isNotBlank()) {
                if (isNotEmpty()) append("; ")
                append(rawPostCookies)
            }
        }

        val sourcesJson = sourcesResp.parseAs<JsonObject>()
        val hosterMap = mutableMapOf<String, MutableList<ServerItem>>()

        // Streaming players
        val playersObj = sourcesJson["players"]?.jsonObject
        playersObj?.forEach { (quality, playerArray) ->
            playerArray.jsonArray.forEach { p ->
                val pObj = p.jsonObject
                val token = pObj["token"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val rawLabel = pObj["label"]?.jsonPrimitive?.contentOrNull ?: "Stream"
                val label = rawLabel.replaceFirstChar { it.uppercase() }
                hosterMap.getOrPut(label) { mutableListOf() }.add(
                    ServerItem(
                        quality = quality,
                        token = token,
                        isDownload = false,
                        watchUrl = watchUrl,
                        csrfToken = csrfToken,
                        cookies = sessionCookies,
                    ),
                )
            }
        }

        // Downloads
        val downloadsObj = sourcesJson["downloads"]?.jsonObject
        downloadsObj?.forEach { (quality, dlArray) ->
            dlArray.jsonArray.forEach { d ->
                val dObj = d.jsonObject
                val token = dObj["token"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val rawLabel = dObj["label"]?.jsonPrimitive?.contentOrNull ?: "Download"
                val label = "[DL] " + rawLabel.replaceFirstChar { it.uppercase() }
                hosterMap.getOrPut(label) { mutableListOf() }.add(
                    ServerItem(
                        quality = quality,
                        token = token,
                        isDownload = true,
                        watchUrl = watchUrl,
                        csrfToken = csrfToken,
                        cookies = sessionCookies,
                    ),
                )
            }
        }

        if (hosterMap.isEmpty()) {
            throw Exception("لم يتم العثور على خوادم تشغيل لهذه الحلقة")
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosterMap.map { (serverName, items) ->
            Hoster(
                hosterName = serverName,
                hosterUrl = items.toJsonString(),
            )
        }.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        if (!hoster.hosterUrl.startsWith("[")) {
            return extractVideosFromUrl(hoster.hosterUrl, hoster.hosterName)
        }

        val items = runCatching { hoster.hosterUrl.parseAs<List<ServerItem>>() }.getOrNull()
            ?: return emptyList()

        val cleanBaseUrl = baseUrl.trimEnd('/')

        return items.parallelCatchingFlatMap { item ->
            val endpoint = if (item.isDownload) "/watch/download-source/" else "/watch/stream-source/"
            val gateEndpoint = if (item.isDownload) "/watch/download-gate/" else "/watch/stream-gate/"

            // 1. Authorize token in session
            val authBuilder = Request.Builder()
                .url("$cleanBaseUrl$endpoint${item.token}")
                .post("".toRequestBody(null))
                .headers(headers)
                .header("Referer", item.watchUrl)
                .header("Origin", cleanBaseUrl)
                .header("X-CSRF-TOKEN", item.csrfToken)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/plain, */*")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")

            if (item.cookies.isNotBlank()) {
                authBuilder.header("Cookie", item.cookies)
            }

            val authResp = runCatching { client.newCall(authBuilder.build()).execute() }.getOrNull()
            val authCookies = authResp?.headers("Set-Cookie")?.joinToString("; ") { it.substringBefore(";") } ?: ""
            val gateCookies = buildString {
                if (item.cookies.isNotBlank()) append(item.cookies)
                if (authCookies.isNotBlank()) {
                    if (isNotEmpty()) append("; ")
                    append(authCookies)
                }
            }
            authResp?.close()

            // 2. Gate endpoint to follow redirect.
            // DO NOT pass the watch page as Referer: WitAnime gate returns 404 when Referer is sent!
            val gateBuilder = Request.Builder()
                .url("$cleanBaseUrl$gateEndpoint${item.token}")
                .headers(headers)
                .removeHeader("Referer")
                .header("Sec-Fetch-Dest", if (item.isDownload) "document" else "iframe")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")

            if (gateCookies.isNotBlank()) {
                gateBuilder.header("Cookie", gateCookies)
            }

            val gateClient = client.newBuilder().followRedirects(true).build()
            val gateResp = runCatching { gateClient.newCall(gateBuilder.build()).execute() }.getOrNull()
                ?: return@parallelCatchingFlatMap emptyList()

            val finalUrl = gateResp.request.url.toString()
            val gateHtml = if (finalUrl.contains("/watch/")) gateResp.body.string() else ""

            val resolvedUrl = if (!finalUrl.contains("/watch/")) {
                finalUrl
            } else {
                val gateDoc = org.jsoup.Jsoup.parse(gateHtml, cleanBaseUrl)
                gateDoc.selectFirst("iframe")?.let { it.attr("abs:src").ifBlank { it.attr("src") }.ifBlank { it.attr("data-src") } }
                    ?: gateDoc.selectFirst("source, video")?.let { it.attr("abs:src").ifBlank { it.attr("src") } }
                    ?: finalUrl
            }

            extractVideosFromUrl(resolvedUrl, "${hoster.hosterName} - ${item.quality}")
        }.sortVideos()
    }

    private suspend fun extractVideosFromUrl(url: String, qualityLabel: String): List<Video> = runCatching {
        when {
            url.contains("mp4upload.com") -> {
                mp4uploadExtractor.videosFromUrl(url, headers)
            }

            url.contains("4shared.com") -> {
                sharedExtractor.videosFromUrl(url, qualityLabel)?.let { listOf(it) } ?: emptyList()
            }

            url.contains("mediafire.com") -> {
                val doc = client.newCall(GET(url, headers)).execute().asJsoup()
                val directUrl = doc.selectFirst("a#downloadButton")?.attr("abs:href")
                    ?: doc.selectFirst("a[href*='download']")?.attr("abs:href")
                if (!directUrl.isNullOrBlank()) {
                    listOf(
                        Video(
                            videoUrl = directUrl,
                            videoTitle = qualityLabel,
                            headers = headers,
                        ),
                    )
                } else {
                    emptyList()
                }
            }

            url.contains("dood") || url.contains("ds2play") || url.contains("d000d") -> {
                doodExtractor.videosFromUrl(url)
            }

            url.contains("ok.ru") || url.contains("odnoklassniki") -> {
                okruExtractor.videosFromUrl(url)
            }

            url.contains("dailymotion.com") || url.contains("dai.ly") -> {
                dailymotionExtractor.videosFromUrl(url)
            }

            url.contains("streamtape.com") || url.contains("streamtape.net") -> {
                streamtapeExtractor.videoFromUrl(url)?.let { listOf(it) } ?: emptyList()
            }

            url.endsWith(".m3u8") || url.contains(".m3u8?") -> {
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    videoNameGen = { q -> "$qualityLabel - $q" },
                )
            }

            url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains(".mp4?") -> {
                listOf(
                    Video(
                        videoUrl = url,
                        videoTitle = qualityLabel,
                        headers = headers,
                    ),
                )
            }

            else -> {
                universalExtractor.videosFromUrl(url, headers, prefix = "$qualityLabel - ")
            }
        }
    }.getOrElse { emptyList() }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareBy<Video> { it.videoTitle.contains(quality) }.thenBy { it.videoTitle },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p (FHD)", "720p (HD)", "480p (SD)"),
            entryValues = listOf("FHD", "HD", "SD"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Auto", "Mp4upload", "4Shared", "Mega", "Videa"),
            entryValues = listOf("auto", "Mp4upload", "4Shared", "Mega", "Videa"),
        )
    }

    private fun extractSlug(url: String): String {
        val clean = url.substringBefore("#").substringBefore("?").trimEnd('/')
        return when {
            clean.contains("/watch/movie/") -> clean.substringAfter("/watch/movie/")
            clean.contains("/watch/") -> clean.substringAfter("/watch/").substringBefore("/")
            clean.contains("/anime/") -> clean.substringAfter("/anime/")
            clean.contains("/movie/") -> clean.substringAfter("/movie/")
            else -> clean.substringAfterLast("/")
        }
    }

    @Serializable
    private data class ServerItem(
        val quality: String = "",
        val token: String = "",
        val isDownload: Boolean = false,
        val watchUrl: String = "",
        val csrfToken: String = "",
        val cookies: String = "",
    )

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://witanime.site"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "FHD"

        private val SOURCES_REGEX by lazy {
            Regex("""sourcesUrl\s*:\s*(?:'([^']+)'|"([^"]+)"|&quot;([^&]+)&quot;|&#0*39;([^&]+)&#0*39;)""")
        }
        private val JSON_MEDIA_TYPE by lazy { "application/json".toMediaType() }
    }
}

package eu.kanade.tachiyomi.animeextension.all.nepu

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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

class Nepu :
    ParsedAnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Nepu"

    override val baseUrl = "https://nepu.to"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 5181466391484419855L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val isNepu = request.url.host.contains("nepu.to")

            var requestBuilder = request.newBuilder()
            var injectedCustomCookies = false

            if (isNepu) {
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

            val newRequest = requestBuilder.build()

            val response = if (newRequest.url.host.contains("tmdb.org")) {
                val newHeaders = newRequest.headers.newBuilder().removeAll("Referer").build()
                chain.proceed(newRequest.newBuilder().headers(newHeaders).build())
            } else {
                chain.proceed(newRequest)
            }

            if (isNepu && response.code == 403 && injectedCustomCookies) {
                response.close()
                chain.proceed(request)
            } else {
                response
            }
        }
        .build()

    override fun headersBuilder(): okhttp3.Headers.Builder {
        val builder = super.headersBuilder().set("Referer", "$baseUrl/")
        val savedUserAgent = getSavedUserAgent()
        if (!savedUserAgent.isNullOrBlank()) {
            builder.set("User-Agent", savedUserAgent)
        }
        return builder
    }

    private fun getSavedCookieData(): JSONObject? {
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
            JSONObject(file.readText())
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

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val path = if (page == 1) "discovery" else "discovery/page/$page"
        return GET("$baseUrl/$path/", headers)
    }

    override fun popularAnimeSelector(): String = ".list-movie, .list-episode"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a") ?: element
        setUrlWithoutDomain(link.attr("href"))
        title = element.selectFirst(".list-title, .jws-post-title, h2, h3, .title, .name")?.text()
            ?: element.selectFirst("img")?.attr("alt")
            ?: link.attr("title")
            ?: ""
        thumbnail_url = element.extractImageUrl()
    }

    override fun popularAnimeNextPageSelector(): String? = "ul.pagination li:not(.disabled) a, .pagination a[title*=ext], a[title*=ext], a[title*=EXT], .pagination a.next, a.next, .next.page-numbers, a.page-link:contains(Next)"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
        val hasNextPage = (popularAnimeNextPageSelector()?.let { document.selectFirst(it) } != null) || animes.size >= 20
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

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

        val url = if (hasFilter) {
            "$baseUrl/discovery".toHttpUrl().newBuilder().apply {
                addQueryParameter("filter", filterJson.toString())
                addQueryParameter("page", page.toString())
            }.build()
        } else {
            val path = if (page == 1) "discovery" else "discovery/page/$page"
            "$baseUrl/$path/".toHttpUrl()
        }

        val response = client.newCall(GET(url, headers)).execute()
        return searchAnimeParse(response)
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
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = ".episodes.tab-content a, .tab-pane a, ul.episodios li, .list-episodes a, .ep-item, .episode-item, a[href*='/episode/'], a[href*='/movie/'], a[href*='/show/'], a[href*='/serie/']"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        val link = if (element.tagName() == "a") element else element.selectFirst("a")!!
        setUrlWithoutDomain(link.attr("abs:href"))
        val epTitle = element.selectFirst("span, .name, .ep-title, .episode")?.text() ?: element.text()
        name = epTitle.trim().ifEmpty { "Episode 1" }
        episode_number = parseEpisodeNumber(name)
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

        val seasons = doc.select("div.season-list div.tab-pane, div#seasons > div, div.tab-pane, div.episodes")

        val episodeList = mutableListOf<SEpisode>()

        if (seasons.isNotEmpty()) {
            seasons.forEach { season ->
                val seasonId = season.attr("id")
                val seasonName = (if (seasonId.isNotEmpty()) doc.selectFirst("a[href='#$seasonId']")?.text() else null)
                    ?: season.selectFirst("span.se-t")?.text()
                    ?: ""
                val episodes = season.select("a").filter { it.attr("href").contains("/episode/") || it.attr("href").contains("/serie/") || it.attr("href").contains("/show/") || it.attr("href").contains("/movie/") }
                episodes.forEach { element ->
                    episodeList.add(
                        episodeFromElement(element).apply {
                            name = if (seasonName.isNotBlank()) "$seasonName - $name" else name
                        },
                    )
                }
            }
        }

        if (episodeList.isEmpty()) {
            val episodes = doc.select(episodeListSelector()).filter { it.attr("href").contains("/episode/") || it.attr("href").contains("/serie/") || it.attr("href").contains("/show/") || it.attr("href").contains("/movie/") }
            if (episodes.isNotEmpty()) {
                episodeList.addAll(episodes.map { episodeFromElement(it) })
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
            } else {
                val savedCookies = getSavedCookiesHeader()
                if (savedCookies.isNotEmpty()) {
                    builder.set("Cookie", savedCookies)
                }
            }
        }

        return builder.build()
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val pageUrl = response.request.url.toString()
        val token = document.selectFirst("input[name=_TOKEN]")?.attr("value")

        document.select(".btn-service").forEach { btn ->
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
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()

                try {
                    client.newCall(request).execute().use { embedResponse ->
                        if (!embedResponse.isSuccessful) return@forEach
                        val embedHtml = embedResponse.body.string()

                        var extractedUrl: String? = null

                        // Try to parse JSON first (Standard Dooplay)
                        try {
                            val jsonMatch = Regex("""["']?embed_url["']?\s*:\s*["']([^"']+)["']""").find(embedHtml)
                                ?: Regex("""["']?link["']?\s*:\s*["']([^"']+)["']""").find(embedHtml)
                                ?: Regex("""["']?url["']?\s*:\s*["']([^"']+)["']""").find(embedHtml)

                            if (jsonMatch != null) {
                                val url = jsonMatch.groupValues[1].replace("\\/", "/")
                                // Sometimes the JSON embed_url contains an iframe tag instead of a raw URL
                                if (url.contains("<iframe")) {
                                    extractedUrl = Jsoup.parse(url).selectFirst("iframe")?.attr("abs:src")
                                } else {
                                    extractedUrl = url
                                }
                            }
                        } catch (e: Exception) {}

                        // Fallback to HTML parsing if JSON extraction failed
                        if (extractedUrl.isNullOrBlank()) {
                            val embedDoc = Jsoup.parse(embedHtml, pageUrl)
                            extractedUrl = embedDoc.selectFirst("iframe")?.attr("abs:src")
                                ?: embedDoc.selectFirst("video source")?.attr("abs:src")
                                ?: Regex("""file"?\s*:\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)
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
                                videoList.add(Video(finalUrl, name, finalUrl, headers = mediaHeaders))
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
                                            if (extracted.isNotEmpty()) {
                                                videoList.addAll(extracted)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // ignore extraction errors
                                }
                            }
                        }

                        if (!extractedUrl.isNullOrBlank()) {
                            val sanitized = sanitize(extractedUrl)
                            val refererContext = if (sanitized.contains(".mp4") || sanitized.contains(".m3u8")) pageUrl else sanitized
                            extractVideos(sanitized, name, refererContext)
                        }
                    }
                } catch (e: Exception) {
                    // skip
                }
            }
        }

        if (videoList.isEmpty()) {
            document.select("div#player iframe, .embed-code iframe, div.source-box iframe, .player-iframe, iframe[src*='embed']").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotBlank() && !src.contains("index.html")) {
                    val videoHeaders = buildVideoHeaders(src, src)

                    if (src.contains(".mp4") || src.contains(".m3u8")) {
                        videoList.add(Video(src, "Video", src, headers = videoHeaders))
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
                                    if (extracted.isNotEmpty()) {
                                        videoList.addAll(extracted)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // ignore extraction errors
                        }
                    }
                }
            }
        }

        // Final Fallback for internal player (e.g. Gravity Falls)
        // If the video is not in an iframe but loaded dynamically on the page itself
        if (videoList.isEmpty()) {
            val videoHeaders = buildVideoHeaders(pageUrl, pageUrl)

            try {
                val extracted = UniversalExtractor(client).videosFromUrl(pageUrl, videoHeaders, prefix = "Internal Player")
                if (extracted.isNotEmpty()) {
                    videoList.addAll(extracted)
                }
            } catch (e: Exception) {
                // ignore extraction errors
            }
        }

        log("videoListParse mapping started. Total videos to process: ${videoList.size}")

        return videoList.distinctBy { it.videoUrl }.map { video ->
            val videoUrl = video.videoUrl
            if (videoUrl.isNullOrBlank()) {
                log("Video URL is null or blank for video quality: ${video.quality}")
                return@map video
            }

            log("Processing video: quality=${video.quality}, url=$videoUrl")

            val proxiedUrl = getProxyUrl(videoUrl, video.headers)
            Video(
                url = proxiedUrl,
                quality = video.quality,
                videoUrl = proxiedUrl,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ==============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        AnimeFilter.Separator(),
        ImdbFilter(),
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
        // 1. Try to find any child element (or self) that has a data-src or src attribute (excluding sprite icons/scripts)
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

        // 2. Fallback to style attribute url() extraction
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

        // 3. Fallback to img child tags
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

    private fun log(msg: String) {
        try {
            val context = Injekt.get<Application>()
            val file = File(context.getExternalFilesDir(null), "nepu_log.txt")
            file.appendText("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}: $msg\n")
        } catch (_: Exception) {}
    }

    private fun getProxyUrl(targetUrl: String, headers: okhttp3.Headers?): String = Companion.getProxyUrl(this, targetUrl, headers)

    companion object {
        private var proxy: LocalProxy? = null

        @Synchronized
        fun getProxyUrl(source: Nepu, targetUrl: String, headers: okhttp3.Headers?): String {
            if (proxy == null) {
                proxy = LocalProxy(source.client) { source.getSavedUserAgent() }
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
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
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
        return "http://127.0.0.1:$port/proxy?url=$encodedUrl&headers=$encodedHeaders"
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
            val encodedHeaders = httpUrl.queryParameter("headers")

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

            val targetHeaders = okhttp3.Headers.Builder()
            if (!encodedHeaders.isNullOrEmpty()) {
                val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders.set(headerParts[0].trim(), headerParts[1].trim())
                    }
                }
            }

            if (targetHeaders.get("User-Agent").isNullOrEmpty()) {
                val savedUA = userAgentProvider()
                if (!savedUA.isNullOrBlank()) {
                    targetHeaders.set("User-Agent", savedUA)
                }
            }

            val reqBuilder = Request.Builder()
                .url(targetUrl)

            // Forward Range header from client
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true)) {
                        targetHeaders.set(name, value)
                    }
                }
            }

            reqBuilder.headers(targetHeaders.build())

            val request = reqBuilder.build()
            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders ?: "")
            }
        } catch (e: Exception) {
            try {
                sendError(socket, 500, e.message ?: "Internal Error")
            } catch (_: Exception) {}
        } finally {
            try {
                socket.shutdownOutput()
            } catch (_: Exception) {}
            try {
                Thread.sleep(50)
            } catch (_: Exception) {}
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String) {
        val out = socket.getOutputStream()
        val code = response.code
        val message = response.message

        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            try {
                val content = response.body.string()
                val modifiedContent = processM3u8(content, targetUrl, encodedHeaders)
                modifiedContentBytes = modifiedContent.toByteArray()
            } catch (e: Exception) {
                log("Error processing m3u8 in sendResponse: ${e.message}")
            }
        }

        out.write("HTTP/1.1 $code $message\r\n".toByteArray())

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Keep-Alive", ignoreCase = true)
            ) {
                continue
            }
            if (name.equals("Content-Length", ignoreCase = true) && isM3u8) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray())
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray())
        }
        out.write("Connection: close\r\n".toByteArray())
        out.write("\r\n".toByteArray())

        if (isM3u8 && modifiedContentBytes != null) {
            out.write(modifiedContentBytes)
        } else {
            response.body.byteStream().use { input ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
        }
        out.flush()
    }

    private val uriRegex = Regex("""URI=["']([^"']+)["']""")

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split("\n")
        val rewrittenLines = lines.map { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                trimmed
            } else if (trimmed.startsWith("#")) {
                uriRegex.find(trimmed)?.let { match ->
                    val uriValue = match.groupValues[1]
                    val absoluteUri = resolveUrl(playlistUrl, uriValue)
                    val proxiedUri = getProxyUrlWithEncodedHeaders(absoluteUri, encodedHeaders)
                    trimmed.replace(uriValue, proxiedUri)
                } ?: trimmed
            } else {
                val absoluteUrl = resolveUrl(playlistUrl, trimmed)
                getProxyUrlWithEncodedHeaders(absoluteUrl, encodedHeaders)
            }
        }.toMutableList()

        if (content.contains("#EXTINF")) {
            var playlistTypeIndex = -1
            var extm3uIndex = -1
            for (i in rewrittenLines.indices) {
                val trimmed = rewrittenLines[i].trim()
                if (trimmed.startsWith("#EXT-X-PLAYLIST-TYPE")) {
                    playlistTypeIndex = i
                } else if (trimmed.startsWith("#EXTM3U")) {
                    extm3uIndex = i
                }
            }

            if (playlistTypeIndex != -1) {
                rewrittenLines[playlistTypeIndex] = "#EXT-X-PLAYLIST-TYPE:VOD"
            } else {
                val insertIndex = if (extm3uIndex != -1) extm3uIndex + 1 else 0
                rewrittenLines.add(insertIndex, "#EXT-X-PLAYLIST-TYPE:VOD")
            }

            val hasEndList = rewrittenLines.any { it.trim() == "#EXT-X-ENDLIST" }
            if (!hasEndList) {
                rewrittenLines.add("#EXT-X-ENDLIST")
            }
        }

        return rewrittenLines.joinToString("\n")
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "http://127.0.0.1:$port/proxy?url=$encodedUrl&headers=$encodedHeaders"
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

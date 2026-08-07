package eu.kanade.tachiyomi.animeextension.all.toonworld4all

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.buzzheavierextractor.BuzzheavierExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class ToonWorld4All :
    Source(),
    ConfigurableAnimeSource {

    override val name = "ToonWorld4All"
    override val baseUrl = "https://toonworld4all.me"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 7291048561930492815L

    private val fastClient: OkHttpClient by lazy {
        val customDispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        client.newBuilder()
            .dispatcher(customDispatcher)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val universalExtractor by lazy { UniversalExtractor(fastClient) }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/", headers)
    } else {
        GET("$baseUrl/page/$page/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("article.herald-post, article.post").mapNotNull { element ->
            try {
                val titleEl = element.selectFirst("h2.entry-title a, h2.post-title a") ?: return@mapNotNull null
                val titleText = titleEl.text().trim()
                val cleanTitle = titleText.substringBefore("[").substringBefore("(").trim()

                SAnime.create().apply {
                    title = cleanTitle
                    setUrlWithoutDomain(titleEl.attr("href"))

                    val img = element.selectFirst("a.herald-post-thumbnail img, a.post-thumb img, img.wp-post-image")
                    thumbnail_url = img?.attr("data-src")?.takeIf { it.isNotBlank() }
                        ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }

        val hasNextPage = document.select("ul.pages-numbers li.the-next-page, a.next").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        if (page == 1) {
            GET("$baseUrl/?s=$query", headers)
        } else {
            GET("$baseUrl/page/$page/?s=$query", headers)
        }
    } else {
        var path = ""
        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        path = categoryPaths[filter.state]
                        break
                    }
                }

                is ChannelFilter -> {
                    if (filter.state > 0) {
                        path = channelPaths[filter.state]
                        break
                    }
                }

                is LanguageFilter -> {
                    if (filter.state > 0) {
                        path = languagePaths[filter.state]
                        break
                    }
                }

                is OttFilter -> {
                    if (filter.state > 0) {
                        path = ottPaths[filter.state]
                        break
                    }
                }

                is QualityFilter -> {
                    if (filter.state > 0) {
                        path = qualityPaths[filter.state]
                        break
                    }
                }

                else -> {}
            }
        }
        if (path.isNotBlank()) {
            if (page == 1) {
                GET("$baseUrl/$path/", headers)
            } else {
                GET("$baseUrl/$path/page/$page/", headers)
            }
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            val titleText = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.selectFirst("h1.entry-title")?.text()
                ?: ""
            title = titleText.substringBefore("[").substringBefore("(").trim()

            description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?: document.select(".entry-content p").firstOrNull()?.text()

            val ogImg = document.selectFirst("meta[property=og:image]")?.attr("content")
            val mainImg = document.selectFirst(".entry-content img")?.attr("src")
            thumbnail_url = ogImg ?: mainImg

            genre = document.select(".post-cats a").joinToString(", ") { it.text() }
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    private data class SEpisodeRaw(
        val name: String,
        val episodeNumber: Float,
        val url: String,
    )

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = fastClient.newCall(animeDetailsRequest(anime)).execute().asJsoup()
        val entryContent = document.selectFirst(".entry-content") ?: return emptyList()

        val pageText = document.text()
        val scanlatorText = buildString {
            if (pageText.contains("Multi Audio", true)) {
                append("Multi Audio")
            } else if (pageText.contains("Dual Audio", true)) {
                append("Dual Audio")
            } else if (pageText.contains("Hindi", true)) {
                append("Hindi")
            }

            if (pageText.contains("Sub", true) || pageText.contains("ESub", true)) {
                if (isNotEmpty()) append(" / Sub") else append("Sub")
            }
        }.ifBlank { null }

        val rawEpisodes = mutableListOf<SEpisodeRaw>()

        val accordionItems = entryContent.select("div.mks_accordion_item")
        if (accordionItems.isNotEmpty()) {
            var count = 1
            accordionItems.forEach { item ->
                val headingText = item.selectFirst("div.mks_accordion_heading")?.text()?.trim() ?: "Episode $count"
                val archiveLink = item.selectFirst("div.mks_accordion_content a[href*='archive.toonworld4all.me']")?.attr("href")
                    ?: item.selectFirst("div.mks_accordion_content a[href]")?.attr("href")

                if (!archiveLink.isNullOrBlank() && !headingText.contains("Zip", ignoreCase = true)) {
                    val epNum = EPISODE_NUMBER_REGEX.find(headingText)?.groupValues?.get(1)?.toFloatOrNull() ?: count.toFloat()
                    rawEpisodes.add(SEpisodeRaw(headingText, epNum, archiveLink))
                    count++
                }
            }
        }

        if (rawEpisodes.isEmpty()) {
            val pTags = entryContent.select("p, h4, h3, h2")
            var episodeCount = 1
            pTags.forEach { pTag ->
                val text = pTag.text().trim()
                val episodeMatch = EPISODE_NUMBER_REGEX.find(text)
                if (episodeMatch != null) {
                    val episodeNumber = episodeMatch.groupValues[1].toFloatOrNull() ?: episodeCount.toFloat()
                    val archiveLink = pTag.selectFirst("a[href*='archive.toonworld4all.me']")?.attr("href")
                        ?: pTag.nextElementSibling()?.selectFirst("a[href*='archive.toonworld4all.me']")?.attr("href")

                    if (!archiveLink.isNullOrBlank()) {
                        rawEpisodes.add(SEpisodeRaw(text, episodeNumber, archiveLink))
                        episodeCount++
                    }
                }
            }
        }

        if (rawEpisodes.isEmpty()) {
            val movieLinks = entryContent.select("a[href*='archive.toonworld4all.me'], div.mks_toggle_content a[href], .entry-content p a[href]").mapNotNull { aTag ->
                val href = aTag.attr("href")
                if (href.contains("archive.toonworld4all.me") || href.contains("filemoon") || href.contains("dood")) href else null
            }.distinct()

            if (movieLinks.isNotEmpty()) {
                movieLinks.forEachIndexed { index, link ->
                    rawEpisodes.add(
                        SEpisodeRaw(
                            if (movieLinks.size == 1) "Movie" else "Part ${index + 1}",
                            (index + 1).toFloat(),
                            link,
                        ),
                    )
                }
            }
        }

        return rawEpisodes.parallelCatchingFlatMap { raw ->
            val episode = SEpisode.create().apply {
                name = raw.name
                episode_number = raw.episodeNumber
                url = raw.url
                scanlator = scanlatorText
            }

            if (raw.url.contains("archive.toonworld4all.me")) {
                try {
                    val fullUrl = if (raw.url.startsWith("http")) raw.url else "https://archive.toonworld4all.me" + (if (raw.url.startsWith("/")) "" else "/") + raw.url
                    val req = Request.Builder()
                        .url(fullUrl)
                        .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
                        .build()

                    val resp = fastClient.newCall(req).execute()
                    val html = resp.body.string()
                    resp.close()

                    val propsRegex = Regex("""window\.__PROPS__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
                    val jsonMatch = propsRegex.find(html)?.groupValues?.get(1)
                    if (jsonMatch != null) {
                        val rootObj = Json.parseToJsonElement(jsonMatch).jsonObject
                        val metaObj = rootObj["data"]?.jsonObject?.get("data")?.jsonObject?.get("metadata")?.jsonObject
                        if (metaObj != null) {
                            val epName = metaObj["name"]?.jsonPrimitive?.contentOrNull
                            val overview = metaObj["overview"]?.jsonPrimitive?.contentOrNull
                            val poster = metaObj["poster"]?.jsonPrimitive?.contentOrNull
                            val air = metaObj["air"]?.jsonPrimitive?.contentOrNull

                            if (!epName.isNullOrBlank()) {
                                val cleanHeading = raw.name.substringBefore(":").trim()
                                episode.name = "$cleanHeading: $epName"
                            }
                            if (!overview.isNullOrBlank()) {
                                episode.summary = overview
                            }
                            if (!poster.isNullOrBlank()) {
                                episode.preview_url = poster
                            }
                            if (!air.isNullOrBlank()) {
                                episode.date_upload = parseDate(air)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // fallback
                }
            }

            listOf(episode)
        }.reversed()
    }

    private fun parseDate(dateStr: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(dateStr)?.time ?: 0L
    }.getOrDefault(0L)

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val rawLink = episode.url.trim()
        val link = when {
            rawLink.startsWith("http") -> rawLink
            rawLink.startsWith("/") -> "https://archive.toonworld4all.me$rawLink"
            else -> "https://archive.toonworld4all.me/$rawLink"
        }

        if (link.contains("archive.toonworld4all.me")) {
            val videos = extractVideosFromArchive(link).filter { isPlayableVideoUrl(it.videoUrl) }
            if (videos.isNotEmpty()) return videos

            // Automatic Fallback for old database entries missing /episode/ or /movie/ prefix
            val slug = link.substringAfterLast("/").substringAfterLast("me/")
            if (slug.isNotBlank() && !link.contains("/episode/") && !link.contains("/movie/")) {
                val epFallback = "https://archive.toonworld4all.me/episode/$slug"
                val epVideos = extractVideosFromArchive(epFallback).filter { isPlayableVideoUrl(it.videoUrl) }
                if (epVideos.isNotEmpty()) return epVideos

                val movieFallback = "https://archive.toonworld4all.me/movie/$slug"
                val movieVideos = extractVideosFromArchive(movieFallback).filter { isPlayableVideoUrl(it.videoUrl) }
                if (movieVideos.isNotEmpty()) return movieVideos
            }
        }

        val videoList = mutableListOf<Video>()
        try {
            when {
                link.contains("filemoon.sx") || link.contains("filemoon.") -> {
                    return FilemoonExtractor(fastClient).videosFromUrl(link, "FileMoon - ")
                }

                link.contains("dood.") -> {
                    return DoodExtractor(fastClient).videosFromUrl(link, "DoodStream")
                }

                else -> {
                    val extracted = runCatching { universalExtractor.videosFromUrl(link, headers) }.getOrDefault(emptyList())
                    if (extracted.isNotEmpty()) return extracted.filter { isPlayableVideoUrl(it.videoUrl) }
                    if (isPlayableVideoUrl(link)) {
                        videoList.add(Video(videoUrl = link, videoTitle = "Direct Stream", headers = headers))
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return videoList.filter { isPlayableVideoUrl(it.videoUrl) }
    }

    private fun isPlayableVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("exe.io") ||
            lower.contains("gdflix.") ||
            lower.contains("gdtot") ||
            lower.contains("mega.nz") ||
            lower.contains("appdrive") ||
            lower.contains("drive.google") ||
            lower.contains("filepress.baby") ||
            lower.contains("filepress.")
        ) {
            return false
        }
        return true
    }

    private data class TargetHoster(
        val hostName: String,
        val targetUrl: String,
        val hiddenCode: String,
        val destination: String,
    )

    private suspend fun extractVideosFromArchive(rawArchiveUrl: String): List<Video> {
        val archiveUrl = if (rawArchiveUrl.startsWith("http")) {
            rawArchiveUrl
        } else {
            "https://archive.toonworld4all.me" + (if (rawArchiveUrl.startsWith("/")) "" else "/") + rawArchiveUrl
        }

        val req = Request.Builder()
            .url(archiveUrl)
            .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
            .build()

        val resp = try {
            fastClient.newCall(req).execute()
        } catch (e: Exception) {
            return emptyList()
        }

        val html = resp.body.string()
        resp.close()

        val propsRegex = Regex("""window\.__PROPS__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = propsRegex.find(html)?.groupValues?.get(1) ?: return emptyList()

        val rootObj = try {
            Json.parseToJsonElement(jsonMatch).jsonObject
        } catch (e: Exception) {
            return emptyList()
        }

        val dataField = rootObj["data"]
        val dataObj = if (dataField != null && dataField is kotlinx.serialization.json.JsonObject) {
            dataField.jsonObject["data"]?.jsonObject ?: dataField.jsonObject
        } else {
            rootObj
        }

        val encodesArray = dataObj["encodes"]?.jsonArray
            ?: dataObj["downloads"]?.jsonArray
            ?: dataObj["streams"]?.jsonArray
            ?: rootObj["encodes"]?.jsonArray
            ?: rootObj["downloads"]?.jsonArray
            ?: return emptyList()

        return encodesArray.parallelCatchingFlatMap { encodeElement ->
            val encodeObj = encodeElement.jsonObject
            val resolution = encodeObj["resolution"]?.jsonPrimitive?.content ?: ""
            val readableCodec = encodeObj["readable"]?.jsonObject?.get("codec")?.jsonPrimitive?.content ?: resolution
            val readableSize = encodeObj["readable"]?.jsonObject?.get("size")?.jsonPrimitive?.content ?: ""
            val qualitySuffix = buildString {
                if (readableCodec.isNotBlank()) append(" [$readableCodec]")
                if (readableSize.isNotBlank()) append(" [$readableSize]")
            }

            val filesArray = encodeObj["files"]?.jsonArray ?: return@parallelCatchingFlatMap emptyList()

            filesArray.parallelCatchingFlatMap { fileElement ->
                val fileObj = fileElement.jsonObject
                val hostName = fileObj["host"]?.jsonPrimitive?.content ?: "Server"
                val redirectPath = fileObj["link"]?.jsonPrimitive?.content ?: return@parallelCatchingFlatMap emptyList()

                val redirectUrl = if (redirectPath.startsWith("http")) {
                    redirectPath
                } else {
                    "https://archive.toonworld4all.me" + (if (redirectPath.startsWith("/")) "" else "/") + redirectPath
                }

                val targetHoster = resolveArchiveRedirect(redirectUrl, hostName) ?: return@parallelCatchingFlatMap emptyList()
                val videos = mutableListOf<Video>()

                try {
                    val hubCloudVideos = if (hostName.equals("HubCloud", ignoreCase = true) || targetHoster.targetUrl.contains("hubcloud")) {
                        resolveHubCloudWithCode(targetHoster.hiddenCode, targetHoster.targetUrl, qualitySuffix)
                    } else {
                        emptyList()
                    }

                    if (hubCloudVideos.isNotEmpty()) {
                        videos.addAll(hubCloudVideos)
                    } else {
                        when {
                            hostName.equals("Buzzheavier", ignoreCase = true) || targetHoster.targetUrl.contains("buzzheavier") -> {
                                videos.addAll(BuzzheavierExtractor(fastClient, headers).videosFromUrl(targetHoster.targetUrl, "Buzzheavier$qualitySuffix - "))
                            }

                            hostName.equals("Filemoon", ignoreCase = true) || targetHoster.targetUrl.contains("filemoon") -> {
                                videos.addAll(FilemoonExtractor(fastClient).videosFromUrl(targetHoster.targetUrl, "FileMoon$qualitySuffix - "))
                            }

                            targetHoster.targetUrl.contains("streamwish") || targetHoster.targetUrl.contains("cdnwish") -> {
                                videos.addAll(StreamWishExtractor(fastClient, headers).videosFromUrl(targetHoster.targetUrl, "StreamWish$qualitySuffix"))
                            }

                            targetHoster.targetUrl.contains("vidhide") || targetHoster.targetUrl.contains("streamhg") -> {
                                videos.addAll(VidHideExtractor(fastClient, headers).videosFromUrl(targetHoster.targetUrl) { "VidHide$qualitySuffix - $it" })
                            }

                            else -> {
                                val extracted = runCatching { universalExtractor.videosFromUrl(targetHoster.targetUrl, headers) }.getOrDefault(emptyList())
                                if (extracted.isNotEmpty()) {
                                    videos.addAll(
                                        extracted.map { v ->
                                            Video(
                                                videoUrl = v.videoUrl,
                                                videoTitle = "$hostName$qualitySuffix - ${v.videoTitle}",
                                                headers = v.headers,
                                                subtitleTracks = v.subtitleTracks,
                                            )
                                        },
                                    )
                                } else if (isPlayableVideoUrl(targetHoster.targetUrl)) {
                                    videos.add(
                                        Video(
                                            videoUrl = targetHoster.targetUrl,
                                            videoTitle = "$hostName$qualitySuffix",
                                            headers = headers,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    if (isPlayableVideoUrl(targetHoster.destination) && targetHoster.destination != targetHoster.targetUrl) {
                        videos.add(
                            Video(
                                videoUrl = targetHoster.destination,
                                videoTitle = "$hostName (Mirror)$qualitySuffix",
                                headers = headers,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    if (isPlayableVideoUrl(targetHoster.targetUrl)) {
                        videos.add(
                            Video(
                                videoUrl = targetHoster.targetUrl,
                                videoTitle = "$hostName$qualitySuffix",
                                headers = headers,
                            ),
                        )
                    }
                    if (isPlayableVideoUrl(targetHoster.destination) && targetHoster.destination != targetHoster.targetUrl) {
                        videos.add(
                            Video(
                                videoUrl = targetHoster.destination,
                                videoTitle = "$hostName (Mirror)$qualitySuffix",
                                headers = headers,
                            ),
                        )
                    }
                }
                videos.filter { isPlayableVideoUrl(it.videoUrl) }
            }
        }
    }

    private fun resolveArchiveRedirect(redirectUrl: String, hostName: String): TargetHoster? {
        return try {
            val fullRedirectUrl = if (redirectUrl.startsWith("http")) {
                redirectUrl
            } else {
                "https://archive.toonworld4all.me" + (if (redirectUrl.startsWith("/")) "" else "/") + redirectUrl
            }

            val req = Request.Builder()
                .url(fullRedirectUrl)
                .headers(headers.newBuilder().set("Referer", "https://archive.toonworld4all.me/").build())
                .build()

            val resp = fastClient.newCall(req).execute()
            val html = resp.body.string()
            resp.close()

            val propsRegex = Regex("""window\.__PROPS__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            val jsonMatch = propsRegex.find(html)?.groupValues?.get(1) ?: return null
            val obj = Json.parseToJsonElement(jsonMatch).jsonObject

            val dataField = obj["data"]
            val linkContainer = if (dataField != null && dataField is kotlinx.serialization.json.JsonObject) {
                dataField.jsonObject["link"]?.jsonObject ?: obj["link"]?.jsonObject
            } else {
                obj["link"]?.jsonObject
            } ?: return null

            val domain = linkContainer["domain"]?.jsonPrimitive?.content ?: ""
            val hidden = linkContainer["hidden"]?.jsonPrimitive?.content ?: ""
            val dest = obj["destination"]?.jsonPrimitive?.content ?: ""

            if (domain.isNotBlank() && hidden.isNotBlank()) {
                TargetHoster(hostName, "$domain$hidden", hidden, dest)
            } else if (dest.isNotBlank()) {
                TargetHoster(hostName, dest, hidden, dest)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveHubCloudWithCode(code: String, rawTargetUrl: String, suffix: String): List<Video> {
        val list = mutableListOf<Video>()

        // Fast path 1: Try raw target URL first if valid
        if (rawTargetUrl.isNotBlank() && !rawTargetUrl.contains(".cx/")) {
            val extracted = extractHubCloudFromUrl(rawTargetUrl, suffix)
            if (extracted.isNotEmpty()) return extracted
        }

        // Fast path 2: Active HubCloud domains prioritized
        val hubcloudDomains = listOf(
            "hubcloud.link",
            "hubcloud.ink",
            "hubcloud.club",
            "hubcloud.co",
        )

        for (domain in hubcloudDomains) {
            for (path in listOf("video", "drive")) {
                val testUrl = "https://$domain/$path/$code"
                val extracted = extractHubCloudFromUrl(testUrl, suffix)
                if (extracted.isNotEmpty()) {
                    list.addAll(extracted)
                    return list
                }
            }
        }
        return list
    }

    private fun getRedirectUrl(link: String, referrer: String): String = try {
        val req = Request.Builder()
            .url(link)
            .header("Referer", referrer)
            .headers(headers)
            .build()
        val resp = fastClient.newCall(req).execute()
        val finalUrl = resp.request.url.toString()
        resp.close()
        if (finalUrl.isNotBlank()) finalUrl else link
    } catch (e: Exception) {
        link
    }

    private fun extractHubCloudFromUrl(url: String, suffix: String): List<Video> {
        val list = mutableListOf<Video>()
        try {
            val req = Request.Builder()
                .url(url)
                .headers(headers)
                .build()

            val resp = fastClient.newCall(req).execute()
            var html = resp.body.string()
            val targetUrl = resp.request.url.toString()
            resp.close()

            if (html.contains("404") || html.contains("File Not Found") || html.length < 100) return emptyList()

            // Handle JS window.location.replace redirect
            val jsRedirectMatch = Regex("""window\.location\.replace\('([^']+)'\)""").find(html)
            if (jsRedirectMatch != null) {
                val jsUrl = jsRedirectMatch.groupValues[1]
                val jsReq = Request.Builder().url(jsUrl).headers(headers).build()
                val jsResp = fastClient.newCall(jsReq).execute()
                html = jsResp.body.string()
                jsResp.close()
            }

            // Find #download button
            var dlLink = Regex("""href=["']([^"']+)["'][^>]*id=["']download["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""id=["']download["'][^>]*href=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: ""

            val finalDlUrl = if (dlLink.isNotBlank()) {
                if (!dlLink.startsWith("http")) {
                    val uri = java.net.URI(targetUrl)
                    val base = "${uri.scheme}://${uri.host}"
                    base.trimEnd('/') + "/" + dlLink.trimStart('/')
                } else {
                    dlLink
                }
            } else {
                targetUrl
            }

            val dlReq = Request.Builder().url(finalDlUrl).headers(headers).build()
            val dlResp = fastClient.newCall(dlReq).execute()
            val dlHtml = dlResp.body.string()
            val dlPageUrl = dlResp.request.url.toString()
            dlResp.close()

            val doc = org.jsoup.Jsoup.parse(dlHtml)
            val buttons = doc.select("a.btn, a[class*=btn]")

            buttons.forEach { element ->
                val link = element.attr("href")
                val label = element.text().trim()
                val lowerLabel = label.lowercase()
                if (link.isBlank() || link == "#") return@forEach

                when {
                    lowerLabel.contains("fsl server") || lowerLabel.contains("fslv2") -> {
                        val direct = getRedirectUrl(link, dlPageUrl)
                        if (isPlayableVideoUrl(direct)) {
                            list.add(Video(videoUrl = direct, videoTitle = "HubCloud (FSL)$suffix", headers = headers))
                        }
                    }

                    lowerLabel.contains("download file") -> {
                        val direct = getRedirectUrl(link, dlPageUrl)
                        if (isPlayableVideoUrl(direct)) {
                            list.add(Video(videoUrl = direct, videoTitle = "HubCloud (Download)$suffix", headers = headers))
                        }
                    }

                    lowerLabel.contains("buzzserver") -> {
                        try {
                            val noRedirectClient = fastClient.newBuilder().followRedirects(false).build()
                            val buzzReq = Request.Builder().url("$link/download").header("Referer", link).build()
                            val buzzResp = noRedirectClient.newCall(buzzReq).execute()
                            val dlink = buzzResp.header("hx-redirect") ?: buzzResp.header("HX-Redirect") ?: ""
                            buzzResp.close()
                            val finalLink = if (dlink.isNotBlank()) dlink else getRedirectUrl(link, dlPageUrl)
                            if (isPlayableVideoUrl(finalLink)) {
                                list.add(Video(videoUrl = finalLink, videoTitle = "HubCloud (BuzzServer)$suffix", headers = headers))
                            }
                        } catch (e: Exception) {
                            val direct = getRedirectUrl(link, dlPageUrl)
                            if (isPlayableVideoUrl(direct)) {
                                list.add(Video(videoUrl = direct, videoTitle = "HubCloud (BuzzServer)$suffix", headers = headers))
                            }
                        }
                    }

                    lowerLabel.contains("pixeldra") || lowerLabel.contains("pixelserver") || lowerLabel.contains("pixeldrain") -> {
                        val idMatch = Regex("""pixeldrain\.com/(?:u|file)/([a-zA-Z0-9]+)""").find(link)
                        val id = idMatch?.groupValues?.get(1) ?: ""
                        if (id.isNotBlank()) {
                            list.add(Video(videoUrl = "https://pixeldrain.com/api/file/$id?download", videoTitle = "HubCloud (Pixeldrain)$suffix", headers = headers))
                        } else {
                            val direct = getRedirectUrl(link, dlPageUrl)
                            if (isPlayableVideoUrl(direct)) {
                                list.add(Video(videoUrl = direct, videoTitle = "HubCloud (Pixeldrain)$suffix", headers = headers))
                            }
                        }
                    }

                    lowerLabel.contains("10gbps") || lowerLabel.contains("10 gbps") -> {
                        val direct = getRedirectUrl(link, dlPageUrl)
                        if (isPlayableVideoUrl(direct)) {
                            list.add(Video(videoUrl = direct, videoTitle = "HubCloud (10Gbps)$suffix", headers = headers))
                        }
                    }

                    else -> {
                        val direct = getRedirectUrl(link, dlPageUrl)
                        if (isPlayableVideoUrl(direct)) {
                            list.add(Video(videoUrl = direct, videoTitle = "HubCloud ($label)$suffix", headers = headers))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return list.filter { isPlayableVideoUrl(it.videoUrl) }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality) },
                { it.videoTitle.contains("1080p") },
                { it.videoTitle.contains("720p") },
                { it.videoTitle.contains("480p") },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply only when search query is empty"),
        CategoryFilter(),
        ChannelFilter(),
        LanguageFilter(),
        OttFilter(),
        QualityFilter(),
    )

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Category / Type",
            arrayOf(
                "All",
                "Animated Series",
                "Animated Movies",
                "Anime",
                "Hollywood Movies",
                "List of Shows",
                "All Movies List",
                "TV Shows",
            ),
        )

    private val categoryPaths = arrayOf(
        "",
        "tag/animated-series",
        "tag/animated-movies",
        "category/anime",
        "tag/hollywood-movies",
        "cartoon-shows-list_25",
        "all-movies-list_25",
        "tv-shows-list_25",
    )

    private class ChannelFilter :
        AnimeFilter.Select<String>(
            "Channel",
            arrayOf(
                "All",
                "Cartoon Network",
                "Disney Channel India",
                "Disney XD India",
                "Marvel HQ",
                "Hungama TV",
                "Sony Yay",
                "ETV Bal Bharat",
                "Sonic Nickelodeon",
                "Nick India",
                "Zee Cafe",
                "Pogo",
                "Nick HD+",
                "Kidzone Plus",
                "Discovery Kids",
                "Disney Junior",
                "Big Magic",
                "Spacetoon India",
                "Just Kids! Sahara TV",
                "Star Plus - Fox Kids",
            ),
        )

    private val channelPaths = arrayOf(
        "",
        "tag/cartoon-network-india",
        "tag/disney-channel-india",
        "tag/disney-xd-india",
        "tag/marvel-hq",
        "category/hungama-tv",
        "category/sony-yay",
        "category/etv-bal-bharat",
        "tag/sonic-nickelodeon",
        "tag/nick-india",
        "category/zee-cafe",
        "category/pogo",
        "category/nick-hd",
        "category/kidzone-plus",
        "category/discovery-kids",
        "category/disney-junior",
        "category/big-magic",
        "tag/spacetoon-india",
        "tag/just-kids-sahara-tv",
        "category/star-plus-fox-kids",
    )

    private class LanguageFilter :
        AnimeFilter.Select<String>(
            "Language",
            arrayOf(
                "All",
                "Hindi Dub",
                "Hindi Cartoons",
                "Tamil Dub",
                "Telugu Dub",
                "Malayalam",
                "Eng Sub",
                "Eng Dub",
                "Dual Audio",
                "Multi Audio",
            ),
        )

    private val languagePaths = arrayOf(
        "",
        "category/hindi-dub",
        "tag/hindi-cartoons",
        "category/tamil-dub",
        "category/telugu-dub",
        "category/malayalam",
        "tag/eng-sub-anime",
        "tag/eng-cartoons",
        "tag/dual-audio",
        "tag/multi-audio",
    )

    private class OttFilter :
        AnimeFilter.Select<String>(
            "OTT Network / Platform",
            arrayOf(
                "All",
                "Crunchyroll",
                "Amazon Prime Video",
                "Netflix",
                "Jio Cinema",
                "Zee5",
                "Apple TV+",
                "Hotstar",
                "Disney+",
                "Discovery Plus",
            ),
        )

    private val ottPaths = arrayOf(
        "",
        "category/crunchyroll",
        "category/amazon-prime-video",
        "category/netflix",
        "category/jio-cinema",
        "category/zee5",
        "category/apple-tv",
        "category/hotstar",
        "category/disney",
        "category/discovery-plus",
    )

    private class QualityFilter :
        AnimeFilter.Select<String>(
            "Quality",
            arrayOf(
                "All",
                "1080p",
                "720p",
                "480p",
            ),
        )

    private val qualityPaths = arrayOf(
        "",
        "tag/1080p",
        "tag/720p",
        "tag/480p",
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480")
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p")

        private val EPISODE_NUMBER_REGEX = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
    }
}

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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale

class ToonWorld4All :
    Source(),
    ConfigurableAnimeSource {

    override val name = "ToonWorld4All"
    override val baseUrl = "https://toonworld4all.me"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 7291048561930492815L

    private val universalExtractor by lazy { UniversalExtractor(client) }

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
        val document = client.newCall(animeDetailsRequest(anime)).execute().asJsoup()
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

            if (raw.url.contains("archive.toonworld4all.me/episode/")) {
                try {
                    val req = Request.Builder()
                        .url(raw.url)
                        .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
                        .build()

                    val resp = client.newCall(req).execute()
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
        val link = episode.url
        if (link.contains("archive.toonworld4all.me/episode/")) {
            return extractVideosFromArchive(link)
        }

        val videoList = mutableListOf<Video>()
        try {
            when {
                link.contains("filemoon.sx") || link.contains("filemoon.") -> {
                    return FilemoonExtractor(client).videosFromUrl(link, "FileMoon - ")
                }

                link.contains("dood.") -> {
                    return DoodExtractor(client).videosFromUrl(link, "DoodStream")
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return videoList
    }

    private data class TargetHoster(
        val hostName: String,
        val targetUrl: String,
        val hiddenCode: String,
    )

    private suspend fun extractVideosFromArchive(archiveUrl: String): List<Video> {
        val req = Request.Builder()
            .url(archiveUrl)
            .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
            .build()

        val resp = client.newCall(req).execute()
        val html = resp.body.string()
        resp.close()

        val propsRegex = Regex("""window\.__PROPS__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = propsRegex.find(html)?.groupValues?.get(1) ?: return emptyList()

        val rootObj = try {
            Json.parseToJsonElement(jsonMatch).jsonObject
        } catch (e: Exception) {
            return emptyList()
        }

        val dataObj = rootObj["data"]?.jsonObject?.get("data")?.jsonObject ?: return emptyList()
        val encodesArray = dataObj["encodes"]?.jsonArray ?: dataObj["downloads"]?.jsonArray ?: return emptyList()

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
            val videos = mutableListOf<Video>()

            filesArray.forEach { fileElement ->
                val fileObj = fileElement.jsonObject
                val hostName = fileObj["host"]?.jsonPrimitive?.content ?: "Server"
                val redirectPath = fileObj["link"]?.jsonPrimitive?.content ?: return@forEach
                val redirectUrl = "https://archive.toonworld4all.me" + redirectPath

                val targetHoster = resolveArchiveRedirect(redirectUrl, hostName) ?: return@forEach

                try {
                    when {
                        hostName.equals("HubCloud", ignoreCase = true) || targetHoster.targetUrl.contains("hubcloud") -> {
                            videos.addAll(resolveHubCloudWithCode(targetHoster.hiddenCode, targetHoster.targetUrl, qualitySuffix))
                        }

                        hostName.equals("Buzzheavier", ignoreCase = true) || targetHoster.targetUrl.contains("buzzheavier") -> {
                            videos.addAll(BuzzheavierExtractor(client, headers).videosFromUrl(targetHoster.targetUrl, "Buzzheavier$qualitySuffix - "))
                        }

                        hostName.equals("Filemoon", ignoreCase = true) || targetHoster.targetUrl.contains("filemoon") -> {
                            videos.addAll(FilemoonExtractor(client).videosFromUrl(targetHoster.targetUrl, "FileMoon$qualitySuffix - "))
                        }

                        targetHoster.targetUrl.contains("streamwish") || targetHoster.targetUrl.contains("cdnwish") -> {
                            videos.addAll(StreamWishExtractor(client, headers).videosFromUrl(targetHoster.targetUrl, "StreamWish$qualitySuffix"))
                        }

                        targetHoster.targetUrl.contains("vidhide") || targetHoster.targetUrl.contains("streamhg") -> {
                            videos.addAll(VidHideExtractor(client, headers).videosFromUrl(targetHoster.targetUrl) { "VidHide$qualitySuffix - $it" })
                        }

                        else -> {
                            // Try universal extractor or direct video link fallback
                            val extracted = runCatching { universalExtractor.videosFromUrl(targetHoster.targetUrl, headers) }.getOrDefault(emptyList())
                            if (extracted.isNotEmpty()) {
                                videos.addAll(extracted.map { v -> Video(v.videoUrl, "$hostName$qualitySuffix - ${v.videoTitle}", v.headers, v.subtitleTracks) })
                            } else if (targetHoster.targetUrl.contains(".mp4") || targetHoster.targetUrl.contains(".mkv") || targetHoster.targetUrl.contains(".m3u8")) {
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
                } catch (e: Exception) {
                    // ignore
                }
            }
            videos
        }
    }

    private fun resolveArchiveRedirect(redirectUrl: String, hostName: String): TargetHoster? {
        return try {
            val req = Request.Builder()
                .url(redirectUrl)
                .headers(headers.newBuilder().set("Referer", "https://archive.toonworld4all.me/").build())
                .build()

            val resp = client.newCall(req).execute()
            val html = resp.body.string()
            resp.close()

            val propsRegex = Regex("""window\.__PROPS__\s*=\s*(\{.*?\});\s*</script>""", RegexOption.DOT_MATCHES_ALL)
            val jsonMatch = propsRegex.find(html)?.groupValues?.get(1) ?: return null
            val obj = Json.parseToJsonElement(jsonMatch).jsonObject
            val linkObj = obj["link"]?.jsonObject ?: return null

            val domain = linkObj["domain"]?.jsonPrimitive?.content ?: ""
            val hidden = linkObj["hidden"]?.jsonPrimitive?.content ?: ""

            if (domain.isNotBlank() && hidden.isNotBlank()) {
                TargetHoster(hostName, "$domain$hidden", hidden)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveHubCloudWithCode(code: String, rawTargetUrl: String, suffix: String): List<Video> {
        val list = mutableListOf<Video>()
        val hubcloudDomains = listOf(
            "hubcloud.club",
            "hubcloud.link",
            "hubcloud.foo",
            "hubcloud.lol",
            "hubcloud.one",
            "hubcloud.vip",
            "hubcloud.ink",
        )

        for (domain in hubcloudDomains) {
            for (path in listOf("video", "drive", "file")) {
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

    private fun extractHubCloudFromUrl(url: String, suffix: String): List<Video> {
        val list = mutableListOf<Video>()
        try {
            val req = Request.Builder()
                .url(url)
                .headers(headers)
                .build()

            val resp = client.newCall(req).execute()
            var html = resp.body.string()
            resp.close()

            if (html.contains("404") || html.contains("File Not Found") || html.length < 100) return emptyList()

            // Handle JS window.location.replace redirect
            val jsRedirectMatch = Regex("""window\.location\.replace\('([^']+)'\)""").find(html)
            if (jsRedirectMatch != null) {
                val jsUrl = jsRedirectMatch.groupValues[1]
                val jsReq = Request.Builder().url(jsUrl).headers(headers).build()
                val jsResp = client.newCall(jsReq).execute()
                html = jsResp.body.string()
                jsResp.close()
            }

            // Find #download button
            var dlLink = Regex("""href=["']([^"']+)["'][^>]*id=["']download["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""id=["']download["'][^>]*href=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: ""

            if (dlLink.isNotBlank()) {
                if (!dlLink.startsWith("http")) {
                    val uri = java.net.URI(url)
                    val base = "${uri.scheme}://${uri.host}"
                    dlLink = base.trimEnd('/') + "/" + dlLink.trimStart('/')
                }

                val dlReq = Request.Builder().url(dlLink).headers(headers).build()
                val dlResp = client.newCall(dlReq).execute()
                val dlHtml = dlResp.body.string()
                dlResp.close()

                val doc = org.jsoup.Jsoup.parse(dlHtml)
                doc.select("a.btn, a[class*=btn]").forEach { element ->
                    val link = element.attr("href")
                    val label = element.text().lowercase()

                    when {
                        label.contains("fsl server") || label.contains("fslv2") -> {
                            if (link.contains("r2.cloudflarestorage.com") || link.contains("googleusercontent.com") || link.contains(".mp4") || link.contains(".mkv")) {
                                list.add(Video(videoUrl = link, videoTitle = "HubCloud (FSL)$suffix", headers = headers))
                            }
                        }

                        label.contains("download file") -> {
                            if (link.contains("r2.cloudflarestorage.com") || link.contains("googleusercontent.com") || link.contains(".mp4") || link.contains(".mkv")) {
                                list.add(Video(videoUrl = link, videoTitle = "HubCloud (Download)$suffix", headers = headers))
                            }
                        }

                        label.contains("buzzserver") -> {
                            try {
                                val noRedirectClient = client.newBuilder().followRedirects(false).build()
                                val buzzReq = Request.Builder().url("$link/download").header("Referer", link).build()
                                val buzzResp = noRedirectClient.newCall(buzzReq).execute()
                                val dlink = buzzResp.header("hx-redirect") ?: buzzResp.header("HX-Redirect") ?: ""
                                buzzResp.close()
                                if (dlink.isNotBlank()) {
                                    list.add(Video(videoUrl = dlink, videoTitle = "HubCloud (BuzzServer)$suffix", headers = headers))
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }

                        label.contains("pixeldra") || label.contains("pixelserver") || label.contains("pixeldrain") -> {
                            val idMatch = Regex("""pixeldrain\.com/(?:u|file)/([a-zA-Z0-9]+)""").find(link)
                            val id = idMatch?.groupValues?.get(1) ?: ""
                            if (id.isNotBlank()) {
                                list.add(Video(videoUrl = "https://pixeldrain.com/api/file/$id?download", videoTitle = "HubCloud (Pixeldrain)$suffix", headers = headers))
                            }
                        }

                        label.contains("10gbps") || label.contains("10 gbps") -> {
                            try {
                                val gpdlResp = client.newCall(GET(link, headers)).execute()
                                val finalUrl = gpdlResp.request.url.toString()
                                gpdlResp.close()

                                if (finalUrl.contains("gamerxyt.com/dl.php?link=")) {
                                    val directLink = URLDecoder.decode(finalUrl.substringAfter("dl.php?link="), "UTF-8")
                                    if (directLink.isNotEmpty()) {
                                        list.add(Video(videoUrl = directLink, videoTitle = "HubCloud (10Gbps)$suffix", headers = headers))
                                    }
                                } else if (finalUrl.contains("video-downloads.googleusercontent.com")) {
                                    list.add(Video(videoUrl = finalUrl, videoTitle = "HubCloud (10Gbps)$suffix", headers = headers))
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
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

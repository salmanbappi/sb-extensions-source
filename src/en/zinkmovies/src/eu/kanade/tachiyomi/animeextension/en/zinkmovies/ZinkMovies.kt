package eu.kanade.tachiyomi.animeextension.en.zinkmovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class ZinkMovies : Source() {

    override val name = "ZinkMovies"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/trending/page/$page/" else "$baseUrl/trending/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/movies/page/$page/" else "$baseUrl/movies/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/page/$page/?s=$encodedQuery"
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimeListPage(response, page)
        }

        var categoryPath = ""
        for (filter in filters) {
            when (filter) {
                is Filters.CategoryFilter -> {
                    if (!filter.isDefault()) {
                        categoryPath = filter.toUriPart()
                    }
                }

                else -> {}
            }
        }

        val targetPath = categoryPath.ifBlank { "movies" }
        val url = if (page > 1) "$baseUrl/$targetPath/page/$page/" else "$baseUrl/$targetPath/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.CategoryFilter(),
    )

    private fun parseAnimeListPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article.item, div.items article, article[id^=post-]").mapNotNull { element ->
            val linkEl = element.selectFirst("div.poster a, .data h3 a, h3 a, a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@mapNotNull null

            val imgEl = element.selectFirst("div.poster img, img")
            val imgUrl = imgEl?.attr("data-lazy-src")?.ifEmpty { imgEl.attr("abs:src").ifEmpty { imgEl.attr("src") } }

            val rawTitle = imgEl?.attr("alt")?.ifEmpty { linkEl.text() } ?: linkEl.text()
            val cleanTitle = cleanAnimeTitle(rawTitle)

            SAnime.create().apply {
                title = cleanTitle
                setUrlWithoutDomain(href)
                thumbnail_url = imgUrl
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.select(".horizontal-pagination a.next-page, .pagination a.next-page, a.next-page, a.page-numbers:contains(${page + 1})").isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    private fun cleanAnimeTitle(title: String): String = title
        .replace(Regex("""\s*\{[^}]*\}"""), "")
        .replace(Regex("""\s*(Dual Audio|Multi Audio|Hindi Dubbed|Hindi Movie|CR WEB-DL|WEB-DL|BluRay|HDTC|ESubs|MSubs|NF|Hollywood|Bollywood).*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""(?i)\s*ZinkMovies(\.org|\.mobi)?"""), "")
        .trim()
        .ifEmpty { title.trim() }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        val rawTitle = doc.selectFirst(".sheader h1, .data h1, .post-title h1, h1:not(.text-logo), .entry-title")?.text() ?: anime.title
        val posterEl = doc.selectFirst("div.poster img, .sheader .poster img, img[itemprop=image]")
        val posterUrl = posterEl?.attr("data-lazy-src")?.ifEmpty { posterEl.attr("abs:src").ifEmpty { posterEl.attr("src") } }

        val synopsis = doc.selectFirst("div.wp-content, div[itemprop=description], #info .wp-content")?.text()
            ?.substringBefore("Multi Audio")
            ?.substringBefore("Dual Audio")
            ?.substringBefore("Download")
            ?.trim() ?: ""

        val ratingText = doc.selectFirst(".starstruck-rating span.dt_rating_vgs, span[itemprop=ratingValue]")?.text()
        val score = ratingText?.toDoubleOrNull()
        val releaseDate = doc.selectFirst("span.date, span[itemprop=dateCreated]")?.text() ?: ""
        val country = doc.selectFirst("span.country")?.text() ?: ""

        return SAnime.create().apply {
            title = cleanAnimeTitle(rawTitle)
            thumbnail_url = posterUrl ?: anime.thumbnail_url
            genre = doc.select("div.sgeneros a, .genres a").joinToString { it.text() }
            status = SAnime.COMPLETED
            initialized = true

            description = buildString {
                if (score != null && score > 0.0) {
                    val full = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(score)}\n\n")
                }
                if (synopsis.isNotBlank()) append("$synopsis\n\n")
                if (releaseDate.isNotBlank()) append("Released: $releaseDate\n")
                if (country.isNotBlank()) append("Country: $country\n")
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        // 1. Check for LinkStore batch buttons (TV Series)
        val linkStoreButtons = doc.select("a[href*=linkstore.zinkcloud.net], a[href*=linkstore.]")

        if (linkStoreButtons.isNotEmpty()) {
            // Deduplicate to fetch 1 linkstore page per unique season
            val seasonBatchMap = mutableMapOf<Int, String>()
            linkStoreButtons.forEach { btn ->
                val seasonNum = Regex("""(?i)Season\s*0*(\d+)""").find(btn.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val href = btn.attr("href")
                if (href.isNotBlank() && seasonNum !in seasonBatchMap) {
                    seasonBatchMap[seasonNum] = href
                }
            }

            val totalSeasons = seasonBatchMap.size
            val episodes = mutableListOf<SEpisode>()

            seasonBatchMap.entries.forEach { (seasonNum, linkStoreUrl) ->
                runCatching {
                    val lsDoc = client.newCall(GET(linkStoreUrl, headers)).execute().asJsoup()
                    val seasonEpNums = mutableSetOf<Int>()

                    lsDoc.select("a[href*=/file/]").forEach { epLink ->
                        val epText = epLink.text().trim()
                        if (epText.contains("Zip", ignoreCase = true)) return@forEach

                        val epNum = Regex("""(?i)EPISODE\s*-\s*0*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""(?i)E0*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: return@forEach

                        seasonEpNums.add(epNum)
                    }

                    seasonEpNums.sorted().forEach { epNum ->
                        val epName = if (totalSeasons > 1) {
                            "Season $seasonNum - Episode $epNum"
                        } else {
                            "Episode $epNum"
                        }

                        episodes.add(
                            SEpisode.create().apply {
                                name = epName
                                setUrlWithoutDomain("${anime.url}#season=$seasonNum&ep=$epNum")
                                episode_number = if (totalSeasons > 1) {
                                    ((seasonNum - 1) * 100 + epNum).toFloat()
                                } else {
                                    epNum.toFloat()
                                }
                            },
                        )
                    }
                }
            }

            if (episodes.isNotEmpty()) {
                return episodes.reversed()
            }
        }

        // 2. Check for Movie File Buttons / DooPlay Link Tables / LinkStore Movie Pages
        val hasMovieButtons = doc.select("a[href*=/file/], div.movie-button-container a, a.movie-simple-button, #download .links_table tr, .links_table tr, a[href*=/links/], a[href*=linkstore]").isNotEmpty()
        if (hasMovieButtons) {
            return listOf(
                SEpisode.create().apply {
                    name = "Full Movie"
                    setUrlWithoutDomain("${anime.url}#movie")
                    episode_number = 1f
                },
            )
        }

        return emptyList()
    }

    // ============================ Video Links & Hosters ===================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val rawUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val baseAnimePath = rawUrl.substringBefore("#")
        val isMovie = episode.url.contains("#movie") || !episode.url.contains("ep=")
        val targetSeason = Regex("""season=(\d+)""").find(episode.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val targetEp = Regex("""ep=(\d+)""").find(episode.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val doc = client.newCall(GET(baseAnimePath, headers)).execute().asJsoup()
        val qualityFiles = mutableListOf<Pair<String, String>>()

        if (isMovie) {
            // 1. Direct movie quality buttons
            doc.select("a[href*=/file/], div.movie-button-container a, a.movie-simple-button").forEach { btn ->
                val href = btn.attr("abs:href").ifEmpty { btn.attr("href") }
                if (href.isNotBlank() && qualityFiles.none { it.second == href }) {
                    val quality = Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(btn.text())?.groupValues?.get(1)?.uppercase() ?: "HD"
                    qualityFiles.add(Pair(quality, href))
                }
            }

            // 2. DooPlay Download Links Table (e.g. Inside Out 2)
            doc.select("#download .links_table tr, .links_table tr, tr[id^=link-]").forEach { tr ->
                val linkEl = tr.selectFirst("a[href*=/links/], td a[href*=/links/], td:first-child a") ?: return@forEach
                val linkHref = linkEl.attr("abs:href").ifEmpty { linkEl.attr("href") }
                if (linkHref.isBlank() || linkHref.contains("#") || linkHref.startsWith("javascript")) return@forEach

                val qText = tr.selectFirst("strong.quality, td:nth-child(2)")?.text() ?: ""
                val quality = Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(qText)?.groupValues?.get(1)?.uppercase()
                    ?: Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(linkEl.text())?.groupValues?.get(1)?.uppercase()
                    ?: "HD"

                val resolvedUrl = resolveLinkRedirect(linkHref)
                if (resolvedUrl.isNotBlank() && qualityFiles.none { it.second == resolvedUrl }) {
                    qualityFiles.add(Pair(quality, resolvedUrl))
                }
            }

            // 3. Single Movie LinkStore
            doc.select("a[href*=linkstore.zinkcloud.net], a[href*=linkstore.]").forEach { lsBtn ->
                val lsHref = lsBtn.attr("abs:href").ifEmpty { lsBtn.attr("href") }
                val lsText = lsBtn.text().trim()
                if (lsHref.isNotBlank() && !lsText.contains("Season", ignoreCase = true)) {
                    runCatching {
                        val lsDoc = client.newCall(GET(lsHref, headers)).execute().asJsoup()
                        val pageTitle = lsDoc.title()
                        val quality = Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(pageTitle)?.groupValues?.get(1)?.uppercase() ?: "HD"
                        lsDoc.select("a[href*=/file/]").forEach { epLink ->
                            val fHref = epLink.attr("abs:href").ifEmpty { epLink.attr("href") }
                            if (fHref.isNotBlank() && qualityFiles.none { it.second == fHref }) {
                                qualityFiles.add(Pair(quality, fHref))
                            }
                        }
                    }
                }
            }
        } else {
            // TV series: find all linkstore batches for this season
            val linkStoreButtons = doc.select("a[href*=linkstore.zinkcloud.net], a[href*=linkstore.]")
            val seasonBatches = linkStoreButtons.filter { btn ->
                val sNum = Regex("""(?i)Season\s*0*(\d+)""").find(btn.text())?.groupValues?.get(1)?.toIntOrNull() ?: 1
                sNum == targetSeason
            }.ifEmpty { linkStoreButtons }

            seasonBatches.forEach { btn ->
                val href = btn.attr("href")
                val btnText = btn.text().trim()
                val quality = Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(btnText)?.groupValues?.get(1)?.uppercase() ?: "HD"

                runCatching {
                    val lsDoc = client.newCall(GET(href, headers)).execute().asJsoup()
                    lsDoc.select("a[href*=/file/]").forEach { epLink ->
                        val epText = epLink.text().trim()
                        if (epText.contains("Zip", ignoreCase = true)) return@forEach

                        val epNum = Regex("""(?i)EPISODE\s*-\s*0*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Regex("""(?i)E0*(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()

                        if (epNum == targetEp) {
                            val fileHref = epLink.attr("abs:href").ifEmpty { epLink.attr("href") }
                            if (fileHref.isNotBlank() && qualityFiles.none { it.second == fileHref }) {
                                qualityFiles.add(Pair(quality, fileHref))
                            }
                        }
                    }
                }
            }
        }

        // Map: ServerName -> List of "Quality|StreamOrPageUrl"
        val serverMap = mutableMapOf<String, MutableList<Pair<String, String>>>()

        qualityFiles.parallelCatchingFlatMap { (quality, fileUrl) ->
            val resolvedServers = resolveServersForFile(fileUrl)
            synchronized(serverMap) {
                resolvedServers.forEach { (serverName, url) ->
                    if (serverName !in excludedServers) {
                        val list = serverMap.getOrPut(serverName) { mutableListOf() }
                        if (list.none { it.first == quality && it.second == url }) {
                            list.add(Pair(quality, url))
                        }
                    }
                }
            }
            emptyList<Unit>()
        }

        return serverMap.map { (serverName, entries) ->
            Hoster(
                hosterName = serverName,
                hosterUrl = entries.joinToString(";;") { "${it.first}|${it.second}" },
            )
        }.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    private fun resolveLinkRedirect(linkUrl: String): String = try {
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val resp = noRedirectClient.newCall(
            GET(linkUrl, headers.newBuilder().set("Referer", "$baseUrl/").build()),
        ).execute()
        val loc = resp.header("Location") ?: ""
        resp.close()
        loc.ifBlank {
            val resp2 = client.newCall(GET(linkUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())).execute()
            val finalUrl = resp2.request.url.toString()
            resp2.close()
            finalUrl
        }
    } catch (e: Exception) {
        ""
    }

    private fun resolveServersForFile(fileUrl: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (fileUrl.isBlank()) return result

        try {
            // A. Handle GDFlix / Direct file pages
            if (fileUrl.contains("gdflix", ignoreCase = true) || fileUrl.contains("gdlink", ignoreCase = true)) {
                val gdResp = client.newCall(GET(fileUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())).execute()
                val gdHtml = gdResp.body.string()
                gdResp.close()

                val gdDoc = Jsoup.parse(gdHtml, fileUrl)

                // 1. Instant DL [10GBPS]
                val instantHref = gdDoc.selectFirst("a[href*=instant.busycdn.xyz], a:contains(Instant DL)")?.let {
                    it.attr("abs:href").ifEmpty { it.attr("href") }
                } ?: ""
                if (instantHref.isNotBlank()) {
                    result["GDFlix"] = instantHref
                    result["Fast Cloud"] = instantHref
                }

                // 2. FAST CLOUD / ZIPDISK
                val cflareHref = gdDoc.selectFirst("a[href*=/cflare/]")?.let {
                    it.attr("abs:href").ifEmpty { it.attr("href") }
                } ?: ""
                if (cflareHref.isNotBlank()) {
                    result.putIfAbsent("Fast Cloud", cflareHref)
                }

                // 3. Direct server buttons
                gdDoc.select("a.btn[href], a[class*=btn][href]").forEach { a ->
                    val href = a.attr("abs:href").ifEmpty { a.attr("href") }
                    val label = a.text().lowercase()
                    if (href.isBlank() || href == fileUrl || href.startsWith("javascript")) return@forEach

                    when {
                        label.contains("hubcloud") || href.contains("hubcloud") -> result.putIfAbsent("HubCloud", href)
                        label.contains("filepress") || href.contains("filebee") -> result.putIfAbsent("FilePress", href)
                    }
                }
                return result
            }

            // B. Handle ZinkCloud (new4.zinkcloud.net/file/...)
            val fileId = fileUrl.substringAfterLast("/file/").substringBefore("?").substringBefore("/")
            if (fileId.isNotBlank()) {
                val hostUrl = fileUrl.toHttpUrlOrNull()
                val baseCloud = if (hostUrl != null) "${hostUrl.scheme}://${hostUrl.host}" else "https://new4.zinkcloud.net"

                // 1. Request masked route token
                val tokenUrl = "$baseCloud/ajax_generate_token.php?random_id=$fileId"
                val formBody = FormBody.Builder()
                    .add("random_id", fileId)
                    .build()

                val tokenReq = Request.Builder()
                    .url(tokenUrl)
                    .post(formBody)
                    .headers(headers.newBuilder().set("Referer", fileUrl).set("X-Requested-With", "XMLHttpRequest").build())
                    .build()

                val tokenResp = client.newCall(tokenReq).execute()
                val tokenJsonStr = tokenResp.body.string()
                tokenResp.close()

                val tokenJson = json.parseToJsonElement(tokenJsonStr).jsonObject
                val token = tokenJson["token"]?.jsonPrimitive?.content ?: ""
                if (token.isNotBlank()) {
                    // 2. Fetch DL Page
                    val dlUrl = "$baseCloud/dl/$token"
                    val dlResp = client.newCall(GET(dlUrl, headers.newBuilder().set("Referer", fileUrl).build())).execute()
                    val dlHtml = dlResp.body.string()
                    dlResp.close()

                    val dlDoc = Jsoup.parse(dlHtml, dlUrl)

                    // Extract Server Handler URL
                    val serverHandlerUrl = Regex("""const\s+SERVER_HANDLER_URL\s*=\s*["']([^"']+)["']""")
                        .find(dlHtml)?.groupValues?.get(1) ?: "https://new4.zinkcloud.net/server-handler.php"

                    // 3. Query server-handler
                    val targetServers = listOf(
                        Pair("worker", "Fast Cloud"),
                        Pair("hubcloud", "HubCloud"),
                        Pair("gdflix", "GDFlix"),
                        Pair("filepress", "FilePress"),
                    )

                    targetServers.forEach { (apiServerKey, displayServerName) ->
                        try {
                            val reqJson = """{"server":"$apiServerKey","random_id":"$fileId"}"""
                            val postReq = Request.Builder()
                                .url(serverHandlerUrl)
                                .post(reqJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                                .headers(headers.newBuilder().set("Referer", dlUrl).set("X-Requested-With", "XMLHttpRequest").build())
                                .build()

                            val sResp = client.newCall(postReq).execute()
                            val sJsonStr = sResp.body.string()
                            sResp.close()

                            val sObj = json.parseToJsonElement(sJsonStr).jsonObject
                            val isSuccess = sObj["success"]?.jsonPrimitive?.booleanOrNull ?: false
                            val resUrl = sObj["url"]?.jsonPrimitive?.content ?: ""

                            if (isSuccess && resUrl.isNotBlank()) {
                                result[displayServerName] = resUrl
                            }
                        } catch (e: Exception) {
                            // skip server failure
                        }
                    }

                    // 4. Parse direct anchor links on DL page
                    dlDoc.select("a.btn[href], a[class*=btn][href]").forEach { a ->
                        val href = a.attr("abs:href").ifEmpty { a.attr("href") }
                        val label = a.text().lowercase()
                        if (href.isBlank() || href == dlUrl || href.startsWith("whatsapp") || href.startsWith("javascript")) return@forEach

                        when {
                            label.contains("hubcloud") || href.contains("hubcloud") -> {
                                result.putIfAbsent("HubCloud", href)
                            }

                            label.contains("gdflix") || href.contains("gdlink") -> {
                                result.putIfAbsent("GDFlix", href)
                            }

                            label.contains("filepress") || href.contains("filebee") -> {
                                result.putIfAbsent("FilePress", href)
                            }

                            label.contains("gcloud") || href.contains("gdshare") -> {
                                result.putIfAbsent("GCloud", href)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return result
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val serverName = hoster.hosterName
        val qualityEntries = hoster.hosterUrl.split(";;").mapNotNull { item ->
            val parts = item.split("|")
            if (parts.size >= 2) Pair(parts[0], parts[1]) else null
        }

        val videoList = qualityEntries.parallelCatchingFlatMap { (quality, serverUrl) ->
            when {
                serverUrl.contains("instant.busycdn.xyz") || serverUrl.contains("busycdn") -> {
                    val redirectUrl = getRedirectUrl(serverUrl, "$baseUrl/")
                    val finalStreamUrl = if (redirectUrl.contains("?url=")) {
                        URLDecoder.decode(redirectUrl.substringAfter("?url="), "UTF-8")
                    } else {
                        redirectUrl
                    }
                    if (finalStreamUrl.isNotBlank()) {
                        listOf(
                            Video(
                                videoUrl = finalStreamUrl,
                                videoTitle = "$quality - $serverName",
                                headers = headers,
                                resolution = parseResolution(quality),
                            ),
                        )
                    } else {
                        emptyList()
                    }
                }

                serverName == "HubCloud" || serverUrl.contains("hubcloud") || serverUrl.contains("gamerxyt") -> {
                    resolveHubCloud(serverUrl, quality)
                }

                serverName == "Fast Cloud" -> {
                    val finalUrl = if (serverUrl.contains("/cflare/")) {
                        getRedirectUrl(serverUrl, "$baseUrl/")
                    } else {
                        serverUrl
                    }
                    listOf(
                        Video(
                            videoUrl = finalUrl,
                            videoTitle = "$quality - Fast Cloud",
                            headers = headers,
                            resolution = parseResolution(quality),
                        ),
                    )
                }

                else -> {
                    listOf(
                        Video(
                            videoUrl = serverUrl,
                            videoTitle = "$quality - $serverName",
                            headers = headers,
                            resolution = parseResolution(quality),
                        ),
                    )
                }
            }
        }

        return videoList.distinctBy { it.videoUrl }.sortVideos()
    }

    private fun resolveHubCloud(hubCloudUrl: String, baseQuality: String): List<Video> {
        val list = mutableListOf<Video>()
        try {
            val resp = client.newCall(GET(hubCloudUrl, headers)).execute()
            val html = resp.body.string()
            resp.close()

            val doc = Jsoup.parse(html, hubCloudUrl)
            val downloadHref = doc.selectFirst("a[href*=gamerxyt.com/hubcloud.php], a#download")?.attr("abs:href")
                ?: doc.selectFirst("a#download")?.attr("href")
                ?: ""

            if (downloadHref.isNotBlank()) {
                val resp2 = client.newCall(GET(downloadHref, headers.newBuilder().set("Referer", hubCloudUrl).build())).execute()
                val doc2 = resp2.asJsoup()
                resp2.close()

                val cardHeaderQuality = doc2.selectFirst("div.card-header")?.text()?.let { text ->
                    Regex("""(?i)(480p|720p|1080p|4k|2160p)""").find(text)?.groupValues?.get(1)?.uppercase()
                }
                val qualityLabel = cardHeaderQuality ?: baseQuality

                doc2.select("a.btn, a[class*=btn]").forEach { btn ->
                    val href = btn.attr("abs:href").ifEmpty { btn.attr("href") }
                    val label = btn.text().lowercase()

                    when {
                        label.contains("fsl") || label.contains("download file") -> {
                            val directLink = if (href.contains("r2.cloudflarestorage.com")) href else getRedirectUrl(href, downloadHref)
                            if (directLink.isNotBlank()) {
                                list.add(
                                    Video(
                                        videoUrl = directLink,
                                        videoTitle = "$qualityLabel - HubCloud [FSL]",
                                        headers = headers,
                                        resolution = parseResolution(qualityLabel),
                                    ),
                                )
                            }
                        }

                        label.contains("10gbps") || label.contains("10 gbps") -> {
                            try {
                                val gpdlResp = client.newCall(GET(href, headers)).execute()
                                val finalUrl = gpdlResp.request.url.toString()
                                gpdlResp.close()

                                if (finalUrl.contains("gamerxyt.com/dl.php?link=")) {
                                    val direct = URLDecoder.decode(finalUrl.substringAfter("dl.php?link="), "UTF-8")
                                    if (direct.isNotBlank()) {
                                        list.add(
                                            Video(
                                                videoUrl = direct,
                                                videoTitle = "$qualityLabel - HubCloud [10Gbps]",
                                                headers = headers,
                                                resolution = parseResolution(qualityLabel),
                                            ),
                                        )
                                    }
                                } else if (finalUrl.contains("googleusercontent.com")) {
                                    list.add(
                                        Video(
                                            videoUrl = finalUrl,
                                            videoTitle = "$qualityLabel - HubCloud [10Gbps]",
                                            headers = headers,
                                            resolution = parseResolution(qualityLabel),
                                        ),
                                    )
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }

                        label.contains("pixeldrain") || label.contains("pixel") -> {
                            val pxlMatch = Regex("""var\s+pxl\s*=\s*"([^"]+)"""").find(doc2.html())
                            val realLink = pxlMatch?.groupValues?.get(1) ?: href
                            val fileId = realLink.substringAfterLast("/u/").substringAfterLast("/file/").substringBefore("?")
                            if (fileId.isNotBlank()) {
                                list.add(
                                    Video(
                                        videoUrl = "https://pixeldrain.com/api/file/$fileId?download",
                                        videoTitle = "$qualityLabel - HubCloud [Pixeldrain]",
                                        headers = headers,
                                        resolution = parseResolution(qualityLabel),
                                    ),
                                )
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

    private fun getRedirectUrl(url: String, referer: String): String = try {
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val req = Request.Builder()
            .url(url)
            .head()
            .headers(headers.newBuilder().set("Referer", referer).build())
            .build()
        val resp = noRedirectClient.newCall(req).execute()
        val loc = resp.header("Location") ?: ""
        resp.close()
        if (loc.isNotBlank()) {
            loc
        } else {
            val fullResp = client.newCall(GET(url, headers.newBuilder().set("Referer", referer).build())).execute()
            val finalUrl = fullResp.request.url.toString()
            fullResp.close()
            finalUrl
        }
    } catch (e: Exception) {
        url
    }

    private fun parseResolution(quality: String): Int = when {
        quality.contains("2160", ignoreCase = true) || quality.contains("4k", ignoreCase = true) -> 2160
        quality.contains("1080", ignoreCase = true) -> 1080
        quality.contains("720", ignoreCase = true) -> 720
        quality.contains("480", ignoreCase = true) -> 480
        quality.contains("360", ignoreCase = true) -> 360
        else -> 0
    }

    // ============================ Preferences & Sorting ===================
    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
            entryValues = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select server folders to hide",
            entries = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
            entryValues = listOf("Fast Cloud", "HubCloud", "GDFlix", "FilePress"),
            default = emptySet(),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://new2.zinkmovies.mobi"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Fast Cloud"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

package eu.kanade.tachiyomi.animeextension.en.moviesmod

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlin.time.Duration.Companion.seconds
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder

class Moviesmod : Source() {

    override val name = "MoviesMod"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(network.client))
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = if (page > 1) "$baseUrl/page/$page/?s=$encoded" else "$baseUrl/?s=$encoded"
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimeListPage(response, page)
        }

        var categoryUrl: String? = null
        for (filter in filters) {
            when (filter) {
                is Filters.CategoryFilter -> {
                    if (!filter.isDefault()) {
                        categoryUrl = filter.toUriPart()
                    }
                }
                else -> {}
            }
        }

        val url = if (!categoryUrl.isNullOrBlank()) {
            if (page > 1) "$baseUrl/$categoryUrl/page/$page/" else "$baseUrl/$categoryUrl/"
        } else {
            if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        }

        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.CategoryFilter(),
    )

    private fun parseAnimeListPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.latestPost, article.post-item, div.latestPost").mapNotNull { element ->
            val linkEl = element.selectFirst("a.post-image, h2.title a, a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@mapNotNull null

            val rawTitle = linkEl.attr("title").ifBlank { linkEl.text() }.ifBlank {
                element.selectFirst("h2.title")?.text() ?: ""
            }
            val cleanTitle = Parser.unescapeEntities(rawTitle, false).removePrefix("Download ").trim()
            val imgEl = element.selectFirst("img.wp-post-image, div.featured-thumbnail img, img")
            val thumb = imgEl?.attr("abs:src")?.ifBlank { imgEl.attr("src") }

            SAnime.create().apply {
                title = cleanTitle
                setUrlWithoutDomain(href)
                thumbnail_url = thumb
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.select("nav.pagination .nav-links a[href*=/page/${page + 1}/], a.next").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()
        val content = doc.selectFirst("div.entry-content")

        // Check for WordPress IMDb plugin widget (present on almost all modern posts)
        val imdbWp = doc.selectFirst("div.imdbwp")
        val imdbTitle = imdbWp?.selectFirst("span.imdbwp__title")?.text()?.trim()
        val imdbThumb = imdbWp?.selectFirst("img.imdbwp__img")?.attr("abs:src")?.takeIf { it.isNotBlank() }
        val imdbRating = imdbWp?.selectFirst("span.imdbwp__star")?.text()?.trim()
            ?: imdbWp?.selectFirst("span.imdbwp__rating")?.text()?.substringAfter("Rating:")?.substringBefore("/")?.trim()
        val imdbMeta = imdbWp?.selectFirst("div.imdbwp__meta")?.text()?.trim()
        val imdbTeaser = imdbWp?.selectFirst("div.imdbwp__teaser")?.text()?.trim()
        val imdbFooter = imdbWp?.selectFirst("div.imdbwp__footer")?.text()?.trim()

        val titleText = imdbTitle ?: doc.selectFirst("h1.entry-title, h1")?.text()?.removePrefix("Download ")?.trim() ?: anime.title
        val thumb = imdbThumb ?: doc.selectFirst(
            "div.entry-content img[src*=/uploads/], div.entry-content img[src*=/covers/], img.wp-post-image",
        )?.attr("abs:src") ?: anime.thumbnail_url

        val infoMap = mutableMapOf<String, String>()
        content?.select("p")?.forEach { p ->
            p.select("strong").forEach { strong ->
                val label = strong.text().trimEnd(':').trim().lowercase()
                val nextEl = strong.nextElementSibling()
                val value = nextEl?.text()?.trim() ?: strong.nextSibling()?.toString()
                    ?.replace(Regex("""<[^>]+>"""), "")?.removePrefix(":")?.removePrefix(" -")?.trim()
                if (label.isNotBlank() && !value.isNullOrBlank()) {
                    infoMap.putIfAbsent(label, value)
                }
            }
        }

        var plotText: String? = imdbTeaser
        if (plotText.isNullOrBlank()) {
            content?.select("h3, h4, p")?.forEach { el ->
                val text = el.text()
                if (text.contains("SYNOPSIS", ignoreCase = true) || text.contains("STORYLINE", ignoreCase = true) || text.contains("PLOT", ignoreCase = true)) {
                    plotText = el.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }
                }
            }
        }

        val genreText = imdbMeta?.substringBeforeLast("|")?.substringAfter("|")?.trim()
            ?: infoMap["genres"]
            ?: doc.select("div.entry-content a[href*=/movies-by-genre/], div.entry-content a[href*=/category/]")
                .joinToString(", ") { it.text() }.ifBlank { null }

        return anime.apply {
            title = Parser.unescapeEntities(titleText, false)
            thumbnail_url = thumb
            genre = genreText
            status = SAnime.COMPLETED
            initialized = true
            description = buildString {
                if (!plotText.isNullOrBlank()) {
                    append(plotText)
                    append("\n\n")
                }
                val rating = imdbRating ?: infoMap["imdb rating"] ?: infoMap["imdb"]
                rating?.let { append("IMDb Rating: $it\n") }
                infoMap["movie name"]?.let { append("Movie Name: $it\n") }
                infoMap["release year"]?.let { append("Release Year: $it\n") }
                infoMap["format"]?.let { append("Format: $it\n") }
                infoMap["size"]?.let { append("Size: $it\n") }
                (infoMap["language"] ?: infoMap["original language"])?.let { append("Language: $it\n") }
                infoMap["quality"]?.let { append("Quality: $it\n") }
                genreText?.let { append("Genres: $it\n") }
                if (!imdbFooter.isNullOrBlank()) {
                    append(imdbFooter)
                    append("\n")
                } else {
                    infoMap["cast"]?.let { append("Cast: $it\n") }
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val pageText = content.text()

        val audioTag = when {
            pageText.contains("Dual Audio", ignoreCase = true) -> "Dual Audio"
            pageText.contains("Multi Audio", ignoreCase = true) -> "Multi Audio"
            pageText.contains("Hindi", ignoreCase = true) -> "Hindi"
            pageText.contains("English", ignoreCase = true) -> "English"
            else -> "Original"
        }

        val postHtml = content.html()

        // Check for TV / Web Series episode archives
        val seasonArchives = mutableMapOf<Int, MutableList<String>>()
        val blocks = postHtml.split(Regex("""(?=<h[1-6])"""))
        var currentSeason = 1

        for (block in blocks) {
            val hMatch = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", RegexOption.DOT_MATCHES_ALL).find(block)
            if (hMatch != null) {
                val hText = hMatch.groupValues[1].replace(Regex("""<[^>]+>"""), "")
                val sMatch = Regex("""(?:Season\s*(\d+)|\bS(\d+)\b)""", RegexOption.IGNORE_CASE).find(hText)
                if (sMatch != null) {
                    val sVal = sMatch.groupValues[1].ifEmpty { sMatch.groupValues[2] }
                    sVal.toIntOrNull()?.let { currentSeason = it }
                }
            }

            val aMatches = Regex("""<a[^>]+href=["'](https?://[^"']*/archives/\d+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(block)
            for (aMatch in aMatches) {
                val href = aMatch.groupValues[1]
                val text = aMatch.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                if (!text.contains("batch", ignoreCase = true) && !text.contains("zip", ignoreCase = true)) {
                    seasonArchives.getOrPut(currentSeason) { mutableListOf() }.add(href)
                }
            }
        }

        if (seasonArchives.isNotEmpty()) {
            val episodes = mutableListOf<SEpisode>()
            seasonArchives.keys.sorted().forEach { season ->
                val archives = seasonArchives[season] ?: return@forEach

                // Resilient: try archives in order until one successfully yields episodes
                var epNums = emptySet<Int>()
                for (arch in archives) {
                    val epResp = runCatching {
                        client.newCall(GET(arch, headersBuilder().set("Referer", "$baseUrl${anime.url}").build())).execute()
                    }.getOrNull()
                    val epDoc = epResp?.asJsoup()

                    val found = mutableSetOf<Int>()
                    epDoc?.select("a[href*='cloud.unblockedgames.world']")?.forEach { a ->
                        val btnText = a.text().trim()
                        val m = Regex("""(?:Episode|Ep|E)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(btnText)
                        m?.groupValues?.get(1)?.toIntOrNull()?.let { found.add(it) }
                    }
                    if (found.isNotEmpty()) {
                        epNums = found
                        break
                    }
                }

                epNums.sorted().forEach { epNum ->
                    val sPad = season.toString().padStart(2, '0')
                    val ePad = epNum.toString().padStart(2, '0')
                    episodes.add(
                        SEpisode.create().apply {
                            name = "S$sPad E$ePad"
                            setUrlWithoutDomain("${anime.url}#season=$season&ep=$epNum")
                            episode_number = if (season == 1) epNum.toFloat() else (season - 1) * 1000f + epNum.toFloat()
                            scanlator = audioTag
                        },
                    )
                }
            }

            if (episodes.isNotEmpty()) {
                return episodes.reversed()
            }
        }

        // Single Movie
        return listOf(
            SEpisode.create().apply {
                name = "Full Movie"
                setUrlWithoutDomain("${anime.url}#movie")
                episode_number = 1.0f
                scanlator = audioTag
            },
        )
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val rawUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val basePostUrl = rawUrl.substringBefore("#")
        val targetSeason = Regex("""#season=(\d+)""", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.get(1)?.toIntOrNull()
        val targetEp = Regex("""ep=(\d+)""", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.get(1)?.toIntOrNull()

        val resp = client.newCall(GET(basePostUrl, headersBuilder().set("Referer", "$baseUrl/").build())).execute()
        val doc = resp.asJsoup()
        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val postHtml = content.html()

        val hosterList = if (targetSeason != null && targetEp != null) {
            // TV Series
            val archiveItems = mutableListOf<Triple<String, String, String>>() // (archHref, qualityLabel, buttonText)
            val blocks = postHtml.split(Regex("""(?=<h[1-6])"""))
            var currentSeason = 1

            for (block in blocks) {
                var qualityLabel = "HD"
                val hMatch = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", RegexOption.DOT_MATCHES_ALL).find(block)
                if (hMatch != null) {
                    val hText = hMatch.groupValues[1].replace(Regex("""<[^>]+>"""), "")
                    val sMatch = Regex("""(?:Season\s*(\d+)|\bS(\d+)\b)""", RegexOption.IGNORE_CASE).find(hText)
                    if (sMatch != null) {
                        val sVal = sMatch.groupValues[1].ifEmpty { sMatch.groupValues[2] }
                        sVal.toIntOrNull()?.let { currentSeason = it }
                    }
                    val qMatch = Regex("""(480p|720p|1080p|2160p|4k)""", RegexOption.IGNORE_CASE).find(hText)?.value?.uppercase() ?: "HD"
                    val is10Bit = hText.contains("10bit", ignoreCase = true) || hText.contains("hevc", ignoreCase = true)
                    qualityLabel = if (is10Bit) "$qMatch 10-Bit" else qMatch
                }

                if (currentSeason == targetSeason) {
                    val aMatches = Regex("""<a[^>]+href=["'](https?://[^"']*/archives/\d+)["'][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(block)
                    for (aMatch in aMatches) {
                        val archHref = aMatch.groupValues[1]
                        val btnText = aMatch.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                        if (!btnText.contains("batch", ignoreCase = true) && !btnText.contains("zip", ignoreCase = true)) {
                            archiveItems.add(Triple(archHref, qualityLabel, btnText))
                        }
                    }
                }
            }

            archiveItems.parallelCatchingFlatMap { (archHref, quality, btnText) ->
                val archResp = client.newCall(GET(archHref, headersBuilder().set("Referer", basePostUrl).build())).execute()
                val archDoc = archResp.asJsoup()
                val subList = mutableListOf<Hoster>()

                archDoc.select("a[href*='cloud.unblockedgames.world']").forEach { ea ->
                    val eText = ea.text().trim()
                    val epNumMatch = Regex("""(?:Episode|Ep|E)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(eText)
                    if (epNumMatch != null && epNumMatch.groupValues[1].toIntOrNull() == targetEp) {
                        val sidUrl = ea.attr("abs:href")
                        if (sidUrl.isNotBlank()) {
                            val cleanBtn = btnText.replace(Regex("""[^\w\s()-]"""), "").trim()
                            val serverLabel = if (cleanBtn.isNotBlank() && !cleanBtn.contains("episode", ignoreCase = true)) cleanBtn else "DriveSeed"
                            val hName = "$quality - $serverLabel"
                            subList.add(Hoster(hosterName = hName, hosterUrl = "$sidUrl|$quality"))
                        }
                    }
                }
                subList
            }
        } else {
            // Movie
            val archiveItems = mutableListOf<Pair<String, String>>() // (archHref, qualityLabel)
            val blocks = postHtml.split(Regex("""(?=<h[1-6])"""))
            for (block in blocks) {
                var qualityLabel = "HD"
                val hMatch = Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""", RegexOption.DOT_MATCHES_ALL).find(block)
                if (hMatch != null) {
                    val hText = hMatch.groupValues[1].replace(Regex("""<[^>]+>"""), "")
                    val qMatch = Regex("""(480p|720p|1080p|2160p|4k)""", RegexOption.IGNORE_CASE).find(hText)?.value?.uppercase() ?: "HD"
                    val is10Bit = hText.contains("10bit", ignoreCase = true) || hText.contains("hevc", ignoreCase = true)
                    qualityLabel = if (is10Bit) "$qMatch 10-Bit" else qMatch
                }

                val aMatches = Regex("""<a[^>]+href=["'](https?://[^"']*/archives/\d+)["'][^>]*>""", RegexOption.DOT_MATCHES_ALL).findAll(block)
                for (aMatch in aMatches) {
                    val archHref = aMatch.groupValues[1]
                    archiveItems.add(Pair(archHref, qualityLabel))
                }
            }

            archiveItems.parallelCatchingFlatMap { (archHref, quality) ->
                val archResp = client.newCall(GET(archHref, headersBuilder().set("Referer", basePostUrl).build())).execute()
                val archDoc = archResp.asJsoup()
                val subList = mutableListOf<Hoster>()

                archDoc.select("a[href*='cloud.unblockedgames.world']").forEach { la ->
                    val lText = la.text().replace(Regex("""[^\w\s()-]"""), "").trim()
                    if (!lText.contains("comment", ignoreCase = true)) {
                        val sidUrl = la.attr("abs:href")
                        if (sidUrl.isNotBlank()) {
                            val serverLabel = if (lText.isNotBlank()) lText else "DriveSeed"
                            val hName = "$quality - $serverLabel"
                            subList.add(Hoster(hosterName = hName, hosterUrl = "$sidUrl|$quality"))
                        }
                    }
                }
                subList
            }
        }

        // Deduplicate by hosterUrl
        val seen = mutableSetOf<String>()
        val distinctHosters = hosterList.filter { seen.add(it.hosterUrl) }

        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return distinctHosters.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.hosterName.contains("Fast", ignoreCase = true) },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|", limit = 2)
        val sidUrl = parts[0]
        val qualityLabel = if (parts.size > 1) parts[1] else "HD"

        return resolveSidToVideos(sidUrl, qualityLabel)
    }

    private fun resolveSidToVideos(sidUrl: String, qualityLabel: String): List<Video> {
        val videoList = mutableListOf<Video>()
        runCatching {
            // Step 1: Request unblockedgames landing page
            val req1 = GET(sidUrl, headersBuilder().set("Referer", "https://links.modpro.blog/").build())
            val resp1 = client.newCall(req1).execute()
            val html1 = resp1.body.string()

            val val1 = Regex("""name=["']_wp_http["']\s+value=["']([^"']+)["']""").find(html1)?.groupValues?.get(1) ?: return emptyList()
            val act1 = Regex("""action=["']([^"']+)["']""").find(html1)?.groupValues?.get(1) ?: "https://cloud.unblockedgames.world/"

            // Step 2: Submit initial form
            val form2 = FormBody.Builder().add("_wp_http", val1).build()
            val req2 = Request.Builder()
                .url(act1)
                .post(form2)
                .headers(headersBuilder().set("Referer", sidUrl).set("Origin", "https://cloud.unblockedgames.world").build())
                .build()
            val resp2 = client.newCall(req2).execute()
            val html2 = resp2.body.string()

            val val2 = Regex("""name=["']_wp_http2["']\s+value=["']([^"']+)["']""").find(html2)?.groupValues?.get(1) ?: return emptyList()
            val tok2 = Regex("""name=["']token["']\s+value=["']([^"']+)["']""").find(html2)?.groupValues?.get(1) ?: return emptyList()
            val act2 = Regex("""<form[^>]+id=["']landing["'][^>]+action=["']([^"']+)["']""").find(html2)?.groupValues?.get(1) ?: return emptyList()

            // Step 3: Submit verification form
            val form3 = FormBody.Builder().add("_wp_http2", val2).add("token", tok2).build()
            val req3 = Request.Builder()
                .url(act2)
                .post(form3)
                .headers(headersBuilder().set("Referer", act1).set("Origin", "https://cloud.unblockedgames.world").build())
                .build()
            val resp3 = client.newCall(req3).execute()
            val html3 = resp3.body.string()

            // Step 4: Extract cookie and navigate to ?go=
            val cookieMatch = Regex("""s_343\s*\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']""").find(html3) ?: return emptyList()
            val cookieName = cookieMatch.groupValues[1]
            val cookieVal = cookieMatch.groupValues[2]

            val goUrl = "https://cloud.unblockedgames.world/?go=$cookieName"
            val req4 = GET(goUrl, headersBuilder().set("Referer", act2).set("Cookie", "$cookieName=$cookieVal").build())
            val resp4 = client.newCall(req4).execute()
            val html4 = resp4.body.string()

            // Step 5: Follow redirect to driveseed.org
            val dsUrl = Regex("""url=([^"'>\s]+)""").find(html4)?.groupValues?.get(1) ?: return emptyList()
            if (!dsUrl.startsWith("http://") && !dsUrl.startsWith("https://")) return emptyList()
            if (dsUrl.any { it.code < 0x20 || it.code > 0x7E }) return emptyList()

            val respDs = client.newCall(GET(dsUrl, headersBuilder().set("Referer", goUrl).build())).execute()
            val htmlDs = respDs.body.string()

            val filePath = Regex("""location\.replace\(["']([^"']+)["']\)""").find(htmlDs)?.groupValues?.get(1) ?: return emptyList()
            val fileUrl = if (filePath.startsWith("http")) filePath else "https://driveseed.org" + if (filePath.startsWith("/")) filePath else "/$filePath"

            // Step 6: Fetch driveseed file page
            val streamHeaders = headersBuilder().set("Referer", "https://driveseed.org/").build()
            val respFile = client.newCall(GET(fileUrl, streamHeaders)).execute()
            val docFile = respFile.asJsoup()

            // Step 7: Resolve Instant Download / Direct Stream links
            docFile.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                val text = a.text().trim()
                if (text.contains("Instant Download", ignoreCase = true) || href.contains("video-gen.xyz")) {
                    val label = if (text.contains("V2", ignoreCase = true)) "Direct Stream V2" else "Direct Stream"
                    runCatching {
                        val headResp = noRedirectClient.newCall(GET(href, streamHeaders)).execute()
                        val loc = headResp.header("Location")
                        val directUrl = when {
                            loc != null && loc.contains("url=") ->
                                URLDecoder.decode(loc.substringAfter("url=").substringBefore("&"), "UTF-8")
                            loc != null -> loc
                            headResp.isSuccessful -> href
                            else -> null
                        }
                        if (!directUrl.isNullOrBlank() && videoList.none { it.videoUrl == directUrl }) {
                            videoList.add(
                                Video(
                                    videoUrl = directUrl,
                                    videoTitle = "$qualityLabel - $label",
                                    headers = streamHeaders,
                                ),
                            )
                        }
                    }
                }
            }

            // Step 8: Resolve Cloudflare Worker mirror streams from /wfile/ (check type=1 and type=2)
            val wfileId = fileUrl.substringAfterLast("/")
            var workerIdx = 1
            for (type in listOf(1, 2)) {
                val wfileUrl = "https://driveseed.org/wfile/$wfileId?type=$type"
                runCatching {
                    val wResp = client.newCall(GET(wfileUrl, streamHeaders)).execute()
                    val wDoc = wResp.asJsoup()
                    wDoc.select("a[href*='workers.dev']").forEach { wLink ->
                        val wHref = wLink.attr("abs:href")
                        if (wHref.isNotBlank() && videoList.none { it.videoUrl == wHref }) {
                            videoList.add(
                                Video(
                                    videoUrl = wHref,
                                    videoTitle = "$qualityLabel - Worker Mirror ${workerIdx++}",
                                    headers = streamHeaders,
                                ),
                            )
                        }
                    }
                }
            }
        }

        return videoList.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) },
        )
    }

    // ============================ Recommendations ========================
    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("div.related-posts article.latestPost, article.latestPost, article.post-item").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@mapNotNull null
            val rawTitle = linkEl.attr("title").ifBlank { linkEl.text() }
            val cleanTitle = Parser.unescapeEntities(rawTitle, false).removePrefix("Download ").trim()
            val imgEl = el.selectFirst("img")
            val thumb = imgEl?.attr("abs:src")?.ifBlank { imgEl.attr("src") }

            SAnime.create().apply {
                title = cleanTitle
                setUrlWithoutDomain(href)
                thumbnail_url = thumb
            }
        }
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
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080p", "720p", "480p"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Direct Stream", "Worker Mirror"),
            entryValues = listOf("Direct Stream", "Worker Mirror"),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://moviesmod.zone"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Direct Stream"
    }
}

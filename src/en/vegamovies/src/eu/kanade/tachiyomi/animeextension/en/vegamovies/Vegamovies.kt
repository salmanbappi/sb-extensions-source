package eu.kanade.tachiyomi.animeextension.en.vegamovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

class Vegamovies : Source() {

    override val name = "Vegamovies"

    override val baseUrl = "https://vegamoviess.you"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    private fun parseAnimeListPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article.post-item, div.post-item").mapNotNull { element ->
            val linkEl = element.selectFirst("h3.entry-title a, a.blog-img, a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href == "$baseUrl/" || href.contains("#")) return@mapNotNull null

            val titleText = linkEl.attr("title").ifEmpty { linkEl.text() }
            val imgEl = element.selectFirst("img.blog-picture, img")
            val imgUrl = imgEl?.attr("abs:src")?.ifEmpty { imgEl.attr("src") }

            SAnime.create().apply {
                title = titleText.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = imgUrl
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.select(".wp-pagenavi a[href*=/page/${page + 1}/]").isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/page/$page/?s=$encodedQuery"
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimeListPage(response, page)
        }

        var categoryUrl: String? = null
        for (filter in filters) {
            when (filter) {
                is CategoryFilter -> {
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
        AnimeFilter.Header("Text search ignores filters"),
        CategoryFilter(),
    )

    // ============================== Filters ===============================
    private open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    private class CategoryFilter :
        UriPartFilter(
            "Category / Type",
            arrayOf(
                Pair("All", ""),
                Pair("Movies", "category/movies"),
                Pair("TV / Web Series", "category/web-series"),
                Pair("Bollywood Movies", "category/bollywood-movies"),
                Pair("Hollywood Movies", "category/hollywood-movies"),
                Pair("South Indian Movies", "category/south-indian-dubbed-movies"),
                Pair("Hindi Dubbed Movies", "category/hindi-dubbed-movies"),
                Pair("Korean Series", "category/korean-series"),
                Pair("Anime", "category/anime"),
                Pair("Netflix", "category/netflix"),
                Pair("Amazon Prime Video", "category/amazon-prime"),
                Pair("Disney+ Hotstar", "category/disney-plus-hotstar"),
                Pair("Dual Audio", "category/dual-audio"),
                Pair("4K Ultra HD", "category/4k-ultrahd"),
                Pair("480p Movies", "category/480p-movies"),
                Pair("720p Movies", "category/720p-movies"),
                Pair("1080p Movies", "category/1080p-movies"),
            ),
        )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val titleText = doc.selectFirst("h1.entry-title, h3.entry-title")?.text()?.trim() ?: anime.title
        val thumbnail = doc.selectFirst(
            "div.entry-content img[src*=/covers/], div.entry-content img[src*=/uploads/], img.blog-picture",
        )?.attr("abs:src") ?: anime.thumbnail_url

        val content = doc.selectFirst("div.entry-content")

        // Scrape <strong>Label:</strong> value pairs from the Movie Info block
        val infoMap = mutableMapOf<String, String>()
        content?.select("p")?.forEach { p ->
            p.select("strong").forEach { strong ->
                val label = strong.text().trimEnd(':').trim().lowercase()
                val value = strong.nextSibling()?.toString()
                    ?.removePrefix(":")?.removePrefix(" -")?.trim()
                if (label.isNotBlank() && !value.isNullOrBlank()) {
                    infoMap.putIfAbsent(label, value)
                }
            }
            val text = p.text()
            mapOf(
                "imdb rating" to "imdb rating",
                "movie name" to "movie name",
                "language" to "language",
                "original language" to "original language",
                "release year" to "release year",
                "format" to "format",
                "size" to "size",
                "runtime" to "runtime",
                "quality" to "quality",
                "genres" to "genres",
                "cast" to "cast",
            ).forEach { (key, mapKey) ->
                if (text.contains(key, ignoreCase = true) && !infoMap.containsKey(mapKey)) {
                    val value = text.substringAfter(":", "").trim().trimStart('-', ' ')
                    if (value.isNotBlank()) infoMap[mapKey] = value
                }
            }
        }

        // Plot — the h3 "SYNOPSIS/PLOT" heading followed by a <p>
        var plotText: String? = null
        content?.select("h3")?.forEach { h3 ->
            if (h3.text().contains("SYNOPSIS", ignoreCase = true) || h3.text().contains("PLOT", ignoreCase = true)) {
                plotText = h3.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }
            }
        }

        val genreText = infoMap["genres"]
            ?: doc.select("div.entry-content a[href*=/category/], div.entry-content a[href*=/genre/]")
                .joinToString(", ") { it.text() }.ifBlank { null }

        return anime.apply {
            title = titleText
            thumbnail_url = thumbnail
            genre = genreText
            status = SAnime.COMPLETED
            initialized = true
            description = buildString {
                if (!plotText.isNullOrBlank()) {
                    append(plotText)
                    append("\n\n")
                }
                (infoMap["imdb rating"] ?: infoMap["imdb"])?.let { append("IMDb Rating: $it\n") }
                infoMap["movie name"]?.let { append("Movie Name: $it\n") }
                infoMap["release year"]?.let { append("Release Year: $it\n") }
                infoMap["format"]?.let { append("Format: $it\n") }
                infoMap["size"]?.let { append("Size: $it\n") }
                infoMap["runtime"]?.let { append("Runtime: ${it.removeSuffix("minutes").trim()} min\n") }
                (infoMap["language"] ?: infoMap["original language"])?.let { append("Language: $it\n") }
                infoMap["quality"]?.let { append("Quality: $it\n") }
                genreText?.let { append("Genres: $it\n") }
                infoMap["cast"]?.let { append("Cast: $it\n") }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    // Parse multi-season & multi-episode TV series & movies from post DOM HTML.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val pageText = doc.selectFirst("div.entry-content")?.text() ?: ""
        val audioTag = when {
            pageText.contains("Dual Audio", ignoreCase = true) -> "Dual Audio"
            pageText.contains("Multi Audio", ignoreCase = true) -> "Multi Audio"
            pageText.contains("Hindi", ignoreCase = true) -> "Hindi"
            else -> "Original"
        }

        val content = doc.selectFirst("div.entry-content") ?: return emptyList()
        val postHtml = content.html()

        val seasonRegex = Regex("""Season\s*(\d+)|\bS(\d+)\b""", RegexOption.IGNORE_CASE)
        val sMatch = seasonRegex.find(doc.select("h1.entry-title").text()) ?: seasonRegex.find(postHtml)
        val globalSeason: Int = sMatch?.let { (it.groupValues[1].ifEmpty { it.groupValues[2] }).toIntOrNull() } ?: 1

        val landingLinks = mutableListOf<Pair<String, String>>()
        content.select("a[href]").forEach { a ->
            val href = a.attr("abs:href")
            val text = a.text().trim()
            if (href.startsWith("http") && (href.contains("nexdrive") || href.contains("vcloud") || href.contains("vgmlinks") || href.contains("fast-dl"))) {
                if (landingLinks.none { it.first == href }) {
                    landingLinks.add(Pair(href, text))
                }
            }
        }

        val detectedEpisodes = mutableSetOf<Int>()

        landingLinks.forEach { (landingUrl, _) ->
            runCatching {
                val resp = client.newCall(GET(landingUrl, headersBuilder().set("Referer", "$baseUrl/").build())).execute()
                val lHtml = resp.body.string()

                val epBlocks = lHtml.split(Regex("""(?=<div[^>]+class=["']ep-title["']|<h[1-6][^>]*>|<p[^>]*>|-:\s*Episodes?:)""", RegexOption.IGNORE_CASE))
                epBlocks.forEach { block ->
                    val epNumMatch = Regex("""(?:Episodes?|Ep)\s*[-:]*\s*(\d+)""", RegexOption.IGNORE_CASE).find(block)
                    if (epNumMatch != null) {
                        val epNum = epNumMatch.groupValues[1].toIntOrNull()
                        if (epNum != null) {
                            detectedEpisodes.add(epNum)
                        }
                    }
                }
            }
        }

        if (detectedEpisodes.isNotEmpty()) {
            val episodes = detectedEpisodes.sorted().map { epNum ->
                val sStr = "S${globalSeason.toString().padStart(2, '0')}"
                val epName = "$sStr Episode ${epNum.toString().padStart(2, '0')}"
                val calculatedNum = (globalSeason * 1000f) + epNum
                SEpisode.create().apply {
                    name = epName
                    setUrlWithoutDomain("${anime.url}#ep=$epNum&season=$globalSeason")
                    episode_number = calculatedNum
                    scanlator = audioTag
                }
            }
            return episodes.reversed()
        }

        // Fallback: Check post directly for episode buttons
        val epRegex = Regex("""(?:Episode|Ep|\bE)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        val postEpisodes = mutableSetOf<Int>()
        content.select("a[href]").forEach { a ->
            val text = a.text().trim()
            val epMatch = epRegex.find(text)
            if (epMatch != null) {
                epMatch.groupValues[1].toIntOrNull()?.let { postEpisodes.add(it) }
            }
        }

        if (postEpisodes.isNotEmpty()) {
            val episodes = postEpisodes.sorted().map { epNum ->
                val sStr = "S${globalSeason.toString().padStart(2, '0')}"
                val epName = "$sStr Episode ${epNum.toString().padStart(2, '0')}"
                val calculatedNum = (globalSeason * 1000f) + epNum
                SEpisode.create().apply {
                    name = epName
                    setUrlWithoutDomain("${anime.url}#ep=$epNum&season=$globalSeason")
                    episode_number = calculatedNum
                    scanlator = audioTag
                }
            }
            return episodes.reversed()
        }

        // Single Movie
        return listOf(
            SEpisode.create().apply {
                name = "Full Movie"
                setUrlWithoutDomain(anime.url)
                episode_number = 1f
                scanlator = audioTag
            },
        )
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val rawUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val basePostUrl = rawUrl.substringBefore("#")
        val targetEp = Regex("""#ep=(\d+)""", RegexOption.IGNORE_CASE).find(rawUrl)?.groupValues?.get(1)?.toIntOrNull()

        val hosterMap = mutableMapOf<String, MutableList<Pair<String, String>>>()

        runCatching {
            val resp = client.newCall(GET(basePostUrl, headersBuilder().set("Referer", "$baseUrl/").build())).execute()
            val doc = resp.asJsoup()

            val landingLinks = mutableListOf<Pair<String, String>>()
            doc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                val btnText = a.text().trim()
                if (href.startsWith("http") && (href.contains("nexdrive") || href.contains("vcloud") || href.contains("vgmlinks") || href.contains("fast-dl"))) {
                    if (landingLinks.none { it.first == href }) {
                        landingLinks.add(Pair(href, btnText))
                    }
                }
            }

            landingLinks.forEach { (landingUrl, btnText) ->
                runCatching {
                    val nexResp = client.newCall(GET(landingUrl, headersBuilder().set("Referer", "$baseUrl/").build())).execute()
                    val nexDoc = nexResp.asJsoup()
                    val lHtml = nexDoc.html()

                    val parseLinks = { bDoc: org.jsoup.nodes.Document ->
                        bDoc.select("a[href]").forEach { na ->
                            val nHref = na.attr("abs:href")
                            val nText = na.text().trim()
                            val serverName = getServerName(nHref)
                            if (serverName != null && nHref.startsWith("http")) {
                                val qualityLabel = extractQualityLabel(nText, btnText)
                                val list = hosterMap.getOrPut(serverName) { mutableListOf() }
                                if (list.none { it.second == nHref }) {
                                    list.add(Pair(qualityLabel, nHref))
                                }
                            }
                        }
                    }

                    if (targetEp != null) {
                        val epBlocks = lHtml.split(Regex("""(?=<div[^>]+class=["']ep-title["']|<h[1-6][^>]*>|<p[^>]*>|-:\s*Episodes?:)""", RegexOption.IGNORE_CASE))
                        epBlocks.forEach { block ->
                            val epMatch = Regex("""(?:Episodes?|Ep)\s*[-:]*\s*(\d+)""", RegexOption.IGNORE_CASE).find(block)
                            if (epMatch != null && epMatch.groupValues[1].toIntOrNull() == targetEp) {
                                val bDoc = Jsoup.parse(block, landingUrl)
                                parseLinks(bDoc)
                            }
                        }
                    } else {
                        parseLinks(nexDoc)
                    }
                }
            }
        }

        if (hosterMap.isEmpty()) {
            return listOf(Hoster(hosterName = "Direct Stream", hosterUrl = "HD|$rawUrl"))
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosterMap.map { (serverName, sources) ->
            Hoster(
                hosterName = serverName,
                hosterUrl = sources.joinToString(";;") { "${it.first}|${it.second}" },
            )
        }.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    private fun getServerName(href: String): String? = when {
        href.contains("fast-dl", ignoreCase = true) -> "Fast Download"
        href.contains("vcloud", ignoreCase = true) -> "V-Cloud"
        href.contains("dood", ignoreCase = true) -> "DoodStream"
        href.contains("filemoon", ignoreCase = true) -> "Filemoon"
        href.contains("streamtape", ignoreCase = true) -> "StreamTape"
        href.contains("streamwish", ignoreCase = true) || href.contains("awish", ignoreCase = true) -> "StreamWish"
        else -> null
    }

    private fun extractQualityLabel(btnText: String, landingText: String): String {
        val combined = "$btnText $landingText"
        val qMatch = Regex("""(480p|720p|1080p|2160p|4k|HEVC)""", RegexOption.IGNORE_CASE).find(combined)?.value?.uppercase()
        val sizeMatch = Regex("""\[?([\d.]+\s*(?:MB|GB))\]?""", RegexOption.IGNORE_CASE).find(combined)?.groupValues?.get(1)

        val qual = if (!qMatch.isNullOrBlank()) {
            qMatch
        } else if (!sizeMatch.isNullOrBlank()) {
            val sizeUpper = sizeMatch.uppercase()
            if (sizeUpper.contains("GB")) {
                val sizeNum = Regex("""[\d.]+""").find(sizeMatch)?.value?.toDoubleOrNull() ?: 0.0
                if (sizeNum >= 3.0) "1080P" else "720P"
            } else if (sizeUpper.contains("MB")) {
                val sizeNum = Regex("""[\d.]+""").find(sizeMatch)?.value?.toDoubleOrNull() ?: 0.0
                if (sizeNum >= 700.0) "720P" else "480P"
            } else {
                "HD"
            }
        } else {
            "HD"
        }

        return if (!sizeMatch.isNullOrBlank()) "$qual ($sizeMatch)" else qual
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawHosterUrl = hoster.hosterUrl
        val refHeaders = headersBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$baseUrl/")
            .build()

        val videoList = mutableListOf<Video>()

        val sources = rawHosterUrl.split(";;")
        sources.forEach { sourceStr ->
            val parts = sourceStr.split("|", limit = 2)
            if (parts.size == 2) {
                val qualityLabel = parts[0]
                val url = parts[1]

                runCatching {
                    when {
                        url.contains("dood", ignoreCase = true) ->
                            doodExtractor.videosFromUrl(url).map { v ->
                                Video(videoUrl = v.url, videoTitle = "$qualityLabel - ${v.quality}", headers = v.headers, subtitleTracks = v.subtitleTracks)
                            }.let { videoList.addAll(it) }

                        url.contains("filemoon", ignoreCase = true) ->
                            filemoonExtractor.videosFromUrl(url, prefix = "$qualityLabel - ", headers = refHeaders).let { videoList.addAll(it) }

                        url.contains("streamtape", ignoreCase = true) ->
                            streamtapeExtractor.videoFromUrl(url, quality = "$qualityLabel - StreamTape")?.let { videoList.add(it) }

                        url.contains("streamwish", ignoreCase = true) || url.contains("awish", ignoreCase = true) ->
                            streamwishExtractor.videosFromUrl(url, prefix = "$qualityLabel - ").let { videoList.addAll(it) }

                        else -> {
                            val postReq = Request.Builder()
                                .url(url)
                                .post(FormBody.Builder().build())
                                .headers(refHeaders)
                                .build()
                            val postResp = runCatching { client.newCall(postReq).execute() }.getOrNull()
                            val doc = postResp?.asJsoup() ?: client.newCall(GET(url, refHeaders)).execute().asJsoup()

                            val vdLink = doc.selectFirst("a#vd, a[cf-cache]")?.attr("abs:href")
                            val finalStreamUrl = if (!vdLink.isNullOrBlank()) vdLink else url

                            if (finalStreamUrl.contains(".m3u8")) {
                                videoList.addAll(
                                    playlistUtils.extractFromHls(
                                        finalStreamUrl,
                                        referer = url,
                                        masterHeaders = refHeaders,
                                        videoHeaders = refHeaders,
                                        videoNameGen = { q -> "$qualityLabel - $q" },
                                    ),
                                )
                            } else {
                                videoList.add(
                                    Video(
                                        videoUrl = finalStreamUrl,
                                        videoTitle = qualityLabel,
                                        headers = refHeaders,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return videoList.sortedByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
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
        return doc.select("article.post-item, div.recent-posts li").mapNotNull { element ->
            val linkEl = element.selectFirst("a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            if (href.isBlank() || href.contains("#")) return@mapNotNull null
            SAnime.create().apply {
                title = linkEl.attr("title").ifEmpty { linkEl.text() }.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
            entries = listOf("Watch Online (Player)", "Fast Download", "V-Cloud", "Filemoon", "StreamWish", "DoodStream", "StreamTape"),
            entryValues = listOf("Watch Online (Player)", "Fast Download", "V-Cloud", "Filemoon", "StreamWish", "DoodStream", "StreamTape"),
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Watch Online (Player)"
    }
}

package eu.kanade.tachiyomi.animeextension.en.vegamovies

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
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

    private val universalExtractor by lazy { UniversalExtractor(client) }
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
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/page/$page/?s=$encodedQuery"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

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
    // Parse episodes directly from the post DOM HTML for 100% stability and zero missing episodes on reload.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val titleText = doc.selectFirst("h1.entry-title, h3.entry-title")?.text() ?: anime.title
        val seasonMatch = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE).find(titleText)
        val seasonPrefix = if (seasonMatch != null) {
            val sNum = seasonMatch.groupValues[1].padStart(2, '0')
            "S$sNum "
        } else {
            ""
        }

        val pageText = doc.selectFirst("div.entry-content")?.text() ?: ""
        val audioTag = when {
            pageText.contains("Dual Audio", ignoreCase = true) -> "Dual Audio"
            pageText.contains("Multi Audio", ignoreCase = true) -> "Multi Audio"
            pageText.contains("Hindi", ignoreCase = true) -> "Hindi"
            else -> "Original"
        }

        val content = doc.selectFirst("div.entry-content") ?: return emptyList()

        val episodes = mutableListOf<SEpisode>()
        var currentQuality = ""

        val qRegex = Regex("""(480p|720p|1080p|2160p|4k|HEVC)""", RegexOption.IGNORE_CASE)
        val epRegex = Regex("""(?:Episode|Ep|\bE)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
        val sizeRegex = Regex("""\[?([\d.]+\s*(?:MB|GB))\]?""", RegexOption.IGNORE_CASE)
        val skipHosts = setOf("telegram", "$baseUrl/", "#")

        val parts = content.html().split(Regex("""(?=<h[1-6][^>]*>)""", RegexOption.IGNORE_CASE))

        parts.forEach { part ->
            val pDoc = Jsoup.parse(part, "$baseUrl${anime.url}")
            val headingText = pDoc.select("h1, h2, h3, h4, h5, h6").text().trim()
            val qMatch = qRegex.find(headingText)
            if (qMatch != null) {
                currentQuality = qMatch.value.uppercase()
            }

            pDoc.select("a[href]").forEach { a ->
                val href = a.attr("abs:href")
                if (href.isBlank() || skipHosts.any { href.contains(it) }) return@forEach
                if (episodes.any { it.url == href }) return@forEach

                if (href.contains("nexdrive") || href.contains("vcloud") || href.contains("fast-dl") || href.contains("vgmlinks")) {
                    val btnText = a.text().trim()
                    val sizeMatch = sizeRegex.find(btnText)
                    val sizeStr = if (sizeMatch != null) " [${sizeMatch.groupValues[1]}]" else ""

                    val epMatch = epRegex.find(btnText) ?: epRegex.find(headingText)
                    val epName = if (epMatch != null) {
                        val epNum = epMatch.groupValues[1]
                        "${seasonPrefix}Episode $epNum$sizeStr"
                    } else if (currentQuality.isNotBlank()) {
                        "$currentQuality$sizeStr"
                    } else {
                        btnText.ifEmpty { "Download Link ${episodes.size + 1}" }
                    }

                    episodes.add(
                        SEpisode.create().apply {
                            name = epName
                            setUrlWithoutDomain(href)
                            episode_number = (episodes.size + 1).toFloat()
                            scanlator = audioTag
                        },
                    )
                }
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                SEpisode.create().apply {
                    name = "Full Movie / Stream"
                    setUrlWithoutDomain(anime.url)
                    episode_number = 1f
                    scanlator = audioTag
                },
            )
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val hosters = mutableListOf<Hoster>()

        runCatching {
            val resp = client.newCall(
                GET(episodeUrl, headersBuilder().set("Referer", "$baseUrl/").build()),
            ).execute()
            val doc = resp.asJsoup()

            // Split nexdrive page by episode headings if present
            val nexHtml = doc.html()
            val epRegex = Regex("""Episodes?:\s*(\d+)""", RegexOption.IGNORE_CASE)
            val parts = nexHtml.split(Regex("""(?=<[^>]+class=["']ep-title[^"']*["']|<h[1-6][^>]*>)""", RegexOption.IGNORE_CASE))

            parts.forEach { part ->
                val epMatch = epRegex.find(part)
                val epPrefix = if (epMatch != null) "Ep ${epMatch.groupValues[1]} - " else ""
                val pDoc = Jsoup.parse(part, episodeUrl)

                pDoc.select("a[href]").forEach { a ->
                    val href = a.attr("abs:href")
                    val text = a.text().trim()
                    val cleanName = getCleanHosterName(href, text, "")
                    if (cleanName != null && href.isNotBlank() && hosters.none { it.hosterUrl == href }) {
                        hosters.add(Hoster(hosterName = "$epPrefix$cleanName", hosterUrl = href))
                    }
                }
            }
        }

        if (hosters.isEmpty()) {
            hosters.add(Hoster("Direct Stream", episodeUrl))
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    private fun getCleanHosterName(href: String, btnText: String, quality: String): String? {
        val serverName = when {
            href.contains("fast-dl", ignoreCase = true) -> "Fast Download"
            href.contains("vcloud", ignoreCase = true) -> "V-Cloud"
            href.contains("filepress", ignoreCase = true) -> "Filepress"
            href.contains("gdtot", ignoreCase = true) -> "GDToT"
            href.contains("dropgalaxy", ignoreCase = true) -> "DropGalaxy"
            href.contains("dood", ignoreCase = true) -> "DoodStream"
            href.contains("filemoon", ignoreCase = true) -> "Filemoon"
            href.contains("streamtape", ignoreCase = true) -> "StreamTape"
            href.contains("streamwish", ignoreCase = true) || href.contains("awish", ignoreCase = true) -> "StreamWish"
            href.contains("vgmlinks", ignoreCase = true) -> "VGMLinks"
            else -> return null
        }

        val sizeMatch = Regex("""\[?([\d.]+\s*(?:MB|GB))\]?""", RegexOption.IGNORE_CASE).find(btnText)?.groupValues?.get(1)

        return buildString {
            append(serverName)
            if (quality.isNotBlank()) append(" [$quality]")
            if (!sizeMatch.isNullOrBlank()) append(" ($sizeMatch)")
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val refHeaders = headersBuilder().set("Referer", url).build()
        val videoList = mutableListOf<Video>()

        runCatching {
            when {
                url.contains("dood", ignoreCase = true) ->
                    videoList.addAll(doodExtractor.videosFromUrl(url))

                url.contains("filemoon", ignoreCase = true) ->
                    videoList.addAll(filemoonExtractor.videosFromUrl(url, prefix = "${hoster.hosterName} - ", headers = refHeaders))

                url.contains("streamtape", ignoreCase = true) ->
                    streamtapeExtractor.videoFromUrl(url, quality = "${hoster.hosterName} - StreamTape")?.let { videoList.add(it) }

                url.contains("streamwish", ignoreCase = true) || url.contains("awish", ignoreCase = true) ->
                    videoList.addAll(streamwishExtractor.videosFromUrl(url, prefix = "${hoster.hosterName} - "))

                else -> {
                    // Issue POST request to fast-dl/vcloud hosters to resolve direct video stream URL
                    val postReq = Request.Builder()
                        .url(url)
                        .post(FormBody.Builder().build())
                        .headers(refHeaders)
                        .build()
                    val postResp = runCatching { client.newCall(postReq).execute() }.getOrNull()
                    val doc = postResp?.asJsoup() ?: client.newCall(GET(url, refHeaders)).execute().asJsoup()

                    val vdLink = doc.selectFirst("a#vd, a[cf-cache]")?.attr("abs:href")
                    if (!vdLink.isNullOrBlank()) {
                        videoList.add(
                            Video(
                                videoUrl = vdLink,
                                videoTitle = "${hoster.hosterName} - Direct Stream",
                                headers = refHeaders,
                            ),
                        )
                    }

                    doc.select("a[href]").forEach { a ->
                        val href = a.attr("abs:href")
                        if (href.startsWith("http") && !href.contains("telegram") && !href.contains("#") &&
                            href != vdLink && (href.contains("googleusercontent") || href.contains(".mp4") || href.contains(".mkv") || href.contains(".m3u8"))
                        ) {
                            videoList.add(
                                Video(
                                    videoUrl = href,
                                    videoTitle = hoster.hosterName,
                                    headers = refHeaders,
                                ),
                            )
                        }
                    }

                    if (videoList.isEmpty()) {
                        val extracted = universalExtractor.videosFromUrl(url, refHeaders)
                        if (extracted.isNotEmpty()) {
                            videoList.addAll(extracted)
                        }
                    }
                }
            }
        }

        return videoList
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
            entries = listOf("Fast Download", "V-Cloud", "Filemoon", "StreamWish", "DoodStream", "StreamTape"),
            entryValues = listOf("Fast Download", "V-Cloud", "Filemoon", "StreamWish", "DoodStream", "StreamTape"),
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Fast Download"
    }
}

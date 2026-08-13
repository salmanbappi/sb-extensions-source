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
import keiyoushi.utils.parallelCatchingMapNotNull
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Collections

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
    private data class ParsedHoster(val name: String, val url: String)

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

        // Gather all download links on the page (nexdrive, vcloud, btn links)
        val downloadAnchors = content.select("a[href]")
            .mapNotNull { a ->
                val href = a.attr("abs:href")
                if (href.isNotBlank() && (href.contains("nexdrive") || href.contains("vcloud") || href.contains("fast-dl") || href.contains("vgmlinks")) &&
                    !href.contains("telegram") && href != "$baseUrl/" && !href.contains("#")
                ) {
                    href
                } else {
                    null
                }
            }.distinct()

        val episodeHosterMap = Collections.synchronizedMap(mutableMapOf<Int, MutableList<ParsedHoster>>())
        val movieHosterList = Collections.synchronizedList(mutableListOf<ParsedHoster>())

        // Fetch redirect/nexdrive pages in parallel
        downloadAnchors.parallelCatchingMapNotNull { linkUrl ->
            runCatching {
                val req = GET(linkUrl, headersBuilder().set("Referer", "$baseUrl/").build())
                val resp = client.newCall(req).execute()
                val nexHtml = resp.body.string()
                val nexDoc = Jsoup.parse(nexHtml, linkUrl)

                // Detect quality label from page text
                val qualityLabel = Regex("""(480p|720p|1080p|2160p|4k)""", RegexOption.IGNORE_CASE)
                    .find(nexDoc.text())?.value?.uppercase() ?: ""

                // Check for episode sections
                val epRegex = Regex("""Episodes?:\s*(\d+)""", RegexOption.IGNORE_CASE)
                val parts = nexHtml.split(Regex("""(?=<[^>]+class=["']ep-title[^"']*["'][^>]*>|<h[1-6][^>]*>)""", RegexOption.IGNORE_CASE))

                var hasEpInPage = false

                parts.forEach { part ->
                    val epMatch = epRegex.find(part)
                    if (epMatch != null) {
                        hasEpInPage = true
                        val epNum = epMatch.groupValues[1].toIntOrNull() ?: 1
                        val pDoc = Jsoup.parse(part, linkUrl)
                        pDoc.select("a[href]").forEach { a ->
                            val href = a.attr("abs:href")
                            val text = a.text().trim()
                            val hosterName = getCleanHosterName(href, text, qualityLabel)
                            if (hosterName != null) {
                                episodeHosterMap.getOrPut(epNum) { mutableListOf() }
                                    .add(ParsedHoster(hosterName, href))
                            }
                        }
                    }
                }

                if (!hasEpInPage) {
                    // Movie page or single file page
                    nexDoc.select("a[href]").forEach { a ->
                        val href = a.attr("abs:href")
                        val text = a.text().trim()
                        val hosterName = getCleanHosterName(href, text, qualityLabel)
                        if (hosterName != null) {
                            movieHosterList.add(ParsedHoster(hosterName, href))
                        }
                    }
                }
            }.getOrNull()
        }

        val episodes = mutableListOf<SEpisode>()

        if (episodeHosterMap.isNotEmpty()) {
            // TV Series with individual episodes
            episodeHosterMap.keys.sorted().forEach { epNum ->
                val hosters = episodeHosterMap[epNum] ?: emptyList()
                val payload = hosters.joinToString(";;") { "${it.name}|${it.url}" }
                episodes.add(
                    SEpisode.create().apply {
                        name = "${seasonPrefix}Episode $epNum"
                        url = "EP_PAYLOAD:$payload"
                        episode_number = epNum.toFloat()
                        scanlator = audioTag
                    },
                )
            }
        } else {
            // Movie
            val payload = movieHosterList.joinToString(";;") { "${it.name}|${it.url}" }
            episodes.add(
                SEpisode.create().apply {
                    name = "Full Movie"
                    url = if (payload.isNotBlank()) "EP_PAYLOAD:$payload" else anime.url
                    episode_number = 1f
                    scanlator = audioTag
                },
            )
        }

        return episodes.reversed()
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

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = mutableListOf<Hoster>()

        if (episode.url.contains("EP_PAYLOAD:")) {
            val raw = episode.url.substringAfter("EP_PAYLOAD:")
            raw.split(";;").forEach { item ->
                val parts = item.split("|")
                if (parts.size >= 2) {
                    val name = parts[0]
                    val url = parts.subList(1, parts.size).joinToString("|")
                    if (name.isNotBlank() && url.isNotBlank()) {
                        hosters.add(Hoster(name, url))
                    }
                }
            }
        } else {
            val episodeUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
            runCatching {
                val resp = client.newCall(
                    GET(episodeUrl, headersBuilder().set("Referer", "$baseUrl/").build()),
                ).execute()
                val doc = resp.asJsoup()

                doc.select("a[href]").forEach { a ->
                    val href = a.attr("abs:href")
                    val name = getCleanHosterName(href, a.text(), "")
                    if (name != null && hosters.none { it.hosterUrl == href }) {
                        hosters.add(Hoster(name, href))
                    }
                }
            }
            if (hosters.isEmpty()) {
                hosters.add(Hoster("Direct", episodeUrl))
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
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
                    val resp = client.newCall(GET(url, refHeaders)).execute()
                    val doc = resp.asJsoup()
                    doc.select("a[href]").forEach { a ->
                        val href = a.attr("abs:href")
                        if (href.startsWith("http") && !href.contains("telegram") && !href.contains("#") &&
                            (
                                href.contains(".m3u8") || href.contains(".mp4") || href.contains(".mkv") ||
                                    href.contains("googleusercontent") || href.contains("drive.google") ||
                                    href.contains("tinyurl") || href.contains("fast-dl") || href.contains("vcloud")
                                )
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
                        } else {
                            videoList.add(
                                Video(
                                    videoUrl = url,
                                    videoTitle = hoster.hosterName,
                                    headers = refHeaders,
                                ),
                            )
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

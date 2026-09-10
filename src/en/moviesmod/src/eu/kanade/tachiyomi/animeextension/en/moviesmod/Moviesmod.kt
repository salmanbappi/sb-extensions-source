package eu.kanade.tachiyomi.animeextension.en.moviesmod

import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class Moviesmod :
    Source(),
    ConfigurableAnimeSource {

    override val name = "MoviesMod"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(network.client))
            .addInterceptor(RateLimitInterceptor())
            .rateLimit(permits = 3, period = 1.seconds)
            .build()
    }

    private val redirectorBypasser by lazy { RedirectorBypasser(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val archiveCache = ConcurrentHashMap<String, Document>()

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
        val postUrl = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        val response = client.newCall(GET(postUrl, headers)).execute()
        val doc = response.asJsoup()
        val content = doc.selectFirst("div.entry-content")

        val imdbWp = content?.selectFirst("div.imdbwp")
        val imdbTitle = imdbWp?.selectFirst("span.imdbwp__title")?.text()?.trim()
        val imdbRating = imdbWp?.selectFirst("span.imdbwp__rating")?.text()?.trim()
        val imdbThumb = imdbWp?.selectFirst("img.imdbwp__img")?.attr("abs:src")
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
        val postUrl = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        val response = client.newCall(GET(postUrl, headers)).execute()
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

        val buttonRows = content.select("p:has(a.maxbutton-episode-links, a.maxbutton-download-links)")
            .ifEmpty { content.select("p:has(a[class*=maxbutton])") }
            .ifEmpty { content.select("p:has(a[href*='/archives/'])") }

        if (buttonRows.isEmpty()) {
            return emptyList()
        }

        val isSerie = buttonRows.any { row ->
            row.select("a").any {
                it.hasClass("maxbutton-episode-links") || it.text().contains("Episode", ignoreCase = true)
            }
        }

        val seasonRegex = Regex("""(?:Season\s*(\d+)|\bS(\d{1,2})\b)""", RegexOption.IGNORE_CASE)
        val qualityRegex = Regex("""\d{3,4}p(?:\s+\w+)?""", RegexOption.IGNORE_CASE)

        if (isSerie) {
            val seasonArchives = mutableMapOf<Int, MutableList<ArchiveLink>>()

            for (row in buttonRows) {
                val aEl = row.selectFirst("a[href]") ?: continue
                val btnText = aEl.text().trim()
                if (btnText.contains("batch", ignoreCase = true) || btnText.contains("zip", ignoreCase = true)) {
                    continue
                }

                val rawHref = aEl.attr("abs:href").ifBlank { aEl.attr("href") }
                if (rawHref.isBlank()) continue
                val archUrl = extractChildUrl(rawHref)

                val prevSiblings = row.previousElementSiblings()
                var seasonNum = 1
                var qualityLabel = "HD"

                // Search backwards for the nearest season title
                for (sibling in prevSiblings.asReversed()) {
                    val sText = sibling.text().trim()
                    if (sText.isNotBlank()) {
                        val sMatch = seasonRegex.find(sText)
                        if (sMatch != null) {
                            val numStr = sMatch.groupValues[1].ifEmpty { sMatch.groupValues.getOrNull(2) }
                            numStr?.toIntOrNull()?.let { seasonNum = it }
                            break
                        }
                    }
                }

                // Search backwards for the nearest quality label
                for (sibling in prevSiblings.asReversed()) {
                    val qText = sibling.text().trim()
                    if (qText.isNotBlank()) {
                        val qMatch = qualityRegex.find(qText)
                        if (qMatch != null) {
                            val is10Bit = qText.contains("10bit", ignoreCase = true) || qText.contains("hevc", ignoreCase = true)
                            qualityLabel = if (is10Bit) "${qMatch.value} 10Bit" else qMatch.value
                            break
                        }
                    }
                }

                seasonArchives.getOrPut(seasonNum) { mutableListOf() }.add(ArchiveLink(qualityLabel, archUrl))
            }

            val allEpisodes = mutableListOf<SEpisode>()
            var sequentialNumber = 1

            for (seasonNum in seasonArchives.keys.sorted()) {
                val archList = seasonArchives[seasonNum] ?: continue
                if (archList.isEmpty()) continue

                val epNums = linkedSetOf<Int>()
                for (arch in archList) {
                    val archUrl = arch.url ?: continue
                    val archDoc = getCachedOrFetchArchive(archUrl, postUrl) ?: continue
                    val links = archDoc.select("div.timed-content-client_show_0_5_0 a")
                        .ifEmpty {
                            archDoc.select("""a[href*="cloud.unblockedgames.world"], a[href*="?sid="], a[href*="r?key="]""")
                        }

                    for ((index, linkEl) in links.withIndex()) {
                        val lText = linkEl.text().trim()
                        if (lText.contains("batch", ignoreCase = true) || lText.contains("zip", ignoreCase = true) || lText.contains("comment", ignoreCase = true)) {
                            continue
                        }
                        val epMatch = Regex("""(?:Episode|Ep|E)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(lText)
                        val ep = epMatch?.groupValues?.get(1)?.toIntOrNull()
                            ?: lText.replace("Episode", "", true).trim().toIntOrNull()
                            ?: (index + 1)
                        epNums.add(ep)
                    }

                    if (epNums.isNotEmpty()) {
                        break
                    }
                }

                val sortedEpNums = epNums.sorted()
                for (epNum in sortedEpNums) {
                    val epData = EpisodeData(
                        season = seasonNum,
                        episode = epNum,
                        postUrl = postUrl,
                        archives = archList,
                    )

                    allEpisodes.add(
                        SEpisode.create().apply {
                            name = "Season $seasonNum Ep $epNum"
                            url = json.encodeToString(epData)
                            episode_number = sequentialNumber.toFloat()
                            scanlator = audioTag
                        },
                    )
                    sequentialNumber++
                }
            }

            if (allEpisodes.isNotEmpty()) {
                return allEpisodes.reversed()
            }
        }

        // Single Movie fallback
        val movieArchives = mutableListOf<ArchiveLink>()
        for (row in buttonRows) {
            val aEl = row.selectFirst("a[href]") ?: continue
            val rawHref = aEl.attr("abs:href").ifBlank { aEl.attr("href") }
            if (rawHref.isBlank()) continue
            val archUrl = extractChildUrl(rawHref)

            val prevSiblings = row.previousElementSiblings()
            var qualityLabel = "HD"
            for (sibling in prevSiblings.asReversed()) {
                val qText = sibling.text().trim()
                val qMatch = qualityRegex.find(qText)
                if (qMatch != null) {
                    val is10Bit = qText.contains("10bit", ignoreCase = true) || qText.contains("hevc", ignoreCase = true)
                    qualityLabel = if (is10Bit) "${qMatch.value} 10Bit" else qMatch.value
                    break
                }
            }

            movieArchives.add(ArchiveLink(qualityLabel, archUrl))
        }

        val movieData = EpisodeData(
            season = 0,
            episode = 0,
            postUrl = postUrl,
            archives = movieArchives,
        )

        return listOf(
            SEpisode.create().apply {
                name = "Full Movie"
                url = json.encodeToString(movieData)
                episode_number = 1.0f
                scanlator = audioTag
            },
        )
    }

    private fun extractChildUrl(mainUrl: String): String {
        return runCatching {
            val urlParam = mainUrl.toHttpUrl().queryParameter("url") ?: return@runCatching mainUrl
            val flags = if (urlParam.contains("-") || urlParam.contains("_")) Base64.URL_SAFE else Base64.DEFAULT
            String(Base64.decode(urlParam, flags))
        }.getOrDefault(mainUrl)
    }

    private fun getCachedOrFetchArchive(archUrl: String, referer: String): Document? {
        archiveCache[archUrl]?.let { return it }
        return runCatching {
            val req = GET(archUrl, headersBuilder().set("Referer", referer).build())
            val doc = client.newCall(req).execute().asJsoup()
            archiveCache[archUrl] = doc
            doc
        }.getOrNull()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val data = runCatching { json.decodeFromString<EpisodeData>(episode.url) }.getOrNull()
            ?: return emptyList()

        val hosters = mutableListOf<Hoster>()
        val archives = data.archives ?: emptyList()
        val season = data.season ?: 1
        val targetEpisode = data.episode ?: 1
        val postUrl = data.postUrl ?: baseUrl

        for (archive in archives) {
            val archUrl = archive.url ?: continue
            val quality = archive.quality ?: "HD"
            val archDoc = getCachedOrFetchArchive(archUrl, postUrl) ?: continue
            val links = archDoc.select("div.timed-content-client_show_0_5_0 a")
                .ifEmpty {
                    archDoc.select("""a[href*="cloud.unblockedgames.world"], a[href*="?sid="], a[href*="r?key="]""")
                }

            for ((index, linkElement) in links.withIndex()) {
                val lText = linkElement.text().trim()
                if (lText.contains("batch", ignoreCase = true) || lText.contains("zip", ignoreCase = true) || lText.contains("comment", ignoreCase = true)) {
                    continue
                }

                val epNum = if (season > 0) {
                    Regex("""(?:Episode|Ep|E)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(lText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: lText.replace("Episode", "", true).trim().toIntOrNull()
                        ?: (index + 1)
                } else {
                    0
                }

                if (epNum == targetEpisode) {
                    val sidUrl = linkElement.attr("abs:href").ifBlank { linkElement.attr("href") }
                    if (sidUrl.isNotBlank()) {
                        val cleanText = lText.replace(Regex("""[^\w\s()-]"""), "").trim()
                        val serverLabel = if (season == 0 && cleanText.isNotBlank()) cleanText else "DriveSeed"
                        hosters.add(
                            Hoster(
                                hosterName = "$quality - $serverLabel",
                                hosterUrl = "$sidUrl|$quality",
                            ),
                        )
                    }
                }
            }
        }

        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return hosters.distinctBy { it.hosterUrl }.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.hosterName.contains("1080p", ignoreCase = true) }
                .thenByDescending { it.hosterName.contains("720p", ignoreCase = true) },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|", limit = 2)
        val sidUrl = parts[0]
        val qualityLabel = if (parts.size > 1) parts[1].substringBefore(" - ").trim() else "HD"

        return resolveSidToVideos(sidUrl, qualityLabel)
    }

    private fun resolveSidToVideos(sidUrl: String, qualityLabel: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val streamHeaders = headersBuilder().set("Referer", "https://driveseed.org/").build()

        runCatching {
            val redirectUrl = if (sidUrl.contains("?sid=")) {
                redirectorBypasser.bypass(sidUrl) ?: return emptyList()
            } else if (sidUrl.contains("r?key=")) {
                sidUrl
            } else {
                return emptyList()
            }

            val mediaResp = client.newCall(GET(redirectUrl, headersBuilder().set("Referer", "https://cloud.unblockedgames.world/").build())).execute()
            val mediaHtml = mediaResp.body.string()

            val filePath = Regex("""replace\(["']([^"']+)["']\)""").find(mediaHtml)?.groupValues?.get(1) ?: return emptyList()
            if (filePath == "/404") return emptyList()
            val fileUrl = if (filePath.startsWith("http")) filePath else "https://" + mediaResp.request.url.host + (if (filePath.startsWith("/")) filePath else "/$filePath")

            val fileResp = client.newCall(GET(fileUrl, streamHeaders)).execute()
            val docFile = fileResp.asJsoup()

            // 1. Instant Download / Direct Stream links
            docFile.select("a.btn, a[href*='cdn.video-gen'], a[href*='video-seed'], a[href*='r2.dev'], a[href*='instant.video-gen']").forEach { a ->
                val href = a.attr("abs:href").ifBlank { a.attr("href") }
                val label = a.text().trim()
                if (href.isNotBlank() && !label.contains("login", ignoreCase = true)) {
                    val headRequest = GET(href, streamHeaders).newBuilder().head().build()
                    val headResp = runCatching { client.newCall(headRequest).execute() }.getOrNull()
                    val loc = headResp?.header("location")
                    val directUrl = when {
                        loc != null && loc.contains("url=") ->
                            URLDecoder.decode(loc.substringAfter("url=").substringBefore("&"), "UTF-8")

                        loc != null -> loc

                        headResp?.isSuccessful == true -> href

                        else -> href
                    }

                    if (!directUrl.isNullOrBlank() && videoList.none { it.videoUrl == directUrl }) {
                        if (directUrl.contains(".m3u8")) {
                            runCatching {
                                playlistUtils.extractFromHls(
                                    directUrl,
                                    videoNameGen = { q -> "$qualityLabel - $q" },
                                )
                            }.getOrNull()?.let { videoList.addAll(it) }
                        } else {
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

            // 2. Cloudflare Worker mirrors (/wfile/)
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
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains("Fast", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains("Instant", ignoreCase = true) },
        )
    }

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = "Override Base URL"
            summary = "Default: $PREF_BASE_URL_DEFAULT"
            setDefaultValue(PREF_BASE_URL_DEFAULT)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p")
            entryValues = arrayOf("1080p", "720p", "480p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Interceptor ============================
    private class RateLimitInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response = chain.proceed(request)
            var attempts = 0
            while (response.code == 429 && attempts < 3) {
                attempts++
                val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 1L
                response.close()
                try {
                    Thread.sleep((retryAfter + 1) * 1000L)
                } catch (_: InterruptedException) {}
                response = chain.proceed(request)
            }
            return response
        }
    }

    @Serializable
    data class EpisodeData(
        val season: Int? = null,
        val episode: Int? = null,
        val postUrl: String? = null,
        val archives: List<ArchiveLink>? = null,
    )

    @Serializable
    data class ArchiveLink(
        val quality: String? = null,
        val url: String? = null,
    )

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://moviesmod.zone"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }
}

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
import extensions.utils.Filters
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.parallelMapNotNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.parser.Parser
import java.net.URLEncoder
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

        val episodeElements = content.select("p:has(a.maxbutton-episode-links, a.maxbutton-download-links)")
            .ifEmpty { content.select("p:has(a[class*=maxbutton])") }
            .ifEmpty { content.select("p:has(a[href*='/archives/'])") }
            .filter { row ->
                val aEl = row.selectFirst("a[href]") ?: return@filter false
                val href = aEl.attr("abs:href").ifBlank { aEl.attr("href") }
                val text = aEl.text().trim()
                href.isNotBlank() && !href.startsWith("javascript:") &&
                    !text.contains("batch", ignoreCase = true) &&
                    !text.contains("zip", ignoreCase = true) &&
                    !text.contains("telegram", ignoreCase = true) &&
                    !text.contains("imdb", ignoreCase = true) &&
                    (href.contains("/archives/") || href.contains("?sid=") || href.contains("?url="))
            }

        if (episodeElements.isEmpty()) {
            return emptyList()
        }

        val pageText = content.text()
        val audioTag = when {
            pageText.contains("Dual Audio", ignoreCase = true) -> "Dual Audio"
            pageText.contains("Multi Audio", ignoreCase = true) -> "Multi Audio"
            pageText.contains("Hindi", ignoreCase = true) -> "Hindi"
            pageText.contains("English", ignoreCase = true) -> "English"
            else -> "Original"
        }

        val qualityRegex = Regex("""\d{3,4}p(?:\s+\w+)?""", RegexOption.IGNORE_CASE)
        val seasonRegex = Regex("""[ .]?S(?:eason)?[ .]?(\d{1,2})[ .]?""", RegexOption.IGNORE_CASE)
        val movieTitleRegex = Regex("""^[^(]+\n?""", RegexOption.IGNORE_CASE)

        val isSerie = episodeElements.any { row ->
            row.select("a").any {
                it.hasClass("maxbutton-episode-links") || it.text().contains("Episode", ignoreCase = true)
            }
        }

        val triples = episodeElements.parallelMapNotNull { row ->
            runCatching {
                // Find the nearest heading immediately preceding this button row
                val prevP = row.previousElementSiblings()
                    .lastOrNull { sibling ->
                        val text = sibling.text().trim()
                        text.isNotBlank() && !sibling.hasClass("maxbutton") && sibling.select("a[class*=maxbutton]").isEmpty()
                    }?.text().orEmpty()

                val quality = qualityRegex.find(prevP)?.value ?: "HD"
                val defaultName = if (isSerie) {
                    seasonRegex.find(prevP)?.value?.trim() ?: "Season 1"
                } else {
                    movieTitleRegex.find(prevP.replace("Download", "").trim())?.value?.trim() ?: "Movie"
                }

                val aEl = row.selectFirst("a[href]") ?: return@parallelMapNotNull null
                val rawHref = aEl.attr("abs:href").ifBlank { aEl.attr("href") }
                val childUrl = extractChildUrl(rawHref)

                val episodePageDocument = runCatching {
                    val req = GET(childUrl, headersBuilder().set("Referer", postUrl).build())
                    client.newCall(req).execute().asJsoup()
                }.getOrNull() ?: return@parallelMapNotNull null

                val links = episodePageDocument.select("div.timed-content-client_show_0_5_0 a")
                    .ifEmpty {
                        episodePageDocument.select("""a[href*="cloud.unblockedgames.world"], a[href*="?sid="], a[href*="r?key="]""")
                    }

                links.mapIndexedNotNull { index, linkElement ->
                    val lText = linkElement.text().trim()
                    if (lText.contains("batch", ignoreCase = true) || lText.contains("zip", ignoreCase = true) || lText.contains("comment", ignoreCase = true)) {
                        return@mapIndexedNotNull null
                    }
                    val episode = if (isSerie) {
                        Regex("""(?:Episode|Ep|E)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(lText)?.groupValues?.get(1)?.toIntOrNull()
                            ?: lText.replace("Episode", "", true).trim().toIntOrNull()
                            ?: (index + 1)
                    } else {
                        0
                    }

                    val url = linkElement.attr("abs:href").takeUnless(String::isBlank)
                        ?: return@mapIndexedNotNull null

                    Triple(
                        Pair(defaultName, episode),
                        url,
                        if (isSerie) quality else "$quality $lText".trim(),
                    )
                }
            }.getOrNull()
        }.flatten()

        val grouped = triples.groupBy { it.first }.values.mapIndexed { index, items ->
            val (itemName, episodeNum) = items.first().first

            SEpisode.create().apply {
                url = EpLinks(
                    urls = items.map { triple ->
                        EpUrl(url = triple.second, quality = triple.third)
                    },
                ).toJson()

                name = if (isSerie) "$itemName Ep $episodeNum" else itemName

                episode_number = (index + 1).toFloat()
                scanlator = audioTag
            }
        }

        return grouped.reversed()
    }

    private fun extractChildUrl(mainUrl: String): String {
        return runCatching {
            val urlParam = mainUrl.toHttpUrl().queryParameter("url") ?: return@runCatching mainUrl
            val flags = if (urlParam.contains("-") || urlParam.contains("_")) Base64.URL_SAFE else Base64.DEFAULT
            String(Base64.decode(urlParam, flags))
        }.getOrDefault(mainUrl)
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val epLinks = runCatching { json.decodeFromString<EpLinks>(episode.url) }.getOrNull()
            ?: return emptyList()

        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return epLinks.urls?.mapNotNull { epUrl ->
            val u = epUrl.url ?: return@mapNotNull null
            val q = epUrl.quality ?: "HD"
            Hoster(
                hosterName = "$q - DriveSeed",
                hosterUrl = "$u|$q",
            )
        }.orEmpty().distinctBy { it.hosterUrl }.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.hosterName.contains("1080p", ignoreCase = true) }
                .thenByDescending { it.hosterName.contains("720p", ignoreCase = true) },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|", limit = 2)
        val sidUrl = parts[0]
        val quality = if (parts.size > 1) parts[1] else "HD"

        return extractVideos(sidUrl, quality)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return getHosterList(episode).parallelMapNotNull { hoster ->
            runCatching { getVideoList(hoster) }.getOrNull()
        }.flatten().sortVideos()
    }

    private fun extractVideos(fileOrSidUrl: String, quality: String): List<Video> {
        val mediaUrl = getMediaUrl(fileOrSidUrl) ?: return emptyList()
        val doc = runCatching {
            client.newCall(GET(mediaUrl, headers)).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        val btns = doc.select("div.card-body a.btn")
        if (btns.isEmpty()) return emptyList()

        val videoList = mutableListOf<Video>()

        btns.forEach { btn ->
            val href = btn.attr("abs:href").takeUnless { it.isBlank() } ?: return@forEach
            val size = SIZE_REGEX.find(btn.text())?.groupValues?.get(1)?.let { " - $it" } ?: ""

            when {
                href.contains("cdn.video-gen.xyz") || href.contains("video-seed.dev") ||
                    href.contains("r2.dev") || href.contains("instant.video-gen") -> {
                    val finalUrl = runCatching {
                        val headRequest = GET(href, headers).newBuilder().head().build()
                        client.newCall(headRequest).execute().use { resp ->
                            if (!resp.isSuccessful) return@use null
                            resp.request.url.queryParameter("url") ?: resp.request.url.toString()
                        }
                    }.getOrNull() ?: href

                    if (finalUrl.contains(".m3u8")) {
                        runCatching {
                            playlistUtils.extractFromHls(
                                playlistUrl = finalUrl,
                                videoNameGen = { q -> "$quality - $q$size" },
                            )
                        }.getOrNull()?.let { videoList.addAll(it) } ?: videoList.add(
                            Video(videoUrl = finalUrl, videoTitle = "$quality - HLS$size"),
                        )
                    } else {
                        videoList.add(
                            Video(videoUrl = finalUrl, videoTitle = "$quality - Instant$size"),
                        )
                    }
                }

                href.contains(".m3u8") -> {
                    runCatching {
                        playlistUtils.extractFromHls(
                            playlistUrl = href,
                            videoNameGen = { q -> "$quality - $q$size" },
                        )
                    }.getOrNull()?.let { videoList.addAll(it) }
                }

                href.contains("/login") -> {}

                else -> {
                    videoList.add(
                        Video(videoUrl = href, videoTitle = "$quality - Direct$size"),
                    )
                }
            }
        }

        if (mediaUrl.contains("/file/")) {
            val wfileUrl = mediaUrl.replace("/file/", "/wfile/")
            runCatching {
                val wDoc = client.newCall(GET(wfileUrl, headers)).execute().asJsoup()
                var wIdx = 1
                wDoc.select("a[href*='workers.dev']").forEach { wLink ->
                    val wHref = wLink.attr("abs:href").ifBlank { wLink.attr("href") }
                    if (wHref.isNotBlank() && videoList.none { it.videoUrl == wHref }) {
                        videoList.add(
                            Video(
                                videoUrl = wHref,
                                videoTitle = "$quality - CF Worker $wIdx",
                                headers = headers,
                            ),
                        )
                        wIdx++
                    }
                }
            }
        }

        return videoList
    }

    private fun getMediaUrl(url: String): String? {
        val mediaResponse = if (url.contains("?sid=")) {
            val finalUrl = redirectorBypasser.bypass(url) ?: return null
            client.newCall(GET(finalUrl)).execute()
        } else if (url.contains("r?key=") || url.contains("/file/")) {
            client.newCall(GET(url)).execute()
        } else {
            return null
        }

        val path = mediaResponse.body.string().substringAfter("replace(\"").substringBefore("\"")
        if (path == "/404") return null

        return "https://" + mediaResponse.request.url.host + path
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
    data class EpLinks(
    val urls: List<EpUrl>? = null
)

    @Serializable
    data class EpUrl(
    val quality: String? = null,
    val url: String? = null
)

    private fun EpLinks.toJson(): String = json.encodeToString(this)

    companion object {
        private val SIZE_REGEX = Regex("""\[((?:.(?!\[))+)]*$""", RegexOption.IGNORE_CASE)

        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://moviesmod.zone"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }
}

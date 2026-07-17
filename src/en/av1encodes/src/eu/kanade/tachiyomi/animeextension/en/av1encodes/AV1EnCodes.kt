package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import extensions.utils.asJsoup
import extensions.utils.parseAs
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class AV1EnCodes : Source() {

    override val client = network.client.newBuilder()
        .addInterceptor(AV1EnCodesCloudflareInterceptor(network.client) { baseUrl })
        .build()

    private val cfBypassUserAgent by lazy {
        preferences.getString(PREF_CF_UA_KEY, PREF_CF_UA_DEFAULT)
            ?.takeIf { it.isNotBlank() } ?: PREF_CF_UA_DEFAULT
    }

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addEditTextPreference(
            key = PREF_CF_UA_KEY,
            title = PREF_CF_UA_TITLE,
            summary = PREF_CF_UA_SUMMARY,
            default = PREF_CF_UA_DEFAULT,
        )
    }

    override val name = "AV1 EnCodes"

    override val baseUrl = "https://av1please.com"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .set("User-Agent", cfBypassUserAgent)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
        .add("Sec-Ch-Ua-Mobile", "?0")
        .add("Sec-Ch-Ua-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/stats#top-downloads", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(parseStatsPage(response.asJsoup()), false)

    private val seasonRegex by lazy { Regex("""\[S\d""") }
    private val animeNameRegex by lazy { Regex("""\[S\d{1,2}(?:-E\d+)?]\s*([^\[]+?)\s*\[""") }
    private val specialCharactersRegex by lazy { Regex("[^a-z0-9]+") }

    private fun parseStatsPage(doc: Document): List<SAnime> {
        val seen = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()

        var searchContext: Element = doc
        val header = doc.select("h1,h2,h3,h4,h5,h6").firstOrNull {
            it.text().contains("Top Downloads", ignoreCase = true)
        }
        if (header != null) {
            val sibling = header.nextElementSibling()
            searchContext = if (sibling != null && sibling.text().length > 20) {
                sibling
            } else {
                header.parent() ?: doc
            }
        }

        searchContext.select("a[href*='/anime/'],div[class*='card'],div[class*='item'],li")
            .filter { el ->
                val text = el.text().trim()
                text.contains(seasonRegex) || text.length in 10..200
            }
            .forEach { el ->
                val link = el.selectFirst("a[href*='/anime/']")
                    ?: el.takeIf { it.tagName() == "a" && it.attr("href").contains("/anime/") }
                if (link != null) {
                    val url = link.attr("href").let {
                        if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                    }
                    if (url.startsWith("/anime/") && seen.add(url)) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain(url)
                                title = extractCleanTitle(el.text())
                                thumbnail_url = getListImageUrl(el)
                            },
                        )
                    }
                    return@forEach
                }

                val animeName = extractCleanTitle(el.text().trim())
                val slug = animeName.lowercase(java.util.Locale.US).replace(specialCharactersRegex, "-").trim('-')
                if (slug.length < 3 || !seen.add("/anime/$slug")) return@forEach
                animes.add(
                    SAnime.create().apply {
                        setUrlWithoutDomain("/anime/$slug")
                        title = animeName
                    },
                )
            }

        if (animes.isEmpty()) {
            animeNameRegex.findAll(searchContext.text())
                .map { it.groupValues[1].trim() }
                .distinct()
                .take(20)
                .forEach { animeName ->
                    val slug = animeName.lowercase(java.util.Locale.US)
                        .replace(specialCharactersRegex, "-").trim('-')
                    if (slug.length >= 3 && seen.add("/anime/$slug")) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain("/anime/$slug")
                                title = extractCleanTitle(animeName)
                            },
                        )
                    }
                }
        }

        return animes
    }

    private val cleanTitleRegex1 by lazy { Regex("""\s*·\s*\d+\s*downloads?.*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex2 by lazy { Regex("""^\[[a-zA-Z0-9_\-]+]\s*""") }
    private val cleanTitleRegex3 by lazy { Regex("""\s*\[\d{3,4}p].*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex4 by lazy { Regex("""\.(mkv|mp4)$""", RegexOption.IGNORE_CASE) }

    private fun extractCleanTitle(raw: String): String {
        var cleaned = raw.replace(cleanTitleRegex1, "")
        cleaned = cleaned.replace(cleanTitleRegex2, "")
        cleaned = cleaned.replace(cleanTitleRegex3, "")
        cleaned = cleaned.replace(cleanTitleRegex4, "")
        return cleaned.trim()
    }

    private fun getListImageUrl(anchor: Element): String? {
        val img = anchor.selectFirst("img")
        if (img != null) {
            val url = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }
                .ifBlank { img.attr("abs:src") }
            if (url.isNotBlank()) return url
        }
        return extractBg(anchor) ?: anchor.allElements.firstNotNullOfOrNull { extractBg(it) }
    }

    private val backgroundUrlRegex by lazy { Regex("""url\(['"](.*?)['"]\)""") }

    private fun extractBg(el: Element): String? {
        val style = el.attr("style")
        if (!style.contains("background", ignoreCase = true)) return null
        val match = backgroundUrlRegex.find(style) ?: return null
        val url = match.groupValues[1].ifBlank { return null }
        return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
    }

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.anime-card").mapNotNull { card ->
            val a = card.selectFirst("h4 > a, .card-body a") ?: return@mapNotNull null
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = a.text().trim()
                thumbnail_url = card.selectFirst("div.poster-wrap > img, img")?.let { img ->
                    img.attr("abs:data-src").ifBlank { null }
                        ?: img.attr("abs:data-lazy-src").ifBlank { null }
                        ?: img.attr("abs:src").ifBlank { null }
                }
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(".search-results-grid .anime-item").map { element ->
            SAnime.create().apply {
                title = element.select("h3").text()
                setUrlWithoutDomain(element.select("a.anime-link").attr("href"))
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }
        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(".anime-title")?.text() ?: ""
            thumbnail_url = doc.selectFirst(".anime-image img")?.attr("abs:src")
            genre = doc.selectFirst(".anime-meta")?.text()
                ?.substringAfter("Genre:")
                ?.trim() ?: ""
            description = doc.selectFirst(".anime-synopsis")?.text()
                ?.removePrefix("Synopsis:")
                ?.trim() ?: ""
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val urlPath = response.request.url.encodedPath
        val slug = urlPath.split("/").last { it.isNotBlank() }
        Log.d(TAG, "episodeListParse: slug=$slug quality=$prefQuality")

        val seasons = doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
            .map { it.attr("data-season") }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("1") }
        Log.d(TAG, "episodeListParse: seasons=$seasons")

        val encodedRes = URLEncoder.encode(prefQuality, "UTF-8").replace("+", "%20")

        val episodeNumberRegex = Regex("""E(\d+)""", RegexOption.IGNORE_CASE)

        return seasons.sortedByDescending { it.toIntOrNull() ?: 0 }.parallelCatchingFlatMapBlocking { season ->
            val epPageUrl = "$baseUrl/episodes/$slug/$season/$encodedRes"
            Log.d(TAG, "episodeListParse: fetching episodes page → $epPageUrl")

            val epHtml = client.newCall(GET(epPageUrl, headers)).awaitSuccess().bodyString()

            val epDoc = org.jsoup.Jsoup.parse(epHtml)
            val downloadLinks = epDoc.select("a[href*='/download/']")
            Log.d(TAG, "episodeListParse: found ${downloadLinks.size} download links for season $season")

            if (downloadLinks.isEmpty()) {
                Log.w(TAG, "episodeListParse: no <a> links found, falling back to regex on raw HTML")
                val filenames = extractFilenames(epHtml)
                Log.d(TAG, "episodeListParse: regex found ${filenames.size} filenames")
                return@parallelCatchingFlatMapBlocking filenames.sortedByDescending { parseEpisodeNumber(it) }.map { filename ->
                    val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                    SEpisode.create().apply {
                        setUrlWithoutDomain("/download/$slug/$season/$encodedRes/$encodedFilename")
                        name = buildEpisodeLabel(filename, season)
                        episode_number = parseEpisodeNumber(filename)
                    }
                }
            }

            downloadLinks.sortedByDescending { link ->
                episodeNumberRegex
                    .find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }.map { link ->
                val fullHref = link.attr("href")
                Log.d(TAG, "episodeListParse: episode link → $fullHref")

                val filename = Uri.decode(fullHref.substringAfterLast("/").substringBefore("?"))

                SEpisode.create().apply {
                    setUrlWithoutDomain(fullHref)
                    name = buildEpisodeLabel(filename, season)
                    episode_number = parseEpisodeNumber(filename)
                }
            }
        }
    }

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val episodeUrl = episode.url
        Log.d(TAG, "getVideoList: episode.url=$episodeUrl")

        val encodedFilename = episodeUrl.substringBefore("?").substringAfterLast("/")
        val filename = Uri.decode(encodedFilename)
        Log.d(TAG, "getVideoList: filename=$filename")

        val downloadPageUrl = baseUrl + episodeUrl

        Log.d(TAG, "getVideoList: fetching download page → $downloadPageUrl")
        val pageHtml = try {
            client.newCall(
                GET(downloadPageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build()),
            ).awaitSuccess()
                .bodyString()
        } catch (e: Exception) {
            Log.e(TAG, "getVideoList: download page failed — ${e.message}")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        val ddlToken = Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]""").find(pageHtml)
            ?.groupValues?.get(1)
            ?: run {
                Log.w(TAG, "getVideoList: no ddl-token found in page, falling back")
                return fallbackDirectUrl(episodeUrl, filename)
            }
        Log.d(TAG, "getVideoList: ddlToken=$ddlToken")

        val ddlUrl = "$baseUrl/get_ddl/$encodedFilename"
        Log.d(TAG, "getVideoList: calling get_ddl → $ddlUrl")
        val ddlRaw = try {
            client.newCall(
                GET(
                    ddlUrl,
                    headers.newBuilder()
                        .set("Accept", "application/json")
                        .set("Referer", downloadPageUrl)
                        .set("X-Ddl-Token", ddlToken)
                        .build(),
                ),
            ).awaitSuccess()
                .bodyString()
        } catch (e: Exception) {
            Log.e(TAG, "getVideoList: get_ddl failed — ${e.message}")
            return fallbackDirectUrl(episodeUrl, filename)
        }
        Log.d(TAG, "getVideoList: get_ddl response=$ddlRaw")

        val ddl = try {
            ddlRaw.parseAs<DdlResponse>()
        } catch (e: Exception) {
            Log.e(TAG, "getVideoList: get_ddl parse failed — ${e.message}")
            return fallbackDirectUrl(episodeUrl, filename)
        }
        if (!ddl.success) {
            Log.w(TAG, "getVideoList: get_ddl success=false")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        val videos = mutableListOf<Video>()

        val resLabel = Regex("""\[(\d+p)]""").find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = Regex("""\[(Dual|Sub|Dub)]""", RegexOption.IGNORE_CASE)
            .find(filename)?.groupValues?.get(1) ?: ""
        val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""
        val sizeLabel = ddl.fileSize?.let { " · $it" } ?: ""
        val qualLabel = "AV1 · $resLabel$audioSuffix$sizeLabel"

        suspend fun resolveRedirect(path: String?): String? {
            if (path.isNullOrBlank()) return null
            val url = if (path.startsWith("/")) "$baseUrl$path" else path
            return try {
                val finalUrl = client.newCall(GET(url, headers.newBuilder().set("Referer", "$baseUrl/").build()))
                    .awaitSuccess().use { resp ->
                        resp.request.url.toString()
                    }
                Log.d(TAG, "getVideoList: redirect $path → $finalUrl")
                finalUrl
            } catch (e: Exception) {
                Log.e(TAG, "getVideoList: redirect failed for $path — ${e.message}")
                null
            }
        }

        val watchUrl = resolveRedirect(ddl.watchLink)
        if (watchUrl != null && watchUrl.contains("/watch/")) {
            val dashBase = watchUrl.replace("/watch/", "/dash/")
            val mpdUrl = "$dashBase/manifest.mpd"
            Log.d(TAG, "getVideoList: DASH MPD → $mpdUrl")
            videos.add(
                Video(
                    videoUrl = mpdUrl,
                    videoTitle = "$qualLabel · DASH",
                ),
            )
        }

        val streamUrl = resolveRedirect(ddl.streamLink)
        if (streamUrl != null && streamUrl != watchUrl) {
            Log.d(TAG, "getVideoList: stream URL → $streamUrl")
            videos.add(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "$qualLabel · Stream",
                ),
            )
        }

        val dlUrl = resolveRedirect(ddl.downloadLink)
        if (dlUrl != null) {
            Log.d(TAG, "getVideoList: download URL → $dlUrl")
            videos.add(
                Video(
                    videoUrl = dlUrl,
                    videoTitle = "$qualLabel · Direct DL",
                ),
            )
        }

        if (videos.isEmpty()) {
            Log.w(TAG, "getVideoList: no videos from get_ddl, falling back")
            return fallbackDirectUrl(episodeUrl, filename)
        }

        Log.d(TAG, "getVideoList: returning ${videos.size} videos")
        return videos
    }

    private fun fallbackDirectUrl(episodeUrl: String, filename: String): List<Video> {
        val fullUrl = baseUrl + episodeUrl
        val resLabel = Regex("""\[(\d+p)]""").find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = Regex("""\[(Dual|Sub|Dub)]""", RegexOption.IGNORE_CASE)
            .find(filename)?.groupValues?.get(1) ?: ""
        val label = "AV1 · $resLabel${if (audioTag.isNotBlank()) " [$audioTag]" else ""} · Direct DL"
        Log.d(TAG, "getVideoList: fallback URL → $fullUrl")
        return listOf(
            Video(
                videoUrl = fullUrl,
                videoTitle = label,
            ),
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareBy { it.videoTitle.contains(quality) },
        ).reversed()
    }

    // ============================ Extraction Helpers =============================
    private val filenameRegex by lazy { Regex("""([a-zA-Z0-9_ \-\\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE) }

    private fun extractFilenames(html: String): List<String> {
        val filenames = mutableSetOf<String>()
        val addDecoded = { fn: String ->
            val clean = Uri.decode(fn.trim())
            if (clean.isNotBlank() && !clean.contains("/")) filenames.add(clean)
        }
        org.jsoup.Jsoup.parse(html).select("a[href*='/download/']").forEach {
            addDecoded(it.attr("href").substringAfterLast("/").substringBefore("?"))
        }
        filenameRegex
            .findAll(html).forEach { addDecoded(it.groupValues[1]) }
        return filenames.toList()
    }

    private val episodeNameRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]\s*(.+?)\s*\[""") }
    private val subdubRegex by lazy { Regex("""\[(Dual|Sub|Dub|English Dub)]""", RegexOption.IGNORE_CASE) }
    private val qualityRegex by lazy { Regex("""\[\d{3,4}p].*""") }

    private fun buildEpisodeLabel(filename: String, season: String): String {
        val epMatch = episodeNameRegex.find(filename)
        return if (epMatch != null) {
            val e = epMatch.groupValues[1]
            val titlePart = epMatch.groupValues[2].trim()
            val audioTag = subdubRegex
                .find(filename)?.groupValues?.get(1) ?: ""
            "Season $season Ep $e - $titlePart${if (audioTag.isNotBlank()) " [$audioTag]" else ""}"
        } else {
            val cleanName = filename.replace(qualityRegex, "")
                .substringBeforeLast(".").trim()
            if (season != "1" && season.isNotBlank()) "Season $season - $cleanName" else cleanName
        }
    }

    private val episodeSNumberRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]""") }
    private fun parseEpisodeNumber(filename: String): Float = episodeSNumberRegex.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

    @Serializable
    data class DdlResponse(
        val success: Boolean,
        val watchLink: String? = null,
        val streamLink: String? = null,
        val downloadLink: String? = null,
        val fileSize: String? = null,
        val error: String? = null,
    )

    companion object {
        private const val TAG = "AV1EnCodes"
        private const val DEFAULT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        private const val PREF_CF_UA_KEY = "cf_bypass_ua"
        private const val PREF_CF_UA_TITLE = "Custom User-Agent"
        private const val PREF_CF_UA_DEFAULT = DEFAULT_UA
        private val PREF_CF_UA_SUMMARY = """Custom User-Agent string for the Cloudflare WebView bypass.
            |Leave blank to use the default.
        """.trimMargin()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")
    }
}

class AV1EnCodesCloudflareInterceptor(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
) : Interceptor {
    private val cfInterceptor = CloudflareInterceptor(client)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isBaseUrl = request.url.host == baseUrlProvider().toHttpUrlOrNull()?.host
        return if (isBaseUrl) {
            cfInterceptor.intercept(chain)
        } else {
            chain.proceed(request)
        }
    }
}

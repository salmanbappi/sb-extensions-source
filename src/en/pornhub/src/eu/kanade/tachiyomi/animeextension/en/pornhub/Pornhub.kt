package eu.kanade.tachiyomi.animeextension.en.pornhub

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

class Pornhub : Source() {

    override val name = "Pornhub"

    private val customUrlPref by lazy {
        CustomUrlPreferences(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Override Base URL",
            dialogMessage = "Enter custom domain or mirror (e.g., https://www.pornhub.com or https://www.pornhub.org):",
        )
    }

    override val baseUrl: String
        get() = customUrlPref.baseUrl

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .retryOnConnectionFailure(true)
            .rateLimit(permits = 5, period = 1.seconds)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val jsonHelper = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var dynamicCategories: List<Pair<String, String>> = emptyList()

    init {
        loadSavedCategories()
        scope.launch { fetchDynamicCategories(force = false) }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Cookie", "accessAgeDisclaimerPH=1; hasVisited=1; age_verified=1; platform=pc")

    // ============================= Popular Videos =============================

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/", headers)
    } else {
        GET("$baseUrl/video?o=ht&page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // Prioritize dynamic "Recommended for You" section if present on homepage
        val recommendedSection = document.select(
            "div#recommendedVideosBox li.pcVideoListItem, " +
                "ul#recommendedVideos li, " +
                "div.recommendedVideos li, " +
                "section.recommendedVideos li, " +
                "div.videoSectionWrapper:has(h2:matches((?i)recommended)) ul.videos li, " +
                "#frontPage ul.videos li",
        )

        val elements = if (recommendedSection.isNotEmpty()) {
            recommendedSection
        } else {
            document.select(
                "li.pcVideoListItem[data-video-vkey], " +
                    "li[data-video-id][data-video-vkey], " +
                    "li.pcVideoListItem, " +
                    "li.videoBox, " +
                    "li.js-popvideoblock, " +
                    "ul#videoSearchResult li, " +
                    "#searchVideos li",
            )
        }

        val animeList = parseVideoCards(elements)
        val hasNextPage = hasNextPage(document)

        return AnimesPage(animeList, hasNextPage)
    }

    // ============================= Latest Updates =============================

    override fun latestUpdatesRequest(page: Int): Request = GET(
        "$baseUrl/video?o=mr&page=$page",
        headers,
    )

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        // Strip out the static recommended/promoted containers on /video?o=mr
        document.select(
            "div#recommendedVideosBox, " +
                "div.recommendedVideos, " +
                "section.recommendedVideos, " +
                "div.videoSectionWrapper:has(h2:matches((?i)recommended)), " +
                "div.featured-video",
        ).remove()

        val elements = document.select(
            "li.pcVideoListItem[data-video-vkey], " +
                "li[data-video-id][data-video-vkey], " +
                "li.pcVideoListItem, " +
                "li.videoBox, " +
                "li.js-popvideoblock, " +
                "ul#videoSearchResult li, " +
                "#searchVideos li",
        )

        val animeList = parseVideoCards(elements)
        val hasNextPage = hasNextPage(document)

        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseVideoCards(elements: Iterable<Element>): List<SAnime> = elements.mapNotNull { element ->
        val linkElement = element.selectFirst("a[href*=/view_video.php]")
            ?: element.selectFirst("a[href*=\"viewkey=\"]")
            ?: return@mapNotNull null

        val imageElement = element.selectFirst("img")
        val title = element
            .selectFirst("span.title a[href], a.thumbnailTitle[href], a[href][title]")
            ?.text()
            ?.ifEmpty {
                element.selectFirst("a[href][title]")?.attr("title").orEmpty()
            }
            ?.ifEmpty {
                imageElement?.attr("alt").orEmpty()
            }
            ?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null

        SAnime.create().apply {
            setUrlWithoutDomain(linkElement.absUrl("href"))
            this.title = title
            thumbnail_url = imageElement?.let(::extractListingThumbnail)
        }
    }.distinctBy { it.url }

    private fun hasNextPage(document: Document): Boolean = document.selectFirst(
        "div.page_next a[href], li.page_next a[href], a.page_next[href], a.next[href], ul.pagination li.next a, a[rel=next], .paginationWrapper .next a",
    ) != null

    // ============================= Search =============================

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val filterData = PornhubFilters.getFilterData(filters)

        if (filterData.reloadCategories) {
            scope.launch { fetchDynamicCategories(force = true) }
        }

        val trimmedQuery = query.trim()
        val isExplicitUserSearch = filterData.username.isNotBlank() ||
            (filterData.searchType in listOf("user", "model", "pornstar", "channel") && trimmedQuery.isNotBlank()) ||
            trimmedQuery.startsWith("user:", ignoreCase = true) ||
            trimmedQuery.startsWith("model:", ignoreCase = true) ||
            trimmedQuery.startsWith("pornstar:", ignoreCase = true) ||
            trimmedQuery.startsWith("channel:", ignoreCase = true)

        if (isExplicitUserSearch) {
            val rawUsername = when {
                filterData.username.isNotBlank() -> filterData.username
                trimmedQuery.contains(':') -> trimmedQuery.substringAfter(':').trim()
                else -> trimmedQuery
            }
            val username = rawUsername.trim().lowercase().replace(' ', '-')

            val targetType = when {
                trimmedQuery.startsWith("model:", ignoreCase = true) -> "model"
                trimmedQuery.startsWith("pornstar:", ignoreCase = true) -> "pornstar"
                trimmedQuery.startsWith("channel:", ignoreCase = true) -> "channel"
                trimmedQuery.startsWith("user:", ignoreCase = true) -> "user"
                filterData.searchType != "video" -> filterData.searchType
                else -> "user"
            }

            val subPath = when (targetType) {
                "model" -> "model/$username/videos"
                "pornstar" -> "pornstar/$username/videos"
                "channel" -> "channels/$username/videos"
                else -> if (filterData.userSection == "public") "users/$username/videos/public" else "users/$username/videos"
            }

            val url = "$baseUrl/$subPath".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                if (filterData.userSort.isNotBlank()) {
                    addQueryParameter("o", filterData.userSort)
                }
            }.build()

            return GET(url, headers)
        }

        // Standard Keyword Search or Filtered Catalog Browsing
        val url = if (trimmedQuery.isNotBlank()) {
            "$baseUrl/video/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("search", trimmedQuery)
                addQueryParameter("page", page.toString())
                if (filterData.sort.isNotBlank()) addQueryParameter("o", filterData.sort)
                if (filterData.production.isNotBlank()) addQueryParameter("p", filterData.production)
                if (filterData.quality.isNotBlank()) addQueryParameter("hd", "1")
                if (filterData.categoryId.isNotBlank()) addQueryParameter("c", filterData.categoryId)
            }.build()
        } else {
            val baseCatalog = when (filterData.orientation) {
                "gay" -> "$baseUrl/gay/video"
                "transgender" -> "$baseUrl/transgender/video"
                else -> "$baseUrl/video"
            }
            baseCatalog.toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                if (filterData.categoryId.isNotBlank()) addQueryParameter("c", filterData.categoryId)
                if (filterData.sort.isNotBlank()) addQueryParameter("o", filterData.sort)
                if (filterData.production.isNotBlank()) addQueryParameter("p", filterData.production)
                if (filterData.quality.isNotBlank()) addQueryParameter("hd", "1")
            }.build()
        }

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================= Details =============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET(if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val flashvars = parseFlashvars(document)
        val title = extractTitle(document, flashvars)
        val uploader = extractUploader(document)
        val views = document.selectFirst("div.ratingInfo div.views span.count, span.views .count")
            ?.text()
            .orEmpty()
        val rating = document.selectFirst("span.percent")?.text().orEmpty()

        return SAnime.create().apply {
            initialized = true
            setUrlWithoutDomain(response.request.url.toString())
            this.title = title
            author = uploader
            artist = uploader
            thumbnail_url = extractDetailThumbnail(document, flashvars)
            genre = extractCategories(document)?.joinToString()
            description = buildString {
                if (!uploader.isNullOrBlank()) {
                    append("Uploader: ").append(uploader).append("\n")
                }
                if (views.isNotEmpty()) {
                    append("Views: ").append(views).append("\n")
                }
                if (rating.isNotEmpty()) {
                    append("Rating: ").append(rating).append("\n")
                }
            }
            status = SAnime.COMPLETED
        }
    }

    // ============================= Episodes =============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episode = SEpisode.create().apply {
            setUrlWithoutDomain(response.request.url.toString())
            name = "Play Video"
            episode_number = 1f
            date_upload = extractPublishedDate(document)
        }
        return listOf(episode)
    }

    // ============================= Video Streams =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val flashvars = parseFlashvars(document)

        val remoteVideos = extractRemoteMediaVideos(flashvars, response)
        val directVideos = extractDirectVideos(document)
        val hlsVideos = extractHlsVideos(flashvars)
        val expandedHlsVideos = extractExpandedHlsVideos(flashvars)

        val allVideos = (remoteVideos + directVideos + expandedHlsVideos + hlsVideos)
            .distinctBy { video ->
                videoResolution(video)?.let { "res:$it" } ?: video.videoUrl
            }
            .sortedByDescending { videoResolution(it) ?: 0 }

        return allVideos
    }

    private fun extractRemoteMediaVideos(flashvars: Flashvars?, pageResponse: Response): List<Video> {
        val mediaEndpoint = flashvars
            ?.mediaDefinitions
            .orEmpty()
            .firstOrNull { definition ->
                definition.format.equals("mp4", ignoreCase = true) &&
                    definition.videoUrl?.contains("/video/get_media") == true
            }
            ?.videoUrl
            ?: return emptyList()

        return runCatching {
            val endpointUrl = mediaEndpoint.toHttpUrl()
            val requestHeaders = headers.newBuilder()
                .apply {
                    val sessionCookies = buildList {
                        addAll(runCatching { Cookie.parseAll(endpointUrl, pageResponse.headers) }.getOrDefault(emptyList()))
                        addAll(client.cookieJar.loadForRequest(endpointUrl))
                    }
                        .filter { it.value.isNotEmpty() }
                        .distinctBy { it.name }

                    if (sessionCookies.isNotEmpty()) {
                        set("Cookie", sessionCookies.joinToString("; ") { "${it.name}=${it.value}" })
                    }
                }
                .build()

            client.newCall(GET(mediaEndpoint, requestHeaders)).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()

                response
                    .parseAs<List<MediaDefinition>>(jsonHelper)
                    .mapNotNull { definition ->
                        val url = definition.videoUrl?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                        if (!url.substringBefore('?').endsWith(".mp4", ignoreCase = true)) return@mapNotNull null

                        val resolution = definition.qualityInt
                            ?: RESOLUTION_REGEX.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            ?: return@mapNotNull null

                        Video(
                            videoUrl = url,
                            videoTitle = "${resolution}p (MP4)",
                            headers = headers,
                        )
                    }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractHlsVideos(flashvars: Flashvars?): List<Video> {
        return flashvars
            ?.mediaDefinitions
            .orEmpty()
            .mapNotNull { definition ->
                val url = definition.videoUrl?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val isHls = definition.format.equals("hls", ignoreCase = true) ||
                    url.contains(".m3u8", ignoreCase = true)
                if (!isHls) return@mapNotNull null

                val resolution = definition.qualityInt
                    ?: RESOLUTION_REGEX.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

                Video(
                    videoUrl = url,
                    videoTitle = if (resolution != null) "${resolution}p (HLS)" else "HLS",
                    headers = headers,
                )
            }
    }

    private fun extractExpandedHlsVideos(flashvars: Flashvars?): List<Video> {
        val masterHlsUrls = flashvars?.mediaDefinitions.orEmpty()
            .filter { def ->
                val url = def.videoUrl.orEmpty()
                (def.format.equals("hls", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true)) &&
                    !url.contains("/video/get_media")
            }
            .mapNotNull { it.videoUrl }
            .distinct()

        val results = mutableListOf<Video>()
        for (url in masterHlsUrls) {
            val variants = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> "$quality (HLS)" },
                )
            }.getOrDefault(emptyList())
            results.addAll(variants)
        }
        return results
    }

    private fun extractDirectVideos(document: Document): List<Video> {
        val urls = document
            .select("meta[property=og:video], meta[name=twitter:player:stream]")
            .map { it.attr("content").trim() }
            .filter { url ->
                url.isNotEmpty() && url.substringBefore('?').endsWith(".mp4", ignoreCase = true)
            }
            .distinct()

        return urls.map { url ->
            val resolution = RESOLUTION_REGEX
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            Video(
                videoUrl = url,
                videoTitle = if (resolution != null) "${resolution}p (MP4)" else "Direct MP4",
                headers = headers,
            )
        }
    }

    private fun videoResolution(video: Video): Int? = video.videoTitle.filter { it.isDigit() }.toIntOrNull()

    private fun parseFlashvars(document: Document): Flashvars? = document.select("script")
        .asSequence()
        .mapNotNull { script ->
            FLASHVARS_REGEX.find(script.data())?.groupValues?.getOrNull(1)
        }
        .mapNotNull { jsonString ->
            runCatching { jsonString.parseAs<Flashvars>(jsonHelper) }.getOrNull()
        }
        .firstOrNull()

    private fun extractTitle(document: Document, flashvars: Flashvars?): String = document.selectFirst("h1.title span.inlineFree, h1.title, .video-wrapper h1")?.text()
        ?: flashvars?.videoTitle
        ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ?: "Pornhub Video"

    private fun extractUploader(document: Document): String? {
        val jsonLd = document.selectFirst("script[type=application/ld+json]")?.data().orEmpty()
        return JSON_LD_AUTHOR_REGEX.find(jsonLd)?.groupValues?.getOrNull(1)
            ?: document.selectFirst("div.userInfo a.usernameWrap, a.bolded[href*=/users/], a.bolded[href*=/model/]")?.text()
    }

    private fun extractCategories(document: Document): List<String>? {
        val fromDom = document.select("div.categoriesWrapper a[href*=/video?c=], div.tagsWrapper a.item")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (fromDom.isNotEmpty()) return fromDom

        val script = document.select("script").asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("categories_in_video") } ?: return null

        val categories = CATEGORIES_REGEX.find(script)?.groupValues?.getOrNull(1) ?: return null
        return categories.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun extractPublishedDate(document: Document): Long {
        val script = document.select("script").asSequence()
            .map { it.data() }
            .firstOrNull { it.contains("video_date_published") } ?: return 0L

        val date = PUBLISHED_DATE_REGEX.find(script)?.groupValues?.getOrNull(1)
        return PUBLISHED_DATE_FORMAT.tryParse(date)
    }

    private fun extractListingThumbnail(image: Element): String? {
        stableThumbnailUrl(image.attr("data-path"))?.let { return it }

        return image.attr("abs:data-image")
            .ifEmpty { image.attr("abs:data-mediumthumb") }
            .ifEmpty { image.attr("abs:data-thumb_url") }
            .ifEmpty { image.attr("abs:data-src") }
            .ifEmpty { image.attr("abs:src") }
            .takeIf { it.isNotEmpty() && !it.startsWith("data:") && !it.contains("blank.gif") }
    }

    private fun stableThumbnailUrl(dataPath: String?): String? {
        if (dataPath.isNullOrBlank()) return null

        val normalized = dataPath.replace("{index}", "0")
        val absoluteUrl = when {
            normalized.startsWith("https://") -> normalized
            normalized.startsWith("//") -> "https:$normalized"
            normalized.startsWith("/") -> "$IMAGE_BASE_URL$normalized"
            else -> return null
        }

        val parsedUrl = absoluteUrl.toHttpUrlOrNull() ?: return null
        if (parsedUrl.host != IMAGE_HOST) return null

        val sourcePath = parsedUrl.encodedPath.substringBefore("/plain/")
        if (!STABLE_IMAGE_PATH_REGEX.matches(sourcePath)) return null

        return parsedUrl
            .newBuilder()
            .encodedPath(sourcePath)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun extractDetailThumbnail(document: Document, flashvars: Flashvars?): String? = document.selectFirst("meta[name=twitter:image]")?.attr("content")?.takeIf { it.isNotEmpty() }
        ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotEmpty() }
        ?: flashvars?.imageUrl

    // ============================= Dynamic Categories =============================

    private fun loadSavedCategories() {
        val saved = preferences.getString(PREF_CATEGORIES_CACHE_KEY, null) ?: return
        val parsed = saved.split(";;").mapNotNull { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        if (parsed.isNotEmpty()) {
            dynamicCategories = parsed
        }
    }

    private fun fetchDynamicCategories(force: Boolean) {
        if (!force && dynamicCategories.isNotEmpty()) return

        runCatching {
            val response = client.newCall(GET("$baseUrl/categories", headers)).execute()
            if (!response.isSuccessful) return

            val document = response.asJsoup()
            val categoryElements = document.select("ul#categoriesWorldMenu li a, ul#categoriesListSection li a, div.category-wrapper a")

            val parsedList = mutableListOf<Pair<String, String>>()
            for (element in categoryElements) {
                val name = element.text().trim()
                val href = element.attr("href")
                val id = href.toHttpUrlOrNull()?.queryParameter("c")
                    ?: href.substringAfter("c=").substringBefore('&')

                if (name.isNotEmpty() && id.isNotEmpty() && parsedList.none { it.second == id }) {
                    parsedList.add(name to id)
                }
            }

            if (parsedList.size > 1) {
                dynamicCategories = parsedList
                val serialized = parsedList.joinToString(";;") { "${it.first}::${it.second}" }
                preferences.edit().putString(PREF_CATEGORIES_CACHE_KEY, serialized).apply()
            }
        }
    }

    override fun getFilterList(): AnimeFilterList {
        val categories = dynamicCategories.ifEmpty { PornhubFilters.DEFAULT_CATEGORIES }
        return AnimeFilterList(
            PornhubFilters.SearchTypeFilter(),
            PornhubFilters.UsernameFilter(),
            AnimeFilter.Separator(),
            PornhubFilters.UserSortFilter(),
            PornhubFilters.UserSectionFilter(),
            AnimeFilter.Separator(),
            PornhubFilters.SortFilter(),
            PornhubFilters.OrientationFilter(),
            PornhubFilters.ProductionFilter(),
            PornhubFilters.DurationFilter(),
            PornhubFilters.QualityFilter(),
            PornhubFilters.DynamicCategoryFilter(categories),
            PornhubFilters.ReloadCategoriesFilter(),
        )
    }

    // ============================= Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        customUrlPref.setupPreferenceScreen(screen)

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred Video Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "240p"),
            entryValues = listOf("1080", "720", "480", "240"),
        )
    }

    companion object {
        private const val PREF_BASE_URL_DEFAULT = "https://www.pornhub.com"
        private const val IMAGE_BASE_URL = "https://ei.phncdn.com"
        private const val IMAGE_HOST = "ei.phncdn.com"

        private const val PREF_QUALITY_KEY = "preferred_video_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_CATEGORIES_CACHE_KEY = "cached_categories_v1"

        private val FLASHVARS_REGEX = Regex(
            """var\s+flashvars_\d+\s*=\s*(\{.*?\});""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val CATEGORIES_REGEX = Regex(
            """['"]categories_in_video['"]\s*:\s*['"]([^'"]+)['"]""",
        )
        private val PUBLISHED_DATE_REGEX = Regex(
            """['"]video_date_published['"]\s*:\s*['"](\d{8})['"]""",
        )
        private val JSON_LD_AUTHOR_REGEX = Regex(
            """"author"\s*:\s*"([^"]+)"""",
        )
        private val RESOLUTION_REGEX = Regex(
            """(?:^|[^0-9])(2160|1440|1080|720|480|360|240)[pP]?""",
            RegexOption.IGNORE_CASE,
        )
        private val STABLE_IMAGE_PATH_REGEX = Regex(
            """.*\.(?:jpe?g|png|webp)$""",
            RegexOption.IGNORE_CASE,
        )
        private val PUBLISHED_DATE_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}

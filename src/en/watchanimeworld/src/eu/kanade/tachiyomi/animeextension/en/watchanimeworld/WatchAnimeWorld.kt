package eu.kanade.tachiyomi.animeextension.en.watchanimeworld

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.EpisodeMetadataFetcher
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Serializable
data class ServerItem(
    val language: String? = null,
    val link: String? = null,
)

@Serializable
data class ZephyrResponse(
    val videoSource: String? = null,
    val hls: Boolean? = null,
)

class WatchAnimeWorld : Source() {

    override val name = "WatchAnimeWorld"

    override val baseUrl = "https://watchanimeworld.net"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(CloudflareInterceptor(network.client))
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private val metadataFetcher by lazy {
        EpisodeMetadataFetcher(client, json)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/series/page/$page/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".latest-ep-swiper-slide article.post, article.post.movies, article.post, .swiper-slide article, div.items article.item").map { element ->
            parseAnimeFromElement(element)
        }.distinctBy { it.url }

        val url = response.request.url.toString()
        val page = url.substringAfter("/page/").substringBefore("/").toIntOrNull()
            ?: url.substringAfter("paged=").substringBefore("&").toIntOrNull()
            ?: 1

        val hasNextPage = document.select(".pagination a, .nav-links a, a.next").any {
            val href = it.attr("href")
            href.contains("/page/${page + 1}") || href.contains("paged=${page + 1}")
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?post_type=episodes&paged=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    private fun SAnime.fullUrl() = if (url.startsWith("http")) url else baseUrl + url
    private fun SEpisode.fullUrl() = if (url.startsWith("http")) url else baseUrl + url

    override fun animeDetailsRequest(anime: SAnime): Request = GET(anime.fullUrl(), headers)

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = super.getAnimeDetails(anime)
        val loadDescriptions = preferences.getBoolean(PREF_LOAD_DESCRIPTIONS_KEY, true)
        if (loadDescriptions && details.description.isNullOrBlank()) {
            try {
                val metadataMap = metadataFetcher.fetch(malId = "", animeTitle = details.title, fallbackThumbnailUrl = details.thumbnail_url)
                val firstDesc = metadataMap.values.firstOrNull { !it.description.isNullOrBlank() }?.description
                if (!firstDesc.isNullOrBlank()) {
                    details.description = firstDesc
                }
            } catch (_: Exception) {}
        }
        return details
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        GET("$baseUrl/page/$page/?s=$query", headers)
    } else {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val network = filters.filterIsInstance<NetworkFilter>().firstOrNull()?.getSelectedValue() ?: ""

        when {
            genre.isNotEmpty() -> GET("$baseUrl/category/genre/$genre/page/$page/", headers)
            language.isNotEmpty() -> GET("$baseUrl/category/language/$language/page/$page/", headers)
            network.isNotEmpty() -> GET("$baseUrl/category/network/$network/page/$page/", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are ignored on text search"),
        GenreFilter(),
        LanguageFilter(),
        NetworkFilter(),
    )

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Genre",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue(): String = GENRES[state].second
    }

    private class LanguageFilter :
        AnimeFilter.Select<String>(
            "Audio Language",
            LANGUAGES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue(): String = LANGUAGES[state].second
    }

    private class NetworkFilter :
        AnimeFilter.Select<String>(
            "Network",
            NETWORKS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue(): String = NETWORKS[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".entry-title, h1.entry-title, h1")?.text()?.trim() ?: ""
            description = document.selectFirst(".description p, .wp-content p, #info p, .description")?.text()?.trim() ?: ""
            genre = document.select(".genres a, .sgeneros a").joinToString { it.text().trim() }
            val posterImg = document.selectFirst("article.post.single img, article.single img, .bd article.post img, .poster img, img[alt^='Image ']:not(.custom-logo)")
            val rawThumb = posterImg?.attr("abs:src")?.takeIf { it.isNotBlank() && !it.contains("SiteTitle") && !it.contains("AWI-SiteTitle") }
                ?: posterImg?.attr("abs:data-src")?.takeIf { it.isNotBlank() && !it.contains("SiteTitle") && !it.contains("AWI-SiteTitle") }
                ?: posterImg?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("SiteTitle") && !it.contains("AWI-SiteTitle") } ?: ""
            thumbnail_url = when {
                rawThumb.startsWith("//") -> "https:$rawThumb"
                rawThumb.startsWith("/") -> baseUrl + rawThumb
                else -> rawThumb
            }
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = super.getEpisodeList(anime)
        val loadThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)
        val loadTitles = preferences.getBoolean(PREF_LOAD_TITLES_KEY, true)
        val loadDescriptions = preferences.getBoolean(PREF_LOAD_DESCRIPTIONS_KEY, true)

        if (!loadThumbnails && !loadTitles && !loadDescriptions) return episodes

        return try {
            val metadataMap = metadataFetcher.fetch(malId = "", animeTitle = anime.title, fallbackThumbnailUrl = anime.thumbnail_url)
            if (metadataMap.isEmpty()) return episodes

            episodes.map { episode ->
                val num = episode.episode_number.toInt()
                val meta = metadataMap[num] ?: return@map episode
                episode.apply {
                    if (loadThumbnails && (preview_url.isNullOrEmpty() || preview_url!!.contains("SiteTitle")) && !meta.thumbnailUrl.isNullOrEmpty()) {
                        preview_url = meta.thumbnailUrl
                    }
                    if (loadDescriptions && summary.isNullOrEmpty() && !meta.description.isNullOrEmpty()) {
                        summary = meta.description
                    }
                    if (loadTitles && !meta.title.isNullOrBlank()) {
                        val seasonPrefix = if (name.startsWith("S")) name.substringBefore(" - ") + " - " else ""
                        val epPad = if (num > 0) num.toString().padStart(2, '0') else episode.episode_number.toString()
                        if (name.matches(Regex("""^(?:S\d+\s*-\s*)?Ep\.\s*\d+$""", RegexOption.IGNORE_CASE)) ||
                            name.equals("Movie", ignoreCase = true)
                        ) {
                            name = "${seasonPrefix}Ep. $epPad - ${meta.title}"
                        }
                    }
                }
            }
        } catch (_: Exception) {
            episodes
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val urlPath = response.request.url.encodedPath

        if (urlPath.contains("/movies/")) {
            return listOf(
                SEpisode.create().apply {
                    url = urlPath
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }

        val episodes = mutableListOf<SEpisode>()
        episodes.addAll(parseEpisodesFromHtml(document))

        // Check for other seasons
        val seasonLinks = document.select(".choose-season .sel-temp a")
        if (seasonLinks.size > 1) {
            val postId = seasonLinks.firstOrNull()?.attr("data-post")
            if (!postId.isNullOrEmpty()) {
                val currentSeason = document.selectFirst(".n_s")?.text()?.trim()?.toIntOrNull() ?: 1

                val otherSeasonEpisodes = seasonLinks.filter { link ->
                    val seasonNum = link.attr("data-season").toIntOrNull() ?: 0
                    seasonNum != currentSeason
                }.parallelCatchingFlatMapBlocking { link ->
                    val seasonNum = link.attr("data-season")
                    val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$seasonNum&post=$postId"
                    try {
                        val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
                        val ajaxHtml = ajaxResponse.bodyString()
                        val ajaxDoc = org.jsoup.Jsoup.parse(ajaxHtml, baseUrl)
                        parseEpisodesFromHtml(ajaxDoc)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
                episodes.addAll(otherSeasonEpisodes)
            }
        }

        return episodes.sortedWith(
            compareByDescending<SEpisode> { ep ->
                val name = ep.name
                if (name.startsWith("S")) {
                    name.substringAfter("S").substringBefore(" ").toIntOrNull() ?: 1
                } else {
                    1
                }
            }.thenByDescending { it.episode_number },
        )
    }

    private fun parseEpisodesFromHtml(document: Document): List<SEpisode> {
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)
        val listItems = if (document.selectFirst("#episode_by_temp") != null) {
            document.select("#episode_by_temp li")
        } else {
            document.select("li")
        }
        return listItems.mapNotNull { li ->
            val article = li.selectFirst("article.episodes") ?: return@mapNotNull null
            val link = article.selectFirst("a.lnk-blk") ?: return@mapNotNull null
            val numEpi = article.selectFirst("span.num-epi")?.text()?.trim() ?: ""
            val titleText = article.selectFirst(".entry-title")?.text()?.trim() ?: ""

            val match = EPISODE_NUM_REGEX.find(numEpi)
            val episodeNum = match?.groupValues?.get(2)?.toFloatOrNull() ?: 0f

            val seasonVal = match?.groupValues?.get(1)?.toIntOrNull()
            val epVal = match?.groupValues?.get(2)?.toIntOrNull()

            val epInt = epVal ?: episodeNum.toInt()
            val isWhole = epVal != null || episodeNum == epInt.toFloat()
            val epPad = if (isWhole) epInt.toString().padStart(2, '0') else episodeNum.toString()

            val displayTitle = when {
                titleText.isNotBlank() && !titleText.contains(numEpi, ignoreCase = true) -> titleText
                seasonVal != null -> "S$seasonVal - Ep. $epPad"
                else -> "Ep. $epPad"
            }

            SEpisode.create().apply {
                val rawHref = link.attr("href").takeIf { it.startsWith("/") }
                    ?: link.attr("abs:href").substringAfter(baseUrl)
                url = rawHref
                name = displayTitle
                episode_number = episodeNum

                val img = article.selectFirst("img")
                val imgUrl = img?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("abs:src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                    ?: img?.attr("src") ?: ""
                val finalImgUrl = when {
                    imgUrl.startsWith("//") -> "https:$imgUrl"
                    imgUrl.startsWith("/") -> baseUrl + imgUrl
                    else -> imgUrl
                }
                preview_url = if (showThumbnails && finalImgUrl.isNotBlank()) finalImgUrl else null
            }
        }
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = error("Not used")
    override fun videoListParse(response: Response): List<Video> = error("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET(episode.fullUrl(), headers)).execute()
        val html = response.bodyString()
        val doc = org.jsoup.Jsoup.parse(html, response.request.url.toString())
        val hosters = mutableListOf<Hoster>()
        val episodeUrl = response.request.url.toString()

        // 1. Extract Zephyr / Zephyrix player sources FIRST (fastest HLS CDN streams)
        val zephyrIframes = doc.select("iframe").mapNotNull {
            it.attr("data-src").takeIf { s -> s.isNotBlank() } ?: it.attr("src").takeIf { s -> s.isNotBlank() }
        }.filter { "zephyr" in it.lowercase() || "zephyrix" in it.lowercase() || "/video/" in it }

        zephyrIframes.forEachIndexed { index, iframeUrl ->
            val suffix = if (zephyrIframes.size > 1) " ${index + 1}" else ""
            hosters.add(
                Hoster(
                    hosterName = "Zephyr$suffix",
                    hosterUrl = "zephyr|Multi|$iframeUrl|$episodeUrl",
                ),
            )
        }

        // 2. Extract /api/player1.php?data= encoded server list
        val player1Match = PLAYER1_REGEX.find(html)
        if (player1Match != null) {
            try {
                val encodedData = player1Match.groupValues[1]
                val decodedBytes = android.util.Base64.decode(encodedData, android.util.Base64.DEFAULT)
                val decodedStr = String(decodedBytes, Charsets.UTF_8)
                val servers = json.decodeFromString<List<ServerItem>>(decodedStr)

                servers.forEach { server ->
                    val link = server.link ?: return@forEach
                    val lang = server.language ?: "Unknown"
                    val isAbyss = "abysscdn.com" in link || "hydraxcdn.biz" in link || "short.icu" in link ||
                        "embedplayabyss.top" in link || "abyssplayer.com" in link || "playabyss.top" in link ||
                        "short.ink" in link || "abyss" in lang.lowercase() || "hydrax" in lang.lowercase()
                    val serverName = if (isAbyss) "Abyss ($lang)" else "Server ($lang)"
                    val type = if (isAbyss) "abyss" else "m3u8"
                    hosters.add(
                        Hoster(
                            hosterName = serverName,
                            hosterUrl = "$type|$lang|$link|$episodeUrl",
                        ),
                    )
                }
            } catch (_: Exception) {}
        }

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedTypes = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()

        return hosters.filter { hoster ->
            val nameLower = hoster.hosterName.lowercase()
            val matchesExclude = excludedServers.any { nameLower.contains(it.lowercase()) }
            val matchesType = excludedTypes.any { nameLower.contains(it.lowercase()) }
            !matchesExclude && !matchesType
        }.distinctBy { it.hosterName }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 4) return emptyList()

        val type = parts[0]
        val lang = parts[1]
        val link = parts[2]
        val episodeUrl = parts[3]

        return try {
            when (type) {
                "zephyr", "zephyrflick" -> {
                    extractZephyr(link, episodeUrl, lang)
                }

                "abyss" -> {
                    AbyssExtractor(client, playlistUtils)
                        .videosFromUrl(link, referer = episodeUrl, prefix = "$lang - ")
                }

                "m3u8" -> {
                    extractM3u8(link, episodeUrl, lang)
                }

                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractM3u8(link: String, episodeUrl: String, lang: String): List<Video> {
        val serverResponse = client.newCall(
            GET(link, headers.newBuilder().set("Referer", episodeUrl).build()),
        ).execute()
        val serverHtml = serverResponse.bodyString()

        val m3u8Matches = M3U8_REGEX.findAll(serverHtml).map { it.value }.toList()
        val distinctM3u8s = m3u8Matches.distinct()

        return distinctM3u8s.flatMap { m3u8Url ->
            if (m3u8Url.contains("master.m3u8") || m3u8Url.contains("playlist.m3u8")) {
                try {
                    playlistUtils.extractFromHls(
                        playlistUrl = m3u8Url,
                        referer = link,
                        masterHeaders = headers.newBuilder().set("Referer", link).build(),
                        videoHeaders = headers.newBuilder().set("Referer", link).build(),
                        videoNameGen = { quality -> "$lang - $quality" },
                    )
                } catch (e: Exception) {
                    listOf(
                        Video(
                            videoUrl = m3u8Url,
                            videoTitle = "$lang - Auto",
                            headers = headers.newBuilder().set("Referer", link).build(),
                        ),
                    )
                }
            } else {
                listOf(
                    Video(
                        videoUrl = m3u8Url,
                        videoTitle = "$lang - Auto",
                        headers = headers.newBuilder().set("Referer", link).build(),
                    ),
                )
            }
        }
    }

    private fun extractZephyr(iframeUrl: String, episodeUrl: String, lang: String): List<Video> {
        val zephyrMatch = ZEPHYR_HASH_REGEX.find(iframeUrl) ?: return emptyList()
        val videoId = zephyrMatch.groupValues[1]
        val iframeHeaders = headers.newBuilder()
            .set("Referer", episodeUrl)
            .build()

        val iframeResponse = client.newCall(
            okhttp3.Request.Builder()
                .url(iframeUrl)
                .headers(iframeHeaders)
                .build(),
        ).execute()

        val iframeHtml = iframeResponse.bodyString()
        val cookies = iframeResponse.headers("Set-Cookie")
        val cookieHeader = cookies.joinToString("; ") { it.substringBefore(";") }

        val formBody = okhttp3.FormBody.Builder()
            .add("hash", videoId)
            .add("r", episodeUrl)
            .build()

        val host = try {
            iframeUrl.toHttpUrl().host
        } catch (_: Exception) {
            "play.zephyrix.top"
        }
        val origin = "https://$host"

        val zephyrHeadersBuilder = headers.newBuilder()
            .set("Referer", iframeUrl)
            .set("Origin", origin)
            .set("X-Requested-With", "XMLHttpRequest")
        if (cookieHeader.isNotEmpty()) {
            zephyrHeadersBuilder.set("Cookie", cookieHeader)
        }
        val zephyrHeaders = zephyrHeadersBuilder.build()

        val zephyrResponse = client.newCall(
            okhttp3.Request.Builder()
                .url("$origin/player/index.php?data=$videoId&do=getVideo")
                .post(formBody)
                .headers(zephyrHeaders)
                .build(),
        ).execute()

        val zephyrData = json.decodeFromString<ZephyrResponse>(zephyrResponse.bodyString())
        val streamUrl = zephyrData.videoSource ?: return emptyList()

        val subtitles = mutableListOf<Track>()
        try {
            val subtitleMatch = SUBTITLE_REGEX.find(iframeHtml)
            if (subtitleMatch != null) {
                val subtitleData = subtitleMatch.groupValues[1]
                subtitleData.split("\n").map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                    val partsMatch = SUBTITLE_LINE_REGEX.find(line)
                    if (partsMatch != null) {
                        val langName = partsMatch.groupValues[1]
                        val subUrl = partsMatch.groupValues[2].trim()
                        subtitles.add(Track(subUrl, langName))
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        val streamHeaders = headers.newBuilder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()

        return if (streamUrl.contains(".m3u8")) {
            try {
                playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "$origin/",
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                    videoNameGen = { quality -> "Zephyr - $quality" },
                    subtitleList = subtitles,
                )
            } catch (e: Exception) {
                listOf(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "Zephyr - Auto",
                        headers = streamHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
        } else {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "Zephyr - Auto",
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val lang = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(server, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(lang, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = PREF_SERVER_TITLE,
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_VALUES,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = PREF_TYPE_TITLE,
            entries = PREF_TYPE_ENTRIES,
            entryValues = PREF_TYPE_VALUES,
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to exclude from the video list",
            entries = listOf("Zephyr", "Abyss"),
            entryValues = listOf("Zephyr", "Abyss"),
            default = emptySet(),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Types (Languages)",
            summary = "Select audio languages to exclude from the video list",
            entries = listOf("Hindi", "English", "Japanese"),
            entryValues = listOf("Hindi", "English", "Japanese"),
            default = emptySet(),
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = PREF_SHOW_THUMBNAILS_TITLE,
            summary = PREF_SHOW_THUMBNAILS_SUMMARY,
            default = true,
        )
        screen.addSwitchPreference(
            key = PREF_LOAD_TITLES_KEY,
            title = PREF_LOAD_TITLES_TITLE,
            summary = PREF_LOAD_TITLES_SUMMARY,
            default = true,
        )
        screen.addSwitchPreference(
            key = PREF_LOAD_DESCRIPTIONS_KEY,
            title = PREF_LOAD_DESCRIPTIONS_TITLE,
            summary = PREF_LOAD_DESCRIPTIONS_SUMMARY,
            default = true,
        )
    }

    // ============================ Utilities ===============================

    private fun parseAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val titleElement = element.selectFirst(".entry-title, h2, h3")
        title = titleElement?.text()?.trim() ?: ""

        val linkElement = element.selectFirst("a.lnk-blk, a")
        val relativeUrl = linkElement?.attr("href")?.takeIf { it.startsWith("/") }
            ?: (linkElement?.attr("abs:href") ?: "").substringAfter(baseUrl)

        if (relativeUrl.contains("/episode/")) {
            val slug = relativeUrl.trim('/').substringAfterLast('/')
            val match = EPISODE_SLUG_REGEX.find(slug)
            if (match != null) {
                val animeSlug = match.groupValues[1]
                url = "/series/$animeSlug/"
            } else {
                url = relativeUrl
            }
        } else {
            url = relativeUrl
        }

        val imgElement = element.selectFirst("img")
        thumbnail_url = imgElement?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("abs:src") ?: ""
    }

    companion object {
        private val EPISODE_NUM_REGEX by lazy { Regex("""(\d+)x(\d+)""") }
        private val PLAYER1_REGEX by lazy { Regex("""/api/player1\.php\?data=([A-Za-z0-9+/=]+)""") }
        private val M3U8_REGEX by lazy { Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE) }
        private val ZEPHYR_HASH_REGEX by lazy { Regex("""/video/([a-f0-9]+)""") }
        private val SUBTITLE_REGEX by lazy { Regex("""var playerjsSubtitle = "([^"]+)"""") }
        private val SUBTITLE_LINE_REGEX by lazy { Regex("""\[([^\]]+)\](.+)""") }
        private val EPISODE_SLUG_REGEX by lazy { Regex("""^(.+?)-(\d+)(?:x|-)(\d+)$""", RegexOption.IGNORE_CASE) }

        private val GENRES = listOf(
            Pair("All", ""),
            Pair("Adventure", "adventure"),
            Pair("Drama", "drama"),
            Pair("Historical", "historical"),
            Pair("Romance", "romance"),
        )

        private val LANGUAGES = listOf(
            Pair("All", ""),
            Pair("English", "english"),
            Pair("Hindi", "hindi"),
            Pair("Japanese", "japanese"),
        )

        private val NETWORKS = listOf(
            Pair("All", ""),
            Pair("Netflix", "netflix"),
        )

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred Server"
        private const val PREF_SERVER_DEFAULT = "Zephyr"
        private val PREF_SERVER_ENTRIES = listOf("Zephyr", "Abyss")
        private val PREF_SERVER_VALUES = listOf("Zephyr", "Abyss")

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_TITLE = "Preferred Audio Language"
        private const val PREF_TYPE_DEFAULT = "English"
        private val PREF_TYPE_ENTRIES = listOf("Hindi", "English", "Japanese")
        private val PREF_TYPE_VALUES = listOf("Hindi", "English", "Japanese")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"
        private const val PREF_SHOW_THUMBNAILS_TITLE = "Show episode thumbnails"
        private const val PREF_SHOW_THUMBNAILS_SUMMARY = "Fetch and display preview images in the episode list."

        private const val PREF_LOAD_TITLES_KEY = "pref_load_titles"
        private const val PREF_LOAD_TITLES_TITLE = "Enrich episode titles"
        private const val PREF_LOAD_TITLES_SUMMARY = "Fetch episode titles from AniList/TMDB/Kitsu"

        private const val PREF_LOAD_DESCRIPTIONS_KEY = "pref_load_descriptions"
        private const val PREF_LOAD_DESCRIPTIONS_TITLE = "Enrich episode descriptions & summaries"
        private const val PREF_LOAD_DESCRIPTIONS_SUMMARY = "Fetch per-episode synopses & descriptions from AniList/TMDB/Kitsu"
    }
}

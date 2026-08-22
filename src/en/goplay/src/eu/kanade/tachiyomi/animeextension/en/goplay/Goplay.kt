package eu.kanade.tachiyomi.animeextension.en.goplay

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Goplay :
    Source(),
    ConfigurableAnimeSource {

    override val name = "GoPlay"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.cloudflareClient.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // Shared Video Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/popular" else "$baseUrl/popular?page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/?page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            if (page == 1) "$baseUrl/search?q=$encodedQuery" else "$baseUrl/search?q=$encodedQuery&page=$page"
        } else {
            var category = ""
            var country = ""
            var type = ""
            var status = ""
            var sort = ""
            var year = ""
            val genres = mutableListOf<String>()

            filters.forEach { filter ->
                when (filter) {
                    is Filters.CategoryFilter -> if (!filter.isDefault()) category = filter.toUriPart()
                    is Filters.CountryFilter -> if (!filter.isDefault()) country = filter.toUriPart()
                    is Filters.TypeFilter -> if (!filter.isDefault()) type = filter.toUriPart()
                    is Filters.StatusFilter -> if (!filter.isDefault()) status = filter.toUriPart()
                    is Filters.SortFilter -> if (!filter.isDefault()) sort = filter.toUriPart()
                    is Filters.YearFilter -> if (filter.state.isNotBlank()) year = filter.state.trim()
                    is Filters.GenreFilter -> genres.addAll(filter.getIncluded())
                    else -> {}
                }
            }

            val builder = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
                if (category.isNotBlank()) addQueryParameter("category", category)
                if (country.isNotBlank()) addQueryParameter("country", country)
                if (type.isNotBlank()) addQueryParameter("type", type)
                if (status.isNotBlank()) addQueryParameter("status", status)
                if (sort.isNotBlank()) addQueryParameter("sort", sort)
                if (year.isNotBlank()) addQueryParameter("year", year)
                genres.forEach { addQueryParameter("genre[]", it) }
                if (page > 1) addQueryParameter("page", page.toString())
            }
            builder.build().toString()
        }

        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.CategoryFilter(),
        Filters.CountryFilter(),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SortFilter(),
        Filters.YearFilter(),
        AnimeFilter.Separator(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val elements = doc.select(
            "div#indexepisodes, div.indexepisodes, #indexepisodelist > div, div.anime-card, div.film-item, div.drama-card, div.item, ul.items > li, .content_left ul.items li, .list-drama li, .drama-box, div.col-item, .video-block",
        )

        val animes = elements.mapNotNull { element ->
            val a = element.selectFirst("#indexepisodeimage a, a[href]") ?: return@mapNotNull null
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#") return@mapNotNull null

            val titleText = element.selectFirst("#indexepisodetitle, h2.title, h3.title, a.title, .film-name, .name a, .name, .title")?.text()?.ifBlank { null }
                ?: a.attr("title").ifBlank { null }
                ?: a.text().ifBlank { return@mapNotNull null }

            val img = element.selectFirst("#indexepisodeimage img, img")
            val thumb = img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("data-original")?.ifBlank { null }
                ?: img?.absUrl("src")?.takeIf { !it.startsWith("data:") }

            SAnime.create().apply {
                title = titleText.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = thumb
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.selectFirst("a.next-page, a.next, .pagination-next, div.pagination li.next a, div.pagination a:contains(>)") != null ||
            (animes.size >= 24 && doc.selectFirst("div.pagination, ul.pagination") == null)

        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val titleText = doc.selectFirst("div#dramatitle, h1.title, .drama-title, h1")?.text()?.substringBefore(" (Episode")?.trim() ?: anime.title
        val synopsis = doc.selectFirst("div#description, div.description, .synopsis, .film-description, .content-desc, .details-info p, #info, .info")?.text() ?: ""
        val score = doc.selectFirst("span.score, .rating, .imdb-rate, span#rating")?.text()?.toDoubleOrNull()
        val statusRaw = doc.selectFirst("span.status, .film-status, span:contains(Status), #dramastatus")?.text() ?: ""
        val country = doc.selectFirst(".country a, span:contains(Country) + a, span:contains(Country)")?.text()

        return SAnime.create().apply {
            title = titleText.ifBlank { anime.title }
            thumbnail_url = anime.thumbnail_url
            genre = doc.select("div.genres a, .genre a, a[href*='/genre/'], #dramagenre a").joinToString { it.text() }
            author = country
            status = when {
                statusRaw.contains("Ongoing", ignoreCase = true) || statusRaw.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
                statusRaw.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                if (score != null && score > 0.0) {
                    val stars = (score / 2).toInt().coerceIn(0, 5)
                    append("★".repeat(stars) + "☆".repeat(5 - stars) + " " + "%.2f".format(score) + "\n\n")
                }
                if (synopsis.isNotBlank()) append(synopsis)
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val epElements = doc.select(
            "div#episodediv > div, div#episodesodd, div#episodeseven, ul.episodes > li, div.episode-item, .episodes-list a, ul.all-episode li, .list-episode-item a, .episode-list li, div#episodes a",
        )

        val episodes = epElements.mapIndexedNotNull { idx, element ->
            val link = element.selectFirst("a[href]") ?: element.takeIf { it.tagName() == "a" && it.hasAttr("href") }
            val rawHref = link?.attr("href")?.ifBlank { null }
                ?: element.attr("data-href").ifBlank { null }
                ?: "${anime.url}#ep=${idx + 1}"

            val nameText = element.selectFirst("#episodesnumber, span.name, a.title, .ep-title, .title")?.text()
                ?: link?.text()?.ifBlank { null }
                ?: "Episode ${idx + 1}"

            val epNum = element.selectFirst("span.num, .ep-num")?.text()?.toFloatOrNull()
                ?: Regex("""(?:Episode|Ep\.?|EP\.?|E)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(nameText)?.groupValues?.get(1)?.toFloatOrNull()
                ?: (idx + 1).toFloat()

            val hasSub = element.selectFirst(".sub-badge, [data-sub='1'], span:contains(Sub)") != null || nameText.contains("Sub", ignoreCase = true)
            val hasDub = element.selectFirst(".dub-badge, [data-dub='1'], span:contains(Dub)") != null || nameText.contains("Dub", ignoreCase = true)
            val scanlatorText = when {
                hasSub && hasDub -> "Sub / Dub"
                hasDub -> "Dub"
                hasSub -> "Sub"
                else -> null
            }

            val dateStr = element.selectFirst("span.date, .ep-date, .time")?.text() ?: ""
            val uploadDate = if (dateStr.isNotBlank()) {
                runCatching { DATE_FORMAT.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
            } else {
                0L
            }

            SEpisode.create().apply {
                setUrlWithoutDomain(rawHref)
                name = nameText.trim()
                episode_number = epNum
                scanlator = scanlatorText
                date_upload = uploadDate
            }
        }.distinctBy { it.url }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.newCall(GET("$baseUrl${episode.url}", headers)).execute().asJsoup()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()

        val providerMap = mutableMapOf<String, MutableList<Pair<String, String>>>()

        // 1. Direct GoPlay Stream / Dash / MPD or HLS
        val html = doc.html()
        val mpdMatch = Regex("""/stream/(?:stream_dash_v3\.mpd|stream\.mpd)\?d=([a-zA-Z0-9+/=_%-]+)""").find(html)
            ?: Regex("""(?:stream_dash_v3\.mpd|stream\.mpd)\?d=([a-zA-Z0-9+/=_%-]+)""").find(html)
        val hlsMatch = Regex("""/stream/(?:stream_hls\.m3u8|stream\.m3u8)\?d=([a-zA-Z0-9+/=_%-]+)""").find(html)
            ?: Regex("""(?:stream_hls\.m3u8|stream\.m3u8)\?d=([a-zA-Z0-9+/=_%-]+)""").find(html)

        if (mpdMatch != null && "GoPlay DASH" !in excludedServers) {
            val mpdUrl = if (mpdMatch.value.startsWith("http")) mpdMatch.value else "$baseUrl${if (mpdMatch.value.startsWith("/")) "" else "/"}${mpdMatch.value}"
            providerMap.getOrPut("GoPlay DASH") { mutableListOf() }.add(Pair("SUB", mpdUrl))
        }

        if (hlsMatch != null && "GoPlay HLS" !in excludedServers) {
            val hlsUrl = if (hlsMatch.value.startsWith("http")) hlsMatch.value else "$baseUrl${if (hlsMatch.value.startsWith("/")) "" else "/"}${hlsMatch.value}"
            providerMap.getOrPut("GoPlay HLS") { mutableListOf() }.add(Pair("SUB", hlsUrl))
        }

        // 2. Scan iframes and embed elements
        doc.select("iframe[src], [data-embed], [data-src], iframe.player-iframe").forEachIndexed { idx, iframe ->
            val embedUrl = iframe.absUrl("src").ifBlank { iframe.attr("data-embed") }.ifBlank { iframe.attr("data-src") }
            if (embedUrl.isNotBlank() && !embedUrl.startsWith("javascript:")) {
                val serverName = iframe.attr("data-server-name").ifBlank {
                    iframe.parent()?.attr("data-server") ?: "Server ${idx + 1}"
                }
                val audioType = iframe.attr("data-audio-type").ifBlank { "SUB" }.uppercase()
                if (serverName !in excludedServers) {
                    providerMap.getOrPut(serverName) { mutableListOf() }.add(Pair(audioType, embedUrl))
                }
            }
        }

        // 3. Scan server tabs / buttons
        doc.select("ul.servers-list li, .server-item, .server-btn, [data-server-id], #sourceid option").forEachIndexed { idx, btn ->
            val serverName = btn.text().ifBlank { btn.attr("data-server-name") }.ifBlank { "Server ${idx + 1}" }
            val serverEmbed = btn.attr("data-embed").ifBlank { btn.attr("data-src") }.ifBlank { btn.attr("data-url") }.ifBlank { btn.attr("value") }
            if (serverEmbed.isNotBlank() && serverEmbed != "0" && serverName !in excludedServers) {
                providerMap.getOrPut(serverName) { mutableListOf() }.add(Pair("SUB", serverEmbed))
            }
        }

        // 4. Fallback: video source tags
        doc.select("video source[src], video[src]").forEachIndexed { idx, v ->
            val vSrc = v.absUrl("src").ifBlank { v.attr("src") }
            if (vSrc.isNotBlank()) {
                val serverName = "Direct Stream ${idx + 1}"
                if (serverName !in excludedServers) {
                    providerMap.getOrPut(serverName) { mutableListOf() }.add(Pair("SUB", vSrc))
                }
            }
        }

        if (providerMap.isEmpty()) {
            providerMap["GoPlay Web Stream"] = mutableListOf(Pair("SUB", "$baseUrl${episode.url}"))
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return providerMap.map { (providerName, audioList) ->
            Hoster(
                hosterName = providerName,
                hosterUrl = audioList.joinToString(";;") { "${it.first}|${it.second}" },
            )
        }.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()

        val audioEntries = hoster.hosterUrl.split(";;").mapNotNull { item ->
            val parts = item.split("|")
            if (parts.size >= 2) Pair(parts[0], parts[1]) else null
        }

        val videoList = audioEntries.parallelCatchingFlatMap { (audioType, embedUrl) ->
            if (audioType.uppercase() in excludedAudios) return@parallelCatchingFlatMap emptyList()

            val extractedVideos = when {
                embedUrl.contains(".mpd") -> {
                    runCatching {
                        playlistUtils.extractFromDash(
                            mpdUrl = embedUrl,
                            videoNameGen = { quality -> "$quality [$audioType]" },
                            referer = "$baseUrl/",
                        )
                    }.getOrElse {
                        listOf(
                            Video(
                                videoUrl = embedUrl,
                                videoTitle = "GoPlay - DASH [$audioType]",
                                headers = embedHeaders,
                            ),
                        )
                    }
                }

                embedUrl.contains("dood") || embedUrl.contains("ds2play") ->
                    doodExtractor.videosFromUrl(embedUrl)

                embedUrl.contains("streamtape") ->
                    streamtapeExtractor.videoFromUrl(embedUrl)?.let { listOf(it) } ?: emptyList()

                embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") ->
                    filemoonExtractor.videosFromUrl(embedUrl, prefix = "${hoster.hosterName} - ", headers = embedHeaders)

                embedUrl.endsWith(".m3u8") || embedUrl.contains(".m3u8?") ->
                    playlistUtils.extractFromHls(
                        playlistUrl = embedUrl,
                        referer = "$baseUrl/",
                        videoNameGen = { quality -> "$quality [$audioType]" },
                    )

                else ->
                    universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "${hoster.hosterName} - ")
            }

            extractedVideos.map { video ->
                val baseTitle = video.videoTitle.replace(Regex("\\s*\\[(?:SUB|DUB|Soft-Sub|RAW)\\]", RegexOption.IGNORE_CASE), "").trim()
                val finalTitle = if (baseTitle.isNotBlank()) "$baseTitle [$audioType]" else "HD [$audioType]"
                Video(
                    videoUrl = video.videoUrl,
                    videoTitle = finalTitle,
                    headers = video.headers ?: embedHeaders,
                    resolution = video.resolution,
                    subtitleTracks = video.subtitleTracks,
                )
            }
        }

        return videoList.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefType, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_BASE_URL_KEY,
            title = "Base URL / Mirror",
            default = PREF_BASE_URL_DEFAULT,
            summary = "%s",
            entries = listOf("GoPlay (.su)", "GoPlay (.ml)"),
            entryValues = listOf("https://goplay.su", "https://goplay.ml"),
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio Type",
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
            entries = listOf("Sub", "Dub", "Raw"),
            entryValues = listOf("SUB", "DUB", "RAW"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Auto", "Server 1", "Server 2", "StreamTape", "DoodStream", "FileMoon"),
            entryValues = listOf("auto", "Server 1", "Server 2", "StreamTape", "DoodStream", "FileMoon"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from playback",
            entries = listOf("StreamTape", "DoodStream", "FileMoon"),
            entryValues = listOf("StreamTape", "DoodStream", "FileMoon"),
            default = emptySet(),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Audio Types",
            summary = "Select audio formats to hide",
            entries = listOf("Sub", "Dub", "Raw"),
            entryValues = listOf("SUB", "DUB", "RAW"),
            default = emptySet(),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://goplay.su"
        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = "SUB"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

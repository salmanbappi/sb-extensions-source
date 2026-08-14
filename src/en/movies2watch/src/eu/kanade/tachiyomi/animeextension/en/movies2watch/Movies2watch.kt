package eu.kanade.tachiyomi.animeextension.en.movies2watch

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
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

class Movies2watch : Source() {

    override val name = "Movies2Watch"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Referer", "$baseUrl/")

    private val ajaxHeaders: Headers by lazy {
        headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    // Shared Video Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/filter.php?sort=rating&order=desc&page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = "$baseUrl/filter.php?sort=updated&order=desc&page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val builder = "$baseUrl/filter.php".toHttpUrl().newBuilder()

        var typeVal = "all"
        var genreVal = ""
        var countryVal = ""
        var yearVal = ""
        var ratingVal = ""
        var sortVal = "updated"
        var orderVal = "desc"

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> typeVal = filter.toUriPart()
                is Filters.GenreFilter -> genreVal = filter.toUriPart()
                is Filters.CountryFilter -> countryVal = filter.toUriPart()
                is Filters.YearFilter -> yearVal = filter.toUriPart()
                is Filters.RatingFilter -> ratingVal = filter.toUriPart()
                is Filters.SortFilter -> sortVal = filter.toUriPart()
                is Filters.OrderFilter -> orderVal = filter.toUriPart()
                else -> {}
            }
        }

        builder.addQueryParameter("type", typeVal)
        if (query.isNotBlank()) {
            builder.addQueryParameter("keyword", query.trim())
        }
        if (genreVal.isNotBlank()) {
            builder.addQueryParameter("genre", genreVal)
        }
        if (countryVal.isNotBlank()) {
            builder.addQueryParameter("country", countryVal)
        }
        if (yearVal.isNotBlank()) {
            builder.addQueryParameter("year", yearVal)
        }
        if (ratingVal.isNotBlank()) {
            builder.addQueryParameter("rating", ratingVal)
        }
        builder.addQueryParameter("sort", sortVal)
        builder.addQueryParameter("order", orderVal)
        builder.addQueryParameter("page", page.toString())

        val response = client.newCall(GET(builder.build(), headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.GenreFilter(),
        Filters.CountryFilter(),
        Filters.YearFilter(),
        Filters.RatingFilter(),
        Filters.SortFilter(),
        Filters.OrderFilter(),
    )

    private fun parseAnimeListPage(response: Response, currentPage: Int): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(".flw-item, .film_list-wrap .flw-item").mapNotNull { element ->
            val linkEl = element.selectFirst(".film-detail .film-name a, .film-poster a") ?: return@mapNotNull null
            val href = linkEl.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            val rawTitle = linkEl.attr("title").ifBlank { linkEl.text().trim() }
            val imgEl = element.selectFirst(".film-poster img")
            val posterUrl = imgEl?.attr("data-src")?.ifBlank { imgEl.attr("src") }
                ?: imgEl?.attr("src")
                ?: ""

            SAnime.create().apply {
                title = rawTitle
                setUrlWithoutDomain(href)
                thumbnail_url = posterUrl
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.select(".pagination .page-item a.page-link").any { link ->
            val href = link.attr("href")
            val pageNum = href.substringAfter("/page/").substringBefore("/").toIntOrNull()
                ?: href.substringAfter("page=").substringBefore("&").toIntOrNull()
            pageNum != null && pageNum > currentPage
        } || doc.select(".pagination li.active + li").isNotEmpty()

        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val synopsis = doc.selectFirst(".film-description, .f-desc, .description, .detail_page-infor .text")?.text()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
            ?: ""

        val rawTitle = doc.selectFirst("h2.heading-name, h1.heading-name, .heading-name, .detail_page-infor h2, h3.film-name")?.text()
            ?: anime.title

        val rawPoster = doc.selectFirst(".film-poster img")?.attr("data-src")?.ifBlank {
            doc.selectFirst(".film-poster img")?.attr("src")
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content") ?: anime.thumbnail_url

        val genres = doc.select(".item-list a[href*=/genre/], .genres a, .genre a").joinToString(", ") { it.text().trim() }

        return SAnime.create().apply {
            title = rawTitle
            thumbnail_url = rawPoster
            genre = genres
            status = SAnime.COMPLETED
            description = synopsis.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = anime.url
        if (animeUrl.contains("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    url = animeUrl
                    name = "Full Movie"
                    episode_number = 1.0f
                },
            )
        }

        val response = client.newCall(GET("$baseUrl$animeUrl", headers)).execute()
        val doc = response.asJsoup()
        val html = doc.html()

        val seasonItems = doc.select(".slt-seasons-dropdown .ss-item, .dropdown-menu .ss-item")
        val episodes = mutableListOf<SEpisode>()

        if (seasonItems.isNotEmpty()) {
            seasonItems.forEachIndexed { seasonIdx, seasonEl ->
                val seasonId = seasonEl.attr("data-id")
                val seasonNumStr = seasonEl.attr("data-ss").ifBlank { (seasonIdx + 1).toString() }
                val seasonNum = seasonNumStr.toIntOrNull() ?: (seasonIdx + 1)
                val seasonName = seasonEl.text().trim().ifBlank { "Season $seasonNum" }

                if (seasonId.isNotBlank()) {
                    runCatching {
                        val epResp = client.newCall(
                            GET("$baseUrl/ajax/ajax.php?episode=$seasonId", ajaxHeaders),
                        ).execute()
                        val epDoc = epResp.asJsoup()
                        epDoc.select("li a.eps-item, a.eps-item").forEachIndexed { epIdx, epEl ->
                            val epHref = epEl.attr("href").trim()
                            if (epHref.isNotBlank()) {
                                val epTitle = epEl.attr("title").ifBlank { epEl.text().trim() }
                                val epNum = Regex("""(?:Eps|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
                                    .find(epTitle)?.groupValues?.get(1)?.toFloatOrNull()
                                    ?: (epIdx + 1).toFloat()

                                episodes.add(
                                    SEpisode.create().apply {
                                        setUrlWithoutDomain(epHref)
                                        name = "$seasonName - $epTitle"
                                        episode_number = (seasonNum * 1000 + epNum.toInt()).toFloat()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            val currentUrlMatch = Regex("""const\s+current_url\s*=\s*['"]([^'"]+)['"]""").find(html)
            if (currentUrlMatch != null) {
                val currentUrl = currentUrlMatch.groupValues[1]
                runCatching {
                    val epResp = client.newCall(GET(currentUrl, ajaxHeaders)).execute()
                    val epDoc = epResp.asJsoup()
                    epDoc.select("li a.eps-item, a.eps-item").forEachIndexed { epIdx, epEl ->
                        val epHref = epEl.attr("href").trim()
                        if (epHref.isNotBlank()) {
                            val epTitle = epEl.attr("title").ifBlank { epEl.text().trim() }
                            val epNum = Regex("""(?:Eps|Episode)\s*(\d+)""", RegexOption.IGNORE_CASE)
                                .find(epTitle)?.groupValues?.get(1)?.toFloatOrNull()
                                ?: (epIdx + 1).toFloat()

                            episodes.add(
                                SEpisode.create().apply {
                                    setUrlWithoutDomain(epHref)
                                    name = epTitle
                                    episode_number = epNum
                                },
                            )
                        }
                    }
                }
            }
        }

        if (episodes.isEmpty()) {
            return listOf(
                SEpisode.create().apply {
                    url = animeUrl
                    name = "Episode 1"
                    episode_number = 1.0f
                },
            )
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val epDoc = client.newCall(GET("$baseUrl${episode.url}", headers)).execute().asJsoup()
        val html = epDoc.html()

        val plUrlMatch = Regex("""const\s+pl_url\s*=\s*['"]([^'"]+)['"]""").find(html)
            ?: Regex("""pl_url\s*=\s*['"]([^'"]+)['"]""").find(html)

        val hosters = mutableListOf<Hoster>()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()

        if (plUrlMatch != null) {
            val plUrl = plUrlMatch.groupValues[1]
            runCatching {
                val srvResp = client.newCall(GET(plUrl, ajaxHeaders)).execute()
                val srvDoc = srvResp.asJsoup()
                srvDoc.select(".sv-item, a[data-srv]").forEach { el ->
                    val srvName = el.attr("data-srv").ifBlank { el.text().trim() }
                    val srvUrl = el.attr("data-id").trim()
                    if (srvUrl.isNotBlank() && srvName !in excludedServers) {
                        hosters.add(Hoster(hosterName = srvName, hosterUrl = srvUrl))
                    }
                }
            }
        }

        if (hosters.isEmpty()) {
            epDoc.select("iframe[src], iframe[data-src]").forEachIndexed { idx, iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank()) {
                    hosters.add(Hoster(hosterName = "Server ${idx + 1}", hosterUrl = src))
                }
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl
        val prefix = "${hoster.hosterName} - "

        return runCatching {
            val resolvedUrl = runCatching {
                val req = GET(rawUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())
                client.newCall(req).execute().use { resp ->
                    resp.request.url.toString()
                }
            }.getOrDefault(rawUrl)

            val embedHeaders = headers.newBuilder()
                .set("Referer", resolvedUrl)
                .build()

            when {
                resolvedUrl.contains("vidmoly", ignoreCase = true) || rawUrl.contains("vidmoly", ignoreCase = true) -> {
                    runCatching { vidmolyExtractor.videosFromUrl(resolvedUrl, prefix) }
                        .getOrElse { vidmolyExtractor.videosFromUrl(rawUrl, prefix) }
                }

                resolvedUrl.contains("filemoon", ignoreCase = true) || resolvedUrl.contains("moonplayer", ignoreCase = true) -> {
                    runCatching { filemoonExtractor.videosFromUrl(resolvedUrl, prefix = prefix, headers = embedHeaders) }
                        .getOrElse { filemoonExtractor.videosFromUrl(rawUrl, prefix = prefix, headers = embedHeaders) }
                }

                resolvedUrl.contains("streamtape", ignoreCase = true) -> {
                    streamtapeExtractor.videoFromUrl(resolvedUrl)?.let(::listOf)
                        ?: streamtapeExtractor.videoFromUrl(rawUrl)?.let(::listOf)
                        ?: emptyList()
                }

                resolvedUrl.contains("dood", ignoreCase = true) || resolvedUrl.contains("ds2play", ignoreCase = true) -> {
                    runCatching { doodExtractor.videosFromUrl(resolvedUrl) }
                        .getOrElse { doodExtractor.videosFromUrl(rawUrl) }
                }

                resolvedUrl.contains(".m3u8") -> {
                    playlistUtils.extractFromHls(
                        playlistUrl = resolvedUrl,
                        referer = resolvedUrl,
                        videoNameGen = { quality -> "$prefix$quality" },
                    )
                }

                else -> {
                    universalExtractor.videosFromUrl(resolvedUrl, embedHeaders, prefix = hoster.hosterName)
                }
            }.ifEmpty {
                universalExtractor.videosFromUrl(resolvedUrl, embedHeaders, prefix = hoster.hosterName)
            }.ifEmpty {
                universalExtractor.videosFromUrl(rawUrl, headers.newBuilder().set("Referer", "$baseUrl/").build(), prefix = hoster.hosterName)
            }
        }.getOrDefault(emptyList())
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Auto", "UpCloud", "Vidmoly", "Videasy", "Vidsrc", "Vidfast"),
            entryValues = listOf("auto", "UpCloud", "Vidmoly", "Videasy", "Vidsrc", "Vidfast"),
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
            entries = listOf("UpCloud", "Vidmoly", "Videasy", "Vidsrc", "Vidfast"),
            entryValues = listOf("UpCloud", "Vidmoly", "Videasy", "Vidsrc", "Vidfast"),
            default = emptySet(),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://movies2watch.vc"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
    }
}

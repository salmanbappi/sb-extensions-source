package eu.kanade.tachiyomi.animeextension.en.movies2watch

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.byseextractor.ByseExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    // Shared Video Extractors (Pure Native)
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val byseExtractor by lazy { ByseExtractor(client, playlistUtils) }
    private val videasyExtractor by lazy { VideasyExtractor(client, playlistUtils) }

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

                                val epNumber = if (seasonNum > 1) {
                                    ((seasonNum - 1) * 100 + epNum.toInt()).toFloat()
                                } else {
                                    epNum
                                }

                                episodes.add(
                                    SEpisode.create().apply {
                                        setUrlWithoutDomain(epHref)
                                        name = "$seasonName - $epTitle"
                                        episode_number = epNumber
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

                    // Exclude non-working embeds (Leave Vidsrc as-is)
                    val isBroken = srvName.contains("vidsrc", ignoreCase = true) ||
                        srvUrl.contains("vidsrc", ignoreCase = true)

                    if (srvUrl.isNotBlank() && !isBroken && srvName !in excludedServers) {
                        hosters.add(Hoster(hosterName = srvName, hosterUrl = srvUrl))
                    }
                }
            }
        }

        if (hosters.isEmpty()) {
            epDoc.select("iframe[src], iframe[data-src]").forEachIndexed { idx, iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                val isBroken = src.contains("vidsrc", ignoreCase = true)
                if (src.isNotBlank() && !isBroken) {
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
                    val loc = resp.header("Location")
                    if (!loc.isNullOrBlank()) loc else resp.request.url.toString()
                }
            }.getOrDefault(rawUrl)

            val embedHeaders = headers.newBuilder()
                .set("Referer", resolvedUrl)
                .build()

            when {
                // Vidmoly: Pure native HLS extraction with multi-language subtitle tracks attached
                rawUrl.contains("/vmf/") || rawUrl.contains("/vms/") || resolvedUrl.contains("vidmoly", true) || resolvedUrl.contains("kaembed", true) || hoster.hosterName.contains("Vidmoly", true) -> {
                    extractVidmoly(rawUrl, resolvedUrl, prefix, embedHeaders)
                }

                // Videasy: Native API + custom Murmur3 keystream decryption with HLS & subtitles
                rawUrl.contains("videasy", true) || resolvedUrl.contains("videasy", true) || hoster.hosterName.contains("Videasy", true) -> {
                    extractVideasy(rawUrl, resolvedUrl, prefix, embedHeaders, hoster.hosterName)
                }

                // UpCloud: Pure native Byse AES-GCM decryption & HLS extraction with subtitles
                rawUrl.contains("/mv/") || rawUrl.contains("/pl/") || resolvedUrl.contains("gn1r5n.org") || hoster.hosterName.contains("UpCloud", true) -> {
                    extractUpCloud(rawUrl, resolvedUrl, prefix, embedHeaders, hoster.hosterName)
                }

                // Vidfast: Direct HLS playlist resolution with subtitles
                rawUrl.contains("vidfast", true) || resolvedUrl.contains("vidfast", true) || hoster.hosterName.contains("Vidfast", true) -> {
                    extractVidfast(rawUrl, resolvedUrl, prefix, embedHeaders, hoster.hosterName)
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

                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    // ============================ Vidmoly Extractor ========================
    private suspend fun extractVidmoly(
        rawUrl: String,
        resolvedUrl: String,
        prefix: String,
        embedHeaders: Headers,
    ): List<Video> {
        val targetUrl = if (resolvedUrl.contains("kaembed", true) || resolvedUrl.contains("vidmoly", true)) resolvedUrl else rawUrl
        val subUrl = runCatching {
            resolvedUrl.toHttpUrl().queryParameter("subget")
                ?: resolvedUrl.toHttpUrl().queryParameter("sub.info")
                ?: rawUrl.toHttpUrl().queryParameter("subget")
                ?: rawUrl.toHttpUrl().queryParameter("sub.info")
        }.getOrNull()

        val subTracks = fetchSubtitles(subUrl)

        // Try direct HTML extraction from kaembed/vidmoly
        val directVideos = runCatching {
            val resp = client.newCall(GET(targetUrl, embedHeaders)).execute()
            val html = resp.body.string()
            val m3u8Match = Regex("""(?:file|src)\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(html)
                ?: Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(html)
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = targetUrl,
                    masterHeaders = embedHeaders,
                    videoHeaders = embedHeaders,
                    videoNameGen = { q -> "$prefix$q" },
                    subtitleList = subTracks,
                )
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        if (directVideos.isNotEmpty()) {
            return directVideos
        }

        val videos = runCatching { vidmolyExtractor.videosFromUrl(resolvedUrl, prefix) }
            .getOrElse {
                runCatching { vidmolyExtractor.videosFromUrl(rawUrl, prefix) }
                    .getOrDefault(emptyList())
            }

        return videos.map { v ->
            Video(
                videoUrl = v.videoUrl,
                videoTitle = v.videoTitle,
                headers = v.headers ?: embedHeaders,
                subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy { it.url },
                audioTracks = v.audioTracks,
            )
        }
    }

    // ============================ Videasy Extractor ========================
    private suspend fun extractVideasy(
        rawUrl: String,
        resolvedUrl: String,
        prefix: String,
        embedHeaders: Headers,
        hosterName: String,
    ): List<Video> = videasyExtractor.extract(resolvedUrl, title = "", prefix = prefix)
        .ifEmpty { videasyExtractor.extract(rawUrl, title = "", prefix = prefix) }

    // ============================ UpCloud Extractor ========================
    private suspend fun extractUpCloud(
        rawUrl: String,
        resolvedUrl: String,
        prefix: String,
        embedHeaders: Headers,
        hosterName: String,
    ): List<Video> {
        val subUrl = runCatching {
            resolvedUrl.toHttpUrl().queryParameter("sub.info")
                ?: resolvedUrl.toHttpUrl().queryParameter("subget")
                ?: rawUrl.toHttpUrl().queryParameter("sub.info")
                ?: rawUrl.toHttpUrl().queryParameter("subget")
        }.getOrNull()

        val subTracks = fetchSubtitles(subUrl)

        // Try ByseExtractor for /e/ embed endpoints
        if (resolvedUrl.contains("/e/")) {
            val byseVideos = runCatching {
                byseExtractor.videosFromUrl(resolvedUrl, prefix = prefix, headers = embedHeaders)
            }.getOrDefault(emptyList())

            if (byseVideos.isNotEmpty()) {
                return byseVideos.map { v ->
                    Video(
                        videoUrl = v.videoUrl,
                        videoTitle = v.videoTitle,
                        headers = v.headers ?: embedHeaders,
                        subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy { it.url },
                        audioTracks = v.audioTracks,
                    )
                }
            }
        }

        // Direct HTML / HLS source parsing fallback
        return runCatching {
            val targetUrl = if (resolvedUrl.startsWith("http")) resolvedUrl else rawUrl
            val resp = client.newCall(GET(targetUrl, embedHeaders)).execute()
            val html = resp.body.string()
            val m3u8Match = Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(html)
                ?: Regex("""source\s*src\s*=\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(html)
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                val hlsList = playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = targetUrl,
                    videoNameGen = { q -> "$prefix$q" },
                    subtitleList = subTracks,
                )
                hlsList.map { v ->
                    Video(
                        videoUrl = v.videoUrl,
                        videoTitle = v.videoTitle,
                        headers = v.headers ?: embedHeaders,
                        subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy { it.url },
                        audioTracks = v.audioTracks,
                    )
                }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    // ============================ Vidfast Extractor ========================
    private suspend fun extractVidfast(
        rawUrl: String,
        resolvedUrl: String,
        prefix: String,
        embedHeaders: Headers,
        hosterName: String,
    ): List<Video> {
        val targetUrl = if (resolvedUrl.contains("vidfast", ignoreCase = true)) resolvedUrl else rawUrl
        val httpUri = runCatching { targetUrl.toHttpUrl() }.getOrNull()
        val pathSegments = httpUri?.pathSegments.orEmpty()
        val isMovie = targetUrl.contains("/movie/")
        val id = pathSegments.getOrNull(1) ?: ""
        val season = if (!isMovie) pathSegments.getOrNull(2) ?: "1" else "1"
        val ep = if (!isMovie) pathSegments.getOrNull(3) ?: "1" else "1"

        val subTracks = mutableListOf<Track>()
        if (id.isNotBlank()) {
            val wyzieUrl = if (isMovie) {
                "https://vidfast.pro/wyzie?id=$id"
            } else {
                "https://vidfast.pro/wyzie?id=$id&season=$season&episode=$ep"
            }
            runCatching {
                val subResp = client.newCall(GET(wyzieUrl, embedHeaders)).execute()
                if (subResp.isSuccessful) {
                    val jsonArray = Json.parseToJsonElement(subResp.body.string()).jsonArray
                    jsonArray.forEach { el ->
                        val obj = el.jsonObject
                        val file = obj["url"]?.jsonPrimitive?.content ?: obj["file"]?.jsonPrimitive?.content ?: return@forEach
                        val label = obj["display"]?.jsonPrimitive?.content ?: obj["label"]?.jsonPrimitive?.content ?: obj["language"]?.jsonPrimitive?.content ?: "Sub"
                        if (file.isNotBlank()) {
                            subTracks.add(Track(file, label))
                        }
                    }
                }
            }
        }

        return runCatching {
            val html = client.newCall(GET(targetUrl, embedHeaders)).execute().body.string()
            val m3u8Match = Regex("""['"]([^'"]+\.m3u8[^'"]*)['"]""").find(html)
            if (m3u8Match != null) {
                val m3u8Url = m3u8Match.groupValues[1]
                val hlsList = playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = targetUrl,
                    videoNameGen = { q -> "$prefix$q" },
                    subtitleList = subTracks,
                )
                hlsList.map { v ->
                    Video(
                        videoUrl = v.videoUrl,
                        videoTitle = v.videoTitle,
                        headers = v.headers ?: embedHeaders,
                        subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy { it.url },
                        audioTracks = v.audioTracks,
                    )
                }
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchSubtitles(subUrl: String?): List<Track> {
        if (subUrl.isNullOrBlank()) return emptyList()
        val tracks = mutableListOf<Track>()
        runCatching {
            val subResp = client.newCall(GET(subUrl, headers)).execute()
            if (subResp.isSuccessful) {
                val jsonArray = Json.parseToJsonElement(subResp.body.string()).jsonArray
                jsonArray.forEach { el ->
                    val obj = el.jsonObject
                    val file = obj["file"]?.jsonPrimitive?.content ?: return@forEach
                    val label = obj["label"]?.jsonPrimitive?.content ?: "Sub"
                    if (file.isNotBlank()) {
                        tracks.add(Track(file, label))
                    }
                }
            }
        }
        return tracks
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
            entries = listOf("Auto", "UpCloud", "Vidmoly", "Videasy", "Vidfast"),
            entryValues = listOf("auto", "UpCloud", "Vidmoly", "Videasy", "Vidfast"),
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
            entries = listOf("UpCloud", "Vidmoly", "Videasy", "Vidfast", "Vidsrc"),
            entryValues = listOf("UpCloud", "Vidmoly", "Videasy", "Vidfast", "Vidsrc"),
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

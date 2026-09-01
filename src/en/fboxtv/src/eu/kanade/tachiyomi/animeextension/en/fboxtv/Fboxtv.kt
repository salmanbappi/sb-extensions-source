package eu.kanade.tachiyomi.animeextension.en.fboxtv

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import extensions.utils.parseAs
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

class Fboxtv : Source() {

    override val name = "FboxTV"

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
        .set("User-Agent", USER_AGENT)
        .set("Referer", "$baseUrl/")

    private val ajaxHeaders: Headers by lazy {
        headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .add("Accept", "*/*")
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/top-imdb?page=$page", headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/filter?type=all&page=$page", headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            // The site's own search form posts to /search/<query-with-dashes>.
            val slug = query.trim().replace(Regex("""\s+"""), "-")
            "$baseUrl/search/$slug".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .build()
        } else {
            val builder = "$baseUrl/filter".toHttpUrl().newBuilder()
            var type = "all"
            var quality = "all"
            var year = "all"
            var country = "all"
            val genres = mutableListOf<String>()

            filters.forEach { filter ->
                when (filter) {
                    is Filters.TypeFilter -> type = filter.toUriPart()
                    is Filters.QualityFilter -> quality = filter.toUriPart()
                    is Filters.YearFilter -> year = filter.toUriPart()
                    is Filters.CountryFilter -> country = filter.toUriPart()
                    is Filters.GenreFilter -> genres.addAll(filter.getIncluded())
                    else -> {}
                }
            }

            builder.addQueryParameter("type", type)
            if (quality != "all") builder.addQueryParameter("quality", quality)
            if (year != "all") builder.addQueryParameter("release_year", year)
            if (country != "all") builder.addQueryParameter("country", country)
            // Multiple genre ids are joined with "-" (e.g. genre=14-2).
            if (genres.isNotEmpty()) builder.addQueryParameter("genre", genres.joinToString("-"))
            builder.addQueryParameter("page", page.toString())
            builder.build()
        }

        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are ignored when using text search"),
        Filters.TypeFilter(),
        Filters.QualityFilter(),
        Filters.YearFilter(),
        Filters.CountryFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeListPage(response: Response, currentPage: Int): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.film_list-wrap div.flw-item").mapNotNull { element ->
            val link = element.selectFirst("div.film-detail h3.film-name a")
                ?: element.selectFirst("div.film-poster a")
                ?: return@mapNotNull null
            val href = link.attr("href").trim().ifBlank { return@mapNotNull null }
            val img = element.selectFirst("div.film-poster img")

            SAnime.create().apply {
                title = link.attr("title").ifBlank { link.text() }.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src")
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.select("ul.pagination a.page-link[href]").any { link ->
            val page = link.attr("href").toHttpUrlOrNullSafe()?.queryParameter("page")?.toIntOrNull()
            page != null && page > currentPage
        }

        return AnimesPage(animes, hasNext)
    }

    private fun String.toHttpUrlOrNullSafe() = runCatching {
        if (startsWith("http")) toHttpUrl() else "$baseUrl${if (startsWith("/")) "" else "/"}$this".toHttpUrl()
    }.getOrNull()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        val poster = doc.selectFirst("div.dp-i-c-poster div.film-poster img")
        val rating = doc.selectFirst("button.btn-imdb")?.text()?.substringAfter("IMDB:")?.trim()
        val quality = doc.selectFirst("button.btn-quality strong")?.text()?.trim()
        val released = doc.selectFirst("span[data-field=released-value]")?.text()?.trim()
        val duration = doc.selectFirst("span[data-field=duration-value]")?.text()?.trim()
        val casts = doc.select("div.elements a[href*=/cast/]").take(8).joinToString(", ") { it.text().trim() }
        val countries = doc.select("div.elements a[href*=/country/]").joinToString(", ") { it.text().trim() }
        val synopsis = doc.selectFirst("div.description")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: ""
        val isSeries = anime.url.contains("/tv/")

        return SAnime.create().apply {
            title = doc.selectFirst("h2.heading-name a")?.text()?.trim() ?: anime.title
            thumbnail_url = poster?.attr("data-show-poster")?.ifBlank { poster.absUrl("src") }
                ?: anime.thumbnail_url
            author = doc.select("div.elements a[href*=/production/]").joinToString(", ") { it.text().trim() }
                .ifBlank { null }
            artist = casts.ifBlank { null }
            genre = doc.select("div.elements a[href*=/genre/]").joinToString(", ") { it.text().trim() }
            // fboxtv.bz exposes no airing state; series keep getting new episodes appended.
            status = if (isSeries) SAnime.ONGOING else SAnime.COMPLETED
            description = buildString {
                if (synopsis.isNotBlank()) appendLine(synopsis).appendLine()
                if (!rating.isNullOrBlank()) appendLine("IMDB: $rating")
                if (!quality.isNullOrBlank()) appendLine("Quality: $quality")
                if (!released.isNullOrBlank()) appendLine("Released: $released")
                if (!duration.isNullOrBlank()) appendLine("Duration: $duration")
                if (countries.isNotBlank()) appendLine("Country: $countries")
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    // SEpisode.url is a permanent anchor over the detail page path:
    //   movie: "/movie/<slug-id>#movie"
    //   tv:    "/tv/<slug-id>#season=1&ep=1"
    // The site's numeric season/episode/server ids are re-resolved at playback
    // time, so nothing ephemeral is persisted in the database.
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val showId = doc.showId() ?: return emptyList()
        val isSeries = doc.isSeries()

        if (!isSeries) {
            return listOf(
                SEpisode.create().apply {
                    url = "${anime.url}#movie"
                    name = "Movie"
                    episode_number = 1f
                },
            )
        }

        val seasons = seasonList(showId)
        val multiSeason = seasons.any { it.first > 1 }
        val episodes = mutableListOf<SEpisode>()

        seasons.forEach { (seasonNum, seasonId) ->
            val epDoc = runCatching {
                client.newCall(GET("$baseUrl/ajax/season/episodes/$seasonId", ajaxHeaders)).execute().asJsoup()
            }.getOrNull() ?: return@forEach

            epDoc.select("a.eps-item").forEachIndexed { idx, el ->
                val label = el.attr("title").ifBlank { el.text() }.trim()
                val epNum = EPISODE_NUMBER_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                val epTitle = label.substringAfter(":", "").trim()

                episodes.add(
                    SEpisode.create().apply {
                        url = "${anime.url}#season=$seasonNum&ep=$epNum"
                        name = buildString {
                            append("S${seasonNum}E$epNum")
                            if (epTitle.isNotBlank()) append(" - $epTitle")
                        }
                        episode_number = if (multiSeason) {
                            ((seasonNum - 1) * 100 + epNum).toFloat()
                        } else {
                            epNum.toFloat()
                        }
                    },
                )
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    /** `/ajax/season/list/<showId>` → ordered list of (seasonNumber, seasonId). */
    private fun seasonList(showId: String): List<Pair<Int, String>> {
        val doc = runCatching {
            client.newCall(GET("$baseUrl/ajax/season/list/$showId", ajaxHeaders)).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        return doc.select("a.ss-item").mapIndexedNotNull { idx, el ->
            val id = el.attr("data-id").trim().ifBlank { return@mapIndexedNotNull null }
            val num = SEASON_NUMBER_REGEX.find(el.text())?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            num to id
        }
    }

    private fun Document.showId(): String? = selectFirst("div.detail_page-watch")?.attr("data-id")?.trim()?.ifBlank { null }

    private fun Document.isSeries(): Boolean = selectFirst("div.detail_page-watch")?.attr("data-type")?.trim() == "2"

    // ============================== Hosters ===============================
    // Tier 1: server folders exposed by fboxtv.bz (UpCloud, VixCloud, MegaCloud, ...).
    // Tier 2 (getVideoList): resolve the folder to the embed it proxies and extract streams.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val detailPath = episode.url.substringBefore("#")
        val anchor = episode.url.substringAfter("#", "")

        val doc = client.newCall(GET("$baseUrl$detailPath", headers)).execute().asJsoup()
        val showId = doc.showId() ?: return emptyList()

        val serverListId = if (doc.isSeries()) {
            val season = ANCHOR_SEASON_REGEX.find(anchor)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val ep = ANCHOR_EPISODE_REGEX.find(anchor)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            episodeId(showId, season, ep) ?: return emptyList()
        } else {
            showId
        }

        // Movies list their servers under /ajax/episode/list/<showId>; series
        // episodes under /ajax/episode/servers/<episodeId>.
        val serversPath = if (doc.isSeries()) "servers/$serverListId" else "list/$serverListId"
        val serverDoc = runCatching {
            client.newCall(GET("$baseUrl/ajax/episode/$serversPath", ajaxHeaders)).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        val excluded = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val preferred = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return serverDoc.select("a.link-item").mapNotNull { el ->
            val serverId = el.attr("data-id").trim().ifBlank { return@mapNotNull null }
            val serverName = el.selectFirst("span")?.text()?.trim()
                ?: el.attr("title").removePrefix("Server ").trim()
            if (serverName in excluded) return@mapNotNull null
            Hoster(hosterName = serverName, hosterUrl = "$serverName$HOSTER_SEPARATOR$serverId")
        }.sortedByDescending { it.hosterName.equals(preferred, ignoreCase = true) }
    }

    /** Walks the season list to map a permanent (season, episode) pair onto the site's episode id. */
    private fun episodeId(showId: String, season: Int, episode: Int): String? {
        val seasonId = seasonList(showId).firstOrNull { it.first == season }?.second ?: return null
        val doc = runCatching {
            client.newCall(GET("$baseUrl/ajax/season/episodes/$seasonId", ajaxHeaders)).execute().asJsoup()
        }.getOrNull() ?: return null

        return doc.select("a.eps-item").mapIndexedNotNull { idx, el ->
            val label = el.attr("title").ifBlank { el.text() }
            val num = EPISODE_NUMBER_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            if (num == episode) el.attr("data-id").trim().ifBlank { null } else null
        }.firstOrNull()
    }

    // ============================ Stream Extraction ============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val serverId = hoster.hosterUrl.substringAfter(HOSTER_SEPARATOR, "")
        if (serverId.isBlank()) return emptyList()

        val embedUrl = resolveEmbedUrl(serverId) ?: return emptyList()
        val label = hoster.hosterName

        val videos = when {
            embedUrl.contains("moviesapi.", ignoreCase = true) -> extractMoviesApi(embedUrl, label)
            embedUrl.contains("vidlove", ignoreCase = true) -> extractVidlove(embedUrl, label)
            else -> extractGeneric(embedUrl, label)
        }

        return videos.sortVideos()
    }

    /** `/ajax/episode/sources/<serverId>` → `{"link": "<third-party embed url>"}`. */
    private fun resolveEmbedUrl(serverId: String): String? = runCatching {
        client.newCall(GET("$baseUrl/ajax/episode/sources/$serverId", ajaxHeaders)).execute()
            .parseAs<SourceLinkDto>(json).link?.trim()?.ifBlank { null }
    }.getOrNull()

    /**
     * moviesapi.to embeds resolve through the site's own "vidora" JSON API, which
     * returns an HLS master plus VTT subtitle tracks without any token exchange.
     * Absent titles answer `{"result": false}`, in which case playback falls
     * through to the next server folder.
     */
    private suspend fun extractMoviesApi(embedUrl: String, label: String): List<Video> {
        // https://moviesapi.to/movie/<tmdb>  ->  /api/vidora/v1/movie/<tmdb>
        // https://moviesapi.to/tv/<tmdb>/<s>/<e>  ->  /api/vidora/v1/tv/<tmdb>/<s>/<e>
        val path = embedUrl.toHttpUrlOrNullSafe()?.encodedPath?.trimEnd('/') ?: return emptyList()
        val apiUrl = "$MOVIESAPI_BASE_URL/api/vidora/v1$path"

        val dto = runCatching {
            client.newCall(GET(apiUrl, moviesApiHeaders)).execute().parseAs<VidoraResponseDto>(json)
        }.getOrNull() ?: return emptyList()
        if (dto.result != true) return emptyList()

        val videos = mutableListOf<Video>()
        dto.sources.orEmpty().forEach { source ->
            val streamUrl = source.url ?: return@forEach
            val subtitles = source.tracks.orEmpty().mapNotNull { track ->
                track.file?.let { Track(it, track.label ?: "Unknown") }
            }

            val extracted = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "$MOVIESAPI_BASE_URL/",
                    masterHeaders = moviesApiHeaders,
                    videoHeaders = moviesApiHeaders,
                    videoNameGen = { quality -> "$label - $quality" },
                    subtitleList = subtitles,
                )
            }.getOrNull().orEmpty()

            // This CDN serves some variant playlists as `/cdn/<base64>.js`, which the
            // player rejects as an unrecognised format. Hand it the master instead and
            // let ExoPlayer resolve the variants itself.
            if (extracted.isEmpty() || extracted.any { it.videoUrl.substringBefore("?").endsWith(".js") }) {
                videos.add(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "$label - HLS",
                        headers = moviesApiHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            } else {
                videos.addAll(extracted)
            }
        }
        return videos
    }

    /**
     * player.vidlove.cc is a thin client over api.shows.st, which answers with a
     * ready HLS master URL and a subtitle list for a plain TMDB id.
     */
    private suspend fun extractVidlove(embedUrl: String, label: String): List<Video> {
        // https://player.vidlove.cc/embed/movie/<tmdb>
        // https://player.vidlove.cc/embed/tv/<tmdb>/<s>/<e>
        val segments = embedUrl.toHttpUrlOrNullSafe()?.pathSegments?.filter { it.isNotBlank() }
            ?: return emptyList()
        val kindIdx = segments.indexOf("embed").takeIf { it >= 0 }?.plus(1) ?: return emptyList()
        val kind = segments.getOrNull(kindIdx) ?: return emptyList()
        val id = segments.getOrNull(kindIdx + 1) ?: return emptyList()

        val apiUrl = when (kind) {
            "movie" -> "$SHOWS_ST_API_URL/movie?id=$id&mode=json"

            "tv" -> {
                val season = segments.getOrNull(kindIdx + 2) ?: return emptyList()
                val ep = segments.getOrNull(kindIdx + 3) ?: return emptyList()
                "$SHOWS_ST_API_URL/tv?id=$id&season=$season&episode=$ep&mode=json"
            }

            else -> return emptyList()
        }

        val dto = runCatching {
            client.newCall(GET(apiUrl, vidloveHeaders)).execute().parseAs<ShowsStResponseDto>(json)
        }.getOrNull() ?: return emptyList()

        val streamUrl = dto.source?.url ?: return emptyList()
        val subtitles = dto.subtitles.orEmpty().mapNotNull { sub ->
            sub.file?.let { Track(it, sub.label ?: "Unknown") }
        }

        val extracted = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = "$VIDLOVE_BASE_URL/",
                masterHeaders = vidloveHeaders,
                videoHeaders = vidloveHeaders,
                videoNameGen = { quality -> "$label - $quality" },
                subtitleList = subtitles,
            )
        }.getOrNull().orEmpty()

        return extracted.ifEmpty {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "$label - HLS",
                    headers = vidloveHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    /**
     * vidfast.vc, vixsrc.to, vidsrcme.ru and primesrc.me are JS players with no
     * plain HTTP stream endpoint, so they go through the WebView-backed extractor.
     */
    private suspend fun extractGeneric(embedUrl: String, label: String): List<Video> {
        val embedHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        return runCatching {
            universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "$label - ")
        }.getOrNull().orEmpty()
    }

    private val moviesApiHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$MOVIESAPI_BASE_URL/")
            .add("Origin", MOVIESAPI_BASE_URL)
            .add("x-player-key", MOVIESAPI_PLAYER_KEY)
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    private val vidloveHeaders: Headers by lazy {
        Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "$VIDLOVE_BASE_URL/")
            .add("Origin", VIDLOVE_BASE_URL)
            .add("Accept", "application/json, text/plain, */*")
            .build()
    }

    // ============================== Sorting ===============================
    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(server, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = SERVERS,
            entryValues = SERVERS,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Hide servers",
            summary = "Servers left unchecked are hidden from the player",
            entries = SERVERS,
            entryValues = SERVERS,
            default = emptySet(),
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private const val HOSTER_SEPARATOR = "|"

        private const val MOVIESAPI_BASE_URL = "https://moviesapi.to"

        /** Static client key moviesapi.to's own player sends with every vidora API call. */
        private const val MOVIESAPI_PLAYER_KEY =
            "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"

        private const val VIDLOVE_BASE_URL = "https://player.vidlove.cc"
        private const val SHOWS_ST_API_URL = "https://api.shows.st"

        private val SERVERS = listOf("UpCloud", "VixCloud", "AKCloud", "MegaCloud", "PrimeSrc", "VidLove")

        private val EPISODE_NUMBER_REGEX = Regex("""Eps\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val SEASON_NUMBER_REGEX = Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val ANCHOR_SEASON_REGEX = Regex("""season=(\d+)""")
        private val ANCHOR_EPISODE_REGEX = Regex("""ep=(\d+)""")

        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://fboxtv.bz"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "UpCloud"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
    }
}

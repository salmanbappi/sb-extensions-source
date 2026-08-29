package eu.kanade.tachiyomi.animeextension.en.cinemaos

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.parseAs
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class Cinemaos :
    Source(),
    ConfigurableAnimeSource {

    override val name = "CinemaOS"

    override val baseUrl = "https://cinemaos.live"

    private val sheguApiUrl = "https://api.shegu.st"
    private val sheguDownloadsUrl = "https://downloads.shegu.st"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val hlsServer by lazy { CinemaosHlsServer(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/tmdb?requestID=trendingMovie&language=en-US&page=$page", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/tmdb?requestID=latestMovie&language=en-US&page=$page", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val request = if (query.isNotBlank()) {
            GET("$baseUrl/api/tmdb?requestID=searchMulti&query=${Uri.encode(query)}&language=en-US&page=$page", headers)
        } else {
            var mediaType = "trending"
            var sortBy = "popularity.desc"
            var year = ""
            val genreIds = mutableListOf<String>()

            for (filter in filters) {
                when (filter) {
                    is Filters.MediaTypeFilter -> mediaType = filter.selected

                    is Filters.SortFilter -> sortBy = filter.selected

                    is Filters.YearFilter -> {
                        if (filter.state.isNotBlank()) year = filter.state.trim()
                    }

                    is Filters.GenreFilter -> {
                        filter.state.forEach { check ->
                            if (check.state) genreIds.add(check.value)
                        }
                    }

                    else -> {}
                }
            }

            val endpoint = when (mediaType) {
                "movie" -> {
                    val genreParam = if (genreIds.isNotEmpty()) "&genreKeywords=${genreIds.joinToString(",")}" else ""
                    val yearParam = if (year.isNotBlank()) "&year=$year" else ""
                    "$baseUrl/api/tmdb?requestID=withKeywordsMovie$genreParam&language=en-US&sortBy=$sortBy$yearParam&page=$page"
                }

                "tv" -> {
                    val genreParam = if (genreIds.isNotEmpty()) "&genreKeywords=${genreIds.joinToString(",")}" else ""
                    val yearParam = if (year.isNotBlank()) "&year=$year" else ""
                    "$baseUrl/api/tmdb?requestID=withKeywordsTv$genreParam&language=en-US&sortBy=$sortBy$yearParam&page=$page"
                }

                else -> {
                    "$baseUrl/api/tmdb?requestID=trendingMovie&language=en-US&page=$page"
                }
            }
            GET(endpoint, headers)
        }

        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val endpoint = if (isMovie) {
            "$baseUrl/api/tmdb?id=$id&requestID=movieData&language=en-US"
        } else {
            "$baseUrl/api/tmdb?id=$id&requestID=tvData&language=en-US"
        }

        return try {
            val response = client.newCall(GET(endpoint, headers)).execute()
            if (isMovie) {
                val details = response.parseAs<MovieDetailsDto>(json)
                details.toSAnime(anime.url)
            } else {
                val details = response.parseAs<TvDetailsDto>(json)
                details.toSAnime(anime.url)
            }
        } catch (_: Exception) {
            anime
        }.apply {
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")

        if (isMovie) {
            return listOf(
                SEpisode.create().apply {
                    name = "Full Movie"
                    episode_number = 1.0f
                    url = "/watch/movie/$id"
                },
            )
        }

        val episodeList = mutableListOf<SEpisode>()
        try {
            val tvResponse = client.newCall(GET("$baseUrl/api/tmdb?id=$id&requestID=tvData&language=en-US", headers)).execute()
            val tvDetails = tvResponse.parseAs<TvDetailsDto>(json)
            val seasons = tvDetails.seasons ?: emptyList()
            val validSeasons = seasons.filter {
                val sNum = it.season_number ?: 0
                sNum > 0 && (it.episode_count ?: 0) > 0
            }.ifEmpty {
                seasons.filter { (it.episode_count ?: 0) > 0 }
            }

            for (season in validSeasons) {
                val seasonNum = season.season_number ?: 1
                val count = season.episode_count ?: 1
                var loadedFromApi = false

                try {
                    val seasonRes = client.newCall(GET("$baseUrl/api/tmdb?id=$id&season=$seasonNum&requestID=tvEpisodes&language=en-US", headers)).execute()
                    val seasonDetails = seasonRes.parseAs<SeasonDetailsDto>(json)
                    val eps = seasonDetails.episodes ?: emptyList()
                    if (eps.isNotEmpty()) {
                        eps.forEach { ep ->
                            episodeList.add(ep.toSEpisode(id.toLong(), seasonNum))
                        }
                        loadedFromApi = true
                    }
                } catch (_: Exception) {}

                if (!loadedFromApi && count > 0) {
                    for (epNum in 1..count) {
                        episodeList.add(
                            SEpisode.create().apply {
                                name = "S${seasonNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')} - Episode $epNum"
                                episode_number = ((seasonNum - 1) * 100 + epNum).toFloat()
                                url = "/watch/tv/$id/$seasonNum/$epNum"
                                scanlator = "Season $seasonNum"
                            },
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return listOf(
                SEpisode.create().apply {
                    name = "Full Movie / Episode 1"
                    episode_number = 1.0f
                    url = "/watch/movie/$id"
                },
            )
        }

        if (episodeList.isEmpty()) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1.0f
                    url = "/watch/tv/$id/1/1"
                },
            )
        }

        return episodeList.distinctBy { it.url }.reversed()
    }

    // ============================ Dynamic Hoster Discovery =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val watchUrl = episode.url
        val isTv = watchUrl.startsWith("/watch/tv/") || watchUrl.startsWith("/tv/")
        val parts = watchUrl.removePrefix("/watch/").removePrefix("/").split("/")

        val mediaType = if (isTv) "tv" else "movie"
        val id = parts.getOrNull(1) ?: return emptyList()
        val season = if (isTv) parts.getOrNull(2) ?: "1" else ""
        val ep = if (isTv) parts.getOrNull(3) ?: "1" else ""

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val hosters = mutableListOf<Hoster>()

        // 1. Direct High-Quality Streams
        if ("Direct Streams" !in excludedServers) {
            hosters.add(
                Hoster(
                    hosterName = "⭐ Direct Streams (Shegu / 4K Cinejoy)",
                    hosterUrl = "shegu:$mediaType:$id:$season:$ep",
                ),
            )
        }

        // 2. Private Streaming Servers
        val privateServers = getActiveServers()
        privateServers.filter { it !in excludedServers }.forEach { serverName ->
            hosters.add(
                Hoster(
                    hosterName = "Private Server ($serverName)",
                    hosterUrl = "sheguserver:$serverName:$mediaType:$id:$season:$ep",
                ),
            )
        }

        // 3. Public Embed Video Hosters
        val publicEmbeds = if (mediaType == "movie") {
            listOf(
                Pair("VidLink", "https://vidlink.pro/movie/$id"),
                Pair("AutoEmbed", "https://player.autoembed.cc/embed/movie/$id"),
                Pair("EmbedSu", "https://embed.su/embed/movie/$id"),
                Pair("Vidfast", "https://vidfast.pro/movie/$id"),
                Pair("VidZee", "https://vidzee.wtf/movie/$id"),
                Pair("SuperEmbed", "https://multiembed.mov/?video_id=$id&tmdb=1"),
                Pair("2Embed", "https://www.2embed.cc/embed/$id"),
                Pair("SmashyStream", "https://player.smashy.stream/movie/$id"),
                Pair("MoviesAPI", "https://moviesapi.club/movie/$id"),
            )
        } else {
            listOf(
                Pair("VidLink", "https://vidlink.pro/tv/$id/$season/$ep"),
                Pair("AutoEmbed", "https://player.autoembed.cc/embed/tv/$id/$season/$ep"),
                Pair("EmbedSu", "https://embed.su/embed/tv/$id/$season/$ep"),
                Pair("Vidfast", "https://vidfast.pro/tv/$id/$season/$ep"),
                Pair("VidZee", "https://vidzee.wtf/tv/$id/$season/$ep"),
                Pair("SuperEmbed", "https://multiembed.mov/?video_id=$id&tmdb=1&s=$season&e=$ep"),
                Pair("2Embed", "https://www.2embed.cc/embedtv/$id&s=$season&e=$ep"),
                Pair("SmashyStream", "https://player.smashy.stream/tv/$id/$season/$ep"),
                Pair("MoviesAPI", "https://moviesapi.club/tv/$id/$season/$ep"),
            )
        }

        publicEmbeds.filter { it.first !in excludedServers }.forEach { (label, embedUrl) ->
            hosters.add(Hoster(hosterName = "Public Server ($label)", hosterUrl = "embed:$label:$embedUrl"))
        }

        return orderHostersByPref(hosters)
    }

    private fun getActiveServers(): List<String> = runCatching {
        val response = client.newCall(GET("$sheguApiUrl/servers", headers)).execute()
        val dto = response.parseAs<SheguServersResponseDto>(json)
        dto.servers?.filter { it.status == "ok" && it.name != "Canaias" }?.mapNotNull { it.name }?.ifEmpty { null }
    }.getOrNull() ?: listOf("Lisbon", "Nebula", "Solara", "Athens", "Joy", "Castle", "Sakura")

    private fun orderHostersByPref(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    // ============================ Stream Extraction =============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl
        val videoList = mutableListOf<Video>()

        when {
            // Direct Cloudflare Worker Downloads & Stream links
            rawUrl.startsWith("shegu:") -> {
                val parts = rawUrl.removePrefix("shegu:").split(":")
                val mediaType = parts.getOrNull(0) ?: "movie"
                val id = parts.getOrNull(1) ?: return emptyList()
                val season = parts.getOrNull(2) ?: "1"
                val ep = parts.getOrNull(3) ?: "1"

                val endpoint = if (mediaType == "movie") {
                    "$sheguDownloadsUrl/movie/$id"
                } else {
                    "$sheguDownloadsUrl/tv/$id/$season/$ep"
                }

                try {
                    val res = client.newCall(GET(endpoint, headers)).execute()
                    val dto = res.parseAs<SheguDownloadsDto>(json)
                    val links = dto.links ?: emptyList()

                    val directHeaders = Headers.Builder()
                        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .add("Referer", "$baseUrl/")
                        .add("Origin", baseUrl)
                        .build()

                    links.forEach { link ->
                        val u = link.url ?: return@forEach
                        val cleanUrl = u.trim().replace(" ", "%20")
                        val quality = link.quality ?: 1080
                        val name = (link.name ?: link.source ?: "Direct Stream").replace(Regex("\\[.*?\\]"), "").trim()
                        val sizeStr = if (!link.size.isNullOrBlank()) " [${link.size}]" else ""
                        val title = "Direct (${quality}p) - $name$sizeStr"

                        videoList.add(
                            Video(
                                videoUrl = cleanUrl,
                                videoTitle = title,
                                headers = directHeaders,
                            ),
                        )
                    }
                } catch (_: Exception) {}
            }

            // Shegu Private Streaming Servers (Lisbon, Nebula, Solara, Athens, Joy, Castle, Sakura)
            rawUrl.startsWith("sheguserver:") -> {
                val parts = rawUrl.removePrefix("sheguserver:").split(":")
                val serverName = parts.getOrNull(0) ?: "Lisbon"
                val mediaType = parts.getOrNull(1) ?: "movie"
                val id = parts.getOrNull(2) ?: return emptyList()
                val season = parts.getOrNull(3) ?: "1"
                val ep = parts.getOrNull(4) ?: "1"

                val videoHeaders = headersBuilder()
                    .set("Referer", "$baseUrl/")
                    .set("Origin", baseUrl)
                    .build()

                val streamUrl = resolveStreamUrlWithWebView(mediaType, id, season, ep, serverName) ?: return emptyList()
                if (streamUrl.startsWith("http")) {
                    if (!streamUrl.contains(".m3u8", ignoreCase = true)) {
                        val proxiedUrl = hlsServer.proxyMasterUrl(streamUrl, videoHeaders, quality = "auto")
                        videoList.add(
                            Video(
                                videoUrl = proxiedUrl,
                                videoTitle = "$serverName - Default",
                                headers = videoHeaders,
                            ),
                        )
                    } else {
                        val masterPlaylist = runCatching {
                            client.newCall(GET(streamUrl, videoHeaders)).execute().body.string()
                        }.getOrNull()

                        if (!masterPlaylist.isNullOrBlank() && masterPlaylist.contains("#EXT-X-STREAM-INF")) {
                            val lines = masterPlaylist.lines()
                            val variants = mutableListOf<String>()

                            for (i in lines.indices) {
                                val line = lines[i].trim()
                                if (line.startsWith("#EXT-X-STREAM-INF")) {
                                    val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(line)
                                    val height = resMatch?.groupValues?.get(1)?.toIntOrNull()

                                    val qualityLabel = when {
                                        height != null && height >= 2160 -> "2160p (4K)"
                                        height != null -> "${height}p"
                                        line.contains("4k", ignoreCase = true) -> "2160p (4K)"
                                        else -> "Video"
                                    }
                                    val qualityKey = when {
                                        height != null -> height.toString()
                                        line.contains("4k", ignoreCase = true) -> "2160"
                                        else -> qualityLabel
                                    }

                                    if (qualityLabel !in variants) {
                                        variants.add(qualityLabel)
                                        val proxiedUrl = hlsServer.proxyMasterUrl(streamUrl, videoHeaders, quality = qualityKey)
                                        videoList.add(
                                            Video(
                                                videoUrl = proxiedUrl,
                                                videoTitle = "$serverName - $qualityLabel",
                                                headers = videoHeaders,
                                            ),
                                        )
                                    }
                                }
                            }

                            val autoUrl = hlsServer.proxyMasterUrl(streamUrl, videoHeaders, quality = "auto")
                            videoList.add(
                                0,
                                Video(
                                    videoUrl = autoUrl,
                                    videoTitle = "$serverName - Auto (Adaptive)",
                                    headers = videoHeaders,
                                ),
                            )
                        } else {
                            val defaultUrl = hlsServer.proxyMasterUrl(streamUrl, videoHeaders, quality = "auto")
                            videoList.add(
                                Video(
                                    videoUrl = defaultUrl,
                                    videoTitle = "$serverName - Default",
                                    headers = videoHeaders,
                                ),
                            )
                        }
                    }
                }
            }

            // Public Embed Server
            rawUrl.startsWith("embed:") -> {
                val parts = rawUrl.removePrefix("embed:").split(":", limit = 2)
                val label = parts.getOrNull(0) ?: "Server"
                val embedUrl = parts.getOrNull(1) ?: ""

                val embedHeaders = Headers.Builder()
                    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .add("Referer", embedUrl)
                    .build()

                try {
                    val extracted = universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "$label - ")
                    videoList.addAll(extracted)
                } catch (_: Exception) {}
            }
        }

        return videoList.sortVideos()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun resolveStreamUrlWithWebView(
        mediaType: String,
        id: String,
        season: String,
        ep: String,
        serverName: String,
    ): String? {
        val latch = CountDownLatch(1)
        var capturedUrl: String? = null
        var webView: WebView? = null

        val scraperHtml = CinemaosScraper.HTML

        Handler(Looper.getMainLooper()).post {
            try {
                webView = WebView(applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = headers["User-Agent"]

                    addJavascriptInterface(
                        object {
                            @android.webkit.JavascriptInterface
                            fun onSuccess(url: String) {
                                capturedUrl = url
                                latch.countDown()
                            }

                            @android.webkit.JavascriptInterface
                            fun onError(err: String) {
                                latch.countDown()
                            }
                        },
                        "AndroidBridge",
                    )

                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            val script = """
                                (async function() {
                                    try {
                                        const res = await window.E("$mediaType", "$id", "$season", "$ep", "$serverName");
                                        if (res && res.url) {
                                            AndroidBridge.onSuccess(res.url);
                                        } else {
                                            AndroidBridge.onError("No stream URL");
                                        }
                                    } catch(e) {
                                        AndroidBridge.onError(e.message || "Failed");
                                    }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(script, null)
                        }
                    }

                    loadDataWithBaseURL("https://cinejoy.to", scraperHtml, "text/html", "UTF-8", null)
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }

        latch.await(15, TimeUnit.SECONDS)

        Handler(Looper.getMainLooper()).post {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {}
        }

        return capturedUrl
    }

    // ============================== Settings / Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Preferred Server",
            summary = "%s",
            entries = listOf(
                "Direct Streams (Shegu / 4K Cinejoy)",
                "Lisbon",
                "Nebula",
                "Solara",
                "Athens",
                "Joy",
                "Castle",
                "Sakura",
                "VidLink",
                "AutoEmbed",
                "EmbedSu",
                "Vidfast",
            ),
            entryValues = listOf(
                "Direct Streams",
                "Lisbon",
                "Nebula",
                "Solara",
                "Athens",
                "Joy",
                "Castle",
                "Sakura",
                "VidLink",
                "AutoEmbed",
                "EmbedSu",
                "Vidfast",
            ),
            default = PREF_HOSTER_DEFAULT,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("2160p (4K)", "1080p", "720p", "480p", "360p", "Auto"),
            entryValues = listOf("2160", "1080", "720", "480", "360", "Auto"),
            default = PREF_QUALITY_DEFAULT,
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from hoster selection",
            entries = listOf(
                "Direct Streams",
                "Lisbon",
                "Nebula",
                "Solara",
                "Athens",
                "Joy",
                "Castle",
                "Sakura",
                "VidLink",
                "AutoEmbed",
                "EmbedSu",
                "Vidfast",
                "VidZee",
                "SuperEmbed",
                "2Embed",
                "SmashyStream",
                "MoviesAPI",
            ),
            entryValues = listOf(
                "Direct Streams",
                "Lisbon",
                "Nebula",
                "Solara",
                "Athens",
                "Joy",
                "Castle",
                "Sakura",
                "VidLink",
                "AutoEmbed",
                "EmbedSu",
                "Vidfast",
                "VidZee",
                "SuperEmbed",
                "2Embed",
                "SmashyStream",
                "MoviesAPI",
            ),
            default = emptySet(),
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(qualityPref, ignoreCase = true) }
                .thenByDescending { getVideoQualityWeight(it.videoTitle) },
        )
    }

    private fun getVideoQualityWeight(title: String): Int {
        val lower = title.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160p") -> 4000
            lower.contains("1080p") -> 1080
            lower.contains("720p") -> 720
            lower.contains("480p") -> 480
            lower.contains("360p") -> 360
            lower.contains("hevc") || lower.contains("x265") -> 50
            lower.contains("av1") -> 60
            lower.contains("10-bit") || lower.contains("hdr") -> 10
            else -> 0
        }
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "Direct Streams"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
    }
}

package eu.kanade.tachiyomi.animeextension.en.cinejoy

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.applicationContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Cinejoy : Source() {

    override val name = "Cinejoy"

    override val baseUrl = "https://cinejoy.to"

    private val sheguApiUrl = "https://api.shegu.st"

    override val lang = "en"

    override val supportsLatest = true

    private val tmdbApiKey = "8476a7ab80ad76f0936744df0430e67c"

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val hlsServer by lazy { CinejoyHlsServer(client) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "https://api.themoviedb.org/3/trending/all/week".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("include_adult", "false")
            .build()
        val response = client.newCall(GET(url, headers)).execute()
        return parseTmdbMediaList(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = "https://api.themoviedb.org/3/discover/movie".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("sort_by", "primary_release_date.desc")
            .addQueryParameter("vote_count.gte", "10")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("include_adult", "false")
            .build()
        val response = client.newCall(GET(url, headers)).execute()
        return parseTmdbMediaList(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val url = "https://api.themoviedb.org/3/search/multi".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", tmdbApiKey)
                .addQueryParameter("query", query)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("include_adult", "false")
                .build()
            val response = client.newCall(GET(url, headers)).execute()
            return parseTmdbMediaList(response)
        }

        var mediaType = "movie"
        var sortBy = "popularity.desc"
        var year = ""
        val includedGenres = mutableListOf<String>()

        for (filter in filters) {
            when (filter) {
                is Filters.TypeFilter -> {
                    if (filter.toUriPart().isNotBlank()) {
                        mediaType = filter.toUriPart()
                    }
                }

                is Filters.SortFilter -> {
                    if (filter.toUriPart().isNotBlank()) {
                        sortBy = filter.toUriPart()
                    }
                }

                is Filters.YearFilter -> {
                    if (filter.state.isNotBlank()) {
                        year = filter.state.trim()
                    }
                }

                is Filters.GenreFilter -> {
                    includedGenres.addAll(filter.getIncluded())
                }

                else -> {}
            }
        }

        val urlBuilder = "https://api.themoviedb.org/3/discover/$mediaType".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("sort_by", sortBy)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("include_adult", "false")

        if (year.isNotBlank()) {
            val yearParam = if (mediaType == "movie") "primary_release_year" else "first_air_date_year"
            urlBuilder.addQueryParameter(yearParam, year)
        }

        if (includedGenres.isNotEmpty()) {
            urlBuilder.addQueryParameter("with_genres", includedGenres.joinToString(","))
        }

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseTmdbMediaList(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.SortFilter(),
        Filters.YearFilter(),
        Filters.GenreFilter(),
    )

    private fun parseTmdbMediaList(response: Response): AnimesPage {
        val dto = response.parseAs<TmdbMediaListDto>()
        val results = dto.results ?: return AnimesPage(emptyList(), false)
        val page = dto.page ?: 1
        val totalPages = dto.total_pages ?: 1

        val animes = results.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val mediaType = item.media_type ?: if (item.first_air_date != null || item.name != null) "tv" else "movie"
            val titleStr = item.title ?: item.name ?: item.original_title ?: item.original_name ?: return@mapNotNull null
            val posterPath = item.poster_path
            val posterUrl = if (!posterPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else ""

            SAnime.create().apply {
                title = titleStr
                setUrlWithoutDomain("/watch/$id?type=$mediaType")
                thumbnail_url = posterUrl
            }
        }

        return AnimesPage(animes, page < totalPages)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = anime.url.substringAfter("/watch/").substringBefore("?")
        val mediaType = if (anime.url.contains("type=tv")) "tv" else "movie"

        val url = "https://api.themoviedb.org/3/$mediaType/$id".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .addQueryParameter("append_to_response", "videos,credits")
            .build()

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<TmdbDetailsDto>()
        val isTv = mediaType == "tv"

        val titleStr = dto.title ?: dto.name ?: anime.title
        val posterPath = dto.poster_path
        val posterUrl = if (!posterPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else anime.thumbnail_url

        val synopsis = dto.overview ?: ""
        val score = dto.vote_average
        val voteCount = dto.vote_count

        val genres = dto.genres?.mapNotNull { it.name }?.joinToString(", ") ?: ""
        val statusRaw = dto.status ?: if (isTv) "Ongoing" else "Completed"
        val releaseYear = (dto.release_date ?: dto.first_air_date ?: "").take(4)

        val trailerKey = dto.videos?.results?.firstOrNull {
            it.site == "YouTube" && it.type == "Trailer"
        }?.key

        return SAnime.create().apply {
            title = titleStr
            thumbnail_url = posterUrl
            genre = genres
            status = when {
                statusRaw.equals("Ended", ignoreCase = true) || statusRaw.equals("Canceled", ignoreCase = true) || !isTv -> SAnime.COMPLETED
                else -> SAnime.ONGOING
            }
            initialized = true

            description = buildString {
                if (score != null && score > 0.0) {
                    val stars = (score / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} ${"%.2f".format(score)}")
                    if (voteCount != null && voteCount > 0) append(" ($voteCount votes)")
                    append("\n\n")
                }
                if (synopsis.isNotBlank()) append(synopsis)
                if (releaseYear.isNotBlank()) append("\n\nYear: $releaseYear")
                append("\nStatus: $statusRaw")
                if (!trailerKey.isNullOrBlank()) {
                    append("\n\n[Trailer](https://www.youtube.com/watch?v=$trailerKey)")
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.substringAfter("/watch/").substringBefore("?")
        val isTv = anime.url.contains("type=tv")

        if (!isTv) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    setUrlWithoutDomain("/watch/movie/$id")
                    episode_number = 1.0f
                },
            )
        }

        val url = "https://api.themoviedb.org/3/tv/$id".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", tmdbApiKey)
            .build()

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<TmdbDetailsDto>()
        val seasons = dto.seasons ?: return emptyList()

        val epList = mutableListOf<SEpisode>()

        for (season in seasons) {
            val seasonNum = season.season_number ?: continue
            if (seasonNum <= 0) continue

            val seasonUrl = "https://api.themoviedb.org/3/tv/$id/season/$seasonNum".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", tmdbApiKey)
                .build()

            runCatching {
                val sRes = client.newCall(GET(seasonUrl, headers)).execute()
                val seasonDto = sRes.parseAs<TmdbSeasonDetailsDto>()
                val episodes = seasonDto.episodes ?: return@runCatching

                for (ep in episodes) {
                    val epNum = ep.episode_number ?: continue
                    val epName = ep.name ?: "Episode $epNum"
                    val stillPath = ep.still_path
                    val stillUrl = if (!stillPath.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$stillPath" else ""
                    val overviewStr = ep.overview ?: ""

                    epList.add(
                        SEpisode.create().apply {
                            name = "S${seasonNum.toString().padStart(2, '0')}E${epNum.toString().padStart(2, '0')} - $epName"
                            setUrlWithoutDomain("/watch/tv/$id/$seasonNum/$epNum")
                            episode_number = ((seasonNum - 1) * 100 + epNum).toFloat()
                            if (stillUrl.isNotBlank()) preview_url = stillUrl
                            if (overviewStr.isNotBlank()) summary = overviewStr
                        },
                    )
                }
            }
        }

        return epList.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val watchUrl = episode.url
        val isTv = watchUrl.startsWith("/watch/tv/")
        val parts = watchUrl.removePrefix("/watch/").split("/")

        val mediaType = if (isTv) "tv" else "movie"
        val id = parts.getOrNull(1) ?: return emptyList()
        val season = if (isTv) parts.getOrNull(2) ?: "1" else ""
        val ep = if (isTv) parts.getOrNull(3) ?: "1" else ""

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val servers = getActiveServers()

        val hosters = servers.filter { it !in excludedServers }.map { serverName ->
            Hoster(
                hosterName = serverName,
                hosterUrl = "$mediaType|$id|$season|$ep|$serverName",
            )
        }

        return sortHostersByPreference(hosters)
    }

    private fun getActiveServers(): List<String> = runCatching {
        val response = client.newCall(GET("$sheguApiUrl/servers", headers)).execute()
        val dto = response.parseAs<SheguServersResponseDto>()
        dto.servers?.filter { it.status == "ok" }?.mapNotNull { it.name }?.ifEmpty { null }
    }.getOrNull() ?: listOf("Lisbon", "Nebula", "Solara", "Athens", "Joy", "Castle", "Sakura", "Canaias")

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 5) return emptyList()

        val mediaType = parts[0]
        val id = parts[1]
        val season = parts[2]
        val ep = parts[3]
        val serverName = parts[4]

        val videoHeaders = headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()

        val streamUrl = resolveStreamUrlWithWebView(mediaType, id, season, ep, serverName) ?: return emptyList()

        val videos = if (streamUrl.contains(".m3u8")) {
            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = "$baseUrl/",
                masterHeaders = videoHeaders,
                videoHeaders = videoHeaders,
                videoNameGen = { quality -> quality },
            ).sortVideos()
        } else {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "Default",
                    headers = videoHeaders,
                ),
            ).sortVideos()
        }

        return hlsServer.proxyVideos(videos)
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

        val scraperHtml = CinejoyScraper.HTML

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
                        "androidBridge",
                    )

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val jsCall = "resolveStream('$mediaType', '$id', '$season', '$ep', '$serverName');"
                            view?.evaluateJavascript(jsCall, null)
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

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains("4K", ignoreCase = true) || it.videoTitle.contains("2160p", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains("1080p", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains("720p", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains("360p", ignoreCase = true) },
        )
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val preferred = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedWith(
            compareByDescending { it.hosterName.equals(preferred, ignoreCase = true) },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverList = listOf("Lisbon", "Nebula", "Solara", "Athens", "Joy", "Castle", "Sakura", "Canaias")

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            summary = "%s",
            entries = serverList.map { "$it Server" },
            entryValues = serverList,
            default = PREF_SERVER_DEFAULT,
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to exclude from hoster list",
            entries = serverList,
            entryValues = serverList,
            default = emptySet(),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("2160p (4K)", "1080p (FHD)", "720p (HD)", "360p (SD)"),
            entryValues = listOf("2160", "1080", "720", "360"),
            default = PREF_QUALITY_DEFAULT,
        )
    }

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Lisbon"

        private const val PREF_EXCLUDE_SERVERS_KEY = "excluded_servers"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

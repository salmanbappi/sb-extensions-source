package eu.kanade.tachiyomi.animeextension.en.oneshows

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import extensions.utils.addListPreference
import extensions.utils.getPreferencesLazy
import extensions.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class Oneshows :
    Source(),
    ConfigurableAnimeSource {

    override val name = "1Shows"

    override val baseUrl = "https://www.1shows.bz"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 2, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    private val universalExtractor by lazy { UniversalExtractor(network.client) }
    private val playlistUtils by lazy { PlaylistUtils(network.client, headers) }
    private val m3u8Integration by lazy { M3u8Integration(network.client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/trending/tv/day?page=$page", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/discover/tv?page=$page&sort_by=first_air_date.desc", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val request = if (query.isNotBlank()) {
            GET("$baseUrl/api/search/query?query=${Uri.encode(query)}&page=$page", headers)
        } else {
            var mediaType = "tv"
            var sortBy = "popularity.desc"
            val genreIds = mutableListOf<String>()

            for (filter in filters) {
                when (filter) {
                    is Filters.TypeFilter -> {
                        when (filter.toUriPart()) {
                            "movie" -> mediaType = "movie"

                            "anime_tv" -> {
                                mediaType = "tv"
                                genreIds.add("16")
                            }

                            "anime_movie" -> {
                                mediaType = "movie"
                                genreIds.add("16")
                            }

                            else -> mediaType = "tv"
                        }
                    }

                    is Filters.SortFilter -> {
                        val sortVal = filter.toUriPart()
                        sortBy = if (sortVal == "date.desc") {
                            if (mediaType == "movie") "primary_release_date.desc" else "first_air_date.desc"
                        } else {
                            sortVal
                        }
                    }

                    is Filters.GenreFilter -> {
                        genreIds.addAll(filter.getIncluded())
                    }

                    else -> {}
                }
            }

            val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${genreIds.distinct().joinToString(",")}" else ""
            GET("$baseUrl/api/discover/$mediaType?page=$page&sort_by=$sortBy$genreParam", headers)
        }

        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.SortFilter(),
        Filters.GenreFilter(),
    )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val isMovie = anime.url.contains("movie")
        val id = anime.url.substringAfterLast("/").substringBefore("?")
        val endpoint = if (isMovie) "$baseUrl/api/movie/$id" else "$baseUrl/api/tv/$id"

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
                    url = if (anime.url.startsWith("/")) anime.url else "/movie/$id"
                },
            )
        }

        val episodeList = mutableListOf<SEpisode>()
        try {
            val tvResponse = client.newCall(GET("$baseUrl/api/tv/$id", headers)).execute()
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
                    val seasonRes = client.newCall(GET("$baseUrl/api/tv/$id/season/$seasonNum", headers)).execute()
                    val seasonDetails = seasonRes.parseAs<SeasonDetailsDto>(json)
                    val eps = seasonDetails.episodes ?: emptyList()
                    if (eps.isNotEmpty()) {
                        eps.forEach { ep ->
                            val epNum = ep.episode_number ?: 1
                            episodeList.add(
                                SEpisode.create().apply {
                                    name = "S$seasonNum E$epNum - ${ep.name ?: "Episode $epNum"}"
                                    episode_number = epNum.toFloat()
                                    date_upload = parseDate(ep.air_date)
                                    url = "/tv/$id?season=$seasonNum&episode=$epNum"
                                    scanlator = "Season $seasonNum"
                                },
                            )
                        }
                        loadedFromApi = true
                    }
                } catch (_: Exception) {}

                if (!loadedFromApi && count > 0) {
                    for (epNum in 1..count) {
                        episodeList.add(
                            SEpisode.create().apply {
                                name = "S$seasonNum E$epNum - Episode $epNum"
                                episode_number = epNum.toFloat()
                                url = "/tv/$id?season=$seasonNum&episode=$epNum"
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
                    url = "/movie/$id"
                },
            )
        }

        if (episodeList.isEmpty()) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Episode 1"
                    episode_number = 1.0f
                    url = "/tv/$id?season=1&episode=1"
                },
            )
        }

        return episodeList.distinctBy { it.url }.reversed()
    }

    override val migration: SharedPreferences.() -> Unit = {
        val currentHoster = getString(PREF_HOSTER_KEY, null)
        if (currentHoster == null || currentHoster == "Vidzee") {
            edit().putString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).apply()
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val isMovie = episode.url.contains("movie")
        val id = if (isMovie) {
            episode.url.substringAfterLast("/").substringBefore("?")
        } else {
            episode.url.substringAfter("/tv/").substringBefore("?")
        }

        val parsedUri = Uri.parse("https://dummy.com${episode.url}")
        val season = parsedUri.getQueryParameter("season") ?: "1"
        val ep = parsedUri.getQueryParameter("episode") ?: "1"
        val path = if (isMovie) "movie/$id" else "tv/$id/$season/$ep"

        val hosters = mutableListOf<Hoster>()

        // 1. Direct Vidrock Sub-Servers (Nova, Atlas, Astra, Orion, etc.)
        val vidrockHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Referer", "https://vidrock.to/")
            .add("Origin", "https://vidrock.to")
            .build()

        try {
            val apiRes = network.client.newCall(GET("https://vidrock.to/api/$path", vidrockHeaders)).execute()
            val serverMap = apiRes.parseAs<Map<String, VidrockServerDto?>>(json)
            serverMap.forEach { (serverName, dto) ->
                if (dto != null && !dto.url.isNullOrBlank()) {
                    val lang = dto.language ?: ""
                    val langSuffix = if (lang.isNotBlank() && !lang.equals("English", true)) " [$lang]" else ""
                    val label = when (serverName) {
                        "Nova" -> "Vidrock (Nova)"
                        "Atlas" -> "Vidrock (Atlas - Multi-Quality)"
                        "Astra" -> "Vidrock (Astra - Direct MP4)"
                        "Orion" -> "Vidrock (Orion)"
                        "Luna" -> "Vidrock (Luna)"
                        "Lyra" -> "Vidrock (Lyra)"
                        "Vega" -> "Vidrock (Vega)"
                        else -> "Vidrock ($serverName$langSuffix)"
                    }
                    hosters.add(Hoster(hosterName = label, hosterUrl = "vidrock:$serverName:$path"))
                }
            }
        } catch (_: Exception) {}

        // 2. Direct MoviesAPI (VidSpark/NetroCDN - 1080p HLS)
        hosters.add(Hoster(hosterName = "MoviesAPI (Direct HLS)", hosterUrl = "moviesapi:$path"))

        // 3. 1shows Embedded Site Providers
        try {
            val providerHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .add("Referer", "https://www.1shows.bz/")
                .add("Origin", "https://www.1shows.bz")
                .add("Accept", "application/json, text/plain, */*")
                .build()
            val req = GET("https://api.viduki.net/embed_providers?site=1shows", providerHeaders)
            val res = network.client.newCall(req).execute()
            val dto = res.parseAs<EmbedProvidersResponseDto>(json)
            dto.providers?.forEach { p ->
                val template = if (isMovie) p.movie else p.tv
                val targetUrl = template
                    ?.replace("{id}", id)
                    ?.replace("{s}", season)
                    ?.replace("{e}", ep)
                    ?: return@forEach
                val baseLabel = p.label ?: p.id ?: "Server"
                val serviceTag = when {
                    targetUrl.contains("vidrock", ignoreCase = true) -> "Vidrock"
                    targetUrl.contains("vidfast", ignoreCase = true) -> "VidFast"
                    targetUrl.contains("vidlink", ignoreCase = true) -> "VidLink"
                    targetUrl.contains("vidy.st", ignoreCase = true) -> "Vidy"
                    targetUrl.contains("viduki.net", ignoreCase = true) -> "Viduki"
                    targetUrl.contains("vidzee", ignoreCase = true) -> "Vidzee"
                    else -> ""
                }
                if (serviceTag != "Vidrock" && serviceTag != "Vidzee") {
                    val label = if (serviceTag.isNotEmpty() && !baseLabel.contains(serviceTag, ignoreCase = true)) {
                        "$baseLabel ($serviceTag)"
                    } else {
                        baseLabel
                    }
                    hosters.add(Hoster(hosterName = label, hosterUrl = targetUrl))
                }
            }
        } catch (_: Exception) {}

        // Fallbacks if discovery was empty
        if (hosters.isEmpty()) {
            hosters.add(Hoster(hosterName = "Vidrock (Nova)", hosterUrl = "vidrock:Nova:$path"))
            hosters.add(Hoster(hosterName = "Vidrock (Atlas)", hosterUrl = "vidrock:Atlas:$path"))
            hosters.add(Hoster(hosterName = "MoviesAPI (Direct HLS)", hosterUrl = "moviesapi:$path"))
            hosters.add(Hoster(hosterName = "Main 3 (VidFast)", hosterUrl = "https://vidfast.vc/$path"))
            hosters.add(Hoster(hosterName = "Main 4 (VidLink)", hosterUrl = "https://vidlink.pro/$path"))
            hosters.add(Hoster(hosterName = "Main 1 (Viduki)", hosterUrl = "https://www.viduki.net/1/$path"))
        }

        return orderHostersByPref(hosters.distinctBy { it.hosterUrl })
    }

    private fun orderHostersByPref(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl
        val subTracks = mutableListOf<Track>()

        val path = when {
            rawUrl.startsWith("vidrock:") -> rawUrl.substringAfter(":").substringAfter(":")
            rawUrl.startsWith("moviesapi:") -> rawUrl.removePrefix("moviesapi:")
            rawUrl.contains("/movie/") -> "movie/" + rawUrl.substringAfter("/movie/").substringBefore("?")
            rawUrl.contains("/tv/") -> "tv/" + rawUrl.substringAfter("/tv/").substringBefore("?")
            else -> ""
        }

        val isMovie = path.startsWith("movie")
        val id = when {
            path.startsWith("movie/") -> path.substringAfter("movie/").substringBefore("/")
            path.startsWith("tv/") -> path.substringAfter("tv/").substringBefore("/")
            else -> ""
        }
        val season = if (!isMovie && path.startsWith("tv/")) {
            path.split("/").getOrNull(1) ?: "1"
        } else {
            "1"
        }
        val ep = if (!isMovie && path.startsWith("tv/")) {
            path.split("/").getOrNull(2) ?: "1"
        } else {
            "1"
        }
        val subPath = if (isMovie) "movie/$id" else "tv/$id/$season/$ep"

        // Multi-source Subtitle Resolution
        if (id.isNotBlank()) {
            val subHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .add("Referer", "https://vidrock.to/")
                .build()

            // 1. SubVdrk
            try {
                val subReq = GET("https://sub.vdrk.site/v2/$subPath", subHeaders)
                val subRes = network.client.newCall(subReq).execute()
                val subList = subRes.parseAs<List<SubtitleDto>>(json)
                subList.forEach { sub ->
                    val subUrl = sub.file ?: sub.url
                    val subLabel = sub.display ?: sub.label ?: sub.language ?: "Subtitle"
                    if (!subUrl.isNullOrBlank()) {
                        subTracks.add(Track(subUrl, subLabel))
                    }
                }
            } catch (_: Exception) {}

            // 2. Wyzie
            try {
                val wyzieUrl = if (isMovie) {
                    "https://vidfast.vc/wyzie?id=$id"
                } else {
                    "https://vidfast.vc/wyzie?id=$id&season=$season&episode=$ep"
                }
                val wyzieHeaders = Headers.Builder()
                    .add("User-Agent", "Mozilla/5.0")
                    .add("Referer", "https://vidfast.vc/")
                    .build()
                val subReq = GET(wyzieUrl, wyzieHeaders)
                val subRes = network.client.newCall(subReq).execute()
                val subList = subRes.parseAs<List<SubtitleDto>>(json)
                subList.forEach { sub ->
                    val subUrl = sub.url ?: sub.file
                    val subLabel = sub.display ?: sub.label ?: sub.language ?: "Subtitle"
                    if (!subUrl.isNullOrBlank()) {
                        subTracks.add(Track(subUrl, subLabel))
                    }
                }
            } catch (_: Exception) {}
        }

        val videoList = mutableListOf<Video>()

        when {
            // Specific Vidrock Server
            rawUrl.startsWith("vidrock:") -> {
                val parts = rawUrl.removePrefix("vidrock:").split(":", limit = 2)
                val targetServer = parts.getOrNull(0) ?: "Nova"
                val vPath = parts.getOrNull(1) ?: ""
                videoList.addAll(extractSingleVidrock(targetServer, vPath, subTracks))
            }

            // MoviesAPI
            rawUrl.startsWith("moviesapi:") -> {
                val vPath = rawUrl.removePrefix("moviesapi:")
                videoList.addAll(extractMoviesApi(vPath, subTracks))
            }

            // VidFast
            rawUrl.contains("vidfast", ignoreCase = true) -> {
                videoList.addAll(extractVidfastVideos(hoster, subTracks))
            }

            // Generic fallback (VidLink, Viduki, Vidy)
            else -> {
                videoList.addAll(extractGenericVideos(hoster, subTracks))
            }
        }

        val distinctSubs = subTracks.distinctBy { it.url }
        val finalVideos = videoList.map { v ->
            Video(
                videoUrl = v.videoUrl,
                videoTitle = v.videoTitle,
                headers = v.headers,
                audioTracks = v.audioTracks,
                subtitleTracks = (v.subtitleTracks.orEmpty() + distinctSubs).distinctBy { it.url },
            )
        }

        return m3u8Integration.processVideoList(finalVideos).sort()
    }

    // ============================ Provider: Vidrock ========================
    private suspend fun extractSingleVidrock(serverName: String, vPath: String, subTracks: List<Track>): List<Video> {
        val vidrockHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Referer", "https://vidrock.to/")
            .add("Origin", "https://vidrock.to")
            .build()

        val hosts = listOf("vidrock.to", "vidrock.ru")
        var serverMap: Map<String, VidrockServerDto?> = emptyMap()
        for (h in hosts) {
            try {
                val apiReq = GET("https://$h/api/$vPath", vidrockHeaders.newBuilder().set("Referer", "https://$h/").set("Origin", "https://$h").build())
                val apiRes = network.client.newCall(apiReq).execute()
                if (apiRes.isSuccessful) {
                    val map = apiRes.parseAs<Map<String, VidrockServerDto?>>(json)
                    if (map.values.any { it?.url?.isNotBlank() == true }) {
                        serverMap = map
                        break
                    }
                }
            } catch (_: Exception) {}
        }

        val serverDto = serverMap[serverName] ?: serverMap.values.firstOrNull { it?.url?.isNotBlank() == true } ?: return emptyList()
        val encUrl = serverDto.url ?: return emptyList()
        val streamUrl = decryptVidrock(encUrl)
        if (streamUrl.isBlank()) return emptyList()

        val lang = serverDto.language ?: ""
        val langSuffix = if (lang.isNotBlank() && !lang.equals("English", true)) " [$lang]" else ""
        val prefix = "$serverName$langSuffix - "

        return try {
            when {
                serverName.equals("Astra", ignoreCase = true) -> {
                    val astraRes = network.client.newCall(GET(streamUrl, vidrockHeaders)).execute()
                    val astraItems = astraRes.parseAs<List<AstraItemDto>>(json)
                    astraItems.mapNotNull { item ->
                        if (!item.url.isNullOrBlank()) {
                            val res = item.resolution ?: 720
                            Video(
                                videoUrl = item.url,
                                videoTitle = "$prefix${res}p (MP4)",
                                headers = vidrockHeaders,
                                subtitleTracks = subTracks,
                            )
                        } else {
                            null
                        }
                    }
                }

                streamUrl.contains(".m3u8", ignoreCase = true) -> {
                    val extracted = playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        referer = "https://vidrock.to/",
                        masterHeaders = vidrockHeaders,
                        videoHeaders = vidrockHeaders,
                        videoNameGen = { q -> "$prefix$q" },
                        subtitleList = subTracks,
                    )
                    if (extracted.isNotEmpty()) {
                        extracted
                    } else {
                        listOf(
                            Video(
                                videoUrl = streamUrl,
                                videoTitle = "$prefix Direct Stream",
                                headers = vidrockHeaders,
                                subtitleTracks = subTracks,
                            ),
                        )
                    }
                }

                else -> {
                    listOf(
                        Video(
                            videoUrl = streamUrl,
                            videoTitle = "$prefix Direct Stream",
                            headers = vidrockHeaders,
                            subtitleTracks = subTracks,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================ Provider: MoviesAPI ========================
    private suspend fun extractMoviesApi(vPath: String, subTracks: List<Track>): List<Video> {
        val apiHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .add("Referer", "https://moviesapi.to/")
            .add("Origin", "https://moviesapi.to")
            .add("x-player-key", "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13")
            .build()

        return try {
            val req = GET("https://moviesapi.to/api/vidora/v1/$vPath", apiHeaders)
            val res = network.client.newCall(req).execute()
            if (!res.isSuccessful) return emptyList()
            val dto = res.parseAs<VidoraResponseDto>(json)
            val streamUrl = dto.sources?.firstOrNull()?.url ?: return emptyList()

            val streamHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .add("Referer", "https://moviesapi.to/")
                .build()

            playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = "https://moviesapi.to/",
                masterHeaders = streamHeaders,
                videoHeaders = streamHeaders,
                videoNameGen = { q -> "MoviesAPI - $q" },
                subtitleList = subTracks,
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================ Provider: VidFast ========================
    private suspend fun extractVidfastVideos(hoster: Hoster, subTracks: List<Track>): List<Video> {
        var embedUrl = hoster.hosterUrl
        if (embedUrl.contains("vidfast.pro")) {
            embedUrl = embedUrl.replace("vidfast.pro", "vidfast.vc")
        }
        val embedUri = Uri.parse(embedUrl)
        val embedHost = embedUri.host ?: "vidfast.vc"

        val embedHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://www.1shows.bz/")
            .add("Origin", "https://www.1shows.bz")
            .build()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://$embedHost/")
            .add("Origin", "https://$embedHost")
            .build()

        return try {
            val videos = universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = hoster.hosterName)
            videos.map { v ->
                Video(
                    videoUrl = v.videoUrl,
                    videoTitle = v.videoTitle,
                    headers = videoHeaders,
                    audioTracks = v.audioTracks,
                    subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy { it.url },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================ Generic Extractor ========================
    private suspend fun extractGenericVideos(hoster: Hoster, subTracks: List<Track>): List<Video> {
        var embedUrl = hoster.hosterUrl
        if (embedUrl.contains("vidy.st") && !embedUrl.contains("www.vidy.st")) {
            embedUrl = embedUrl.replace("vidy.st", "www.vidy.st")
        }
        val embedUri = Uri.parse(embedUrl)
        val embedHost = embedUri.host ?: "1shows.bz"

        val embedHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://www.1shows.bz/")
            .add("Origin", "https://www.1shows.bz")
            .build()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://$embedHost/")
            .add("Origin", "https://$embedHost")
            .build()

        return try {
            val videos = universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = hoster.hosterName)
            videos.map { v ->
                Video(
                    videoUrl = v.videoUrl,
                    videoTitle = v.videoTitle,
                    headers = videoHeaders,
                    audioTracks = v.audioTracks,
                    subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy { it.url },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)

        return sortedWith(
            compareByDescending<Video> { hoster != null && it.videoTitle.contains(hoster, ignoreCase = true) }
                .thenByDescending { quality != null && it.videoTitle.contains(quality, ignoreCase = true) },
        )
    }

    private fun decryptVidrock(b64url: String): String {
        return runCatching {
            val decoded = Base64.decode(b64url, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (decoded.size < 28) return ""
            val iv = decoded.copyOfRange(0, 12)
            val ciphertextAndTag = decoded.copyOfRange(12, decoded.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(VIDROCK_AES_KEY, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val plaintext = cipher.doFinal(ciphertextAndTag)
            String(plaintext, Charsets.UTF_8)
        }.getOrDefault("")
    }

    // ============================= Preferences ============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Preferred Server",
            entries = listOf(
                "Vidrock (Nova)",
                "Vidrock (Atlas)",
                "Vidrock (Astra)",
                "MoviesAPI",
                "VidFast",
                "VidLink",
                "Viduki",
                "Vidy",
            ),
            entryValues = listOf(
                "Nova",
                "Atlas",
                "Astra",
                "MoviesAPI",
                "VidFast",
                "VidLink",
                "Viduki",
                "Vidy",
            ),
            default = PREF_HOSTER_DEFAULT,
            summary = "%s",
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("1080p", "720p", "480p", "360p", "Auto"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "Nova"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val VIDZEE_RC4_KEY = "e4f9b27d8c1a6ef5037db98ac54e21f0b9d6c3a781fe42ad65c0e9b73f148a2d"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private val VIDROCK_AES_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class SearchResponseDto(
    val page: Int? = null,
    val results: List<MediaItemDto>? = null,
    val total_pages: Int? = null,
    val total_results: Int? = null,
)

@Serializable
data class MediaItemDto(
    val id: Long? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val media_type: String? = null,
    val first_air_date: String? = null,
    val release_date: String? = null,
    val vote_average: Double? = null,
) {
    fun toSAnime(): SAnime? {
        val itemId = id ?: return null
        val itemTitle = title ?: name ?: return null
        val type = media_type?.lowercase() ?: if (first_air_date != null || name != null) "tv" else "movie"
        return SAnime.create().apply {
            this.title = itemTitle
            this.url = "/$type/$itemId"
            this.thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.description = overview
            this.status = SAnime.UNKNOWN
        }
    }
}

@Serializable
data class MovieDetailsDto(
    val id: Long? = null,
    val title: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val release_date: String? = null,
    val status: String? = null,
    val genres: List<GenreDto>? = null,
    val production_companies: List<CompanyDto>? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime = SAnime.create().apply {
        this.title = this@MovieDetailsDto.title ?: ""
        this.url = fallbackUrl
        this.thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        this.description = overview
        this.genre = genres?.mapNotNull { it.name }?.joinToString()
        this.author = production_companies?.mapNotNull { it.name }?.joinToString()
        this.status = when (this@MovieDetailsDto.status?.lowercase()) {
            "released" -> SAnime.COMPLETED
            "in production", "planned" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        this.fetch_type = FetchType.Episodes
    }
}

@Serializable
data class TvDetailsDto(
    val id: Long? = null,
    val name: String? = null,
    val overview: String? = null,
    val poster_path: String? = null,
    val backdrop_path: String? = null,
    val first_air_date: String? = null,
    val status: String? = null,
    val genres: List<GenreDto>? = null,
    val production_companies: List<CompanyDto>? = null,
    val seasons: List<SeasonDto>? = null,
) {
    fun toSAnime(fallbackUrl: String): SAnime = SAnime.create().apply {
        this.title = this@TvDetailsDto.name ?: ""
        this.url = fallbackUrl
        this.thumbnail_url = poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
        this.description = overview
        this.genre = genres?.mapNotNull { it.name }?.joinToString()
        this.author = production_companies?.mapNotNull { it.name }?.joinToString()
        this.status = when (this@TvDetailsDto.status?.lowercase()) {
            "returning series", "in production" -> SAnime.ONGOING
            "ended", "canceled" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        this.fetch_type = FetchType.Episodes
    }
}

@Serializable
data class SeasonDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val name: String? = null,
    val episode_count: Int? = null,
    val air_date: String? = null,
    val poster_path: String? = null,
)

@Serializable
data class SeasonDetailsDto(
    val id: String? = null,
    val season_number: Int? = null,
    val episodes: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: Long? = null,
    val season_number: Int? = null,
    val episode_number: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val air_date: String? = null,
    val still_path: String? = null,
    val vote_average: Double? = null,
)

@Serializable
data class GenreDto(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class CompanyDto(
    val id: Int? = null,
    val name: String? = null,
)

@Serializable
data class EmbedProvidersResponseDto(
    val site: String? = null,
    val resetVersion: Int? = null,
    val providers: List<EmbedProviderItemDto>? = null,
)

@Serializable
data class EmbedProviderItemDto(
    val id: String? = null,
    val label: String? = null,
    val movie: String? = null,
    val tv: String? = null,
)

@Serializable
data class SubtitleDto(
    val label: String? = null,
    val language: String? = null,
    val display: String? = null,
    val file: String? = null,
    val url: String? = null,
)

@Serializable
data class VidzeePayloadDto(
    val c: String? = null,
)

@Serializable
data class VidzeeStreamDto(
    val url: String? = null,
    val language: String? = null,
)

@Serializable
data class VidrockServerDto(
    val url: String? = null,
    val type: String? = null,
    val language: String? = null,
    val flag: String? = null,
)

@Serializable
data class AstraItemDto(
    val resolution: Int? = null,
    val url: String? = null,
)

@Serializable
data class VidoraResponseDto(
    val result: Boolean? = null,
    val sources: List<VidoraSourceDto>? = null,
)

@Serializable
data class VidoraSourceDto(
    val url: String? = null,
    val source: String? = null,
)

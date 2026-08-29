package eu.kanade.tachiyomi.animeextension.en.flixer

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
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
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.getPreferencesLazy
import extensions.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.Key
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class Flixer :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Flixer"

    override val baseUrl = "https://flixer.gd"

    private val apiBaseUrl = "https://plsdontscrapemelove.flixer.gd/api/tmdb"

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
    private val hlsServer by lazy { FlixerHlsServer(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$apiBaseUrl/trending/all/day?page=$page", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$apiBaseUrl/discover/movie?page=$page&sort_by=primary_release_date.desc&vote_count.gte=10", headers)
        val response = client.newCall(request).execute()
        val dto = response.parseAs<SearchResponseDto>(json)
        val animes = (dto.results ?: emptyList()).mapNotNull { it.toSAnime() }
        val hasNext = (dto.page ?: page) < (dto.total_pages ?: page)
        return AnimesPage(animes, hasNext)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val request = if (query.isNotBlank()) {
            GET("$apiBaseUrl/search/multi?query=${Uri.encode(query)}&page=$page", headers)
        } else {
            var mediaType = "trending"
            var sortBy = "popularity.desc"
            val genreIds = mutableListOf<String>()

            for (filter in filters) {
                when (filter) {
                    is Filters.MediaTypeFilter -> mediaType = filter.selected
                    is Filters.SortFilter -> sortBy = filter.selected
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
                    val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${genreIds.joinToString(",")}" else ""
                    "$apiBaseUrl/discover/movie?page=$page&sort_by=$sortBy$genreParam"
                }
                "tv" -> {
                    val genreParam = if (genreIds.isNotEmpty()) "&with_genres=${genreIds.joinToString(",")}" else ""
                    "$apiBaseUrl/discover/tv?page=$page&sort_by=$sortBy$genreParam"
                }
                else -> {
                    "$apiBaseUrl/trending/all/day?page=$page"
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
        val endpoint = if (isMovie) "$apiBaseUrl/movie/$id" else "$apiBaseUrl/tv/$id"

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
            val tvResponse = client.newCall(GET("$apiBaseUrl/tv/$id", headers)).execute()
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
                    val seasonRes = client.newCall(GET("$apiBaseUrl/tv/$id/season/$seasonNum", headers)).execute()
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

    // ============================ Dynamic Hoster Discovery =============================
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

        val vidrockHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://vidrock.ru/")
            .add("Origin", "https://vidrock.ru")
            .build()

        try {
            val apiRes = client.newCall(GET("https://vidrock.ru/api/$path", vidrockHeaders)).execute()
            val serverMap = apiRes.parseAs<Map<String, VidrockServerDto?>>(json)

            serverMap.forEach { (serverName, dto) ->
                if (dto?.url != null) {
                    val label = when (serverName) {
                        "Nova" -> "Server 1 (Nova)"
                        "Luna" -> "Server 2 (Luna)"
                        "Atlas" -> "Server 3 (Atlas)"
                        "Astra" -> "Server 4 (Astra - Direct MP4)"
                        "Orion" -> "Server 5 (Orion)"
                        "Lyra" -> "Server 6 (Lyra)"
                        "Vega" -> "Server 7 (Vega)"
                        else -> "Server ($serverName)"
                    }
                    hosters.add(Hoster(hosterName = label, hosterUrl = "vidrock:$serverName:$path"))
                }
            }
        } catch (_: Exception) {}

        if (hosters.isEmpty()) {
            hosters.add(Hoster(hosterName = "Server 1 (Nova)", hosterUrl = "vidrock:Nova:$path"))
            hosters.add(Hoster(hosterName = "Server 2 (Luna)", hosterUrl = "vidrock:Luna:$path"))
            hosters.add(Hoster(hosterName = "Server 3 (Atlas)", hosterUrl = "vidrock:Atlas:$path"))
            hosters.add(Hoster(hosterName = "Server 4 (Astra - Direct MP4)", hosterUrl = "vidrock:Astra:$path"))
        }

        hosters.add(Hoster(hosterName = "Server (VidSrc)", hosterUrl = "vidsrc:$path"))

        return orderHostersByPref(hosters)
    }

    private fun orderHostersByPref(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    // ============================ Inside Folder: 100% Working Stream Extraction =============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl
        val subTracks = mutableListOf<Track>()

        val path = rawUrl.substringAfter(":")
        val isMovie = path.startsWith("movie") || rawUrl.endsWith(":movie")
        val id = when {
            path.startsWith("movie/") -> path.substringAfter("movie/")
            path.startsWith("tv/") -> path.substringAfter("tv/").substringBefore("/")
            rawUrl.contains(":") -> rawUrl.split(":").getOrNull(1) ?: ""
            else -> ""
        }

        val season = if (!isMovie && path.startsWith("tv/")) {
            path.split("/").getOrNull(2) ?: "1"
        } else if (!isMovie && rawUrl.contains(":")) {
            rawUrl.split(":").getOrNull(2) ?: "1"
        } else {
            "1"
        }

        val ep = if (!isMovie && path.startsWith("tv/")) {
            path.split("/").getOrNull(3) ?: "1"
        } else if (!isMovie && rawUrl.contains(":")) {
            rawUrl.split(":").getOrNull(3) ?: "1"
        } else {
            "1"
        }

        // 1. Fetch Subtitles from Wyzie
        if (id.isNotBlank()) {
            try {
                val wyzieUrl = if (isMovie) {
                    "https://vidfast.pro/wyzie?id=$id"
                } else {
                    "https://vidfast.pro/wyzie?id=$id&season=$season&episode=$ep"
                }
                val subReq = GET(wyzieUrl, Headers.headersOf("User-Agent", "Mozilla/5.0", "Referer", "https://vidfast.pro/"))
                val subRes = client.newCall(subReq).execute()
                val subList = subRes.parseAs<List<SubtitleDto>>(json)
                subList.forEach { sub ->
                    val subUrl = sub.url ?: sub.file
                    val subLabel = sub.display ?: sub.label ?: sub.language ?: "Subtitle"
                    if (!subUrl.isNullOrBlank()) {
                        subTracks.add(Track(subUrl, subLabel))
                    }
                }
            } catch (_: Exception) {}

            // 2. Fetch Subtitles from SubVdrk
            try {
                val subPath = if (isMovie) "movie/$id" else "tv/$id/$season/$ep"
                val subReq = GET("https://sub.vdrk.site/v2/$subPath", Headers.headersOf("User-Agent", "Mozilla/5.0", "Referer", "https://vidrock.ru/"))
                val subRes = client.newCall(subReq).execute()
                val subList = subRes.parseAs<List<SubtitleDto>>(json)
                subList.forEach { sub ->
                    val subUrl = sub.file ?: sub.url
                    val subLabel = sub.display ?: sub.label ?: sub.language ?: "Subtitle"
                    if (!subUrl.isNullOrBlank()) {
                        subTracks.add(Track(subUrl, subLabel))
                    }
                }
            } catch (_: Exception) {}
        }

        val videoList = mutableListOf<Video>()

        when {
            // Vidrock Servers (Nova / Luna / Atlas / Astra / Orion / Lyra / Vega / Hindi)
            rawUrl.startsWith("vidrock:") -> {
                val parts = rawUrl.removePrefix("vidrock:").split(":", limit = 2)
                val targetServer = parts.getOrNull(0) ?: "Nova"
                val vPath = parts.getOrNull(1) ?: ""

                val vidrockHeaders = Headers.Builder()
                    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .add("Referer", "https://vidrock.ru/")
                    .add("Origin", "https://vidrock.ru")
                    .build()

                try {
                    val apiRes = client.newCall(GET("https://vidrock.ru/api/$vPath", vidrockHeaders)).execute()
                    val serverMap = apiRes.parseAs<Map<String, VidrockServerDto?>>(json)
                    val serverDto = serverMap[targetServer] ?: serverMap.values.filterNotNull().firstOrNull()

                    if (serverDto?.url != null) {
                        val streamUrl = decryptVidrock(serverDto.url)
                        if (streamUrl.isNotBlank()) {
                            if (targetServer.equals("Astra", ignoreCase = true)) {
                                // Direct MP4 playlist json
                                val astraRes = client.newCall(GET(streamUrl, vidrockHeaders)).execute()
                                val astraItems = astraRes.parseAs<List<AstraItemDto>>(json)
                                astraItems.forEach { item ->
                                    if (!item.url.isNullOrBlank()) {
                                        val res = item.resolution ?: 720
                                        videoList.add(
                                            Video(
                                                videoUrl = item.url,
                                                videoTitle = "${res}p",
                                                headers = vidrockHeaders,
                                                subtitleTracks = subTracks,
                                            ),
                                        )
                                    }
                                }
                            } else if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                                videoList.addAll(
                                    playlistUtils.extractFromHls(
                                        playlistUrl = streamUrl,
                                        referer = "https://vidrock.ru/",
                                        masterHeaders = vidrockHeaders,
                                        videoHeaders = vidrockHeaders,
                                        videoNameGen = { q -> q },
                                        subtitleList = subTracks,
                                    ),
                                )
                            } else {
                                videoList.add(
                                    Video(
                                        videoUrl = streamUrl,
                                        videoTitle = "Direct Stream",
                                        headers = vidrockHeaders,
                                        subtitleTracks = subTracks,
                                    ),
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // VidSrc
            rawUrl.startsWith("vidsrc:") -> {
                val vidsrcPath = rawUrl.removePrefix("vidsrc:")
                val embedUrl = "https://vidsrc.to/embed/$vidsrcPath"
                try {
                    videoList.addAll(vidsrcExtractor.videosFromUrl(embedUrl, hosterName = "", subtitleList = subTracks))
                } catch (_: Exception) {}
            }
        }

        // Attach subtitles to all resolved video streams
        val cleanedList = videoList.map { v ->
            val cleanTitle = v.videoTitle
                .replace(Regex("^(vidfast|vidlink|vidsrc|2embed|smashy|multiembed|vidrock)\\s*-\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "Auto" }

            Video(
                videoUrl = v.videoUrl,
                videoTitle = cleanTitle,
                headers = v.headers,
                audioTracks = v.audioTracks,
                subtitleTracks = (v.subtitleTracks.orEmpty() + subTracks).distinctBy { it.url },
            )
        }

        return hlsServer.proxyVideos(cleanedList).sortVideos()
    }

    private fun decryptVidrock(b64url: String): String {
        return runCatching {
            val decoded = Base64.decode(b64url, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            if (decoded.size < 28) return@runCatching ""
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

    // ============================== Settings / Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_HOSTER_KEY,
            title = "Preferred Server",
            summary = "%s",
            entries = listOf(
                "Nova",
                "Luna",
                "Atlas",
                "Astra",
                "Orion",
                "Lyra",
                "Vega",
                "VidSrc",
            ),
            entryValues = listOf("Nova", "Luna", "Atlas", "Astra", "Orion", "Lyra", "Vega", "VidSrc"),
            default = PREF_HOSTER_DEFAULT,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p", "Auto"),
            entryValues = listOf("1080", "720", "480", "360", "Auto"),
            default = PREF_QUALITY_DEFAULT,
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
        private const val PREF_HOSTER_DEFAULT = "Nova"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val VIDROCK_AES_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

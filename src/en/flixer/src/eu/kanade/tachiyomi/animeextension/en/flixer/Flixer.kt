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
import eu.kanade.tachiyomi.lib.m3u8server.M3u8Integration
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
    private val m3u8Integration by lazy { M3u8Integration(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

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

    // ============================ 2-Tier Hoster Folders (7 Servers) =============================
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

        // 7 distinct server folders mapped to direct HLS streaming endpoints
        val servers = listOf(
            Hoster(hosterName = "Server 1 (Ares - Nova)", hosterUrl = "vidrock:Nova:$path"),
            Hoster(hosterName = "Server 2 (Balder - Luna)", hosterUrl = "vidrock:Luna:$path"),
            Hoster(hosterName = "Server 3 (Circe - Orion)", hosterUrl = "vidrock:Orion:$path"),
            Hoster(hosterName = "Server 4 (Dionysus - Astra)", hosterUrl = "vidrock:Astra:$path"),
            Hoster(hosterName = "Server 5 (Eros - Hindi / Multi)", hosterUrl = "vidrock:Hindi:$path"),
            Hoster(hosterName = "Server 6 (Freya - Vega)", hosterUrl = "vidrock:Vega:$path"),
            Hoster(hosterName = "Server 7 (Gaia - VidFast)", hosterUrl = "vidfast:$path"),
        )

        return orderHostersByPref(servers)
    }

    private fun orderHostersByPref(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    // ============================ Inside Folder: Quality & Stream Selection =============================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl

        return when {
            rawUrl.startsWith("vidrock:") -> extractVidrockVideo(hoster)
            rawUrl.startsWith("vidfast:") -> extractVidfastVideo(hoster)
            else -> extractGenericVideo(hoster)
        }
    }

    private suspend fun extractVidrockVideo(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.removePrefix("vidrock:").split(":", limit = 2)
        val targetServer = parts.getOrNull(0) ?: "Nova"
        val path = parts.getOrNull(1) ?: return emptyList()

        val vidrockHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://vidrock.ru/")
            .add("Origin", "https://vidrock.ru")
            .build()

        val subTracks = mutableListOf<Track>()
        try {
            val subReq = GET("https://sub.vdrk.site/v2/$path", vidrockHeaders)
            val subRes = client.newCall(subReq).execute()
            val subList = subRes.parseAs<List<SubtitleDto>>(json)
            subList.forEach { sub ->
                val subUrl = sub.file ?: sub.url
                val subLabel = sub.label ?: sub.display ?: sub.language ?: "Subtitle"
                if (!subUrl.isNullOrBlank()) {
                    subTracks.add(Track(subUrl, subLabel))
                }
            }
        } catch (_: Exception) {}

        val serverMap: Map<String, VidrockServerDto?> = try {
            val apiReq = GET("https://vidrock.ru/api/$path", vidrockHeaders)
            val apiRes = client.newCall(apiReq).execute()
            apiRes.parseAs<Map<String, VidrockServerDto?>>(json)
        } catch (_: Exception) {
            emptyMap()
        }

        val serverDto = serverMap[targetServer] ?: serverMap.values.filterNotNull().firstOrNull()
        val encryptedUrl = serverDto?.url ?: return emptyList()

        val streamUrl = decryptVidrock(encryptedUrl)
        if (streamUrl.isBlank()) return emptyList()

        val lang = serverDto.language ?: ""
        val langSuffix = if (lang.isNotBlank() && !lang.equals("English", true)) " [$lang]" else ""

        val rawVideos = if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            try {
                playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "https://vidrock.ru/",
                    masterHeaders = vidrockHeaders,
                    videoHeaders = vidrockHeaders,
                    videoNameGen = { q -> "$q$langSuffix" },
                    subtitleList = subTracks,
                )
            } catch (_: Exception) {
                listOf(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "Auto$langSuffix",
                        headers = vidrockHeaders,
                        subtitleTracks = subTracks,
                    ),
                )
            }
        } else {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "Direct Stream$langSuffix",
                    headers = vidrockHeaders,
                    subtitleTracks = subTracks,
                ),
            )
        }

        return m3u8Integration.processVideoList(rawVideos).sortVideos()
    }

    private suspend fun extractVidfastVideo(hoster: Hoster): List<Video> {
        val path = hoster.hosterUrl.removePrefix("vidfast:")
        val isMovie = path.startsWith("movie/")
        val id = if (isMovie) path.substringAfter("movie/") else path.substringAfter("tv/").substringBefore("/")
        val pathSegments = path.split("/")
        val season = pathSegments.getOrNull(2) ?: "1"
        val ep = pathSegments.getOrNull(3) ?: "1"

        val vidfastHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://vidfast.pro/")
            .add("Origin", "https://vidfast.pro")
            .build()

        val subTracks = mutableListOf<Track>()
        try {
            val wyzieUrl = if (isMovie) {
                "https://vidfast.pro/wyzie?id=$id"
            } else {
                "https://vidfast.pro/wyzie?id=$id&season=$season&episode=$ep"
            }
            val subReq = GET(wyzieUrl, vidfastHeaders)
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

        val embedUrl = if (isMovie) "https://vidfast.pro/movie/$id" else "https://vidfast.pro/tv/$id/$season/$ep"

        val rawVideos = try {
            val extracted = universalExtractor.videosFromUrl(embedUrl, vidfastHeaders)
            extracted.map { v ->
                Video(
                    videoUrl = v.videoUrl,
                    videoTitle = v.videoTitle.replace(Regex("^vidfast\\s*-\\s*", RegexOption.IGNORE_CASE), ""),
                    headers = vidfastHeaders,
                    audioTracks = v.audioTracks,
                    subtitleTracks = (v.subtitleTracks + subTracks).distinctBy { it.url },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }

        return m3u8Integration.processVideoList(rawVideos).sortVideos()
    }

    private suspend fun extractGenericVideo(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        return try {
            val videos = universalExtractor.videosFromUrl(url, headers)
            m3u8Integration.processVideoList(videos).sortVideos()
        } catch (_: Exception) {
            emptyList()
        }
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
                "Server 1 (Ares - Nova)",
                "Server 2 (Balder - Luna)",
                "Server 3 (Circe - Orion)",
                "Server 4 (Dionysus - Astra)",
                "Server 5 (Eros - Hindi / Multi)",
                "Server 6 (Freya - Vega)",
                "Server 7 (Gaia - VidFast)",
            ),
            entryValues = listOf("Ares", "Balder", "Circe", "Dionysus", "Eros", "Freya", "Gaia"),
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
        private const val PREF_HOSTER_DEFAULT = "Ares"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private val VIDROCK_AES_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

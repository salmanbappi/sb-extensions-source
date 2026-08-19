package eu.kanade.tachiyomi.animeextension.en.oneshows

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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

    override val baseUrl = "https://www.1shows.org"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 2, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "application/json, text/plain, */*")

    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

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

        val defaultHosters = if (isMovie) {
            listOf(
                Hoster(hosterName = "Vidzee (Direct HLS)", hosterUrl = "https://player.vidzee.wtf/embed/movie/$id"),
                Hoster(hosterName = "Vidrock (Multi-Server)", hosterUrl = "https://vidrock.ru/movie/$id"),
                Hoster(hosterName = "VidFast", hosterUrl = "https://vidfast.pro/movie/$id"),
                Hoster(hosterName = "VidLink", hosterUrl = "https://vidlink.pro/movie/$id"),
                Hoster(hosterName = "Main 1 (Viduki)", hosterUrl = "https://www.viduki.net/1/movie/$id"),
                Hoster(hosterName = "Main 2 (Vidy)", hosterUrl = "https://vidy.st/movie/$id"),
                Hoster(hosterName = "Multi-Language (Viduki)", hosterUrl = "https://www.viduki.net/2/movie/$id"),
                Hoster(hosterName = "Premium Embeds (Viduki)", hosterUrl = "https://www.viduki.net/4/movie/$id"),
            )
        } else {
            listOf(
                Hoster(hosterName = "Vidzee (Direct HLS)", hosterUrl = "https://player.vidzee.wtf/embed/tv/$id/$season/$ep"),
                Hoster(hosterName = "Vidrock (Multi-Server)", hosterUrl = "https://vidrock.ru/tv/$id/$season/$ep"),
                Hoster(hosterName = "VidFast", hosterUrl = "https://vidfast.pro/tv/$id/$season/$ep"),
                Hoster(hosterName = "VidLink", hosterUrl = "https://vidlink.pro/tv/$id/$season/$ep"),
                Hoster(hosterName = "Main 1 (Viduki)", hosterUrl = "https://www.viduki.net/1/tv/$id/$season/$ep"),
                Hoster(hosterName = "Main 2 (Vidy)", hosterUrl = "https://vidy.st/tv/$id/$season/$ep"),
                Hoster(hosterName = "Multi-Language (Viduki)", hosterUrl = "https://www.viduki.net/2/tv/$id/$season/$ep"),
                Hoster(hosterName = "Premium Embeds (Viduki)", hosterUrl = "https://www.viduki.net/4/tv/$id/$season/$ep"),
            )
        }

        return try {
            val req = GET("https://api.viduki.net/embed_providers?site=1shows", headers)
            val res = client.newCall(req).execute()
            val dto = res.parseAs<EmbedProvidersResponseDto>(json)
            val dynamicList = dto.providers?.mapNotNull { p ->
                val template = if (isMovie) p.movie else p.tv
                val targetUrl = template
                    ?.replace("{id}", id)
                    ?.replace("{s}", season)
                    ?.replace("{e}", ep)
                    ?: return@mapNotNull null
                val label = p.label ?: p.id ?: "Server"
                Hoster(hosterName = label, hosterUrl = targetUrl)
            } ?: emptyList()
            if (dynamicList.isNotEmpty()) {
                val combined = (dynamicList + defaultHosters).distinctBy { it.hosterUrl }
                sortHosters(combined)
            } else {
                sortHosters(defaultHosters)
            }
        } catch (_: Exception) {
            sortHosters(defaultHosters)
        }
    }

    private fun sortHosters(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT) ?: PREF_HOSTER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val name = hoster.hosterName

        return when {
            url.contains("vidzee", ignoreCase = true) || name.contains("Vidzee", ignoreCase = true) -> {
                extractVidzeeVideos(hoster)
            }

            url.contains("vidrock", ignoreCase = true) || name.contains("Vidrock", ignoreCase = true) -> {
                extractVidrockVideos(hoster)
            }

            url.contains("vidfast", ignoreCase = true) || name.contains("VidFast", ignoreCase = true) -> {
                extractVidfastVideos(hoster)
            }

            else -> {
                extractGenericVideos(hoster)
            }
        }
    }

    // ============================ Provider: Vidzee =========================
    private suspend fun extractVidzeeVideos(hoster: Hoster): List<Video> = coroutineScope {
        val embedUrl = hoster.hosterUrl
        val isMovie = embedUrl.contains("movie")
        val id = if (isMovie) {
            embedUrl.substringAfter("/movie/").substringBefore("?").substringBefore("/")
        } else {
            embedUrl.substringAfter("/tv/").substringBefore("?").substringBefore("/")
        }

        val season = if (!isMovie) {
            val parts = embedUrl.substringAfter("/tv/").substringBefore("?").split("/")
            if (parts.size >= 2) parts[1] else "1"
        } else {
            "1"
        }

        val ep = if (!isMovie) {
            val parts = embedUrl.substringAfter("/tv/").substringBefore("?").split("/")
            if (parts.size >= 3) parts[2] else "1"
        } else {
            "1"
        }

        val path = if (isMovie) "movie/$id" else "tv/$id/$season/$ep"
        val vidzeeHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://player.vidzee.wtf/")
            .add("Origin", "https://player.vidzee.wtf")
            .build()

        val subTracks = mutableListOf<Track>()
        try {
            val subReq = GET("https://core.vidzee.wtf/subs/$path", vidzeeHeaders)
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

        val servers = listOf(
            Pair("ipcloud", "IPcloud"),
            Pair("v6:Hindi", "Hindi v3"),
            Pair("dcloud", "Dcloud"),
            Pair("tik", "TCloud"),
        )

        val videos = servers.map { (serverId, serverLabel) ->
            async {
                try {
                    val streamReq = GET("https://core.vidzee.wtf/streams/$path?s=$serverId&e=1", vidzeeHeaders)
                    val streamRes = client.newCall(streamReq).execute()
                    if (!streamRes.isSuccessful) return@async emptyList<Video>()
                    val payload = streamRes.parseAs<VidzeePayloadDto>(json)
                    val rawEncrypted = payload.c ?: return@async emptyList<Video>()
                    val ct = Base64.decode(rawEncrypted, Base64.DEFAULT)
                    val decryptedBytes = rc4Drop2048(VIDZEE_RC4_KEY, ct)
                    val streamDto = json.decodeFromString<VidzeeStreamDto>(String(decryptedBytes, Charsets.UTF_8))
                    val streamUrl = streamDto.url ?: return@async emptyList<Video>()
                    val streamLang = streamDto.language ?: ""
                    val langSuffix = if (streamLang.isNotBlank() && streamLang != "Auto") " [$streamLang]" else ""
                    val prefix = "Vidzee ($serverLabel$langSuffix) - "

                    if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                        playlistUtils.extractFromHls(
                            playlistUrl = streamUrl,
                            referer = "https://player.vidzee.wtf/",
                            masterHeaders = vidzeeHeaders,
                            videoHeaders = vidzeeHeaders,
                            videoNameGen = { q -> "$prefix$q" },
                            subtitleList = subTracks,
                        )
                    } else {
                        listOf(
                            Video(
                                videoUrl = streamUrl,
                                videoTitle = "Vidzee ($serverLabel$langSuffix)",
                                headers = vidzeeHeaders,
                                subtitleTracks = subTracks,
                            ),
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        videos.sort()
    }

    // ============================ Provider: Vidrock ========================
    private suspend fun extractVidrockVideos(hoster: Hoster): List<Video> = coroutineScope {
        val embedUrl = hoster.hosterUrl
        val isMovie = embedUrl.contains("movie")
        val id = if (isMovie) {
            embedUrl.substringAfter("/movie/").substringBefore("?").substringBefore("/")
        } else {
            embedUrl.substringAfter("/tv/").substringBefore("?").substringBefore("/")
        }

        val season = if (!isMovie) {
            val parts = embedUrl.substringAfter("/tv/").substringBefore("?").split("/")
            if (parts.size >= 2) parts[1] else "1"
        } else {
            "1"
        }

        val ep = if (!isMovie) {
            val parts = embedUrl.substringAfter("/tv/").substringBefore("?").split("/")
            if (parts.size >= 3) parts[2] else "1"
        } else {
            "1"
        }

        val path = if (isMovie) "movie/$id" else "tv/$id/$season/$ep"
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

        val videos = try {
            val apiReq = GET("https://vidrock.ru/api/$path", vidrockHeaders)
            val apiRes = client.newCall(apiReq).execute()
            val serverMap = apiRes.parseAs<Map<String, VidrockServerDto?>>(json)

            serverMap.mapNotNull { (serverName, serverDto) ->
                if (serverDto == null || serverDto.url.isNullOrBlank()) return@mapNotNull null
                async {
                    try {
                        val streamUrl = decryptVidrock(serverDto.url)
                        if (streamUrl.isBlank()) return@async emptyList<Video>()
                        val lang = serverDto.language ?: ""
                        val langSuffix = if (lang.isNotBlank() && !lang.equals("English", true)) " [$lang]" else ""
                        val prefix = "Vidrock ($serverName$langSuffix) - "

                        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                            playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "https://vidrock.ru/",
                                masterHeaders = vidrockHeaders,
                                videoHeaders = vidrockHeaders,
                                videoNameGen = { q -> "$prefix$q" },
                                subtitleList = subTracks,
                            )
                        } else {
                            listOf(
                                Video(
                                    videoUrl = streamUrl,
                                    videoTitle = "Vidrock ($serverName$langSuffix)",
                                    headers = vidrockHeaders,
                                    subtitleTracks = subTracks,
                                ),
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        } catch (_: Exception) {
            emptyList()
        }

        videos.sort()
    }

    // ============================ Provider: VidFast ========================
    private suspend fun extractVidfastVideos(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl
        val embedUri = Uri.parse(embedUrl)
        val embedHost = embedUri.host ?: "vidfast.pro"
        val isMovie = embedUrl.contains("movie")
        val id = if (isMovie) {
            embedUrl.substringAfter("/movie/").substringBefore("?").substringBefore("/")
        } else {
            embedUrl.substringAfter("/tv/").substringBefore("?").substringBefore("/")
        }
        val season = embedUri.pathSegments.getOrNull(2) ?: "1"
        val ep = embedUri.pathSegments.getOrNull(3) ?: "1"

        val embedHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", embedUrl)
            .add("Origin", "https://$embedHost")
            .build()

        val subTracks = mutableListOf<Track>()
        try {
            val wyzieUrl = if (isMovie) {
                "https://vidfast.pro/wyzie?id=$id"
            } else {
                "https://vidfast.pro/wyzie?id=$id&season=$season&episode=$ep"
            }
            val subReq = GET(wyzieUrl, embedHeaders)
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

        return try {
            val videos = universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = hoster.hosterName)
            val mappedVideos = videos.map { v ->
                Video(
                    videoUrl = v.videoUrl,
                    videoTitle = v.videoTitle,
                    headers = embedHeaders,
                    audioTracks = v.audioTracks,
                    subtitleTracks = (v.subtitleTracks + subTracks).distinctBy { it.url },
                )
            }
            mappedVideos.sort()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ============================ Generic Extractor ========================
    private suspend fun extractGenericVideos(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl
        val embedUri = Uri.parse(embedUrl)
        val embedHost = embedUri.host ?: "1shows.org"
        val embedHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", embedUrl)
            .add("Origin", "https://$embedHost")
            .build()

        val videoHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://$embedHost/")
            .add("Origin", "https://$embedHost")
            .build()

        return try {
            val videos = universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = hoster.hosterName)
            val mappedVideos = videos.map { v ->
                Video(
                    videoUrl = v.videoUrl,
                    videoTitle = v.videoTitle,
                    headers = videoHeaders,
                    audioTracks = v.audioTracks,
                    subtitleTracks = v.subtitleTracks,
                )
            }
            mappedVideos.sort()
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

    // ============================= Crypto Helpers =========================
    private fun rc4Drop2048(key: ByteArray, data: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }
        var i = 0
        j = 0
        for (discard in 0 until 2048) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
        }
        val out = ByteArray(data.size)
        for (k in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val temp = s[i]
            s[i] = s[j]
            s[j] = temp
            val streamByte = s[(s[i] + s[j]) and 0xFF]
            out[k] = (data[k].toInt() xor streamByte).toByte()
        }
        return out
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
            entries = listOf("Vidzee", "Vidrock", "VidFast", "VidLink", "Vidy", "Viduki"),
            entryValues = listOf("Vidzee", "Vidrock", "VidFast", "VidLink", "Vidy", "Viduki"),
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
        private const val PREF_HOSTER_DEFAULT = "Vidzee"

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

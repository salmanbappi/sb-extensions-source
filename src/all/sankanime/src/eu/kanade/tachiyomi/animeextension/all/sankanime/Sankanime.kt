package eu.kanade.tachiyomi.animeextension.all.sankanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URI
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class Sankanime : Source() {

    override val name = "Sankanime"

    override val baseUrl = "https://sankanime.web.id"

    private val apiBaseUrl = "https://www.sankavollerei.web.id/plananimek/api"

    override val lang = "all"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val m3u8Integration by lazy { M3u8Integration(client) }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .addInterceptor(SankanimeApiInterceptor())
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Interceptor ===============================
    inner class SankanimeApiInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalReq = chain.request()
            val urlStr = originalReq.url.toString()

            if (!urlStr.contains("/plananimek/api")) {
                return chain.proceed(originalReq)
            }

            val uri = URI(urlStr)
            val fullPath = uri.rawPath + if (uri.rawQuery != null) "?" + uri.rawQuery else ""
            val method = originalReq.method

            val (reqS, nonce) = SankanimeCrypto.makeRequestHeader(method, fullPath)

            val reqBuilder = originalReq.newBuilder()
                .header("x-req-s", reqS)
                .header("Referer", "$baseUrl/")
                .header("Origin", baseUrl)
                .header("Accept", "application/json, text/plain, */*")
                .header("x-nonce-req", nonce)

            val response = chain.proceed(reqBuilder.build())
            val rawBody = response.body?.string() ?: return response

            val decrypted = SankanimeCrypto.decryptPayload(rawBody, nonce)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val newBody = decrypted.toResponseBody(mediaType)

            return response.newBuilder().body(newBody).build()
        }
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val reqUrl = "$apiBaseUrl/most-popular?page=$page"
        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<AnimeListResultDto>>(json)
        val resultData = dto.results?.data ?: emptyList()
        val animes = resultData.map { it.toSAnime() }
        val totalPages = dto.results?.totalPages ?: 1
        return AnimesPage(animes, page < totalPages)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val reqUrl = "$apiBaseUrl/recently-updated?page=$page"
        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<AnimeListResultDto>>(json)
        val resultData = dto.results?.data ?: emptyList()
        val animes = resultData.map { it.toSAnime() }
        val totalPages = dto.results?.totalPages ?: 1
        return AnimesPage(animes, page < totalPages)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val reqUrl = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            "$apiBaseUrl/search?keyword=$encodedQuery&page=$page"
        } else {
            val params = mutableListOf<String>()
            params.add("page=$page")

            for (filter in filters) {
                when (filter) {
                    is Filters.TypeFilter -> {
                        if (!filter.isDefault()) params.add("format=${filter.toUriPart()}")
                    }

                    is Filters.StatusFilter -> {
                        if (!filter.isDefault()) params.add("status=${filter.toUriPart()}")
                    }

                    is Filters.SeasonFilter -> {
                        if (!filter.isDefault()) params.add("season=${filter.toUriPart()}")
                    }

                    is Filters.SortFilter -> {
                        if (!filter.isDefault()) params.add("sort=${filter.toUriPart()}")
                    }

                    is Filters.GenreFilter -> {
                        val included = filter.getIncluded()
                        if (included.isNotEmpty()) {
                            params.add("genres=${included.joinToString(",")}")
                        }
                    }

                    else -> {}
                }
            }
            "$apiBaseUrl/filter?${params.joinToString("&")}"
        }

        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<AnimeListResultDto>>(json)
        val resultData = dto.results?.data ?: emptyList()
        val animes = resultData.map { it.toSAnime() }
        val totalPages = dto.results?.totalPages ?: 1
        return AnimesPage(animes, page < totalPages && animes.isNotEmpty())
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeId = anime.url.substringBefore("#")
        val reqUrl = "$apiBaseUrl/info?id=$animeId"
        return try {
            val response = client.newCall(GET(reqUrl, headers)).execute()
            val dto = response.parseAs<ApiResponseDto<AnimeInfoResultDto>>(json)
            val detail = dto.results?.data
            if (detail != null) {
                detail.toSAnime(anime)
            } else {
                anime.apply { initialized = true }
            }
        } catch (_: Exception) {
            anime.apply { initialized = true }
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.substringBefore("#")
        val reqUrl = "$apiBaseUrl/episodes/$animeId"
        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<EpisodeListResultDto>>(json)
        val epList = dto.results?.episodes ?: emptyList()
        return epList.map { it.toSEpisode(animeId) }.reversed()
    }

    // ============================ 2-Tier Hosters =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeId = episode.url.substringBefore("#")
        val epNum = episode.url.substringAfter("#ep=", "1")

        val serversUrl = "$apiBaseUrl/servers/$animeId?ep=$epNum"
        val serverList = try {
            val serversResp = client.newCall(GET(serversUrl, headers)).execute()
            val serversDto = serversResp.parseAs<ApiResponseDto<List<ServerItemDto>>>(json)
            serversDto.results ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val serverMap = linkedMapOf<String, MutableSet<String>>()
        for (server in serverList) {
            val sName = server.serverName ?: continue
            val sType = server.type ?: "sub"
            serverMap.getOrPut(sName) { mutableSetOf() }.add(sType)
        }

        val vhdServers = listOf("VHD-1", "VHD-2", "VHD-3")
        for (vhd in vhdServers) {
            if (!serverMap.containsKey(vhd)) {
                serverMap[vhd] = mutableSetOf("sub", "dub")
            }
        }

        return serverMap.map { (name, types) ->
            val typesStr = types.joinToString(",")
            val displayName = when (name.uppercase()) {
                "HD-1" -> "HD-1 (Megaplay)"
                "HD-2" -> "HD-2 (Megaplay-SU)"
                "HD-3" -> "HD-3 (VidNest)"
                "HD-4" -> "HD-4 (Gogoanime)"
                "VHD-1" -> "VHD-1 (Megaplay-Alt)"
                "VHD-2" -> "VHD-2 (Megaplay-SU-Alt)"
                "VHD-3" -> "VHD-3 (TryEmbed)"
                else -> name
            }
            Hoster(
                hosterName = displayName,
                hosterUrl = "$animeId#ep=$epNum&server=${name.lowercase()}&types=$typesStr",
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val animeId = hoster.hosterUrl.substringBefore("#")
        val epNum = hoster.hosterUrl.substringAfter("#ep=").substringBefore("&")
        val server = hoster.hosterUrl.substringAfter("&server=").substringBefore("&")
        val typesStr = hoster.hosterUrl.substringAfter("&types=", "sub,dub")
        val types = typesStr.split(",").filter { it.isNotBlank() }

        val videoList = mutableListOf<Video>()

        for (type in types) {
            val streamUrl = "$apiBaseUrl/stream?id=$animeId?ep=$epNum&server=${URLEncoder.encode(server.lowercase(), "UTF-8")}&type=${URLEncoder.encode(type.lowercase(), "UTF-8")}"
            try {
                val streamResp = client.newCall(GET(streamUrl, headers)).execute()
                val streamDto = streamResp.parseAs<ApiResponseDto<StreamResultDto>>(json)
                val streamResult = streamDto.results ?: continue
                val streamingLink = streamResult.streamingLink
                val m3u8Url = streamingLink?.link?.file ?: continue

                val refererHost = if (!streamResult.iframe.isNullOrBlank()) {
                    try {
                        val uri = URI(streamResult.iframe)
                        "${uri.scheme}://${uri.host}/"
                    } catch (_: Exception) {
                        "https://megaplay.buzz/"
                    }
                } else {
                    "https://megaplay.buzz/"
                }

                val streamHeaders = headers.newBuilder()
                    .set("Referer", refererHost)
                    .set("Origin", refererHost.trimEnd('/'))
                    .build()

                val subtitleTracks = streamingLink.tracks?.mapNotNull { track ->
                    val file = track.file ?: return@mapNotNull null
                    val label = track.label ?: "Subtitle"
                    Track(file, label)
                } ?: emptyList()

                val extractedVideos = playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = refererHost,
                    videoNameGen = { quality -> "[${type.uppercase()}] $quality" },
                    subtitleList = subtitleTracks,
                )

                if (extractedVideos.isNotEmpty()) {
                    videoList.addAll(extractedVideos)
                } else {
                    videoList.add(
                        Video(
                            videoUrl = m3u8Url,
                            videoTitle = "[${type.uppercase()}] Default",
                            headers = streamHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            } catch (_: Exception) {}
        }

        val processedVideos = m3u8Integration.processVideoList(videoList)
        return processedVideos.sortVideos()
    }

    // ============================== Preferences ===========================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }

        val subDubPref = ListPreference(screen.context).apply {
            key = PREF_SUB_DUB_KEY
            title = "Preferred Audio (Sub/Dub)"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue(PREF_SUB_DUB_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }

        screen.addPreference(qualityPref)
        screen.addPreference(subDubPref)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val subDub = preferences.getString(PREF_SUB_DUB_KEY, PREF_SUB_DUB_DEFAULT) ?: PREF_SUB_DUB_DEFAULT

        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(subDub, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality) },
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SUB_DUB_KEY = "pref_sub_dub"
        private const val PREF_SUB_DUB_DEFAULT = "sub"
    }
}

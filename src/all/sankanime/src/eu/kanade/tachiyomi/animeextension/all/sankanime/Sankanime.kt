package eu.kanade.tachiyomi.animeextension.all.sankanime

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
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
            .rateLimit(permits = 3, period = 1.seconds)
            .addInterceptor(CloudflareInterceptor(network.client, headers[USER_AGENT_HEADER]!!))
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add(USER_AGENT_HEADER, "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        // page is 1-indexed in Aniyomi, API is 0-indexed
        val reqUrl = "$apiBaseUrl/popular?page=${page - 1}"
        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<DataWrapperDto<AnimeListDataDto>>>(json)
        val listData = dto.data?.data
        val animes = listData?.movie?.map { it.toSAnime() } ?: emptyList()
        val totalPages = listData?.getTotalPages() ?: 1
        return AnimesPage(animes, page < totalPages)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val reqUrl = "$apiBaseUrl/new?page=${page - 1}"
        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<DataWrapperDto<AnimeListDataDto>>>(json)
        val listData = dto.data?.data
        val animes = listData?.movie?.map { it.toSAnime() } ?: emptyList()
        val totalPages = listData?.getTotalPages() ?: 1
        return AnimesPage(animes, page < totalPages)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val reqUrl = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            "$apiBaseUrl/search?keyword=$encodedQuery&page=${page - 1}"
        } else {
            val params = mutableListOf("page=${page - 1}")
            for (filter in filters) {
                when (filter) {
                    is Filters.TypeFilter -> if (!filter.isDefault()) params.add("type=${filter.toUriPart()}")

                    is Filters.StatusFilter -> if (!filter.isDefault()) params.add("status=${filter.toUriPart()}")

                    is Filters.SeasonFilter -> if (!filter.isDefault()) params.add("season=${filter.toUriPart()}")

                    is Filters.SortFilter -> if (!filter.isDefault()) params.add("sort=${filter.toUriPart()}")

                    is Filters.GenreFilter -> {
                        val included = filter.getIncluded()
                        if (included.isNotEmpty()) params.add("genre_in=${included.joinToString(",")}")
                    }

                    else -> {}
                }
            }
            "$apiBaseUrl/popular?${params.joinToString("&")}"
        }

        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<DataWrapperDto<AnimeListDataDto>>>(json)
        val listData = dto.data?.data
        val animes = listData?.movie?.map { it.toSAnime() } ?: emptyList()
        val totalPages = listData?.getTotalPages() ?: 1
        return AnimesPage(animes, page < totalPages && animes.isNotEmpty())
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = anime.url.substringBefore("#")
        val reqUrl = "$apiBaseUrl/detail/$slug"
        return try {
            val response = client.newCall(GET(reqUrl, headers)).execute()
            val dto = response.parseAs<ApiResponseDto<DataWrapperDto<AnimeDetailDataDto>>>(json)
            val detail = dto.data?.data?.movie
            detail?.toSAnime(anime) ?: anime.apply { initialized = true }
        } catch (_: Exception) {
            anime.apply { initialized = true }
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.substringBefore("#")
        val reqUrl = "$apiBaseUrl/detail/$slug"
        val response = client.newCall(GET(reqUrl, headers)).execute()
        val dto = response.parseAs<ApiResponseDto<DataWrapperDto<AnimeDetailDataDto>>>(json)
        val episodes = dto.data?.data?.movie?.episode ?: emptyList()
        return episodes.map { it.toSEpisode(slug) }.reversed()
    }

    // ========================== Video List ================================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val streamId = episode.url.substringAfter("#").ifBlank { return emptyList() }
        val streamUrl = "$apiBaseUrl/stream/$streamId"

        val videoList = mutableListOf<Video>()

        try {
            val streamResp = client.newCall(GET(streamUrl, headers)).execute()
            val dto = streamResp.parseAs<ApiResponseDto<DataWrapperDto<StreamResultDto>>>(json)
            val streamResult = dto.data?.data ?: return emptyList()

            val m3u8Url = streamResult.getStreamUrl() ?: return emptyList()

            val refererHost = try {
                streamResult.iframe?.let {
                    val uri = URI(it)
                    "${uri.scheme}://${uri.host}/"
                } ?: "$baseUrl/"
            } catch (_: Exception) {
                "$baseUrl/"
            }

            val streamHeaders = headers.newBuilder()
                .set("Referer", refererHost)
                .set("Origin", refererHost.trimEnd('/'))
                .build()

            val subtitleTracks = (streamResult.tracks ?: streamResult.subtitle)
                ?.mapNotNull { track ->
                    val file = track.resolveUrl() ?: return@mapNotNull null
                    val label = track.label ?: "Subtitle"
                    Track(file, label)
                } ?: emptyList()

            val typeLabel = streamResult.type?.uppercase() ?: "SUB"

            val extractedVideos = playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = refererHost,
                videoNameGen = { quality -> "[$typeLabel] $quality" },
                subtitleList = subtitleTracks,
            )

            if (extractedVideos.isNotEmpty()) {
                videoList.addAll(extractedVideos)
            } else {
                videoList.add(
                    Video(
                        videoUrl = m3u8Url,
                        videoTitle = "[$typeLabel] Default",
                        headers = streamHeaders,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        } catch (_: Exception) {}

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
        private const val USER_AGENT_HEADER = "User-Agent"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SUB_DUB_KEY = "pref_sub_dub"
        private const val PREF_SUB_DUB_DEFAULT = "sub"
    }
}

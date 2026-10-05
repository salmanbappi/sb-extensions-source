package eu.kanade.tachiyomi.animeextension.en.ext1mindrama

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.ext1mindrama.extractors.AbyssExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class Onemindrama : Source() {

    override val name = "1MinDrama"

    override val baseUrl = "https://1mindrama.net"

    private val apiUrl = "https://api.1mindrama.net/api"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 5, period = 1.seconds)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val abyssExtractor by lazy { AbyssExtractor(client, playlistUtils) }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")

    // ============================== Popular Anime ==============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$apiUrl/movies?page=$page&limit=20&sort=views&lang=en"
        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<MovieListResponseDto>(json)
        val animeList = dto.data?.map { it.toSAnime() } ?: emptyList()
        val hasNextPage = dto.pagination?.let { it.page != null && it.totalPages != null && it.page < it.totalPages }
            ?: (animeList.size >= 20)
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest Updates =============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = "$apiUrl/movies?page=$page&limit=20&sort=latest_episode_updated_at&lang=en"
        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<MovieListResponseDto>(json)
        val animeList = dto.data?.map { it.toSAnime() } ?: emptyList()
        val hasNextPage = dto.pagination?.let { it.page != null && it.totalPages != null && it.page < it.totalPages }
            ?: (animeList.size >= 20)
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Search Anime ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$apiUrl/movies".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")

        var selectedLang = lang
        var selectedSort = "views"

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search", query.trim())
        }

        for (filter in filters) {
            when (filter) {
                is Filters.SortFilter -> {
                    val sort = filter.toUriPart()
                    if (sort.isNotBlank()) {
                        selectedSort = sort
                    }
                }

                is Filters.StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotBlank() && status != "all") {
                        urlBuilder.addQueryParameter("status", status)
                    }
                }

                is Filters.TypeFilter -> {
                    val type = filter.toUriPart()
                    if (type.isNotBlank() && type != "all") {
                        urlBuilder.addQueryParameter("type", type)
                    }
                }

                is Filters.LanguageFilter -> {
                    val langVal = filter.toUriPart()
                    if (langVal.isNotBlank()) {
                        selectedLang = langVal
                    }
                }

                is Filters.GenreFilter -> {
                    val genreId = filter.toUriPart()
                    if (genreId.isNotBlank()) {
                        urlBuilder.addQueryParameter("tag_ids", genreId)
                    }
                }

                else -> {}
            }
        }

        urlBuilder.addQueryParameter("sort", selectedSort)
        if (selectedLang != "all") {
            urlBuilder.addQueryParameter("lang", selectedLang)
        }

        val response = client.newCall(GET(urlBuilder.build().toString(), headers)).execute()
        val dto = response.parseAs<MovieListResponseDto>(json)
        val animeList = dto.data?.map { it.toSAnime() } ?: emptyList()
        val hasNextPage = dto.pagination?.let { it.page != null && it.totalPages != null && it.page < it.totalPages }
            ?: (animeList.size >= 20)
        return AnimesPage(animeList, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.SortFilter(),
        Filters.StatusFilter(),
        Filters.TypeFilter(),
        Filters.LanguageFilter(),
        Filters.GenreFilter(),
    )

    // ============================== Anime Details ==============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val movieId = anime.url.substringBefore("#").trimEnd('/').substringAfterLast("/")
        val response = client.newCall(GET("$apiUrl/movies/$movieId", headers)).execute()
        val dto = response.parseAs<MovieDetailResponseDto>(json)
        val movie = dto.data ?: return anime

        return anime.apply {
            title = movie.title?.trim() ?: title
            thumbnail_url = resolveImage(movie.poster ?: movie.backdrop) ?: thumbnail_url
            movie.backdrop?.let {
                background_url = resolveImage(it)
            }
            description = movie.description?.trim()
            genre = movie.category?.joinToString(", ")
            status = when (movie.status?.lowercase()) {
                "completed", "finished" -> SAnime.COMPLETED
                "ongoing", "airing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            author = movie.director?.takeIf { it.isNotBlank() }
            initialized = true
        }
    }

    // ============================== Episode List ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val movieId = anime.url.substringBefore("#").trimEnd('/').substringAfterLast("/")
        val response = client.newCall(GET("$apiUrl/episodes?movieId=$movieId&limit=500", headers)).execute()
        val dto = response.parseAs<EpisodeListResponseDto>(json)
        val episodeItems = dto.data ?: emptyList()

        val episodes = episodeItems.mapIndexed { index, ep ->
            val season = ep.seasonNumber ?: ep.season_number ?: 1
            val epNum = ep.episodeNumber ?: ep.episode_number ?: (index + 1)
            val calculatedEpNumber = if (season <= 1) {
                epNum.toFloat()
            } else {
                ((season - 1) * 100 + epNum).toFloat()
            }

            SEpisode.create().apply {
                url = "${anime.url}#season=$season&ep=$epNum"
                name = ep.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                episode_number = calculatedEpNumber
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================== Hoster List ================================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val movieId = episode.url.substringBefore("#").trimEnd('/').substringAfterLast("/")
        val seasonNum = Regex("""season=(\d+)""").find(episode.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val epNum = Regex("""ep=(\d+)""").find(episode.url)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val response = client.newCall(GET("$apiUrl/episodes?movieId=$movieId&limit=500", headers)).execute()
        val dto = response.parseAs<EpisodeListResponseDto>(json)
        val episodeItems = dto.data ?: emptyList()

        val targetEpisode = episodeItems.find { ep ->
            val s = ep.seasonNumber ?: ep.season_number ?: 1
            val e = ep.episodeNumber ?: ep.episode_number ?: 0
            s == seasonNum && e == epNum
        } ?: episodeItems.firstOrNull() ?: return emptyList()

        val hosters = mutableListOf<Hoster>()
        targetEpisode.servers?.forEach { server ->
            val serverName = server.serverName ?: server.server_name ?: "Server"
            val rawUrl = server.fullUrl ?: server.full_url ?: server.streamUrl ?: server.stream_url ?: server.url ?: ""
            if (rawUrl.isBlank()) return@forEach

            val isDirectMp4 = serverName.contains("CDN_1", ignoreCase = true) ||
                server.type?.contains("Direct", ignoreCase = true) == true ||
                rawUrl.contains("/uploads/", ignoreCase = true)

            val finalUrl = if (isDirectMp4) {
                resolveVideoUrl(rawUrl)
            } else when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                rawUrl.startsWith("/") -> "$videoDomain$rawUrl"
                else -> "$videoDomain/$rawUrl"
            }

            val displayName = when {
                isDirectMp4 -> "CDN 1 (Direct MP4)"
                serverName.contains("CDN_3", ignoreCase = true) || finalUrl.contains("abyss", ignoreCase = true) -> "CDN 3 (Abyss)"
                serverName.contains("CDN_2", ignoreCase = true) -> "CDN 2 (Direct MP4)"
                else -> serverName
            }

            hosters.add(
                Hoster(
                    hosterName = displayName,
                    hosterUrl = finalUrl,
                ),
            )
        }

        return hosters
    }

    // ============================== Video List =================================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawHosterUrl = hoster.hosterUrl
        val isDirectMp4 = hoster.hosterName.contains("Direct MP4", ignoreCase = true) ||
            rawHosterUrl.contains("/uploads/", ignoreCase = true)
        val hosterUrl = if (isDirectMp4) resolveVideoUrl(rawHosterUrl) else rawHosterUrl

        val videoList = when {
            hosterUrl.contains("abyssplayer.com", ignoreCase = true) ||
                hosterUrl.contains("abyss.to", ignoreCase = true) ||
                hosterUrl.contains("abysscdn.com", ignoreCase = true) ||
                hosterUrl.contains("short.icu", ignoreCase = true) -> {
                abyssExtractor.videosFromUrl(hosterUrl, referer = "$baseUrl/")
            }

            else -> {
                val videoHeaders = Headers.Builder()
                    .add("User-Agent", DEFAULT_USER_AGENT)
                    .add("Referer", "$baseUrl/")
                    .build()
                listOf(
                    Video(
                        videoUrl = hosterUrl,
                        videoTitle = "${hoster.hosterName} - 1080p",
                        headers = videoHeaders,
                        resolution = 1080,
                    ),
                )
            }
        }

        return videoList.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================== Preferences ================================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
        )
    }

    private fun MovieDto.toSAnime(): SAnime = SAnime.create().apply {
        val movieId = id ?: ""
        url = "/movie-detail/$movieId"
        title = this@toSAnime.title?.trim() ?: ""
        thumbnail_url = resolveImage(poster ?: backdrop)
        backdrop?.let {
            background_url = resolveImage(it)
        }
        genre = category?.joinToString(", ")
        status = when (this@toSAnime.status?.lowercase()) {
            "completed", "finished" -> SAnime.COMPLETED
            "ongoing", "airing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        description = this@toSAnime.description?.trim()
        author = director?.takeIf { it.isNotBlank() }
    }

    @Volatile
    private var coverDomain: String = DEFAULT_COVER_DOMAIN

    @Volatile
    private var videoDomain: String = DEFAULT_VIDEO_DOMAIN

    @Volatile
    private var domainsFetched = false

    private fun fetchDomainSettings() {
        if (domainsFetched) return
        synchronized(this) {
            if (domainsFetched) return
            try {
                val response = client.newCall(GET("$apiUrl/settings/domains", headers)).execute()
                val dto = response.parseAs<DomainSettingsResponseDto>(json)
                dto.data?.coverDomain?.takeIf { it.isNotBlank() }?.let {
                    coverDomain = normalizeDomain(it)
                }
                dto.data?.videoDomain?.takeIf { it.isNotBlank() }?.let {
                    videoDomain = normalizeDomain(it)
                }
                domainsFetched = true
            } catch (_: Exception) {}
        }
    }

    private fun normalizeDomain(domain: String): String {
        val trimmed = domain.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun resolveVideoUrl(rawUrl: String): String {
        fetchDomainSettings()
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            val cleanPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "$videoDomain$cleanPath"
        }
        if (trimmed.contains("/uploads/")) {
            try {
                val httpUrl = trimmed.toHttpUrl()
                return "$videoDomain${httpUrl.encodedPath}"
            } catch (_: Exception) {}
        }
        return trimmed
    }

    private fun resolveImage(path: String?): String? {
        if (path.isNullOrBlank()) return null
        fetchDomainSettings()
        val trimmed = path.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            val cleanPath = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "$coverDomain$cleanPath"
        }
        if (trimmed.contains("/uploads/") || trimmed.contains("/upload/")) {
            try {
                val httpUrl = trimmed.toHttpUrl()
                return "$coverDomain${httpUrl.encodedPath}"
            } catch (_: Exception) {}
        }
        return trimmed
    }

    companion object {
        private const val DEFAULT_COVER_DOMAIN = "https://1min.poke-black-and-white.net"
        private const val DEFAULT_VIDEO_DOMAIN = "https://img.1mindrama.net"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

// ============================== DTO Models ==============================
@Serializable
data class DomainSettingsResponseDto(
    val success: Boolean? = null,
    val data: DomainSettingsDto? = null,
)

@Serializable
data class DomainSettingsDto(
    val coverDomain: String? = null,
    val videoDomain: String? = null,
)

@Serializable
data class MovieListResponseDto(
    val success: Boolean? = null,
    val data: List<MovieDto>? = null,
    val pagination: PaginationDto? = null,
)

@Serializable
data class MovieDetailResponseDto(
    val success: Boolean? = null,
    val data: MovieDto? = null,
)

@Serializable
data class EpisodeListResponseDto(
    val success: Boolean? = null,
    val data: List<EpisodeDto>? = null,
)

@Serializable
data class MovieDto(
    val id: String? = null,
    val title: String? = null,
    val originalTitle: String? = null,
    val slug: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val type: String? = null,
    val category: List<String>? = null,
    val country: String? = null,
    val year: Int? = null,
    val duration: String? = null,
    val quality: String? = null,
    val lang: List<String>? = null,
    val rating: Double? = null,
    val views: Long? = null,
    val totalEpisodes: Int? = null,
    val currentEpisodes: Int? = null,
    val status: String? = null,
    val description: String? = null,
    val director: String? = null,
)

@Serializable
data class EpisodeDto(
    val id: String? = null,
    val movieId: String? = null,
    val movie_id: String? = null,
    val seasonNumber: Int? = null,
    val season_number: Int? = null,
    val episodeNumber: Int? = null,
    val episode_number: Int? = null,
    val title: String? = null,
    val duration: String? = null,
    val servers: List<ServerDto>? = null,
)

@Serializable
data class ServerDto(
    val id: String? = null,
    val serverName: String? = null,
    val server_name: String? = null,
    val type: String? = null,
    val url: String? = null,
    val fullUrl: String? = null,
    val full_url: String? = null,
    val streamUrl: String? = null,
    val stream_url: String? = null,
    val status: String? = null,
)

@Serializable
data class PaginationDto(
    val total: Int? = null,
    val page: Int? = null,
    val limit: Int? = null,
    val totalPages: Int? = null,
)

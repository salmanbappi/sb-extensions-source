package eu.kanade.tachiyomi.animeextension.en.bingr

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Bingr : Source() {

    override val name = "Bingr"

    override val baseUrl = "https://bingr.one"

    private val apiBaseUrl = "https://api.bingr.one/api"

    private val filmuBaseUrl = "https://hianime.filmu.in"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    @Volatile
    private var cachedToken: String = ""

    @Volatile
    private var tokenExpiry: Long = 0L

    private val detailsCache = mutableMapOf<String, AnimeItemDto>()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$apiBaseUrl/anime/trending?page=$page", headers)).execute()
        val dto = response.parseAs<AnimePageDto>(json)
        val results = dto.results ?: emptyList()
        val animes = results.map { it.toSAnime() }
        val hasNextPage = results.isNotEmpty() && (dto.hasNextPage ?: (results.size >= 20))
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(
            GET("$apiBaseUrl/anime/discover?page=$page&sort=START_DATE_DESC", headers),
        ).execute()
        val dto = response.parseAs<AnimePageDto>(json)
        val results = dto.results ?: emptyList()
        val animes = results.map { it.toSAnime() }
        val hasNextPage = results.isNotEmpty() && (dto.hasNextPage ?: (results.size >= 20))
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            "$apiBaseUrl/anime/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", query)
                addQueryParameter("page", page.toString())
            }.build()
        } else {
            "$apiBaseUrl/anime/discover".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                filters.forEach { filter ->
                    when (filter) {
                        is Filters.SortFilter -> {
                            val v = filter.toUriPart()
                            if (v.isNotBlank()) addQueryParameter("sort", v)
                        }

                        is Filters.FormatFilter -> {
                            val v = filter.toUriPart()
                            if (v.isNotBlank()) addQueryParameter("format", v)
                        }

                        is Filters.StatusFilter -> {
                            val v = filter.toUriPart()
                            if (v.isNotBlank()) addQueryParameter("status", v)
                        }

                        is Filters.SeasonFilter -> {
                            val v = filter.toUriPart()
                            if (v.isNotBlank()) addQueryParameter("season", v)
                        }

                        is Filters.GenreFilter -> {
                            val v = filter.toUriPart()
                            if (v.isNotBlank()) addQueryParameter("genre", v)
                        }

                        else -> {}
                    }
                }
            }.build()
        }

        val response = client.newCall(GET(url.toString(), headers)).execute()
        val dto = response.parseAs<AnimePageDto>(json)
        val results = dto.results ?: emptyList()
        val animes = results.map { it.toSAnime() }
        val hasNextPage = results.isNotEmpty() && (dto.hasNextPage ?: (results.size >= 20))
        return AnimesPage(animes, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.SortFilter(),
        Filters.FormatFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.GenreFilter(),
    )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeId = anime.url.substringBefore("|")
        val item = fetchAnimeDetailsDto(animeId)
        return item.toSAnime().apply {
            initialized = true
        }
    }

    private fun fetchAnimeDetailsDto(animeId: String): AnimeItemDto {
        detailsCache[animeId]?.let { return it }
        val response = client.newCall(GET("$apiBaseUrl/anime/$animeId", headers)).execute()
        val item = response.parseAs<AnimeItemDto>(json)
        detailsCache[animeId] = item
        return item
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.substringBefore("|")
        val animeTitle = anime.title
        val episodes = mutableListOf<SEpisode>()
        var chunk = 1
        val maxChunks = 100

        while (chunk <= maxChunks) {
            val response = client.newCall(GET("$apiBaseUrl/anime/$animeId/episodes?chunk=$chunk", headers)).execute()
            val dto = response.parseAs<EpisodesResponseDto>(json)
            val list = dto.episodes ?: emptyList()
            if (list.isEmpty()) break

            for (ep in list) {
                episodes.add(ep.toSEpisode(animeId, animeTitle))
            }

            if (dto.hasNextPage != true) break
            chunk++
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        val animeId = parts.getOrNull(0) ?: ""
        val epNum = parts.getOrNull(1) ?: "1"
        val animeTitle = parts.getOrNull(2) ?: ""

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val hosters = listOf(
            Hoster(
                hosterName = "Hikari [MegaPlay - SUB]",
                hosterUrl = "$animeId|$epNum|hikari_sub|$animeTitle",
            ),
            Hoster(
                hosterName = "Hikari [MegaPlay - DUB]",
                hosterUrl = "$animeId|$epNum|hikari_dub|$animeTitle",
            ),
            Hoster(
                hosterName = "AnimeSalt [Mikazuki]",
                hosterUrl = "$animeId|$epNum|animesalt|$animeTitle",
            ),
        )

        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        val animeId = parts.getOrNull(0) ?: return emptyList()
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        val serverType = parts.getOrNull(2) ?: "hikari_sub"
        val animeTitle = parts.getOrNull(3) ?: ""

        val token = getToken()
        val videos = mutableListOf<Video>()

        when (serverType) {
            "hikari_sub", "hikari_dub" -> {
                val type = if (serverType == "hikari_dub") "dub" else "sub"
                val malId = getMalId(animeId, animeTitle)
                val streamUrl = "$filmuBaseUrl/hianime/megaplay?malId=$malId&ep=$epNum&type=$type"
                val apiHeaders = Headers.Builder()
                    .add("User-Agent", USER_AGENT)
                    .add("x-api-key", token)
                    .build()

                val res = runCatching {
                    client.newCall(GET(streamUrl, apiHeaders)).execute().parseAs<MegaPlayResponseDto>(json)
                }.getOrNull()

                res?.streams?.forEach { stream ->
                    val m3u8Url = stream.url ?: return@forEach
                    val referer = stream.referer?.takeIf { it.isNotBlank() } ?: "https://megaplay.buzz/"
                    val origin = runCatching { "https://${referer.toHttpUrl().host}" }.getOrDefault("https://megaplay.buzz")
                    val subTracks = stream.subtitles?.mapNotNull { it.toTrack(filmuBaseUrl, token) } ?: emptyList()

                    val streamHeaders = Headers.Builder()
                        .add("User-Agent", USER_AGENT)
                        .add("Referer", referer)
                        .add("Origin", origin)
                        .build()

                    val hlsExtractor = PlaylistUtils(client, streamHeaders)
                    val extractedVideos = runCatching {
                        hlsExtractor.extractFromHls(
                            playlistUrl = m3u8Url,
                            referer = referer,
                            masterHeaders = streamHeaders,
                            videoHeaders = streamHeaders,
                            videoNameGen = { quality -> quality },
                            subtitleList = subTracks,
                        )
                    }.getOrDefault(emptyList())

                    if (extractedVideos.isNotEmpty()) {
                        videos.addAll(extractedVideos)
                    } else {
                        videos.add(
                            Video(
                                videoUrl = m3u8Url,
                                videoTitle = "1080p",
                                headers = streamHeaders,
                                subtitleTracks = subTracks,
                            ),
                        )
                    }
                }
            }

            "animesalt" -> {
                val title = if (animeTitle.isNotBlank()) animeTitle else getAnimeTitle(animeId)
                val streamUrl = "$filmuBaseUrl/animesalt/streams".toHttpUrl().newBuilder().apply {
                    addQueryParameter("title", title)
                    addQueryParameter("ep", epNum.toString())
                    addQueryParameter("season", "1")
                }.build()
                val apiHeaders = Headers.Builder()
                    .add("User-Agent", USER_AGENT)
                    .add("x-api-key", token)
                    .build()

                val res = runCatching {
                    client.newCall(GET(streamUrl.toString(), apiHeaders)).execute().parseAs<AnimeSaltResponseDto>(json)
                }.getOrNull()

                res?.streams?.forEach { stream ->
                    var m3u8Url = stream.proxyUrl ?: stream.url ?: return@forEach
                    if (m3u8Url.startsWith("/")) {
                        m3u8Url = "$filmuBaseUrl$m3u8Url"
                    }
                    if (token.isNotBlank() && !m3u8Url.contains("apiKey=")) {
                        m3u8Url += if (m3u8Url.contains("?")) "&apiKey=$token" else "?apiKey=$token"
                    }

                    val referer = stream.referer?.takeIf { it.isNotBlank() } ?: "https://animesalt.cx/"
                    val origin = runCatching { "https://${referer.toHttpUrl().host}" }.getOrDefault("https://animesalt.cx")
                    val subTracks = stream.subtitles?.mapNotNull { it.toTrack(filmuBaseUrl, token) } ?: emptyList()

                    val streamHeaders = Headers.Builder()
                        .add("User-Agent", USER_AGENT)
                        .add("Referer", referer)
                        .add("Origin", origin)
                        .add("x-api-key", token)
                        .build()

                    val hlsExtractor = PlaylistUtils(client, streamHeaders)
                    val extractedVideos = runCatching {
                        hlsExtractor.extractFromHls(
                            playlistUrl = m3u8Url,
                            referer = referer,
                            masterHeaders = streamHeaders,
                            videoHeaders = streamHeaders,
                            videoNameGen = { quality -> quality },
                            subtitleList = subTracks,
                        )
                    }.getOrDefault(emptyList())

                    if (extractedVideos.isNotEmpty()) {
                        videos.addAll(extractedVideos)
                    } else {
                        videos.add(
                            Video(
                                videoUrl = m3u8Url,
                                videoTitle = "${stream.quality ?: 1080}p",
                                headers = streamHeaders,
                                subtitleTracks = subTracks,
                            ),
                        )
                    }
                }
            }
        }

        return videos.sortVideos()
    }

    private fun getMalId(animeId: String, titleFallback: String = ""): Long {
        val details = runCatching { fetchAnimeDetailsDto(animeId) }.getOrNull()
        val directMalId = details?.idMal ?: details?.id ?: animeId.toLongOrNull() ?: 0L
        if (directMalId > 0L) return directMalId

        val queryTitle = if (titleFallback.isNotBlank()) titleFallback else details?.title ?: ""
        if (queryTitle.isNotBlank()) {
            val token = getToken()
            val searchUrl = "$filmuBaseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("q", queryTitle)
            }.build()
            val searchHeaders = Headers.Builder()
                .add("User-Agent", USER_AGENT)
                .add("x-api-key", token)
                .build()

            val searchDto = runCatching {
                client.newCall(GET(searchUrl.toString(), searchHeaders)).execute().parseAs<HikariSearchResponseDto>(json)
            }.getOrNull()

            val match = searchDto?.results?.firstOrNull()
            if (match?.malId != null && match.malId > 0L) {
                return match.malId
            }
        }

        return animeId.toLongOrNull() ?: 0L
    }

    private fun getAnimeTitle(animeId: String): String {
        val details = runCatching { fetchAnimeDetailsDto(animeId) }.getOrNull()
        return details?.title ?: ""
    }

    @Synchronized
    private fun getToken(): String {
        if (cachedToken.isNotBlank() && System.currentTimeMillis() < tokenExpiry) {
            return cachedToken
        }
        return try {
            val emptyBody = ByteArray(0).toRequestBody(null)
            val request = POST("$filmuBaseUrl/token", headers, emptyBody)
            val response = client.newCall(request).execute()
            val tokenDto = response.parseAs<TokenDto>(json)
            val token = tokenDto.token ?: ""
            if (token.isNotBlank()) {
                cachedToken = token
                tokenExpiry = System.currentTimeMillis() + (2 * 60 * 60 * 1000)
            }
            cachedToken
        } catch (_: Exception) {
            cachedToken
        }
    }

    // ============================== Preferences ===========================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_QUALITY_KEY
                title = "Preferred Quality"
                entries = arrayOf("1080p", "720p", "480p", "360p")
                entryValues = arrayOf("1080", "720", "480", "360")
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).commit()
                }
            },
        )

        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_SERVER_KEY
                title = "Preferred Server"
                entries = arrayOf("Hikari [MegaPlay - SUB]", "Hikari [MegaPlay - DUB]", "AnimeSalt [Mikazuki]")
                entryValues = arrayOf("MegaPlay - SUB", "MegaPlay - DUB", "AnimeSalt")
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_SERVER_KEY, newValue as String).commit()
                }
            },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(qualityPref) }
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
            else -> 0
        }
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "MegaPlay - SUB"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class AnimePageDto(
    val page: Int? = null,
    val results: List<AnimeItemDto>? = null,
    val hasNextPage: Boolean? = null,
)

@Serializable
data class AnimeItemDto(
    val id: Long? = null,
    val idMal: Long? = null,
    val type: String? = null,
    val title: String? = null,
    val title_native: String? = null,
    val year: Int? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val color: String? = null,
    val rating: Float? = null,
    val overview: String? = null,
    val status: String? = null,
    val format: String? = null,
    val episodes: Int? = null,
    val genres: List<String>? = null,
    val studios: List<String>? = null,
    val season: String? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        val animeId = this@AnimeItemDto.id ?: 0L
        title = this@AnimeItemDto.title ?: ""
        url = animeId.toString()
        thumbnail_url = this@AnimeItemDto.poster
        description = buildString {
            this@AnimeItemDto.overview?.let { append(it) }
            if (this@AnimeItemDto.rating != null) {
                append("\n\n★ Rating: ${this@AnimeItemDto.rating}/10")
            }
            if (this@AnimeItemDto.year != null) {
                append("\nYear: ${this@AnimeItemDto.year}")
            }
            if (this@AnimeItemDto.format != null) {
                append("\nFormat: ${this@AnimeItemDto.format}")
            }
            if (this@AnimeItemDto.season != null) {
                append("\nSeason: ${this@AnimeItemDto.season}")
            }
            if (!this@AnimeItemDto.studios.isNullOrEmpty()) {
                append("\nStudio: ${this@AnimeItemDto.studios.joinToString()}")
            }
        }.trim()
        genre = this@AnimeItemDto.genres?.joinToString()
        status = when (this@AnimeItemDto.status?.uppercase()) {
            "RELEASING", "AIRING", "ONGOING" -> SAnime.ONGOING
            "FINISHED", "COMPLETED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            "HIATUS" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
        artist = this@AnimeItemDto.studios?.joinToString()
        author = this@AnimeItemDto.studios?.joinToString()
        fetch_type = FetchType.Episodes
    }
}

@Serializable
data class EpisodesResponseDto(
    val total: Int? = null,
    val episodes: List<EpisodeItemDto>? = null,
    val has_next_page: Boolean? = null,
) {
    val hasNextPage: Boolean?
        get() = has_next_page
}

@Serializable
data class EpisodeItemDto(
    val still: String? = null,
    val title: String? = null,
    val rating: Float? = null,
    val episode: Int? = null,
    val air_date: String? = null,
    val overview: String? = null,
) {
    fun toSEpisode(animeId: String, titleFallback: String?): SEpisode = SEpisode.create().apply {
        val epNum = episode ?: 1
        name = if (!title.isNullOrBlank()) "Episode $epNum: $title" else "Episode $epNum"
        episode_number = epNum.toFloat()
        date_upload = parseDate(air_date)
        url = "$animeId|$epNum|${titleFallback ?: ""}"
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }
}

@Serializable
data class TokenDto(
    val token: String? = null,
)

@Serializable
data class MegaPlayResponseDto(
    val ok: Boolean? = null,
    val server: String? = null,
    val malId: Long? = null,
    val episode: Int? = null,
    val type: String? = null,
    val streams: List<MegaPlayStreamDto>? = null,
)

@Serializable
data class MegaPlayStreamDto(
    val server: String? = null,
    val url: String? = null,
    val type: String? = null,
    val referer: String? = null,
    val dubType: String? = null,
    val subtitles: List<SubtitleDto>? = null,
)

@Serializable
data class AnimeSaltResponseDto(
    val ok: Boolean? = null,
    val title: String? = null,
    val episode: Int? = null,
    val streams: List<AnimeSaltStreamDto>? = null,
)

@Serializable
data class AnimeSaltStreamDto(
    val server: String? = null,
    val url: String? = null,
    val proxyUrl: String? = null,
    val type: String? = null,
    val referer: String? = null,
    val quality: Int? = null,
    val dubType: String? = null,
    val subtitles: List<SubtitleDto>? = null,
)

@Serializable
data class SubtitleDto(
    val label: String? = null,
    val lang: String? = null,
    val url: String? = null,
) {
    fun toTrack(baseUrl: String, token: String = ""): Track? {
        val subUrl = url ?: return null
        var fullUrl = if (subUrl.startsWith("/")) "$baseUrl$subUrl" else subUrl
        if (token.isNotBlank() && !fullUrl.contains("apiKey=")) {
            fullUrl += if (fullUrl.contains("?")) "&apiKey=$token" else "?apiKey=$token"
        }
        val displayLang = label ?: lang ?: "Sub"
        return Track(url = fullUrl, lang = displayLang)
    }
}

@Serializable
data class HikariSearchResponseDto(
    val query: String? = null,
    val results: List<HikariSearchResultDto>? = null,
)

@Serializable
data class HikariSearchResultDto(
    val id: Long? = null,
    val malId: Long? = null,
    val title: String? = null,
)

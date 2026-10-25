package eu.kanade.tachiyomi.animeextension.en.watchanimez

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import extensions.utils.UrlUtils
import extensions.utils.injectLazy
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class Watchanimez : Source() {

    override val name = "WatchAnimeZ"

    override val baseUrl = "https://watchanimez.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Application.MODE_PRIVATE)
    }

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    private fun getCatalogBase(): String {
        val source = preferences.getString(PREF_CATALOG_KEY, PREF_CATALOG_DEFAULT) ?: PREF_CATALOG_DEFAULT
        return if (source == "anicore") "$baseUrl/api/v1/anicore" else "$baseUrl/api/v1"
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("${getCatalogBase()}/popular?page=$page&per_page=24", headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val container = dto.results ?: return AnimesPage(emptyList(), false)
        val animes = (container.results ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, container.hasNextPage ?: false)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("${getCatalogBase()}/recent?page=$page&per_page=24", headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val container = dto.results ?: return AnimesPage(emptyList(), false)
        val animes = (container.results ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, container.hasNextPage ?: false)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            "${getCatalogBase()}/search?query=$encodedQuery&page=$page&per_page=24"
        } else {
            val urlBuilder = "${getCatalogBase()}/filter?page=$page&per_page=24".toHttpUrl().newBuilder()
            for (filter in filters) {
                when (filter) {
                    is Filters.SortFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }
                    is Filters.GenreFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("genre", filter.toUriPart())
                    }
                    is Filters.FormatFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("format", filter.toUriPart())
                    }
                    is Filters.StatusFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())
                    }
                    is Filters.SeasonFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())
                    }
                    is Filters.YearFilter -> {
                        if (filter.state.isNotBlank()) urlBuilder.addQueryParameter("year", filter.state.trim())
                    }
                    else -> {}
                }
            }
            urlBuilder.build().toString()
        }

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<ApiResponseDto>(json)
        val container = dto.results ?: return AnimesPage(emptyList(), false)
        val animes = (container.results ?: emptyList()).map { it.toSAnime() }
        return AnimesPage(animes, container.hasNextPage ?: false)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.SortFilter(),
        Filters.GenreFilter(),
        Filters.FormatFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.YearFilter(),
    )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val anilistId = anime.url.substringBefore("#")
        val response = client.newCall(GET("$baseUrl/api/v1/info/$anilistId", headers)).execute()
        val dto = response.parseAs<AnimeDetailsResponseDto>(json)
        val item = dto.results ?: return anime

        return anime.apply {
            title = item.title?.userPreferred
                ?: item.title?.english
                ?: item.title?.romaji
                ?: anime.title
            thumbnail_url = item.coverImage?.extraLarge
                ?: item.coverImage?.large
                ?: anime.thumbnail_url
            description = item.description?.replace(Regex("<[^>]*>"), "")?.trim()
            genre = item.genres?.joinToString(", ")
            status = when (item.status?.uppercase()) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            val studios = item.studios?.filter { it.isAnimationStudio == true }?.mapNotNull { it.name }
            if (!studios.isNullOrEmpty()) {
                author = studios.joinToString(", ")
                artist = author
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val anilistId = anime.url.substringBefore("#")
        val response = client.newCall(GET("$baseUrl/api/v1/info/$anilistId", headers)).execute()
        val dto = response.parseAs<AnimeDetailsResponseDto>(json)
        val item = dto.results ?: return emptyList()

        val streamingMap = item.streamingEpisodes?.associateBy { ep ->
            Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE).find(ep.title ?: "")?.groupValues?.get(1)?.toIntOrNull()
        } ?: emptyMap()

        val totalEpisodes = maxOf(
            item.episodes ?: 0,
            item.streamingEpisodes?.size ?: 0,
            1,
        )

        val malIdStr = item.idMal?.toString() ?: ""

        val episodes = (1..totalEpisodes).map { epNum ->
            val streamingEp = streamingMap[epNum]
            SEpisode.create().apply {
                name = streamingEp?.title ?: "Episode $epNum"
                episode_number = epNum.toFloat()
                url = "$anilistId#ep=$epNum&mal=$malIdStr"
            }
        }

        // Return descending order (Ep N at top, Ep 1 at bottom)
        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Hoster Folders ==========================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val anilistId = episode.url.substringBefore("#")
        val epNum = episode.url.substringAfter("#ep=").substringBefore("&")
        val malId = episode.url.substringAfter("&mal=", "")

        val hosters = mutableListOf<Hoster>()

        // 1. Selfhost (Preferred / Default)
        hosters.add(
            Hoster(
                hosterName = "Selfhost",
                hosterUrl = "type=selfhost&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 2. MegaPlay (Server 15)
        hosters.add(
            Hoster(
                hosterName = "MegaPlay (Server 15)",
                hosterUrl = "type=mega&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 3. ReAnime (Server 7)
        hosters.add(
            Hoster(
                hosterName = "ReAnime (Server 7)",
                hosterUrl = "type=reanime&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        // 4. Blakite (Server 20) - Multi-Language Server
        hosters.add(
            Hoster(
                hosterName = "Blakite (Server 20)",
                hosterUrl = "type=blakite&anilistId=$anilistId&malId=$malId&ep=$epNum",
            ),
        )

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    // ============================ Video Streams ===========================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val params = hoster.hosterUrl.split("&").associate {
            val parts = it.split("=")
            parts[0] to parts.getOrElse(1) { "" }
        }

        val type = params["type"] ?: return emptyList()
        val anilistId = params["anilistId"] ?: ""
        val malId = params["malId"] ?: ""
        val epNum = params["ep"] ?: "1"

        val videos = when (type) {
            "selfhost" -> getSelfhostVideos(malId, epNum)
            "mega" -> getMegaPlayVideos(anilistId, epNum)
            "reanime" -> getReAnimeVideos(anilistId, epNum)
            "blakite" -> getBlakiteVideos(anilistId, epNum)
            else -> emptyList()
        }

        return videos.sortVideos()
    }

    // ---------------------------- Selfhost --------------------------------
    private fun getSelfhostVideos(malId: String, epNum: String): List<Video> {
        if (malId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        try {
            val url = "https://edge1-frankfut.animetvplus.site/dlm/stream/$malId/$epNum"
            val resp = client.newCall(GET(url, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val dto = resp.parseAs<SelfhostStreamDto>(json)
                val masterUrl = dto.master ?: dto.index
                val aid = dto.aid

                if (!masterUrl.isNullOrBlank() && masterUrl.startsWith("http")) {
                    // Fetch subtitles and skip times from meta.json
                    val metaUrl = masterUrl.replace(Regex("""master\.txt(\?.*)?$"""), "meta.json$1")
                    val subTracks = runCatching {
                        val metaResp = client.newCall(GET(metaUrl, streamHeaders)).execute()
                        if (metaResp.isSuccessful) {
                            val meta = metaResp.parseAs<SelfhostMetaDto>(json)
                            meta.subs?.mapNotNull { sub ->
                                val file = sub.file ?: return@mapNotNull null
                                val fullSubUrl = UrlUtils.fixUrl(file, masterUrl)
                                Track(fullSubUrl, sub.label ?: "Subtitles")
                            }
                        } else null
                    }.getOrNull() ?: emptyList()

                    val subVideos = playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "Selfhost - $quality (Sub)" },
                        subtitleList = subTracks,
                    )
                    videos.addAll(subVideos)
                }

                // Check Dub availability
                if (aid != null) {
                    val dubAvailUrl = "https://animetvplus-dub.b-cdn.net/dub/$aid/$epNum/available"
                    val isDubAvailable = runCatching {
                        val dubResp = client.newCall(GET(dubAvailUrl, streamHeaders)).execute()
                        if (dubResp.isSuccessful) {
                            dubResp.parseAs<DubAvailableDto>(json).dub_available == true
                        } else false
                    }.getOrDefault(false)

                    if (isDubAvailable) {
                        val dubUrl = "https://animetvplus-dub.b-cdn.net/dub/$aid/$epNum/index.txt"
                        val dubVideos = playlistUtils.extractFromHls(
                            playlistUrl = dubUrl,
                            referer = baseUrl,
                            masterHeaders = streamHeaders,
                            videoHeaders = streamHeaders,
                            videoNameGen = { quality -> "Selfhost - $quality (Dub)" },
                        )
                        videos.addAll(dubVideos)
                    }
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ---------------------------- MegaPlay (Server 15) --------------------
    private fun getMegaPlayVideos(anilistId: String, epNum: String): List<Video> {
        if (anilistId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        // 1. Sub
        try {
            val subPageUrl = "https://gjheh8.bot-keep.xyz/watch/ani/$anilistId/$epNum/sub"
            val resp = client.newCall(GET(subPageUrl, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val html = resp.body.string()
                val src = Regex("""var\s+SRC\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
                if (!src.isNullOrBlank()) {
                    val subVideos = playlistUtils.extractFromHls(
                        playlistUrl = src,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "MegaPlay - $quality (Sub)" },
                    )
                    videos.addAll(subVideos)
                }
            }
        } catch (_: Exception) {}

        // 2. Dub
        try {
            val dubPageUrl = "https://gjheh8.bot-keep.xyz/watch/ani/$anilistId/$epNum/dub"
            val resp = client.newCall(GET(dubPageUrl, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val html = resp.body.string()
                val src = Regex("""var\s+SRC\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.trim()
                if (!src.isNullOrBlank()) {
                    val dubVideos = playlistUtils.extractFromHls(
                        playlistUrl = src,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "MegaPlay - $quality (Dub)" },
                    )
                    videos.addAll(dubVideos)
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ---------------------------- ReAnime (Server 7) ----------------------
    private fun getReAnimeVideos(anilistId: String, epNum: String): List<Video> {
        if (anilistId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        try {
            val apiUrl = "https://proxy.skillmds.tech/s6/servers/$anilistId/$epNum"
            val resp = client.newCall(GET(apiUrl, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val serverData = resp.parseAs<ReAnimeServersResponseDto>(json)

                // Sub streams
                serverData.sub?.forEach { server ->
                    val dataLink = server.dataLink ?: return@forEach
                    val linkResp = client.newCall(
                        GET("https://proxy.skillmds.tech/s6/stream-from-link?link=${URLEncoder.encode(dataLink, "UTF-8")}", streamHeaders),
                    ).execute()
                    if (linkResp.isSuccessful) {
                        val streamDto = linkResp.parseAs<ReAnimeStreamDto>(json)
                        val streamUrl = streamDto.url ?: streamDto.rawUrl
                        if (!streamUrl.isNullOrBlank()) {
                            val subVideos = playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "https://reanime.to/",
                                masterHeaders = streamHeaders,
                                videoHeaders = streamHeaders,
                                videoNameGen = { quality -> "ReAnime - $quality (Sub)" },
                            )
                            videos.addAll(subVideos)
                        }
                    }
                }

                // Dub streams
                serverData.dub?.forEach { server ->
                    val dataLink = server.dataLink ?: return@forEach
                    val linkResp = client.newCall(
                        GET("https://proxy.skillmds.tech/s6/stream-from-link?link=${URLEncoder.encode(dataLink, "UTF-8")}", streamHeaders),
                    ).execute()
                    if (linkResp.isSuccessful) {
                        val streamDto = linkResp.parseAs<ReAnimeStreamDto>(json)
                        val streamUrl = streamDto.url ?: streamDto.rawUrl
                        if (!streamUrl.isNullOrBlank()) {
                            val dubVideos = playlistUtils.extractFromHls(
                                playlistUrl = streamUrl,
                                referer = "https://reanime.to/",
                                masterHeaders = streamHeaders,
                                videoHeaders = streamHeaders,
                                videoNameGen = { quality -> "ReAnime - $quality (Dub)" },
                            )
                            videos.addAll(dubVideos)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ---------------------------- Blakite (Server 20) ---------------------
    // Multi-Language Server: Streams are served directly without language folder splits
    private fun getBlakiteVideos(anilistId: String, epNum: String): List<Video> {
        if (anilistId.isBlank()) return emptyList()
        val videos = mutableListOf<Video>()
        val streamHeaders = headersBuilder().build()

        try {
            val url = "$baseUrl/api/blakite?tmdbId=$anilistId&season=1&episode=$epNum"
            val resp = client.newCall(GET(url, streamHeaders)).execute()
            if (resp.isSuccessful) {
                val dto = resp.parseAs<BlakiteResponseDto>(json)
                dto.data?.let { data ->
                    val streamUrl = data.url ?: return@let
                    val multiVideos = playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        referer = baseUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "Server 20 - $quality (Multi-Audio)" },
                    )
                    videos.addAll(multiVideos)
                }
            }
        } catch (_: Exception) {}

        return videos
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        // 1. Preferred Server
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_SERVER_KEY
                title = "Preferred Server"
                entries = arrayOf("Selfhost", "MegaPlay (Server 15)", "ReAnime (Server 7)", "Blakite (Server 20)")
                entryValues = arrayOf("Selfhost", "MegaPlay", "ReAnime", "Blakite")
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_SERVER_KEY, newValue as String).commit()
                }
            },
        )

        // 2. Preferred Audio inside Server
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_AUDIO_KEY
                title = "Preferred Audio Priority"
                entries = arrayOf("Sub", "Dub", "Multi-Audio")
                entryValues = arrayOf("Sub", "Dub", "Multi-Audio")
                setDefaultValue(PREF_AUDIO_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_AUDIO_KEY, newValue as String).commit()
                }
            },
        )

        // 3. Preferred Language for Multi-Audio
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_LANG_KEY
                title = "Preferred Language (Multi-Audio)"
                entries = arrayOf("English", "Hindi", "Japanese", "Tamil", "Telugu", "Spanish", "French", "German", "Portuguese")
                entryValues = arrayOf("en", "hi", "ja", "ta", "te", "es", "fr", "de", "pt")
                setDefaultValue(PREF_LANG_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_LANG_KEY, newValue as String).commit()
                }
            },
        )

        // 4. Preferred Quality
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

        // 5. Catalog Source
        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_CATALOG_KEY
                title = "Catalog Source"
                entries = arrayOf("AniList (Default)", "AniCore")
                entryValues = arrayOf("anilist", "anicore")
                setDefaultValue(PREF_CATALOG_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_CATALOG_KEY, newValue as String).commit()
                }
            },
        )
    }

    private fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val audioPref = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val langPref = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(audioPref, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(langPref, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(qualityPref) }
                .thenByDescending { getVideoQualityWeight(it.videoTitle) },
        )
    }

    private fun getVideoQualityWeight(title: String): Int {
        val lower = title.lowercase()
        return when {
            lower.contains("1080p") -> 1080
            lower.contains("720p") -> 720
            lower.contains("480p") -> 480
            lower.contains("360p") -> 360
            else -> 0
        }
    }

    companion object {
        private const val PREF_SERVER_KEY = "pref_server_key"
        private const val PREF_SERVER_DEFAULT = "Selfhost"

        private const val PREF_AUDIO_KEY = "pref_audio_key"
        private const val PREF_AUDIO_DEFAULT = "Sub"

        private const val PREF_LANG_KEY = "pref_lang_key"
        private const val PREF_LANG_DEFAULT = "en"

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_CATALOG_KEY = "pref_catalog_key"
        private const val PREF_CATALOG_DEFAULT = "anilist"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class ApiResponseDto(
    val success: Boolean? = null,
    val results: ResultsContainerDto? = null,
)

@Serializable
data class ResultsContainerDto(
    val page: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
    val hasNextPage: Boolean? = null,
    val results: List<AnimeItemDto>? = null,
)

@Serializable
data class AnimeTitleDto(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class CoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class StudioItemDto(
    val id: Int? = null,
    val name: String? = null,
    val isAnimationStudio: Boolean? = null,
)

@Serializable
data class StreamingEpisodeDto(
    val title: String? = null,
    val thumbnail: String? = null,
    val url: String? = null,
    val site: String? = null,
)

@Serializable
data class AnimeItemDto(
    val id: Int? = null,
    val idMal: Int? = null,
    val title: AnimeTitleDto? = null,
    val description: String? = null,
    val coverImage: CoverImageDto? = null,
    val bannerImage: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val genres: List<String>? = null,
    val averageScore: Double? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val studios: List<StudioItemDto>? = null,
    val streamingEpisodes: List<StreamingEpisodeDto>? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        val animeTitle = title?.userPreferred
            ?: title?.english
            ?: title?.romaji
            ?: "Anime"
        this.title = animeTitle
        thumbnail_url = coverImage?.extraLarge ?: coverImage?.large ?: coverImage?.medium ?: ""
        url = (id ?: 0).toString()
    }
}

@Serializable
data class AnimeDetailsResponseDto(
    val success: Boolean? = null,
    val results: AnimeItemDto? = null,
)

@Serializable
data class SelfhostStreamDto(
    val aid: Int? = null,
    val mal: Int? = null,
    val ep: Int? = null,
    val master: String? = null,
    val index: String? = null,
    val type: String? = null,
)

@Serializable
data class MetaSubtitleDto(
    val label: String? = null,
    val file: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class MetaAudioDto(
    val label: String? = null,
    val lang: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class MetaSkipTimeDto(
    val start: Double? = null,
    val end: Double? = null,
)

@Serializable
data class SelfhostMetaDto(
    val source: String? = null,
    val height: Int? = null,
    val intro: MetaSkipTimeDto? = null,
    val outro: MetaSkipTimeDto? = null,
    val subs: List<MetaSubtitleDto>? = null,
    val audio: List<MetaAudioDto>? = null,
)

@Serializable
data class DubAvailableDto(
    val dub_available: Boolean? = null,
)

@Serializable
data class ReAnimeServerItemDto(
    val dataLink: String? = null,
    val dataType: String? = null,
    val serverName: String? = null,
)

@Serializable
data class ReAnimeServersResponseDto(
    val success: Boolean? = null,
    val sub: List<ReAnimeServerItemDto>? = null,
    val dub: List<ReAnimeServerItemDto>? = null,
)

@Serializable
data class ReAnimeStreamDto(
    val rawUrl: String? = null,
    val url: String? = null,
)

@Serializable
data class BlakiteDataDto(
    val url: String? = null,
)

@Serializable
data class BlakiteResponseDto(
    val success: Boolean? = null,
    val data: BlakiteDataDto? = null,
)

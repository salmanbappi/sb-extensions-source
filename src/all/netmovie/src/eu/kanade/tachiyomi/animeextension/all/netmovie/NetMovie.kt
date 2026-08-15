package eu.kanade.tachiyomi.animeextension.all.netmovie

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class NetMovie : Source() {

    override val name = "NetMovie"

    override val baseUrl = "https://pc.netmovie.site"

    override val lang = "all"

    override val supportsLatest = true

    private val apiUrl: String
        get() = preferences.getString(PREF_API_URL_KEY, DEFAULT_API_URL) ?: DEFAULT_API_URL

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val requestUrl = "${apiUrl}catalog/bollywood?page=$page&limit=$PAGE_SIZE"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val dto = response.parseAs<CatalogResponse>(json)
        val animes = (dto.results ?: emptyList()).map { it.toSAnime() }
        val hasNextPage = page < (dto.pagination?.pages ?: page)
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val requestUrl = "${apiUrl}catalog/hollywood?page=$page&limit=$PAGE_SIZE"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val dto = response.parseAs<CatalogResponse>(json)
        val animes = (dto.results ?: emptyList()).map { it.toSAnime() }
        val hasNextPage = page < (dto.pagination?.pages ?: page)
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val requestUrl = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            "${apiUrl}new-search/movies?title=$encodedQuery&page=$page&limit=$PAGE_SIZE"
        } else {
            var category = "bollywood"
            for (filter in filters) {
                if (filter is Filters.CategoryFilter) {
                    category = filter.toUriPart()
                }
            }
            "${apiUrl}catalog/$category?page=$page&limit=$PAGE_SIZE"
        }

        val response = client.newCall(GET(requestUrl, headers)).execute()
        val dto = response.parseAs<CatalogResponse>(json)
        val animes = (dto.results ?: emptyList()).map { it.toSAnime() }
        val hasNextPage = page < (dto.pagination?.pages ?: page)
        return AnimesPage(animes, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = extractMovieId(anime.url)
        val requestUrl = "${apiUrl}movies/$id"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val wrapper = response.parseAs<MovieDetailWrapper>(json)
        val item = wrapper.result ?: throw Exception("Movie details not found")

        return SAnime.create().apply {
            title = item.titleEn?.ifBlank { item.titleRu } ?: item.titleRu ?: item.title ?: anime.title
            thumbnail_url = item.poster ?: anime.thumbnail_url
            url = "/movies/$id"
            description = buildString {
                if (!item.description.isNullOrBlank()) {
                    append(item.description)
                    append("\n\n")
                }
                val rating = item.ratings?.imdb?.rating
                if (rating != null && rating > 0) {
                    append("★ IMDb: ").append(rating).append("/10\n")
                }
                if (item.year != null && item.year > 0) {
                    append("📅 Year: ").append(item.year).append("\n")
                }
                if (item.duration != null && item.duration > 0) {
                    val h = item.duration / 60
                    val m = item.duration % 60
                    append("⏱ Duration: ").append(if (h > 0) "${h}h ${m}m" else "$m min").append("\n")
                }
                if (!item.languages.isNullOrEmpty()) {
                    append("🌐 Audio: ").append(item.languages.mapNotNull { it.name }.joinToString()).append("\n")
                }
                if (!item.countries.isNullOrEmpty()) {
                    append("📍 Country: ").append(item.countries.mapNotNull { it.name }.joinToString()).append("\n")
                }
            }.trim()
            genre = item.genres?.mapNotNull { it.name }?.joinToString()
            status = if (item.type.equals("movie", ignoreCase = true)) SAnime.COMPLETED else SAnime.UNKNOWN
            fetch_type = FetchType.Episodes
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = extractMovieId(anime.url)
        val requestUrl = "${apiUrl}movies/$id"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val wrapper = response.parseAs<MovieDetailWrapper>(json)
        val item = wrapper.result ?: return listOf(createMovieEpisode(id))

        if (item.type.equals("movie", ignoreCase = true) || item.player.isNullOrEmpty()) {
            return listOf(createMovieEpisode(id))
        }

        // For series, attempt to parse seasons and episodes from embed player
        val iframePlayer = item.player.firstOrNull {
            it.url != null && (it.source.equals("iframe", true) || it.url.contains("rasta428jem.com"))
        }

        if (iframePlayer?.url != null) {
            val serialEpisodes = runCatching {
                extractSerialEpisodes(id, iframePlayer.url)
            }.getOrNull()

            if (!serialEpisodes.isNullOrEmpty()) {
                return serialEpisodes
            }
        }

        return listOf(createMovieEpisode(id))
    }

    private fun createMovieEpisode(id: String): SEpisode = SEpisode.create().apply {
        name = "Full Movie"
        episode_number = 1f
        url = "/movies/$id"
    }

    private fun extractSerialEpisodes(movieId: String, embedUrl: String): List<SEpisode> {
        val embedHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val embedResponse = client.newCall(GET(embedUrl, embedHeaders)).execute()
        val embedHtml = embedResponse.body.string()

        val key = KEY_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()
        var playlistFile = FILE_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()
        playlistFile = playlistFile.replace("\\/", "/")
        if (!playlistFile.startsWith("http")) {
            playlistFile = "https://rasta428jem.com$playlistFile"
        }

        val postHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .set("X-CSRF-TOKEN", key)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val emptyBody = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val playlistResponse = client.newCall(POST(playlistFile, postHeaders, emptyBody)).execute()
        val playlistJson = playlistResponse.body.string().trim()

        val episodes = mutableListOf<SEpisode>()
        val seasonsArray = JSONArray(playlistJson)

        for (s in 0 until seasonsArray.length()) {
            val seasonObj = seasonsArray.optJSONObject(s) ?: continue
            val seasonTitle = seasonObj.optString("title", "Season ${s + 1}")
            val episodesArray = seasonObj.optJSONArray("folder") ?: continue

            for (e in 0 until episodesArray.length()) {
                val epObj = episodesArray.optJSONObject(e) ?: continue
                val epNumStr = epObj.optString("episode", "${e + 1}")
                val epNum = epNumStr.toFloatOrNull() ?: (e + 1).toFloat()
                val epTitle = epObj.optString("title", "$epNumStr episode")

                val subFolder = epObj.optJSONArray("folder")
                var subFile = ""
                if (subFolder != null && subFolder.length() > 0) {
                    val subObj = subFolder.optJSONObject(0)
                    subFile = subObj?.optString("file") ?: ""
                }

                val encodedSubfile = URLEncoder.encode(subFile, "UTF-8")
                val encodedEmbed = URLEncoder.encode(embedUrl, "UTF-8")
                val encodedSeason = URLEncoder.encode(seasonTitle, "UTF-8")

                val episode = SEpisode.create().apply {
                    name = "$seasonTitle - $epTitle"
                    episode_number = (s * 100 + epNum).toFloat()
                    url = "/serial?id=$movieId&season=$encodedSeason&ep=$epNumStr&subfile=$encodedSubfile&embed=$encodedEmbed"
                }
                episodes.add(episode)
            }
        }

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()

        if (episode.url.startsWith("/serial?")) {
            videoList.addAll(extractSerialVideos(episode.url))
        } else {
            val id = extractMovieId(episode.url)
            videoList.addAll(extractMovieVideos(id))
        }

        return videoList.sortVideos()
    }

    private fun extractSerialVideos(url: String): List<Video> {
        val videos = mutableListOf<Video>()
        val queryParams = url.substringAfter("?").split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else "")
        }

        val embedUrl = queryParams["embed"] ?: ""
        val subfile = queryParams["subfile"] ?: ""
        val movieId = queryParams["id"] ?: ""

        if (embedUrl.isNotEmpty()) {
            val embedHeaders = headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .build()
            val embedHtml = client.newCall(GET(embedUrl, embedHeaders)).execute().body.string()
            val key = KEY_REGEX.find(embedHtml)?.groupValues?.get(1)

            if (key != null && subfile.isNotEmpty()) {
                val cleanSubfile = subfile.removePrefix("~")
                val subUrl = "https://rasta428jem.com/playlist/$cleanSubfile.txt"
                val postHeaders = headers.newBuilder()
                    .set("Referer", embedUrl)
                    .set("X-CSRF-TOKEN", key)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                val emptyBody = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val streamRes = client.newCall(POST(subUrl, postHeaders, emptyBody)).execute()
                val streamUrl = streamRes.body.string().trim()

                if (streamUrl.startsWith("http")) {
                    videos.addAll(
                        playlistUtils.extractFromHls(
                            playlistUrl = streamUrl,
                            referer = embedUrl,
                            videoNameGen = { quality -> "HDVB - $quality" },
                        ),
                    )
                }
            }
        }

        if (videos.isEmpty() && movieId.isNotEmpty()) {
            videos.addAll(extractMovieVideos(movieId))
        }

        return videos
    }

    private fun extractMovieVideos(id: String): List<Video> {
        val videos = mutableListOf<Video>()
        val requestUrl = "${apiUrl}movies/$id"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val wrapper = response.parseAs<MovieDetailWrapper>(json)
        val item = wrapper.result ?: return emptyList()

        for (player in item.player ?: emptyList()) {
            val playerUrl = player.url ?: continue
            val translator = player.translator ?: "Stream"

            if (player.source.equals("m3u8", ignoreCase = true) || playerUrl.contains(".m3u8")) {
                runCatching {
                    videos.addAll(
                        playlistUtils.extractFromHls(
                            playlistUrl = playerUrl,
                            referer = baseUrl,
                            videoNameGen = { quality -> "$translator - $quality" },
                        ),
                    )
                }
            } else if (player.source.equals("iframe", ignoreCase = true) || playerUrl.contains("rasta428jem.com")) {
                runCatching {
                    videos.addAll(extractHdvbVideos(playerUrl, translator))
                }
            }
        }

        return videos
    }

    private fun extractHdvbVideos(embedUrl: String, defaultTranslator: String): List<Video> {
        val videos = mutableListOf<Video>()
        val embedHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val embedResponse = client.newCall(GET(embedUrl, embedHeaders)).execute()
        val embedHtml = embedResponse.body.string()

        val key = KEY_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()
        var playlistFile = FILE_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()
        playlistFile = playlistFile.replace("\\/", "/")
        if (!playlistFile.startsWith("http")) {
            playlistFile = "https://rasta428jem.com$playlistFile"
        }

        val postHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .set("X-CSRF-TOKEN", key)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val emptyBody = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val playlistResponse = client.newCall(POST(playlistFile, postHeaders, emptyBody)).execute()
        val playlistJson = playlistResponse.body.string().trim()

        val itemsArray = JSONArray(playlistJson)
        for (i in 0 until itemsArray.length()) {
            val itemObj = itemsArray.optJSONObject(i) ?: continue
            val itemTitle = itemObj.optString("title", defaultTranslator)
            val subfile = itemObj.optString("file")
            if (subfile.isNullOrBlank()) continue

            val cleanSubfile = subfile.removePrefix("~")
            val subUrl = "https://rasta428jem.com/playlist/$cleanSubfile.txt"
            val streamRes = client.newCall(POST(subUrl, postHeaders, emptyBody)).execute()
            val streamUrl = streamRes.body.string().trim()

            if (streamUrl.startsWith("http")) {
                videos.addAll(
                    playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        referer = embedUrl,
                        videoNameGen = { quality -> "$itemTitle - $quality" },
                    ),
                )
            }
        }

        return videos
    }

    private fun extractMovieId(url: String): String = url.substringAfterLast("/").substringBefore("?").substringBefore("&")

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, DEFAULT_QUALITY) ?: DEFAULT_QUALITY
        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality) }
                .thenByDescending { it.videoTitle.contains("1080") }
                .thenByDescending { it.videoTitle.contains("720") }
                .thenByDescending { it.videoTitle.contains("480") }
                .thenByDescending { it.videoTitle.contains("360") },
        )
    }

    // ============================== Preferences ===========================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(DEFAULT_QUALITY)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val DEFAULT_API_URL = "https://mapi.elochkaigolochla.com/api/v1/"
        private const val PREF_API_URL_KEY = "pref_api_url"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val DEFAULT_QUALITY = "1080"
        private const val PAGE_SIZE = 20

        private val KEY_REGEX = Regex(""""key"\s*:\s*"([^"]+)"""")
        private val FILE_REGEX = Regex(""""file"\s*:\s*"([^"]+)"""")
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class CatalogResponse(
    val results: List<MovieItem>? = null,
    val pagination: PaginationDto? = null,
)

@Serializable
data class PaginationDto(
    val page: Int? = null,
    val pages: Int? = null,
    @SerialName("on_page") val onPage: Int? = null,
    val results: Int? = null,
)

@Serializable
data class MovieDetailWrapper(
    val result: MovieItem? = null,
)

@Serializable
data class MovieItem(
    @SerialName("kinopoisk_id") val kinopoiskId: Long? = null,
    val id: Long? = null,
    val type: String? = null,
    val year: Int? = null,
    @SerialName("title_ru") val titleRu: String? = null,
    @SerialName("title_en") val titleEn: String? = null,
    val title: String? = null,
    val name: String? = null,
    val description: String? = null,
    val poster: String? = null,
    val duration: Int? = null,
    val ratings: RatingsDto? = null,
    val genres: List<NamedItemDto>? = null,
    val countries: List<NamedItemDto>? = null,
    val languages: List<NamedItemDto>? = null,
    val player: List<PlayerDto>? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        val effectiveTitle = titleEn?.ifBlank { titleRu } ?: titleRu ?: title ?: name ?: "Untitled"
        this.title = effectiveTitle
        val effectiveId = kinopoiskId ?: id ?: 0L
        this.url = "/movies/$effectiveId"
        this.thumbnail_url = poster
        this.description = this@MovieItem.description
        this.genre = genres?.mapNotNull { it.name }?.joinToString()
        this.status = if (type.equals("movie", ignoreCase = true)) SAnime.COMPLETED else SAnime.UNKNOWN
        this.fetch_type = FetchType.Episodes
    }
}

@Serializable
data class RatingsDto(
    val imdb: RatingValDto? = null,
)

@Serializable
data class RatingValDto(
    val rating: Double? = null,
    val votes: Double? = null,
)

@Serializable
data class NamedItemDto(
    val id: String? = null,
    val name: String? = null,
)

@Serializable
data class PlayerDto(
    val url: String? = null,
    val translator: String? = null,
    @SerialName("translator_id") val translatorId: Long? = null,
    val quality: String? = null,
    val source: String? = null,
    val server: Int? = null,
)

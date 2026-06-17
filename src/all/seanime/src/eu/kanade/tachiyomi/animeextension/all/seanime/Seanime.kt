package eu.kanade.tachiyomi.animeextension.all.seanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.UnmeteredSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import extensions.utils.delegate
import extensions.utils.parseAs
import extensions.utils.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.security.MessageDigest

// ============================== DATA TRANSFER OBJECTS (DTOs) ==============================

@Serializable
data class LibraryCollectionDto(
    val lists: List<LibraryCollectionListDto> = emptyList(),
)

@Serializable
data class LibraryCollectionListDto(
    val type: String? = null,
    val status: String? = null,
    val entries: List<LibraryCollectionEntryDto> = emptyList(),
)

@Serializable
data class LibraryCollectionEntryDto(
    val mediaId: Int,
    val media: BaseMediaDto,
)

@Serializable
data class BaseMediaDto(
    val id: Int,
    val title: MediaTitleDto? = null,
    val coverImage: MediaCoverImageDto? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val season: String? = null,
)

@Serializable
data class MediaTitleDto(
    val userPreferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
)

@Serializable
data class MediaCoverImageDto(
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class AnimeEntryResponseDto(
    val data: AnimeEntryDataDto,
)

@Serializable
data class AnimeEntryDataDto(
    val mediaId: Int,
    val media: BaseMediaDto,
    val episodes: List<EpisodeDto> = emptyList(),
)

@Serializable
data class EpisodeDto(
    val episodeNumber: Int,
    val displayTitle: String? = null,
    val episodeTitle: String? = null,
    val isDownloaded: Boolean = false,
    val localFile: LocalFileDto? = null,
    val episodeMetadata: EpisodeMetadataDto? = null,
)

@Serializable
data class LocalFileDto(
    val path: String,
)

@Serializable
data class EpisodeMetadataDto(
    val summary: String? = null,
    val image: String? = null,
)

@Serializable
data class OnlineEpisodeListResponseDto(
    val data: OnlineEpisodeListResponseDataDto,
)

@Serializable
data class OnlineEpisodeListResponseDataDto(
    val episodes: List<OnlineEpisodeDto> = emptyList(),
)

@Serializable
data class OnlineEpisodeDto(
    val id: String,
    val number: Int,
    val url: String,
    val title: String = "",
)

@Serializable
data class OnlineEpisodeSourceDto(
    val data: OnlineEpisodeSourceDataDto,
)

@Serializable
data class OnlineEpisodeSourceDataDto(
    val number: Int,
    val videoSources: List<OnlineVideoSourceDto> = emptyList(),
)

@Serializable
data class OnlineVideoSourceDto(
    val url: String,
    val server: String,
    val quality: String,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class AniListResponse(
    val data: AniListData,
)

@Serializable
data class AniListData(
    val Page: AniListPage,
)

@Serializable
data class AniListPage(
    val media: List<AniListMedia>,
)

@Serializable
data class AniListMedia(
    val id: Int,
    val title: MediaTitleDto? = null,
    val coverImage: MediaCoverImageDto? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
)

// ============================== MAIN EXTENSION CLASS ==============================

class Seanime :
    Source(),
    UnmeteredSource,
    ConfigurableAnimeSource {

    override val name = "Seanime"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 85274903847291047L

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL, DEFAULT_BASE_URL)!!.removeSuffix("/")

    override val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    // Cache to prevent repetitive mappings during sorting
    private var cachedHeaders: okhttp3.Headers? = null
    private var lastPassword = ""

    private fun getSeanimeHeaders(): okhttp3.Headers {
        val password = preferences.getString(PREF_SERVER_PASSWORD, DEFAULT_SERVER_PASSWORD)!!
        if (cachedHeaders != null && password == lastPassword) {
            return cachedHeaders!!
        }
        val builder = okhttp3.Headers.Builder()
        builder.add("User-Agent", "Aniyomi-Seanime-Extension")
        if (password.isNotBlank()) {
            val token = sha256(password)
            builder.add("X-Seanime-Token", token)
        }
        lastPassword = password
        cachedHeaders = builder.build()
        return cachedHeaders!!
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseSeasonNumber(title: String): Double {
        val regex = Regex("Season\\s+(\\d+)|S(\\d+)", RegexOption.IGNORE_CASE)
        val match = regex.find(title)
        if (match != null) {
            val group1 = match.groups[1]?.value
            val group2 = match.groups[2]?.value
            return (group1 ?: group2)?.toDoubleOrNull() ?: 1.0
        }
        return 1.0
    }

    private fun LibraryCollectionEntryDto.toSAnime(): SAnime = SAnime.create().apply {
        val animeTitle = media.title?.userPreferred
            ?: media.title?.english
            ?: media.title?.romaji
            ?: "Anime $mediaId"
        title = animeTitle
        thumbnail_url = media.coverImage?.large ?: media.coverImage?.medium
        description = media.description
        genre = media.genres.joinToString(", ")
        status = when (media.status?.uppercase()) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        season_number = parseSeasonNumber(animeTitle)
        url = mediaId.toString()
        initialized = true
    }

    private fun AniListMedia.toSAnime(): SAnime = SAnime.create().apply {
        val animeTitle = this@toSAnime.title?.userPreferred
            ?: this@toSAnime.title?.english
            ?: this@toSAnime.title?.romaji
            ?: "Anime $id"
        title = animeTitle
        thumbnail_url = coverImage?.large
        description = this@toSAnime.description
        genre = genres.joinToString(", ")
        status = when (this@toSAnime.status?.uppercase()) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        season_number = parseSeasonNumber(animeTitle)
        url = id.toString()
        initialized = true
    }

    // ============================== SOURCE INTERFACE OVERRIDES ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val headers = getSeanimeHeaders()
        val response = client.newCall(GET("$baseUrl/api/v1/library/collection", headers)).await()
        if (response.isSuccessful) {
            val collection = response.parseAs<LibraryCollectionDto>(json)
            val animeList = collection.lists.flatMap { list ->
                list.entries.map { it.toSAnime() }
            }.distinctBy { it.url }
            return AnimesPage(animeList, false)
        } else {
            response.close()
            throw Exception("Failed to fetch library collection (Code: ${response.code})")
        }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage = getPopularAnime(page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) {
            return getPopularAnime(page)
        }

        val mode = preferences.getString(PREF_STREAMING_MODE, DEFAULT_STREAMING_MODE)!!
        if (mode == MODE_ONLINE) {
            return searchAniList(page, query)
        } else {
            val headers = getSeanimeHeaders()
            val response = client.newCall(GET("$baseUrl/api/v1/library/collection", headers)).await()
            if (response.isSuccessful) {
                val collection = response.parseAs<LibraryCollectionDto>(json)
                val filteredList = collection.lists.flatMap { list ->
                    list.entries.map { it.toSAnime() }
                }.distinctBy { it.url }.filter { anime ->
                    anime.title.contains(query, ignoreCase = true) ||
                        (anime.genre?.contains(query, ignoreCase = true) == true)
                }
                return AnimesPage(filteredList, false)
            } else {
                response.close()
                throw Exception("Failed to search library collection (Code: ${response.code})")
            }
        }
    }

    private suspend fun searchAniList(page: Int, query: String): AnimesPage {
        val graphQLQuery = """
            query (${'$'}search: String, ${'$'}page: Int) {
              Page (page: ${'$'}page, perPage: 20) {
                media (search: ${'$'}search, type: ANIME) {
                  id
                  title {
                    userPreferred
                    english
                    romaji
                  }
                  coverImage {
                    large
                  }
                  description
                  status
                  genres
                }
              }
            }
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put("query", graphQLQuery)
            put(
                "variables",
                buildJsonObject {
                    put("search", query)
                    put("page", page)
                },
            )
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = bodyJson.toString().toRequestBody(mediaType)

        val response = client.newCall(
            okhttp3.Request.Builder()
                .url("https://graphql.anilist.co")
                .post(requestBody)
                .build(),
        ).await()

        if (response.isSuccessful) {
            val aniListResponse = response.parseAs<AniListResponse>(json)
            val list = aniListResponse.data.Page.media.map { it.toSAnime() }
            return AnimesPage(list, list.size >= 20)
        } else {
            response.close()
            throw Exception("Failed to search AniList (Code: ${response.code})")
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val mediaId = anime.url.toInt()
        val headers = getSeanimeHeaders()
        val response = client.newCall(GET("$baseUrl/api/v1/library/anime-entry/$mediaId", headers)).await()
        if (response.isSuccessful) {
            val entryDto = response.parseAs<AnimeEntryResponseDto>(json)
            val media = entryDto.data.media
            return SAnime.create().apply {
                val animeTitle = media.title?.userPreferred
                    ?: media.title?.english
                    ?: media.title?.romaji
                    ?: "Anime $mediaId"
                title = animeTitle
                thumbnail_url = media.coverImage?.large ?: media.coverImage?.medium
                description = media.description
                genre = media.genres.joinToString(", ")
                status = when (media.status?.uppercase()) {
                    "FINISHED" -> SAnime.COMPLETED
                    "RELEASING" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                season_number = parseSeasonNumber(animeTitle)
                url = mediaId.toString()
                initialized = true
            }
        } else {
            response.close()
            return anime
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val mediaId = anime.url.toInt()
        val mode = preferences.getString(PREF_STREAMING_MODE, DEFAULT_STREAMING_MODE)!!
        val headers = getSeanimeHeaders()

        if (mode == MODE_ONLINE) {
            val provider = preferences.getString(PREF_PREFERRED_PROVIDER, DEFAULT_PREFERRED_PROVIDER)!!
            val dubbed = preferences.getBoolean(PREF_DUBBED, DEFAULT_DUBBED)
            val body = buildJsonObject {
                put("mediaId", mediaId)
                put("dubbed", dubbed)
                put("provider", provider)
            }.toRequestBody(json)

            val response = client.newCall(POST("$baseUrl/api/v1/onlinestream/episode-list", headers, body)).await()
            if (response.isSuccessful) {
                val epListResponse = response.parseAs<OnlineEpisodeListResponseDto>(json)
                return epListResponse.data.episodes.map { ep ->
                    SEpisode.create().apply {
                        url = "online:$mediaId:${ep.number}:$provider:$dubbed"
                        name = ep.title.ifBlank { "Episode ${ep.number}" }
                        episode_number = ep.number.toFloat()
                    }
                }.reversed()
            } else {
                response.close()
                throw Exception("Failed to fetch online episodes (Code: ${response.code})")
            }
        } else {
            val response = client.newCall(GET("$baseUrl/api/v1/library/anime-entry/$mediaId", headers)).await()
            if (response.isSuccessful) {
                val entryDto = response.parseAs<AnimeEntryResponseDto>(json)
                val allEpisodes = entryDto.data.episodes

                val filteredEpisodes = if (mode == MODE_LOCAL) {
                    allEpisodes.filter { it.isDownloaded }
                } else {
                    allEpisodes
                }

                return filteredEpisodes.map { ep ->
                    SEpisode.create().apply {
                        if (mode == MODE_LOCAL) {
                            url = "local:${ep.localFile?.path}"
                        } else {
                            url = "torrent:$mediaId:${ep.episodeNumber}:${ep.episodeNumber}"
                        }
                        val epName = ep.displayTitle ?: "Episode ${ep.episodeNumber}"
                        val epSubTitle = ep.episodeTitle
                        name = if (!epSubTitle.isNullOrBlank()) "$epName - $epSubTitle" else epName
                        episode_number = ep.episodeNumber.toFloat()
                        summary = ep.episodeMetadata?.summary
                        preview_url = ep.episodeMetadata?.image
                    }
                }.reversed()
            } else {
                response.close()
                throw Exception("Failed to fetch library entry (Code: ${response.code})")
            }
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlStr = episode.url
        val headers = getSeanimeHeaders()

        when {
            urlStr.startsWith("local:") -> {
                val filePath = urlStr.removePrefix("local:")
                val encodedPath = URLEncoder.encode(filePath, "UTF-8")
                val videoUrl = "$baseUrl/api/v1/mediastream/file?path=$encodedPath"
                return listOf(Video(videoUrl = videoUrl, videoTitle = "Local Server (Direct Stream)", headers = headers))
            }

            urlStr.startsWith("torrent:") -> {
                val parts = urlStr.removePrefix("torrent:").split(":")
                val mediaId = parts[0].toInt()
                val episodeNumber = parts[1].toInt()
                val aniDBEpisode = parts[2]

                val body = buildJsonObject {
                    put("mediaId", mediaId)
                    put("episodeNumber", episodeNumber)
                    put("aniDBEpisode", aniDBEpisode)
                    put("autoSelect", true)
                    put("playbackType", "default")
                }.toRequestBody(json)

                val response = client.newCall(POST("$baseUrl/api/v1/torrentstream/start", headers, body)).await()
                if (response.isSuccessful) {
                    response.close()
                    val streamUrl = "$baseUrl/api/v1/torrentstream/stream/video.mp4"
                    return listOf(Video(videoUrl = streamUrl, videoTitle = "Torrent Stream (Seanime Auto-Select)", headers = headers))
                } else {
                    response.close()
                    throw Exception("Failed to start torrent stream (Code: ${response.code})")
                }
            }

            urlStr.startsWith("online:") -> {
                val parts = urlStr.removePrefix("online:").split(":")
                val mediaId = parts[0].toInt()
                val episodeNumber = parts[1].toInt()
                val provider = parts[2]
                val dubbed = parts[3].toBoolean()

                val body = buildJsonObject {
                    put("episodeNumber", episodeNumber)
                    put("mediaId", mediaId)
                    put("provider", provider)
                    put("dubbed", dubbed)
                }.toRequestBody(json)

                val response = client.newCall(POST("$baseUrl/api/v1/onlinestream/episode-source", headers, body)).await()
                if (response.isSuccessful) {
                    val sourceDto = response.parseAs<OnlineEpisodeSourceDto>(json)
                    val videoSources = sourceDto.data.videoSources

                    val list = videoSources.flatMap { vs ->
                        val videoHeaders = okhttp3.Headers.Builder().apply {
                            headers.forEach { (name, value) -> add(name, value) }
                            vs.headers.forEach { (k, v) -> set(k, v) }
                        }.build()

                        listOf(Video(videoUrl = vs.url, videoTitle = "${vs.server} (${vs.quality})", headers = videoHeaders))
                    }

                    val preferredQuality = preferences.getString(PREF_PREFERRED_QUALITY, DEFAULT_PREFERRED_QUALITY)!!
                    val preferredServer = preferences.getString(PREF_PREFERRED_SERVER, DEFAULT_PREFERRED_SERVER)!!

                    return list.sortedWith(
                        compareByDescending<Video> { it.videoTitle.contains(preferredQuality, ignoreCase = true) }
                            .thenByDescending { it.videoTitle.contains(preferredServer, ignoreCase = true) },
                    )
                } else {
                    response.close()
                    throw Exception("Failed to fetch online stream sources (Code: ${response.code})")
                }
            }

            else -> return emptyList()
        }
    }

    private suspend fun okhttp3.Call.await(): Response = withContext(Dispatchers.IO) { execute() }

    // ============================== PREFERENCES SETUP ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL
            title = "Base URL"
            summary = "Seanime Server URL (default: http://127.0.0.1:43211)"
            setDefaultValue(DEFAULT_BASE_URL)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val newUrl = (newValue as String).trim()
                    newUrl.removeSuffix("/")
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SERVER_PASSWORD
            title = "Server Password"
            summary = "Password of the Seanime server (leave blank if none)"
            setDefaultValue(DEFAULT_SERVER_PASSWORD)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_STREAMING_MODE
            title = "Streaming Mode"
            entries = arrayOf("Local Library Only", "Torrent Stream", "Online Stream")
            entryValues = arrayOf(MODE_LOCAL, MODE_TORRENT, MODE_ONLINE)
            setDefaultValue(DEFAULT_STREAMING_MODE)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PREFERRED_QUALITY
            title = "Preferred Quality (Online Stream)"
            entries = arrayOf("Source", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("Source", "1080p", "720p", "480p", "360p")
            setDefaultValue(DEFAULT_PREFERRED_QUALITY)
            summary = "%s"
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PREFERRED_SERVER
            title = "Preferred Server Keyword"
            summary = "Preferred server/host for online streaming (e.g. gogoplay)"
            setDefaultValue(DEFAULT_PREFERRED_SERVER)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PREFERRED_PROVIDER
            title = "Default Online Stream Provider"
            summary = "Provider extension ID used for online streams (e.g. gogoanime)"
            setDefaultValue(DEFAULT_PREFERRED_PROVIDER)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DUBBED
            title = "Dubbed Mode"
            summary = "Check to request dubbed versions of online streams if available"
            setDefaultValue(DEFAULT_DUBBED)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_BASE_URL = "pref_base_url"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:43211"

        private const val PREF_SERVER_PASSWORD = "pref_server_password"
        private const val DEFAULT_SERVER_PASSWORD = ""

        private const val PREF_STREAMING_MODE = "pref_streaming_mode"
        private const val MODE_LOCAL = "local"
        private const val MODE_TORRENT = "torrent"
        private const val MODE_ONLINE = "online"
        private const val DEFAULT_STREAMING_MODE = MODE_LOCAL

        private const val PREF_PREFERRED_QUALITY = "pref_preferred_quality"
        private const val DEFAULT_PREFERRED_QUALITY = "Source"

        private const val PREF_PREFERRED_SERVER = "pref_preferred_server"
        private const val DEFAULT_PREFERRED_SERVER = "gogoplay"

        private const val PREF_PREFERRED_PROVIDER = "pref_preferred_provider"
        private const val DEFAULT_PREFERRED_PROVIDER = "gogoanime"

        private const val PREF_DUBBED = "pref_dubbed"
        private const val DEFAULT_DUBBED = false
    }
}

package eu.kanade.tachiyomi.animeextension.en.onetouchtv

import android.util.Base64
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

class OneTouchTV : Source() {

    override val name = "OneTouch TV"

    override val baseUrl = "https://api3.devcorp.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    private val m3u8Integration by lazy { M3u8Integration(client) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    private val aesKey = "im72charPasswordofdInitVectorStm".toByteArray(Charsets.UTF_8)
    private val aesIv = "im72charPassword".toByteArray(Charsets.UTF_8)

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        .add("Referer", "https://onetouchtv.xyz/")

    // ============================== Decryption Helper ==============================
    private fun decrypt(encrypted: String): String {
        val clean = encrypted
            .replace("-._", "/")
            .replace("-._", "/")
            .replace("@", "+")
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")

        val pad = (4 - clean.length % 4) % 4
        val base64Str = if (pad > 0) clean + "=".repeat(pad) else clean

        val cipherBytes = Base64.decode(base64Str, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val ivSpec = IvParameterSpec(aesIv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decryptedBytes = cipher.doFinal(cipherBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private inline fun <reified T> fetchEncrypted(path: String): T? {
        val url = if (path.startsWith("http")) path else "$baseUrl$path"
        val response = client.newCall(GET(url, headers)).execute()
        if (!response.isSuccessful) return null
        val encryptedText = response.body.string()
        val jsonString = decrypt(encryptedText)
        val apiResponse = json.decodeFromString<ApiResponseDto<T>>(jsonString)
        return apiResponse.result
    }

    // ============================== Popular Anime ==============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val items = fetchEncrypted<List<ContentItemDto>>("/vod/popular?page=$page") ?: emptyList()
        val animeList = items.map { it.toSAnime() }
        val hasNextPage = items.size >= 30
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Latest Updates =============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val items = fetchEncrypted<List<ContentItemDto>>("/vod/filter?status=ongoing&page=$page") ?: emptyList()
        val animeList = items.map { it.toSAnime() }
        val hasNextPage = items.size >= 30
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Search Anime ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            if (page > 1) return AnimesPage(emptyList(), false)
            val searchUrl = "$baseUrl/vod/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query.trim())
                .build()
                .toString()

            val items = fetchEncrypted<List<ContentItemDto>>(searchUrl) ?: emptyList()
            val animeList = items.map { it.toSAnime() }
            return AnimesPage(animeList, false)
        }

        val params = Filters.getFilterParams(filters)
        val filterUrl = "$baseUrl/vod/filter".toHttpUrl().newBuilder().apply {
            if (params.type.isNotBlank()) addQueryParameter("type", params.type)
            if (params.country.isNotBlank()) addQueryParameter("country", params.country)
            if (params.status.isNotBlank()) addQueryParameter("status", params.status)
            if (params.year.isNotBlank()) addQueryParameter("year", params.year)
            if (params.genres.isNotBlank()) addQueryParameter("genres", params.genres)
            addQueryParameter("page", page.toString())
        }.build().toString()

        val items = fetchEncrypted<List<ContentItemDto>>(filterUrl) ?: emptyList()
        val animeList = items.map { it.toSAnime() }
        val hasNextPage = items.size >= 30
        return AnimesPage(animeList, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // ============================== Anime Details ==============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val contentId = anime.url.removePrefix("/vod/").substringBefore("#").substringBefore("?")
        val detail = fetchEncrypted<ContentDetailDto>("/vod/$contentId/detail")

        return anime.apply {
            if (detail != null) {
                title = detail.title?.trim() ?: title
                thumbnail_url = detail.image ?: detail.poster ?: thumbnail_url

                val desc = buildString {
                    detail.description?.let { append(it.trim()).append("\n\n") }
                    detail.otherTitles?.takeIf { it.isNotEmpty() }?.let {
                        append("Other Names: ").append(it.joinToString(", ")).append("\n")
                    }
                    detail.country?.let { append("Country: ").append(it.replaceFirstChar { c -> c.uppercase() }).append("\n") }
                    detail.type?.let { append("Type: ").append(it.replaceFirstChar { c -> c.uppercase() }).append("\n") }
                    detail.year?.let { append("Year: ").append(it).append("\n") }
                    detail.rating?.takeIf { it != "0.0" && it != "0" }?.let { append("Rating: ").append(it).append("/10\n") }
                    detail.releaseDate?.let { append("Release Date: ").append(it).append("\n") }
                    detail.aired_start?.let { append("Aired: ").append(it).append(detail.aired_end?.let { e -> " to $e" } ?: "").append("\n") }
                }.trim()

                description = desc.ifBlank { null }
                genre = detail.genres?.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
                status = when (detail.status?.lowercase()) {
                    "completed" -> SAnime.COMPLETED
                    "ongoing" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
                author = detail.director
                artist = detail.actors?.mapNotNull { it.name }?.joinToString(", ")?.takeIf { it.isNotBlank() }
            }
            initialized = true
        }
    }

    // ============================== Episode List ===============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val contentId = anime.url.removePrefix("/vod/").substringBefore("#").substringBefore("?")
        val detail = fetchEncrypted<ContentDetailDto>("/vod/$contentId/detail") ?: return emptyList()
        val epList = detail.episodes ?: emptyList()

        val episodes = epList.mapIndexed { index, ep ->
            val epNumStr = ep.episode?.trim() ?: (index + 1).toString()
            val epNumber = epNumStr.toFloatOrNull() ?: (index + 1).toFloat()
            val playId = ep.playId ?: epNumStr

            SEpisode.create().apply {
                url = "/vod/$contentId#playId=$playId&ep=$epNumStr"
                name = if (epNumStr.isBlank() || epNumStr == "0" || detail.type == "movie") "Movie" else "Episode $epNumStr"
                episode_number = epNumber
                scanlator = if (ep.isSub == true) "Sub" else null
                date_upload = parseDate(ep.released_at)
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================== Hoster List ================================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val contentId = episode.url.removePrefix("/vod/").substringBefore("#")
        val playId = Regex("""playId=([^&]+)""").find(episode.url)?.groupValues?.get(1) ?: "1"

        val streamData = fetchEncrypted<EpisodeStreamDto>("/vod/$contentId/episode/$playId") ?: return emptyList()
        val sources = streamData.sources ?: emptyList()

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return sources.mapIndexed { idx, src ->
            val serverName = src.name?.replaceFirstChar { it.uppercase() } ?: "Server ${idx + 1}"
            val quality = src.quality?.let { " ($it)" } ?: ""
            Hoster(
                hosterName = "$serverName$quality",
                hosterUrl = "${episode.url}&serverIdx=$idx",
            )
        }.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(prefServer, ignoreCase = true) },
        )
    }

    // ============================== Video List =================================
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val contentId = hoster.hosterUrl.removePrefix("/vod/").substringBefore("#")
        val playId = Regex("""playId=([^&]+)""").find(hoster.hosterUrl)?.groupValues?.get(1) ?: "1"
        val serverIdx = Regex("""serverIdx=(\d+)""").find(hoster.hosterUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val streamData = fetchEncrypted<EpisodeStreamDto>("/vod/$contentId/episode/$playId") ?: return emptyList()
        val sources = streamData.sources ?: emptyList()
        if (sources.isEmpty()) return emptyList()

        val source = sources.getOrNull(serverIdx) ?: sources.first()
        val streamUrl = source.url ?: return emptyList()

        val subtitles = (streamData.track ?: emptyList()).mapNotNull { trk ->
            val file = trk.file ?: return@mapNotNull null
            val name = trk.name ?: trk.code ?: "Subtitle"
            Track(file, name)
        }

        val streamHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()

        val serverName = source.name?.replaceFirstChar { it.uppercase() } ?: "OneTouch"

        val rawVideos = if (streamUrl.contains(".m3u8") || source.type.equals("hls", ignoreCase = true)) {
            runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "$baseUrl/",
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                    videoNameGen = { q -> "$serverName - $q" },
                    subtitleList = subtitles,
                )
            }.getOrDefault(emptyList())
        } else {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "$serverName - ${source.quality ?: "Default"}",
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }

        val videos = if (rawVideos.isEmpty()) {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "$serverName - ${source.quality ?: "Auto"}",
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        } else {
            rawVideos
        }

        val processed = m3u8Integration.processVideoList(videos)
        return processed.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val qualityPref = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val subPref = preferences.getString(PREF_SUB_LANG_KEY, PREF_SUB_LANG_DEFAULT) ?: PREF_SUB_LANG_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(qualityPref, ignoreCase = true) }
                .thenByDescending { getVideoQualityWeight(it.videoTitle) }
                .thenByDescending { it.subtitleTracks.any { s -> s.lang.contains(subPref, ignoreCase = true) } },
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
            lower.contains("auto") -> 500
            else -> 0
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            dateFormat.parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun ContentItemDto.toSAnime(): SAnime = SAnime.create().apply {
        val contentId = id ?: ""
        url = "/vod/$contentId"
        title = this@toSAnime.title?.trim() ?: ""
        thumbnail_url = image ?: poster
    }

    // ============================== Preferences ================================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_QUALITY_KEY
                title = "Preferred Quality"
                entries = arrayOf("1080p", "720p", "480p", "360p", "Auto")
                entryValues = arrayOf("1080", "720", "480", "360", "Auto")
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
                entries = arrayOf("Loklok", "Default")
                entryValues = arrayOf("Loklok", "Default")
                setDefaultValue(PREF_SERVER_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_SERVER_KEY, newValue as String).commit()
                }
            },
        )

        screen.addPreference(
            ListPreference(context).apply {
                key = PREF_SUB_LANG_KEY
                title = "Preferred Subtitle Language"
                entries = arrayOf(
                    "English",
                    "Spanish",
                    "French",
                    "Portuguese",
                    "Indonesian",
                    "Vietnamese",
                    "Thai",
                    "Chinese",
                    "Arabic",
                    "Turkish",
                    "Russian",
                    "Filipino",
                    "Hindi",
                    "German",
                    "Bangla",
                )
                entryValues = arrayOf(
                    "English",
                    "Espa",
                    "Fran",
                    "Portu",
                    "Indo",
                    "Vi",
                    "Thai",
                    "Chinese",
                    "Arabic",
                    "Turk",
                    "Russ",
                    "Filipino",
                    "Hindi",
                    "Deutsch",
                    "Bangla",
                )
                setDefaultValue(PREF_SUB_LANG_DEFAULT)
                summary = "%s"
                setOnPreferenceChangeListener { _, newValue ->
                    preferences.edit().putString(PREF_SUB_LANG_KEY, newValue as String).commit()
                }
            },
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Loklok"
        private const val PREF_SUB_LANG_KEY = "preferred_sub_lang"
        private const val PREF_SUB_LANG_DEFAULT = "English"
    }
}

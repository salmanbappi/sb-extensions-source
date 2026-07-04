package eu.kanade.tachiyomi.animeextension.all.nowhdtime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import extensions.utils.get
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Nowhdtime :
    Source(),
    ConfigurableAnimeSource {
    override val name = "Nowhdtime"
    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 673928172938476251L

    private val jsonParser by lazy {
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .build()

    override suspend fun getPopularAnime(page: Int): AnimesPage = getSearchAnime(page, "", AnimeFilterList())
    override suspend fun getLatestUpdates(page: Int): AnimesPage = getSearchAnime(page, "", AnimeFilterList())

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage = if (query.isNotBlank()) {
        val response = client.newCall(GET("$baseUrl/search?search=${URLEncoder.encode(query, "UTF-8")}")).execute()
        val doc = response.asJsoup()
        val items = parseAnimeList(doc)
        AnimesPage(items, false)
    } else {
        var category = "movies"
        filters.forEach { filter ->
            if (filter is CategoryFilter) {
                if (filter.state == 1) {
                    category = "tv-shows"
                }
            }
        }
        val url = if (page == 1) "$baseUrl/$category" else "$baseUrl/$category?page=$page"
        val response = client.newCall(GET(url)).execute()
        val doc = response.asJsoup()
        val items = parseAnimeList(doc)
        val hasNextPage = doc.selectFirst("a[href*=\"page=${page + 1}\"]") != null
        AnimesPage(items, hasNextPage)
    }

    private fun parseAnimeList(doc: Document): List<SAnime> {
        return doc.select("div.movie-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/watch-]") ?: return@mapNotNull null
            val title = card.selectFirst("h4")?.text() ?: ""
            val img = card.selectFirst("img")?.attr("abs:src") ?: ""
            SAnime.create().apply {
                url = link.attr("abs:href").substringAfter(baseUrl)
                this.title = title
                thumbnail_url = img
            }
        }
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}")).execute()
        val doc = response.asJsoup()
        return anime.apply {
            val infoSpans = doc.select("div.mb-4.flex.items-center.flex-wrap.gap-2.text-sm > span")
            val rawDesc = doc.selectFirst("p.text-gray-300")?.text()
            val scoreVal = infoSpans.firstOrNull { it.text().contains("IMDB", ignoreCase = true) }
                ?.text()?.substringAfter("IMDB:")?.trim()?.substringBefore("/")?.toDoubleOrNull()

            val scorePosition = preferences.getString(PREF_SCORE_POSITION_KEY, "top") ?: "top"
            description = buildDescription(rawDesc, scoreVal, scorePosition)

            genre = infoSpans.map { it.text().trim() }
                .filter { text ->
                    !text.contains("IMDB", ignoreCase = true) &&
                        !text.contains("Season", ignoreCase = true) &&
                        !text.contains("/") &&
                        !text.matches(Regex("""\d+m|\d+h.*"""))
                }
                .joinToString(", ")

            author = doc.selectFirst("span:contains(Director:) + span")?.text()
            status = if (anime.url.contains("/movie/")) SAnime.COMPLETED else SAnime.UNKNOWN
            initialized = true
        }
    }

    private fun formatScore(score: Double?): String? {
        if (score == null || score <= 0.0) return null
        val stars = buildString {
            val full = (score / 2).toInt().coerceIn(0, 5)
            repeat(full) { append("★") }
            repeat(5 - full) { append("☆") }
        }
        return "$stars ${"%.2f".format(score)}"
    }

    private fun buildDescription(raw: String?, score: Double?, position: String): String {
        val scoreStr = formatScore(score) ?: return raw.orEmpty()
        return when (position) {
            "top" -> "$scoreStr\n\n${raw.orEmpty()}"
            "bottom" -> "${raw.orEmpty()}\n\n$scoreStr"
            else -> raw.orEmpty()
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}")).execute()
        val doc = response.asJsoup()
        val isMovie = anime.url.contains("/movie/")
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)

        if (isMovie) {
            return listOf(
                SEpisode.create().apply {
                    name = "Play Movie"
                    url = anime.url
                    episode_number = 1F
                },
            )
        } else {
            val episodes = mutableListOf<SEpisode>()
            val seasonPanels = doc.select("div.season-panel")
            seasonPanels.forEach { panel ->
                val seasonId = panel.attr("id").substringAfter("season-").toIntOrNull() ?: 1
                val epItems = panel.select("div.episode-item")
                epItems.forEach { epItem ->
                    val epNum = epItem.attr("data-episode").toIntOrNull() ?: 1
                    val epName = epItem.selectFirst("h4")?.text() ?: "Episode $epNum"
                    val epOverview = epItem.selectFirst("p.text-gray-400")?.text()
                    val epThumbnail = if (showThumbnails) epItem.selectFirst("img")?.attr("abs:src") else null
                    val epDate = epItem.select("div.text-\\[11px\\].text-gray-500 span").firstOrNull()?.text()
                    val showId = epItem.attr("data-tv-show-id")

                    episodes.add(
                        SEpisode.create().apply {
                            name = "S$seasonId E$epNum - $epName"
                            url = "${anime.url}?showId=$showId&season=$seasonId&episode=$epNum"
                            episode_number = epNum.toFloat()
                            summary = epOverview
                            preview_url = epThumbnail
                            if (!epDate.isNullOrBlank()) {
                                date_upload = parseEpisodeDate(epDate)
                            }
                        },
                    )
                }
            }
            return episodes.reversed()
        }
    }

    private fun parseEpisodeDate(dateStr: String): Long = try {
        SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).parse(dateStr.trim())?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val isMovie = episode.url.contains("/movie/")
        val hosters = mutableListOf<Hoster>()

        if (isMovie) {
            val response = client.newCall(GET("$baseUrl${episode.url.substringBefore("?")}")).execute()
            val html = response.body.string()
            val playersJson = playersRegex.find(html)?.groupValues?.get(1) ?: return emptyList()
            val players = jsonParser.decodeFromString<List<PlayerDto>>(playersJson)
            players.forEach { player ->
                hosters.add(
                    Hoster(
                        hosterName = player.server,
                        hosterUrl = player.url,
                    ),
                )
            }
        } else {
            val animeUrl = episode.url.substringBefore("?")
            val params = episode.url.substringAfter("?")
            val showId = params.substringAfter("showId=").substringBefore("&").toIntOrNull() ?: 0
            val season = params.substringAfter("season=").substringBefore("&").toIntOrNull() ?: 1
            val epNum = params.substringAfter("episode=").substringBefore("&").toIntOrNull() ?: 1

            val response = client.newCall(GET("$baseUrl$animeUrl")).execute()
            val body = response.body.string()
            val csrfToken = csrfRegex.find(body)?.groupValues?.get(1) ?: return emptyList()
            val cookies = response.headers("Set-Cookie")
            val cookieHeader = cookies.joinToString("; ") { it.substringBefore(";") }

            val postBody = """{"tv_show_id":$showId,"season":$season,"episode":$epNum}""".toRequestBody("application/json".toMediaType())
            val postRequest = Request.Builder()
                .url("$baseUrl/episode-details")
                .post(postBody)
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .header("Cookie", cookieHeader)
                .header("Referer", "$baseUrl$animeUrl")
                .build()

            val postResponse = client.newCall(postRequest).execute()
            val responseData = jsonParser.decodeFromString<EpisodeDetailsResponse>(postResponse.body.string())
            responseData.players.forEach { player ->
                hosters.add(
                    Hoster(
                        hosterName = player.server,
                        hosterUrl = player.url,
                    ),
                )
            }
        }
        return hosters.distinctBy { it.hosterName }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl
        val serverName = hoster.hosterName

        return when {
            "nhdapi.com" in embedUrl || "vidnest.fun" in embedUrl -> {
                extractNhdStream(embedUrl, serverName)
            }

            "vidsrc" in embedUrl || "vidsrcme" in embedUrl -> {
                try {
                    val headers = Headers.Builder()
                        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                        .add("Referer", "https://nowhdtime.com.bd/")
                        .build()
                    eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor(client, headers)
                        .videosFromUrl(embedUrl, serverName)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private fun extractNhdStream(embedUrl: String, serverName: String): List<Video> {
        val isTv = embedUrl.contains("/tv/")
        val id = if (isTv) {
            tvEmbedRegex.find(embedUrl)?.groupValues?.get(1) ?: ""
        } else {
            movieEmbedRegex.find(embedUrl)?.groupValues?.get(1) ?: ""
        }

        val season = if (isTv) tvEmbedRegex.find(embedUrl)?.groupValues?.get(2)?.toIntOrNull() ?: 1 else 1
        val episode = if (isTv) tvEmbedRegex.find(embedUrl)?.groupValues?.get(3)?.toIntOrNull() ?: 1 else 1

        val ip = getClientIp()
        if (ip.isBlank()) return emptyList()

        // Request token
        val tokenBody = """{"ipv4":"$ip"}""".toRequestBody("application/json".toMediaType())
        val tokenRequest = Request.Builder()
            .url("https://player.nhdapi.com/api/token")
            .post(tokenBody)
            .header("Content-Type", "application/json")
            .header("X-Content-Id", id)
            .header("Referer", "https://player.nhdapi.com/")
            .header("Origin", "https://player.nhdapi.com")
            .build()

        val tokenResponse = client.newCall(tokenRequest).execute()
        if (!tokenResponse.isSuccessful) return emptyList()

        val tokenBodyStr = tokenResponse.body.string()
        val token = tokenRegex.find(tokenBodyStr)?.groupValues?.get(1) ?: return emptyList()
        val secureId = secureIdRegex.find(tokenBodyStr)?.groupValues?.get(1) ?: return emptyList()

        // Request movie/tv source
        val sourceUrl = if (isTv) {
            "https://player.nhdapi.com/api/tv?id=$secureId&season=$season&episode=$episode"
        } else {
            "https://player.nhdapi.com/api/movie?id=$secureId"
        }

        val sourceRequest = Request.Builder()
            .url(sourceUrl)
            .header("X-API-Token", token)
            .header("X-Client-IPv4", ip)
            .header("Referer", "https://player.nhdapi.com/")
            .header("Origin", "https://player.nhdapi.com")
            .build()

        val sourceResponse = client.newCall(sourceRequest).execute()
        if (!sourceResponse.isSuccessful) return emptyList()

        val encryptedData = jsonParser.decodeFromString<EncryptedResponse>(sourceResponse.body.string())
        val decryptedStr = decryptGcm(encryptedData.iv, encryptedData.tag, encryptedData.data, DECRYPTION_KEY)
        val decryptedData = jsonParser.decodeFromString<DecryptedResponse>(decryptedStr)

        val videoHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
            .add("Referer", "https://player.nhdapi.com/")
            .build()

        val videos = mutableListOf<Video>()
        decryptedData.stream?.hls_streaming?.let { hlsUrl ->
            if (hlsUrl.isNotBlank()) {
                videos.add(
                    Video(
                        videoUrl = hlsUrl,
                        videoTitle = "$serverName - HLS",
                        headers = videoHeaders,
                        resolution = 1080,
                    ),
                )
            }
        }

        decryptedData.stream?.download?.forEach { dl ->
            videos.add(
                Video(
                    videoUrl = dl.url,
                    videoTitle = "$serverName - ${dl.quality}",
                    headers = videoHeaders,
                    resolution = dl.quality.replace("p", "").toIntOrNull(),
                ),
            )
        }

        return videos
    }

    private fun getClientIp(): String {
        val ip = runCatching {
            val response = client.newCall(GET("https://api.ipify.org/?format=json")).execute()
            val body = response.body.string()
            ipRegex.find(body)?.groupValues?.get(1)
        }.getOrNull()
        if (!ip.isNullOrBlank() && !ip.contains(":")) return ip

        val fallbackIp = runCatching {
            val response = client.newCall(GET("https://1.1.1.1/cdn-cgi/trace")).execute()
            val body = response.body.string()
            ipTraceRegex.find(body)?.groupValues?.get(1)
        }.getOrNull()
        if (!fallbackIp.isNullOrBlank() && !fallbackIp.contains(":")) return fallbackIp

        return ""
    }

    private fun decryptGcm(ivB64: String, tagB64: String, dataB64: String, keyStr: String): String {
        val keyBytes = java.security.MessageDigest.getInstance("SHA-256").digest(keyStr.toByteArray())
        val keySpec = SecretKeySpec(keyBytes, "AES")

        val ivBytes = android.util.Base64.decode(ivB64, android.util.Base64.DEFAULT)
        val tagBytes = android.util.Base64.decode(tagB64, android.util.Base64.DEFAULT)
        val dataBytes = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)

        val combined = dataBytes + tagBytes
        val spec = GCMParameterSpec(128, ivBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        val decryptedBytes = cipher.doFinal(combined)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, "1080") ?: "1080"
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(preferredQuality) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_DOMAIN_DEFAULT,
            title = "Base URL",
            key = PREF_DOMAIN_KEY,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = "1080p",
            title = "Preferred Quality",
            summary = "",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
        )

        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            default = "top",
            title = "Score Display Position",
            summary = "Where to show the rating (e.g. ★★★★☆ 8.29) in the description",
            entries = listOf("Top of description", "Bottom of description", "Disabled"),
            entryValues = listOf("top", "bottom", "disabled"),
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            default = true,
            title = "Show episode thumbnails",
            summary = "Fetch and display thumbnail images in the episode list.",
        )
    }

    private class CategoryFilter :
        AnimeFilter.Select<String>(
            "Category",
            arrayOf("Movies", "TV Shows"),
        )

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Use category to browse Movies or TV Shows"),
        CategoryFilter(),
    )

    @Serializable
    data class EpisodeDetailsResponse(
        val success: Boolean,
        val players: List<PlayerDto> = emptyList(),
    )

    @Serializable
    data class PlayerDto(
        val type: String,
        val name: String,
        val url: String,
        val server: String,
    )

    @Serializable
    data class EncryptedResponse(
        val iv: String,
        val tag: String,
        val data: String,
    )

    @Serializable
    data class DecryptedResponse(
        val status: String,
        val stream: StreamDto? = null,
    )

    @Serializable
    data class StreamDto(
        val hls_streaming: String? = null,
        val download: List<DownloadDto> = emptyList(),
    )

    @Serializable
    data class DownloadDto(
        val quality: String,
        val url: String,
    )

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://nowhdtime.com.bd"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SCORE_POSITION_KEY = "pref_score_position"
        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private const val DECRYPTION_KEY = "Z9#rL!v2K*5qP&7mXw"

        private val playersRegex = Regex("""const\s+players\s*=\s*([\s\S]*?);""")
        private val csrfRegex = Regex("""meta\s+name="csrf-token"\s+content="([^"]+)"""")

        private val movieEmbedRegex = Regex("""/movie/(\d+)""")
        private val tvEmbedRegex = Regex("""/tv/(\d+)/(\d+)/(\d+)""")

        private val ipRegex = Regex("""(?:"ip"\s*:\s*")([^"]+)""")
        private val ipTraceRegex = Regex("""(?m)^ip=(.+)$""")
        private val tokenRegex = Regex("""(?:"token"\s*:\s*")([^"]+)""")
        private val secureIdRegex = Regex("""(?:"secureId"\s*:\s*")([^"]+)""")
    }
}

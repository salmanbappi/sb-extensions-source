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
                val pUrl = player.url ?: return@forEach
                hosters.add(
                    Hoster(
                        hosterName = player.server ?: "Server",
                        hosterUrl = pUrl,
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
                val pUrl = player.url ?: return@forEach
                hosters.add(
                    Hoster(
                        hosterName = player.server ?: "Server",
                        hosterUrl = pUrl,
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
                extractVidnest(embedUrl, serverName)
            }

            "neodrive" in embedUrl -> {
                extractNeodrive(embedUrl, serverName)
            }

            "multiembed.mov" in embedUrl || "streamingnow.mov" in embedUrl -> {
                extractStreamingnow(embedUrl)
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

    private fun extractStreamingnow(embedUrl: String): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val response = client.newCall(GET(embedUrl)).execute()
            val finalUrl = response.request.url.toString()
            val html = response.body.string()

            if (html.contains("cf-turnstile-widget") || html.contains("turnstile")) {
                return emptyList()
            }

            val token = streamingnowTokenRegex.find(html)?.groupValues?.get(1) ?: return emptyList()

            val postBody = "token=$token".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val postRequest = Request.Builder()
                .url("https://streamingnow.mov/response.php")
                .post(postBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", finalUrl)
                .build()

            val postResponse = client.newCall(postRequest).execute()
            val responseHtml = postResponse.body.string()
            val doc = org.jsoup.Jsoup.parse(responseHtml)

            val servers = doc.select("li[data-id][data-server]")
            servers.forEach { serverLi ->
                val serverId = serverLi.attr("data-server")
                val videoId = serverLi.attr("data-id")
                val serverName = serverLi.text().trim()

                val playvideoUrl = "https://streamingnow.mov/playvideo.php?video_id=$videoId&server_id=$serverId&token=$token"
                val playvideoResponse = client.newCall(GET(playvideoUrl)).execute()
                val playvideoHtml = playvideoResponse.body.string()
                val playvideoDoc = org.jsoup.Jsoup.parse(playvideoHtml)

                val iframe = playvideoDoc.selectFirst("iframe")
                val iframeSrc = iframe?.attr("abs:src") ?: iframe?.attr("src") ?: ""
                when {
                    "dood" in iframeSrc -> {
                        try {
                            val doodVideos = eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor(client)
                                .videosFromUrl(iframeSrc)
                            videos.addAll(doodVideos)
                        } catch (e: Exception) {}
                    }

                    iframeSrc.isNotBlank() -> {
                        try {
                            val iframeResponse = client.newCall(GET(iframeSrc)).execute()
                            val iframeHtml = iframeResponse.body.string()
                            val m3u8Match = m3u8Regex.find(iframeHtml)?.groupValues?.get(1)
                            if (m3u8Match != null) {
                                val videoUrl = if (m3u8Match.startsWith("http")) m3u8Match else "https:$m3u8Match"
                                videos.add(
                                    Video(
                                        videoUrl = videoUrl,
                                        videoTitle = "$serverName - Player",
                                        headers = Headers.Builder().add("Referer", iframeSrc).build(),
                                        resolution = 1080,
                                    ),
                                )
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {}
        return videos
    }

    private fun extractNeodrive(embedUrl: String, serverName: String): List<Video> {
        return try {
            val response = client.newCall(GET(embedUrl)).execute()
            val doc = response.asJsoup()
            val iframe = doc.selectFirst("iframe") ?: return emptyList()
            val iframeSrc = iframe.attr("src")
            val idEncoded = iframeSrc.substringAfter("id=").substringBefore("&")
            val decoded1 = java.net.URLDecoder.decode(idEncoded, "UTF-8")
            val directUrl = java.net.URLDecoder.decode(decoded1, "UTF-8")

            val videoHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                .build()

            listOf(
                Video(
                    videoUrl = directUrl,
                    videoTitle = "$serverName - Direct R2",
                    headers = videoHeaders,
                    resolution = 1080,
                ),
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractVidnest(embedUrl: String, serverName: String): List<Video> {
        val isTv = embedUrl.contains("/tv/")
        val tmdbId = if (isTv) {
            tvEmbedRegex.find(embedUrl)?.groupValues?.get(1) ?: ""
        } else {
            movieEmbedRegex.find(embedUrl)?.groupValues?.get(1) ?: ""
        }

        if (tmdbId.isBlank()) return emptyList()

        val season = if (isTv) tvEmbedRegex.find(embedUrl)?.groupValues?.get(2) ?: "1" else "1"
        val episode = if (isTv) tvEmbedRegex.find(embedUrl)?.groupValues?.get(3) ?: "1" else "1"

        val base = "https://new.vidnest.fun"
        val endpoints = listOf(
            Pair("movies5f", if (isTv) "$base/movies5f/tv/$tmdbId/$season/$episode" else "$base/movies5f/movie/$tmdbId"),
            Pair("klikxxi", if (isTv) "$base/klikxxi/tv/$tmdbId/$season/$episode" else "$base/klikxxi/movie/$tmdbId"),
            Pair("vidlink", if (isTv) "$base/vidlink/tv/$tmdbId/$season/$episode" else "$base/vidlink/movie/$tmdbId"),
        )

        val videos = mutableListOf<Video>()

        endpoints.forEach { (type, url) ->
            runCatching {
                val response = client.newCall(
                    GET(
                        url,
                        Headers.Builder()
                            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                            .add("Referer", "https://vidnest.fun/")
                            .build(),
                    ),
                ).execute()

                if (response.isSuccessful) {
                    val body = response.body.string()
                    val responseJson = jsonParser.decodeFromString<EncryptedResponse>(body)
                    val rawEncrypted = responseJson.data ?: return@runCatching
                    val decrypted = decodeCustomBase64(rawEncrypted, "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/=")

                    when (type) {
                        "movies5f" -> {
                            val data = jsonParser.decodeFromString<CatflixResponse>(decrypted)
                            data.data?.downloads?.forEach { dl ->
                                val streamUrl = dl.url ?: return@forEach
                                val headers = Headers.Builder()
                                    .add("Origin", "https://fmoviesunblocked.net")
                                    .add("Referer", "https://fmoviesunblocked.net/")
                                    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                    .build()
                                videos.add(
                                    Video(
                                        videoUrl = streamUrl,
                                        videoTitle = "$serverName - Movies5f - ${dl.resolution}p",
                                        headers = headers,
                                    ),
                                )
                            }
                        }

                        "klikxxi" -> {
                            val data = jsonParser.decodeFromString<OphimResponse>(decrypted)
                            data.sources.forEach { src ->
                                val streamUrl = src.url ?: return@forEach
                                val headers = Headers.Builder()
                                    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                                    .add("Referer", "https://vidnest.fun/")
                                    .build()
                                videos.add(
                                    Video(
                                        videoUrl = streamUrl,
                                        videoTitle = "$serverName - Klikxxi - ${src.quality}",
                                        headers = headers,
                                    ),
                                )
                            }
                        }

                        "vidlink" -> {
                            val data = jsonParser.decodeFromString<HexaResponse>(decrypted)
                            data.data?.stream?.qualities?.forEach { (res, item) ->
                                val streamUrl = item.url ?: return@forEach
                                val headers = Headers.Builder()
                                    .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36")
                                    .add("Referer", "https://vidnest.fun/")
                                    .build()
                                videos.add(
                                    Video(
                                        videoUrl = streamUrl,
                                        videoTitle = "$serverName - Vidlink - ${res}p",
                                        headers = headers,
                                    ),
                                )
                            }
                        }
                    }
                }
            }.onFailure { it.printStackTrace() }
        }

        return videos
    }

    private fun decodeCustomBase64(data: String, alphabet: String): String {
        val s = IntArray(256) { -1 }
        for (i in alphabet.indices) {
            s[alphabet[i].code] = i
        }
        val out = mutableListOf<Byte>()
        var t = 0
        while (t < data.length) {
            val chunkStr = data.substring(t, minOf(t + 4, data.length)).let {
                if (it.length < 4) it + "=".repeat(4 - it.length) else it
            }
            t += 4
            val l = IntArray(4) { 64 }
            for (e in chunkStr.indices) {
                val charCode = chunkStr[e].code
                val valIdx = if (charCode < 256) s[charCode] else -1
                l[e] = if (valIdx != -1) valIdx else 64
            }
            out.add(((l[0] shl 2) or (l[1] ushr 4)).toByte())
            if (l[2] != 64) {
                out.add((((l[1] and 15) shl 4) or (l[2] ushr 2)).toByte())
            }
            if (l[3] != 64) {
                out.add((((l[2] and 3) shl 6) or l[3]).toByte())
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
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
        val type: String? = null,
        val name: String? = null,
        val url: String? = null,
        val server: String? = null,
    )

    @Serializable
    data class EncryptedResponse(
        val iv: String = "",
        val tag: String = "",
        val data: String? = null,
    )

    @Serializable
    data class DecryptedResponse(
        val status: String? = null,
        val stream: StreamDto? = null,
    )

    @Serializable
    data class StreamDto(
        val hls_streaming: String? = null,
        val download: List<DownloadDto> = emptyList(),
    )

    @Serializable
    data class DownloadDto(
        val quality: String? = null,
        val url: String? = null,
    )

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://nowhdtime.com.bd"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SCORE_POSITION_KEY = "pref_score_position"
        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private val playersRegex = Regex("""const\s+players\s*=\s*([\s\S]*?);""")
        private val csrfRegex = Regex("""meta\s+name="csrf-token"\s+content="([^"]+)"""")

        private val movieEmbedRegex = Regex("""/movie/(\d+)""")
        private val tvEmbedRegex = Regex("""/tv/(\d+)/(\d+)/(\d+)""")

        private val streamingnowTokenRegex = Regex("""load_sources\("([^"]+)"\)""")
        private val m3u8Regex = Regex("""["'](https?:[^"']+\.m3u8[^"']*)["']""")
    }
}

@Serializable
data class CatflixResponse(
    val data: CatflixData? = null,
) {
    @Serializable
    data class CatflixData(
        val downloads: List<DownloadItem> = emptyList(),
    )

    @Serializable
    data class DownloadItem(
        val url: String? = null,
        val resolution: Int = 1080,
    )
}

@Serializable
data class OphimResponse(
    val sources: List<SourceItem> = emptyList(),
) {
    @Serializable
    data class SourceItem(
        val url: String? = null,
        val quality: String = "auto",
        val type: String = "hls",
    )
}

@Serializable
data class HexaResponse(
    val data: HexaData? = null,
) {
    @Serializable
    data class HexaData(
        val stream: HexaStream? = null,
    )

    @Serializable
    data class HexaStream(
        val qualities: Map<String, QualityItem> = emptyMap(),
    )

    @Serializable
    data class QualityItem(
        val url: String? = null,
    )
}

package eu.kanade.tachiyomi.animeextension.en.animestream

import android.net.Uri
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeStream : Source() {

    override val name = "AnimeStream"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api/v1/videos/popular?per_page=24&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseData = response.body.string()
        val popularList = json.decodeFromString<List<PopularItemDto>>(responseData)
        val animeList = popularList.map { item ->
            SAnime.create().apply {
                title = item.title
                url = if (item.type == "movie") "/content/${item.content_id}" else "/series/${item.content_id}"
                thumbnail_url = item.image
            }
        }
        return AnimesPage(animeList, animeList.size >= 24)
    }

    // ============================== Latest ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/v1/videos/new?page=$page&limit=24", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val year = filters.filterIsInstance<YearFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.getSelectedValue() ?: ""
        val audio = filters.filterIsInstance<AudioFilter>().firstOrNull()?.getSelectedValue() ?: ""

        val urlBuilder = "$baseUrl/api/v1/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) {
                addQueryParameter("query", query)
                addQueryParameter("suggest", "1")
            }
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "24")

            if (type.isNotBlank()) addQueryParameter("t", type)
            if (genre.isNotBlank()) addQueryParameter("genre", genre)
            if (year.isNotBlank()) addQueryParameter("year", year)
            if (status.isNotBlank()) addQueryParameter("status", status)
            if (audio.isNotBlank()) addQueryParameter("audio", audio)
        }
        return GET(urlBuilder.build(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseData = response.body.string()
        val searchResult = json.decodeFromString<SearchResponseDto>(responseData)
        val animeList = mutableListOf<SAnime>()

        searchResult.series?.forEach { item ->
            animeList.add(
                SAnime.create().apply {
                    title = item.title
                    url = "/series/${item.content_id}"
                    thumbnail_url = item.image
                },
            )
        }

        searchResult.movies?.forEach { item ->
            animeList.add(
                SAnime.create().apply {
                    title = item.title
                    url = "/content/${item.content_id}"
                    thumbnail_url = item.image
                },
            )
        }

        return AnimesPage(animeList, animeList.size >= 24)
    }

    // ============================== Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/api/v1${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val responseData = response.body.string()
        val details = json.decodeFromString<DetailsResponseDto>(responseData)
        val scoreVal = details.score?.let { it / 10.0 }
        val scorePosition = preferences.getString(PREF_SCORE_POSITION_KEY, "top") ?: "top"

        return SAnime.create().apply {
            title = details.title ?: ""
            description = buildDescription(details.description, scoreVal, scorePosition)
            thumbnail_url = details.image ?: details.images?.find { it.type == "poster_tall" }?.url
            status = SAnime.UNKNOWN
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

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val isMovie = anime.url.startsWith("/content/")
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)

        if (isMovie) {
            val episode = SEpisode.create().apply {
                url = anime.url
                name = "Movie"
                episode_number = 1.0f
                date_upload = 0L
            }
            return listOf(episode)
        } else {
            val seriesId = anime.url.substringAfterLast("/")
            val seriesResponse = client.newCall(GET("$baseUrl/api/v1/series/$seriesId", headers)).awaitSuccess()
            val details = json.decodeFromString<DetailsResponseDto>(seriesResponse.body.string())
            val episodes = mutableListOf<SEpisode>()

            details.seasons?.forEach { season ->
                val seasonResponse = client.newCall(GET("$baseUrl/api/v1/season/${season.content_id}/episodes?order_by=desc", headers)).awaitSuccess()
                val seasonEpisodes = json.decodeFromString<List<EpisodeItemDto>>(seasonResponse.body.string())
                seasonEpisodes.forEach { ep ->
                    val epNumStr = ep.episode_number?.let {
                        if (it % 1f == 0f) it.toInt().toString() else it.toString()
                    } ?: "1"
                    val epTitle = ep.title
                    val nameFormatted = if (!epTitle.isNullOrBlank() && !epTitle.equals("Episode $epNumStr", ignoreCase = true)) {
                        "S${season.season_number} Ep. $epNumStr - $epTitle"
                    } else {
                        "Season ${season.season_number} Episode $epNumStr"
                    }
                    episodes.add(
                        SEpisode.create().apply {
                            url = "/episode/${ep.content_id}"
                            name = nameFormatted
                            episode_number = ep.episode_number ?: 1.0f
                            date_upload = 0L
                            summary = ep.description
                            preview_url = if (showThumbnails) ep.image else null
                            scanlator = getScanlatorLabel(ep.audio_locales)
                        },
                    )
                }
            }
            return episodes.sortedByDescending { it.episode_number }
        }
    }

    private fun getScanlatorLabel(locales: List<String>?): String? {
        if (locales.isNullOrEmpty()) return null
        val hasSub = locales.contains("ja-JP")
        val hasDub = locales.any { it != "ja-JP" }
        return when {
            hasSub && hasDub -> "Sub, Dub"
            hasDub -> "Dub"
            hasSub -> "Sub"
            else -> null
        }
    }

    // ============================== Hosters (Lazy Stream Resolution) ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val isMovie = episode.url.startsWith("/content/")
        val contentId = episode.url.substringAfterLast("/")
        val typePath = if (isMovie) "movie" else "episode"

        val response = client.newCall(GET("$baseUrl/api/v1/$typePath/$contentId/media/hls/ja-JP", headers)).awaitSuccess()
        val mediaData = json.decodeFromString<MediaResponseDto>(response.body.string())

        val hosters = mutableListOf<Hoster>()

        // 1. Process Main HLS Stream
        mediaData.hls?.let { hls ->
            val locale = hls.locale ?: "ja-JP"
            val playlist = hls.playlist ?: ""
            val data = HosterData(locale, playlist, hls.hard_subs ?: emptyList())
            hosters.add(
                Hoster(
                    hosterName = getLocaleName(locale),
                    hosterUrl = json.encodeToString(HosterData.serializer(), data),
                ),
            )
        }

        // 2. Process alternative dubbed streams
        mediaData.versions?.hls?.forEach { version ->
            val locale = version.locale ?: ""
            val playlist = version.playlist ?: ""
            val data = HosterData(locale, playlist, version.hard_subs ?: emptyList())
            hosters.add(
                Hoster(
                    hosterName = getLocaleName(locale),
                    hosterUrl = json.encodeToString(HosterData.serializer(), data),
                ),
            )
        }

        return hosters.distinctBy { it.hosterName }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterData = json.decodeFromString<HosterData>(hoster.hosterUrl)
        val videoList = mutableListOf<Video>()

        if (proxy == null) {
            proxy = LocalProxyServer(client)
        }
        proxy!!.start()

        // Extract videos from main playlist
        if (hosterData.playlist.isNotBlank()) {
            val langName = getLocaleName(hosterData.locale)
            val playlistParts = hosterData.playlist.split("/")
            val mediaFolder = playlistParts.getOrNull(7) ?: ""
            val mediaId = mediaFolder.substringBefore("_")
            val proxiedPlaylistUrl = getProxyUrl(hosterData.playlist, mediaId)

            val extracted = playlistUtils.extractFromHls(
                playlistUrl = proxiedPlaylistUrl,
                masterHeaders = headers,
                videoHeaders = headers,
                videoNameGen = { quality -> "$langName - $quality" },
            )
            videoList.addAll(extracted)
        }

        // Extract videos from hardsubs playlists
        hosterData.hardSubs.forEach { sub ->
            sub.playlist?.let { playlistUrl ->
                val langName = getLocaleName(hosterData.locale)
                val subLang = getLocaleName(sub.locale ?: "en-US")
                val playlistParts = playlistUrl.split("/")
                val mediaFolder = playlistParts.getOrNull(7) ?: ""
                val mediaId = mediaFolder.substringBefore("_")
                val proxiedPlaylistUrl = getProxyUrl(playlistUrl, mediaId)

                val extracted = playlistUtils.extractFromHls(
                    playlistUrl = proxiedPlaylistUrl,
                    masterHeaders = headers,
                    videoHeaders = headers,
                    videoNameGen = { quality -> "$langName [Hardsub: $subLang] - $quality" },
                )
                videoList.addAll(extracted)
            }
        }

        return videoList
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, "720p")!!
        val audioType = preferences.getString(PREF_TYPE_KEY, "sub")!!
        return this.sortedWith(
            compareByDescending<Video> { video ->
                val title = video.videoTitle
                if (audioType == "sub") {
                    title.contains("Japanese") || title.contains("Hardsub")
                } else {
                    title.contains("English") && !title.contains("Hardsub")
                }
            }
                .thenByDescending { it.videoTitle.contains(quality) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================== Filter list ==============================

    override fun getFilterList() = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        AudioFilter(),
    )

    private fun getLocaleName(locale: String): String = when (locale.lowercase()) {
        "ja-jp" -> "Japanese (RAW)"
        "en-us" -> "English"
        "de-de" -> "German"
        "fr-fr" -> "French"
        "it-it" -> "Italian"
        "es-es" -> "Spanish (Spain)"
        "es-419" -> "Spanish (LATAM)"
        "pt-br" -> "Portuguese (Brazil)"
        "ru-ru" -> "Russian"
        "ar-sa" -> "Arabic"
        "hi-in" -> "Hindi"
        "te-in" -> "Telugu"
        "ta-in" -> "Tamil"
        "th-th" -> "Thai"
        "vi-vn" -> "Vietnamese"
        "id-id" -> "Indonesian"
        "ms-my" -> "Malay"
        "zh-cn" -> "Chinese (Simplified)"
        "zh-hk" -> "Chinese (Traditional)"
        else -> locale.uppercase()
    }

    // ============================== Settings ==============================

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
            summary = "%s",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080p", "720p", "480p"),
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            default = "sub",
            title = "Preferred Audio Type",
            summary = "%s",
            entries = listOf("Subbed", "Dubbed"),
            entryValues = listOf("sub", "dub"),
        )
        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            default = "top",
            title = "Score Display Position",
            summary = "%s",
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

    // ============================== Filters ==============================

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Type",
            TYPES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = TYPES[state].second
    }

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Genre",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = GENRES[state].second
    }

    private class YearFilter :
        AnimeFilter.Select<String>(
            "Year",
            YEARS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = YEARS[state].second
    }

    private class StatusFilter :
        AnimeFilter.Select<String>(
            "Status",
            STATUS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = STATUS[state].second
    }

    private class AudioFilter :
        AnimeFilter.Select<String>(
            "Audio",
            AUDIO.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = AUDIO[state].second
    }

    // ============================== DTOs & Helper Classes ==============================

    @Serializable
    private data class HosterData(
        val locale: String,
        val playlist: String,
        val hardSubs: List<SubtitleDto> = emptyList(),
    )

    @Serializable
    private data class PopularItemDto(
        val content_id: String,
        val title: String,
        val image: String? = null,
        val type: String? = null,
    )

    @Serializable
    private data class SearchResponseDto(
        val series: List<PopularItemDto>? = null,
        val movies: List<PopularItemDto>? = null,
    )

    @Serializable
    private data class ImageDto(
        val url: String,
        val type: String,
    )

    @Serializable
    private data class SeasonDto(
        val content_id: String,
        val title: String,
        val season_number: Int,
        val episode_count: Int? = null,
    )

    @Serializable
    private data class DetailsResponseDto(
        val content_id: String? = null,
        val title: String? = null,
        val description: String? = null,
        val images: List<ImageDto>? = null,
        val image: String? = null,
        val score: Double? = null,
        val seasons: List<SeasonDto>? = null,
    )

    @Serializable
    private data class EpisodeItemDto(
        val content_id: String,
        val title: String? = null,
        val episode_number: Float? = null,
        val image: String? = null,
        val description: String? = null,
        val audio_locales: List<String>? = null,
    )

    @Serializable
    private data class SubtitleDto(
        val locale: String? = null,
        val playlist: String? = null,
    )

    @Serializable
    private data class HlsDto(
        val locale: String? = null,
        val playlist: String? = null,
        val hard_subs: List<SubtitleDto>? = null,
    )

    @Serializable
    private data class VersionsDto(
        val hls: List<HlsDto>? = null,
    )

    @Serializable
    private data class MediaResponseDto(
        val hls: HlsDto? = null,
        val versions: VersionsDto? = null,
    )

    private fun getProxyUrl(targetUrl: String, mediaId: String): String {
        val port = proxy?.port ?: 0
        if (port <= 0) return targetUrl
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "key.bin"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&media_id=$mediaId"
    }

    companion object {
        private var proxy: LocalProxyServer? = null

        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://anime.uniquestream.net"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_SCORE_POSITION_KEY = "pref_score_position"
        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private val GENRES = listOf(
            Pair("Any", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("Music", "music"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shonen", "shonen"),
            Pair("Slice of Life", "slice of life"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
        )

        private val TYPES = listOf(
            Pair("Any", ""),
            Pair("Series", "series"),
            Pair("Movies", "movies"),
        )

        private val YEARS = listOf(Pair("Any", "")) + (2026 downTo 1966).map { Pair(it.toString(), it.toString()) }

        private val STATUS = listOf(
            Pair("Any", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Completed", "completed"),
        )

        private val AUDIO = listOf(
            Pair("Any", ""),
            Pair("Subbed", "sub"),
            Pair("Dubbed", "dub"),
        )
    }
}

private class LocalProxyServer(private val client: okhttp3.OkHttpClient) {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    val port: Int
        get() = serverSocket?.let {
            if (it.isClosed) 0 else it.localPort
        } ?: 0

    fun start() {
        if (running.get() && serverSocket?.isClosed == false) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            executor.execute {
                while (running.get() && serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(socket) }
                    } catch (e: Exception) {
                        if (serverSocket?.isClosed == true || !running.get()) {
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            running.set(false)
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val firstLine = input.bufferedReader().readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val path = parts[1]
                routeRequest(path, output)
            }
        }
    }

    private fun routeRequest(path: String, output: OutputStream) {
        val uri = Uri.parse("http://127.0.0.1$path")
        val encodedUrl = uri.getQueryParameter("url") ?: return
        val mediaId = uri.getQueryParameter("media_id") ?: ""
        val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

        try {
            if (path.contains("playlist.m3u8")) {
                servePlaylist(targetUrl, mediaId, output)
            } else if (path.contains("key.bin")) {
                serveKey(targetUrl, mediaId, output)
            }
        } catch (e: Exception) {
            try {
                output.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Exception) {}
        }
    }

    private fun servePlaylist(targetUrl: String, mediaId: String, output: OutputStream) {
        val reqHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://anime.uniquestream.net/")
            .build()

        val response = client.newCall(GET(targetUrl, reqHeaders)).execute()
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }

        val content = response.body.string()
        response.close()
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                uriRegex.find(trimmed)?.let { match ->
                    val uriValue = match.groupValues[1]
                    val resolvedUri = targetUrl.toHttpUrl().resolve(uriValue)?.toString() ?: uriValue
                    val proxiedUri = if (resolvedUri.contains(".m3u8") || resolvedUri.contains("key") || resolvedUri.contains("playlist")) {
                        getProxyUrl(resolvedUri, mediaId)
                    } else {
                        resolvedUri
                    }
                    builder.append(trimmed.replace(uriValue, proxiedUri))
                } ?: builder.append(trimmed)
            } else {
                val resolvedUri = targetUrl.toHttpUrl().resolve(trimmed)?.toString() ?: trimmed
                if (resolvedUri.contains(".m3u8")) {
                    builder.append(getProxyUrl(resolvedUri, mediaId))
                } else {
                    builder.append(resolvedUri)
                }
            }
            builder.append("\n")
        }

        val bodyBytes = builder.toString().toByteArray()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
        output.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bodyBytes)
        output.flush()
    }

    private fun serveKey(targetUrl: String, mediaId: String, output: OutputStream) {
        val reqHeaders = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://anime.uniquestream.net/")
            .add("x-am-media-id", mediaId)
            .build()

        val response = client.newCall(GET(targetUrl, reqHeaders)).execute()
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }

        val keyText = response.body.string().trim()
        response.close()
        val decryptedKey = decryptKey(keyText, mediaId)

        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${decryptedKey.size}\r\n".toByteArray())
        output.write("Content-Type: application/octet-stream\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(decryptedKey)
        output.flush()
    }

    private fun getProxyUrl(targetUrl: String, mediaId: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "key.bin"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&media_id=$mediaId"
    }

    private fun decryptKey(encryptedBase64: String, mediaId: String): ByteArray {
        val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
        val md = MessageDigest.getInstance("SHA-256")
        val keySalt = md.digest(("key" + mediaId).toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        md.reset()
        val ivSalt = md.digest(("iv" + mediaId).toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        val secretKey = SecretKeySpec(keySalt, "AES")
        val ivSpec = IvParameterSpec(ivSalt)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedFull = cipher.doFinal(encryptedBytes)
        return decryptedFull.copyOfRange(0, 16)
    }
}

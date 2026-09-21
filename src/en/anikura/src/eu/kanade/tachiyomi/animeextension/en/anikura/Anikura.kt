package eu.kanade.tachiyomi.animeextension.en.anikura

import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.UrlUtils
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Anikura : Source() {

    override val name = "Anikura"

    private val isTrialExpired: Boolean
        get() = System.currentTimeMillis() >= TRIAL_EXPIRATION_TIMESTAMP

    override val baseUrl: String
        get() = if (isTrialExpired) EXPIRED_BASE_URL else MAIN_BASE_URL

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$MAIN_BASE_URL/")
        .add("Accept-Language", "en-US,en;q=0.9")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val m3u8Integration by lazy { M3u8Integration(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (isTrialExpired) return getTrialExpiredPage()

        val response = client.newCall(GET("$baseUrl/browse?sort=popular&page=$page", headers)).execute()
        return parseAnimeListPage(response.asJsoup(), page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (isTrialExpired) return getTrialExpiredPage()

        val response = client.newCall(GET("$baseUrl/browse?sort=trending&page=$page", headers)).execute()
        return parseAnimeListPage(response.asJsoup(), page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (isTrialExpired) return getTrialExpiredPage()

        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val response = client.newCall(GET("$baseUrl/search?q=$encodedQuery", headers)).execute()
            return parseAnimeListPage(response.asJsoup(), page)
        }

        val urlBuilder = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
        }

        filters.forEach { filter ->
            when (filter) {
                is Filters.SortFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())

                is Filters.StatusFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())

                is Filters.FormatFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("format", filter.toUriPart())

                is Filters.AudioFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("audio", filter.toUriPart())

                is Filters.YearFilter -> if (filter.state.isNotBlank()) urlBuilder.addQueryParameter("year", filter.state.trim())

                is Filters.GenreFilter -> {
                    val included = filter.getIncluded()
                    if (included.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genre", included.joinToString(","))
                    }
                }

                else -> {}
            }
        }

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseAnimeListPage(response.asJsoup(), page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.SortFilter(),
        Filters.StatusFilter(),
        Filters.FormatFilter(),
        Filters.AudioFilter(),
        Filters.YearFilter(),
        Filters.GenreFilter(Filters.GENRES),
    )

    private fun parseAnimeListPage(doc: Document, page: Int): AnimesPage {
        val seen = mutableSetOf<String>()
        val animeList = mutableListOf<SAnime>()

        doc.select("a[href*='/anime/']").forEach { element ->
            val href = element.attr("href")
            if (!href.contains(Regex("""/anime/\d+/"""))) return@forEach
            val cleanHref = href.substringBefore("?")
            if (!seen.add(cleanHref)) return@forEach

            val title = element.selectFirst("h1, h2, h3, h4, .poster-title, .film-name")?.text()?.trim()
                ?: element.selectFirst("img")?.attr("alt")?.trim()
                ?: ""
            if (title.isBlank()) return@forEach

            val rawImg = element.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }
            val thumbnail = extractImageUrl(rawImg)

            animeList.add(
                SAnime.create().apply {
                    this.title = title
                    this.thumbnail_url = thumbnail
                    this.setUrlWithoutDomain(cleanHref)
                    this.fetch_type = FetchType.Episodes
                },
            )
        }

        val hasNextPage = doc.selectFirst("a[href*='page=${page + 1}']") != null || animeList.size >= 30
        return AnimesPage(animeList, hasNextPage)
    }

    private fun getTrialExpiredPage(): AnimesPage {
        val expiredAnime = SAnime.create().apply {
            title = "Trial Period Expired (5 Days Over)"
            thumbnail_url = EXPIRED_BASE_URL
            url = "#trial-expired"
            fetch_type = FetchType.Episodes
        }
        return AnimesPage(listOf(expiredAnime), false)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        if (isTrialExpired || anime.url == "#trial-expired") {
            return anime.apply {
                title = "Trial Period Expired"
                thumbnail_url = EXPIRED_BASE_URL
                description = "The 5-day trial period has ended. The base URL is now set to $EXPIRED_BASE_URL"
                status = SAnime.COMPLETED
                initialized = true
            }
        }

        val html = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().body.string()
        val doc = org.jsoup.Jsoup.parse(html)

        val title = Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.let { Parser.unescapeEntities(it, false) }
            ?: doc.selectFirst("h1 span.sr-only, h1")?.text()?.trim()
            ?: anime.title

        val poster = Regex("""\\\"poster\\\":\\\"([^\\\"]+)\\\"""").find(html)?.groupValues?.get(1)
            ?: extractImageUrl(doc.selectFirst("img.poster-image, div.poster-frame img")?.attr("src"))
            ?: anime.thumbnail_url

        val statusRaw = Regex("""\\\"status\\\":\\\"([^\\\"]+)\\\"""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("span.status")?.text()?.trim()

        val genresRaw = Regex("""\\\"genres\\\":\[([^\]]+)\]""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("""[\\"]"""), "")

        val studiosRaw = Regex("""\\\"studios\\\":\[([^\]]+)\]""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("""[\\"]"""), "")

        val score = Regex("""\\\"score\\\":\\\"([^\\\"]+)\\\"""").find(html)?.groupValues?.get(1)?.toDoubleOrNull()

        val descriptionRaw = Regex("""<meta[^>]+name="description"[^>]+content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.let { Parser.unescapeEntities(it, false) }
            ?: doc.selectFirst("div.description, div.synopsis")?.text()?.trim()

        return anime.apply {
            this.title = title
            this.thumbnail_url = poster
            this.genre = genresRaw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.joinToString(", ")
            this.author = studiosRaw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.joinToString(", ")
            this.status = when {
                statusRaw == null -> SAnime.UNKNOWN
                statusRaw.contains("Ongoing", ignoreCase = true) || statusRaw.contains("Airing", ignoreCase = true) || statusRaw.contains("RELEASING", ignoreCase = true) -> SAnime.ONGOING
                statusRaw.contains("Finished", ignoreCase = true) || statusRaw.contains("COMPLETED", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            this.description = buildString {
                if (score != null && score > 0.0) {
                    append("★ %.1f / 10\n\n".format(Locale.US, score))
                }
                if (!descriptionRaw.isNullOrBlank()) {
                    append(descriptionRaw)
                }
            }.trim()
            this.initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (isTrialExpired || anime.url == "#trial-expired") {
            return emptyList()
        }

        val html = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().body.string()

        val matches = Regex("""\\\"id\\\":(\d+),\\\"number\\\":(\d+),\\\"title\\\":\\\"([^\\\"]*)\\\"""").findAll(html)
        val epTitles = mutableMapOf<Int, String>()

        matches.forEach { m ->
            val num = m.groupValues[2].toIntOrNull() ?: return@forEach
            val title = m.groupValues[3].trim()
            val existing = epTitles[num]
            if (existing == null || (existing.startsWith("Episode ", ignoreCase = true) && !title.startsWith("Episode ", ignoreCase = true))) {
                epTitles[num] = title
            }
        }

        if (epTitles.isEmpty()) {
            val epCount = Regex("""\\\"episodeCount\\\":(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""\\\"episodes\\\":\\\"(\d+)\\\"""").find(html)?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            for (i in 1..epCount) {
                epTitles[i] = "Episode $i"
            }
        }

        val cleanAnimeUrl = anime.url.substringBefore("#")
        val episodes = epTitles.map { (num, title) ->
            SEpisode.create().apply {
                url = "$cleanAnimeUrl#ep=$num"
                name = if (title.isBlank() || title.equals("Episode $num", ignoreCase = true)) {
                    "Episode $num"
                } else {
                    "Episode $num: $title"
                }
                episode_number = num.toFloat()
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        if (isTrialExpired) return emptyList()

        val animeId = Regex("""/anime/(\d+)""").find(episode.url)?.groupValues?.get(1) ?: return emptyList()
        val epNum = Regex("""#ep=(\d+)""").find(episode.url)?.groupValues?.get(1) ?: "1"
        val animeSlug = episode.url.substringAfter("/anime/$animeId/").substringBefore("#").ifBlank { "stream" }

        val hosters = mutableListOf<Hoster>()

        listOf("sub", "dub").forEach { lang ->
            try {
                val apiUrl = "$MAIN_BASE_URL/api/watch/streams?id=$animeId&ep=$epNum&lang=$lang"
                val streamReqHeaders = headers.newBuilder()
                    .add("x-anikura-player", "1")
                    .set("Referer", "$MAIN_BASE_URL/watch/$animeId/$animeSlug?ep=$epNum&lang=$lang")
                    .build()

                val res = client.newCall(GET(apiUrl, streamReqHeaders)).execute()
                if (res.isSuccessful) {
                    val body = res.body.string()
                    val json = JSONObject(body)
                    val streams = json.optJSONArray("streams") ?: return@forEach
                    for (i in 0 until streams.length()) {
                        val streamObj = streams.getJSONObject(i)
                        val streamUrl = streamObj.optString("url")
                        if (streamUrl.isBlank()) continue
                        val label = streamObj.optString("label").ifBlank { "Server ${i + 1} (${lang.uppercase()})" }
                        val fixedUrl = UrlUtils.fixUrl(streamUrl, MAIN_BASE_URL)
                        val tracksArray = streamObj.optJSONArray("tracks")?.toString() ?: "[]"
                        val hosterPayload = JSONObject().apply {
                            put("url", fixedUrl)
                            put("label", label)
                            put("tracks", tracksArray)
                        }.toString()

                        hosters.add(
                            Hoster(
                                hosterName = label,
                                hosterUrl = hosterPayload,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        if (isTrialExpired) return emptyList()

        val hosterData = try {
            JSONObject(hoster.hosterUrl)
        } catch (_: Exception) {
            null
        }

        val streamUrl = hosterData?.optString("url") ?: hoster.hosterUrl
        val tracksJsonStr = hosterData?.optString("tracks") ?: "[]"
        val tracksJson = try {
            JSONArray(tracksJsonStr)
        } catch (_: Exception) {
            JSONArray()
        }

        val subtitleTracks = mutableListOf<Track>()
        for (i in 0 until tracksJson.length()) {
            val trackObj = tracksJson.getJSONObject(i)
            val trackUrl = trackObj.optString("url")
            val trackLabel = trackObj.optString("label").ifBlank { trackObj.optString("language", "Subtitle") }
            if (trackUrl.isNotBlank()) {
                subtitleTracks.add(Track(url = UrlUtils.fixUrl(trackUrl, MAIN_BASE_URL), lang = trackLabel))
            }
        }

        val streamHeaders = headers.newBuilder()
            .set("Referer", "$MAIN_BASE_URL/")
            .set("x-anikura-player", "1")
            .build()

        val rawVideos = try {
            if (streamUrl.contains(".m3u8")) {
                val videos = playlistUtils.extractFromHls(
                    playlistUrl = streamUrl,
                    referer = "$MAIN_BASE_URL/",
                    videoNameGen = { quality -> quality },
                    subtitleList = subtitleTracks,
                )
                if (videos.isNotEmpty()) {
                    videos
                } else {
                    listOf(
                        Video(
                            videoUrl = streamUrl,
                            videoTitle = "Default",
                            headers = streamHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            } else {
                listOf(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = "Default",
                        headers = streamHeaders,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        } catch (_: Exception) {
            listOf(
                Video(
                    videoUrl = streamUrl,
                    videoTitle = "Default",
                    headers = streamHeaders,
                    subtitleTracks = subtitleTracks,
                ),
            )
        }

        val proxiedVideos = rawVideos.map { video ->
            val proxyInputUrl = if (video.videoUrl.contains(".m3u8", ignoreCase = true)) {
                if (video.videoUrl.contains(".m3u8?", ignoreCase = true) || video.videoUrl.endsWith(".m3u8", ignoreCase = true) || video.videoUrl.contains(".m3u8#", ignoreCase = true)) {
                    video.videoUrl
                } else {
                    "${video.videoUrl}#.m3u8"
                }
            } else {
                "${video.videoUrl}#.m3u8"
            }

            val proxied = m3u8Integration.processVideoList(
                listOf(
                    Video(
                        videoUrl = proxyInputUrl,
                        videoTitle = video.videoTitle,
                        subtitleTracks = video.subtitleTracks,
                        audioTracks = video.audioTracks,
                        headers = video.headers ?: streamHeaders,
                    ),
                ),
            ).firstOrNull() ?: video

            Video(
                videoUrl = proxied.videoUrl,
                videoTitle = video.videoTitle,
                subtitleTracks = proxied.subtitleTracks,
                audioTracks = proxied.audioTracks,
                headers = video.headers ?: streamHeaders,
            )
        }

        return proxiedVideos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    private fun extractImageUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        return try {
            if (rawUrl.contains("/api/media/image?u=")) {
                val encoded = rawUrl.substringAfter("/api/media/image?u=").substringBefore("&")
                URLDecoder.decode(encoded, "UTF-8")
            } else {
                UrlUtils.fixUrl(rawUrl, MAIN_BASE_URL)
            }
        } catch (_: Exception) {
            UrlUtils.fixUrl(rawUrl, MAIN_BASE_URL)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("AniKoto", "AniBD", "KAA"),
            entryValues = listOf("AniKoto", "AniBD", "KAA"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
    }

    companion object {
        const val MAIN_BASE_URL = "https://anikura.club"
        const val EXPIRED_BASE_URL = "https://cdn.dribbble.com/userupload/23193562/file/original-01678fc58a3e26f5817db853432cd78a.png?resize=752x&vertical=center"

        // 5-day trial period from today (2026-09-21)
        private const val TRIAL_START_TIMESTAMP = 1789961660000L
        private const val TRIAL_DURATION_MS = 5L * 24 * 60 * 60 * 1000L // 5 days
        private const val TRIAL_EXPIRATION_TIMESTAMP = TRIAL_START_TIMESTAMP + TRIAL_DURATION_MS

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "AniKoto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

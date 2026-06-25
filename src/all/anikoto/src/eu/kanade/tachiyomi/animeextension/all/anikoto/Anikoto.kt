package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class Anikoto : Source() {

    override val name = "Anikoto"
    override val baseUrl = "https://anikototv.to"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val builder = request.newBuilder()
            if (request.header("User-Agent") == null) {
                builder.header("User-Agent", "Mozilla/5.0")
            }
            if (request.header("Referer") == null) {
                builder.header("Referer", "$baseUrl/")
            }
            chain.proceed(builder.build())
        }
        .build()

    private val noCloudflareClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                if (request.header("Referer") == null) {
                    builder.header("Referer", "https://vidtube.site/")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    private val extractors by lazy { AnikotoExtractors(noCloudflareClient, json) }
    private val metadataFetcher by lazy { EpisodeMetadataFetcher(client, json) }

    // ---- Preferences ----

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val titleLang: String
        get() = preferences.getString(PREF_TITLE_LANG, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val prefetchBuffer: String
        get() = preferences.getString(PREF_BUFFER, PREF_BUFFER_DEFAULT) ?: PREF_BUFFER_DEFAULT

    private val preferredServer: String
        get() = preferences.getString(PREF_SERVER, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

    private val loadThumbnails: Boolean
        get() = preferences.getBoolean(PREF_LOAD_THUMBNAILS, true)

    private val loadTitles: Boolean
        get() = preferences.getBoolean(PREF_LOAD_TITLES, true)

    private val loadDescriptions: Boolean
        get() = preferences.getBoolean(PREF_LOAD_DESCRIPTIONS, true)

    // ---- Headers ----

    private fun ajaxHeaders(slug: String): Headers {
        val referer = if (slug.isEmpty()) "$baseUrl/" else "$baseUrl/watch/$slug/ep-1"
        return headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", referer)
            .build()
    }

    // ---- Browse ----

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/most-viewed?page=$page")).execute()
        return parseAnimeList(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/latest-updated?page=$page")).execute()
        return parseAnimeList(response.asJsoup())
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$baseUrl/filter".toHttpUrl().newBuilder()
        if (query.isNotBlank()) urlBuilder.addQueryParameter("keyword", query)
        for (filter in filters) {
            when (filter) {
                is SortFilter -> filter.toQuery()?.let { urlBuilder.addQueryParameter("sort", it) }
                is GenreFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("genre[]", it) }
                is TypeFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("term_type[]", it) }
                is StatusFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("status[]", it) }
                is LanguageFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("language[]", it) }
                else -> {}
            }
        }
        urlBuilder.addQueryParameter("page", page.toString())
        val response = client.newCall(GET(urlBuilder.build())).execute()
        return parseAnimeList(response.asJsoup())
    }

    override fun getFilterList(): AnimeFilterList = getAnikotoFilters()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl/watch/${anime.url}/ep-1")).execute()
        return parseAnimeDetails(response.asJsoup(), anime.url)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        logi("getEpisodeList(url=${anime.url})")
        val slug = anime.url
        val detailResponse = client.newCall(GET("$baseUrl/watch/$slug/ep-1")).execute()
        val detailDoc = detailResponse.asJsoup()
        val watchMain = detailDoc.selectFirst("#watch-main")
        val animeId = watchMain?.attr("data-id") ?: run {
            loge("getEpisodeList: no #watch-main data-id found")
            return emptyList()
        }
        if (animeId.isEmpty()) {
            loge("getEpisodeList: data-id is empty")
            return emptyList()
        }

        val vrf = AnikotoRC4.encodeVrf(animeId)
        val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=${URLEncoder.encode(vrf, "UTF-8")}&style=default"
        val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders(slug))).execute()
        val ajaxJson = json.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())
        if (ajaxJson.status != 200 || ajaxJson.result.isEmpty()) {
            loge("getEpisodeList: ajax status=${ajaxJson.status}")
            return emptyList()
        }

        val epDoc = Jsoup.parse(ajaxJson.result)
        val elements = epDoc.select("ul.ep-range a, .ep-range a")
        val episodes = elements.mapNotNull { element ->
            val num = element.attr("data-num")
            if (num.isEmpty()) return@mapNotNull null
            val malId = element.attr("data-mal")
            val timestamp = element.attr("data-timestamp")
            val dataIds = element.attr("data-ids")
            val hasSub = element.attr("data-sub") == "1"
            val hasDub = element.attr("data-dub") == "1"
            var title = element.attr("title")
            if (title.isBlank()) title = "Episode $num"
            val meta = EpisodeMeta(slug, num, malId, timestamp, dataIds, hasSub, hasDub, title)
            SEpisode.create().apply {
                url = meta.encode()
                name = title
                episode_number = num.toFloatOrNull() ?: 0.0f
                date_upload = (timestamp.toLongOrNull() ?: 0L) * 1000L
                val scanlatorList = mutableListOf<String>()
                if (hasSub) scanlatorList.add("Sub")
                if (hasDub) scanlatorList.add("Dub")
                scanlator = if (scanlatorList.isEmpty()) "Raw" else scanlatorList.joinToString(" / ")
            }
        }.reversed()

        return enrichEpisodesWithMetadata(episodes, detailDoc)
    }

    private suspend fun enrichEpisodesWithMetadata(
        episodes: List<SEpisode>,
        detailDoc: Document,
    ): List<SEpisode> {
        if (!loadThumbnails && !loadTitles && !loadDescriptions) return episodes

        val firstMeta = episodes.firstOrNull()?.let {
            runCatching { EpisodeMeta.decode(it.url) }.getOrNull()
        }
        val malId = firstMeta?.malId?.takeIf { it.isNotBlank() } ?: return episodes

        val animeCoverUrl = detailDoc.selectFirst("#w-info .poster img")?.absUrl("src")

        return try {
            logi("enrichEpisodesWithMetadata: malId=$malId, thumbs=$loadThumbnails, titles=$loadTitles, descs=$loadDescriptions")
            val metadataMap = metadataFetcher.fetch(malId, animeCoverUrl)
            if (metadataMap.isEmpty()) return episodes

            episodes.map { episode ->
                val epNum = episode.episode_number.toInt()
                val episodeMeta = metadataMap[epNum] ?: return@map episode
                episode.apply {
                    if (loadThumbnails && !episodeMeta.thumbnailUrl.isNullOrEmpty()) {
                        preview_url = episodeMeta.thumbnailUrl
                    }
                    if (loadDescriptions && !episodeMeta.description.isNullOrEmpty()) {
                        summary = episodeMeta.description
                    }
                    if (loadTitles && !episodeMeta.title.isNullOrBlank()) {
                        name = "Episode $epNum - ${episodeMeta.title}"
                    }
                }
            }
        } catch (e: Exception) {
            loge("enrichEpisodesWithMetadata: FAILED", e)
            episodes
        }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val meta = runCatching { EpisodeMeta.decode(episode.url) }.getOrNull() ?: return super.getHosterList(episode)
        val tasks = buildHosterTasks(meta)
        if (tasks.isEmpty()) return super.getHosterList(episode)

        val hosters = tasks
            .groupBy { it.serverName() }
            .map { (serverName, serverTasks) ->
                Hoster(
                    hosterName = serverName,
                    hosterUrl = HosterTask.encodeSelection(episode.url, serverTasks),
                )
            }
        return sortHostersByPriority(hosters, preferredServer)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val selection = HosterTask.decodeSelection(hoster.hosterUrl) ?: return super.getVideoList(hoster)
        val (episodeUrl, tasks) = selection
        val meta = runCatching { EpisodeMeta.decode(episodeUrl) }.getOrNull() ?: return emptyList()
        return videosFromTasks(meta, tasks)
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        logi("=== getVideoList START ===")
        val meta = try {
            EpisodeMeta.decode(episode.url)
        } catch (e: Exception) {
            loge("EpisodeMeta.decode FAILED", e)
            return emptyList()
        }
        return videosFromTasks(meta, buildHosterTasks(meta))
    }

    override suspend fun resolveVideo(video: Video): Video {
        activeProxyServer?.onQualitySwitch()
        return video
    }

    private suspend fun resolveStreamForTask(task: HosterTask, slug: String): LocalProxyServer.AudioStream? {
        logi("--- resolving: ${task.label} ---")
        return try {
            val encodedToken = URLEncoder.encode(task.token, "UTF-8")
            val ajaxUrl = "$baseUrl/ajax/server?get=$encodedToken"
            val response = client.newCall(GET(ajaxUrl, ajaxHeaders(slug))).execute()
            val jsonResponse = json.decodeFromString<ServerResponse>(response.body.string())
            val url = jsonResponse.result?.url
            if (jsonResponse.status != 200 || url.isNullOrEmpty()) {
                loge("  [${task.label}] resolve FAILED: status=${jsonResponse.status}")
                return null
            }

            val host = url.substringAfter("://").substringBefore("/")
            val hosterName = task.label.substringAfter(" - ")
            logi("  [${task.label}] iframe=$url host=$host")

            when {
                host.contains("vidtube.site") ||
                    host.contains("megaplay.buzz") ||
                    host.contains("vidwish.live") -> {
                    logi("  [${task.label}] → Flow A (VidTube), host=$host")
                    extractors.resolveVidTube(url, task.audioType, hosterName)
                }

                host.contains("mewcdn.online") -> {
                    logi("  [${task.label}] → Flow B (Kiwi), host=$host")
                    extractors.resolveKiwi(url, task.audioType, hosterName)
                }

                else -> {
                    Log.w(TAG, "  [${task.label}] UNKNOWN host=$host, skipping")
                    null
                }
            }
        } catch (e: Exception) {
            loge("  [${task.label}] CRASHED", e)
            null
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferredQuality
        val prefAudioLabel = when (preferredAudio) {
            "A-DUB" -> "DUB"
            "H-SUB" -> "HSUB"
            else -> PREF_AUDIO_DEFAULT
        }
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.startsWith(prefAudioLabel, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) },
        )
    }

    private fun sortHostersByPriority(hosters: List<Hoster>, prefServer: String): List<Hoster> {
        val priority = HOSTER_PRIORITY
        return if (prefServer == PREF_SERVER_DEFAULT) {
            hosters.sortedBy { h ->
                priority.indexOfFirst { h.hosterName.contains(it, ignoreCase = true) }
                    .let { if (it < 0) Int.MAX_VALUE else it }
            }
        } else {
            hosters.sortedWith(
                compareBy { h ->
                    if (h.hosterName.contains(prefServer, ignoreCase = true)) {
                        0
                    } else {
                        priority.indexOfFirst { h.hosterName.contains(it, ignoreCase = true) }
                            .let { if (it < 0) Int.MAX_VALUE else it + 1 }
                    }
                },
            )
        }
    }

    private suspend fun buildHosterTasks(meta: EpisodeMeta): List<HosterTask> {
        val tasks = mutableListOf<HosterTask>()

        if (meta.malId.isNotEmpty() && meta.epNum.isNotEmpty() && meta.timestamp.isNotEmpty()) {
            val mapperUrl = "https://mapper.nekostream.site/api/mal/${meta.malId}/${meta.epNum}/${meta.timestamp}"
            logi("mapper API: GET $mapperUrl")
            try {
                val mapperResponse = client.newCall(GET(mapperUrl, ajaxHeaders(meta.slug))).execute()
                val bodyStr = mapperResponse.body.string()
                val jsonObject = json.parseToJsonElement(bodyStr) as? JsonObject
                if (jsonObject != null) {
                    val mapperTokens = parseMapperResponse(jsonObject)
                    logi("mapper parsed ${mapperTokens.size} tokens")
                    for (token in mapperTokens) {
                        val label = when (token.audio) {
                            "sub" -> "H-SUB - ${token.serverName}"
                            "dub" -> "A-DUB - ${token.serverName}"
                            else -> "${token.audio.uppercase(Locale.ROOT)} - ${token.serverName}"
                        }
                        tasks.add(HosterTask(label, token.token, token.audio, "mapper"))
                    }
                }
            } catch (e: Exception) {
                loge("mapper API FAILED", e)
            }
        }

        if (meta.dataIds.isNotEmpty()) {
            val primaryUrl = "$baseUrl/ajax/server/list?servers=${meta.dataIds}"
            try {
                val primaryResponse = client.newCall(GET(primaryUrl, ajaxHeaders(meta.slug))).execute()
                val pJson = json.decodeFromString<ServerListResponse>(primaryResponse.body.string())
                if (pJson.status == 200 && pJson.result.isNotEmpty()) {
                    val pDoc = Jsoup.parse(pJson.result)
                    for (element in pDoc.select("div.servers > div.type")) {
                        val dataType = element.attr("data-type")
                        val audioLabel = when (dataType) {
                            "dub" -> "DUB"
                            "sub" -> "SUB"
                            "hsub" -> "HSUB"
                            else -> dataType.uppercase(Locale.ROOT)
                        }
                        for (serverElement in element.select("li")) {
                            val linkId = serverElement.attr("data-link-id")
                            val text = serverElement.text()
                            if (linkId.isNotEmpty()) {
                                tasks.add(HosterTask("$audioLabel - $text", linkId, dataType, "primary"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                loge("primary API FAILED", e)
            }
        }

        return tasks.distinctBy { "${it.audioType}|${it.label}|${it.token}" }
    }

    private suspend fun videosFromTasks(meta: EpisodeMeta, tasks: List<HosterTask>): List<Video> {
        logi("Total tasks: ${tasks.size}")
        if (tasks.isEmpty()) return emptyList()

        val resolvedStreams = coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    resolveStreamForTask(task, meta.slug)
                }
            }.awaitAll().filterNotNull()
        }

        logi("Total resolved streams: ${resolvedStreams.size}")
        if (resolvedStreams.isEmpty()) {
            displayToast("Anikoto: No playable streams found", Toast.LENGTH_LONG)
            return emptyList()
        }

        val segHeaders = Headers.Builder()
            .add("Referer", "https://vidtube.site/")
            .add("User-Agent", USER_AGENT)
            .build()

        val server = LocalProxyServer(noCloudflareClient, segHeaders)
        server.playlist = LocalProxyServer.Playlist(resolvedStreams)
        server.prefetchCount = prefetchBuffer.toIntOrNull() ?: 10
        server.start()
        swapProxyServer(server)

        val allVideos = mutableListOf<Video>()
        for (stream in resolvedStreams) {
            val streamKey = "${stream.audioType}-${stream.hosterName}"
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
            val subtitleTracks = server.getSubtitleTracks(stream)
            for (variant in stream.variants) {
                val videoUrl = "${server.baseUrl}/variant/$streamKey/${variant.quality}.m3u8"
                val audioLabel = when (stream.audioType) {
                    "dub" -> "DUB"
                    "sub" -> "SUB"
                    "hsub" -> "HSUB"
                    else -> stream.audioType.uppercase(Locale.ROOT)
                }
                val title = "$audioLabel - ${variant.quality}"
                allVideos.add(
                    Video(
                        videoUrl = videoUrl,
                        videoTitle = title,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        }

        return try {
            allVideos.sortVideos()
        } catch (_: Throwable) {
            allVideos
        }
    }

    // ---- Parsers ----

    private fun parseAnimeList(doc: Document): AnimesPage {
        val elements = doc.select("div#list-items > div.item")
        val animes = elements.mapNotNull { element ->
            val nameLink = element.selectFirst("a.name.d-title")
                ?: element.selectFirst("div.ani.poster.tip > a")
                ?: return@mapNotNull null
            val href = nameLink.attr("href")
            val slug = href.substringAfter("/watch/", "").substringBefore("/ep-", "")
            if (slug.isEmpty()) return@mapNotNull null
            SAnime.create().apply {
                url = slug
                var titleText = nameLink.text().trim()
                if (titleText.isEmpty()) titleText = nameLink.attr("data-jp")
                title = titleText
                val posterImg = element.selectFirst("div.ani.poster.tip img")
                thumbnail_url = posterImg?.absUrl("src") ?: element.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNext = if (doc.selectFirst("ul.pagination li:has(a[rel=next])") != null) {
            true
        } else {
            try {
                val activePage = doc.selectFirst("ul.pagination li.active")?.text()?.toIntOrNull() ?: 0
                val pages = doc.select("ul.pagination li.page-item a.page-link").mapNotNull { it.text().toIntOrNull() }
                pages.any { it > activePage }
            } catch (e: Exception) {
                false
            }
        }
        return AnimesPage(animes, hasNext)
    }

    private fun parseAnimeDetails(doc: Document, slug: String): SAnime {
        val useJp = titleLang == "jp"
        return SAnime.create().apply {
            url = slug
            val titleHeader = doc.selectFirst("#w-info h1.title.d-title")
            title = if (useJp) {
                val jpTitle = titleHeader?.attr("data-jp")
                if (!jpTitle.isNullOrEmpty()) jpTitle else titleHeader?.text() ?: slug
            } else {
                titleHeader?.text() ?: slug
            }
            val posterImg = doc.selectFirst("#w-info .poster img")
            thumbnail_url = posterImg?.absUrl("src")
            description = buildDescription(doc)
            val genres = doc.select("#w-info .bmeta a[href*=\"/genre/\"]")
            genre = genres.joinToString(", ") { it.text() }
            val statusElement = doc.selectFirst("#w-info .bmeta a[href*=\"/status/\"]")
            status = parseStatus(statusElement?.text())
            val studiosMeta = doc.select("#w-info .bmeta .meta > div").firstOrNull {
                it.text().contains("Studios", ignoreCase = true)
            }
            author = studiosMeta?.select("a")?.joinToString(", ") { it.text() }
            artist = author
        }
    }

    private fun buildDescription(doc: Document): String {
        val synopsis = doc.selectFirst("#w-info .synopsis .content")?.text()
            ?: doc.selectFirst("#w-info .synopsis")?.text() ?: ""
        val bmeta = doc.selectFirst("#w-info .bmeta") ?: return synopsis
        val metaList = mutableListOf<String>()
        for (element in bmeta.select(".meta > div")) {
            val label = element.selectFirst("label, strong, b")?.text()?.removeSuffix(":")
            val value = element.select("span, a").joinToString(", ") { it.text() }
            if (label != null && value.isNotEmpty()) metaList.add("$label: $value")
        }
        return if (metaList.isEmpty()) synopsis else metaList.joinToString("\n") + "\n\n" + synopsis
    }

    private fun parseStatus(text: String?): Int {
        val lowerCase = text?.lowercase(Locale.ROOT) ?: return SAnime.UNKNOWN
        return when (lowerCase) {
            "finished airing" -> SAnime.COMPLETED
            "ongoing", "currently airing", "not yet aired", "upcoming" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ---- Preferences ----

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        try {
            screen.addListPreference(
                key = PREF_QUALITY,
                default = PREF_QUALITY_DEFAULT,
                title = "Preferred quality",
                summary = "Sorts videos so this quality is on top",
                entries = listOf("1080p", "720p", "480p", "360p"),
                entryValues = listOf("1080", PREF_QUALITY_DEFAULT, "480", "360"),
            )
            screen.addListPreference(
                key = PREF_AUDIO,
                default = PREF_AUDIO_DEFAULT,
                title = "Preferred audio",
                summary = "Sub, Dub, or Hsub first",
                entries = listOf("Sub", "Dub", "Hsub"),
                entryValues = listOf(PREF_AUDIO_DEFAULT, "A-DUB", "H-SUB"),
            )
            screen.addListPreference(
                key = PREF_TITLE_LANG,
                default = PREF_TITLE_LANG_DEFAULT,
                title = "Title language",
                summary = "Show English or Japanese titles",
                entries = listOf("English", "Japanese"),
                entryValues = listOf(PREF_TITLE_LANG_DEFAULT, "jp"),
            )
            screen.addListPreference(
                key = PREF_BUFFER,
                default = PREF_BUFFER_DEFAULT,
                title = "Pre-fetch buffer",
                summary = "How much to download ahead of playback. Higher = smoother but more data.",
                entries = listOf("10%", "20%", "30%", "40%", "50%", "60%", "70%", "80%", "90%", "100%"),
                entryValues = listOf(PREF_BUFFER_DEFAULT, "20", "30", "40", "50", "60", "70", "80", "90", "100"),
            )
            screen.addListPreference(
                key = PREF_SERVER,
                default = PREF_SERVER_DEFAULT,
                title = "Preferred video server",
                summary = "Which video server to try first. Auto picks the best available.",
                entries = listOf("Auto", "VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream"),
                entryValues = listOf(PREF_SERVER_DEFAULT, "VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream"),
            )

            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_LOAD_THUMBNAILS
                title = "Load episode thumbnails"
                summaryOn = "Fetching preview images from external sources"
                summaryOff = "Episode thumbnails disabled (faster episode list loading)"
                setDefaultValue(true)
            }.also { screen.addPreference(it) }

            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_LOAD_TITLES
                title = "Load episode titles"
                summaryOn = "Fetching episode titles from external sources"
                summaryOff = "Using default episode numbers only"
                setDefaultValue(true)
            }.also { screen.addPreference(it) }

            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_LOAD_DESCRIPTIONS
                title = "Load episode descriptions"
                summaryOn = "Fetching episode descriptions from external sources"
                summaryOff = "Episode descriptions disabled"
                setDefaultValue(true)
            }.also { screen.addPreference(it) }
        } catch (e: Exception) {
            loge("setupPreferenceScreen CRASHED", e)
        }
    }

    // ---- Logging ----

    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun loge(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    companion object {
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private const val PREF_AUDIO = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "SUB"
        private const val PREF_TITLE_LANG = "pref_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "en"
        private const val PREF_BUFFER = "pref_buffer"
        private const val PREF_BUFFER_DEFAULT = "10"
        private const val PREF_SERVER = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_LOAD_THUMBNAILS = "pref_load_thumbnails"
        private const val PREF_LOAD_TITLES = "pref_load_titles"
        private const val PREF_LOAD_DESCRIPTIONS = "pref_load_descriptions"

        private const val TAG = "Anikoto"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val HOSTER_PRIORITY = listOf("VidCloud-1", "VidPlay-1", "Vidstream-2", "HD-1", "Kiwi-Stream")

        @Volatile
        private var activeProxyServer: LocalProxyServer? = null

        @Synchronized
        private fun swapProxyServer(newServer: LocalProxyServer): LocalProxyServer {
            activeProxyServer?.let { runCatching { it.stop() } }
            activeProxyServer = newServer
            return newServer
        }
    }
}

data class VariantInfo(
    val url: String,
    val bandwidth: Int,
    val quality: String,
    val resolution: Int,
)

package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
        val builder = client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)

        // Remove Cloudflare interceptor to prevent WebView popups in background thread
        builder.interceptors().removeAll { it.javaClass.simpleName.contains("Cloudflare", true) }

        builder.build()
    }

    private val proxyFetchClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val metadataClient by lazy {
        val builder = network.client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)

        builder.interceptors().removeAll { it.javaClass.simpleName.contains("Cloudflare", true) }

        builder
            .rateLimitHost("https://api.jikan.moe".toHttpUrl(), 2, 1, TimeUnit.SECONDS)
            .rateLimitHost("https://graphql.anilist.co".toHttpUrl(), 2, 1, TimeUnit.SECONDS)
            .rateLimitHost("https://kitsu.app".toHttpUrl(), 2, 1, TimeUnit.SECONDS)
            .rateLimitHost("https://anikage.cc".toHttpUrl(), 2, 1, TimeUnit.SECONDS)
            .build()
    }

    private val webViewFetcher by lazy { WebViewFetcher() }
    private val extractors by lazy { AnikotoExtractors(client, json, webViewFetcher) }
    private val metadataFetcher by lazy { EpisodeMetadataFetcher(client, json, webViewFetcher) }

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

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0")
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

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
                        val epNumStr = if (episode_number % 1 == 0f) episode_number.toInt().toString() else episode_number.toString()
                        name = "Episode $epNumStr: ${episodeMeta.title}"
                    }
                }
            }
        } catch (e: Exception) {
            loge("enrichEpisodesWithMetadata: FAILED", e)
            episodes
        }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        logi("=== getHosterList START ===")
        logi("episode.url = ${episode.url}")
        logi("episode.name = ${episode.name}")

        val meta = try {
            EpisodeMeta.decode(episode.url)
        } catch (e: Exception) {
            loge("getHosterList: EpisodeMeta.decode FAILED — episode.url is not a valid encoded meta", e)
            return emptyList()
        }

        logi("getHosterList: EpisodeMeta parsed OK: slug=${meta.slug} num=${meta.epNum} mal=${meta.malId} ts=${meta.timestamp} hasSub=${meta.hasSub} hasDub=${meta.hasDub}")

        val tasks = mutableListOf<HosterTask>()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()

        // PATH A: Primary Server List
        if (meta.dataIds.isNotEmpty()) {
            val primaryUrl = "$baseUrl/ajax/server/list?servers=${meta.dataIds}"
            logi("PATH A: GET $primaryUrl")
            try {
                val primaryResponse = client.newCall(GET(primaryUrl, ajaxHeaders(meta.slug))).execute()
                val pJson = json.decodeFromString<ServerListResponse>(primaryResponse.body.string())
                logi("PATH A: parsed status=${pJson.status}, result HTML length = ${pJson.result.length}")
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
                        if (excludedAudios.any { it.equals(audioLabel, true) }) {
                            continue
                        }

                        for (serverElement in element.select("li")) {
                            val linkId = serverElement.attr("data-link-id")
                            val serverName = serverElement.text().trim()
                            if (excludedServers.any { it.equals(serverName, true) }) {
                                continue
                            }
                            if (linkId.isNotEmpty()) {
                                val label = "$audioLabel - $serverName"
                                tasks.add(HosterTask(label, linkId, dataType, "primary", meta.slug))
                                logi("  + task (primary): $label")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                loge("PATH A: FAILED — continuing to mapper", e)
            }
        }

        // PATH B: Nekostream Mapper API (Kiwi-Stream)
        val enableKiwi = preferences.getBoolean(PREF_ENABLE_KIWI_KEY, PREF_ENABLE_KIWI_DEFAULT)
        if (!enableKiwi) {
            logi("PATH B: skipped (Kiwi-Stream disabled in settings)")
        } else if (meta.malId.isEmpty() || meta.epNum.isEmpty() || meta.timestamp.isEmpty()) {
            loge("PATH B: skipped (missing malId/epNum/timestamp in EpisodeMeta)")
        } else {
            val mapperUrl = "https://mapper.nekostream.site/api/mal/${meta.malId}/${meta.epNum}/${meta.timestamp}"
            logi("PATH B: GET $mapperUrl")
            try {
                val mapperResponse = client.newCall(GET(mapperUrl, ajaxHeaders(meta.slug))).execute()
                if (mapperResponse.isSuccessful) {
                    val bodyStr = mapperResponse.body.string()
                    val jsonObj = json.decodeFromString<JsonObject>(bodyStr)
                    val mapperTokens = parseMapperResponse(jsonObj)
                    logi("PATH B: parsed ${mapperTokens.size} mapper tokens")

                    if (mapperTokens.isEmpty()) {
                        val keys = jsonObj.keys
                        if (keys.any { it == "Kiwi-Stream" }) {
                            logi("PATH B: Kiwi-Stream has download links but no streaming URL — streaming not available for this episode")
                        } else {
                            logi("PATH B: no Kiwi-Stream entries found in mapper response")
                        }
                    }

                    for (token in mapperTokens) {
                        val audioLabel = when (token.audio) {
                            "dub" -> "DUB"
                            "sub" -> "SUB"
                            "hsub" -> "HSUB"
                            else -> token.audio.uppercase(Locale.ROOT)
                        }
                        if (excludedAudios.any { it.equals(audioLabel, true) }) {
                            continue
                        }
                        if (token.serverName == "Kiwi-Stream") {
                            val serverName = token.serverName
                            if (excludedServers.any { it.equals(serverName, true) }) {
                                continue
                            }
                            val label = "$audioLabel - $serverName"
                            tasks.add(HosterTask(label, token.token, token.audio, "mapper", meta.slug))
                            logi("  + task (mapper): $label")
                        }
                    }
                }
            } catch (e: Exception) {
                loge("PATH B: mapper FAILED — continuing with primary tasks", e)
            }
        }

        logi("getHosterList: total servers found = ${tasks.size}")
        if (tasks.isEmpty()) {
            loge("getHosterList: no tasks to resolve — returning empty")
            return emptyList()
        }

        logi("getHosterList: resolving ${tasks.size} servers in parallel...")
        for (task in tasks) {
            logi("  server: ${task.label} [${task.audioType}] source=${task.source} token=${task.token.take(40)}")
        }

        val resolvedStreams = coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    resolveStreamForTask(task, task.slug)
                }
            }.awaitAll().filterNotNull()
        }

        logi("getHosterList: resolved ${resolvedStreams.size}/${tasks.size} streams")
        if (resolvedStreams.isEmpty()) {
            loge("getHosterList: all streams failed — returning empty")
            return emptyList()
        }

        for (stream in resolvedStreams) {
            logi("  resolved: ${stream.hosterName} [${stream.audioLabel}] — ${stream.variants.size} variants, ${stream.subtitles.size} subs")
        }

        val server = LocalProxyServer(
            client = proxyFetchClient,
            segmentHeaders = Headers.Builder()
                .set("User-Agent", USER_AGENT)
                .set("Referer", "https://vidtube.site/")
                .set("Accept", "*/*")
                .build(),
            webViewFetcher = webViewFetcher,
        )
        server.playlist = LocalProxyServer.Playlist(resolvedStreams)
        server.prefetchCount = prefetchBuffer.toIntOrNull() ?: 10
        val proxyUrl = server.start()
        logi("getHosterList: proxy started at $proxyUrl (prefetch=${server.prefetchCount}%)")
        swapProxyServer(server)

        logi("getHosterList: building Video objects (grouped by server)...")
        val linkedHashMap = mutableMapOf<String, MutableList<Video>>()
        resolvedStreams.forEachIndexed { i, audioStream ->
            val subtitleTracks = server.getSubtitleTracks(audioStream.audioType)
            for (variant in audioStream.variants) {
                val videoUrl = "$proxyUrl/variant/${audioStream.audioType}/${variant.quality}.m3u8"
                val audioPrefix = audioStream.audioLabel.split(" - ").firstOrNull() ?: audioStream.audioLabel
                val title = "$audioPrefix - ${variant.quality}"

                // Using named arguments as required by checklist
                val video = Video(
                    videoUrl = videoUrl,
                    videoTitle = title,
                    subtitleTracks = subtitleTracks,
                    headers = null,
                )
                linkedHashMap.getOrPut(audioStream.hosterName) { mutableListOf() }.add(video)
            }
        }

        val hostersList = mutableListOf<Hoster>()
        for ((serverName, videos) in linkedHashMap) {
            val sortedVideos = try {
                videos.sortVideos()
            } catch (t: Throwable) {
                videos
            }
            logi("  Hoster: $serverName — ${sortedVideos.size} videos")
            hostersList.add(
                Hoster(
                    hosterUrl = "",
                    hosterName = serverName,
                    videoList = sortedVideos,
                ),
            )
        }

        val preferredServerVal = preferredServer
        var sortedHosters = hostersList.toList()
        if (preferredServerVal != PREF_SERVER_DEFAULT) {
            sortedHosters = hostersList.sortedByDescending {
                it.hosterName.contains(preferredServerVal, ignoreCase = true)
            }
        }

        var totalVideosCount = 0
        for (h in sortedHosters) {
            totalVideosCount += h.videoList?.size ?: 0
        }
        logi("getHosterList: ${sortedHosters.size} hosters, $totalVideosCount total videos")
        logi("========== getHosterList END ==========")

        return sortedHosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = getHosterList(episode).flatMap { it.videoList ?: emptyList() }

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

                host.contains("mewcdn.online") || host.contains("zaptrix.buzz") || host.contains("mewstream.buzz") || host.contains("voltara.click") -> {
                    if (preferences.getBoolean(PREF_ENABLE_KIWI_KEY, PREF_ENABLE_KIWI_DEFAULT)) {
                        logi("  [${task.label}] → Flow B (Kiwi), host=$host")
                        extractors.resolveKiwi(url, task.audioType, hosterName)
                    } else {
                        null
                    }
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
            compareByDescending<Video> { it.videoTitle.contains(prefAudioLabel, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) },
        )
    }

    private fun sortHostersByPriority(hosters: List<Hoster>, prefServer: String): List<Hoster> {
        val priority = HOSTER_PRIORITY
        return if (prefServer == PREF_SERVER_DEFAULT) {
            hosters.sortedBy { h -> priority.indexOf(h.hosterName).let { if (it < 0) Int.MAX_VALUE else it } }
        } else {
            hosters.sortedWith(
                compareBy { h ->
                    if (h.hosterName.contains(prefServer, ignoreCase = true)) {
                        0
                    } else {
                        priority.indexOf(h.hosterName).let { if (it < 0) Int.MAX_VALUE else it + 1 }
                    }
                },
            )
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
        val metaMap = mutableMapOf<String, String>()
        for (element in bmeta.select("div.meta > div")) {
            val label = element.ownText().removeSuffix(":").trim()
            val value = element.select("span").text().trim()
            if (label.isNotEmpty() && value.isNotEmpty()) {
                metaMap[label] = value
            }
        }

        val rating = doc.selectFirst("#w-info .binfo i.rating")?.text()
        val altTitles = doc.selectFirst("#w-info .binfo div.names")?.text()

        val sb = java.lang.StringBuilder()
        sb.append(synopsis)

        metaMap["MAL"]?.let { sb.append("\n\nMAL Score: $it") }
        metaMap["Type"]?.let { sb.append("\nType: $it") }
        metaMap["Premiered"]?.let { sb.append("\nPremiered: $it") }
        metaMap["Aired"]?.let { sb.append("\nAired: $it") }
        metaMap["Duration"]?.let { sb.append("\nDuration: $it") }

        val studios = bmeta.select("div:contains(Studios) span a").joinToString(", ") { it.text() }
        if (studios.isNotBlank()) {
            sb.append("\nStudio: $studios")
        }

        rating?.takeIf { it.isNotBlank() }?.let { sb.append("\nRating: $it") }
        altTitles?.takeIf { it.isNotBlank() }?.let { sb.append("\n\nAlt titles: $it") }

        return sb.toString()
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
                key = PREF_ENABLE_KIWI_KEY
                title = "Enable Kiwi-Stream"
                summaryOn = "Fetching Kiwi-Stream from external sources"
                summaryOff = "Kiwi-Stream disabled"
                setDefaultValue(PREF_ENABLE_KIWI_DEFAULT)
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
            MultiSelectListPreference(screen.context).apply {
                key = PREF_EXCLUDE_SERVERS_KEY
                title = "Exclude Servers"
                entries = arrayOf("VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream")
                entryValues = arrayOf("VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream")
                setDefaultValue(emptySet<String>())
                summary = "Select servers to exclude from the video list"
            }.also { screen.addPreference(it) }

            MultiSelectListPreference(screen.context).apply {
                key = PREF_EXCLUDE_AUDIO_KEY
                title = "Exclude Audio"
                entries = arrayOf("Sub", "Dub", "Hsub")
                entryValues = arrayOf("SUB", "DUB", "HSUB")
                setDefaultValue(emptySet<String>())
                summary = "Select audio formats to exclude from the video list"
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

        private const val PREF_ENABLE_KIWI_KEY = "pref_enable_kiwi"
        private const val PREF_ENABLE_KIWI_DEFAULT = true

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"

        private val HOSTER_PRIORITY = listOf("Kiwi-Stream", "VidCloud-1", "VidPlay-1", "Vidstream-2", "HD-1")

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

@kotlinx.serialization.Serializable
data class HosterTask(
    val label: String,
    val token: String,
    val audioType: String,
    val source: String,
    val slug: String,
)

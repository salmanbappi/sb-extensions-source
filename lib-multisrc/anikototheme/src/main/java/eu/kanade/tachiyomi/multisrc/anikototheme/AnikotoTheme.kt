package eu.kanade.tachiyomi.multisrc.anikototheme

import android.app.Application
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
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
import extensions.utils.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

abstract class AnikotoTheme : Source() {

    abstract override val name: String
    abstract override val baseUrl: String
    abstract override val lang: String
    override val supportsLatest = true

    protected open val bmetaSelector = "div.bmeta"
    protected open val scoreLabel = "MAL"
    protected open val scorePrefix = "MAL Score"
    protected open val aliasSelector = "div.names"
    protected open val synopsisSelector = "div.synopsis div.content"
    protected open val detailPosterSelector = "div.poster img"
    protected open val popularAnimeSelector = "div.ani.items > div.item"

    // Server entries differ per site skin: anikoto uses li[data-link-id], sogo uses
    // a.server[data-link-id], suge uses div.server[data-link-id]. Cover all three so the
    // shared getHosterList works on every theme without per-site overrides.
    protected open val serverSelector = "li[data-link-id], a.server[data-link-id], div.server[data-link-id], .server[data-link-id], .server, div.item, .item"
    protected open val typeSelector = "div.servers > div.type, div.types > div.type, div.ani-server-wrapper > div.type, .server-type, div.type, div.server-type"
    protected open val useMapper = false

    protected open fun getVrf(animeId: String): String = URLEncoder.encode(AnikotoRC4.encodeVrf(animeId), "UTF-8")

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

    private val webViewFetcher by lazy { WebViewFetcher(Injekt.get<Application>()) }
    private val extractors by lazy { AnikotoExtractors(client, json, webViewFetcher) }
    private val metadataFetcher by lazy {
        val tmdbKey = try {
            val packageName = this.javaClass.name.substringBeforeLast('.')
            val buildConfigClass = Class.forName("$packageName.BuildConfig")
            buildConfigClass.getField("TMDB_API").get(null) as String
        } catch (_: Exception) {
            ""
        }
        EpisodeMetadataFetcher(client, json, webViewFetcher, tmdbKey)
    }
    private val smartSearch by lazy { SmartSearch(webViewFetcher) }

    // ---- Preferences ----

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

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

    private val smartSearchEnabled: Boolean
        get() = preferences.getBoolean(PREF_SMART_SEARCH, PREF_SMART_SEARCH_DEFAULT)

    private val smartSearchPhrase: String
        get() = preferences.getString(PREF_SMART_SEARCH_PHRASE, PREF_SMART_SEARCH_PHRASE_DEFAULT)
            ?: PREF_SMART_SEARCH_PHRASE_DEFAULT

    // ---- Headers ----

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0")
        .set("Referer", "$baseUrl/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "en-US,en;q=0.9")

    private fun ajaxHeaders(slug: String): Headers {
        // Suge's detail pages live under /anime/ — keep the Referer on a same-origin detail
        // URL so /ajax/server?get= sees an expected referer on every skin.
        val clean = getCleanSlug(slug)
        val referer = if (baseUrl.contains("animesuge")) {
            if (clean.isEmpty()) "$baseUrl/" else "$baseUrl/anime/$clean/ep-1"
        } else {
            if (slug.isEmpty()) "$baseUrl/" else "$baseUrl/watch/$slug/ep-1"
        }
        return headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
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

    private fun getSearchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val urlBuilder = "$baseUrl/filter".toHttpUrl().newBuilder()
        if (query.isNotBlank()) urlBuilder.addQueryParameter("keyword", query)
        for (filter in filters) {
            when (filter) {
                is SortFilter -> filter.toQuery()?.let { urlBuilder.addQueryParameter("sort", it) }
                is GenreFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("genre[]", it) }
                is TypeFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("term_type[]", it) }
                is StatusFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("status[]", it) }
                is LanguageFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("language[]", it) }
                is SeasonFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("season[]", it) }
                is YearFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("year[]", it) }
                is RatingFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("rating[]", it) }
                is SourceFilter -> filter.toQueries().forEach { urlBuilder.addQueryParameter("source[]", it) }
                else -> {}
            }
        }
        urlBuilder.addQueryParameter("page", page.toString())
        return GET(urlBuilder.build())
    }

    protected suspend fun showToast(message: String) {
        try {
            val app = Injekt.get<Application>()
            withContext(Dispatchers.Main) {
                Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            loge("SmartSearch: failed to show toast", e)
        }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (!smartSearch.shouldTrigger(query, smartSearchEnabled, smartSearchPhrase)) {
            val response = client.newCall(getSearchAnimeRequest(page, query, filters)).execute()
            return parseAnimeList(response.asJsoup())
        }

        logi("SmartSearch: triggered (page=$page, query=\"$query\")")
        val strippedQuery = smartSearch.stripPhrase(query, smartSearchPhrase)

        if (strippedQuery.isBlank()) {
            logw("SmartSearch: empty query after phrase strip, falling back to normal search")
            val response = client.newCall(getSearchAnimeRequest(page, query, filters)).execute()
            return parseAnimeList(response.asJsoup())
        }

        val cachedTitle = smartSearch.getCachedTitle(strippedQuery, page)
        val title = if (cachedTitle != null) {
            cachedTitle
        } else {
            val resolved = smartSearch.resolve(strippedQuery)
            if (resolved == null) {
                logw("SmartSearch: AI resolution failed, falling back to normal search")
                smartSearch.cacheTitle(strippedQuery, strippedQuery)
                showToast("AI search was unable to initiate and fell back to normal search")
                val response = client.newCall(getSearchAnimeRequest(page, query, filters)).execute()
                return parseAnimeList(response.asJsoup())
            }
            smartSearch.cacheTitle(strippedQuery, resolved)
            resolved
        }

        logi("SmartSearch: searching AniKoto for \"$title\" (page $page)")
        val request = getSearchAnimeRequest(page, title, filters)
        val response = client.newCall(request).execute()
        var results = parseAnimeList(response.asJsoup())

        if (results.animes.isEmpty() && page == 1 && title != strippedQuery) {
            val shortTitle = title.split(Regex("\\s+"))
                .filter { it.length > 2 }
                .take(3)
                .joinToString(" ")
            if (shortTitle.isNotEmpty() && shortTitle != title) {
                logi("SmartSearch: 0 results for full title, trying short: \"$shortTitle\"")
                val fallbackRequest = getSearchAnimeRequest(page, shortTitle, filters)
                val fallbackResponse = client.newCall(fallbackRequest).execute()
                results = parseAnimeList(fallbackResponse.asJsoup())
            }
        }

        return results
    }

    override fun getFilterList(): AnimeFilterList {
        if (smartSearchEnabled) {
            smartSearch.warmUp()
        }
        return getAnikotoThemeFilters()
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val detailUrl = if (baseUrl.contains("animesuge")) {
            val path = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
            val clean = getCleanSlug(path)
            "$baseUrl/anime/$clean"
        } else {
            "$baseUrl/watch/${anime.url}/ep-1"
        }
        val response = client.newCall(GET(detailUrl)).execute()
        return parseAnimeDetails(response.asJsoup(), anime.url)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        logi("getEpisodeList(url=${anime.url})")
        val slug = anime.url
        // Suge's detail pages live under /anime/ — probe the right detail path per skin.
        val detailUrls = if (baseUrl.contains("animesuge")) {
            val cleanSlug = getCleanSlug(slug)
            listOf("$baseUrl/anime/$cleanSlug", "$baseUrl/anime/$cleanSlug/ep-1")
        } else {
            listOf("$baseUrl/watch/$slug/ep-1")
        }
        val detailDoc = detailUrls.firstNotNullOfOrNull { url ->
            runCatching {
                val doc = client.newCall(GET(url)).execute().asJsoup()
                val watchMain = doc.selectFirst("#watch-page, #watch-main, .watch-wrap, .favourite[data-id], [data-id]")
                val animeId = watchMain?.attr("data-id")
                if (animeId.isNullOrEmpty()) null else doc
            }.getOrNull()
        } ?: run {
            loge("getEpisodeList: no watch main element or data-id found")
            return emptyList()
        }
        val watchMain = detailDoc.selectFirst("#watch-page, #watch-main, .watch-wrap, .favourite[data-id], [data-id]")
        val animeId = watchMain?.attr("data-id") ?: run {
            loge("getEpisodeList: no watch main element or data-id found")
            return emptyList()
        }
        if (animeId.isEmpty()) {
            loge("getEpisodeList: data-id is empty")
            return emptyList()
        }

        val vrf = getVrf(animeId)
        val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf&style=default"
        val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders(slug))).execute()
        val ajaxJson = json.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())
        if (ajaxJson.status != 200 || ajaxJson.result.isEmpty()) {
            loge("getEpisodeList: ajax status=${ajaxJson.status}")
            return emptyList()
        }

        val epDoc = Jsoup.parse(ajaxJson.result)
        // data-num is present on anikoto + sogo skins; suge uses data-slug instead.
        val elements = epDoc.select("ul.ep-range a, .ep-range a, .range a, a[data-ids]")
        val episodes = elements.mapNotNull { element ->
            var num = element.attr("data-num")
            if (num.isEmpty()) num = element.attr("data-slug")
            if (num.isEmpty()) return@mapNotNull null
            val malId = element.attr("data-mal")
            val timestamp = element.attr("data-timestamp")
            val dataIds = element.attr("data-ids")
            val hasSub = element.attr("data-sub") == "1"
            val hasDub = element.attr("data-dub") == "1"
            var title = element.attr("title")
            // Sogo puts the display number in the li title / link text instead of a[title].
            if (title.isBlank()) title = element.text().trim()
            if (title.isBlank()) title = element.parent()?.attr("title")?.trim() ?: ""
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

        val malId = elements.firstNotNullOfOrNull { it.attr("data-mal").takeIf { mal -> mal.isNotEmpty() } } ?: ""
        return enrichEpisodesWithMetadata(episodes, detailDoc, malId)
    }

    private suspend fun enrichEpisodesWithMetadata(
        episodes: List<SEpisode>,
        detailDoc: Document,
        malId: String,
    ): List<SEpisode> {
        if (!loadThumbnails && !loadTitles && !loadDescriptions) return episodes

        val animeTitle = detailDoc.selectFirst("h1.title")?.text()?.trim() ?: ""
        if (malId.isBlank() && animeTitle.isBlank()) return episodes

        val animeCoverUrl = detailDoc.selectFirst("#w-info .poster img")?.absUrl("src")

        return try {
            logi("enrichEpisodesWithMetadata: malId=$malId, title=$animeTitle, thumbs=$loadThumbnails, titles=$loadTitles, descs=$loadDescriptions")
            val metadataMap = metadataFetcher.fetch(malId, animeTitle, animeCoverUrl)
            if (metadataMap.isEmpty()) {
                return episodes
            }

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

    override fun getEpisodeUrl(episode: SEpisode): String = baseUrl + EpisodeMeta.extractUrlPath(episode.url)

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        logi("=== getHosterList START ===")
        logi("episode.url = ${episode.url}")
        logi("episode.name = ${episode.name}")

        var meta = try {
            EpisodeMeta.decode(episode.url)
        } catch (e: Exception) {
            loge("getHosterList: EpisodeMeta.decode FAILED — episode.url is not a valid encoded meta", e)
            return emptyList()
        }

        if (meta.dataIds.isEmpty()) {
            logi("getHosterList: metadata is empty, fetching fresh metadata")
            meta = fetchFreshEpisodeMeta(meta.slug, meta.epNum) ?: return emptyList()
        }

        logi("getHosterList: EpisodeMeta parsed OK: slug=${meta.slug} num=${meta.epNum} mal=${meta.malId} ts=${meta.timestamp} hasSub=${meta.hasSub} hasDub=${meta.hasDub}")

        val tasks = mutableListOf<HosterTask>()

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
                    // The server list HTML is a fragment whose type wrappers may be the
                    // document root's direct children — select from the fragment root and
                    // also fall back to any bare server entries (some skins omit types).
                    val typeBlocks = pDoc.select(typeSelector)
                    if (typeBlocks.isNotEmpty()) {
                        for (element in typeBlocks) {
                            parseServerBlock(element, tasks, meta)
                        }
                    } else {
                        for (serverElement in pDoc.select(serverSelector)) {
                            var linkId = serverElement.attr("data-link-id")
                            if (linkId.isEmpty()) linkId = serverElement.attr("data-id")
                            val serverName = serverElement.text().trim()
                            if (serverName.isEmpty()) {
                                continue
                            }
                            if (linkId.isNotEmpty()) {
                                val label = "SUB - $serverName"
                                tasks.add(HosterTask(label, linkId, "sub", "primary", meta.slug))
                                logi("  + task (primary, typeless): $label")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                loge("PATH A: FAILED — continuing to mapper", e)
            }
        }

        // PATH B: Nekostream Mapper API (Kiwi-Stream)
        if (useMapper) {
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
                            if (token.serverName == "Kiwi-Stream") {
                                val serverName = token.serverName
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
        } else {
            logi("PATH B: skipped (useMapper is false for this source)")
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
        server.reResolveStream = { staleStream ->
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                reResolveStream(staleStream)
            }
        }
        server.prefetchCount = prefetchBuffer.toIntOrNull() ?: 10
        server.start()
        val proxyUrl = server.baseUrl
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

    open suspend fun fetchFreshEpisodeMeta(slug: String, epNum: String): EpisodeMeta? {
        // Suge's detail pages live under /anime/ — probe the right detail path per skin.
        val detailPaths = if (baseUrl.contains("animesuge")) {
            val cleanSlug = getCleanSlug(slug)
            listOf("$baseUrl/anime/$cleanSlug", "$baseUrl/anime/$cleanSlug/ep-1")
        } else {
            val cleanSlug = getCleanSlug(slug)
            listOf("$baseUrl/watch/$cleanSlug/ep-$epNum")
        }
        try {
            val cleanSlug = getCleanSlug(slug)
            val detailDoc = detailPaths.firstNotNullOfOrNull { url ->
                runCatching {
                    val doc = client.newCall(GET(url)).execute().asJsoup()
                    val watchMain = doc.selectFirst("#watch-page, #watch-main, .watch-wrap, .favourite[data-id], [data-id]")
                    val animeId = watchMain?.attr("data-id")
                    if (animeId.isNullOrEmpty()) null else doc
                }.getOrNull()
            } ?: return null
            val watchMain = detailDoc.selectFirst("#watch-page, #watch-main, .watch-wrap, .favourite[data-id], [data-id]")
            val animeId = watchMain?.attr("data-id") ?: return null
            if (animeId.isEmpty()) return null

            val vrf = getVrf(animeId)
            val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=$vrf&style=default"
            val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders(cleanSlug))).execute()
            val ajaxJson = json.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())
            if (ajaxJson.status != 200 || ajaxJson.result.isEmpty()) return null

            val epDoc = Jsoup.parse(ajaxJson.result)
            val elements = epDoc.select("ul.ep-range a, .ep-range a, .range a, a[data-ids]")
            for (element in elements) {
                var num = element.attr("data-num")
                if (num.isEmpty()) num = element.attr("data-slug")
                if (num == epNum) {
                    val malId = element.attr("data-mal")
                    val timestamp = element.attr("data-timestamp")
                    val dataIds = element.attr("data-ids")
                    val hasSub = element.attr("data-sub") == "1"
                    val hasDub = element.attr("data-dub") == "1"
                    var title = element.attr("title")
                    if (title.isBlank()) title = element.text().trim()
                    if (title.isBlank()) title = element.parent()?.attr("title")?.trim() ?: ""
                    if (title.isBlank()) title = "Episode $num"
                    return EpisodeMeta(cleanSlug, num, malId, timestamp, dataIds, hasSub, hasDub, title)
                }
            }
        } catch (e: Exception) {
            loge("fetchFreshEpisodeMeta FAILED", e)
        }
        return null
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList ?: emptyList()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = getHosterList(episode).flatMap { it.videoList ?: emptyList() }

    override suspend fun resolveVideo(video: Video): Video {
        activeProxyServer?.onQualitySwitch()
        return video
    }

    private suspend fun reResolveStream(staleStream: LocalProxyServer.AudioStream): LocalProxyServer.AudioStream? {
        val iframeUrl = staleStream.iframeUrl
        if (iframeUrl.isBlank()) return null
        val host = iframeUrl.substringAfter("://").substringBefore("/")
        logi("Proxy full re-resolve: ${staleStream.hosterName}/${staleStream.audioType} host=$host")
        return when {
            host.contains("vidtube.site", ignoreCase = true) ||
                host.contains("megaplay.buzz", ignoreCase = true) ||
                host.contains("vidwish.live", ignoreCase = true) -> {
                extractors.resolveVidTube(iframeUrl, staleStream.audioType, staleStream.hosterName)
            }

            host.contains("mewcdn.online", ignoreCase = true) -> {
                extractors.resolveKiwi(iframeUrl, staleStream.audioType, staleStream.hosterName)
            }

            else -> null
        }
    }

    private fun parseServerBlock(
        element: org.jsoup.nodes.Element,
        tasks: MutableList<HosterTask>,
        meta: EpisodeMeta,
    ) {
        var dataType = element.attr("data-type")
        if (dataType.isEmpty()) {
            // Fallback if structure is different
            dataType = "sub"
        }
        val audioLabel = when (dataType.lowercase(Locale.ROOT)) {
            "dub" -> "DUB"
            "sub" -> "SUB"
            "hsub" -> "HSUB"
            else -> dataType.uppercase(Locale.ROOT)
        }

        for (serverElement in element.select(serverSelector)) {
            var linkId = serverElement.attr("data-link-id")
            if (linkId.isEmpty()) linkId = serverElement.attr("data-id")
            val serverName = serverElement.text().trim()
            if (serverName.isEmpty()) continue
            if (linkId.isNotEmpty()) {
                val label = "$audioLabel - $serverName"
                tasks.add(HosterTask(label, linkId, dataType, "primary", meta.slug))
                logi("  + task (primary): $label")
            }
        }
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
                host.contains("vidtube.site", ignoreCase = true) || host.contains("megaplay.buzz", ignoreCase = true) || host.contains("vidwish.live", ignoreCase = true) -> {
                    logi("  [${task.label}] → Flow A (VidTube), host=$host")
                    extractors.resolveVidTube(url, task.audioType, hosterName)
                }

                host.contains("mewcdn.online", ignoreCase = true) -> {
                    if (preferences.getBoolean(PREF_ENABLE_KIWI_KEY, PREF_ENABLE_KIWI_DEFAULT)) {
                        logi("  [${task.label}] → Flow B (Kiwi), host=$host")
                        extractors.resolveKiwi(url, task.audioType, hosterName)
                    } else {
                        null
                    }
                }

                else -> {
                    logi("  [${task.label}] → UNKNOWN host=$host, skipping")
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
        val elements = doc.select(popularAnimeSelector)
        val animes = elements.map { el -> parseSearchItem(el) }
        val hasNext = doc.select("a.page-link[rel=next]").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    private fun parseSearchItem(el: org.jsoup.nodes.Element): SAnime {
        val linkEl = when {
            el.tagName() == "a" && el.hasClass("name") -> el
            el.selectFirst("a.name.d-title") != null -> el.selectFirst("a.name.d-title")!!
            el.selectFirst("a[href*=/watch/]") != null -> el.selectFirst("a[href*=/watch/]")!!
            else -> el
        }
        var href = linkEl.attr("href")
        if (href.startsWith("http")) href = href.substringAfter(baseUrl)
        val slug = href.removePrefix("/watch/").substringBefore("/ep-")
        val titleText = linkEl.selectFirst(".name")?.text()?.trim()
            ?: linkEl.text().trim().ifEmpty { "Unknown" }
        val thumb = el.selectFirst("img")?.absUrl("src")
            ?: linkEl.selectFirst("img")?.absUrl("src")
        return SAnime.create().apply {
            url = slug
            title = titleText
            thumbnail_url = thumb
        }
    }

    private fun parseAnimeDetails(doc: Document, slug: String): SAnime {
        // Sogo flattens the layout: #w-info directly contains .info (no .binfo wrapper).
        val binfo = doc.selectFirst("#w-info .binfo") ?: doc.selectFirst("div.binfo") ?: doc.selectFirst("#w-info .info") ?: doc.selectFirst("#w-info")
            ?: return SAnime.create().apply { url = slug }
        // Sogo's meta rows are "Label: <span>value</span>" (ownText works); anikoto uses the same.
        val bmeta = doc.selectFirst(bmetaSelector) ?: binfo.selectFirst(bmetaSelector) ?: doc.selectFirst("div.bl-meta") ?: binfo
            ?: return SAnime.create().apply { url = slug }

        // Build meta map from bmeta
        val metaMap = mutableMapOf<String, String>()
        bmeta.select("div.meta > div").forEach { el ->
            var label = el.ownText().removeSuffix(":").trim()
            // Sogo wraps labels in links sometimes ("Premiered: FALL 2022" with no ownText).
            if (label.isEmpty()) label = el.text().substringBefore(":").trim()
            val value = el.select("span").text().trim()
            if (label.isNotEmpty() && value.isNotEmpty()) metaMap[label] = value
        }

        val genresText = bmeta.select("div:contains(Genres) span a, .genre-list span a").eachText().joinToString(", ")
        val studiosText = bmeta.select("div:contains(Studios) span a").eachText().joinToString(", ")
        val statusText = metaMap["Status"] ?: ""

        val altTitles = binfo.selectFirst(aliasSelector)?.text()
        val synopsis = binfo.selectFirst(synopsisSelector)?.text()?.trim()
        val ratingText = binfo.selectFirst("i.rating")?.text() ?: ""

        val desc = buildString {
            if (!synopsis.isNullOrBlank()) append(synopsis)
            metaMap[scoreLabel]?.takeIf { it.isNotBlank() }?.let { append("\n\n$scorePrefix: $it") }
            metaMap["Type"]?.takeIf { it.isNotBlank() }?.let { append("\nType: $it") }
            metaMap["Premiered"]?.takeIf { it.isNotBlank() }?.let { append("\nPremiered: $it") }
            metaMap["Aired"]?.takeIf { it.isNotBlank() }?.let { append("\nAired: $it") }
            metaMap["Duration"]?.takeIf { it.isNotBlank() }?.let { append("\nDuration: $it") }
            if (studiosText.isNotBlank()) append("\nStudio: $studiosText")
            if (ratingText.isNotBlank()) append("\nRating: $ratingText")
            if (!altTitles.isNullOrBlank()) append("\n\nAlt titles: $altTitles")
        }

        val animeStatus = when {
            statusText.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
            statusText.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

        return SAnime.create().apply {
            url = slug
            val h1 = binfo.selectFirst("h1.title")
            title = h1?.text() ?: slug
            thumbnail_url = doc.selectFirst(detailPosterSelector)
                ?.absUrl("src")
                ?: binfo.selectFirst("div.poster img")?.absUrl("src")
            description = desc
            genre = genresText
            status = animeStatus
            author = if (studiosText.isNotBlank()) studiosText else null
            artist = author
            initialized = true
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        try {
            // ── Category 1: Playback ────────────────────────────────────
            PreferenceCategory(screen.context).apply {
                title = "Playback"
                screen.addPreference(this)

                ListPreference(context).apply {
                    key = PREF_QUALITY
                    title = "Preferred quality"
                    entries = arrayOf("1080p", "720p", "480p", "360p")
                    entryValues = arrayOf("1080", PREF_QUALITY_DEFAULT, "480", "360")
                    setDefaultValue(PREF_QUALITY_DEFAULT)
                    summary = "Currently: %s"
                }.also(::addPreference)

                ListPreference(context).apply {
                    key = PREF_AUDIO
                    title = "Preferred audio"
                    entries = arrayOf("Sub", "Dub", "Hardsub")
                    entryValues = arrayOf(PREF_AUDIO_DEFAULT, "A-DUB", "H-SUB")
                    setDefaultValue(PREF_AUDIO_DEFAULT)
                    summary = "Currently: %s"
                }.also(::addPreference)

                ListPreference(context).apply {
                    key = PREF_BUFFER
                    title = "Pre-fetch buffer"
                    entries = arrayOf("10%", "20%", "30%", "50%", "100%")
                    entryValues = arrayOf("10", "20", "30", "50", "100")
                    setDefaultValue(PREF_BUFFER_DEFAULT)
                    summary = "Currently: %s"
                }.also(::addPreference)

                ListPreference(context).apply {
                    key = PREF_SERVER
                    title = "Preferred server"
                    entries = arrayOf("Auto", "VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream")
                    entryValues = arrayOf("auto", "VidPlay-1", "HD-1", "Vidstream-2", "VidCloud-1", "Kiwi-Stream")
                    setDefaultValue(PREF_SERVER_DEFAULT)
                    summary = "Currently: %s"
                }.also(::addPreference)
            }

            // ── Category 2: Servers ─────────────────────────────────────
            if (useMapper) {
                PreferenceCategory(screen.context).apply {
                    title = "Servers"
                    screen.addPreference(this)

                    SwitchPreferenceCompat(context).apply {
                        key = PREF_ENABLE_KIWI_KEY
                        title = "Enable Kiwi-Stream"
                        summaryOn = "Fetching Kiwi-Stream from external sources"
                        summaryOff = "Kiwi-Stream disabled"
                        setDefaultValue(PREF_ENABLE_KIWI_DEFAULT)
                    }.also(::addPreference)
                }
            }

            // ── Category 3: Episode metadata ────────────────────────────
            PreferenceCategory(screen.context).apply {
                title = "Episode metadata"
                screen.addPreference(this)

                SwitchPreferenceCompat(context).apply {
                    key = PREF_LOAD_THUMBNAILS
                    title = "Load episode thumbnails"
                    summaryOn = "Fetching preview images from external sources"
                    summaryOff = "Episode thumbnails disabled (faster episode list loading)"
                    setDefaultValue(true)
                }.also(::addPreference)

                SwitchPreferenceCompat(context).apply {
                    key = PREF_LOAD_TITLES
                    title = "Load episode titles"
                    summaryOn = "Fetching episode titles from external sources"
                    summaryOff = "Using default episode numbers only"
                    setDefaultValue(true)
                }.also(::addPreference)

                SwitchPreferenceCompat(context).apply {
                    key = PREF_LOAD_DESCRIPTIONS
                    title = "Load episode descriptions"
                    summaryOn = "Fetching episode descriptions from external sources"
                    summaryOff = "Episode descriptions disabled"
                    setDefaultValue(true)
                }.also(::addPreference)
            }

            // ── Category 4: Smart Search ────────────────────────────────
            PreferenceCategory(screen.context).apply {
                title = "Smart Search"
                screen.addPreference(this)

                SwitchPreferenceCompat(context).apply {
                    key = PREF_SMART_SEARCH
                    title = "Enable smart search"
                    summaryOn = "AI resolves descriptive queries and corrects spelling"
                    summaryOff = "Smart search disabled (normal keyword search only)"
                    setDefaultValue(PREF_SMART_SEARCH_DEFAULT)
                }.also(::addPreference)

                EditTextPreference(context).apply {
                    key = PREF_SMART_SEARCH_PHRASE
                    title = "Activation phrase"
                    dialogTitle = "Activation phrase"
                    dialogMessage = "Type this at the start of your search to trigger AI.\n" +
                        "Case-insensitive. Must be followed by a space.\n" +
                        "Leave empty to use AI for all searches."
                    setDefaultValue(PREF_SMART_SEARCH_PHRASE_DEFAULT)
                    updatePhraseSummary(this, preferences.getString(PREF_SMART_SEARCH_PHRASE, PREF_SMART_SEARCH_PHRASE_DEFAULT) ?: PREF_SMART_SEARCH_PHRASE_DEFAULT)
                    onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                        updatePhraseSummary(this, newValue as? String ?: "")
                        true
                    }
                }.also(::addPreference)

                Preference(context).apply {
                    title = "Details"
                    val currentPhrase = (preferences.getString(PREF_SMART_SEARCH_PHRASE, PREF_SMART_SEARCH_PHRASE_DEFAULT) ?: PREF_SMART_SEARCH_PHRASE_DEFAULT).ifBlank { "(empty)" }
                    val phraseDisplay = if (currentPhrase == "(empty)") "(empty — AI used for all)" else "\"$currentPhrase\""
                    summary = "Type your activation phrase at the start of your search to trigger AI.\n" +
                        "Leave empty to use AI for all searches.\n\n" +
                        "Case-insensitive. Must be followed by a space.\n\n" +
                        "Your phrase: $phraseDisplay\n\n" +
                        "Examples:\n" +
                        "• ${currentPhrase.takeIf { it != "(empty)" } ?: "?"} the anime with a russian girl\n" +
                        "• ${currentPhrase.takeIf { it != "(empty)" } ?: "?"} narutp\n" +
                        "• ${currentPhrase.takeIf { it != "(empty)" } ?: "?"} anime about a spy\n\n" +
                        "Note: ~5-8s latency per AI search."
                    isSelectable = false
                }.also(::addPreference)
            }
        } catch (e: Exception) {
            loge("setupPreferenceScreen CRASHED", e)
        }
    }

    private fun updatePhraseSummary(pref: EditTextPreference, phrase: String?) {
        val displayPhrase = phrase?.trim()?.ifBlank { "(empty — AI used for all)" } ?: "(empty — AI used for all)"
        val text = "Currently: $displayPhrase"
        val spannable = SpannableString(text)
        val phraseStart = "Currently: ".length
        val phraseEnd = text.length
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#dc2626")),
            phraseStart,
            phraseEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            phraseStart,
            phraseEnd,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        pref.summary = spannable
    }

    protected fun getCleanSlug(slug: String): String {
        var s = slug
        while (true) {
            val prev = s
            s = s.removePrefix("/")
                .removePrefix("watch/")
                .removePrefix("anime/")
                .removePrefix("watch/")
                .removeSuffix("/")
            if (s == prev) break
        }
        return s
    }

    // ---- Logging ----

    protected fun logi(msg: String) = Log.i(TAG, msg)
    protected fun logw(msg: String) = Log.w(TAG, msg)
    protected fun loge(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    companion object {
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private const val PREF_AUDIO = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "SUB"
        private const val PREF_BUFFER = "pref_buffer"
        private const val PREF_BUFFER_DEFAULT = "10"
        private const val PREF_SERVER = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_LOAD_THUMBNAILS = "pref_load_thumbnails"
        private const val PREF_LOAD_TITLES = "pref_load_titles"
        private const val PREF_LOAD_DESCRIPTIONS = "pref_load_descriptions"

        private const val PREF_SMART_SEARCH = "pref_smart_search"
        private const val PREF_SMART_SEARCH_DEFAULT = false
        private const val PREF_SMART_SEARCH_PHRASE = "pref_smart_search_phrase"
        private const val PREF_SMART_SEARCH_PHRASE_DEFAULT = "?"

        private const val TAG = "Anikoto"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val PREF_ENABLE_KIWI_KEY = "pref_enable_kiwi"
        private const val PREF_ENABLE_KIWI_DEFAULT = true

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

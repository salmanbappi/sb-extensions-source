package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceScreen
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
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                if (request.header("Referer") == null) {
                    builder.header("Referer", "https://vidtube.site/")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    private val preferredQuality: String
        get() = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    private val preferredAudio: String
        get() = preferences.getString(PREF_AUDIO, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

    private val titleLang: String
        get() = preferences.getString(PREF_TITLE_LANG, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    private val prefetchBuffer: String
        get() = preferences.getString(PREF_BUFFER, PREF_BUFFER_DEFAULT) ?: PREF_BUFFER_DEFAULT

    private fun ajaxHeaders(slug: String): Headers {
        val referer = if (slug.isEmpty()) {
            "$baseUrl/"
        } else {
            "$baseUrl/watch/$slug/ep-1"
        }
        return headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Referer", referer)
            .build()
    }

    private fun vidtubePageHeaders(): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "https://vidtube.site/")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    private fun vidtubeApiHeaders(): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "https://vidtube.site/")
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Accept", "*/*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .build()

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        logi("getPopularAnime(page=$page)")
        val response = client.newCall(GET("$baseUrl/most-viewed?page=$page")).execute()
        return parseAnimeList(response.asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        logi("getLatestUpdates(page=$page)")
        val response = client.newCall(GET("$baseUrl/latest-updated?page=$page")).execute()
        return parseAnimeList(response.asJsoup())
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        logi("getSearchAnime(page=$page, query='$query')")
        val urlBuilder = "$baseUrl/filter".toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("keyword", query)
        }
        for (filter in filters) {
            when (filter) {
                is SortFilter -> {
                    val q = filter.toQuery()
                    if (q != null) {
                        urlBuilder.addQueryParameter("sort", q)
                    }
                }

                is GenreFilter -> {
                    filter.toQueries().forEach {
                        urlBuilder.addQueryParameter("genre[]", it)
                    }
                }

                is TypeFilter -> {
                    filter.toQueries().forEach {
                        urlBuilder.addQueryParameter("term_type[]", it)
                    }
                }

                is StatusFilter -> {
                    filter.toQueries().forEach {
                        urlBuilder.addQueryParameter("status[]", it)
                    }
                }

                is LanguageFilter -> {
                    filter.toQueries().forEach {
                        urlBuilder.addQueryParameter("language[]", it)
                    }
                }

                else -> {}
            }
        }
        urlBuilder.addQueryParameter("page", page.toString())
        val response = client.newCall(GET(urlBuilder.build())).execute()
        return parseAnimeList(response.asJsoup())
    }

    override fun getFilterList(): AnimeFilterList = getAnikotoFilters()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        logi("getAnimeDetails(url=${anime.url})")
        val response = client.newCall(GET("$baseUrl/watch/${anime.url}/ep-1")).execute()
        return parseAnimeDetails(response.asJsoup(), anime.url)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        logi("getEpisodeList(url=${anime.url})")
        val slug = anime.url
        val response = client.newCall(GET("$baseUrl/watch/$slug/ep-1")).execute()
        val watchDoc = response.asJsoup()
        val watchMain = watchDoc.selectFirst("#watch-main")
        if (watchMain == null) {
            loge("getEpisodeList: no #watch-main data-id found")
            return emptyList()
        }
        val animeId = watchMain.attr("data-id")
        if (animeId.isEmpty()) {
            loge("getEpisodeList: no #watch-main data-id found")
            return emptyList()
        }
        logi("animeId=$animeId")
        val vrf = AnikotoRC4.encodeVrf(animeId)
        val ajaxUrl = "$baseUrl/ajax/episode/list/$animeId?vrf=${URLEncoder.encode(vrf, "UTF-8")}&style=default"
        val ajaxResponse = client.newCall(GET(ajaxUrl, ajaxHeaders(slug))).execute()
        val ajaxJson = json.decodeFromString<EpisodeListResponse>(ajaxResponse.body.string())
        if (ajaxJson.status != 200 || ajaxJson.result.isEmpty()) {
            loge("getEpisodeList: ajax returned status=${ajaxJson.status}, resultLen=${ajaxJson.result.length}")
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
            if (title.isBlank()) {
                title = "Episode $num"
            }
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
        }
        return episodes.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        logi("=== getVideoList START ===")
        val meta = try {
            EpisodeMeta.decode(episode.url)
        } catch (e: Exception) {
            loge("EpisodeMeta.decode FAILED", e)
            return emptyList()
        }
        logi("meta: slug=${meta.slug}, ep=${meta.epNum}, mal=${meta.malId}, ts=${meta.timestamp}, dataIds=${meta.dataIds.take(30)}..., sub=${meta.hasSub}, dub=${meta.hasDub}")

        val tasks = mutableListOf<HosterTask>()

        // 1. Mapper API
        if (meta.malId.isNotEmpty() && meta.epNum.isNotEmpty() && meta.timestamp.isNotEmpty()) {
            val mapperUrl = "https://mapper.nekostream.site/api/mal/${meta.malId}/${meta.epNum}/${meta.timestamp}"
            logi("mapper API: GET $mapperUrl")
            try {
                val mapperResponse = client.newCall(GET(mapperUrl, ajaxHeaders(meta.slug))).execute()
                logi("mapper response code=${mapperResponse.code}")
                val bodyStr = mapperResponse.body.string()
                val jsonObject = json.parseToJsonElement(bodyStr) as? JsonObject
                if (jsonObject == null) {
                    throw IllegalStateException("mapper response is not a JSON object")
                }
                val mapperTokens = parseMapperResponse(jsonObject)
                logi("mapper parsed ${mapperTokens.size} tokens")
                for (mapperStreamToken in mapperTokens) {
                    val audio = mapperStreamToken.audio
                    val label = when (audio) {
                        "sub" -> "H-SUB - ${mapperStreamToken.serverName}"
                        "dub" -> "A-DUB - ${mapperStreamToken.serverName}"
                        else -> "$audio - ${mapperStreamToken.serverName}"
                    }
                    tasks.add(HosterTask(label, mapperStreamToken.token, mapperStreamToken.audio, "mapper"))
                    logi("  + task: $label")
                }
            } catch (e: Exception) {
                loge("mapper API FAILED", e)
            }
        }

        // 2. Primary API
        if (meta.dataIds.isNotEmpty()) {
            val primaryUrl = "$baseUrl/ajax/server/list?servers=${meta.dataIds}"
            logi("primary API: GET $primaryUrl")
            try {
                val primaryResponse = client.newCall(GET(primaryUrl, ajaxHeaders(meta.slug))).execute()
                logi("primary response code=${primaryResponse.code}")
                val pJson = json.decodeFromString<ServerListResponse>(primaryResponse.body.string())
                logi("primary status=${pJson.status}, resultLen=${pJson.result.length}")
                if (pJson.status == 200 && pJson.result.isNotEmpty()) {
                    val pDoc = Jsoup.parse(pJson.result)
                    val typeDivs = pDoc.select("div.servers > div.type")
                    logi("primary found ${typeDivs.size} type-groups")
                    for (element in typeDivs) {
                        val dataType = element.attr("data-type")
                        val audioLabel = when (dataType) {
                            "dub" -> "DUB"
                            "sub" -> "SUB"
                            "hsub" -> "HSUB"
                            else -> dataType.uppercase(Locale.ROOT)
                        }
                        val servers = element.select("li")
                        logi("  type=$dataType ($audioLabel): ${servers.size} servers")
                        for (serverElement in servers) {
                            val linkId = serverElement.attr("data-link-id")
                            val text = serverElement.text()
                            if (linkId.isNotEmpty()) {
                                tasks.add(HosterTask("$audioLabel - $text", linkId, dataType, "primary"))
                                logi("    + task: $audioLabel - $text")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                loge("primary API FAILED", e)
            }
        } else {
            logi("SKIP primary: dataIds is empty")
        }

        logi("Total tasks to resolve: ${tasks.size}")

        val resolvedStreams = coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    resolveStreamForTask(task.label, task.token, task.audioType, meta.slug)
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
        logi("Proxy server started at ${server.baseUrl} (prefetch=${server.prefetchCount}%)")

        val allVideos = mutableListOf<Video>()
        for (stream in resolvedStreams) {
            val subtitleTracks = server.getSubtitleTracks(stream.audioType)
            logi("Stream ${stream.audioLabel}: ${stream.variants.size} variants, ${subtitleTracks.size} subtitles")
            for (variant in stream.variants) {
                val videoUrl = "${server.baseUrl}/variant/${stream.audioType}/${variant.quality}.m3u8"
                val title = "${stream.hosterName} - ${variant.quality}"
                allVideos.add(
                    Video(
                        videoUrl = videoUrl,
                        videoTitle = title,
                        subtitleTracks = subtitleTracks,
                    ),
                )
                logi("  + Video: $title -> $videoUrl")
            }
        }

        val sortedVideos = try {
            sortVideos(allVideos)
        } catch (t: Throwable) {
            allVideos
        }

        logi("=== getVideoList END: ${sortedVideos.size} videos ===")
        if (sortedVideos.isNotEmpty()) {
            val top = sortedVideos.first()
            displayToast("BY 1118000 :)", Toast.LENGTH_SHORT)
            displayToast("Anikoto: Ready to play - ${top.videoTitle}", Toast.LENGTH_SHORT)
        }

        return sortedVideos
    }

    override suspend fun resolveVideo(video: Video): Video {
        logi("resolveVideo: switching to ${video.videoTitle}")
        displayToast("Anikoto: Switching to ${video.videoTitle}", Toast.LENGTH_SHORT)
        activeProxyServer?.onQualitySwitch()
        return video
    }

    private suspend fun resolveStreamForTask(
        label: String,
        token: String,
        audioType: String,
        slug: String,
    ): LocalProxyServer.AudioStream? {
        logi("--- resolving: $label ---")
        val encodedToken = URLEncoder.encode(token, "UTF-8")
        val ajaxUrl = "$baseUrl/ajax/server?get=$encodedToken"
        logi("  [$label] GET $ajaxUrl")
        return try {
            val response = client.newCall(GET(ajaxUrl, ajaxHeaders(slug))).execute()
            logi("  [$label] response code=${response.code}")
            val jsonResponse = json.decodeFromString<ServerResponse>(response.body.string())
            val status = jsonResponse.status
            val result = jsonResponse.result
            val url = result?.url
            val strTake = url?.take(80)
            logi("  [$label] json status=$status, url=$strTake")
            if (status == 200 && url != null && url.isNotEmpty()) {
                val iframeUrl = url
                logi("  [$label] iframe = $iframeUrl")
                if (!iframeUrl.contains("vidtube.site")) {
                    loge("  [$label] UNKNOWN iframe host: $iframeUrl")
                    return null
                }
                logi("  [$label] Path B: vidtube (VidPlay)")
                resolveVidTubeStream(iframeUrl, audioType, label)
            } else {
                loge("  [$label] resolve token FAILED: status=$status")
                null
            }
        } catch (e: Exception) {
            loge("  [$label] EXCEPTION", e)
            null
        }
    }

    private suspend fun resolveVidTubeStream(
        iframeUrl: String,
        audioType: String,
        hosterName: String,
    ): LocalProxyServer.AudioStream? {
        logi("resolveVidTubeStream: iframeUrl=$iframeUrl, type=$audioType")
        try {
            logi("VidTube Step1: GET $iframeUrl")
            val noCfClient = noCloudflareClient
            val response = noCfClient.newCall(GET(iframeUrl, vidtubePageHeaders())).execute()
            val strString3 = response.body.string()
            logi("VidTube Step1: page len=${strString3.length}")
            val dataIdRegex = Regex("""data-id="(\d+)"""")
            val match = dataIdRegex.find(strString3)
            val dataId = match?.groupValues?.get(1)
            logi("VidTube Step1: extracted data-id=$dataId")
            if (dataId.isNullOrEmpty()) {
                loge("VidTube Step1: no data-id found")
                return null
            }
            val step2Url = "https://vidtube.site/stream/getSourcesNew?id=$dataId&type=$audioType"
            logi("VidTube Step2: GET $step2Url")
            val step2Response = noCfClient.newCall(
                Request.Builder()
                    .url(step2Url)
                    .headers(vidtubeApiHeaders())
                    .build(),
            ).execute()
            logi("VidTube Step2: response code=${step2Response.code}")
            if (step2Response.isSuccessful) {
                val bodyText = step2Response.body.string()
                val apiJson = json.decodeFromString<VidTubeSourcesResponse>(bodyText)
                val masterM3u8Url = apiJson.sources?.file
                if (masterM3u8Url.isNullOrEmpty() || !masterM3u8Url.startsWith("http")) {
                    loge("VidTube Step2: no valid m3u8 returned")
                    return null
                }
                logi("VidTube Step2: master m3u8=$masterM3u8Url, ${apiJson.tracks.size} subtitle tracks")

                val validSubtitles = apiJson.tracks.filter {
                    it.file.startsWith("http") && it.label.isNotEmpty()
                }.map { track ->
                    val lang = when {
                        track.label.contains("English", ignoreCase = true) -> "eng"
                        track.label.contains("Spanish", ignoreCase = true) -> "spa"
                        track.label.contains("French", ignoreCase = true) -> "fra"
                        track.label.contains("German", ignoreCase = true) -> "deu"
                        track.label.contains("Portuguese", ignoreCase = true) -> "por"
                        track.label.contains("Japanese", ignoreCase = true) -> "jpn"
                        else -> "und"
                    }
                    LocalProxyServer.SubtitleData(track.file, track.label, lang)
                }

                if (validSubtitles.isEmpty()) {
                    logw("VidTube Step2: no valid subtitle tracks (apiJson.tracks=${apiJson.tracks.size})")
                } else {
                    logi("VidTube Step2: ${validSubtitles.size} valid subtitles: ${validSubtitles.joinToString { it.label }}")
                }

                val segHeaders = Headers.Builder()
                    .add("Referer", "https://vidtube.site/")
                    .add("User-Agent", USER_AGENT)
                    .build()

                logi("VidTube Step3: fetching master m3u8")
                val masterResponse = noCfClient.newCall(GET(masterM3u8Url, segHeaders)).execute()
                val masterText = masterResponse.body.string()
                if (!masterText.startsWith("#EXTM3U")) {
                    loge("VidTube Step3: master is not m3u8: ${masterText.take(80)}")
                    return null
                }

                val masterBase = masterM3u8Url.substringBeforeLast("/") + "/"
                val variantInfos = mutableListOf<VariantInfo>()
                val masterLines = masterText.lines()
                var i = 0
                while (i < masterLines.size) {
                    val line = masterLines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        val next = masterLines.getOrNull(i + 1) ?: ""
                        if (next.isNotEmpty() && !next.startsWith("#")) {
                            val bandwidthMatch = Regex("""BANDWIDTH=(\d+)""").find(line)
                            val bandwidth = bandwidthMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val nameMatch = Regex("""NAME="([^"]+)"""").find(line)
                            val name = nameMatch?.groupValues?.get(1) ?: "Unknown"
                            val resMatch = Regex("""(\d{3,4})""").find(name)
                            val resolution = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val fullUrl = if (next.startsWith("http")) next else masterBase + next.trim()
                            variantInfos.add(VariantInfo(fullUrl, bandwidth, name, resolution))
                            i += 2
                        } else {
                            i++
                        }
                    } else {
                        i++
                    }
                }

                if (variantInfos.isEmpty()) {
                    loge("VidTube Step3: no variants in master m3u8")
                    return null
                }
                logi("VidTube Step3: ${variantInfos.size} variants: ${variantInfos.joinToString { "${it.quality}(${it.bandwidth})" }}")

                val variantDataList = mutableListOf<LocalProxyServer.VariantData>()
                for (vi in variantInfos) {
                    logi("VidTube Step3: fetching variant ${vi.quality} -> ${vi.url}")
                    try {
                        val varResponse = noCfClient.newCall(GET(vi.url, segHeaders)).execute()
                        val variantSegments = parseVariantSegments(varResponse.body.string(), vi.url)
                        if (variantSegments.isNotEmpty()) {
                            variantDataList.add(LocalProxyServer.VariantData(vi.quality, vi.bandwidth, vi.resolution, variantSegments))
                            val duration = variantSegments.sumOf { it.duration }
                            logi("VidTube Step3: variant ${vi.quality}: ${variantSegments.size} segments, ${String.format(Locale.ROOT, "%.1f", duration)}s")
                        } else {
                            logw("VidTube Step3: variant ${vi.quality} has no segments, skipping")
                        }
                    } catch (e: Exception) {
                        loge("VidTube Step3: variant ${vi.quality} fetch FAILED: ${e.message}")
                    }
                }

                if (variantDataList.isEmpty()) {
                    loge("VidTube Step3: no variants could be loaded")
                    return null
                }

                val upperCase = when (audioType) {
                    "sub" -> "SUB"
                    "dub" -> "DUB"
                    "hsub" -> "HSUB"
                    else -> audioType.uppercase(Locale.ROOT)
                }

                logi("VidTube Step3: resolved stream: $hosterName - ${variantDataList.size} variants, ${validSubtitles.size} subs")
                return LocalProxyServer.AudioStream(audioType, upperCase, hosterName, variantDataList, validSubtitles)
            } else {
                val responseBody = step2Response.body
                val errorMsg = responseBody?.string()?.take(200) ?: "no body"
                loge("VidTube Step2: HTTP ${step2Response.code} - $errorMsg")
                step2Response.close()
                return null
            }
        } catch (e: Exception) {
            loge("VidTube Step1/2 FAILED", e)
            return null
        }
    }

    private fun parseVariantSegments(varText: String, variantUrl: String): List<LocalProxyServer.SegmentInfo> {
        val segments = mutableListOf<LocalProxyServer.SegmentInfo>()
        val varBase = variantUrl.substringBeforeLast("/") + "/"
        val lines = varText.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF:")) {
                val durationStr = line.substringAfter("#EXTINF:").substringBefore(",")
                val duration = durationStr.toDoubleOrNull() ?: 0.0
                val next = lines.getOrNull(i + 1) ?: ""
                if (next.isNotEmpty() && !next.startsWith("#")) {
                    val fullUrl = if (next.startsWith("http")) next else varBase + next.trim()
                    segments.add(LocalProxyServer.SegmentInfo(fullUrl, duration))
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return segments
    }

    private fun sortVideos(list: List<Video>): List<Video> {
        val prefQuality = preferredQuality
        val preferredAudio = preferredAudio
        val prefAudioLabel = when (preferredAudio) {
            "A-DUB" -> "DUB"
            "H-SUB" -> "HSUB"
            else -> PREF_AUDIO_DEFAULT
        }
        return list.sortedWith(
            compareByDescending<Video> { it.videoTitle.startsWith(prefAudioLabel, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) },
        )
    }

    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun logw(msg: String) = Log.w(TAG, msg)
    private fun loge(msg: String, e: Throwable? = null) {
        if (e != null) {
            Log.e(TAG, msg, e)
        } else {
            Log.e(TAG, msg)
        }
    }

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
                if (titleText.isEmpty()) {
                    titleText = nameLink.attr("data-jp")
                }
                title = titleText

                val posterImg = element.selectFirst("div.ani.poster.tip img")
                thumbnail_url = posterImg?.absUrl("src") ?: element.selectFirst("img")?.absUrl("src")
            }
        }

        val hasNext = if (doc.selectFirst("ul.pagination li:has(a[rel=next])") != null) {
            true
        } else {
            try {
                val activeElement = doc.selectFirst("ul.pagination li.active")
                val activePage = activeElement?.text()?.toIntOrNull() ?: 0
                val paginationLinks = doc.select("ul.pagination li.page-item a.page-link")
                val pages = paginationLinks.mapNotNull { it.text().toIntOrNull() }
                pages.any { it > activePage }
            } catch (e: Exception) {
                false
            }
        }

        return AnimesPage(animes, hasNext)
    }

    private fun parseAnimeDetails(doc: Document, slug: String): SAnime {
        val useJp = titleLang == "jp"
        val anime = SAnime.create().apply {
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
        return anime
    }

    private fun buildDescription(doc: Document): String {
        val contentElement = doc.selectFirst("#w-info .synopsis .content")
        val synopsis = contentElement?.text() ?: doc.selectFirst("#w-info .synopsis")?.text() ?: ""
        val bmeta = doc.selectFirst("#w-info .bmeta") ?: return synopsis

        val metaList = mutableListOf<String>()
        val metaElements = bmeta.select(".meta > div")
        for (element in metaElements) {
            val labelElement = element.selectFirst("label, strong, b")
            val label = labelElement?.text()?.removeSuffix(":")
            val valueElements = element.select("span, a")
            val value = valueElements.joinToString(", ") { it.text() }
            if (label != null && value.isNotEmpty()) {
                metaList.add("$label: $value")
            }
        }

        return if (metaList.isEmpty()) {
            synopsis
        } else {
            metaList.joinToString("\n") + "\n\n" + synopsis
        }
    }

    private fun parseStatus(text: String?): Int {
        val lowerCase = text?.lowercase(Locale.ROOT) ?: return SAnime.UNKNOWN
        return when (lowerCase) {
            "finished airing" -> SAnime.COMPLETED
            "ongoing", "currently airing", "not yet aired", "upcoming" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        try {
            logi("setupPreferenceScreen: adding preferences")
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
            logi("setupPreferenceScreen: done")
        } catch (e: Exception) {
            loge("setupPreferenceScreen CRASHED", e)
        }
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

        private const val TAG = "Anikoto"
        private const val USER_AGENT = "Mozilla/5.0"

        @Volatile
        private var activeProxyServer: LocalProxyServer? = null

        @Synchronized
        private fun swapProxyServer(newServer: LocalProxyServer): LocalProxyServer {
            activeProxyServer?.let {
                runCatching { it.stop() }
            }
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

data class HosterTask(
    val label: String,
    val token: String,
    val audioType: String,
    val source: String,
)

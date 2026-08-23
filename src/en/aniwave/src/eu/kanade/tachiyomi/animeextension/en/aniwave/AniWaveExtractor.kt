package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.util.Base64
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

private data class ExtractionResult(
    val videos: List<Video>,
    val requiresProxy: Boolean,
)

class AniWaveExtractor(private val source: AniWave) {

    suspend fun extractVideos(document: Document, episode: SEpisode, epUrl: String): List<Video> {
        val serverData = source.parseServerListData(document).toMutableList()
        val mapperServers = fetchMapperServers(episode)
        serverData.addAll(mapperServers)

        return serverData.parallelCatchingFlatMap { server ->
            extractVideo(server, epUrl, includeServerInTitle = true)
        }
    }

    private suspend fun getEmbedLink(serverId: String, epUrl: String): String {
        val listHeaders = source.headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Referer", source.getFullUrl(epUrl))
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return source.client.newCall(GET("${source.baseUrl}/ajax/server?get=$serverId", listHeaders))
            .awaitSuccess().use { response ->
                if (!response.isSuccessful) throw Exception("Server API returned HTTP ${response.code}")
                response.parseAs<ServerResponseDto>().result?.url ?: ""
            }
    }

    internal suspend fun fetchMapperServers(episode: SEpisode): List<AniWave.VideoData> {
        val epUrlStr = episode.url
        val malId = epUrlStr.substringAfter("&mal=", "").substringBefore("&")
            .takeIf { it.isNotEmpty() } ?: return emptyList()
        val slug = epUrlStr.substringAfter("&slug=", "").substringBefore("&")
            .takeIf { it.isNotEmpty() } ?: return emptyList()
        val ts = epUrlStr.substringAfter("&ts=", "").substringBefore("&")
            .takeIf { it.isNotEmpty() } ?: return emptyList()

        val apiUrl = "${source.mapperUrl}/mal/$malId/$slug/$ts"

        return try {
            val mapperHeaders = source.headers.newBuilder().apply {
                add("Accept", "application/json, text/javascript, */*; q=0.01")
                add("Referer", "${source.baseUrl}/")
                add("Origin", source.baseUrl)
            }.build()

            source.client.newCall(GET(apiUrl, mapperHeaders)).awaitSuccess().use { apiResponse ->
                val mapperJson = apiResponse.parseAs<Map<String, MapperServerDto?>>()

                mapperJson.keys
                    .filter { !it.equals("status", true) }
                    .map { source.mapMapperServerName(it) }
                    .also { source.updateDiscoveredServers(it, isMapper = true) }

                source.updateDiscoveredTypes(listOf("H-Sub", "A-Dub"))

                val servers = mutableListOf<AniWave.VideoData>()

                for ((key, serverDto) in mapperJson) {
                    if (key.equals("status", true)) continue
                    val serverName = source.mapMapperServerName(key)

                    listOf("sub" to "H-Sub", "dub" to "A-Dub").forEach { (typeKey, typeLabel) ->
                        val linkDto = when (typeKey) {
                            "sub" -> serverDto?.sub
                            "dub" -> serverDto?.dub
                            else -> null
                        } ?: return@forEach

                        val linkId = linkDto.url
                        if (linkId.isEmpty()) return@forEach

                        if (!source.hostToggle.contains(serverName)) return@forEach
                        if (!source.isTypeEnabled(typeLabel, source.typeToggle)) return@forEach

                        servers.add(AniWave.VideoData(typeLabel, linkId, serverName))
                    }
                }

                servers
            }
        } catch (e: Exception) {
            Log.e("AniWaveExtractor", "Mapper API failed: ${e.message}")
            emptyList()
        }
    }

    internal suspend fun extractVideo(
        server: AniWave.VideoData,
        epUrl: String,
        includeServerInTitle: Boolean = true,
    ): List<Video> = try {
        val embedLink = if (server.serverId.startsWith("http")) {
            server.serverId
        } else {
            getEmbedLink(server.serverId, epUrl)
        }

        val result = when {
            embedLink.contains("mewcdn.online/player/plyr.php") ->
                extractFromMewcdnPlayer(embedLink, server, includeServerInTitle)

            embedLink.endsWith(".m3u8") || (embedLink.contains(".m3u8") && !embedLink.contains("/stream/")) ->
                extractDirectM3u8(embedLink, server, includeServerInTitle = includeServerInTitle)

            else ->
                extractFromPlayer(embedLink, server, includeServerInTitle = includeServerInTitle)
        }

        val needsProxy = result.requiresProxy || source.alwaysNeedsProxy(server.serverName)

        if (needsProxy) proxyVideoList(result.videos) else result.videos
    } catch (e: Exception) {
        Log.e("AniWaveExtractor", "Failed to extract from ${server.serverName}: ${e.message}")
        emptyList()
    }

    private suspend fun extractFromPlayer(
        embedUrl: String,
        server: AniWave.VideoData,
        pageReferer: String = "${source.baseUrl}/",
        includeServerInTitle: Boolean = true,
    ): ExtractionResult {
        val host = try {
            embedUrl.toHttpUrl().host
        } catch (_: Exception) {
            return ExtractionResult(emptyList(), false)
        }

        val pageHeaders = source.headers.newBuilder()
            .add("Referer", pageReferer)
            .build()

        val pageBody = source.client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("Player page failed: HTTP ${it.code}")
            it.body.string()
        }

        val dataId = DATA_ID_REGEX.find(pageBody)?.groupValues?.get(1)
        if (dataId != null) {
            return fetchSourcesFromApi(dataId, host, embedUrl, server, includeServerInTitle)
        }

        val iframeSrc = IFRAME_SRC_REGEX.find(pageBody)?.groupValues?.get(1)
        if (iframeSrc != null) {
            val resolvedSrc = resolveUrl(iframeSrc, embedUrl)
            return extractFromPlayer(resolvedSrc, server, pageReferer = embedUrl, includeServerInTitle = includeServerInTitle)
        }

        val directM3u8 = M3U8_REGEX.find(pageBody)?.groupValues?.get(0)
        if (directM3u8 != null) {
            return extractDirectM3u8(directM3u8, server, "https://$host/", includeServerInTitle)
        }

        val sourceSrc = SOURCE_TAG_REGEX.find(pageBody)?.groupValues?.get(1)
        if (sourceSrc != null) {
            val resolvedSrc = resolveUrl(sourceSrc, embedUrl)
            return extractDirectM3u8(resolvedSrc, server, "https://$host/", includeServerInTitle)
        }

        val jsVarUrl = JS_VAR_M3U8_REGEX.find(pageBody)?.let { match ->
            match.groupValues.getOrNull(1)?.takeIf(String::isNotEmpty)
                ?: match.groupValues.getOrNull(2)?.takeIf(String::isNotEmpty)
        }
        if (jsVarUrl != null) {
            val resolvedUrl = resolveUrl(jsVarUrl, embedUrl)
            if (resolvedUrl.contains(".m3u8") || resolvedUrl.contains("/stream/")) {
                return try {
                    fetchSourcesFromPage(resolvedUrl, server, "https://$host/", includeServerInTitle)
                } catch (_: Exception) {
                    extractDirectM3u8(resolvedUrl, server, "https://$host/", includeServerInTitle)
                }
            }
        }

        Log.e("AniWaveExtractor", "No extraction strategy matched for ${server.serverName} at $embedUrl")
        return ExtractionResult(emptyList(), false)
    }

    private suspend fun fetchSourcesFromApi(
        dataId: String,
        host: String,
        embedUrl: String,
        server: AniWave.VideoData,
        includeServerInTitle: Boolean = true,
    ): ExtractionResult {
        val streamType = try {
            embedUrl.toHttpUrl().pathSegments.lastOrNull()
                ?.takeIf { it == "sub" || it == "dub" }
        } catch (_: Exception) {
            null
        } ?: ""

        val apiHeaders = source.headers.newBuilder().apply {
            add("Accept", "*/*")
            add("X-Requested-With", "XMLHttpRequest")
            add("Referer", embedUrl)
            add("Origin", "https://$host")
        }.build()

        val (data, usedGetSourcesNew) = fetchSourceData(dataId, host, apiHeaders, streamType)

        val m3u8 = data.sources.takeIf { it.startsWith("http") }
            ?: throw Exception("No valid m3u8 found")

        val subtitles = data.tracks
            ?.filter { it.kind == "captions" }
            ?.map { Track(it.file, it.label) }
            .orEmpty()

        val titlePrefix = if (includeServerInTitle) {
            val displayName = source.getServerDisplayName(server.serverName)
            val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""
            "$displayName$typeSuffix"
        } else {
            server.type.takeIf { it.isNotEmpty() } ?: "Video"
        }

        val vidHeaders = source.headers.newBuilder()
            .set("Referer", "https://$host/")
            .set("Origin", "https://$host")
            .build()

        val videos = source.playlistUtils.extractFromHls(
            playlistUrl = m3u8,
            videoNameGen = { quality ->
                "$titlePrefix - ${source.cleanHlsQuality(quality)}"
            },
            subtitleList = subtitles,
            referer = "https://$host/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, usedGetSourcesNew)
    }

    private suspend fun fetchSourceData(
        dataId: String,
        host: String,
        apiHeaders: Headers,
        streamType: String,
    ): Pair<SourceResponseDto, Boolean> {
        val primaryResult = try {
            val data = source.client.newCall(GET("https://$host/stream/getSources?id=$dataId&id=$dataId", apiHeaders))
                .awaitSuccess().use { response ->
                    if (!response.isSuccessful) throw Exception("getSources failed: HTTP ${response.code}")
                    response.parseAs<SourceResponseDto>()
                }
            data to false
        } catch (_: Exception) {
            null
        }

        if (primaryResult != null) return primaryResult

        val newUrl = if (streamType.isNotEmpty()) {
            "https://$host/stream/getSourcesNew?id=$dataId&id=$dataId&type=$streamType&type=$streamType"
        } else {
            "https://$host/stream/getSourcesNew?id=$dataId&id=$dataId"
        }

        val data = source.client.newCall(GET(newUrl, apiHeaders))
            .awaitSuccess().use { response ->
                if (!response.isSuccessful) throw Exception("getSourcesNew failed: HTTP ${response.code}")
                response.parseAs<SourceResponseDto>()
            }

        return data to true
    }

    private suspend fun fetchSourcesFromPage(
        url: String,
        server: AniWave.VideoData,
        referer: String,
        includeServerInTitle: Boolean = true,
    ): ExtractionResult {
        val pageHeaders = source.headers.newBuilder()
            .add("Referer", referer)
            .build()

        val body = source.client.newCall(GET(url, pageHeaders)).awaitSuccess().use {
            if (!it.isSuccessful) throw Exception("Page fetch failed: HTTP ${it.code}")
            it.body.string()
        }

        if (body.trimStart().startsWith("#EXTM3U")) {
            return extractDirectM3u8(url, server, referer, includeServerInTitle)
        }

        val m3u8 = M3U8_REGEX.find(body)?.groupValues?.get(0)
            ?: throw Exception("No m3u8 found in page")

        return extractDirectM3u8(m3u8, server, referer, includeServerInTitle)
    }

    private suspend fun extractDirectM3u8(
        m3u8Url: String,
        server: AniWave.VideoData,
        referer: String = "${source.baseUrl}/",
        includeServerInTitle: Boolean = true,
    ): ExtractionResult {
        val titlePrefix = if (includeServerInTitle) {
            val displayName = source.getServerDisplayName(server.serverName)
            val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""
            "$displayName$typeSuffix"
        } else {
            server.type.takeIf { it.isNotEmpty() } ?: "Video"
        }

        val vidHeaders = source.headers.newBuilder()
            .set("Referer", referer)
            .build()

        val videos = source.playlistUtils.extractFromHls(
            playlistUrl = m3u8Url,
            videoNameGen = { quality ->
                "$titlePrefix - ${source.cleanHlsQuality(quality)}"
            },
            referer = referer,
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, false)
    }

    private suspend fun extractFromMewcdnPlayer(
        embedUrl: String,
        server: AniWave.VideoData,
        includeServerInTitle: Boolean = true,
    ): ExtractionResult {
        val fragment = embedUrl.substringAfter("#").substringBefore("#").takeIf { it.isNotEmpty() }
            ?: throw Exception("No fragment found in mewcdn player URL")

        val rawM3u8 = String(Base64.decode(fragment, Base64.DEFAULT), Charsets.UTF_8).trim()
        if (!rawM3u8.startsWith("http")) {
            throw Exception("Invalid m3u8 URL decoded from mewcdn fragment")
        }

        val pageHeaders = source.headers.newBuilder()
            .add("Referer", "${source.baseUrl}/")
            .build()

        val hostMap = source.client.newCall(GET(embedUrl, pageHeaders)).awaitSuccess().use { response ->
            parseHostMap(response.body.string())
        }

        val m3u8 = applyHostMap(rawM3u8, hostMap)

        val titlePrefix = if (includeServerInTitle) {
            val displayName = source.getServerDisplayName(server.serverName)
            val typeSuffix = server.type.takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""
            "$displayName$typeSuffix"
        } else {
            server.type.takeIf { it.isNotEmpty() } ?: "Video"
        }

        val vidHeaders = source.headers.newBuilder()
            .set("Referer", "https://mewcdn.online/")
            .set("Origin", "https://mewcdn.online")
            .build()

        val videos = source.playlistUtils.extractFromHls(
            playlistUrl = m3u8,
            videoNameGen = { quality ->
                "$titlePrefix - ${source.cleanHlsQuality(quality)}"
            },
            referer = "https://mewcdn.online/",
            masterHeaders = vidHeaders,
            videoHeaders = vidHeaders,
        )

        return ExtractionResult(videos, true)
    }

    private suspend fun proxyVideoList(videos: List<Video>): List<Video> {
        if (!source.m3u8ServerManager.isRunning()) {
            Log.e("AniWaveExtractor", "M3U8 server not running, dropping ${videos.size} videos")
            return emptyList()
        }
        return videos.mapNotNull { video ->
            val processedUrl = proxyThroughM3u8Server(video.videoUrl)
            if (processedUrl == null) {
                Log.w("AniWaveExtractor", "Proxy failed for: ${video.videoTitle}")
            }
            processedUrl?.let {
                Video(
                    videoUrl = it,
                    videoTitle = video.videoTitle,
                    headers = video.headers,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                )
            }
        }
    }

    private fun proxyThroughM3u8Server(originalUrl: String): String? = try {
        source.m3u8ServerManager.processM3u8Url(originalUrl)
    } catch (e: Exception) {
        Log.e("AniWaveExtractor", "Proxy process failed: ${e.message}")
        null
    }

    private fun parseHostMap(html: String): Map<String, String> {
        val mapMatch = HOST_MAP_REGEX.find(html) ?: return emptyMap()
        return HOST_ENTRY_REGEX.findAll(mapMatch.groupValues[1]).associate {
            it.groupValues[1] to it.groupValues[2]
        }
    }

    private fun applyHostMap(url: String, hostMap: Map<String, String>): String {
        var result = url
        for ((origin, proxy) in hostMap) {
            if (result.contains(origin)) {
                result = result.replace(origin, proxy)
                break
            }
        }
        return result
    }

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http")) return url
        val baseUrl = try {
            base.toHttpUrl()
        } catch (_: Exception) {
            return url
        }
        return baseUrl.resolve(url)?.toString() ?: url
    }

    companion object {
        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")
        private val IFRAME_SRC_REGEX = Regex("""<iframe[^>]+src="([^"]+)"""")
        private val M3U8_REGEX = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
        private val SOURCE_TAG_REGEX = Regex("""<source[^>]+src="([^"]+\.m3u8[^"]*)"""")
        private val JS_VAR_M3U8_REGEX = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""" +
                """|(?:file|source|url|src)\s*[:=]\s*["']([^"']*(?:\.m3u8|/stream/)[^"']*)["']""",
        )
        private val HOST_MAP_REGEX = Regex("""var HOST_MAP\s*=\s*\{([^}]+)\}""")
        private val HOST_ENTRY_REGEX = Regex("""'([^']+)'\s*:\s*'([^']+)'""")
    }
}

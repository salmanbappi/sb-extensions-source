package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import eu.kanade.tachiyomi.animeextension.all.animex.ANIMEX_API_UA
import eu.kanade.tachiyomi.animeextension.all.animex.AnimexProxy
import eu.kanade.tachiyomi.animeextension.all.animex.SourcesResponse
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

interface HostResolver {
    val id: String
    val displayName: String

    suspend fun getVideos(
        slug: String,
        epNum: String,
        type: String,
        subStyle: String,
        client: OkHttpClient,
        baseHeaders: Headers,
        proxy: AnimexProxy,
        json: Json,
    ): List<Video>
}

abstract class BaseAnmxResolver : HostResolver {

    protected open fun modifyVideoHeaders(
        headersBuilder: Headers.Builder,
        providerId: String,
        sourcesHeaders: Map<String, String>?,
    ) {
        headersBuilder.apply {
            removeAll("Origin")
            removeAll("Accept")
            sourcesHeaders?.forEach { (k, v) -> set(k, v) }
        }
    }

    override suspend fun getVideos(
        slug: String,
        epNum: String,
        type: String,
        subStyle: String,
        client: OkHttpClient,
        baseHeaders: Headers,
        proxy: AnimexProxy,
        json: Json,
    ): List<Video> {
        val sourcesRequest = GET("https://pp.animex.one/rest/api/sources?id=$slug&epNum=$epNum&type=$type&providerId=$id", baseHeaders)
        val sourcesData = try {
            fetchWithApiRetry(client, sourcesRequest).use { response ->
                if (!response.isSuccessful) return emptyList()
                json.decodeFromString<SourcesResponse>(response.body.string())
            }
        } catch (_: Exception) {
            return emptyList()
        }

        val subtitleTracks = sourcesData.tracks?.mapNotNull { track ->
            val trackUrl = track.url ?: return@mapNotNull null
            Track(absoluteUrl(trackUrl), track.label ?: track.lang ?: "English")
        } ?: emptyList()

        val providerVideos = mutableListOf<Video>()
        val providerName = displayName.uppercase()
        val categoryLabel = type.uppercase()

        for (source in sourcesData.sources) {
            val sourceUrl = source.url ?: continue
            val streamUrl = absoluteUrl(sourceUrl)
            val quality = source.quality ?: "Auto"

            val videoHeadersBuilder = baseHeaders.newBuilder()
            modifyVideoHeaders(videoHeadersBuilder, id, sourcesData.headers)
            val videoHeaders = videoHeadersBuilder.build()

            val isHls = streamUrl.contains(".m3u8", ignoreCase = true) ||
                streamUrl.contains(".txt", ignoreCase = true) ||
                source.type?.contains("mpegurl", ignoreCase = true) == true

            if (isHls) {
                try {
                    val playlistUtils = PlaylistUtils(client, baseHeaders)
                    val playlistVideos = playlistUtils.extractFromHls(
                        playlistUrl = streamUrl,
                        masterHeaders = videoHeaders,
                        videoHeaders = videoHeaders,
                        videoNameGen = { hlsQuality ->
                            val parsedQuality = if (hlsQuality == "Video") quality else hlsQuality
                            "$providerName: $parsedQuality ($categoryLabel)$subStyle"
                        },
                        subtitleList = subtitleTracks,
                    ).map { video ->
                        val cleanUrl = if (video.videoUrl.startsWith("//")) "https:${video.videoUrl}" else video.videoUrl
                        video.copy(videoUrl = cleanUrl)
                    }
                    providerVideos.addAll(playlistVideos)
                } catch (_: Exception) {
                    val qualityLabel = "$providerName: $quality ($categoryLabel)$subStyle"
                    providerVideos.add(
                        Video(
                            videoUrl = streamUrl,
                            videoTitle = qualityLabel,
                            headers = videoHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            } else if (streamUrl.contains(".mpd", ignoreCase = true)) {
                try {
                    val dashVideos = extractDash(
                        client = client,
                        mpdUrl = streamUrl,
                        headers = videoHeaders,
                        quality = quality,
                        providerName = providerName,
                        categoryLabel = categoryLabel,
                        subStyle = subStyle,
                        subtitleTracks = subtitleTracks,
                    )
                    if (dashVideos.isNotEmpty()) {
                        providerVideos.addAll(dashVideos)
                    } else {
                        throw Exception("No DASH streams")
                    }
                } catch (_: Exception) {
                    val qualityLabel = "$providerName: $quality ($categoryLabel)$subStyle"
                    providerVideos.add(
                        Video(
                            videoUrl = streamUrl,
                            videoTitle = qualityLabel,
                            headers = videoHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            } else {
                val qualityLabel = "$providerName: $quality ($categoryLabel)$subStyle"
                providerVideos.add(
                    Video(
                        videoUrl = streamUrl,
                        videoTitle = qualityLabel,
                        headers = videoHeaders,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        }

        return providerVideos.map { video ->
            val proxiedSubtitles = video.subtitleTracks.map { track ->
                if (track.url.startsWith("http://127.0.0.1")) {
                    track
                } else {
                    Track(proxy.getSubtitleProxyUrl(track.url, video.headers), track.lang)
                }
            }

            val isDirectMedia = video.videoUrl.contains(".mp4", ignoreCase = true) ||
                video.videoUrl.contains(".mkv", ignoreCase = true) ||
                video.videoUrl.contains(".webm", ignoreCase = true) ||
                video.videoUrl.contains(".mpd", ignoreCase = true)

            if (isDirectMedia) {
                video.copy(subtitleTracks = proxiedSubtitles)
            } else {
                Video(
                    videoUrl = proxy.getProxyUrl(video.videoUrl, video.headers),
                    videoTitle = video.videoTitle,
                    subtitleTracks = proxiedSubtitles,
                    audioTracks = video.audioTracks,
                    headers = video.headers,
                )
            }
        }
    }

    protected suspend fun fetchWithApiRetry(client: OkHttpClient, request: Request, attempts: Int = 2): Response {
        var response = client.newCall(request).execute()
        var attempt = 1
        while (attempt < attempts && response.code in 500..599) {
            response.close()
            delay(600)
            response = client.newCall(request).execute()
            attempt++
        }
        return response
    }

    protected fun absoluteUrl(url: String, base: String = "https://animex.one"): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> try {
            val baseUri = java.net.URI(base)
            baseUri.resolve(url).toString()
        } catch (_: Exception) {
            base.substringBeforeLast("/") + "/" + url
        }
    }

    private fun extractDash(
        client: OkHttpClient,
        mpdUrl: String,
        headers: Headers,
        quality: String,
        providerName: String,
        categoryLabel: String,
        subStyle: String,
        subtitleTracks: List<Track>,
    ): List<Video> {
        val request = GET(mpdUrl, headers)
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val doc = response.asJsoup()
        val adaptationSets = doc.select("AdaptationSet, adaptationset")
        val videoRepresentations = mutableListOf<org.jsoup.nodes.Element>()
        val audioTracks = mutableListOf<Track>()

        adaptationSets.forEach { adaptationSet ->
            val mimeType = adaptationSet.attr("mimeType").ifEmpty { adaptationSet.attr("mimetype") }
            val isVideo = mimeType.contains("video", ignoreCase = true)
            val isAudio = mimeType.contains("audio", ignoreCase = true)

            adaptationSet.select("Representation, representation").forEach { representation ->
                val repMimeType = representation.attr("mimeType").ifEmpty { representation.attr("mimetype") }
                val repIsVideo = isVideo || repMimeType.contains("video", ignoreCase = true)
                val repIsAudio = isAudio || repMimeType.contains("audio", ignoreCase = true)

                if (repIsVideo) {
                    videoRepresentations.add(representation)
                } else if (repIsAudio) {
                    val bandwidth = representation.attr("bandwidth").toLongOrNull()
                    val formatBytes = { bytes: Long? ->
                        when {
                            bytes == null -> ""
                            bytes >= 1_000_000_000 -> "%.2f GB/s".format(bytes / 1_000_000_000.0)
                            bytes >= 1_000_000 -> "%.2f MB/s".format(bytes / 1_000_000.0)
                            bytes >= 1_000 -> "%.2f KB/s".format(bytes / 1_000.0)
                            else -> "$bytes bytes/s"
                        }
                    }
                    var baseUrlTag = representation.selectFirst("BaseURL, baseurl")
                        ?: representation.parent()?.selectFirst("BaseURL, baseurl")
                        ?: representation.parent()?.parent()?.selectFirst("BaseURL, baseurl")
                    val relativeUrl = baseUrlTag?.text()?.trim() ?: representation.text().trim()
                    if (relativeUrl.isNotEmpty()) {
                        audioTracks.add(Track(absoluteUrl(relativeUrl, mpdUrl), formatBytes(bandwidth)))
                    }
                }
            }
        }

        if (videoRepresentations.isEmpty()) {
            doc.select("Representation, representation").forEach { representation ->
                val repMimeType = representation.attr("mimeType").ifEmpty { representation.attr("mimetype") }
                if (repMimeType.contains("video", ignoreCase = true)) {
                    videoRepresentations.add(representation)
                }
            }
        }

        return videoRepresentations.mapNotNull { videoSrc ->
            val bandwidth = videoSrc.attr("bandwidth")
            val height = videoSrc.attr("height").ifEmpty { videoSrc.parent()?.attr("height") ?: "Video" }
            val width = videoSrc.attr("width").ifEmpty { videoSrc.parent()?.attr("width") ?: "" }
            val res = if (width.isNotEmpty() && height.isNotEmpty()) "$height (${width}x$height)" else height

            var baseUrlTag = videoSrc.selectFirst("BaseURL, baseurl")
                ?: videoSrc.parent()?.selectFirst("BaseURL, baseurl")
                ?: videoSrc.parent()?.parent()?.selectFirst("BaseURL, baseurl")
            val relativeUrl = baseUrlTag?.text()?.trim() ?: videoSrc.text().trim()
            if (relativeUrl.isEmpty()) return@mapNotNull null

            val videoUrl = absoluteUrl(relativeUrl, mpdUrl)
            if (videoUrl == mpdUrl) return@mapNotNull null

            val videoTitle = "$providerName: $res ($categoryLabel)$subStyle" + if (bandwidth.isNotEmpty()) " - $bandwidth" else ""
            Video(
                videoUrl = videoUrl,
                videoTitle = videoTitle,
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                headers = headers,
            )
        }
    }
}

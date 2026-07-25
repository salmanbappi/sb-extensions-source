package eu.kanade.tachiyomi.animeextension.en.fouranimo

import eu.kanade.tachiyomi.animesource.model.Video
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLEncoder

object FourAnimoHlsServer : NanoHTTPD(0) {

    val port: Int
        get() = super.getListeningPort()

    @Volatile
    private var isRunning = false

    @Volatile
    private var client: OkHttpClient? = null

    override fun start() {
        super.start()
        isRunning = true
    }

    override fun stop() {
        super.stop()
        isRunning = false
    }

    fun processVideoList(client: OkHttpClient, videos: List<Video>): List<Video> {
        this.client = client
        ensureStarted()
        return videos.map { video ->
            if (video.videoUrl.contains(".m3u8", ignoreCase = true) || video.videoUrl.contains("/p?t=", ignoreCase = true)) {
                video.copyWithLocalUrl(createLocalM3u8Url(video.videoUrl, video.headers))
            } else {
                video
            }
        }
    }

    @Synchronized
    private fun ensureStarted() {
        if (!isRunning) {
            start()
            isRunning = true
        }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri.startsWith("/m3u8") -> handleM3u8Request(session)
        session.uri.startsWith("/segment") -> handleSegmentRequest(session)
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleM3u8Request(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")

        val referer = session.parameters["referer"]?.firstOrNull() ?: "https://4animo.xyz/"

        return try {
            val headers = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                .add("Referer", referer)
                .build()

            val playlist = fetchString(url, headers)
            val content = rewritePlaylist(playlist, url, referer)
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", content)
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleSegmentRequest(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")

        val referer = session.parameters["referer"]?.firstOrNull() ?: "https://4animo.xyz/"

        return try {
            val headers = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
                .add("Referer", referer)
                .build()

            val rawData = fetchBytes(url, headers)
            val cleanData = stripImageHeader(rawData)
            newChunkedResponse(Status.OK, "video/mp2t", ByteArrayInputStream(cleanData))
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun stripImageHeader(data: ByteArray): ByteArray {
        if (data.size < 188) return data

        // PNG Check: 0x89 'P' 'N' 'G'
        if (data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()) {
            val iend = byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte())
            for (i in 0 until data.size - 8) {
                if (data[i] == iend[0] && data[i + 1] == iend[1] && data[i + 2] == iend[2] && data[i + 3] == iend[3]) {
                    val offset = i + 8
                    if (offset < data.size) {
                        return data.copyOfRange(offset, data.size)
                    }
                }
            }
        }

        // Align to MPEG-TS sync byte (0x47)
        for (i in 0 until minOf(data.size - 188, 8192)) {
            if (data[i] == 0x47.toByte() && data[i + 188] == 0x47.toByte()) {
                return data.copyOfRange(i, data.size)
            }
        }

        return data
    }

    private fun createLocalM3u8Url(m3u8Url: String, headers: Headers?): String {
        val encodedUrl = URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())
        val referer = headers?.get("Referer") ?: "https://4animo.xyz/"
        val encodedReferer = URLEncoder.encode(referer, Charsets.UTF_8.name())
        return "http://127.0.0.1:$port/m3u8?url=$encodedUrl&referer=$encodedReferer"
    }

    private fun createLocalSegmentUrl(segmentUrl: String, referer: String): String {
        val encodedUrl = URLEncoder.encode(segmentUrl, Charsets.UTF_8.name())
        val encodedReferer = URLEncoder.encode(referer, Charsets.UTF_8.name())
        return "http://127.0.0.1:$port/segment?url=$encodedUrl&referer=$encodedReferer"
    }

    private fun Video.copyWithLocalUrl(localUrl: String): Video = Video(
        videoUrl = localUrl,
        videoTitle = videoTitle,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        headers = headers,
    )

    private fun fetchString(url: String, headers: Headers): String {
        val reqClient = client ?: throw IOException("Server not initialized")
        return reqClient.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Fetch playlist failed (${resp.code})")
            resp.body.string()
        }
    }

    private fun fetchBytes(url: String, headers: Headers): ByteArray {
        val reqClient = client ?: throw IOException("Server not initialized")
        return reqClient.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Fetch segment failed (${resp.code})")
            resp.body.bytes()
        }
    }

    private fun rewritePlaylist(content: String, originalUrl: String, referer: String): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        val modifiedLines = mutableListOf<String>()

        content.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#") || trimmed.isBlank() -> modifiedLines.add(line)

                else -> {
                    val resolvedUrl = baseHttpUrl?.resolve(trimmed)?.toString() ?: trimmed
                    val isMasterPlaylist = content.contains("#EXT-X-STREAM-INF")
                    if (isMasterPlaylist || resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        modifiedLines.add(createLocalM3u8Url(resolvedUrl, Headers.headersOf("Referer", referer)))
                    } else {
                        modifiedLines.add(createLocalSegmentUrl(resolvedUrl, referer))
                    }
                }
            }
        }

        return modifiedLines.joinToString("\n")
    }
}

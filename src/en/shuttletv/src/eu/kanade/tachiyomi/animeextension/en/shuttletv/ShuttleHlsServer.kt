package eu.kanade.tachiyomi.animeextension.en.shuttletv

import eu.kanade.tachiyomi.animesource.model.Video
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object ShuttleHlsServer : NanoHTTPD(0) {

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

    fun processVideoList(client: OkHttpClient, videos: List<Video>, defaultHeaders: Headers): List<Video> {
        this.client = client
        ensureStarted()
        return videos.map { video ->
            val localUrl = createLocalUrl(video.videoUrl)
            Video(
                videoUrl = localUrl,
                videoTitle = video.videoTitle,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
                headers = video.headers ?: defaultHeaders,
            )
        }
    }

    @Synchronized
    private fun ensureStarted() {
        if (!isRunning) {
            try {
                start()
                isRunning = true
            } catch (_: Exception) {}
        }
    }

    private fun createLocalUrl(targetUrl: String): String {
        val encodedUrl = URLEncoder.encode(targetUrl, StandardCharsets.UTF_8.name())
        return "http://localhost:$port/proxy?url=$encodedUrl"
    }

    override fun serve(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")

        val client = this.client
            ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server client not initialized")

        return try {
            val reqHeaders = extractHeadersFromSession(session)
            val request = Request.Builder()
                .url(url)
                .headers(reqHeaders)
                .build()

            var response = client.newCall(request).execute()
            if (response.code == 403) {
                response.close()
                val fallbackHeaders = reqHeaders.newBuilder().set("Referer", "https://cinesrc.st/").build()
                response = client.newCall(Request.Builder().url(url).headers(fallbackHeaders).build()).execute()
            }

            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                return newFixedLengthResponse(Status.lookup(code) ?: Status.INTERNAL_ERROR, MIME_PLAINTEXT, "HTTP $code")
            }

            val body = response.body
            val inputStream = body.byteStream()

            val headerBuffer = ByteArray(131072)
            var totalRead = 0
            while (totalRead < headerBuffer.size) {
                val read = inputStream.read(headerBuffer, totalRead, headerBuffer.size - totalRead)
                if (read == -1) break
                totalRead += read
            }

            val sample = if (totalRead == headerBuffer.size) headerBuffer else headerBuffer.copyOf(totalRead)
            val sampleString = String(sample, StandardCharsets.UTF_8)

            if (sampleString.trimStart().startsWith("#EXTM3U")) {
                val fullContent = sampleString + inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                response.close()
                val rewritten = rewritePlaylist(fullContent, url)
                newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", rewritten)
            } else {
                val skipBytes = detectSkipBytes(sample)
                val contentLength = body.contentLength()
                val payloadLength = if (contentLength > 0) contentLength - skipBytes else -1L

                val combinedStream: InputStream = SequenceInputStream(
                    ByteArrayInputStream(sample, skipBytes, maxOf(0, totalRead - skipBytes)),
                    inputStream,
                )

                val filterStream = object : FilterInputStream(combinedStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            response.close()
                        }
                    }
                }

                val status = Status.lookup(response.code) ?: Status.OK
                val mimeType = response.header("Content-Type") ?: "video/mp2t"

                val localResponse = if (payloadLength >= 0) {
                    newFixedLengthResponse(status, mimeType, filterStream, payloadLength)
                } else {
                    newChunkedResponse(status, mimeType, filterStream)
                }

                response.header("Accept-Ranges")?.let { localResponse.addHeader("Accept-Ranges", it) }
                response.header("Content-Range")?.let { localResponse.addHeader("Content-Range", it) }
                localResponse
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun extractHeadersFromSession(session: IHTTPSession): Headers = Headers.Builder().apply {
        var hasReferer = false
        session.headers.forEach { (key, value) ->
            when (key.lowercase()) {
                "user-agent", "origin", "accept", "accept-language", "accept-encoding", "cache-control", "pragma", "range" -> add(key, value)
                "referer" -> {
                    add(key, value)
                    hasReferer = true
                }
            }
        }
        if (!hasReferer) {
            set("Referer", "https://cinesrc.st/")
        }
    }.build()

    private fun rewritePlaylist(content: String, originalUrl: String): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        val modifiedLines = mutableListOf<String>()

        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                modifiedLines.add(line)
                return@forEach
            }
            if (trimmed.startsWith("#")) {
                val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                val match = uriRegex.find(trimmed)
                if (match != null) {
                    val uriValue = match.groupValues[1]
                    val resolved = baseHttpUrl?.resolve(uriValue)?.toString() ?: uriValue
                    modifiedLines.add(trimmed.replace(uriValue, createLocalUrl(resolved)))
                } else {
                    modifiedLines.add(line)
                }
            } else {
                val resolved = baseHttpUrl?.resolve(trimmed)?.toString() ?: trimmed
                modifiedLines.add(createLocalUrl(resolved))
            }
        }

        return modifiedLines.joinToString("\n")
    }

    private fun detectSkipBytes(data: ByteArray): Int {
        if (data.size < 4) return 0
        val isPng = data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        val isGif = data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte()
        if (!isPng && !isGif && !isJpeg) return 0

        val maxScan = minOf(data.size, 131072)
        if (isPng) {
            val iend = byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte())
            val maxIend = minOf(data.size - iend.size, maxScan)
            for (i in 0..maxIend) {
                if (data[i] == iend[0] && data[i + 1] == iend[1] && data[i + 2] == iend[2] && data[i + 3] == iend[3]) {
                    if (i + 8 <= data.size) return i + 8
                }
            }
        }
        val maxTs = minOf(data.size - 188 * 2, maxScan)
        for (i in 0..maxTs) {
            if (data[i] == 0x47.toByte()) {
                var validCount = 0
                val limit = minOf(data.size, i + 188 * 4)
                var j = i
                while (j < limit) {
                    if (data[j] == 0x47.toByte()) validCount++
                    j += 188
                }
                if (validCount >= 3) return i
            }
        }
        return 0
    }
}

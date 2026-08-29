package eu.kanade.tachiyomi.animeextension.en.flixer

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class FlixerHlsServer(private val client: OkHttpClient) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    var port: Int = 0
        private set

    init {
        try {
            serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            executor.execute {
                while (serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private enum class Kind { PLAYLIST, SEGMENT_TS, RAW }

    fun proxyMasterUrl(masterUrl: String, headers: Headers?, quality: String? = null): String = proxyUrl(masterUrl, headers, Kind.PLAYLIST, quality)

    private fun proxyUrl(targetUrl: String, headers: Headers?, kind: Kind, quality: String? = null): String {
        if (port == 0) return targetUrl
        val encodedUrl = Base64.encodeToString(
            targetUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val headersStr = headers?.let { h ->
            buildString {
                for (i in 0 until h.size) {
                    append(h.name(i)).append(":").append(h.value(i)).append("\n")
                }
            }
        } ?: ""
        val encodedHeaders = Base64.encodeToString(
            headersStr.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val path = when (kind) {
            Kind.PLAYLIST -> "playlist.m3u8"
            Kind.SEGMENT_TS -> "segment.ts"
            Kind.RAW -> "raw.key"
        }
        val qualityParam = if (!quality.isNullOrBlank()) "&quality=$quality" else ""
        return "http://127.0.0.1:$port/proxy/$path?url=$encodedUrl&headers=$encodedHeaders$qualityParam"
    }

    fun proxyVideos(videos: List<Video>): List<Video> = videos.map { video ->
        if (!video.needsProxy()) return@map video

        val proxiedUrl = if (looksLikePlaylistUrl(video.videoUrl)) {
            proxyUrl(video.videoUrl, video.headers, Kind.PLAYLIST)
        } else {
            video.videoUrl
        }

        val proxiedAudio = video.audioTracks.map { track ->
            if (looksLikePlaylistUrl(track.url)) {
                Track(proxyUrl(track.url, video.headers, Kind.PLAYLIST), track.lang)
            } else {
                track
            }
        }
        val proxiedSubs = video.subtitleTracks.map { track ->
            if (looksLikePlaylistUrl(track.url)) {
                Track(proxyUrl(track.url, video.headers, Kind.PLAYLIST), track.lang)
            } else {
                track
            }
        }

        Video(
            videoUrl = proxiedUrl,
            videoTitle = video.videoTitle,
            headers = video.headers,
            subtitleTracks = proxiedSubs,
            audioTracks = proxiedAudio,
        )
    }

    private fun Video.needsProxy(): Boolean = looksLikePlaylistUrl(videoUrl) ||
        audioTracks.any { looksLikePlaylistUrl(it.url) } ||
        subtitleTracks.any { looksLikePlaylistUrl(it.url) }

    private fun looksLikePlaylistUrl(url: String): Boolean {
        if (url.contains(".m3u8", ignoreCase = true) || url.contains("mpegurl", ignoreCase = true)) return true
        if (url.contains(".mp4", ignoreCase = true) || url.contains("streamrk", ignoreCase = true) || url.contains(".mkv", ignoreCase = true)) return false
        val lower = url.substringBefore('?').substringBefore('#').lowercase()
        return !lower.endsWith(".mp4") && !lower.endsWith(".mkv") &&
            !lower.endsWith(".webm") && !lower.endsWith(".ts")
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.setSoLinger(true, 5)
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (!path.startsWith("/proxy")) {
                sendError(socket, 404, "Not Found")
                return
            }

            val queryParams = path.substringAfter("?", "")
            var targetUrl = ""
            var encodedHeaders = ""
            var targetQuality: String? = null

            for (param in queryParams.split("&")) {
                val kv = param.split("=", limit = 2)
                if (kv.size == 2) {
                    when (kv[0]) {
                        "url" -> targetUrl = String(Base64.decode(kv[1], Base64.URL_SAFE), Charsets.UTF_8)
                        "headers" -> encodedHeaders = kv[1]
                        "quality" -> targetQuality = kv[1]
                    }
                }
            }

            if (targetUrl.isEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val customHeaders = decodeHeaders(encodedHeaders)
            val requestBuilder = Request.Builder().url(targetUrl)
            customHeaders?.let { requestBuilder.headers(it) }

            val response = client.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    sendError(socket, resp.code, resp.message)
                    return
                }

                val body = resp.body ?: return
                val bodyBytes = body.bytes()

                if (path.contains("playlist.m3u8") || isM3u8Body(bodyBytes)) {
                    val m3u8Content = String(bodyBytes, Charsets.UTF_8)
                    val processed = processM3u8(m3u8Content, targetUrl, encodedHeaders, targetQuality)
                    val out = socket.getOutputStream()
                    writeStatusAndPassHeaders(out, resp)
                    val outBytes = processed.toByteArray(Charsets.UTF_8)
                    out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray(Charsets.UTF_8))
                    out.write("Content-Length: ${outBytes.size}\r\n".toByteArray(Charsets.UTF_8))
                    out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    out.write(outBytes)
                    out.flush()
                } else {
                    sendMediaResponse(socket, resp, bodyBytes)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendMediaResponse(socket: Socket, response: Response, data: ByteArray) {
        val out = socket.getOutputStream()
        val media = stripToMedia(data)
        writeStatusAndPassHeaders(out, response)
        out.write("Content-Length: ${media.size}\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: ${mediaMime(media)}\r\n".toByteArray(Charsets.UTF_8))
        out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(media)
        out.flush()
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {}
    }

    private fun writeStatusAndPassHeaders(out: OutputStream, response: Response) {
        val msg = response.message.ifBlank { if (response.code == 200) "OK" else "Error" }
        out.write("HTTP/1.1 ${response.code} $msg\r\n".toByteArray(Charsets.UTF_8))
        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                name.equals("Content-Length", ignoreCase = true) ||
                name.equals("Content-Encoding", ignoreCase = true)
            ) {
                continue
            }
            out.write("$name: ${headers.value(i)}\r\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun isM3u8Body(bytes: ByteArray): Boolean {
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && (
                bytes[i] == ' '.code.toByte() || bytes[i] == '\n'.code.toByte() ||
                    bytes[i] == '\r'.code.toByte() || bytes[i] == '\t'.code.toByte()
                )
        ) {
            i++
        }
        val tag = "#EXTM3U".toByteArray(Charsets.US_ASCII)
        if (i + tag.size > bytes.size) return false
        for (j in tag.indices) {
            if (bytes[i + j] != tag[j]) return false
        }
        return true
    }

    private fun processM3u8(
        content: String,
        playlistUrl: String,
        encodedHeaders: String,
        targetQuality: String? = null,
    ): String {
        val lines = content.split(Regex("""\r?\n"""))
        val headers = decodeHeaders(encodedHeaders)
        val isMaster = lines.any { it.trim().startsWith("#EXT-X-STREAM-INF") }

        if (isMaster && !targetQuality.isNullOrBlank() && !targetQuality.equals("auto", ignoreCase = true)) {
            val builder = StringBuilder(content.length)
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.isEmpty()) {
                    i++
                    continue
                }
                when {
                    line.startsWith("#EXT-X-MEDIA") -> {
                        builder.append(rewriteUriAttr(line, playlistUrl, headers, Kind.PLAYLIST)).append("\n")
                    }

                    line.startsWith("#EXT-X-STREAM-INF") -> {
                        val streamInf = line
                        i++
                        if (i < lines.size) {
                            val variantLine = lines[i].trim()
                            if (matchesQuality(streamInf, variantLine, targetQuality)) {
                                builder.append(streamInf).append("\n")
                                val resolved = resolveUrl(playlistUrl, variantLine)
                                builder.append(proxyUrl(resolved, headers, Kind.PLAYLIST)).append("\n")
                            }
                        }
                    }

                    line.startsWith("#EXTM3U") || line.startsWith("#EXT-X-VERSION") || line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") -> {
                        builder.append(line).append("\n")
                    }
                }
                i++
            }
            val result = builder.toString().trimEnd()
            if (result.contains("#EXT-X-STREAM-INF")) {
                return result
            }
        }

        val builder = StringBuilder(content.length * 2)
        val isFmp4 = content.contains("#EXT-X-MAP")
        var nextLineIsVariant = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                when {
                    trimmed.startsWith("#EXT-X-MEDIA") ->
                        builder.append(rewriteUriAttr(trimmed, playlistUrl, headers, Kind.PLAYLIST))

                    trimmed.startsWith("#EXT-X-MAP") ->
                        builder.append(rewriteUriAttr(trimmed, playlistUrl, headers, Kind.SEGMENT_TS))

                    trimmed.startsWith("#EXT-X-KEY") ->
                        builder.append(rewriteUriAttr(trimmed, playlistUrl, headers, Kind.RAW))

                    else -> builder.append(trimmed)
                }
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) nextLineIsVariant = true
            } else {
                val resolved = resolveUrl(playlistUrl, trimmed)
                val kind = if (nextLineIsVariant) Kind.PLAYLIST else Kind.SEGMENT_TS
                builder.append(proxyUrl(resolved, headers, kind))
                nextLineIsVariant = false
            }
            builder.append("\n")
        }
        return builder.toString()
    }

    private fun matchesQuality(streamInf: String, variantUrl: String, targetQuality: String): Boolean {
        val cleanQ = targetQuality.lowercase().replace("p", "").replace(" ", "").replace("(4k)", "")
        if (cleanQ == "2160" || targetQuality.contains("4k", ignoreCase = true)) {
            if (streamInf.contains("3840x2160") || streamInf.contains("x2160") ||
                variantUrl.contains("4k", ignoreCase = true) || variantUrl.contains("2160")
            ) {
                return true
            }
        }
        val resMatch = Regex("""RESOLUTION=\d+x(\d+)""").find(streamInf)
        if (resMatch != null && resMatch.groupValues[1] == cleanQ) {
            return true
        }
        if (streamInf.contains("x$cleanQ") || variantUrl.contains("_${cleanQ}p") || variantUrl.contains("${cleanQ}p")) {
            return true
        }
        return false
    }

    private fun rewriteUriAttr(line: String, playlistUrl: String, headers: Headers?, kind: Kind): String {
        val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
        val match = uriRegex.find(line) ?: return line
        val uriValue = match.groupValues[1]
        val resolved = resolveUrl(playlistUrl, uriValue)
        return line.replace(uriValue, proxyUrl(resolved, headers, kind))
    }

    private fun stripToMedia(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        if (startsWithMediaContainer(data, 0)) return data

        val start = findMediaStart(data)
        return if (start in 1 until data.size) data.copyOfRange(start, data.size) else data
    }

    private fun startsWithMediaContainer(data: ByteArray, off: Int): Boolean {
        if (off + 8 <= data.size) {
            val type = String(data, off + 4, 4, Charsets.US_ASCII)
            if (type in listOf("ftyp", "styp", "moof", "sidx", "free", "mdat", "moov", "emsg")) {
                return true
            }
        }
        if (data[off] == 0x47.toByte() && off + 188 < data.size && data[off + 188] == 0x47.toByte()) return true
        return false
    }

    private fun findMediaStart(data: ByteArray): Int {
        val limit = minOf(data.size - 8, 64 * 1024)
        val types = listOf("ftyp", "styp", "moof", "sidx", "moov")
        for (i in 4..limit) {
            val t = String(data, i, 4, Charsets.US_ASCII)
            if (t in types) return i - 4
        }
        for (i in 0 until limit) {
            if (data[i] == 0x47.toByte() && i + 188 < data.size && data[i + 188] == 0x47.toByte()) return i
        }
        return 0
    }

    private fun mediaMime(data: ByteArray): String {
        if (data.size >= 8) {
            val type = String(data, 4, 4, Charsets.US_ASCII)
            if (type in listOf("ftyp", "styp", "moof", "sidx", "moov", "mdat", "free", "emsg")) {
                return "video/mp4"
            }
        }
        if (data.isNotEmpty() && data[0] == 0x47.toByte()) return "video/mp2t"
        if (data.size >= 2 && data[0] == 0xFF.toByte() && (data[1].toInt() and 0xF6) in listOf(0xF0, 0xF2)) {
            return "audio/aac"
        }
        return "video/mp4"
    }

    private fun decodeHeaders(encodedHeaders: String): Headers? {
        if (encodedHeaders.isEmpty()) return null
        return try {
            val headersStr = String(
                Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            val builder = Headers.Builder()
            headersStr.split("\n").forEach { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) builder.set(parts[0].trim(), parts[1].trim())
            }
            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        val body = "<h1>$code $message</h1>"
        val response = "HTTP/1.1 $code $message\r\n" +
            "Content-Type: text/html\r\n" +
            "Content-Length: ${body.length}\r\n" +
            "Connection: close\r\n\r\n" +
            body
        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}

package eu.kanade.tachiyomi.animeextension.en.twodhive

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class TwoDHiveProxy(private val client: OkHttpClient) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    fun start(): Boolean {
        if (serverSocket != null && serverSocket?.isClosed == false) return true
        return try {
            serverSocket = ServerSocket(0).also { socket ->
                port = socket.localPort
                executor.execute {
                    while (serverSocket?.isClosed == false) {
                        runCatching {
                            val conn = socket.accept()
                            executor.execute { handle(conn) }
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            serverSocket = null
            false
        }
    }

    fun isRunning(): Boolean = serverSocket?.isClosed == false

    fun processVideo(video: Video, referer: String): Video {
        val url = video.videoUrl
        if (!url.contains(".m3u8", ignoreCase = true) && !url.contains("/stream.m3u8")) return video
        if (!start()) return video
        val encoded = encode(url)
        val encodedReferer = encode(referer)
        return video.copy(
            videoUrl = "http://127.0.0.1:$port/playlist/$encoded.m3u8?r=$encodedReferer",
        )
    }

    fun processDirectVideo(video: Video, referer: String): Video {
        val url = video.videoUrl
        if (!start()) return video
        val encoded = encode(url)
        val encodedReferer = encode(referer)
        return video.copy(
            videoUrl = "http://127.0.0.1:$port/direct/$encoded.mp4?r=$encodedReferer",
        )
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sock ->
                sock.tcpNoDelay = true
                val input = sock.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1) ?: return

                when {
                    path.startsWith("/playlist/") -> servePlaylist(sock, method, path)
                    path.startsWith("/media/") -> serveMedia(sock, method, path)
                    path.startsWith("/direct/") -> serveDirect(sock, method, path, input)
                    else -> respond(sock, 404, "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun servePlaylist(sock: Socket, method: String, path: String) {
        val (masterUrl, referer) = decode(path) ?: run {
            respond(sock, 400, "text/plain", "Bad Request".toByteArray())
            return
        }

        if (method.equals("HEAD", true)) {
            respond(sock, 200, "application/vnd.apple.mpegurl", ByteArray(0), sendBody = false)
            return
        }

        val body = fetchText(masterUrl, referer) ?: run {
            respond(sock, 502, "text/plain", "Upstream playlist fetch failed".toByteArray())
            return
        }

        val isMediaPlaylist = body.substringAfter("#EXTM3U").contains("#EXTINF")
        val out = StringBuilder()
        for (line in body.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || trimmed.startsWith("#") -> out.append(trimmed).append('\n')

                isMediaPlaylist -> {
                    val absolute = resolve(masterUrl, trimmed)
                    val b64 = encode(absolute)
                    out.append("http://127.0.0.1:$port/media/$b64.ts?r=").append(encode(referer)).append('\n')
                }

                else -> {
                    val absolute = resolve(masterUrl, trimmed)
                    val b64 = encode(absolute)
                    out.append("http://127.0.0.1:$port/playlist/$b64.m3u8?r=").append(encode(referer)).append('\n')
                }
            }
        }
        respond(sock, 200, "application/vnd.apple.mpegurl", out.toString().toByteArray())
    }

    private fun serveMedia(sock: Socket, method: String, path: String) {
        val (segmentUrl, referer) = decode(path) ?: run {
            respond(sock, 400, "text/plain", "Bad Request".toByteArray())
            return
        }

        if (method.equals("HEAD", true)) {
            respond(sock, 200, "video/mp2t", ByteArray(0), sendBody = false)
            return
        }

        val request = Request.Builder()
            .url(segmentUrl)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    respond(sock, 502, "text/plain", "Upstream ${response.code}".toByteArray())
                    return
                }
                val rawBytes = response.body.bytes()
                val offset = findMpegTsOffset(rawBytes)
                val cleanBytes = if (offset > 0 && offset < rawBytes.size) {
                    rawBytes.copyOfRange(offset, rawBytes.size)
                } else {
                    rawBytes
                }
                respond(sock, 200, "video/mp2t", cleanBytes)
            }
        } catch (e: Exception) {
            runCatching { respond(sock, 502, "text/plain", (e.message ?: "").toByteArray()) }
        }
    }

    private fun serveDirect(sock: Socket, method: String, path: String, input: java.io.BufferedReader) {
        val (mediaUrl, referer) = decode(path) ?: run {
            respond(sock, 400, "text/plain", "Bad Request".toByteArray())
            return
        }

        var rangeHeader: String? = null
        var line = input.readLine()
        while (!line.isNullOrBlank()) {
            if (line.startsWith("Range:", ignoreCase = true)) {
                rangeHeader = line.substringAfter(":").trim()
            }
            line = input.readLine()
        }

        val reqBuilder = Request.Builder()
            .url(mediaUrl)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)

        if (rangeHeader != null) {
            reqBuilder.header("Range", rangeHeader)
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val statusCode = response.code
                val contentType = "video/mp4"
                val contentLength = response.body.contentLength()

                val head = StringBuilder("HTTP/1.1 $statusCode ${response.message}\r\n")
                    .append("Content-Type: $contentType\r\n")
                if (contentLength >= 0) {
                    head.append("Content-Length: $contentLength\r\n")
                }
                response.header("Content-Range")?.let {
                    head.append("Content-Range: $it\r\n")
                }
                head.append("Accept-Ranges: bytes\r\n")
                head.append("Connection: close\r\n\r\n")

                val out = sock.getOutputStream()
                out.write(head.toString().toByteArray())
                if (!method.equals("HEAD", true)) {
                    response.body.byteStream().copyTo(out)
                }
                out.flush()
                runCatching { sock.shutdownOutput() }
            }
        } catch (e: Exception) {
            runCatching { respond(sock, 502, "text/plain", (e.message ?: "").toByteArray()) }
        }
    }

    private fun findMpegTsOffset(data: ByteArray): Int {
        if (data.size < 4) return 0
        if (data[0] == 0x47.toByte()) return 0
        val limit = minOf(data.size - 376, 65536)
        for (i in 0 until limit) {
            if (data[i] == 0x47.toByte() &&
                data[i + 188] == 0x47.toByte() &&
                data[i + 376] == 0x47.toByte()
            ) {
                return i
            }
        }
        return 0
    }

    private fun fetchText(url: String, referer: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    } catch (_: Exception) {
        null
    }

    private fun decode(path: String): Pair<String, String>? {
        val referer = Regex("""[?&]r=([^&]+)""").find(path)?.groupValues?.get(1)
            ?.let { runCatching { String(Base64.decode(it, Base64.URL_SAFE)) }.getOrNull() }
            ?: "https://2dhive.com/"

        val prefix = when {
            path.startsWith("/playlist/") -> "/playlist/"
            path.startsWith("/media/") -> "/media/"
            path.startsWith("/direct/") -> "/direct/"
            else -> return null
        }
        val token = path.substringAfter(prefix)
            .substringBefore('?')
            .substringBefore(".m3u8")
            .substringBefore(".ts")
            .substringBefore(".mp4")
            .replace("=", "")
        if (token.isBlank()) return null
        val url = runCatching { String(Base64.decode(token, Base64.URL_SAFE)) }.getOrNull() ?: return null
        if (!url.startsWith("http")) return null
        return url to referer
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun resolve(baseUrl: String, relative: String): String =
        baseUrl.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: relative

    private fun respond(sock: Socket, status: Int, contentType: String, body: ByteArray, sendBody: Boolean = true) {
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "Internal Server Error"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray())
        if (sendBody && body.isNotEmpty()) {
            out.write(body)
        }
        out.flush()
        runCatching { sock.shutdownOutput() }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

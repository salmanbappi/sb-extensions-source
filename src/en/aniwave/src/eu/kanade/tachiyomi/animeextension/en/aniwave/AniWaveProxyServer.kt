package eu.kanade.tachiyomi.animeextension.en.aniwave

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Lightweight, self-contained local loopback HTTP proxy server for AniWave.
 * Operates purely on standard Java ServerSockets with zero external dependencies
 * (no NanoHTTPD), eliminating host-app classloader conflicts and ProGuard/R8 issues.
 *
 * Automatically intercepts HLS playlists and strips image-based segment obfuscation
 * (PNG, JPEG, GIF wrappers) used by video CDNs so MPV plays cleanly without demuxer errors.
 */
class AniWaveProxyServer(private val client: OkHttpClient) {

    private val tag = "AniWaveProxy"
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AniWaveProxy-Worker").apply { isDaemon = true }
    }

    @Volatile
    private var isRunning = false

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        if (isRunning && serverSocket?.isClosed == false) return
        synchronized(this) {
            if (isRunning && serverSocket?.isClosed == false) return
            try {
                val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
                serverSocket = s
                isRunning = true
                Log.d(tag, "AniWave local proxy server started on 127.0.0.1:${s.localPort}")
                executor.execute {
                    while (isRunning && !s.isClosed) {
                        try {
                            val socket = s.accept()
                            executor.execute { handleSocket(socket) }
                        } catch (_: Exception) {
                            if (!isRunning || s.isClosed) break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start AniWave proxy: ${e.message}")
                isRunning = false
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        Log.d(tag, "AniWave local proxy server stopped")
    }

    fun isServerRunning(): Boolean = isRunning && serverSocket?.isClosed == false

    /**
     * Converts an upstream video or playlist URL into a local loopback URL.
     */
    fun relay(targetUrl: String, referer: String? = null, userAgent: String? = null): String {
        start()
        val ext = if (targetUrl.contains(".m3u8", ignoreCase = true)) ".m3u8" else ".ts"
        val encodedTarget = URLEncoder.encode(targetUrl, "UTF-8")
        return buildString {
            append("http://127.0.0.1:$port/stream$ext?u=$encodedTarget")
            if (!referer.isNullOrBlank()) {
                append("&r=").append(URLEncoder.encode(referer, "UTF-8"))
            }
            if (!userAgent.isNullOrBlank()) {
                append("&ua=").append(URLEncoder.encode(userAgent, "UTF-8"))
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30000

            val input: InputStream = socket.getInputStream()
            val reader = input.bufferedReader(Charsets.UTF_8)
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val fullPath = parts[1]
            val isHead = method.equals("HEAD", ignoreCase = true)

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val queryString = fullPath.substringAfter('?', "")
            val params = parseQuery(queryString)
            val targetUrl = params["u"] ?: return respond(socket, 400, "text/plain", "Missing target URL".toByteArray(), isHead)
            val referer = params["r"]
            val userAgent = params["ua"]

            if (targetUrl.contains(".m3u8", ignoreCase = true)) {
                handlePlaylist(socket, targetUrl, referer, userAgent, isHead)
            } else {
                handleSegment(socket, targetUrl, referer, userAgent, rangeHeader, isHead)
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handlePlaylist(
        socket: Socket,
        url: String,
        referer: String?,
        userAgent: String?,
        isHead: Boolean,
    ) {
        val reqBuilder = Request.Builder().url(url)
        if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)
        if (!userAgent.isNullOrBlank()) reqBuilder.header("User-Agent", userAgent)

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    respond(socket, response.code, "text/plain", "Upstream HTTP ${response.code}".toByteArray(), isHead)
                    return
                }
                val body = response.body.string()
                val rewritten = rewritePlaylist(body, url, referer, userAgent)
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                respond(socket, 200, "application/vnd.apple.mpegurl", bytes, isHead)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing playlist $url: ${e.message}")
            respond(socket, 502, "text/plain", "Failed to fetch playlist: ${e.message}".toByteArray(), isHead)
        }
    }

    private fun handleSegment(
        socket: Socket,
        url: String,
        referer: String?,
        userAgent: String?,
        rangeHeader: String?,
        isHead: Boolean,
    ) {
        val reqBuilder = Request.Builder().url(url)
        if (!referer.isNullOrBlank()) reqBuilder.header("Referer", referer)
        if (!userAgent.isNullOrBlank()) reqBuilder.header("User-Agent", userAgent)
        if (!rangeHeader.isNullOrBlank()) reqBuilder.header("Range", rangeHeader)

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    respond(socket, response.code, "text/plain", "Upstream HTTP ${response.code}".toByteArray(), isHead)
                    return
                }
                val rawBytes = response.body.bytes()
                val cleanBytes = stripImageObfuscation(rawBytes)
                respond(socket, response.code, "video/mp2t", cleanBytes, isHead)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing segment $url: ${e.message}")
            respond(socket, 502, "text/plain", "Failed to fetch segment: ${e.message}".toByteArray(), isHead)
        }
    }

    private fun rewritePlaylist(
        content: String,
        baseUrl: String,
        referer: String?,
        userAgent: String?,
    ): String = buildString {
        val baseHttp = baseUrl.toHttpUrlOrNull()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> append('\n')

                trimmed.startsWith("#EXT-X-KEY:") || trimmed.startsWith("#EXT-X-MEDIA:") || trimmed.startsWith("#EXT-X-MAP:") -> {
                    val rewritten = URI_ATTR_REGEX.replace(line) { match ->
                        val originalUri = match.groupValues[1]
                        val resolved = resolveUrl(baseHttp, originalUri)
                        "URI=\"${relay(resolved, referer, userAgent)}\""
                    }
                    append(rewritten).append('\n')
                }

                trimmed.startsWith("#") -> append(line).append('\n')

                else -> {
                    val resolved = resolveUrl(baseHttp, trimmed)
                    append(relay(resolved, referer, userAgent)).append('\n')
                }
            }
        }
    }

    private fun resolveUrl(base: okhttp3.HttpUrl?, relative: String): String = base?.resolve(relative)?.toString() ?: relative

    /**
     * Strips PNG disguise (IEND chunk + MPEG-TS sync scan), JPEG headers,
     * and interleaved junk to recover clean MPEG-TS TS packets starting with 0x47.
     */
    private fun stripImageObfuscation(data: ByteArray): ByteArray {
        if (data.size < 188) return data
        if (isCleanTs(data)) return data

        val offset = detectSegmentOffset(data)
        if (offset > 0 && offset < data.size) {
            val stripped = data.copyOfRange(offset, data.size)
            if (isCleanTs(stripped)) return stripped
            val secondary = detectGeneralTsOffset(stripped)
            return if (secondary > 0 && secondary < stripped.size) {
                stripped.copyOfRange(secondary, stripped.size)
            } else {
                stripped
            }
        }

        val generalOffset = detectGeneralTsOffset(data)
        if (generalOffset > 0 && generalOffset < data.size) {
            return data.copyOfRange(generalOffset, data.size)
        }

        return data
    }

    private fun detectSegmentOffset(data: ByteArray): Int {
        if (data.size < 8) return 0
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return 0
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            // Locate 'I', 'E', 'N', 'D' chunk
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8 // 4 bytes for IEND + 4 bytes CRC
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return 0
        val tsLength = data.size - videoStart
        if (tsLength < 188) return videoStart
        val iMin = min(tsLength - 188, 400)
        for (offset in 0 until iMin) {
            val actualOffset = videoStart + offset
            if (data[actualOffset] == TS_SYNC && data[actualOffset + TS_PACKET_SIZE] == TS_SYNC) {
                return actualOffset
            }
        }
        return videoStart
    }

    private fun detectGeneralTsOffset(data: ByteArray): Int {
        val ceiling = min(data.size - TS_PACKET_SIZE * 2, 8192)
        for (i in 0 until ceiling) {
            if (data[i] == TS_SYNC && data[i + TS_PACKET_SIZE] == TS_SYNC) {
                return i
            }
        }
        return 0
    }

    private fun isCleanTs(data: ByteArray): Boolean {
        if (data.size < TS_PACKET_SIZE * 4 || data[0] != TS_SYNC) return false
        var syncs = 0
        var index = 0
        val ceiling = min(data.size - TS_PACKET_SIZE, TS_PACKET_SIZE * 16)
        while (index <= ceiling) {
            if (data[index] == TS_SYNC) syncs++
            index += TS_PACKET_SIZE
        }
        return syncs >= 4
    }

    private fun respond(
        socket: Socket,
        status: Int,
        mime: String,
        body: ByteArray,
        headOnly: Boolean = false,
    ) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "Status $status"
        }
        val out: OutputStream = socket.getOutputStream()
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $mime\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (!headOnly) {
            out.write(body)
        }
        out.flush()
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { param ->
            val eq = param.indexOf('=')
            if (eq <= 0) {
                null
            } else {
                val k = URLDecoder.decode(param.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(param.substring(eq + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    companion object {
        private const val TS_SYNC = 0x47.toByte()
        private const val TS_PACKET_SIZE = 188
        private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")
    }
}

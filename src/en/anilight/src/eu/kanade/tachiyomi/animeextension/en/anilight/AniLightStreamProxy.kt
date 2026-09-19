package eu.kanade.tachiyomi.animeextension.en.anilight

import android.util.Base64
import aniyomi.lib.m3u8server.AutoDetector
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Loopback HLS relay used for the krussdomi family (providers "l" and "raye").
 *
 * Those masters carry `#EXT-X-MEDIA:TYPE=AUDIO` renditions whose URIs are
 * absolute worker URLs, and every playlist in the chain — audio renditions
 * included — hands out MPEG-TS chunks under fake `.jpg` names with an
 * `image/jpeg` content type. The shared m3u8server only rewrites bare URI
 * lines and never `#EXT-X-MEDIA` attributes, so the player fetched the audio
 * playlists straight from the worker, hit the `.jpg`/`image/jpeg` disguise and
 * died on "unrecognised file format".
 *
 * This relay keeps the master whole (so the separate audio renditions are
 * still handled natively by the player) but points *every* URI inside it back
 * at itself: `#EXT-X-MEDIA` attributes, variant playlists and chunks alike.
 * Chunks are re-served as `video/mp2t` with any image wrapper stripped, which
 * is what makes the disguised segments playable.
 *
 * Only URLs produced by [relay] are ever handed to the player; they carry no
 * `.m3u8` suffix (base64url contains no dots), so `M3u8Integration` leaves them
 * alone instead of wrapping them a second time.
 */
class AniLightStreamProxy(private val client: OkHttpClient) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var port: Int = 0

    /** Returns the loopback URL that serves [targetUrl] (and everything it references). */
    fun relay(targetUrl: String, referer: String): String {
        start()
        return "$LOOPBACK:$port$PATH?u=${encode(targetUrl)}&r=${encode(referer)}"
    }

    private fun start() {
        if (serverSocket?.isClosed == false) return
        synchronized(this) {
            if (serverSocket?.isClosed == false) return
            val socket = try {
                ServerSocket(0)
            } catch (_: Exception) {
                return
            }
            serverSocket = socket
            port = socket.localPort
            executor.execute {
                while (!socket.isClosed) {
                    val connection = try {
                        socket.accept()
                    } catch (_: Exception) {
                        return@execute
                    }
                    executor.execute { handle(connection) }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.setSoLinger(true, 5)

            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val isHead = requestLine.startsWith("HEAD ")
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val params = path.substringAfter("?", "")
                .split("&")
                .mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator <= 0) null else pair.take(separator) to pair.drop(separator + 1)
                }
                .toMap()

            val target = params["u"]?.let(::decode) ?: return respond(socket, 400, MIME_TEXT, ByteArray(0))
            val referer = params["r"]?.let(::decode).orEmpty()

            val request = Request.Builder()
                .url(target)
                .header("User-Agent", USER_AGENT)
                .apply { if (referer.isNotBlank()) header("Referer", referer) }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    respond(socket, response.code, MIME_TEXT, ByteArray(0))
                    return
                }
                val body = response.body.bytes()
                val effectiveUrl = response.request.url.toString()

                if (isPlaylist(body)) {
                    val rewritten = rewritePlaylist(String(body, Charsets.UTF_8), effectiveUrl, referer)
                    respond(socket, 200, MIME_HLS, rewritten.toByteArray(Charsets.UTF_8), isHead)
                } else {
                    respond(socket, 200, MIME_TS, normalizeSegment(body), isHead)
                }
            }
        } catch (_: Exception) {
            // Dropped connection or upstream failure: the socket just closes.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun rewritePlaylist(playlist: String, baseUrl: String, referer: String): String = buildString {
        playlist.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> append('\n')

                trimmed.startsWith(MEDIA_TAG) -> append(MEDIA_URI_REGEX.replace(line) { match ->
                    "URI=\"${relay(resolve(match.groupValues[1], baseUrl), referer)}\""
                }).append('\n')

                trimmed.startsWith("#") -> append(line).append('\n')

                else -> append(relay(resolve(trimmed, baseUrl), referer)).append('\n')
            }
        }
    }

    private fun resolve(uri: String, baseUrl: String): String =
        baseUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri

    private fun isPlaylist(body: ByteArray): Boolean {
        val head = body.decodeToString(0, minOf(body.size, 512)).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return head.startsWith("#EXTM3U") || head.startsWith("#EXT-X-")
    }

    /**
     * Leaves clean MPEG-TS untouched (so no false-positive junk stripping on
     * real video data) and only runs the detector when the chunk really is
     * wrapped, matching what the shared m3u8server does for other providers.
     */
    private fun normalizeSegment(data: ByteArray): ByteArray {
        if (isCleanTs(data)) return data
        val ranges = AutoDetector.detectInterleavedSkips(data)
        if (ranges.isEmpty()) return data

        val stripped = ByteArray((data.size - ranges.sumOf { it.last - it.first + 1 }).coerceAtLeast(0))
        var cursor = 0
        var offset = 0
        ranges.forEach { range ->
            if (range.first > cursor) {
                val length = range.first - cursor
                System.arraycopy(data, cursor, stripped, offset, length)
                offset += length
            }
            cursor = range.last + 1
        }
        if (cursor < data.size) {
            System.arraycopy(data, cursor, stripped, offset, data.size - cursor)
        }
        return stripped
    }

    private fun isCleanTs(data: ByteArray): Boolean {
        if (data.size < TS_PACKET_SIZE * 4 || data[0] != TS_SYNC) return false
        var syncs = 0
        var index = 0
        val ceiling = minOf(data.size - TS_PACKET_SIZE, TS_PACKET_SIZE * 16)
        while (index <= ceiling) {
            if (data[index] == TS_SYNC) syncs++
            index += TS_PACKET_SIZE
        }
        return syncs >= 8
    }

    private fun respond(socket: Socket, status: Int, mime: String, body: ByteArray, headOnly: Boolean = false) {
        val out: OutputStream = socket.getOutputStream()
        val reason = if (status == 200) "OK" else "Upstream Error"
        out.write("HTTP/1.1 $status $reason\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n".toByteArray())
        out.write("Content-Length: ${body.size}\r\n".toByteArray())
        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val LOOPBACK = "http://127.0.0.1"
        const val PATH = "/anilight"

        const val MIME_HLS = "application/vnd.apple.mpegurl"
        const val MIME_TS = "video/mp2t"
        const val MIME_TEXT = "text/plain"

        const val MEDIA_TAG = "#EXT-X-MEDIA:"
        const val TS_SYNC = 0x47.toByte()
        const val TS_PACKET_SIZE = 188

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val MEDIA_URI_REGEX = Regex("""URI="([^"]+)"""")
    }
}

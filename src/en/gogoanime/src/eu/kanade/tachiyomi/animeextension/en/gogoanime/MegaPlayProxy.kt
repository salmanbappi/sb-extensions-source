package eu.kanade.tachiyomi.animeextension.en.gogoanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Minimal local HLS relay for the MegaPlay (HD-1) CDN.
 *
 * Why this exists: MegaPlay serves plain MPEG-TS segments under rotating fake
 * image extensions (`seg-...-00000.jpg`, `...html`, `...js`, ...). FFmpeg-based
 * players (mpv, the engine Aniyomi uses) probe the segment demuxer partly from
 * the URL suffix, pick `image2`/mjpeg for these, and then fail with
 * "Invalid data found when processing input" in a repeating glitch loop.
 *
 * This relay rewrites the media playlist so every segment URL it hands to the
 * player ends in `.ts`, fetches the real bytes upstream with the required
 * `Referer: https://megaplay.buzz/` header, and streams them back untouched.
 *
 * Zero external dependencies (pure `ServerSocket`), scoped to this extension.
 */
class MegaPlayProxy(private val client: OkHttpClient) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    fun start(): Boolean {
        if (serverSocket != null && !serverSocket!!.isClosed) return true
        return try {
            serverSocket = ServerSocket(0).also { socket ->
                port = socket.localPort
                executor.execute {
                    while (!socket.isClosed) {
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

    /**
     * Rewrites a Video so its playlist (and every segment inside it) is served
     * from loopback with `.ts`-terminated URLs. Returns the original video
     * unchanged when the relay cannot start or the URL is not an HLS playlist.
     */
    fun processVideo(video: Video, referer: String): Video {
        val url = video.videoUrl
        if (!url.contains(".m3u8", ignoreCase = true)) return video
        if (!start()) return video
        val encoded = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val encodedReferer = Base64.encodeToString(referer.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return video.copy(
            videoUrl = "http://127.0.0.1:$port/playlist/$encoded.m3u8?r=$encodedReferer",
        )
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { sock ->
                sock.tcpNoDelay = true
                val input = sock.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: return

                when {
                    path.startsWith("/playlist/") -> servePlaylist(sock, path)
                    path.startsWith("/media/") -> serveMedia(sock, path)
                    else -> respond(sock, 404, "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (_: Exception) {
            // Connection dropped by the player; nothing to do.
        }
    }

    /** /playlist/<b64 master>.m3u8?r=<b64 referer> -> rewritten master playlist. */
    private fun servePlaylist(sock: Socket, path: String) {
        val (masterUrl, referer) = decode(path) ?: run {
            respond(sock, 400, "text/plain", "Bad Request".toByteArray())
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
                    // Segment line: route through /media/ so the URL ends in .ts.
                    val absolute = resolve(masterUrl, trimmed)
                    val b64 = encode(absolute)
                    out.append("http://127.0.0.1:$port/media/$b64.ts?r=").append(encode(referer)).append('\n')
                }

                else -> {
                    // Child playlist line inside a master: recurse through /playlist/.
                    val absolute = resolve(masterUrl, trimmed)
                    val b64 = encode(absolute)
                    out.append("http://127.0.0.1:$port/playlist/$b64.m3u8?r=").append(encode(referer)).append('\n')
                }
            }
        }
        respond(sock, 200, "application/vnd.apple.mpegurl", out.toString().toByteArray())
    }

    /** /media/<b64 segment>.ts?r=<b64 referer> -> raw segment bytes as video/mp2t. */
    private fun serveMedia(sock: Socket, path: String) {
        val (segmentUrl, referer) = decode(path) ?: run {
            respond(sock, 400, "text/plain", "Bad Request".toByteArray())
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
                    respond(
                        sock,
                        502,
                        "text/plain",
                        "Upstream ${response.code} for segment".toByteArray(),
                    )
                    return
                }
                val bytes = response.body.bytes()
                respond(sock, 200, "video/mp2t", bytes)
            }
        } catch (e: Exception) {
            runCatching { respond(sock, 502, "text/plain", e.message?.toByteArray() ?: ByteArray(0)) }
        }
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
            ?: "https://megaplay.buzz/"
        val b64 = path.substringAfter("/playlist/", "")
            .substringAfter("/media/", "")
            .substringBefore(".m3u8")
            .substringBefore(".ts")
            .substringBefore("?")
        if (b64.isBlank()) return null
        val url = runCatching { String(Base64.decode(b64, Base64.URL_SAFE)) }.getOrNull() ?: return null
        if (!url.startsWith("http")) return null
        return url to referer
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun resolve(baseUrl: String, relative: String): String =
        baseUrl.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: relative

    private fun respond(sock: Socket, status: Int, contentType: String, body: ByteArray) {
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
        sock.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
        runCatching { sock.shutdownOutput() }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}

package eu.kanade.tachiyomi.animeextension.en.cinejoy

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Self-contained local HLS proxy for Cinejoy.
 *
 * Cinejoy fronts several CDNs (movieboxnoob, nebula, …). They obfuscate HLS in
 * ways that break players directly:
 *
 *  * Segments and even child playlists are given fake extensions (`.jpg`,
 *    `.png`, `.jpeg`, `.html`) and served with the matching bogus
 *    `Content-Type` (`image/jpeg`, `text/html`), while the actual bytes are
 *    fragmented-MP4 (`ftyp`/`styp`/`moof`) or MPEG-TS. mpv then refuses to
 *    parse the nested playlist ("Not detecting m3u8/hls with non standard
 *    extension and non standard mime type") and tries to decode segments as
 *    still images ("mjpeg: No JPEG data found in image").
 *  * Some segments are wrapped behind a real PNG/JPEG/GIF header that must be
 *    stripped before the media container starts.
 *
 * This proxy fetches every resource server-side (re-issuing the upstream
 * Referer/User-Agent), then decides what a resource is by SNIFFING ITS BYTES,
 * not by its URL or the CDN's mislabeled mime:
 *
 *  * If the body starts with `#EXTM3U`, it is treated as a playlist: every
 *    child playlist / segment / init-map / audio-rendition URI is rewritten to
 *    loop back through this proxy, and it is re-served as
 *    `application/vnd.apple.mpegurl`.
 *  * Otherwise it is a media segment: any leading image/junk header is stripped
 *    and it is re-served with a correct video mime (`video/mp4` for fMP4,
 *    `video/mp2t` for TS).
 *
 * Both [Video.videoUrl] and the [Video.audioTracks]/[Video.subtitleTracks]
 * entries are proxied, so external audio renditions get the identical laundering
 * the video track does — which is what makes audio actually play.
 */
class CinejoyHlsServer(private val client: OkHttpClient) {

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

    private enum class Kind { PLAYLIST, SEGMENT, RAW }

    /**
     * Builds a localhost URL wrapping [targetUrl]. [kind] records what the
     * resource is *expected* to be (from its position in a parent playlist) so
     * the local path carries a sensible extension for the player; the actual
     * handling is still decided by content sniffing when the bytes arrive.
     */
    private fun proxyUrl(targetUrl: String, headers: Headers?, kind: Kind): String {
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
            Kind.SEGMENT -> "segment.ts"
            Kind.RAW -> "raw.key"
        }
        return "http://127.0.0.1:$port/proxy/$path?url=$encodedUrl&headers=$encodedHeaders"
    }

    /**
     * Routes the video playlist and every external audio/subtitle rendition of
     * each [Video] through the local proxy. Non-HLS entries are left untouched.
     */
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

    // A top-level rendition URL is assumed to be an HLS playlist. We keep this
    // permissive on purpose: these CDNs disguise playlists behind .jpg/.png/etc,
    // so only obvious non-HLS media containers are excluded.
    private fun looksLikePlaylistUrl(url: String): Boolean {
        if (url.contains(".m3u8", ignoreCase = true) || url.contains("mpegurl", ignoreCase = true)) return true
        val lower = url.substringBefore('?').substringBefore('#').lowercase()
        return !lower.endsWith(".mp4") && !lower.endsWith(".mkv") &&
            !lower.endsWith(".webm") && !lower.endsWith(".ts")
    }

    private fun handleSocket(socket: Socket) {
        try {
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

            val httpUrl = ("http://127.0.0.1$path").toHttpUrl()
            val encodedUrl = httpUrl.queryParameter("url")
            val encodedHeaders = httpUrl.queryParameter("headers") ?: ""

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(
                Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            val isKey = path.contains("raw.key")

            val targetHeaders = decodeHeaders(encodedHeaders)?.newBuilder() ?: Headers.Builder()

            // Forward a client Range header only for media segments, never for
            // playlists/keys (a ranged playlist request breaks parsing).
            val isPlaylistPath = path.contains("playlist.m3u8")
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val hp = line!!.split(":", limit = 2)
                if (hp.size == 2 && hp[0].trim().equals("Range", ignoreCase = true) && !isPlaylistPath && !isKey) {
                    targetHeaders.set("Range", hp[1].trim())
                }
            }

            val request = Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders, isKey)
            }
        } catch (e: Exception) {
            try {
                sendError(socket, 500, e.message ?: "Internal Error")
            } catch (_: Exception) {}
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(
        socket: Socket,
        response: Response,
        targetUrl: String,
        encodedHeaders: String,
        isKey: Boolean,
    ) {
        val out = socket.getOutputStream()
        val bodyBytes = response.body.bytes()

        // Decryption keys and any other opaque blob: pass through verbatim.
        if (isKey) {
            writeStatusAndPassHeaders(out, response)
            out.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: application/octet-stream\r\n".toByteArray(Charsets.UTF_8))
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()
            return
        }

        // Content sniff: the CDN mislabels playlists as image/html, so trust the
        // bytes. A playlist always begins with the #EXTM3U tag.
        if (isM3u8Body(bodyBytes)) {
            val text = String(bodyBytes, Charsets.UTF_8)
            val rewritten = processM3u8(text, targetUrl, encodedHeaders).toByteArray(Charsets.UTF_8)
            writeStatusAndPassHeaders(out, response)
            out.write("Content-Length: ${rewritten.size}\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray(Charsets.UTF_8))
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(rewritten)
            out.flush()
            return
        }

        // Media segment: strip any disguising image/junk header, then relabel
        // with a real video mime the player can actually demux.
        val media = stripToMedia(bodyBytes)
        writeStatusAndPassHeaders(out, response)
        out.write("Content-Length: ${media.size}\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: ${mediaMime(media)}\r\n".toByteArray(Charsets.UTF_8))
        out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(media)
        out.flush()
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
        // Skip a UTF-8 BOM / leading whitespace, then look for #EXTM3U.
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

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)
        val headers = decodeHeaders(encodedHeaders)

        // A master playlist declares variant streams; the plain URL line that
        // follows #EXT-X-STREAM-INF is itself a child playlist, not a segment.
        var nextLineIsVariant = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                when {
                    // Audio/subtitle renditions point at child playlists.
                    trimmed.startsWith("#EXT-X-MEDIA") ->
                        builder.append(rewriteUriAttr(trimmed, playlistUrl, headers, Kind.PLAYLIST))

                    // Init map is a media (fMP4) init segment.
                    trimmed.startsWith("#EXT-X-MAP") ->
                        builder.append(rewriteUriAttr(trimmed, playlistUrl, headers, Kind.SEGMENT))

                    // Decryption key: opaque bytes, passed through untouched.
                    trimmed.startsWith("#EXT-X-KEY") ->
                        builder.append(rewriteUriAttr(trimmed, playlistUrl, headers, Kind.RAW))

                    else -> builder.append(trimmed)
                }
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) nextLineIsVariant = true
            } else {
                val resolved = resolveUrl(playlistUrl, trimmed)
                val kind = if (nextLineIsVariant) Kind.PLAYLIST else Kind.SEGMENT
                builder.append(proxyUrl(resolved, headers, kind))
                nextLineIsVariant = false
            }
            builder.append("\n")
        }
        return builder.toString()
    }

    private fun rewriteUriAttr(line: String, playlistUrl: String, headers: Headers?, kind: Kind): String {
        val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
        val match = uriRegex.find(line) ?: return line
        val uriValue = match.groupValues[1]
        val resolved = resolveUrl(playlistUrl, uriValue)
        return line.replace(uriValue, proxyUrl(resolved, headers, kind))
    }

    // ---- segment byte laundering ----

    private fun stripToMedia(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        // Already a clean container at offset 0.
        if (startsWithMediaContainer(data, 0)) return data

        // Real image wrapper? Scan for the embedded media container start.
        val start = findMediaStart(data)
        return if (start in 1 until data.size) data.copyOfRange(start, data.size) else data
    }

    private fun startsWithMediaContainer(data: ByteArray, off: Int): Boolean {
        // ISO-BMFF box: [4-byte size][4-char type]; accept common HLS fMP4 boxes.
        if (off + 8 <= data.size) {
            val type = String(data, off + 4, 4, Charsets.US_ASCII)
            if (type == "ftyp" || type == "styp" || type == "moof" || type == "sidx" ||
                type == "free" || type == "mdat" || type == "moov" || type == "emsg"
            ) {
                return true
            }
        }
        // MPEG-TS: sync byte 0x47 repeating every 188 bytes.
        if (data[off] == 0x47.toByte() && off + 188 < data.size && data[off + 188] == 0x47.toByte()) return true
        return false
    }

    private fun findMediaStart(data: ByteArray): Int {
        val limit = minOf(data.size - 8, 64 * 1024)
        // fMP4 box types.
        val types = listOf("ftyp", "styp", "moof", "sidx", "moov")
        for (i in 4..limit) {
            val t = String(data, i, 4, Charsets.US_ASCII)
            if (t in types) return i - 4
        }
        // TS sync fallback.
        for (i in 0 until limit) {
            if (data[i] == 0x47.toByte() && i + 188 < data.size && data[i + 188] == 0x47.toByte()) return i
        }
        return 0
    }

    private fun mediaMime(data: ByteArray): String {
        if (data.size >= 8) {
            val type = String(data, 4, 4, Charsets.US_ASCII)
            if (type == "ftyp" || type == "styp" || type == "moof" || type == "sidx" ||
                type == "moov" || type == "mdat" || type == "free" || type == "emsg"
            ) {
                return "video/mp4"
            }
        }
        if (data.isNotEmpty() && data[0] == 0x47.toByte()) return "video/mp2t"
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

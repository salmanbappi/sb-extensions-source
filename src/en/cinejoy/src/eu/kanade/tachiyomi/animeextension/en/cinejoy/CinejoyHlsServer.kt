package eu.kanade.tachiyomi.animeextension.en.cinejoy

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

/**
 * Self-contained local HLS proxy for Cinejoy.
 *
 * Cinejoy's CDN (movieboxnoob) serves a master playlist whose video variants
 * and external audio renditions (declared with `#EXT-X-MEDIA:TYPE=AUDIO`) are
 * separate `.m3u8` playlists. Their media segments are named `*.html` and
 * returned with `Content-Type: text/html`, even though the bytes are
 * fragmented-MP4 (`moof`/`ftyp`). Players fetch these directly and choke on
 * the `text/html` typing, so nothing decodes.
 *
 * This proxy rewrites every playlist (video variant *and* each audio/subtitle
 * rendition) so their child playlists and segments are fetched back through
 * localhost, where we re-issue the upstream `Referer`/`User-Agent` and correct
 * the `Content-Type` on segment responses. Both the primary [Video.videoUrl]
 * and the entries in [Video.audioTracks]/[Video.subtitleTracks] are proxied,
 * which is the piece the video-only path was missing (hence: no audio).
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

    fun getProxyUrl(targetUrl: String, headers: Headers? = null): String {
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
        val ext = if (isM3u8(targetUrl)) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    /**
     * Routes the video playlist and every external audio/subtitle rendition of
     * each [Video] through the local proxy. Non-HLS entries are left untouched.
     */
    fun proxyVideos(videos: List<Video>): List<Video> = videos.map { video ->
        if (!videoNeedsProxy(video)) return@map video

        val proxiedUrl = if (isM3u8(video.videoUrl)) {
            getProxyUrl(video.videoUrl, video.headers)
        } else {
            video.videoUrl
        }

        val proxiedAudio = video.audioTracks.map { track ->
            if (isM3u8(track.url)) Track(getProxyUrl(track.url, video.headers), track.lang) else track
        }

        val proxiedSubs = video.subtitleTracks.map { track ->
            if (isM3u8(track.url)) Track(getProxyUrl(track.url, video.headers), track.lang) else track
        }

        Video(
            videoUrl = proxiedUrl,
            videoTitle = video.videoTitle,
            headers = video.headers,
            subtitleTracks = proxiedSubs,
            audioTracks = proxiedAudio,
        )
    }

    private fun videoNeedsProxy(video: Video): Boolean = isM3u8(video.videoUrl) ||
        video.audioTracks.any { isM3u8(it.url) } ||
        video.subtitleTracks.any { isM3u8(it.url) }

    private fun isM3u8(url: String): Boolean = url.contains(".m3u8", ignoreCase = true) ||
        url.contains("mpegurl", ignoreCase = true)

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
            val isM3u8Request = isM3u8(targetUrl) || path.contains("playlist.m3u8", ignoreCase = true)

            val targetHeaders = decodeHeaders(encodedHeaders)?.newBuilder() ?: Headers.Builder()

            // Forward a client Range header only for media segments, never for playlists.
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true) && !isM3u8Request) {
                        targetHeaders.set(name, value)
                    }
                }
            }

            val request = Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders)
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

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String) {
        val out = socket.getOutputStream()
        val isM3u8Response = isM3u8(targetUrl) ||
            response.header("Content-Type")?.contains("mpegurl", ignoreCase = true) == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8Response) {
            val bodyString = response.body.string()
            val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
            modifiedContentBytes = modifiedContent.toByteArray(Charsets.UTF_8)
        }

        val msg = response.message.ifBlank { if (response.code == 200) "OK" else "Error" }
        out.write("HTTP/1.1 ${response.code} $msg\r\n".toByteArray(Charsets.UTF_8))

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                name.equals("Content-Length", ignoreCase = true)
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray(Charsets.UTF_8))
        }

        if (isM3u8Response && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray(Charsets.UTF_8))
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(modifiedContentBytes)
        } else {
            val cl = response.body.contentLength()
            if (cl > 0) {
                out.write("Content-Length: $cl\r\n".toByteArray(Charsets.UTF_8))
            }
            // Segments are fragmented-MP4 mislabeled as text/html by the CDN.
            // Strip the html typing so the player relies on the fMP4 init map instead.
            val contentType = response.header("Content-Type") ?: "video/mp4"
            val mime = if (contentType.contains("html", ignoreCase = true)) "video/mp4" else contentType
            out.write("Content-Type: $mime\r\n".toByteArray(Charsets.UTF_8))
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))

            val inputStream: InputStream = response.body.byteStream()
            copyStream(inputStream, out)
        }
        out.flush()
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
    }

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)
        val headers = decodeHeaders(encodedHeaders)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                // Rewrite embedded URIs on tag lines: audio/subtitle renditions
                // (#EXT-X-MEDIA), init segments (#EXT-X-MAP) and keys (#EXT-X-KEY).
                if (trimmed.startsWith("#EXT-X-KEY") ||
                    trimmed.startsWith("#EXT-X-MAP") ||
                    trimmed.startsWith("#EXT-X-MEDIA")
                ) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrl(resolvedUri, headers)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                // Media line: a child playlist or a segment. Proxy either way.
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                builder.append(getProxyUrl(resolvedUri, headers))
            }
            builder.append("\n")
        }

        return builder.toString()
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

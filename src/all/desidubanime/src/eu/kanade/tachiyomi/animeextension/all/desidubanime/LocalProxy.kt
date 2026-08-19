package eu.kanade.tachiyomi.animeextension.all.desidubanime

import android.util.Base64
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.min

class LocalProxy(private val client: OkHttpClient) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    var port: Int = 0
        private set

    init {
        try {
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            executor.execute {
                while (serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {
                        // Server closed or accept error
                    }
                }
            }
        } catch (_: Exception) {
            // Failed to start local server
        }
    }

    private val wsBuffers = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Int, ByteArray>>()
    private val wsCounters = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private val wsActiveConnections = java.util.concurrent.ConcurrentHashMap<String, okhttp3.WebSocket>()

    fun getVirtualWsPlaylistUrl(wsEndpoint: String, headers: Headers? = null): String {
        val sessionId = java.util.UUID.randomUUID().toString().take(8)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val buffer = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
        wsCounters[sessionId] = counter
        wsBuffers[sessionId] = buffer

        val reqBuilder = Request.Builder().url(wsEndpoint)
        headers?.let { reqBuilder.headers(it) }
        val wsRequest = reqBuilder.build()

        val ws = client.newWebSocket(
            wsRequest,
            object : okhttp3.WebSocketListener() {
                override fun onMessage(webSocket: okhttp3.WebSocket, bytes: okio.ByteString) {
                    val idx = counter.getAndIncrement()
                    buffer[idx] = bytes.toByteArray()
                    if (buffer.size > 30) {
                        buffer.remove(idx - 30)
                    }
                }

                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                    wsActiveConnections.remove(sessionId)
                }

                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
                    wsActiveConnections.remove(sessionId)
                }
            },
        )
        wsActiveConnections[sessionId] = ws

        return "http://127.0.0.1:$port/virtual-ws/$sessionId/playlist.m3u8"
    }

    fun getProxyUrl(targetUrl: String, headers: Headers? = null): String {
        val encodedUrl = Base64.encodeToString(
            targetUrl.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(
            headersStr.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun handleSocket(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (path.startsWith("/virtual-ws/")) {
                handleVirtualWsSocket(socket, path)
                return
            }

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
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains("playlist.m3u8")

            val targetHeaders = Headers.Builder()
            if (encodedHeaders.isNotEmpty()) {
                val headersStr = String(
                    Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8,
                )
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders.set(headerParts[0].trim(), headerParts[1].trim())
                    }
                }
            }

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
        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            val bodyString = response.body.string()
            val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
            modifiedContentBytes = modifiedContent.toByteArray(Charsets.UTF_8)
        }

        out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray(Charsets.UTF_8))

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                (name.equals("Content-Length", ignoreCase = true) && isM3u8)
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray(Charsets.UTF_8))
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray(Charsets.UTF_8))
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray(Charsets.UTF_8))
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
            out.write(modifiedContentBytes)
        } else {
            out.write("Content-Type: video/mp2t\r\n".toByteArray(Charsets.UTF_8))
            out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))

            val rawBytes = response.body.bytes()
            val stripped = stripImageHeader(rawBytes)
            out.write(stripped)
        }
        out.flush()
    }

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrl(resolvedUri)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                builder.append(getProxyUrl(resolvedUri))
            }
            builder.append("\n")
        }

        return builder.toString()
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun stripImageHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data

        // PNG Check: 0x89, 'P', 'N', 'G'
        val isPng = data[0] == (-119).toByte() &&
            data[1] == 80.toByte() &&
            data[2] == 78.toByte() &&
            data[3] == 71.toByte()

        if (isPng) {
            var videoStart = -1
            val length = data.size - 4
            for (i in 0 until length) {
                // Look for IEND chunk (73, 69, 78, 68) + 4 bytes CRC
                if (data[i] == 73.toByte() &&
                    data[i + 1] == 69.toByte() &&
                    data[i + 2] == 78.toByte() &&
                    data[i + 3] == 68.toByte()
                ) {
                    videoStart = i + 8
                    break
                }
            }

            if (videoStart in 0 until data.size) {
                val tsData = data.copyOfRange(videoStart, data.size)
                val iMin = min(tsData.size - 188, 400)
                for (offset in 0 until iMin) {
                    if (tsData[offset] == 0x47.toByte()) {
                        return tsData.copyOfRange(offset, tsData.size)
                    }
                }
                return tsData
            }
        }

        // JPEG Check: 0xFF, 0xD8, 0xFF
        val isJpeg = data[0] == (-1).toByte() && data[1] == (-40).toByte() && data[2] == (-1).toByte()
        if (isJpeg) {
            val iMin = min(data.size - 188, 2048)
            for (offset in 2 until iMin) {
                if (data[offset] == 0x47.toByte() && data[offset + 188] == 0x47.toByte()) {
                    return data.copyOfRange(offset, data.size)
                }
            }
        }

        return data
    }

    private fun handleVirtualWsSocket(socket: Socket, path: String) {
        val out = socket.getOutputStream()
        try {
            when {
                path.endsWith("playlist.m3u8") -> {
                    val sessionId = path.substringAfter("/virtual-ws/").substringBefore("/")
                    serveVirtualPlaylist(out, sessionId)
                }

                path.contains("/segment/") -> {
                    val sessionId = path.substringAfter("/virtual-ws/").substringBefore("/")
                    val id = path.substringAfterLast("/").substringBefore(".ts").toIntOrNull() ?: 0
                    serveVirtualSegment(out, sessionId, id)
                }

                else -> sendError(socket, 404, "Not Found")
            }
        } catch (_: Exception) {
            try {
                sendError(socket, 500, "Virtual WS Error")
            } catch (_: Exception) {}
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun serveVirtualPlaylist(out: java.io.OutputStream, sessionId: String) {
        val counter = wsCounters[sessionId]?.get() ?: 0
        val startIdx = maxOf(0, counter - 5)
        val sb = StringBuilder().apply {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:3\n")
            append("#EXT-X-TARGETDURATION:6\n")
            append("#EXT-X-MEDIA-SEQUENCE:$startIdx\n")
            for (i in startIdx until counter) {
                append("#EXTINF:4.0,\n")
                append("http://127.0.0.1:$port/virtual-ws/$sessionId/segment/$i.ts\n")
            }
        }
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun serveVirtualSegment(out: java.io.OutputStream, sessionId: String, id: Int) {
        val buffer = wsBuffers[sessionId]
        val chunk = buffer?.get(id) ?: ByteArray(0)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${chunk.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
        if (chunk.isNotEmpty()) {
            val stripped = stripImageHeader(chunk)
            out.write(stripped)
        }
        out.flush()
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: text/plain\r\n".toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.UTF_8))
        out.write(message.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}

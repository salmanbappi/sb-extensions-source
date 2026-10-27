package eu.kanade.tachiyomi.animeextension.all.animex

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AnimexProxy(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.let { if (it.isClosed) 0 else it.localPort } ?: 0

    fun start() {
        if (running.get() && serverSocket?.isClosed == false) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            executor.execute {
                while (running.get() && serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(socket) }
                    } catch (_: Exception) {
                        if (serverSocket?.isClosed == true || !running.get()) break
                    }
                }
            }
        } catch (_: Exception) {
            running.set(false)
        }
    }

    fun getProxyUrl(url: String, headers: Headers? = null): String {
        start()
        val encodedUrl = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = encodeHeaders(headers)
        val path = if (url.contains(".m3u8") || url.contains(".txt")) "playlist.m3u8" else "segment.ts"
        val query = "url=$encodedUrl" + if (encodedHeaders != null) "&headers=$encodedHeaders" else ""
        return "http://127.0.0.1:$port/$path?$query"
    }

    fun getSubtitleProxyUrl(url: String, headers: Headers?): String {
        start()
        val encodedUrl = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = encodeHeaders(headers)
        val query = "url=$encodedUrl" + if (encodedHeaders != null) "&headers=$encodedHeaders" else ""
        return "http://127.0.0.1:$port/subtitle.vtt?$query"
    }

    private fun encodeHeaders(headers: Headers?): String? {
        if (headers == null || headers.size == 0) return null
        val map = mutableMapOf<String, String>()
        for (i in 0 until headers.size) {
            map[headers.name(i)] = headers.value(i)
        }
        return try {
            Base64.encodeToString(json.encodeToString(map).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeHeaders(encoded: String?): Headers {
        if (encoded.isNullOrEmpty()) {
            return Headers.Builder()
                .set("User-Agent", ANIMEX_API_UA)
                .set("Referer", "https://animex.one/")
                .build()
        }
        return try {
            val jsonStr = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val map = json.decodeFromString<Map<String, String>>(jsonStr)
            Headers.Builder().apply {
                for (entry in map.entries) {
                    set(entry.key, entry.value)
                }
            }.build()
        } catch (_: Exception) {
            Headers.Builder()
                .set("User-Agent", ANIMEX_API_UA)
                .set("Referer", "https://animex.one/")
                .build()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val firstLine = input.bufferedReader().readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val path = parts[1]
                routeRequest(path, output)
            }
        }
    }

    private fun routeRequest(path: String, output: OutputStream) {
        val uri = Uri.parse("http://127.0.0.1$path")
        val encodedUrl = uri.getQueryParameter("url") ?: return
        val encodedHeaders = uri.getQueryParameter("headers")
        val targetUrl = try {
            String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } catch (_: Exception) {
            return
        }
        val headers = decodeHeaders(encodedHeaders)

        try {
            when {
                path.contains("playlist.m3u8") -> servePlaylist(targetUrl, headers, encodedHeaders, output)
                path.contains("key.bin") -> serveKey(targetUrl, headers, output)
                path.contains("subtitle.vtt") -> serveText(targetUrl, headers, output, "text/vtt")
                else -> serveSegment(targetUrl, headers, encodedHeaders, output)
            }
        } catch (_: Exception) {
            try {
                output.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Exception) {}
        }
    }

    private fun getInternalProxyUrl(url: String, headersStr: String?, isKey: Boolean = false): String {
        val encoded = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val path = when {
            isKey || url.contains(".key") || url.contains("key.bin") -> "key.bin"
            url.contains(".m3u8") || url.contains(".txt") -> "playlist.m3u8"
            else -> "segment.ts"
        }
        val query = "url=$encoded" + if (!headersStr.isNullOrEmpty()) "&headers=$headersStr" else ""
        return "http://127.0.0.1:$port/$path?$query"
    }

    private val httpClient: OkHttpClient by lazy {
        client.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
    }

    private fun Response.silentClose() {
        try {
            close()
        } catch (_: Exception) {}
    }

    private fun fetchWithRetry(targetUrl: String, headers: Headers): Response {
        val fallbackHeaders = headers.newBuilder()
            .set("Referer", "https://animex.one/")
            .build()
        val noRefererHeaders = headers.newBuilder().removeAll("Referer").build()
        val httpUrl = targetUrl.replaceFirst("https://", "http://")

        val attempts = mutableListOf(
            Triple(targetUrl, headers, false),
            Triple(targetUrl, fallbackHeaders, false),
            Triple(targetUrl, noRefererHeaders, false),
            Triple(targetUrl, headers, true),
            Triple(targetUrl, fallbackHeaders, true),
            Triple(httpUrl, noRefererHeaders, true),
        )

        var lastResponse: Response? = null
        var lastError: Exception? = null
        for ((url, requestHeaders, useHttp1) in attempts) {
            try {
                val callClient = if (useHttp1) httpClient else client
                val req = okhttp3.Request.Builder().url(url).headers(requestHeaders).build()
                val response = callClient.newCall(req).execute()
                if (response.isSuccessful) {
                    lastResponse?.silentClose()
                    return response
                }
                lastResponse?.silentClose()
                lastResponse = response
            } catch (e: Exception) {
                lastError = e
            }
        }
        return lastResponse ?: throw lastError ?: java.io.IOException("All proxy fetch attempts failed for $targetUrl")
    }

    private fun servePlaylist(targetUrl: String, headers: Headers, encodedHeaders: String?, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, headers)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }

        val rawBytes = response.body.bytes()
        response.silentClose()

        val marker = "#EXTM3U".toByteArray(Charsets.US_ASCII)
        val markerIndex = findAsciiMarker(rawBytes, marker, rawBytes.size)
        val content = if (markerIndex > 0) {
            String(rawBytes, markerIndex, rawBytes.size - markerIndex, Charsets.UTF_8)
        } else {
            String(rawBytes, Charsets.UTF_8)
        }
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                uriRegex.find(trimmed)?.let { match ->
                    val uriValue = match.groupValues[1]
                    val rawResolved = when {
                        uriValue.startsWith("//") -> "https:$uriValue"
                        else -> targetUrl.toHttpUrl().resolve(uriValue)?.toString() ?: uriValue
                    }
                    val resolvedUri = if (rawResolved.startsWith("//")) "https:$rawResolved" else rawResolved
                    val isKeyLine = trimmed.contains("#EXT-X-KEY") || resolvedUri.contains(".key")
                    val proxiedUri = getInternalProxyUrl(resolvedUri, encodedHeaders, isKey = isKeyLine)
                    builder.append(trimmed.replace(uriValue, proxiedUri))
                } ?: builder.append(trimmed)
            } else {
                val rawResolved = when {
                    trimmed.startsWith("//") -> "https:$trimmed"
                    else -> targetUrl.toHttpUrl().resolve(trimmed)?.toString() ?: trimmed
                }
                val resolvedUri = if (rawResolved.startsWith("//")) "https:$rawResolved" else rawResolved
                val isKeyLine = resolvedUri.contains(".key")
                builder.append(getInternalProxyUrl(resolvedUri, encodedHeaders, isKey = isKeyLine))
            }
            builder.append("\n")
        }

        val bodyBytes = builder.toString().toByteArray()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
        output.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bodyBytes)
        output.flush()
    }

    private fun serveText(targetUrl: String, headers: Headers, output: OutputStream, contentType: String) {
        val response = fetchWithRetry(targetUrl, headers)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.silentClose()
            return
        }

        val bytes = response.body.bytes()
        response.silentClose()

        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Content-Type: $contentType\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun serveKey(targetUrl: String, headers: Headers, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, headers)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }

        val bytes = response.body.bytes()
        response.close()

        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Content-Type: application/octet-stream\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun serveSegment(targetUrl: String, headers: Headers, encodedHeaders: String?, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, headers)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
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

        if (looksLikePlaylist(headerBuffer)) {
            response.close()
            return servePlaylist(targetUrl, headers, encodedHeaders, output)
        }

        val sample = if (totalRead == headerBuffer.size) headerBuffer else headerBuffer.copyOf(totalRead)
        val skipBytes = detectSkipBytes(sample)
        val contentLength = body.contentLength()
        val payloadLength = if (contentLength > 0) contentLength - skipBytes else -1L

        val headerBuilder = StringBuilder("HTTP/1.1 200 OK\r\n")
        if (payloadLength >= 0) {
            headerBuilder.append("Content-Length: $payloadLength\r\n")
        }
        headerBuilder.append("Content-Type: video/mp2t\r\n")
        headerBuilder.append("Connection: close\r\n\r\n")
        output.write(headerBuilder.toString().toByteArray())

        if (totalRead > skipBytes) {
            output.write(sample, skipBytes, totalRead - skipBytes)
        }

        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
        response.close()
    }

    private fun looksLikePlaylist(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        val marker = "#EXTM3U".toByteArray(Charsets.US_ASCII)
        val extX = "#EXT-X".toByteArray(Charsets.US_ASCII)
        return findAsciiMarker(data, marker, minOf(data.size, 65536)) >= 0 ||
            findAsciiMarker(data, extX, minOf(data.size, 65536)) >= 0
    }

    private fun findAsciiMarker(data: ByteArray, marker: ByteArray, limit: Int): Int {
        val maxStart = minOf(data.size, limit) - marker.size
        if (maxStart < 0) return -1
        var i = 0
        while (i <= maxStart) {
            if (data[i] == marker[0]) {
                var j = 1
                while (j < marker.size && data[i + j] == marker[j]) j++
                if (j == marker.size) return i
            }
            i++
        }
        return -1
    }

    private fun detectSkipBytes(data: ByteArray): Int {
        if (data.size < 4) return 0

        val isPng = data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        val isGif = data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte()

        if (!isPng && !isJpeg && !isGif) return 0

        val maxScan = minOf(data.size, 131072)

        val ftyp = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte())
        val maxFtyp = minOf(data.size - ftyp.size, maxScan)
        for (i in 0..maxFtyp) {
            if (data[i] == ftyp[0] && data[i + 1] == ftyp[1] && data[i + 2] == ftyp[2] && data[i + 3] == ftyp[3]) {
                return if (i >= 4) i - 4 else i
            }
        }

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
                    if (data[j] == 0x47.toByte()) {
                        validCount++
                    }
                    j += 188
                }
                if (validCount >= 3) {
                    return i
                }
            }
        }
        return 0
    }
}

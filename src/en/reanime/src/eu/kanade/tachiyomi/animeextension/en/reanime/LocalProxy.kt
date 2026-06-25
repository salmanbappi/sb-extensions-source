package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Base64
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

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
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    fun getProxyUrl(targetUrl: String, headers: Headers?, pk: String? = null): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(headersStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cleanUrl = targetUrl.substringBefore("?")
        val isM3u8 = cleanUrl.endsWith(".m3u8") || cleanUrl.contains("mpegurl")
        val ext = if (isM3u8) "playlist.m3u8" else "segment.ts"
        val pkParam = if (pk != null) "&pk=$pk" else ""
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders$pkParam"
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
            val pk = httpUrl.queryParameter("pk")

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val cleanTargetUrl = targetUrl.substringBefore("?")
            val isM3u8Request = cleanTargetUrl.endsWith(".m3u8") || path.substringBefore("?").endsWith("playlist.m3u8")

            val targetHeaders = Headers.Builder()
            if (encodedHeaders.isNotEmpty()) {
                val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders.set(headerParts[0].trim(), headerParts[1].trim())
                    }
                }
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
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
                sendResponse(socket, response, targetUrl, encodedHeaders, pk)
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

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String, pk: String?) {
        val out = socket.getOutputStream()
        val contentType = response.header("Content-Type")?.lowercase() ?: ""
        val cleanTargetUrl = targetUrl.substringBefore("?")
        val isM3u8 = cleanTargetUrl.endsWith(".m3u8") || contentType.contains("mpegurl") || contentType.contains("mpeg-url")

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            try {
                val rawBytes = response.body.bytes()
                val decryptedBodyString = if (pk != null && rawBytes.isNotEmpty()) {
                    val checkLen = minOf(rawBytes.size, 16)
                    val firstBytesStr = String(rawBytes, 0, checkLen, Charsets.UTF_8)
                    if (!firstBytesStr.startsWith("#EXTM3U")) {
                        val pkBytes = Base64.decode(pk, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                        val encryptedBytes = Base64.decode(rawBytes, Base64.DEFAULT)
                        val decryptedBytes = ByteArray(encryptedBytes.size)
                        for (i in encryptedBytes.indices) {
                            decryptedBytes[i] = (encryptedBytes[i].toInt() xor pkBytes[i % pkBytes.size].toInt()).toByte()
                        }
                        String(decryptedBytes, Charsets.UTF_8)
                    } else {
                        String(rawBytes, Charsets.UTF_8)
                    }
                } else {
                    String(rawBytes, Charsets.UTF_8)
                }

                val modifiedContent = processM3u8(decryptedBodyString, targetUrl, encodedHeaders, pk)
                modifiedContentBytes = modifiedContent.toByteArray()
            } catch (e: Exception) {}
        }

        out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())

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
            out.write("$name: $value\r\n".toByteArray())
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray())
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
        } else {
            val contentType = response.header("Content-Type") ?: "video/mp2t"
            out.write("Content-Type: $contentType\r\n".toByteArray())
        }
        out.write("Connection: close\r\n\r\n".toByteArray())

        if (isM3u8 && modifiedContentBytes != null) {
            out.write(modifiedContentBytes)
        } else {
            response.body.byteStream().use { input ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
            }
        }
        out.flush()
    }

    private val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String, pk: String?): String {
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
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders, pk)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                builder.append(getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders, pk))
            }
            builder.append("\n")
        }

        return builder.toString()
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String, pk: String?): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val cleanUrl = targetUrl.substringBefore("?")
        val isM3u8 = cleanUrl.endsWith(".m3u8") || cleanUrl.contains("mpegurl")
        val ext = if (isM3u8) "playlist.m3u8" else "segment.ts"
        val pkParam = if (pk != null) "&pk=$pk" else ""
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders$pkParam"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: text/plain\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(message.toByteArray())
        out.flush()
    }
}

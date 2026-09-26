package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AnimePaheHlsServer {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var isRunning = false

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var mp4Client: OkHttpClient? = null

    private val mp4Headers = ConcurrentHashMap<String, Headers>()

    private val hlsAttributeRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private data class HlsKey(val url: String, val iv: String?)

    fun start() {
        if (isRunning && serverSocket?.isClosed == false) return
        synchronized(this) {
            if (isRunning && serverSocket?.isClosed == false) return
            try {
                serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                port = serverSocket!!.localPort
                isRunning = true
                executor.execute {
                    while (isRunning && serverSocket?.isClosed == false) {
                        try {
                            val socket = serverSocket!!.accept()
                            executor.execute { handleSocket(socket) }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
    }

    fun processVideoList(client: OkHttpClient, videos: List<Video>): List<Video> {
        this.client = client
        ensureStarted()
        return videos.map { video ->
            if (video.videoUrl.contains(".m3u8", ignoreCase = true)) {
                video.copyWithLocalUrl(createLocalM3u8Url(video.videoUrl))
            } else {
                video
            }
        }
    }

    fun processMp4VideoList(client: OkHttpClient, videos: List<Video>): List<Video> {
        mp4Client = client
        ensureStarted()
        return videos.map { video ->
            val localUrl = createLocalMp4Url(video.videoUrl)
            mp4Headers[video.videoUrl] = video.headers ?: Headers.Builder().build()
            video.copyWithLocalMp4Url(localUrl)
        }
    }

    @Synchronized
    private fun ensureStarted() {
        if (!isRunning || serverSocket == null || serverSocket?.isClosed == true) {
            start()
        }
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.setSoLinger(true, 5)

            val input = socket.getInputStream()
            val reader = input.bufferedReader(Charsets.UTF_8)
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return

            val pathWithQuery = requestParts[1]

            val requestHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    requestHeaders[k] = v
                }
            }

            val path = pathWithQuery.substringBefore("?")
            val queryString = pathWithQuery.substringAfter("?", "")
            val queryParams = parseQueryParams(queryString)

            val output = socket.getOutputStream()

            when {
                path.startsWith("/m3u8") -> handleM3u8(queryParams, requestHeaders, output)
                path.startsWith("/segment") -> handleSegment(queryParams, requestHeaders, output)
                path.startsWith("/mp4") -> handleMp4(queryParams, requestHeaders, output)
                else -> sendResponse(output, 404, "Not Found", "text/plain", "Not Found".toByteArray())
            }
            output.flush()
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (param in query.split("&")) {
            val idx = param.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(param.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(param.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun handleM3u8(params: Map<String, String>, headers: Map<String, String>, out: OutputStream) {
        val url = params["url"] ?: run {
            sendResponse(out, 400, "Bad Request", "text/plain", "Missing url".toByteArray())
            return
        }

        try {
            val forwardHeaders = buildForwardHeaders(headers)
            val playlist = fetchString(url, forwardHeaders)
            val rewritten = rewritePlaylist(playlist, url)
            sendResponse(out, 200, "OK", "application/vnd.apple.mpegurl", rewritten.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Server Error", "text/plain", "Error: ${e.message}".toByteArray())
        }
    }

    private fun handleSegment(params: Map<String, String>, headers: Map<String, String>, out: OutputStream) {
        val url = params["url"] ?: run {
            sendResponse(out, 400, "Bad Request", "text/plain", "Missing url".toByteArray())
            return
        }

        try {
            val forwardHeaders = buildForwardHeaders(headers)
            val keyUrl = params["key"]
            val iv = params["iv"]
            val data = fetchSegment(url, forwardHeaders, keyUrl, iv)
            sendResponse(out, 200, "OK", "video/mp2t", data)
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Server Error", "text/plain", "Error: ${e.message}".toByteArray())
        }
    }

    private fun handleMp4(params: Map<String, String>, headers: Map<String, String>, out: OutputStream) {
        val url = params["url"] ?: run {
            sendResponse(out, 400, "Bad Request", "text/plain", "Missing url".toByteArray())
            return
        }

        try {
            val reqHeadersBuilder = Headers.Builder().apply {
                mp4Headers[url]?.let { sourceHeaders ->
                    for (i in 0 until sourceHeaders.size) {
                        add(sourceHeaders.name(i), sourceHeaders.value(i))
                    }
                }
                headers["range"]?.let { set("Range", it) }
            }

            val response = requireMp4Client().newCall(
                Request.Builder().url(url).headers(reqHeadersBuilder.build()).build(),
            ).execute()

            response.use { resp ->
                val code = resp.code
                val statusText = if (code == 206) {
                    "Partial Content"
                } else if (code == 200) {
                    "OK"
                } else {
                    resp.message
                }
                val contentType = resp.header("Content-Type") ?: "video/mp4"
                val contentLength = resp.header("Content-Length")
                val acceptRanges = resp.header("Accept-Ranges")
                val contentRange = resp.header("Content-Range")

                val headerText = buildString {
                    append("HTTP/1.1 $code $statusText\r\n")
                    append("Content-Type: $contentType\r\n")
                    if (contentLength != null) append("Content-Length: $contentLength\r\n")
                    if (acceptRanges != null) append("Accept-Ranges: $acceptRanges\r\n")
                    if (contentRange != null) append("Content-Range: $contentRange\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(headerText.toByteArray(Charsets.UTF_8))

                resp.body?.byteStream()?.use { stream ->
                    stream.copyTo(out)
                }
            }
        } catch (e: Exception) {
            sendResponse(out, 500, "Internal Server Error", "text/plain", "Error: ${e.message}".toByteArray())
        }
    }

    private fun sendResponse(out: OutputStream, code: Int, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(body)
    }

    private fun buildForwardHeaders(headers: Map<String, String>): Headers = Headers.Builder().apply {
        headers.forEach { (key, value) ->
            when (key) {
                "user-agent", "referer", "origin", "accept", "accept-language",
                "accept-encoding", "cache-control", "pragma",
                -> add(key, value)
            }
        }
    }.build()

    private fun fetchString(url: String, headers: Headers): String = requireClient().newCall(
        Request.Builder().url(url).headers(headers).build(),
    ).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch playlist: ${response.code}")
        }
        response.body?.string() ?: ""
    }

    private fun fetchSegment(url: String, headers: Headers, keyUrl: String?, iv: String?): ByteArray {
        val rawData = fetchBytes(url, headers)
        return if (keyUrl.isNullOrBlank()) {
            rawData
        } else {
            val ivHex = iv ?: throw IOException("Missing AES-128 IV for encrypted segment")
            decryptAes128Cbc(rawData, fetchBytes(keyUrl, headers), ivHex)
        }
    }

    private fun fetchBytes(url: String, headers: Headers): ByteArray = requireClient().newCall(
        Request.Builder().url(url).headers(headers).build(),
    ).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("Failed to fetch resource: ${response.code}")
        }
        response.body?.bytes() ?: ByteArray(0)
    }

    private fun requireClient(): OkHttpClient = client ?: throw IOException("AnimePahe HLS server is not initialized")
    private fun requireMp4Client(): OkHttpClient = mp4Client ?: throw IOException("AnimePahe MP4 server is not initialized")

    private fun createLocalM3u8Url(m3u8Url: String): String {
        val encodedUrl = URLEncoder.encode(m3u8Url, Charsets.UTF_8.name())
        return "http://127.0.0.1:$port/m3u8?url=$encodedUrl"
    }

    private fun createLocalMp4Url(mp4Url: String): String {
        val encodedUrl = URLEncoder.encode(mp4Url, Charsets.UTF_8.name())
        return "http://127.0.0.1:$port/mp4?url=$encodedUrl"
    }

    private fun Video.copyWithLocalUrl(localUrl: String): Video = Video(
        videoUrl = localUrl,
        videoTitle = videoTitle,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        memo = memo,
        initialized = true,
    )

    private fun Video.copyWithLocalMp4Url(localUrl: String): Video = Video(
        videoUrl = localUrl,
        videoTitle = videoTitle,
        headers = headers,
        preferred = preferred,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
        timestamps = timestamps,
        mpvArgs = mpvArgs,
        ffmpegStreamArgs = ffmpegStreamArgs,
        ffmpegVideoArgs = ffmpegVideoArgs,
        memo = memo,
        initialized = true,
    )

    private fun rewritePlaylist(content: String, originalUrl: String): String {
        val baseHttpUrl = originalUrl.toHttpUrlOrNull()
        val modifiedLines = mutableListOf<String>()
        var mediaSequence = 0L
        var segmentSequence = 0L
        var currentKey: HlsKey? = null

        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(":").trim().toLongOrNull() ?: mediaSequence
                    segmentSequence = mediaSequence
                    modifiedLines.add(line)
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val attributes = parseHlsAttributes(line)
                    when (attributes["METHOD"]?.uppercase()) {
                        "AES-128" -> {
                            val keyUri = attributes["URI"]
                            if (keyUri.isNullOrBlank()) {
                                currentKey = null
                                modifiedLines.add(line)
                            } else {
                                currentKey = HlsKey(
                                    url = resolveHlsUrl(baseHttpUrl, keyUri),
                                    iv = attributes["IV"]?.normalizeHlsIv(),
                                )
                            }
                        }

                        else -> {
                            currentKey = null
                            modifiedLines.add(line)
                        }
                    }
                }

                line.startsWith("#") || line.isBlank() -> modifiedLines.add(line)

                else -> {
                    val resolvedUrl = resolveHlsUrl(baseHttpUrl, line)
                    if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
                        modifiedLines.add(createLocalM3u8Url(resolvedUrl))
                    } else {
                        modifiedLines.add(createLocalSegmentUrl(resolvedUrl, currentKey, segmentSequence))
                        segmentSequence++
                    }
                }
            }
        }

        return modifiedLines.joinToString("\n")
    }

    private fun parseHlsAttributes(line: String): Map<String, String> = hlsAttributeRegex.findAll(line.substringAfter(":")).associate {
        it.groupValues[1] to it.groupValues[2].trim('"')
    }

    private fun resolveHlsUrl(baseHttpUrl: HttpUrl?, uri: String): String = baseHttpUrl?.resolve(uri)?.toString() ?: uri

    private fun createLocalSegmentUrl(segmentUrl: String, key: HlsKey?, sequence: Long): String {
        val encodedUrl = URLEncoder.encode(segmentUrl, Charsets.UTF_8.name())
        return buildString {
            append("http://127.0.0.1:$port/segment?url=$encodedUrl")
            if (key != null) {
                append("&key=")
                append(URLEncoder.encode(key.url, Charsets.UTF_8.name()))
                append("&iv=")
                append(URLEncoder.encode(key.iv ?: sequence.toHlsIv(), Charsets.UTF_8.name()))
            }
        }
    }

    private fun decryptAes128Cbc(data: ByteArray, key: ByteArray, iv: String): ByteArray {
        if (key.size != 16) {
            throw IOException("Invalid AES-128 key length: ${key.size}")
        }

        val normalizedIv = iv.normalizeHlsIv()
        if (normalizedIv.length != 32) {
            throw IOException("Invalid AES-128 IV length: ${normalizedIv.length}")
        }

        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(normalizedIv.hexToByteArray()),
            )
            cipher.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("Failed to decrypt AES-128 segment", e)
        } catch (e: NumberFormatException) {
            throw IOException("Invalid AES-128 IV", e)
        }
    }

    private fun Long.toHlsIv(): String = toString(16).padStart(32, '0')

    private fun String.normalizeHlsIv(): String = removePrefix("0x")
        .removePrefix("0X")
        .padStart(32, '0')

    private fun String.hexToByteArray(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

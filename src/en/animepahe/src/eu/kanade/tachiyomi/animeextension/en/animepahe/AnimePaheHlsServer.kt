package eu.kanade.tachiyomi.animeextension.en.animepahe

import eu.kanade.tachiyomi.animesource.model.Video
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object AnimePaheHlsServer : NanoHTTPD(0) {

    @Volatile private var hlsClient: OkHttpClient? = null
    @Volatile private var mp4Client: OkHttpClient? = null
    @Volatile private var running = false

    private val hlsHeaders = ConcurrentHashMap<String, Pair<String, String>>()
    private val mp4Headers = ConcurrentHashMap<String, Headers>()

    @Volatile private var activeCookies = ""
    @Volatile private var activeUserAgent = ""

    private val hlsAttributeRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    private data class HlsKey(val url: String, val iv: String?)

    @Synchronized
    private fun ensureStarted() {
        if (!running) {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            running = true
        }
    }

    fun processVideoList(client: OkHttpClient, videos: List<Video>): List<Video> {
        hlsClient = client
        ensureStarted()
        return videos.map { video ->
            if (video.videoUrl.contains(".m3u8", ignoreCase = true)) {
                val cookies = video.headers?.get("Cookie") ?: ""
                val ua = video.headers?.get("User-Agent") ?: ""
                hlsHeaders[video.videoUrl] = Pair(cookies, ua)
                video.copy(videoUrl = createLocalUrl("/m3u8", video.videoUrl))
            } else {
                video
            }
        }
    }

    fun processMp4VideoList(client: OkHttpClient, videos: List<Video>): List<Video> {
        mp4Client = client
        ensureStarted()
        return videos.map { video ->
            val localUrl = createLocalUrl("/mp4", video.videoUrl)
            mp4Headers[video.videoUrl] = video.headers ?: Headers.Builder().build()
            video.copy(videoUrl = localUrl)
        }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.uri.startsWith("/m3u8") -> handleM3u8(session)
        session.uri.startsWith("/segment") -> handleSegment(session)
        session.uri.startsWith("/mp4") -> handleMp4(session)
        else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    private fun handleM3u8(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        return try {
            hlsHeaders[url]?.let { (cookies, ua) ->
                activeCookies = cookies
                activeUserAgent = ua
            }
            val headers = Headers.Builder().apply {
                if (activeUserAgent.isNotBlank()) set("User-Agent", activeUserAgent)
                if (activeCookies.isNotBlank()) set("Cookie", activeCookies)
                set("Referer", "https://kwik.cx/")
            }.build()
            val playlist = fetchString(requireHlsClient(), url, headers)
            val rewritten = rewritePlaylist(playlist, url)
            newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", rewritten)
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    private fun handleSegment(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        return try {
            val headers = Headers.Builder().apply {
                if (activeUserAgent.isNotBlank()) set("User-Agent", activeUserAgent)
                if (activeCookies.isNotBlank()) set("Cookie", activeCookies)
                set("Referer", "https://kwik.cx/")
            }.build()
            val keyUrl = session.parameters["key"]?.firstOrNull()
            val iv = session.parameters["iv"]?.firstOrNull()
            val data = fetchSegment(url, headers, keyUrl, iv)
            newChunkedResponse(Status.OK, "video/mp2t", ByteArrayInputStream(data))
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    private fun handleMp4(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url")
        return try {
            val req = Request.Builder().url(url).headers(relayHeaders(session)).apply {
                session.headers["range"]?.let { header("Range", it) }
            }.build()
            val upstream = requireMp4Client().newCall(req).execute()
            val body = upstream.body
            val contentType = upstream.header("Content-Type") ?: "video/mp4"
            val contentLength = upstream.header("Content-Length")?.toLongOrNull() ?: -1L
            val status = Status.lookup(upstream.code) ?: Status.OK
            val stream = object : FilterInputStream(body.byteStream()) {
                override fun close() { try { super.close() } finally { upstream.close() } }
            }
            val resp = if (contentLength >= 0) newFixedLengthResponse(status, contentType, stream, contentLength)
            else newChunkedResponse(status, contentType, stream)
            upstream.header("Accept-Ranges")?.let { resp.addHeader("Accept-Ranges", it) }
            upstream.header("Content-Range")?.let { resp.addHeader("Content-Range", it) }
            resp
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "Error")
        }
    }

    private fun relayHeaders(session: IHTTPSession): Headers = Headers.Builder().apply {
        mp4Headers[session.parameters["url"]?.firstOrNull() ?: ""]?.let { sourceHeaders ->
            for (index in 0 until sourceHeaders.size) {
                add(sourceHeaders.name(index), sourceHeaders.value(index))
            }
        }
        session.headers["range"]?.let { set("Range", it) }
    }.build()

    private fun createLocalUrl(path: String, originalUrl: String): String {
        val encoded = URLEncoder.encode(originalUrl, "UTF-8")
        return "http://localhost:$listeningPort$path?url=$encoded"
    }

    private fun createSegmentUrl(segmentUrl: String, key: HlsKey?, seq: Long): String {
        val encoded = URLEncoder.encode(segmentUrl, "UTF-8")
        return buildString {
            append("http://localhost:$listeningPort/segment?url=$encoded")
            if (key != null) {
                append("&key=").append(URLEncoder.encode(key.url, "UTF-8"))
                append("&iv=").append(URLEncoder.encode(key.iv ?: seq.toHlsIv(), "UTF-8"))
            }
        }
    }

    private fun fetchString(client: OkHttpClient, url: String, headers: Headers): String =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Upstream ${r.code}")
            r.body.string()
        }

    private fun fetchBytes(client: OkHttpClient, url: String, headers: Headers): ByteArray =
        client.newCall(Request.Builder().url(url).headers(headers).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Upstream ${r.code}")
            r.body.bytes()
        }

    private fun fetchSegment(url: String, headers: Headers, keyUrl: String?, iv: String?): ByteArray {
        val client = requireHlsClient()
        val raw = fetchBytes(client, url, headers)
        return if (keyUrl.isNullOrBlank()) raw
        else decryptAes128(raw, fetchBytes(client, keyUrl, headers), iv ?: throw IOException("Missing IV"))
    }

    private fun rewritePlaylist(content: String, originalUrl: String): String {
        val base = originalUrl.toHttpUrlOrNull()
        val lines = mutableListOf<String>()
        var seq = 0L
        var segSeq = seq
        var key: HlsKey? = null

        content.lines().forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    seq = line.substringAfter(":").trim().toLongOrNull() ?: seq
                    segSeq = seq
                    lines += line
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = hlsAttributeRegex.findAll(line.substringAfter(":"))
                        .associate { it.groupValues[1] to it.groupValues[2].trim('"') }
                    when (attrs["METHOD"]?.uppercase()) {
                        "AES-128" -> {
                            val keyUri = attrs["URI"]
                            key = if (keyUri.isNullOrBlank()) null
                            else HlsKey(resolveUrl(base, keyUri), attrs["IV"]?.normalizeIv())
                        }
                        else -> { key = null; lines += line }
                    }
                }
                line.startsWith("#") || line.isBlank() -> lines += line
                else -> {
                    val resolved = resolveUrl(base, line)
                    lines += if (resolved.contains(".m3u8", true)) createLocalUrl("/m3u8", resolved)
                    else { val s = createSegmentUrl(resolved, key, segSeq++); s }
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun resolveUrl(base: HttpUrl?, uri: String): String = base?.resolve(uri)?.toString() ?: uri

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: String): ByteArray {
        if (key.size != 16) throw IOException("Bad key length: ${key.size}")
        val ivNorm = iv.normalizeIv()
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").also {
                it.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivNorm.hexToBytes()))
            }.doFinal(data)
        } catch (e: GeneralSecurityException) {
            throw IOException("AES decrypt failed", e)
        }
    }

    private fun requireHlsClient() = hlsClient ?: throw IOException("HLS client not set")
    private fun requireMp4Client() = mp4Client ?: throw IOException("MP4 client not set")

    private fun Long.toHlsIv() = toString(16).padStart(32, '0')
    private fun String.normalizeIv() = removePrefix("0x").removePrefix("0X").padStart(32, '0')
    private fun String.hexToBytes() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

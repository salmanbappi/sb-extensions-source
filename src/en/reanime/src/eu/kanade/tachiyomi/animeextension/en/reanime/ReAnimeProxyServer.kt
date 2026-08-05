package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Base64
import android.webkit.CookieManager
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Local proxy that makes ReAnime/FlixCloud HLS streams playable by reversing the
 * client-side obfuscation that the embed player applies:
 *
 * - Manifests (master.m3u8 / video.m3u8) are served base64-encoded and then
 *   XOR-encrypted with the 32-byte PK key derived from the WASM interpreter.
 *   The proxy base64-decodes, XOR-decrypts and rewrites every URI to point back
 *   at itself so sub-playlists and segments keep getting decrypted.
 * - Segments are TS data wrapped in a WEBP (`RIFF....WEBP`, 12 bytes) or PNG
 *   (8 bytes) container, XOR'd with a fixed 16-byte key unless the payload
 *   already starts with the TS sync byte 0x47 ('G').
 */
object ReAnimeProxyServer : NanoHTTPD(0) {

    private const val MIME_MPEGURL = "application/vnd.apple.mpegurl"
    private const val MIME_MP2T = "video/mp2t"
    private const val MIME_OCTET = "application/octet-stream"
    private const val MIME_SRT = "application/x-subrip"
    private const val MIME_VTT = "text/vtt"
    private const val ORIGIN = "https://flixcloud.cc"

    private val segmentXorKey = ubyteArrayOf(
        157u, 42u, 241u, 71u, 179u, 142u, 92u, 112u,
        166u, 25u, 228u, 59u, 216u, 98u, 15u, 197u,
    ).toByteArray()

    @Volatile
    private var isRunning = false

    @Volatile
    private var client: OkHttpClient? = null

    val port: Int
        get() = super.getListeningPort()

    override fun start() {
        super.start()
        isRunning = true
    }

    override fun stop() {
        super.stop()
        isRunning = false
    }

    fun ensureStarted(client: OkHttpClient) {
        if (this.client == null) {
            val builder = client.newBuilder().cache(null)
            builder.interceptors().removeAll { it is CloudflareInterceptor }
            this.client = builder.build()
        }
        if (!isRunning) {
            try {
                start()
            } catch (_: Exception) {}
        }
    }

    fun proxyUrl(target: String, pk: ByteArray, embedUrl: String): String = proxyUrl(target, pk.toHex(), embedUrl)

    private fun proxyUrl(target: String, pkHex: String, embedUrl: String): String {
        val encUrl = URLEncoder.encode(target, StandardCharsets.UTF_8.name())
        val encRef = URLEncoder.encode(embedUrl, StandardCharsets.UTF_8.name())
        return "http://127.0.0.1:$port/proxy?url=$encUrl&pk=$pkHex&ref=$encRef"
    }

    /**
     * Returns a proxy URL for a subtitle file (SRT/VTT) that only needs the proper
     * Origin/Referer headers — no manifest decryption or segment unwrapping.
     */
    fun subtitleProxyUrl(target: String, embedUrl: String): String {
        val encUrl = URLEncoder.encode(target, StandardCharsets.UTF_8.name())
        val encRef = URLEncoder.encode(embedUrl, StandardCharsets.UTF_8.name())
        return "http://127.0.0.1:$port/proxy?url=$encUrl&ref=$encRef"
    }

    override fun serve(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        val pkHex = session.parameters["pk"]?.firstOrNull()
        val refererParam = session.parameters["ref"]?.firstOrNull()

        val client = this.client
            ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server client not initialized")

        val pk = try {
            pkHex?.hexToByteArray()
        } catch (_: Exception) {
            null
        }

        val referer = refererParam ?: "$ORIGIN/"

        return try {
            val request = buildUpstreamRequest(url, referer)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    val status = when (code) {
                        403 -> Status.FORBIDDEN
                        404 -> Status.NOT_FOUND
                        else -> Status.lookup(code) ?: Status.INTERNAL_ERROR
                    }
                    return newFixedLengthResponse(
                        status,
                        MIME_PLAINTEXT,
                        "Upstream HTTP $code",
                    )
                }
                val body = response.body?.bytes()
                    ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty upstream body")
                handleBody(url, body, pk, pkHex, referer)
            }
        } catch (e: Throwable) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleBody(url: String, body: ByteArray, pk: ByteArray?, pkHex: String?, referer: String): Response {
        val lower = url.lowercase(Locale.US)

        // Subtitle text files — pass through as-is with correct MIME type
        if (lower.contains(".srt") || lower.contains(".vtt")) {
            val mime = if (lower.contains(".vtt")) MIME_VTT else MIME_SRT
            return textResponse(mime, String(body, StandardCharsets.UTF_8))
        }

        // Segments: image-wrapped TS
        if (lower.contains(".webp") || lower.contains(".png")) {
            return handleSegment(url, body, referer)
        }

        // Manifests
        if (lower.contains(".m3u8")) {
            if (pk != null && pkHex != null) {
                decryptManifest(body, pk)?.let { plain ->
                    return textResponse(MIME_MPEGURL, rewritePlaylist(plain, url, pkHex, referer))
                }
            }
            val rawText = String(body, StandardCharsets.UTF_8)
            if (rawText.trimStart().startsWith("#EXTM3U")) {
                return textResponse(MIME_MPEGURL, rewritePlaylist(rawText, url, pkHex ?: "", referer))
            }
        }

        // Fallback sniffing for other paths (keys, subtitle playlists, etc.)
        if (pk != null && pkHex != null) {
            decryptManifest(body, pk)?.let { plain ->
                return textResponse(MIME_MPEGURL, rewritePlaylist(plain, url, pkHex, referer))
            }
        }
        return handleSegment(url, body, referer)
    }

    /**
     * Manifests are base64 text XOR'd with the PK key. Returns the plaintext
     * playlist or null when the body isn't an encrypted manifest.
     */
    private fun decryptManifest(body: ByteArray, pk: ByteArray): String? {
        val rawStr = String(body, StandardCharsets.UTF_8).trim()
        if (rawStr.startsWith("#EXTM3U")) {
            return rawStr
        }
        val decoded = safeDecodeB64(rawStr) ?: return null
        if (decoded.isEmpty()) return null
        val plain = xorBytes(decoded, pk)
        val text = String(plain, StandardCharsets.UTF_8)
        return if (text.trimStart().startsWith("#EXTM3U")) text else null
    }

    private fun safeDecodeB64(str: String): ByteArray? {
        val clean = str.trim()
        if (clean.isEmpty()) return null
        return try {
            Base64.decode(clean, Base64.DEFAULT)
        } catch (_: Exception) {
            try {
                Base64.decode(clean, Base64.URL_SAFE)
            } catch (_: Exception) {
                val normalized = clean.replace('-', '+').replace('_', '/')
                try {
                    Base64.decode(normalized, Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun unwrapSegmentBytes(body: ByteArray): ByteArray {
        var offset = -1
        var needsXor = true
        if (body.size >= 12 &&
            body[0] == 'R'.code.toByte() && body[1] == 'I'.code.toByte() &&
            body[2] == 'F'.code.toByte() && body[3] == 'F'.code.toByte() &&
            body[8] == 'W'.code.toByte() && body[9] == 'E'.code.toByte() &&
            body[10] == 'B'.code.toByte() && body[11] == 'P'.code.toByte()
        ) {
            offset = 12
            needsXor = body.size <= 12 || body[12] != 'G'.code.toByte()
        } else if (body.size >= 8 &&
            body[0] == 0x89.toByte() && body[1] == 'P'.code.toByte() &&
            body[2] == 'N'.code.toByte() && body[3] == 'G'.code.toByte() &&
            body[4] == 13.toByte() && body[5] == 10.toByte() &&
            body[6] == 26.toByte() && body[7] == 10.toByte()
        ) {
            offset = 8
            needsXor = body.size <= 8 || body[8] != 'G'.code.toByte()
        }

        if (offset >= 0) {
            val unwrapped = body.copyOfRange(offset, body.size)
            if (needsXor) {
                var j = 0
                for (i in unwrapped.indices) {
                    unwrapped[i] = (unwrapped[i].toInt() xor (segmentXorKey[j and 15].toInt() and 0xff)).toByte()
                    j++
                }
            }
            return unwrapped
        }
        return body
    }

    /**
     * Segments are TS data wrapped in a WEBP or PNG container, optionally XOR'd
     * with a fixed 16-byte key. Automatically fetches and appends the corresponding
     * audio TS segment when servicing demuxed video segment requests.
     */
    private fun handleSegment(url: String, body: ByteArray, referer: String): Response {
        val videoTs = unwrapSegmentBytes(body)

        if (url.contains("/seg-") && url.contains("-v1-a0.")) {
            val audioUrl = url.replace(Regex("""/seg-(\d+)-f(\d+)-v\d+-a0\."""), "/audio/seg-$1-f$2-a0-a1.")
            if (audioUrl != url) {
                try {
                    val client = this.client
                    if (client != null) {
                        val request = buildUpstreamRequest(audioUrl, referer)
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val audioBody = response.body?.bytes()
                                if (audioBody != null && audioBody.isNotEmpty()) {
                                    val audioTs = unwrapSegmentBytes(audioBody)
                                    val combined = ByteArray(videoTs.size + audioTs.size)
                                    System.arraycopy(videoTs, 0, combined, 0, videoTs.size)
                                    System.arraycopy(audioTs, 0, combined, videoTs.size, audioTs.size)
                                    return bytesResponse(combined, MIME_MP2T)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        return bytesResponse(videoTs, MIME_MP2T)
    }

    /**
     * Rewrites every URI in the playlist (sub-playlist variants, EXT-X-KEY,
     * EXT-X-MEDIA and segment lines) to point back at this proxy.
     */
    private fun rewritePlaylist(content: String, originalUrl: String, pkHex: String, referer: String): String {
        val base = originalUrl.toHttpUrlOrNull()
        val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
        val hasExternalAudio = content.contains("#EXT-X-MEDIA:TYPE=AUDIO")
        return content.lines().joinToString("\n") { line ->
            var trimmed = line.trim()
            if (hasExternalAudio && trimmed.startsWith("#EXT-X-STREAM-INF")) {
                trimmed = trimmed.replace(Regex("""mp4a\.[^",]+,"""), "")
                    .replace(Regex(""",\s*mp4a\.[^",]+"""), "")
            }
            when {
                trimmed.isEmpty() -> line

                trimmed.startsWith("#") -> {
                    val match = uriRegex.find(trimmed)
                    if (match != null) {
                        val uriValue = match.groupValues[1]
                        val resolved = base?.resolve(uriValue)?.toString() ?: uriValue
                        trimmed.replace(uriValue, proxyUrl(resolved, pkHex, referer))
                    } else {
                        trimmed
                    }
                }

                else -> {
                    val resolved = base?.resolve(trimmed)?.toString() ?: trimmed
                    proxyUrl(resolved, pkHex, referer)
                }
            }
        }
    }

    private fun buildUpstreamRequest(url: String, referer: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .header("Origin", ORIGIN)
            .header("Referer", referer)

        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)
            if (!cookies.isNullOrBlank()) {
                builder.header("Cookie", cookies)
            }
        } catch (_: Throwable) {}

        return builder.build()
    }

    private fun xorBytes(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        val result = ByteArray(length / 2)
        for (i in 0 until length step 2) {
            result[i / 2] = substring(i, i + 2).toInt(16).toByte()
        }
        return result
    }

    private fun textResponse(mimeType: String, text: String): Response = newFixedLengthResponse(Status.OK, mimeType, text)

    private fun bytesResponse(data: ByteArray, mimeType: String): Response = newFixedLengthResponse(Status.OK, mimeType, ByteArrayInputStream(data), data.size.toLong())
}

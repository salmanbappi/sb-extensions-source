package eu.kanade.tachiyomi.animeextension.en.reanime

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val MIME_MPEGURL = "application/vnd.apple.mpegurl"
    private const val MIME_MP2T = "video/mp2t"
    private const val MIME_OCTET = "application/octet-stream"
    private const val ORIGIN = "https://flixcloud.cc"

    private val segmentXorKey = ubyteArrayOf(
        157u, 42u, 241u, 71u, 179u, 142u, 92u, 112u,
        166u, 25u, 228u, 59u, 216u, 98u, 15u, 197u,
    ).toByteArray()

    @Volatile
    private var isRunning = false

    @Volatile
    private var client: OkHttpClient? = null

    @Volatile
    private var pk: ByteArray? = null

    @Volatile
    private var embedUrl: String? = null

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

    fun startProxy(client: OkHttpClient, pk: ByteArray, embedUrl: String) {
        this.client = client
        this.pk = pk
        this.embedUrl = embedUrl
        if (!isRunning) {
            try {
                start()
            } catch (_: Exception) {}
        }
    }

    fun proxyUrl(target: String): String {
        val encoded = URLEncoder.encode(target, StandardCharsets.UTF_8.name())
        return "http://127.0.0.1:$port/proxy?url=$encoded"
    }

    override fun serve(session: IHTTPSession): Response {
        val url = session.parameters["url"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing url parameter")
        val client = this.client
            ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server client not initialized")
        val pk = this.pk
            ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Decryption key not initialized")

        return try {
            val request = buildUpstreamRequest(url)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    return newFixedLengthResponse(
                        Status.lookup(code) ?: Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT,
                        "Upstream HTTP $code",
                    )
                }
                val body = response.body?.bytes()
                    ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Empty upstream body")
                handleBody(url, body, pk)
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleBody(url: String, body: ByteArray, pk: ByteArray): Response {
        val lower = url.lowercase(Locale.US)

        // Segments: image-wrapped TS
        if (lower.contains(".webp") || lower.contains(".png")) {
            val ts = decodeSegment(body) ?: body
            return bytesResponse(ts, MIME_MP2T)
        }

        // Manifests
        if (lower.contains(".m3u8")) {
            decryptManifest(body, pk)?.let { plain ->
                return textResponse(MIME_MPEGURL, rewritePlaylist(plain, url))
            }
            val rawText = String(body, StandardCharsets.UTF_8)
            if (rawText.trimStart().startsWith("#EXTM3U")) {
                return textResponse(MIME_MPEGURL, rewritePlaylist(rawText, url))
            }
        }

        // Fallback sniffing for other paths (keys, subtitle playlists, etc.)
        decryptManifest(body, pk)?.let { plain ->
            return textResponse(MIME_MPEGURL, rewritePlaylist(plain, url))
        }
        decodeSegment(body)?.let { ts ->
            return bytesResponse(ts, MIME_MP2T)
        }
        return bytesResponse(body, MIME_OCTET)
    }

    /**
     * Manifests are base64 text XOR'd with the PK key. Returns the plaintext
     * playlist or null when the body isn't an encrypted manifest.
     */
    private fun decryptManifest(body: ByteArray, pk: ByteArray): String? {
        val decoded = try {
            Base64.decode(String(body, StandardCharsets.UTF_8), Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        if (decoded.isEmpty()) return null
        val plain = xorBytes(decoded, pk)
        val text = String(plain, StandardCharsets.UTF_8)
        return if (text.trimStart().startsWith("#EXTM3U")) text else null
    }

    /**
     * Segments are TS data wrapped in a WEBP or PNG container, optionally XOR'd
     * with a fixed 16-byte key. Returns the decoded TS bytes or null when the
     * body isn't an image-wrapped segment.
     */
    private fun decodeSegment(body: ByteArray): ByteArray? {
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
        if (offset < 0) return null

        val out = body.copyOfRange(offset, body.size)
        if (needsXor) {
            for (i in out.indices) {
                out[i] = (out[i].toInt() xor (segmentXorKey[i and 15].toInt() and 0xff)).toByte()
            }
        }
        return out
    }

    /**
     * Rewrites every URI in the playlist (sub-playlist variants, EXT-X-KEY,
     * EXT-X-MEDIA and segment lines) to point back at this proxy.
     */
    private fun rewritePlaylist(content: String, originalUrl: String): String {
        val base = originalUrl.toHttpUrlOrNull()
        val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
        return content.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line

                trimmed.startsWith("#") -> {
                    val match = uriRegex.find(trimmed)
                    if (match != null) {
                        val uriValue = match.groupValues[1]
                        val resolved = base?.resolve(uriValue)?.toString() ?: uriValue
                        trimmed.replace(uriValue, proxyUrl(resolved))
                    } else {
                        line
                    }
                }

                else -> {
                    val resolved = base?.resolve(trimmed)?.toString() ?: trimmed
                    proxyUrl(resolved)
                }
            }
        }
    }

    private fun buildUpstreamRequest(url: String): Request {
        val httpUrl = url.toHttpUrlOrNull()
        // Mirror browser referrer behavior: same-site (fetch.flixcloud.cc) gets
        // the full embed URL, cross-site (segment vault) gets origin-only.
        val isSameSite = httpUrl?.host?.endsWith("flixcloud.cc") == true
        val referer = if (isSameSite) (embedUrl ?: "$ORIGIN/") else "$ORIGIN/"
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", ORIGIN)
            .header("Referer", referer)
            .build()
    }

    private fun xorBytes(data: ByteArray, key: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            out[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return out
    }

    private fun textResponse(mimeType: String, text: String): Response = newFixedLengthResponse(Status.OK, mimeType, text)

    private fun bytesResponse(data: ByteArray, mimeType: String): Response = newFixedLengthResponse(Status.OK, mimeType, ByteArrayInputStream(data), data.size.toLong())
}

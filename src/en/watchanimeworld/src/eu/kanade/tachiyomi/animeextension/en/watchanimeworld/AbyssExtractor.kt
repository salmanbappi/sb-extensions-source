package eu.kanade.tachiyomi.animeextension.en.watchanimeworld

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AbyssExtractor(

    private val client: OkHttpClient,
    private val playlistUtils: PlaylistUtils,
) {
    private var prefix: String = ""

    fun videosFromUrl(url: String, referer: String? = null, prefix: String = ""): List<Video> {
        this.prefix = prefix
        var targetUrl = url

        // Normalize host (short.icu and embedplayabyss.top to abyssplayer.com)
        if (targetUrl.contains("short.icu") || targetUrl.contains("embedplayabyss.top") || targetUrl.contains("abysscdn.com") || targetUrl.contains("abyssplayer.com")) {
            val id = when {
                targetUrl.contains("v=") -> targetUrl.substringAfter("v=").substringBefore("&")
                targetUrl.contains("/embed/") -> targetUrl.substringAfter("/embed/").substringBefore("?").substringBefore("/")
                else -> targetUrl.substringAfterLast("/").substringBefore("?").substringBefore("#")
            }
            if (id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                targetUrl = "https://abyssplayer.com/?v=$id"
            }
        }

        val baseReferer = referer ?: targetUrl.toHttpUrl().newBuilder().encodedPath("/").build().toString()
        val headers = Headers.Builder().apply {
            add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            add("Referer", baseReferer)
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }.build()

        val response = try {
            client.newCall(GET(targetUrl, headers)).execute()
        } catch (e: Exception) {
            return emptyList()
        }

        if (!response.isSuccessful) return emptyList()

        val finalUrl = response.request.url.toString()
        val html = response.body?.string() ?: return emptyList()

        // Update referer to always use the player URL
        val finalReferer = finalUrl

        val datas = extractDatasPayload(html)
        if (datas != null) {
            val slug = datas.optString("slug", "")
            val md5Id = datas.optString("md5_id", "")
            val userId = datas.optString("user_id", "")
            val mediaObj = datas.opt("media")
            val isDownload = datas.optBoolean("isDownload", false)

            val mediaPayload = when (mediaObj) {
                is JSONObject -> mediaObj
                is String -> decryptMedia(mediaObj, userId, slug, md5Id)
                else -> null
            }

            // Extract subtitles
            val subtitles = mutableListOf<Track>()
            val subJsonArray = mediaPayload?.optJSONArray("subtitles")
                ?: mediaPayload?.optJSONArray("tracks")
                ?: datas.optJSONArray("subtitles")
                ?: datas.optJSONObject("config")?.optJSONArray("subtitles")

            if (subJsonArray != null) {
                for (i in 0 until subJsonArray.length()) {
                    val subObj = subJsonArray.optJSONObject(i) ?: continue
                    val src = subObj.optString("src").takeIf { it.isNotEmpty() }
                        ?: subObj.optString("file").takeIf { it.isNotEmpty() }
                        ?: subObj.optString("url")
                    val label = subObj.optString("label").takeIf { it.isNotEmpty() }
                        ?: subObj.optString("title").takeIf { it.isNotEmpty() }
                        ?: "Sub"
                    if (src.isNotEmpty()) {
                        subtitles.add(Track(src, label))
                    }
                }
            }

            if (mediaPayload != null) {
                return extractFromMediaPayload(mediaPayload, slug, md5Id, isDownload, finalReferer, subtitles)
            }
        }

        // Fallback to legacy extraction
        val legacyUrl = legacyExtract(html)
        if (legacyUrl != null) {
            return listOf(
                Video(
                    videoUrl = legacyUrl,
                    videoTitle = "Abyss - Video",
                    headers = Headers.Builder().add("Referer", finalReferer).build(),
                ),
            )
        }

        return emptyList()
    }

    private fun extractDatasPayload(html: String): JSONObject? {
        val match = datasRegex.find(html) ?: return null
        val base64Str = match.groupValues[1].trim()
        val rawBytes = try {
            Base64.decode(base64Str, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }

        // Parse raw bytes as ISO-8859-1 (Latin-1) JSON to preserve binary bytes in the string fields!
        val latin1Str = rawBytes.toString(Charsets.ISO_8859_1)
        try {
            if (latin1Str.trim().startsWith("{")) {
                return JSONObject(latin1Str)
            }
        } catch (_: Exception) {}

        // Fallback parsing (Latin-1 decoding + custom checks)
        val decodedStr = latin1Str
        val payload = JSONObject()

        // Slug
        Regex(""""slug"\s*:\s*"([^"]+)"""").find(decodedStr)?.let {
            payload.put("slug", it.groupValues[1])
        }
        // md5_id
        Regex(""""md5_id"\s*:\s*(\d+)""").find(decodedStr)?.let {
            payload.put("md5_id", it.groupValues[1])
        }
        // user_id
        Regex(""""user_id"\s*:\s*(\d+)""").find(decodedStr)?.let {
            payload.put("user_id", it.groupValues[1])
        }

        // Media block
        val mediaMarker = "\"media\":\""
        val configMarker = "\",\"config\""
        val mIdx = decodedStr.indexOf(mediaMarker)
        val cIdx = decodedStr.indexOf(configMarker)
        if (mIdx >= 0 && cIdx > mIdx) {
            val mediaEscaped = decodedStr.substring(mIdx + mediaMarker.length, cIdx)
            payload.put("media", decodeEscapedBinary(mediaEscaped))
        } else {
            Regex(""""media"\s*:\s*"((?:\\.|[^"\\])*)"""").find(decodedStr)?.let {
                payload.put("media", decodeEscapedBinary(it.groupValues[1]))
            }
        }

        // isDownload
        Regex(""""isDownload"\s*:\s*(true|false)""").find(decodedStr)?.let {
            payload.put("isDownload", it.groupValues[1] == "true")
        }

        return payload
    }

    private fun decodeEscapedBinary(escaped: String): String {
        if (escaped.isEmpty()) return ""
        val out = StringBuilder()
        var i = 0
        val escMap = mapOf(
            'n' to '\n',
            'r' to '\r',
            't' to '\t',
            'b' to '\b',
            'f' to '\u000c',
            '\\' to '\\',
            '"' to '"',
            '/' to '/',
        )
        while (i < escaped.length) {
            val ch = escaped[i]
            if (ch == '\\' && i + 1 < escaped.length) {
                val nxt = escaped[i + 1]
                if (nxt == 'u' && i + 5 < escaped.length) {
                    try {
                        val hex = escaped.substring(i + 2, i + 6)
                        out.append(hex.toInt(16).toChar())
                        i += 6
                        continue
                    } catch (_: Exception) {}
                }
                if (escMap.containsKey(nxt)) {
                    out.append(escMap[nxt])
                    i += 2
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun decryptMedia(encryptedText: String, userId: String, slug: String, md5Id: String): JSONObject? {
        if (encryptedText.isEmpty() || userId.isEmpty() || slug.isEmpty() || md5Id.isEmpty()) return null
        val keySeed = "$userId:$slug:$md5Id"

        val rawBytes = ByteArray(encryptedText.length) { i ->
            (encryptedText[i].code and 0xFF).toByte()
        }

        val result = aesCtrTransform(rawBytes, keySeed) ?: return null
        return try {
            JSONObject(result.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveKey(seed: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest(seed.toByteArray(Charsets.UTF_8))
        val hexString = hash.joinToString("") { String.format("%02x", it) }
        return hexString.toByteArray(Charsets.UTF_8)
    }

    private fun aesCtrTransform(dataBytes: ByteArray, keySeed: String): ByteArray? = try {
        val key = deriveKey(keySeed)
        val iv = key.copyOfRange(0, 16)
        val secretKey = SecretKeySpec(key, "AES")
        val ivParameterSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
        cipher.doFinal(dataBytes)
    } catch (e: Exception) {
        null
    }

    private fun buildSoraToken(pathValue: String, sizeValue: String): String? {
        val transformed = aesCtrTransform(pathValue.toByteArray(Charsets.UTF_8), sizeValue) ?: return null
        val first = Base64.encodeToString(transformed, Base64.NO_WRAP).replace("=", "")
        val second = Base64.encodeToString(first.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).replace("=", "")
        return second
    }

    private fun extractFromMediaPayload(
        mediaPayload: JSONObject,
        slug: String,
        md5Id: String,
        isDownload: Boolean,
        referer: String,
        subtitles: List<Track>,
    ): List<Video> {
        val videoList = mutableListOf<Video>()
        val streamHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", referer)
            .build()

        // 1. Try MP4 sources
        val mp4 = mediaPayload.optJSONObject("mp4")
        if (mp4 != null) {
            val sources = mp4.optJSONArray("sources")
            val domains = mp4.optJSONArray("domains")
            if (sources != null) {
                val sourceList = mutableListOf<JSONObject>()
                for (i in 0 until sources.length()) {
                    val src = sources.optJSONObject(i) ?: continue
                    sourceList.add(src)
                }
                sourceList.sortByDescending { it.optLong("size", 0L) }

                for (src in sourceList) {
                    val label = src.optString("label", "Video")
                    val size = src.optString("size", "")
                    val resId = src.optString("res_id", "")
                    val sub = src.optString("sub", "")

                    // Direct file
                    val direct = src.optString("file", "")
                    if (direct.isNotEmpty()) {
                        videoList.add(
                            Video(
                                videoUrl = "${direct.replace("\\/", "/")}?ext=.mp4",
                                videoTitle = "${prefix}Abyss - $label (MP4)",
                                headers = streamHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                        continue
                    }

                    // Handle other MP4 sources through local proxy decryption
                    val sizeValue = size.toLongOrNull() ?: 0L
                    val partSizeValue = src.optLong("partSize", 0L)

                    val fristDatas = mediaPayload.optJSONArray("fristDatas")
                        ?: mediaPayload.optJSONArray("fristdata")
                    var firstPart: JSONObject? = null
                    if (fristDatas != null) {
                        for (k in 0 until fristDatas.length()) {
                            val fd = fristDatas.optJSONObject(k) ?: continue
                            if (fd.optString("res_id") == resId) {
                                firstPart = fd
                                break
                            }
                        }
                    }

                    var domain: String? = null
                    if (domains != null) {
                        for (j in 0 until domains.length()) {
                            val d = domains.optString(j, "")
                            if (d.isNotEmpty() && d.contains(sub)) {
                                domain = d
                                break
                            }
                        }
                    }

                    var mainUrl = ""
                    val urlVal = src.optString("url")
                    val pathVal = src.optString("path")
                    if (urlVal.isNotEmpty() && pathVal.isNotEmpty()) {
                        mainUrl = "${urlVal.trimEnd('/')}/${pathVal.trimStart('/')}".replace("\\/", "/")
                    } else if (firstPart != null && domain != null) {
                        val fdUrl = firstPart.optString("url")
                        if (fdUrl.isNotEmpty()) {
                            val fdPath = fdUrl.substringAfter("://").substringAfter("/")
                            val basePath = fdPath.substringBeforeLast(".fd")
                            val normDomain = if (domain.startsWith("http")) domain else "https://$domain"
                            mainUrl = "${normDomain.trimEnd('/')}/$basePath"
                        }
                    }

                    if (sizeValue > 0L && mainUrl.isNotEmpty()) {
                        val id = UUID.randomUUID().toString()
                        val partsList = mutableListOf<VideoPart>()
                        val fdUrl = firstPart?.optString("url") ?: ""
                        val fdSize = firstPart?.optLong("partSize", 0L) ?: 0L

                        if (fdSize > 0L && fdUrl.isNotEmpty()) {
                            partsList.add(VideoPart(fdUrl, 0, fdSize, 0))
                        }

                        val startOffset = if (fdSize > 0L) fdSize else 0L
                        if (partSizeValue <= 0L) {
                            partsList.add(VideoPart(mainUrl, startOffset, sizeValue, startOffset))
                        } else {
                            var virtualOffset = 0L
                            var partIndex = 0
                            while (virtualOffset < sizeValue) {
                                val nextOffset = minOf(virtualOffset + partSizeValue, sizeValue)
                                val partUrl = if (partIndex == 0) mainUrl else "$mainUrl$partIndex"
                                if (partIndex == 0) {
                                    if (startOffset < nextOffset) {
                                        partsList.add(VideoPart(partUrl, startOffset, nextOffset, startOffset))
                                    }
                                } else {
                                    partsList.add(VideoPart(partUrl, virtualOffset, nextOffset, 0))
                                }
                                virtualOffset = nextOffset
                                partIndex++
                            }
                        }

                        val videoData = AbyssVideoData(
                            size = sizeValue,
                            firstPartUrl = if (fdSize > 0L) fdUrl else "",
                            firstPartSize = fdSize,
                            parts = partsList,
                        )
                        AbyssProxy.mediaCache[id] = videoData

                        val port = AbyssProxy.getPort(client)
                        val proxyUrl = "http://127.0.0.1:$port/play?id=$id"
                        videoList.add(
                            Video(
                                videoUrl = proxyUrl,
                                videoTitle = "${prefix}Abyss - $label (MP4)",
                                headers = streamHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                    }
                }
            }
        }

        // 2. Try HLS
        val hls = mediaPayload.optJSONObject("hls")
        if (hls != null) {
            var hlsUrl: String? = null
            val hlsLabel = hls.optString("label", "")

            for (key in listOf("file", "url", "master", "src", "source")) {
                val valStr = hls.optString(key, "")
                if (valStr.isNotEmpty()) {
                    hlsUrl = valStr.replace("\\/", "/")
                    break
                }
            }

            if (hlsUrl != null) {
                try {
                    val hlsVideos = playlistUtils.extractFromHls(
                        playlistUrl = hlsUrl,
                        referer = referer,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> if (hlsLabel.isNotEmpty()) "${prefix}Abyss - $hlsLabel ($quality)" else "${prefix}Abyss ($quality)" },
                        subtitleList = subtitles,
                    )
                    if (hlsVideos.isNotEmpty()) {
                        videoList.addAll(hlsVideos)
                    } else {
                        videoList.add(
                            Video(
                                videoUrl = hlsUrl,
                                videoTitle = if (hlsLabel.isNotEmpty()) "${prefix}Abyss - $hlsLabel (Auto)" else "${prefix}Abyss - Auto",
                                headers = streamHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                    }
                } catch (_: Exception) {
                    videoList.add(
                        Video(
                            videoUrl = hlsUrl,
                            videoTitle = if (hlsLabel.isNotEmpty()) "${prefix}Abyss - $hlsLabel (Auto)" else "${prefix}Abyss - Auto",
                            headers = streamHeaders,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            } else {
                val hlsSources = hls.optJSONArray("sources")
                if (hlsSources != null) {
                    for (i in 0 until hlsSources.length()) {
                        val hs = hlsSources.optJSONObject(i) ?: continue
                        var f: String? = null
                        for (key in listOf("file", "url", "src")) {
                            val valStr = hs.optString(key, "")
                            if (valStr.isNotEmpty()) {
                                f = valStr
                                break
                            }
                        }
                        if (f != null) {
                            val label = hs.optString("label", "Video")
                            try {
                                val hlsVideos = playlistUtils.extractFromHls(
                                    playlistUrl = f.replace("\\/", "/"),
                                    referer = referer,
                                    masterHeaders = streamHeaders,
                                    videoHeaders = streamHeaders,
                                    videoNameGen = { quality -> "${prefix}Abyss - $label ($quality)" },
                                    subtitleList = subtitles,
                                )
                                if (hlsVideos.isNotEmpty()) {
                                    videoList.addAll(hlsVideos)
                                } else {
                                    videoList.add(
                                        Video(
                                            videoUrl = f.replace("\\/", "/"),
                                            videoTitle = "${prefix}Abyss - $label (Auto)",
                                            headers = streamHeaders,
                                            subtitleTracks = subtitles,
                                        ),
                                    )
                                }
                            } catch (_: Exception) {
                                videoList.add(
                                    Video(
                                        videoUrl = f.replace("\\/", "/"),
                                        videoTitle = "${prefix}Abyss - $label (Auto)",
                                        headers = streamHeaders,
                                        subtitleTracks = subtitles,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            // HLS fallback by ID
            val hlsId = hls.optString("id", "")
            if (videoList.isEmpty() && hlsId.isNotEmpty()) {
                val fallbackUrl = "https://abyssplayer.com/#hls/$hlsId/master.m3u8"
                try {
                    val hlsVideos = playlistUtils.extractFromHls(
                        playlistUrl = fallbackUrl,
                        referer = referer,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "${prefix}Abyss ($quality)" },
                        subtitleList = subtitles,
                    )
                    if (hlsVideos.isNotEmpty()) {
                        videoList.addAll(hlsVideos)
                    } else {
                        videoList.add(
                            Video(
                                videoUrl = fallbackUrl,
                                videoTitle = "${prefix}Abyss - Auto",
                                headers = streamHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                    }
                } catch (_: Exception) {
                    videoList.add(
                        Video(
                            videoUrl = fallbackUrl,
                            videoTitle = "${prefix}Abyss - Auto",
                            headers = streamHeaders,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            }
        }

        return videoList
    }

    private fun legacyExtract(html: String): String? {
        val m = legacyRegex.find(html)
        if (m != null) {
            try {
                val decoded = customDecode(m.groupValues[1])
                val json = JSONObject(decoded)
                val domain = json.optString("domain", "")
                val id = json.optString("id", "")
                if (domain.isNotEmpty() && id.isNotEmpty()) {
                    return "https://${domain.trim('/')}/$id"
                }
            } catch (_: Exception) {}
        }
        val dm = domainRegex.find(html)
        val im = idRegex.find(html)
        if (dm != null && im != null) {
            return "https://${dm.groupValues[1].trim('/')}/${im.groupValues[1]}"
        }
        return null
    }

    private fun customDecode(encoded: String): String {
        val out = java.io.ByteArrayOutputStream()
        encoded.chunked(4).forEach { chunk ->
            val padded = chunk.padEnd(4, '=')
            val c = padded.map { ch ->
                val idx = CHARSET.indexOf(ch)
                if (idx != -1) idx else 64
            }
            out.write((c[0] shl 2) or (c[1] ushr 4))
            if (c[2] != 64) {
                out.write(((c[1] and 15) shl 4) or (c[2] ushr 2))
            }
            if (c[3] != 64) {
                out.write(((c[2] and 3) shl 6) or c[3])
            }
        }
        return out.toString("UTF-8")
    }

    companion object {
        private const val CHARSET = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
        private val datasRegex = Regex("""(?:const|var)\s+datas\s*=\s*"([^"]+)"""")
        private val legacyRegex = Regex("""[\w\$]+='([A-Za-z0-9+/=RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW]{30,})_'""")
        private val domainRegex = Regex("""['"]domain['"]\s*:\s*['"]([^'"]+)['"]""")
        private val idRegex = Regex("""['"]id['"]\s*:\s*['"]([^'"]+)['"]""")
    }
}

object AbyssProxy {
    private var server: LocalProxyServer? = null
    val mediaCache = ConcurrentHashMap<String, AbyssVideoData>()

    fun getPort(client: OkHttpClient): Int {
        synchronized(this) {
            if (server == null) {
                server = LocalProxyServer(client).apply { start() }
            }
            return server!!.port
        }
    }
}

data class AbyssVideoData(
    val size: Long,
    val firstPartUrl: String,
    val firstPartSize: Long,
    val parts: List<VideoPart>,
) {
    @Volatile
    private var decryptedHeader: ByteArray? = null

    fun getOrFetchDecryptedHeader(client: OkHttpClient): ByteArray {
        decryptedHeader?.let { return it }
        synchronized(this) {
            decryptedHeader?.let { return it }

            val headerSize = minOf(firstPartSize, 65536L).toInt()
            val request = okhttp3.Request.Builder()
                .url(firstPartUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://abyssplayer.com/")
                .header("Range", "bytes=0-${headerSize - 1}")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw java.io.IOException("Failed to fetch header: ${response.code}")
            }
            val bodyBytes = response.body?.bytes() ?: throw java.io.IOException("Empty header body")

            val seed = firstPartUrl.substringAfterLast("/")
            val decrypted = try {
                aesDecryptCTR(bodyBytes, seed)
            } catch (e: Exception) {
                bodyBytes
            }

            decryptedHeader = decrypted
            return decrypted
        }
    }

    private fun cleanSeed(seed: String): String {
        var cleaned = seed
        val firstDot = cleaned.indexOf('.')
        if (firstDot != -1) {
            cleaned = cleaned.substring(0, firstDot) + cleaned.substring(firstDot + 1)
        }
        return cleaned.replace(":", "").replace("-", "")
    }

    private fun aesDecryptCTR(dataBytes: ByteArray, seed: String): ByteArray {
        val cleaned = cleanSeed(seed)
        val md5 = MessageDigest.getInstance("MD5")
        val hash = md5.digest(cleaned.toByteArray(Charsets.UTF_8))
        val hexString = hash.joinToString("") { String.format("%02x", it) }
        val key = hexString.toByteArray(Charsets.UTF_8)
        val iv = key.copyOfRange(0, 16)

        val secretKey = SecretKeySpec(key, "AES")
        val ivParameterSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)
        return cipher.doFinal(dataBytes)
    }
}

class VideoPart(
    val url: String,
    val virtualStart: Long,
    val virtualEnd: Long,
    val physicalOffset: Long,
)

class LocalProxyServer(
    private val client: OkHttpClient,
) : NanoHTTPD(0) {

    val port: Int
        get() = super.getListeningPort()

    override fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri != "/play") {
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val id = session.parameters["id"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing id")

        val data = AbyssProxy.mediaCache[id]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Video data not found")

        val rangeHeader = session.headers["range"]
        var reqStart = 0L
        var reqEnd = data.size - 1

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeStr = rangeHeader.substring(6)
            val dashIdx = rangeStr.indexOf('-')
            if (dashIdx != -1) {
                val startStr = rangeStr.substring(0, dashIdx)
                val endStr = rangeStr.substring(dashIdx + 1)
                if (startStr.isNotEmpty()) reqStart = startStr.toLong()
                if (endStr.isNotEmpty()) reqEnd = endStr.toLong()
            }
        }

        if (reqEnd >= data.size) reqEnd = data.size - 1
        if (reqStart > reqEnd) reqStart = reqEnd

        val contentLength = reqEnd - reqStart + 1

        val decryptedHeader = if (data.firstPartUrl.isNotEmpty()) {
            try {
                data.getOrFetchDecryptedHeader(client)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val streamHeaders = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "https://abyssplayer.com/")
            .build()

        val response = newFixedLengthResponse(
            if (rangeHeader != null) Status.PARTIAL_CONTENT else Status.OK,
            "video/mp4",
            ProxyInputStream(client, streamHeaders, data.parts, decryptedHeader, reqStart, reqEnd),
            contentLength,
        )
        response.addHeader("Accept-Ranges", "bytes")
        if (rangeHeader != null) {
            response.addHeader("Content-Range", "bytes $reqStart-$reqEnd/${data.size}")
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}

class ProxyInputStream(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val parts: List<VideoPart>,
    private val decryptedHeader: ByteArray?,
    private var currentPos: Long,
    private val endPos: Long,
) : java.io.InputStream() {

    private var activeStream: java.io.InputStream? = null
    private var activeStreamEnd = -1L

    override fun read(): Int {
        if (currentPos > endPos) return -1

        if (decryptedHeader != null && currentPos < 65536) {
            val byte = decryptedHeader[currentPos.toInt()].toInt() and 0xFF
            currentPos++
            return byte
        }

        val part = parts.find { currentPos >= it.virtualStart && currentPos < it.virtualEnd }
            ?: return -1

        if (activeStream == null || currentPos > activeStreamEnd) {
            activeStream?.close()
            activeStream = null

            val partEnd = minOf(part.virtualEnd - 1, endPos)
            val physStart = part.physicalOffset + (currentPos - part.virtualStart)
            val physEnd = part.physicalOffset + (partEnd - part.virtualStart)

            val request = okhttp3.Request.Builder()
                .url(part.url)
                .headers(headers)
                .header("Range", "bytes=$physStart-$physEnd")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw java.io.IOException("CDN request failed: ${response.code}")
            }
            activeStream = response.body?.byteStream()
            activeStreamEnd = partEnd
        }

        val byte = activeStream?.read() ?: -1
        if (byte != -1) {
            currentPos++
        }
        return byte
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (currentPos > endPos) return -1

        if (decryptedHeader != null && currentPos < 65536) {
            val toRead = minOf(len.toLong(), 65536 - currentPos, endPos - currentPos + 1).toInt()
            System.arraycopy(decryptedHeader, currentPos.toInt(), b, off, toRead)
            currentPos += toRead
            return toRead
        }

        val part = parts.find { currentPos >= it.virtualStart && currentPos < it.virtualEnd }
            ?: return -1

        if (activeStream == null || currentPos > activeStreamEnd) {
            activeStream?.close()
            activeStream = null

            val partEnd = minOf(part.virtualEnd - 1, endPos)
            val physStart = part.physicalOffset + (currentPos - part.virtualStart)
            val physEnd = part.physicalOffset + (partEnd - part.virtualStart)

            val request = okhttp3.Request.Builder()
                .url(part.url)
                .headers(headers)
                .header("Range", "bytes=$physStart-$physEnd")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                throw java.io.IOException("CDN request failed: ${response.code}")
            }
            activeStream = response.body?.byteStream()
            activeStreamEnd = partEnd
        }

        val maxLen = minOf(len.toLong(), activeStreamEnd - currentPos + 1).toInt()
        val bytesRead = activeStream?.read(b, off, maxLen) ?: -1
        if (bytesRead > 0) {
            currentPos += bytesRead
        }
        return bytesRead
    }

    override fun close() {
        activeStream?.close()
        super.close()
    }
}

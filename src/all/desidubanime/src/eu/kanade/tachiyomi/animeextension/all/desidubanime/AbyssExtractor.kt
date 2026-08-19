package eu.kanade.tachiyomi.animeextension.all.desidubanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AbyssExtractor(
    private val client: OkHttpClient,
    private val playlistUtils: PlaylistUtils,
    private val localProxy: LocalProxy? = null,
) {
    private var prefix: String = ""

    fun videosFromUrl(url: String, referer: String? = null, prefix: String = ""): List<Video> {
        this.prefix = prefix
        var targetUrl = url

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
        } catch (_: Exception) {
            return emptyList()
        }

        if (!response.isSuccessful) return emptyList()

        val finalUrl = response.request.url.toString()
        val html = response.body.string()
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

        val latin1Str = rawBytes.toString(Charsets.ISO_8859_1)
        try {
            if (latin1Str.trim().startsWith("{")) {
                return JSONObject(latin1Str)
            }
        } catch (_: Exception) {}

        val decodedStr = latin1Str
        val payload = JSONObject()

        Regex(""""slug"\s*:\s*"([^"]+)"""").find(decodedStr)?.let {
            payload.put("slug", it.groupValues[1])
        }
        Regex(""""md5_id"\s*:\s*(\d+)""").find(decodedStr)?.let {
            payload.put("md5_id", it.groupValues[1])
        }
        Regex(""""user_id"\s*:\s*(\d+)""").find(decodedStr)?.let {
            payload.put("user_id", it.groupValues[1])
        }

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
    } catch (_: Exception) {
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
            }
        }

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

                    val direct = src.optString("file", "")
                    val srcUrl = src.optString("url", "")
                    val path = src.optString("path", "")

                    if (direct.isNotEmpty()) {
                        val videoUrl = direct.replace("\\/", "/") + "?ext=.mp4"
                        val proxiedUrl = localProxy?.getProxyUrl(videoUrl, streamHeaders) ?: videoUrl
                        videoList.add(
                            Video(
                                videoUrl = proxiedUrl,
                                videoTitle = "${prefix}Abyss - $label (MP4)",
                                headers = streamHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                        continue
                    }

                    if (srcUrl.isNotEmpty() && path.isNotEmpty()) {
                        val normUrl = if (srcUrl.startsWith("http")) srcUrl else "https://$srcUrl"
                        val directUrl = "${normUrl.trimEnd('/')}/${path.trimStart('/')}"
                        val proxiedUrl = localProxy?.getProxyUrl(directUrl, streamHeaders) ?: directUrl
                        videoList.add(
                            Video(
                                videoUrl = proxiedUrl,
                                videoTitle = "${prefix}Abyss - $label (Direct)",
                                headers = streamHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                    }

                    if (size.isNotEmpty() && resId.isNotEmpty() && sub.isNotEmpty() && domains != null) {
                        var domain: String? = null
                        for (j in 0 until domains.length()) {
                            val d = domains.optString(j, "")
                            if (d.isNotEmpty() && d.contains(sub)) {
                                domain = d
                                break
                            }
                        }

                        if (domain != null) {
                            val pathValue = "/mp4/$md5Id/$resId/$size?v=$slug"
                            val token = buildSoraToken(pathValue, size)
                            if (token != null) {
                                val norm = if (domain.startsWith("http")) domain else "https://$domain"
                                val finalUrl = "${norm.trimEnd('/')}/sora/$size/$token"
                                val proxiedUrl = localProxy?.getProxyUrl(finalUrl, streamHeaders) ?: finalUrl
                                videoList.add(
                                    Video(
                                        videoUrl = proxiedUrl,
                                        videoTitle = "${prefix}Abyss - $label (Sora)",
                                        headers = streamHeaders,
                                        subtitleTracks = subtitles,
                                    ),
                                )

                                val wsHost = norm.removePrefix("https://").removePrefix("http://").trimEnd('/')
                                val wsUrl = "wss://$wsHost/future"
                                val wsProxiedUrl = localProxy?.getVirtualWsPlaylistUrl(wsUrl, streamHeaders)
                                if (wsProxiedUrl != null) {
                                    videoList.add(
                                        Video(
                                            videoUrl = wsProxiedUrl,
                                            videoTitle = "${prefix}Abyss - $label (Virtual HLS)",
                                            headers = streamHeaders,
                                            subtitleTracks = subtitles,
                                        ),
                                    )
                                }
                            }
                        }
                    }
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

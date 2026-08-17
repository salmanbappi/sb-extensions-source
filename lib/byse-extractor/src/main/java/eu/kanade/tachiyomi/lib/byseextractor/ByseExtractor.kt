package eu.kanade.tachiyomi.lib.byseextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ByseExtractor(
    private val client: OkHttpClient,
    private val playlistUtils: PlaylistUtils = PlaylistUtils(client),
) {
    fun videosFromUrl(url: String, prefix: String = "Byse - ", headers: Headers? = null): List<Video> {
        val fileId = url.substringAfterLast("e/").substringAfterLast("/").substringBefore("?").substringBefore("#")
        if (fileId.isBlank()) return emptyList()

        val host = url.substringBefore("/e/").takeIf { it.startsWith("http") } ?: "https://bysetayico.com"
        val apiUrl = "$host/api/videos/$fileId"

        val apiHeaders = (headers?.newBuilder() ?: Headers.Builder())
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", "$host/e/$fileId")
            .set("Accept", "application/json, text/plain, */*")
            .build()

        val response = try {
            client.newCall(GET(apiUrl, apiHeaders)).execute()
        } catch (_: Exception) {
            return emptyList()
        }

        if (!response.isSuccessful) return emptyList()

        val body = response.body.string()
        val jsonElement = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return emptyList()

        val playback = jsonElement["playback"]?.jsonObject ?: return emptyList()
        val versionStr = playback["version"]?.jsonPrimitive?.content ?: "1"
        val version = versionStr.toIntOrNull() ?: 1
        val ivStr = playback["iv"]?.jsonPrimitive?.content ?: return emptyList()
        val payloadStr = playback["payload"]?.jsonPrimitive?.content ?: return emptyList()
        val keyPartsArray = playback["key_parts"]?.jsonArray ?: return emptyList()

        val keyParts = keyPartsArray.map { it.jsonPrimitive.content }
        val idx1 = version
        val idx2 = 31 - version

        if (idx1 < 1 || idx2 < 1 || idx1 > keyParts.size || idx2 > keyParts.size) return emptyList()

        val part1 = b64UrlDecode(keyParts[idx1 - 1])
        val part2 = b64UrlDecode(keyParts[idx2 - 1])
        val keyBytes = part1 + part2

        val ivBytes = b64UrlDecode(ivStr)
        val ciphertextWithTag = b64UrlDecode(payloadStr)

        val decryptedJsonStr = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(ciphertextWithTag)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }

        val decryptedObj = try {
            JSONObject(decryptedJsonStr)
        } catch (_: Exception) {
            return emptyList()
        }

        val sources = decryptedObj.optJSONArray("sources") ?: return emptyList()
        val tracks = decryptedObj.optJSONArray("tracks")

        val subtitleTracks = mutableListOf<Track>()
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val trackUrl = track.optString("url")
                val label = track.optString("label", "Sub")
                if (trackUrl.isNotEmpty()) {
                    subtitleTracks.add(Track(trackUrl, label))
                }
            }
        }

        val videoList = mutableListOf<Video>()
        for (i in 0 until sources.length()) {
            val src = sources.optJSONObject(i) ?: continue
            val videoUrl = src.optString("url")
            val label = src.optString("label", "1080p")
            val mimeType = src.optString("mime_type", "")

            if (videoUrl.isNotBlank()) {
                if (mimeType.contains("mpegurl", ignoreCase = true) || videoUrl.contains(".m3u8")) {
                    val hlsVideos = try {
                        playlistUtils.extractFromHls(
                            playlistUrl = videoUrl,
                            referer = "$host/",
                            masterHeaders = apiHeaders,
                            videoHeaders = apiHeaders,
                            videoNameGen = { quality -> "$prefix$label ($quality)" },
                            subtitleList = subtitleTracks,
                        )
                    } catch (_: Exception) {
                        emptyList()
                    }

                    if (hlsVideos.isNotEmpty()) {
                        videoList.addAll(hlsVideos)
                    } else {
                        videoList.add(
                            Video(
                                videoUrl = videoUrl,
                                videoTitle = "$prefix$label (Auto)",
                                headers = apiHeaders,
                                subtitleTracks = subtitleTracks,
                            ),
                        )
                    }
                } else {
                    videoList.add(
                        Video(
                            videoUrl = videoUrl,
                            videoTitle = "$prefix$label",
                            headers = apiHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            }
        }

        return videoList
    }

    private fun b64UrlDecode(input: String): ByteArray {
        val base64 = input.replace("-", "+").replace("_", "/")
        val pad = (4 - base64.length % 4) % 4
        val padded = base64 + "=".repeat(pad)
        return Base64.decode(padded, Base64.DEFAULT)
    }
}

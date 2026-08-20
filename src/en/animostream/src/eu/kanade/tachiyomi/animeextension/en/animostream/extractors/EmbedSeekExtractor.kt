package eu.kanade.tachiyomi.animeextension.en.animostream.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class EmbedSeekExtractor(
    private val client: OkHttpClient,
    private val playlistUtils: PlaylistUtils,
) {
    fun videosFromUrl(url: String, referer: String = "https://www.animostream.xyz/"): List<Video> {
        val id = when {
            url.contains("#") -> url.substringAfter("#").substringBefore("&")
            url.contains("id=") -> url.substringAfter("id=").substringBefore("&")
            else -> url.substringAfterLast("/").substringBefore("?").substringBefore("#")
        }
        if (id.isBlank()) return emptyList()

        val headers = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .set("Referer", referer)
            .build()

        val apiUrl = "https://animostream.embedseek.online/api/v1/video?id=$id&w=1920&h=1080&r=www.animostream.xyz"
        val response = try {
            client.newCall(GET(apiUrl, headers)).execute()
        } catch (_: Exception) {
            return emptyList()
        }

        if (!response.isSuccessful) return emptyList()

        val hexData = response.body.string().trim()
        if (hexData.isEmpty() || hexData.length % 2 != 0) return emptyList()

        val decryptedJsonStr = decryptPayload(hexData) ?: return emptyList()
        val data = try {
            JSONObject(decryptedJsonStr)
        } catch (_: Exception) {
            return emptyList()
        }

        val videoList = mutableListOf<Video>()

        // Subtitles
        val subtitles = mutableListOf<Track>()
        val subtitleObj = data.optJSONObject("subtitle")
        if (subtitleObj != null) {
            val keys = subtitleObj.keys()
            while (keys.hasNext()) {
                val langKey = keys.next()
                val subPath = subtitleObj.optString(langKey, "")
                if (subPath.isNotBlank()) {
                    val fullSubUrl = if (subPath.startsWith("http")) subPath else "https://animostream.embedseek.online${subPath.substringBefore("#")}"
                    val label = when (langKey.lowercase()) {
                        "en" -> "English"
                        "hi" -> "Hindi"
                        else -> langKey.uppercase()
                    }
                    subtitles.add(Track(url = fullSubUrl, lang = label))
                }
            }
        }

        // Direct source or streams
        val sourceUrl = data.optString("source", "").ifBlank {
            data.optString("hlsVideoTiktok", "").ifBlank {
                data.optString("cf", "")
            }
        }

        if (sourceUrl.isNotBlank()) {
            val streamHeaders = Headers.Builder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .set("Referer", "https://animostream.embedseek.online/")
                .build()

            if (sourceUrl.contains(".m3u8")) {
                try {
                    val hlsVideos = playlistUtils.extractFromHls(
                        playlistUrl = sourceUrl,
                        referer = "https://animostream.embedseek.online/",
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "EmbedSeek - $quality" },
                        subtitleList = subtitles,
                    )
                    videoList.addAll(hlsVideos)
                } catch (_: Exception) {
                    videoList.add(
                        Video(
                            videoUrl = sourceUrl,
                            videoTitle = "EmbedSeek - Auto",
                            headers = streamHeaders,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            } else {
                videoList.add(
                    Video(
                        videoUrl = sourceUrl,
                        videoTitle = "EmbedSeek - Video",
                        headers = streamHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
        }

        return videoList
    }

    private fun decryptPayload(hexData: String): String? = try {
        val cipherBytes = hexStringToByteArray(hexData)
        val keySpec = SecretKeySpec(KEY_BYTES, "AES")
        val ivSpec = IvParameterSpec(IV_BYTES)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(cipherBytes)
        decrypted.toString(Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    companion object {
        private val KEY_BYTES = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private val IV_BYTES = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
    }
}

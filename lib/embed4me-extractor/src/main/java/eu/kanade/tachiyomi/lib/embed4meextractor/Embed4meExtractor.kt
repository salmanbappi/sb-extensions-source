package eu.kanade.tachiyomi.lib.embed4meextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Embed4meExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
        val videoId = when {
            decodedUrl.contains("#") -> decodedUrl.substringAfterLast("#").trim()
            decodedUrl.contains("/embed/", true) -> decodedUrl.substringAfter("/embed/").substringBefore("/").trim()
            else -> decodedUrl.substringAfterLast("/").trim()
        }

        if (videoId.isEmpty() || videoId.contains("http")) return emptyList()

        val parsedUrl = url.toHttpUrl()
        val apiUrl = "${parsedUrl.scheme}://${parsedUrl.host}/api/v1/video?id=$videoId&w=1920&h=1080&r="

        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Origin", "${parsedUrl.scheme}://${parsedUrl.host}")
            .add("Referer", "${parsedUrl.scheme}://${parsedUrl.host}/")
            .build()

        return try {
            val response = client.newCall(eu.kanade.tachiyomi.network.GET(apiUrl, headers)).execute()
            val responseBody = response.body.string().trim()

            android.util.Log.d("Embed4me", "API Response length: ${responseBody.length}")

            val decryptedJsonStr = decryptAesCbc(responseBody, "kiemtienmua911ca", "1234567890oiuytr")
            if (decryptedJsonStr == null) {
                android.util.Log.d("Embed4me", "Decrypted string is null")
                return emptyList()
            }

            android.util.Log.d("Embed4me", "Decrypted JSON: $decryptedJsonStr")

            val dataObj = json.parseToJsonElement(decryptedJsonStr).jsonObject
            val cfUrl = dataObj["cf"]?.jsonPrimitive?.content ?: ""
            val sourceUrl = dataObj["source"]?.jsonPrimitive?.content ?: ""

            // Prefer sourceUrl if it contains .m3u8, as cfUrl might be a .txt manifest causing app issues
            val videoUrl = if (sourceUrl.contains(".m3u8")) sourceUrl else cfUrl.ifEmpty { sourceUrl }

            if (videoUrl.isNotEmpty()) {
                val videoHeaders = headers.newBuilder()
                    .set("Referer", "${parsedUrl.scheme}://${parsedUrl.host}/")
                    .set("Origin", "${parsedUrl.scheme}://${parsedUrl.host}")
                    .build()
                val title = if (prefix.isNotEmpty()) prefix.trim().removeSuffix("-").trim() else "Embed4me"
                listOf(Video(videoUrl = videoUrl, videoTitle = title, headers = videoHeaders))
            } else {
                android.util.Log.d("Embed4me", "No videoUrl found. cfUrl: $cfUrl, sourceUrl: $sourceUrl")
                emptyList()
            }
        } catch (e: Exception) {
            android.util.Log.d("Embed4me", "Exception in videosFromUrl: ${e.message}")
            emptyList()
        }
    }

    private fun decryptAesCbc(hexData: String, keyStr: String, ivStr: String): String? = try {
        val cleanHex = hexData.trim().replace("\"", "")
        val data = hexStringToByteArray(cleanHex)

        val keyBytes = keyStr.toByteArray(Charsets.UTF_8)
        val ivBytes = ivStr.toByteArray(Charsets.UTF_8)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val ivParameterSpec = IvParameterSpec(ivBytes)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivParameterSpec)

        val decryptedBytes = cipher.doFinal(data)
        String(decryptedBytes, Charsets.UTF_8)
    } catch (e: Exception) {
        android.util.Log.d("Embed4me", "Decryption error: ${e.message}")
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
}

package eu.kanade.tachiyomi.lib.lycorisextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.nio.charset.Charset

class LycorisExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun videosFromUrl(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val dataAttribute = document.selectFirst("div#player")?.attr("data-player")
            ?: return emptyList()

        val decodedData = java.net.URLDecoder.decode(dataAttribute, "UTF-8")
        val jsonObj = json.decodeFromString<JsonObject>(decodedData)
        val episodeId = jsonObj["episode_id"]?.jsonPrimitive?.content ?: return emptyList()

        val videoLinks = fetchAndDecodeVideo(client, episodeId)
            ?: fetchAndDecodeVideo(client, episodeId, true)
            ?: return emptyList()

        val videoList = mutableListOf<Video>()
        val videoArray = json.decodeFromString<JsonObject>(videoLinks)["links"]?.jsonArray
            ?: return emptyList()

        for (i in 0 until videoArray.size) {
            val videoObj = videoArray[i].jsonObject
            val videoUrl = videoObj["link"]?.jsonPrimitive?.content ?: continue
            val quality = videoObj["label"]?.jsonPrimitive?.content ?: "Video"

            if (checkLinks(client, videoUrl)) {
                videoList.add(Video(videoUrl = videoUrl, videoTitle = quality))
            }
        }

        return videoList
    }

    private fun decodePythonEscape(input: String): String {
        val regex = Regex("\\\\u([0-9a-fA-F]{4})")
        return regex.replace(input) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
    }

    private fun decodeVideoLinks(data: String): String? = try {
        val decoded = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
        String(decoded, Charset.forName("UTF-8"))
    } catch (e: Exception) {
        null
    }

    private fun fetchAndDecodeVideo(client: OkHttpClient, episodeId: String, isSecondary: Boolean = false): String? {
        val requestUrl: HttpUrl = if (isSecondary) {
            val convertedText = episodeId.toByteArray(Charset.forName("UTF-8")).toString(Charset.forName("ISO-8859-1"))
            val unicodeEscape = decodePythonEscape(convertedText)
            val finalText = unicodeEscape.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1).toString(java.nio.charset.StandardCharsets.UTF_8)

            GETLNKURL.toHttpUrl().newBuilder()
                .addQueryParameter("link", finalText)
                .build()
        } else {
            GETSECONDARYURL.toHttpUrl().newBuilder()
                .addQueryParameter("id", episodeId)
                .build()
        }
        return client.newCall(GET(requestUrl.toString()))
            .execute()
            .use { response ->
                val data = response.body.string()
                decodeVideoLinks(data)
            }
    }

    private fun checkLinks(client: OkHttpClient, link: String): Boolean {
        if (!link.contains("https://")) return false

        return try {
            client.newCall(GET(link)).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val GETLNKURL = "https://v5.animedigitalnetwork.fr/api/getlink"
        private const val GETSECONDARYURL = "https://v5.animedigitalnetwork.fr/api/getsecondary"
    }
}

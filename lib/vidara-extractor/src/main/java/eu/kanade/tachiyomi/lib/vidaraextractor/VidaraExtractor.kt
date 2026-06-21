package eu.kanade.tachiyomi.lib.vidaraextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class VidaraExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()
    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val host = url.toHttpUrl().host
        val filecode = if (url.contains("#")) url.substringAfter("#") else url.substringAfter("/e/").substringBefore("?")

        return try {
            if (url.contains("upns.pro")) {
                extractUpns(host, filecode, url, prefix)
            } else {
                extractVidara(host, filecode, url, prefix)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractVidara(host: String, filecode: String, url: String, prefix: String): List<Video> {
        val body = """{"filecode":"$filecode","device":"android"}""".toRequestBody("application/json".toMediaType())
        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Origin", "https://$host")
            .add("Referer", "https://$host/")
            .build()

        val response = client.newCall(POST("https://$host/api/stream", headers, body)).execute()
        val data = json.decodeFromString<VidaraResponse>(response.body.string())

        return if (data.streaming_url != null) {
            playlistUtils.extractFromHls(
                data.streaming_url,
                url,
                videoNameGen = { quality -> "${prefix}Vidara:$quality" },
            )
        } else {
            emptyList()
        }
    }

    private fun extractUpns(host: String, id: String, url: String, prefix: String): List<Video> {
        val headers = Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .add("Referer", "https://$host/")
            .build()
        val response = client.newCall(GET("https://$host/api/v1/video?id=$id", headers)).execute()
        val hex = response.body.string()
        if (hex.startsWith("{")) {
            Log.e("VidaraExtractor", "API error: $hex")
            return emptyList()
        }

        val decrypted = decryptHex(hex)
        Log.d("VidaraExtractor", "Decrypted: ${decrypted.take(100)}...")

        val m3u8 = decrypted.substringAfter("\"file\":\"").substringBefore("\"")
        if (m3u8 == decrypted) {
            Log.e("VidaraExtractor", "Failed to extract m3u8 from decrypted data")
            return emptyList()
        }

        return playlistUtils.extractFromHls(
            m3u8,
            url,
            videoNameGen = { quality -> "${prefix}Upns:$quality" },
        )
    }

    private fun decryptHex(hex: String): String = hex.chunked(2)
        .map { it.toInt(16).toChar() }
        .joinToString("")
        .reversed()

    @Serializable
    data class VidaraResponse(
        val streaming_url: String? = null,
    )
}

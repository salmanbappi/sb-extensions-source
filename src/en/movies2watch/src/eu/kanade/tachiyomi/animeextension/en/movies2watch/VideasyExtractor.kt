package eu.kanade.tachiyomi.animeextension.en.movies2watch

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder

class VideasyExtractor(
    private val client: OkHttpClient,
    private val playlistUtils: PlaylistUtils,
    private val localProxy: LocalProxy = LocalProxy(client),
) {
    companion object {
        private const val API_BASE = "https://api.speedracelight.com"
        private const val PLAYER_ORIGIN = "https://player.videasy.to"
        private val F = intArrayOf(
            1116352408, 1899447441, 3049323471L.toInt(), 3921009573L.toInt(),
            961987163, 1508970993, 2453635748L.toInt(), 2870763221L.toInt(),
            3624381080L.toInt(), 310598401, 607225278, 1426881987,
            1925078388, 2162078206L.toInt(), 2614888103L.toInt(), 3248222580L.toInt(),
        )
        private val H = byteArrayOf(109, 118, 109, 49) // "mvm1"
    }

    private val headers = Headers.Builder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Referer", "$PLAYER_ORIGIN/")
        .set("Origin", PLAYER_ORIGIN)
        .build()

    private fun rotl(e: Int, t: Int): Int {
        val shift = t and 31
        return if (shift == 0) e else ((e shl shift) or (e ushr (32 - shift)))
    }

    private fun mixMurmur(value: Int): Int {
        var e = value
        e = e xor (e ushr 16)
        e = (e.toLong() * 2246822507L).toInt()
        e = e xor (e ushr 13)
        e = (e.toLong() * 3266489909L).toInt()
        e = e xor (e ushr 16)
        return e
    }

    private fun decryptPayload(encPayload: String, seed: String, tmdbId: Int): String? {
        val clean = encPayload.trim().trim('"').replace('-', '+').replace('_', '/')
        val pad = (4 - (clean.length % 4)) % 4
        val b64 = clean + "=".repeat(pad)
        val raw = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            try {
                Base64.decode(clean, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            } catch (_: Exception) {
                return null
            }
        }
        val length = raw.size
        if (length < 4) return null

        val sArr = IntArray(61)
        var fnv = 2166136261L.toInt()
        for (i in seed.indices) {
            fnv = (fnv xor seed[i].code).toLong().let { (it * 16777619L).toInt() }
        }
        val tmdbHash = tmdbId xor 2654435769L.toInt()
        var state = mixMurmur(mixMurmur(fnv) xor mixMurmur(tmdbHash))

        for (i in 0 until 8) {
            val modIdx = (state.toUInt() % 61u).toInt()
            state = rotl(state + 2654435769L.toInt(), 7 + (7 and i))
            sArr[modIdx] = state xor mixMurmur(state)
            state = mixMurmur(state + modIdx)
        }
        var acc = mixMurmur(2779096485L.toInt() xor state)

        val keyStream = ByteArray(length)
        var step = 0
        var byteIdx = 0
        while (byteIdx < length) {
            val modIdx = (acc.toUInt() % 61u).toInt()
            val hasElement = if (modIdx < sArr.size) -1 else 0
            val tableVal = if (modIdx < sArr.size) sArr[modIdx] else 0
            val mult = (2654435769L.toInt().toLong() * (step + 1).toLong()).toInt()
            val aVal = tableVal xor mult
            val lVal = (acc xor aVal) or (acc and aVal and hasElement)
            val rotPart = rotl(lVal + acc, 31 and modIdx) xor rotl(acc, 31 and (modIdx * 7))
            acc = mixMurmur(rotPart + 2654435769L.toInt())
            if (modIdx < sArr.size) {
                sArr[modIdx] = acc
            }
            step++
            val word = acc
            keyStream[byteIdx++] = (word and 0xFF).toByte()
            if (byteIdx < length) keyStream[byteIdx++] = ((word ushr 8) and 0xFF).toByte()
            if (byteIdx < length) keyStream[byteIdx++] = ((word ushr 16) and 0xFF).toByte()
            if (byteIdx < length) keyStream[byteIdx++] = ((word ushr 24) and 0xFF).toByte()
        }

        for (i in 0 until length) {
            raw[i] = (raw[i].toInt() xor keyStream[i].toInt()).toByte()
        }

        for (i in H.indices) {
            if (raw[i] != H[i]) return null
        }

        return String(raw, 4, length - 4, Charsets.UTF_8)
    }

    fun extract(url: String, title: String = "", prefix: String = "Videasy - "): List<Video> {
        val uri = runCatching { url.toHttpUrl() }.getOrNull()
        val pathSegments = uri?.pathSegments.orEmpty()
        val isMovie = url.contains("/movie/") || !url.contains("/tv/")

        val tmdbStr = pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() }
        val tmdbId = tmdbStr?.toIntOrNull()
            ?: Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(?:id|tmdb|mediaId)=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
            ?: return emptyList()

        val season = if (!isMovie) {
            pathSegments.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: Regex("""/tv/\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: Regex("""(?:season|s)=(\d+)""").find(url)?.groupValues?.get(1)
                ?: "1"
        } else {
            "1"
        }

        val episode = if (!isMovie) {
            pathSegments.getOrNull(3)?.takeIf { it.isNotBlank() }
                ?: Regex("""/tv/\d+/\d+/(\d+)""").find(url)?.groupValues?.get(1)
                ?: Regex("""(?:episode|e)=(\d+)""").find(url)?.groupValues?.get(1)
                ?: "1"
        } else {
            "1"
        }

        val seedResp = runCatching {
            client.newCall(GET("$API_BASE/seed?mediaId=$tmdbId", headers)).execute()
        }.getOrNull() ?: return emptyList()

        if (!seedResp.isSuccessful) return emptyList()
        val seedJson = runCatching { Json.parseToJsonElement(seedResp.body.string()).jsonObject }.getOrNull()
            ?: return emptyList()
        val seed = seedJson["seed"]?.jsonPrimitive?.content ?: return emptyList()

        val encodedTitle = URLEncoder.encode(title.ifBlank { "Media" }, "UTF-8")
        val endpoints = listOf(
            "/cdn/sources-with-title",
            "/vsrc/sources-with-title",
            "/m4uhd/sources-with-title",
        )

        val videos = mutableListOf<Video>()
        for (ep in endpoints) {
            val queryParams = if (isMovie) {
                "title=$encodedTitle&mediaType=movie&tmdbId=$tmdbId&enc=2&seed=${URLEncoder.encode(seed, "UTF-8")}"
            } else {
                "title=$encodedTitle&mediaType=tv&seasonId=$season&episodeId=$episode&tmdbId=$tmdbId&enc=2&seed=${URLEncoder.encode(seed, "UTF-8")}"
            }
            val epUrl = "$API_BASE$ep?$queryParams"

            val resp = runCatching { client.newCall(GET(epUrl, headers)).execute() }.getOrNull() ?: continue
            if (!resp.isSuccessful) continue
            val body = resp.body.string()
            val decrypted = decryptPayload(body, seed, tmdbId) ?: continue
            val jsonObj = runCatching { Json.parseToJsonElement(decrypted).jsonObject }.getOrNull() ?: continue

            val subtitles = mutableListOf<Track>()
            jsonObj["subtitles"]?.jsonArray?.forEach { subEl ->
                val subObj = subEl.jsonObject
                val subUrl = subObj["url"]?.jsonPrimitive?.content ?: return@forEach
                val subLang = subObj["language"]?.jsonPrimitive?.content
                    ?: subObj["lang"]?.jsonPrimitive?.content
                    ?: "Sub"
                if (subUrl.isNotBlank()) {
                    subtitles.add(Track(subUrl, subLang))
                }
            }

            val sources = jsonObj["sources"]?.jsonArray
            sources?.forEach { srcEl ->
                val srcObj = srcEl.jsonObject
                val srcUrl = srcObj["url"]?.jsonPrimitive?.content ?: return@forEach
                val quality = srcObj["quality"]?.jsonPrimitive?.content ?: "Auto"

                val videoTitle = "$prefix$quality"
                val playUrl = localProxy.getProxyUrl(srcUrl, headers)
                videos.add(
                    Video(
                        videoUrl = playUrl,
                        videoTitle = videoTitle,
                        headers = headers,
                        subtitleTracks = subtitles,
                    ),
                )
            }

            if (videos.isEmpty()) {
                val playlistUrl = jsonObj["playlist"]?.jsonPrimitive?.content
                if (!playlistUrl.isNullOrBlank()) {
                    val playUrl = localProxy.getProxyUrl(playlistUrl, headers)
                    videos.add(
                        Video(
                            videoUrl = playUrl,
                            videoTitle = "${prefix}Auto",
                            headers = headers,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            }

            if (videos.isNotEmpty()) break
        }

        return videos
    }
}

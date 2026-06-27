package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

class AnikotoExtractors(
    private val client: OkHttpClient,
    private val json: Json,
    private val webViewFetcher: WebViewFetcher? = null,
) {
    companion object {
        private const val TAG = "AnikotoExtractors"
        private val DATA_ID_REGEX = Regex("""data-id="(\d+)"""")
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""")
        private val NAME_REGEX = Regex("""NAME="([^"]+)"""")
        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // Limit concurrent variant playlist fetches to avoid rate limits (matches v4 APK)
        private val variantSemaphore = Semaphore(2)
    }

    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun logd(msg: String) = Log.d(TAG, msg)
    private fun loge(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    private fun vidtubePageHeaders(host: String): Headers = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://$host/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    private fun vidtubeApiHeaders(): Headers = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://vidtube.site/")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Accept", "*/*")
        .build()

    private fun kiwiHeaders(): Headers = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://vibeplayer.site/")
        .set("Accept", "*/*")
        .build()

    private fun segHeaders(host: String): Headers = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://$host/")
        .set("Accept", "*/*")
        .build()

    private fun extractHost(url: String): String? = try {
        url.substringAfter("://").substringBefore("/")
    } catch (e: Exception) {
        null
    }

    private fun isWafBlockedHost(url: String): Boolean = url.contains("mewstream.buzz", ignoreCase = true) ||
        url.contains("voltara.click", ignoreCase = true) ||
        url.contains("zaptrix.buzz", ignoreCase = true)

    private fun fetchString(url: String, headers: Headers): String {
        if (isWafBlockedHost(url) && webViewFetcher != null) {
            return webViewFetcher.fetchText(url)
        }
        val response = client.newCall(Request.Builder().url(url).headers(headers).build()).execute()
        if (!response.isSuccessful) {
            if (isWafBlockedHost(url) && webViewFetcher != null) {
                return webViewFetcher.fetchText(url)
            }
            throw RuntimeException("HTTP ${response.code}")
        }
        return response.body.string()
    }

    private fun testSegment(url: String, headers: Headers): Boolean = try {
        val request = Request.Builder().url(url).headers(headers).build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    } catch (e: Exception) {
        false
    }

    private fun inferLang(label: String): String = when {
        label.contains("English", ignoreCase = true) -> "eng"
        label.contains("Spanish", ignoreCase = true) -> "spa"
        label.contains("French", ignoreCase = true) -> "fra"
        label.contains("German", ignoreCase = true) -> "deu"
        label.contains("Portuguese", ignoreCase = true) -> "por"
        label.contains("Japanese", ignoreCase = true) -> "jpn"
        else -> "und"
    }

    private fun parseMasterPlaylist(text: String, masterUrl: String): List<VariantInfo> {
        val result = mutableListOf<VariantInfo>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val next = lines.getOrNull(i + 1)?.trim() ?: ""
                if (next.isNotEmpty() && !next.startsWith("#")) {
                    val bandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val resH = RESOLUTION_REGEX.find(line)?.groupValues?.get(1) ?: ""
                    val resolution = resH.toIntOrNull() ?: 0
                    var quality = NAME_REGEX.find(line)?.groupValues?.get(1) ?: ""
                    if (quality.isBlank() || quality == "Unknown") {
                        quality = if (resolution > 0) "${resolution}p" else "Unknown"
                    }
                    val fullUrl = URI(masterUrl).resolve(next).toString()
                    result.add(VariantInfo(fullUrl, bandwidth, quality, resolution))
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return result
    }

    private fun parseVariantSegments(text: String, variantUrl: String): List<LocalProxyServer.SegmentInfo> {
        val result = mutableListOf<LocalProxyServer.SegmentInfo>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("#EXTINF:")) {
                val duration = lines[i].substringAfter("#EXTINF:").substringBefore(",").toDoubleOrNull() ?: 0.0
                val next = lines.getOrNull(i + 1)?.trim() ?: ""
                if (next.isNotEmpty() && !next.startsWith("#")) {
                    val fullUrl = URI(variantUrl).resolve(next).toString()
                    result.add(LocalProxyServer.SegmentInfo(fullUrl, duration))
                    i += 2
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return result
    }

    suspend fun resolveVidTube(
        iframeUrl: String,
        audioType: String,
        hosterName: String,
    ): LocalProxyServer.AudioStream? {
        logi("resolveVidTube: START hoster=$hosterName audio=$audioType")
        return try {
            val host = extractHost(iframeUrl) ?: "vidtube.site"
            logi("resolveVidTube: [1/5] GET iframe page: $iframeUrl (host=$host)")
            val pageHtml = fetchString(iframeUrl, vidtubePageHeaders(host))

            val dataId = DATA_ID_REGEX.find(pageHtml)?.groupValues?.get(1)
            if (dataId.isNullOrEmpty()) {
                loge("resolveVidTube: no data-id found in iframe HTML (len=${pageHtml.length})")
                return null
            }
            logi("resolveVidTube: data-id=$dataId")

            val apiHeaders = vidtubeApiHeaders()

            var sourcesBody: String? = null
            try {
                val sourcesUrl = "https://$host/stream/getSources?id=$dataId&type=$audioType"
                logi("resolveVidTube: [2/5] GET getSources: $sourcesUrl")
                sourcesBody = fetchString(sourcesUrl, apiHeaders)
            } catch (e: Exception) {
                // Ignore
            }

            if (sourcesBody == null) {
                loge("resolveVidTube: no valid m3u8 in getSources response")
                return null
            }

            val sourcesResp = json.decodeFromString<VidTubeSourcesResponse>(sourcesBody)
            val masterM3u8 = sourcesResp.sources?.file
            if (masterM3u8.isNullOrEmpty() || !masterM3u8.startsWith("http")) {
                loge("resolveVidTube: no valid m3u8 in getSources response (sources.file='$masterM3u8')")
                return null
            }
            logi("resolveVidTube: [3/5] fetching master m3u8")

            val subtitles = sourcesResp.tracks.filter {
                it.file.startsWith("http") && it.label.isNotEmpty()
            }.map { track ->
                LocalProxyServer.SubtitleData(track.file, track.label, inferLang(track.label))
            }
            if (subtitles.isNotEmpty()) {
                logi("resolveVidTube: subs=${subtitles.size} track(s)")
            }

            val seg = segHeaders(host)
            val masterText = fetchString(masterM3u8, seg)
            if (!masterText.startsWith("#EXTM3U")) {
                loge("resolveVidTube: master is not m3u8 (starts with ${masterText.take(40)})")
                return null
            }

            val variants = parseMasterPlaylist(masterText, masterM3u8)
            if (variants.isEmpty()) {
                loge("resolveVidTube: no variants in master m3u8")
                return null
            }
            logi("resolveVidTube: ${variants.size} variants: ${variants.joinToString { it.quality }}")
            logi("resolveVidTube: [4/5] fetching ${variants.size} variant playlists")

            val variantDataList = mutableListOf<LocalProxyServer.VariantData>()
            for (vi in variants) {
                try {
                    variantSemaphore.withPermit {
                        val varText = fetchString(vi.url, seg)
                        val segs = parseVariantSegments(varText, vi.url)
                        logi("resolveVidTube:   variant ${vi.quality}(${vi.bandwidth}): ${segs.size} segments")
                        if (segs.isNotEmpty()) {
                            variantDataList.add(LocalProxyServer.VariantData(vi.quality, vi.bandwidth, vi.resolution, segs))
                        }
                    }
                } catch (e: Exception) {
                    loge("resolveVidTube:   variant ${vi.quality} fetch FAILED: ${e.message}")
                }
            }

            if (variantDataList.isEmpty()) {
                loge("resolveVidTube: no variants could be loaded")
                return null
            }

            val audioLabel = when (audioType) {
                "sub" -> "SUB"
                "dub" -> "DUB"
                "hsub" -> "HSUB"
                else -> audioType.uppercase()
            }

            logi("resolveVidTube: SUCCESS hoster=$hosterName audio=$audioLabel variants=${variantDataList.size} subs=${subtitles.size} referer=https://$host/")
            LocalProxyServer.AudioStream(audioType, audioLabel, hosterName, variantDataList, subtitles, seg)
        } catch (e: Exception) {
            loge("resolveVidTube: FAILED hoster=$hosterName audio=$audioType", e)
            null
        }
    }

    suspend fun resolveKiwi(
        iframeUrl: String,
        audioType: String,
        hosterName: String,
    ): LocalProxyServer.AudioStream? {
        logi("resolveKiwi: START hoster=$hosterName audio=$audioType")
        return try {
            val fragment = iframeUrl.substringAfter("#", "")
            if (fragment.isBlank()) {
                loge("resolveKiwi: no #fragment in iframe URL")
                return null
            }
            val decoded = try {
                val bytes = android.util.Base64.decode(fragment, 0)
                String(bytes, Charsets.ISO_8859_1)
            } catch (e: Exception) {
                loge("resolveKiwi: base64 decode failed", e)
                return null
            }

            if (!decoded.startsWith("http")) {
                loge("resolveKiwi: decoded fragment is not a URL: ${decoded.take(60)}")
                return null
            }
            logi("resolveKiwi: decoded m3u8=${decoded.take(80)}")
            logd("resolveKiwi: [2/4] fetching master m3u8")

            val headers = kiwiHeaders()
            val masterText = fetchString(decoded, headers)
            if (masterText.startsWith("#EXTM3U")) {
                val variants = parseMasterPlaylist(masterText, decoded)
                if (variants.isEmpty()) {
                    loge("resolveKiwi: no variants in master m3u8")
                    return null
                }
                logi("resolveKiwi: ${variants.size} variants: ${variants.joinToString { it.quality }}")
                logd("resolveKiwi: [3/4] fetching ${variants.size} variant playlists (NO ad filter)")

                val variantDataList = mutableListOf<LocalProxyServer.VariantData>()
                for (vi in variants) {
                    try {
                        variantSemaphore.withPermit {
                            val varText = fetchString(vi.url, headers)
                            val segs = parseVariantSegments(varText, vi.url)
                            logd("resolveKiwi:   variant ${vi.quality}: ${segs.size} segments (no filter)")
                            if (segs.isNotEmpty()) {
                                variantDataList.add(LocalProxyServer.VariantData(vi.quality, vi.bandwidth, vi.resolution, segs))
                            }
                        }
                    } catch (e: Exception) {
                        loge("resolveKiwi:   variant ${vi.quality} fetch FAILED: ${e.message}")
                    }
                }

                if (variantDataList.isEmpty()) {
                    loge("resolveKiwi: no variants could be loaded")
                    return null
                }

                val audioLabel = if (audioType == "sub") "H-SUB" else "A-DUB"

                logi("resolveKiwi: SUCCESS hoster=$hosterName audio=$audioLabel variants=${variantDataList.size} referer=https://vibeplayer.site/")
                LocalProxyServer.AudioStream(audioType, audioLabel, hosterName, variantDataList, emptyList(), headers)
            } else {
                loge("resolveKiwi: master is not m3u8 (starts with ${masterText.take(40)})")
                null
            }
        } catch (e: Exception) {
            loge("resolveKiwi: FAILED hoster=$hosterName audio=$audioType", e)
            null
        }
    }
}

package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request

class AnikotoExtractors(
    private val client: OkHttpClient,
    private val json: Json,
    private val webViewFetcher: WebViewFetcher? = null,
) {
    companion object {
        private const val TAG = "AnikotoExtractors"
        private val DATA_ID_REGEX = Regex("""data-id="([^"]+)"""")

        // CDN (`s=`) discovery, mirroring the player's own handling: the inline script compares the
        // CDNs it ships with (`"tcdn"!==s`), and the markup may link sources with an explicit `s=`.
        private val SC_IN_PAGE_JS = Regex(""""([a-z0-9_]{2,12})"!==s""")
        private val SC_IN_PAGE_URL = Regex("""[?&]s=([a-z0-9_]{2,12})""")
        private val SOURCES_ENDPOINTS = listOf("getSourcesNew", "getSources")
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // MegaPlay-style `enc` payload cipher (from lib/newclient.min.js):
        // AES-256-CBC, key "i?LMTAx0Q6,:}50U" zero-padded to 32 bytes,
        // IV "W0;27ToaUpl_P%'c", base64url body decrypting to {"file": "<master m3u8>"}.
        private const val MEGAPLAY_AES_KEY = "i?LMTAx0Q6,:}50U"
        private const val MEGAPLAY_AES_IV = "W0;27ToaUpl_P%'c"

        // Limit concurrent variant playlist fetches to avoid rate limits (matches v4 APK)
        private val variantSemaphore = Semaphore(2)
    }

    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun logd(msg: String) = Log.d(TAG, msg)
    private fun logw(msg: String) = Log.w(TAG, msg)
    private fun loge(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    private fun vidtubePageHeaders(host: String): Headers = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", "https://$host/")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .build()

    private fun vidtubeApiHeaders(host: String, referer: String): Headers = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Referer", referer)
        .set("Origin", "https://$host")
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

    private fun parseMasterPlaylist(text: String, masterUrl: String): List<VariantInfo> = HlsPlaylistParser.parseMasterPlaylist(text, masterUrl)

    /**
     * Decrypts a MegaPlay-style `enc` payload to its `{"file": "<master m3u8>"}` JSON.
     * Returns the master URL, or an empty string when the payload cannot be decoded.
     */
    private fun decryptEncPayload(enc: String): String {
        if (enc.isBlank()) return ""
        return try {
            val raw = Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP)
            val keyBytes = ByteArray(32)
            val keySrc = MEGAPLAY_AES_KEY.toByteArray(Charsets.UTF_8)
            System.arraycopy(keySrc, 0, keyBytes, 0, minOf(keySrc.size, 32))
            val ivBytes = MEGAPLAY_AES_IV.toByteArray(Charsets.UTF_8).copyOf(16)
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.IvParameterSpec(ivBytes),
            )
            val plain = cipher.doFinal(raw).toString(Charsets.UTF_8)
            json.decodeFromString<VidTubeSources>(plain).file
        } catch (e: Exception) {
            logw("decryptEncPayload: failed (${e.message})")
            ""
        }
    }

    /**
     * Decoded `getSources*` payload: the master playlist plus the tracks that came with it, and
     * the already-fetched [masterText] once the master has been verified to serve real HLS.
     */
    private data class SourcesData(
        val masterM3u8: String,
        val tracks: List<VidTubeTrack>,
        val masterText: String,
    )

    /**
     * The player appends an `s` (CDN selector) parameter to its `getSources*` calls and each value
     * maps to a different CDN host, so a source is only usable when the right `s` is used. Collect
     * every candidate we can see, most specific first: the embed URL's own `s=`, the CDNs named by
     * the inline player script (`"tcdn"!==s&&"bcdn"!==s`), any `?s=` links in the markup, and the
     * two known CDNs with a bare request as the last resort.
     */
    private fun buildSCandidates(iframeUrl: String, pageHtml: String): List<String> {
        val fromUrl = iframeUrl.substringAfter('?', "")
            .substringBefore('#')
            .split('&')
            .firstOrNull { it.startsWith("s=") }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }

        return buildList {
            fromUrl?.let { add(it) }
            SC_IN_PAGE_JS.findAll(pageHtml).forEach { add(it.groupValues[1]) }
            SC_IN_PAGE_URL.findAll(pageHtml).forEach { add(it.groupValues[1]) }
            add("bcdn")
            add("tcdn")
            add("")
        }.distinct().take(6)
    }

    /** Decodes a `getSources*` body into its master playlist + tracks, or null when unusable. */
    private fun parseSourcesBody(body: String): Pair<String, List<VidTubeTrack>>? {
        val resp = try {
            json.decodeFromString<VidTubeSourcesResponse>(body)
        } catch (e: Exception) {
            logw("resolveVidTube: JSON decode failed: ${e.message}; body=${body.take(200)}")
            return null
        }

        val master = resp.sources?.file?.takeIf { it.isNotEmpty() }
            ?: decryptEncPayload(resp.enc).takeIf { it.isNotEmpty() }
        if (master.isNullOrEmpty() || !master.startsWith("http")) {
            logw("resolveVidTube: no sources in payload (sources='${resp.sources?.file}' enc='${resp.enc.take(30)}…')")
            return null
        }

        val tracks = resp.tracks.filter { it.file.startsWith("http") && it.label.isNotEmpty() }
        return master to tracks
    }

    /**
     * Walks every `s` candidate and both `getSources*` endpoints, and only accepts a response once
     * its master playlist has been fetched and confirmed to be real HLS. Earlier CDN hosts for the
     * same episode go stale and answer with a WAF/challenge page, so verification is what keeps the
     * hoster list populated instead of failing every server.
     */
    private fun fetchSourcesData(
        host: String,
        dataId: String,
        audioType: String,
        sCandidates: List<String>,
        apiHeaders: Headers,
    ): SourcesData? {
        var usedWebView = false
        var lastError = "no candidates"

        for (s in sCandidates) {
            val suffix = if (s.isEmpty()) "" else "&s=$s"
            for (endpoint in SOURCES_ENDPOINTS) {
                val url = "https://$host/stream/$endpoint?id=$dataId&type=$audioType$suffix"
                logi("resolveVidTube: GET $endpoint (s=${s.ifEmpty { "-" }})")

                val body = try {
                    fetchString(url, apiHeaders)
                } catch (e: Exception) {
                    lastError = "$endpoint s=${s.ifEmpty { "-" }}: ${e.message}"
                    // Anti-bot hosts reject plain OkHttp outright; retry through the WebView once.
                    if (!usedWebView && webViewFetcher != null) {
                        usedWebView = true
                        try {
                            webViewFetcher!!.fetchText(url)
                        } catch (e2: Exception) {
                            logw("resolveVidTube: $endpoint via webview failed: ${e2.message}")
                            null
                        }
                    } else {
                        null
                    }
                } ?: continue

                val parsed = parseSourcesBody(body) ?: continue
                val (masterM3u8, tracks) = parsed

                val masterText = try {
                    fetchString(masterM3u8, segHeaders(extractHost(masterM3u8) ?: host))
                } catch (e: Exception) {
                    lastError = "$endpoint s=${s.ifEmpty { "-" }} master: ${e.message}"
                    logw("resolveVidTube: master did not verify ($lastError)")
                    continue
                }
                if (!masterText.startsWith("#EXTM3U")) {
                    lastError = "$endpoint s=${s.ifEmpty { "-" }} master is not m3u8"
                    logw("resolveVidTube: master is not m3u8 (starts with ${masterText.take(40)})")
                    continue
                }

                logi("resolveVidTube: master verified via $endpoint (s=${s.ifEmpty { "-" }})")
                return SourcesData(masterM3u8, tracks, masterText)
            }
        }

        loge("resolveVidTube: no valid m3u8 from ${SOURCES_ENDPOINTS.joinToString("/")} (host=$host, sCandidates=$sCandidates, last: $lastError)")
        return null
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

            val apiHeaders = vidtubeApiHeaders(host, iframeUrl)

            // The embed URL carries the CDN selector (`?s=tcdn`); the page JS names the others.
            val sCandidates = buildSCandidates(iframeUrl, pageHtml)
            logi("resolveVidTube: s candidates = $sCandidates")

            val sources = fetchSourcesData(host, dataId, audioType, sCandidates, apiHeaders) ?: return null
            val masterM3u8 = sources.masterM3u8
            val masterText = sources.masterText
            logi("resolveVidTube: [3/5] master already verified")

            val subtitles = sources.tracks.map { track ->
                LocalProxyServer.SubtitleData(track.file, track.label, inferLang(track.label))
            }
            if (subtitles.isNotEmpty()) {
                logi("resolveVidTube: subs=${subtitles.size} track(s)")
            }

            val variants = parseMasterPlaylist(masterText, masterM3u8)
            if (variants.isEmpty()) {
                loge("resolveVidTube: no variants in master m3u8")
                return null
            }
            logi("resolveVidTube: ${variants.size} variants: ${variants.joinToString { it.quality }}")
            logi("resolveVidTube: [4/5] fetching ${variants.size} variant playlists")

            val seg = segHeaders(host)

            val variantDataList = coroutineScope {
                variants.map { vi ->
                    async(Dispatchers.IO) {
                        variantSemaphore.withPermit {
                            try {
                                val varText = fetchString(vi.url, seg)
                                val segs = HlsPlaylistParser.parseVariantSegments(varText, vi.url)
                                logi("resolveVidTube:   variant ${vi.quality}(${vi.bandwidth}): ${segs.size} segments")
                                if (segs.isNotEmpty()) {
                                    LocalProxyServer.VariantData(
                                        quality = vi.quality,
                                        bandwidth = vi.bandwidth,
                                        resolution = vi.resolution,
                                        segments = segs,
                                        playlistUrl = vi.url,
                                        masterUrl = masterM3u8,
                                    )
                                } else {
                                    null
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                loge("resolveVidTube:   variant ${vi.quality} fetch FAILED: ${e.message}")
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
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
            LocalProxyServer.AudioStream(
                audioType = audioType,
                audioLabel = audioLabel,
                hosterName = hosterName,
                variants = variantDataList,
                subtitles = subtitles,
                headers = seg,
                iframeUrl = iframeUrl,
            )
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

                val variantDataList = coroutineScope {
                    variants.map { vi ->
                        async(Dispatchers.IO) {
                            variantSemaphore.withPermit {
                                try {
                                    val varText = fetchString(vi.url, headers)
                                    val segs = HlsPlaylistParser.parseVariantSegments(varText, vi.url)
                                    logd("resolveKiwi:   variant ${vi.quality}: ${segs.size} segments (no filter)")
                                    if (segs.isNotEmpty()) {
                                        LocalProxyServer.VariantData(
                                            quality = vi.quality,
                                            bandwidth = vi.bandwidth,
                                            resolution = vi.resolution,
                                            segments = segs,
                                            playlistUrl = vi.url,
                                            masterUrl = decoded,
                                        )
                                    } else {
                                        null
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    loge("resolveKiwi:   variant ${vi.quality} fetch FAILED: ${e.message}")
                                    null
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                if (variantDataList.isEmpty()) {
                    loge("resolveKiwi: no variants could be loaded")
                    return null
                }

                val audioLabel = if (audioType == "sub") "H-SUB" else "A-DUB"

                logi("resolveKiwi: SUCCESS hoster=$hosterName audio=$audioLabel variants=${variantDataList.size} referer=https://vibeplayer.site/")
                LocalProxyServer.AudioStream(
                    audioType = audioType,
                    audioLabel = audioLabel,
                    hosterName = hosterName,
                    variants = variantDataList,
                    subtitles = emptyList(),
                    headers = headers,
                    iframeUrl = iframeUrl,
                )
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

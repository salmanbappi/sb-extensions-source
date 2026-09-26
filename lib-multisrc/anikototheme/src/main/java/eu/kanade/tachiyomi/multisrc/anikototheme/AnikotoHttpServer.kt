package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.HttpServer
import eu.kanade.tachiyomi.animesource.model.Track
import fi.iki.elonen.NanoHTTPD
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.api.get
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class AnikotoHttpServer(
    client: OkHttpClient,
    private val segmentHeaders: Headers,
    private val webViewFetcher: WebViewFetcher? = null,
) : HttpServer() {
    companion object {
        private const val MAX_CACHE_ENTRIES = 30
        private const val MAX_CONCURRENT_PREFETCHES = 5

        // Hosters (VidTube/Kiwi) mint segment URLs that expire some minutes after the playlist is
        // resolved. Re-fetch the variant playlist on this cadence while playing so segments past
        // ~20 minutes keep valid URLs instead of 403ing and killing playback. When the variant
        // playlist URL itself expires, the refresh escalates: master re-mint, then a full
        // re-resolve of the hoster chain (see [refreshVariant]).
        private const val VARIANT_REFRESH_INTERVAL_MS = 120000L

        // How long a player-facing segment fetch waits for an in-flight background refresh to
        // finish before trying its own re-mint (the player socket timeout is far longer).
        private const val REFRESH_WAIT_MAX_MS = 10000L
        private const val TAG = "AnikotoProxy"
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** Statuses that mean "the CDN wants a real browser" rather than a bad URL. */
        private val BROWSER_FALLBACK_CODES = setOf(403, 429, 503)

        /**
         * Subtitle tracks for [audioType] as placeholder URLs. The app rewrites
         * [HttpServer.PLACEHOLDER_URL] to the live local port (see `Video.copyHttpServer`), which is
         * also what makes `Video.usesHttpServer()` detect that this video needs a local server.
         */
        fun subtitleTracksFor(playlist: Playlist?, audioType: String): List<Track> {
            val streams = playlist?.streams ?: return emptyList()
            val stream = streams.firstOrNull { it.audioType == audioType } ?: return emptyList()
            return stream.subtitles.mapIndexed { i, sub ->
                Track("${HttpServer.PLACEHOLDER_URL}/sub/$audioType/$i", sub.label)
            }
        }
    }

    private val fetchClient: OkHttpClient = client

    var prefetchCount: Int = 10
    private val lastActivityMs = AtomicLong(System.currentTimeMillis())
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AnikotoProxy-Worker").apply { isDaemon = true }
    }

    private val proxyCacheDir: File by lazy {
        val baseDir = try {
            uy.kohesive.injekt.Injekt.get<android.app.Application>().cacheDir
        } catch (_: Exception) {
            File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        }
        File(baseDir, "anikoto_proxy_cache").apply { mkdirs() }
    }

    private data class CachedSegment(
        val file: File,
        val size: Long,
    )

    private val segmentCache = ConcurrentHashMap<String, CachedSegment>()
    private val cacheOrder = Collections.synchronizedList(mutableListOf<String>())

    private val fetching = ConcurrentHashMap<String, Boolean>()
    private val prefetchGeneration = AtomicLong(0L)
    private val activePrefetches = AtomicLong(0L)

    var playlist: Playlist? = null

    /**
     * Full re-resolve hook, wired by the theme. Re-runs the hoster resolution from the original
     * iframe URL so the proxy can mint a brand-new URL chain (master + variant + segments) when
     * both the captured variant and master playlist URLs have expired mid-playback.
     */
    @Volatile
    var reResolveStream: ((stream: AudioStream) -> AudioStream?)? = null

    private val variantStates = ConcurrentHashMap<String, VariantState>()

    /**
     * Reply metadata for the request currently being served. The handlers still write their body
     * into an [OutputStream]; this records the status/content type (or a file to stream) so [serve]
     * can turn it into a NanoHTTPD response. Thread-local so concurrent requests cannot race.
     */
    private class Reply {
        var status: Int = 200
        var contentType: String = "application/octet-stream"
        var file: File? = null
    }

    private val reply = ThreadLocal.withInitial { Reply() }

    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun logw(msg: String) = Log.w(TAG, msg)
    private fun loge(msg: String) = Log.e(TAG, msg)

    data class VariantData(
        val quality: String,
        val bandwidth: Int,
        val resolution: Int,
        val segments: List<SegmentInfo>,
        // Upstream variant playlist URL, kept so segment URLs can be re-minted mid-session
        // (the URLs handed out at resolve time expire after a limited window).
        val playlistUrl: String = "",
        // Upstream master playlist URL, kept so the proxy can re-mint a brand-new variant URL
        // when the captured variant playlist URL itself has expired.
        val masterUrl: String = "",
    )

    data class SegmentInfo(
        val url: String,
        val duration: Double,
    )

    data class SubtitleData(
        val url: String,
        val label: String,
        val language: String,
    )

    data class AudioStream(
        val audioType: String,
        val audioLabel: String,
        val hosterName: String,
        val variants: List<VariantData>,
        val subtitles: List<SubtitleData>,
        val headers: Headers,
        // Original hoster iframe URL. Used as the entry point for a full re-resolve when both the
        // variant and master playlist URLs have expired mid-playback.
        val iframeUrl: String = "",
    )

    data class Playlist(
        val streams: List<AudioStream>,
    )

    /**
     * Mutable per-variant state. Segment URLs are refreshed in place as the session plays, so the
     * extractors' resolve-time snapshot must not be treated as immutable.
     */
    private class VariantState(seed: VariantData, headers: Headers) {
        @Volatile
        var playlistUrl: String = seed.playlistUrl

        @Volatile
        var masterUrl: String = seed.masterUrl

        @Volatile
        var headers: Headers = headers

        val quality: String = seed.quality

        @Volatile
        var refreshedAtMs: Long = System.currentTimeMillis()

        val segments: MutableList<SegmentInfo> = CopyOnWriteArrayList(seed.segments)
        val refreshInProgress = AtomicBoolean(false)
    }

    fun clearCache() {
        synchronized(cacheOrder) {
            segmentCache.values.forEach { runCatching { it.file.delete() } }
            segmentCache.clear()
            cacheOrder.clear()
        }
        runCatching {
            proxyCacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    override fun start() {
        clearCache()
        variantStates.clear()
        lastActivityMs.set(System.currentTimeMillis())
        super.start()
        logi("Proxy server started on $url (prefetch=$prefetchCount% of total segments)")
    }

    override fun stop() {
        logi("Stopping proxy server")
        runCatching { executor.shutdownNow() }
        reResolveStream = null
        clearCache()
        fetching.clear()
        variantStates.clear()
        prefetchGeneration.incrementAndGet()
        activePrefetches.set(0L)
        runCatching { super.stop() }
    }

    /**
     * NanoHTTPD entry point. The request handlers below still write their body through an
     * [OutputStream]; we capture that per request and translate it into a NanoHTTPD [NanoHTTPD.Response],
     * so the streaming logic (segment re-mint, prefetch, disk cache) is unchanged from the previous
     * hand-rolled socket server. The app owns the lifecycle: it starts us via `createHttpServer()`
     * and substitutes [HttpServer.PLACEHOLDER_URL] with [url] before handing the video to the player.
     */
    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        touchActivity()
        val meta = Reply()
        reply.set(meta)
        val buffer = ByteArrayOutputStream()
        try {
            routeRequest(session.uri ?: "/", buffer)
        } catch (e: Exception) {
            loge("Route error for ${session.uri}: ${e.message}")
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Internal Server Error: ${e.message}",
            )
        }

        val status = NanoHTTPD.Response.Status.lookup(meta.status) ?: NanoHTTPD.Response.Status.OK
        val file = meta.file
        val response = if (file != null && file.exists()) {
            NanoHTTPD.newFixedLengthResponse(status, meta.contentType, FileInputStream(file), file.length())
        } else {
            val bytes = buffer.toByteArray()
            NanoHTTPD.newFixedLengthResponse(status, meta.contentType, ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    fun onQualitySwitch() {
        logi("Quality switch — canceling prefetches, bumping generation")
        prefetchGeneration.incrementAndGet()
        fetching.clear()
    }

    private fun touchActivity() {
        lastActivityMs.set(System.currentTimeMillis())
    }

    private fun routeRequest(path: String, output: OutputStream) {
        logi("REQUEST: $path")
        val parts = path.trim('/').split('/')
        if (parts.size >= 3 && parts[0] == "variant") {
            val audioType = parts[1]
            val quality = parts[2].removeSuffix(".m3u8")
            serveVariantPlaylist(audioType, quality, output)
        } else if (parts.size >= 4 && parts[0] == "seg") {
            val audioType = parts[1]
            val quality = parts[2]
            val index = parts[3].toIntOrNull()
            if (index != null) {
                serveSegment(audioType, quality, index, output)
            } else {
                sendError(output, 400, "Bad segment index")
            }
        } else if (parts.size >= 3 && parts[0] == "sub") {
            val audioType = parts[1]
            val subIndex = parts[2].toIntOrNull()
            if (subIndex != null) {
                serveSubtitle(audioType, subIndex, output)
            } else {
                sendError(output, 400, "Bad subtitle index")
            }
        } else {
            sendError(output, 404, "Not Found: $path")
        }
    }

    private fun serveVariantPlaylist(audioType: String, quality: String, output: OutputStream) {
        val pl = playlist ?: return sendError(output, 500, "No playlist")
        val stream = pl.streams.firstOrNull { it.audioType == audioType }
            ?: return sendError(output, 404, "Audio type not found: $audioType")
        val variant = stream.variants.firstOrNull { it.quality == quality }
            ?: return sendError(output, 404, "Quality not found: $quality")
        val state = variantState(stream, variant)

        val maxDuration = state.segments.maxOfOrNull { it.duration } ?: 0.0
        val targetDuration = (maxDuration.toInt() + 1)

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:$targetDuration\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        state.segments.forEachIndexed { i, seg ->
            sb.append("#EXTINF:${seg.duration},\n")
            sb.append("$url/seg/$audioType/$quality/$i\n")
        }
        sb.append("#EXT-X-ENDLIST\n")
        sendText(output, "application/vnd.apple.mpegurl", sb.toString())
    }

    private fun serveSegment(audioType: String, quality: String, index: Int, output: OutputStream) {
        val pl = playlist ?: return sendError(output, 500, "No playlist")
        val stream = pl.streams.firstOrNull { it.audioType == audioType }
            ?: return sendError(output, 404, "Audio type not found: $audioType")
        val variant = stream.variants.firstOrNull { it.quality == quality }
            ?: return sendError(output, 404, "Quality not found: $quality")
        val state = variantState(stream, variant)
        if (index !in state.segments.indices) {
            return sendError(output, 404, "Segment $index not found")
        }

        val cacheKey = "$audioType/$quality/$index"
        touchActivity()
        val cached = segmentCache[cacheKey]
        if (cached != null && cached.file.exists()) {
            logi("CACHE HIT: $cacheKey (${cached.size} bytes)")
            sendFile(output, "video/MP2T", cached.file)
            maybeRefreshVariant(stream, state)
            triggerPrefetch(state, audioType, quality, index)
            return
        }

        if (fetching[cacheKey] == true) {
            logi("WAIT FOR FETCH: $cacheKey")
            var waited = 0
            while (fetching[cacheKey] == true && waited < 15000) {
                Thread.sleep(50L)
                waited += 50
            }
            val waitedSegment = segmentCache[cacheKey]
            if (waitedSegment != null && waitedSegment.file.exists()) {
                logi("FETCH WAIT SUCCEEDED: $cacheKey (${waitedSegment.size} bytes)")
                sendFile(output, "video/MP2T", waitedSegment.file)
                maybeRefreshVariant(stream, state)
                triggerPrefetch(state, audioType, quality, index)
                return
            }
            logw("FETCH WAIT FAILED, fetching synchronously: $cacheKey")
        }

        logi("FETCH: $cacheKey → ${state.segments[index].url.take(80)}...")
        fetching[cacheKey] = true
        try {
            // Refresh the captured segment URLs if they are getting old (see VARIANT_REFRESH_INTERVAL_MS).
            maybeRefreshVariant(stream, state)
            val segBytes = fetchSegmentWithRemint(state, index, stream)
            val offset = detectSegmentOffset(segBytes)
            requireValidSegment(segBytes, offset, cacheKey)
            val servedSize = segBytes.size - offset
            val firstByte = if (servedSize > 0) segBytes[offset] else 0.toByte()
            val isTsSync = firstByte == 0x47.toByte()
            logi("STRIPPED: $cacheKey ${segBytes.size}→$servedSize bytes, first=0x${String.format("%02x", firstByte)}, tsSync=$isTsSync")
            if (!isTsSync && servedSize > 0) {
                val hexStr = segBytes.copyOfRange(offset, min(offset + 8, segBytes.size)).joinToString("") { String.format("%02x", it) }
                logw("WARNING: segment $cacheKey doesn't start with 0x47! First 8 bytes: $hexStr")
            }
            cacheSegment(cacheKey, segBytes, offset)
            val newlyCached = segmentCache[cacheKey]
            if (newlyCached != null && newlyCached.file.exists()) {
                sendFile(output, "video/MP2T", newlyCached.file)
            } else {
                sendBytes(output, "video/MP2T", segBytes, offset)
            }
            triggerPrefetch(state, audioType, quality, index)
        } catch (e: Exception) {
            loge("Segment fetch failed ($cacheKey): ${e.message}")
            sendError(output, 502, "Fetch error: ${e.message}")
        } finally {
            fetching.remove(cacheKey)
        }
    }

    /**
     * Fetches a segment and aggressively escalates through the full URL chain when it fails:
     * retry the current segment, refresh the variant, re-mint the variant through the master, then
     * fully re-resolve the hoster. This applies to transport failures as well as HTTP status errors.
     */
    private fun fetchSegmentWithRemint(
        state: VariantState,
        index: Int,
        stream: AudioStream,
    ): ByteArray {
        val firstUrl = state.segments.getOrNull(index)?.url
            ?: throw RuntimeException("Segment $index no longer available")
        try {
            return fetchSegment(firstUrl, state.headers, retry = true)
        } catch (firstError: Exception) {
            logw("Segment $index failed (${firstError.message}) — forcing URL-chain re-mint")
            if (!refreshVariant(stream, state, waitForExisting = true)) throw firstError
        }

        val variantUrl = state.segments.getOrNull(index)?.url
            ?: throw RuntimeException("Segment $index unavailable after variant refresh")
        try {
            return fetchSegment(variantUrl, state.headers, retry = true)
        } catch (variantError: Exception) {
            logw("Segment $index still failed (${variantError.message}) — forcing master/full re-mint")
            if (!refreshVariant(stream, state, waitForExisting = true, startAtMaster = true)) throw variantError
        }

        val reResolvedUrl = state.segments.getOrNull(index)?.url
            ?: throw RuntimeException("Segment $index unavailable after full re-mint")
        return fetchSegment(reResolvedUrl, state.headers, retry = true)
    }

    private fun isWafBlockedHost(url: String): Boolean = url.contains("mewstream.buzz") || url.contains("voltara.click") || url.contains("zaptrix.buzz")

    private fun variantState(stream: AudioStream, variant: VariantData): VariantState = variantStates.computeIfAbsent("${stream.audioType}/${variant.quality}") { VariantState(variant, stream.headers) }

    /**
     * Re-mints segment URLs in the background when the current snapshot is older than
     * [VARIANT_REFRESH_INTERVAL_MS]. Never blocks the player's segment request thread on an
     * upstream playlist fetch; the forced refresh on a 4xx covers the case where an old URL dies
     * before this background refresh lands.
     */
    private fun maybeRefreshVariant(stream: AudioStream, state: VariantState) {
        if (System.currentTimeMillis() - state.refreshedAtMs < VARIANT_REFRESH_INTERVAL_MS) return
        val gen = prefetchGeneration.get()
        executor.execute {
            if (prefetchGeneration.get() == gen) {
                refreshVariant(stream, state)
            }
        }
    }

    /**
     * Refreshes the complete expiring URL chain. It first tries the current variant URL, then asks
     * the master playlist for a new variant URL, and finally invokes [reResolveStream] to repeat the
     * hoster handshake. Old segments are retained if an upstream response is truncated.
     */
    private fun refreshVariant(
        stream: AudioStream,
        state: VariantState,
        waitForExisting: Boolean = false,
        startAtMaster: Boolean = false,
    ): Boolean {
        if (!state.refreshInProgress.compareAndSet(false, true)) {
            if (!waitForExisting) return true
            val startedAt = System.currentTimeMillis()
            while (state.refreshInProgress.get() && System.currentTimeMillis() - startedAt < REFRESH_WAIT_MAX_MS) {
                Thread.sleep(50L)
            }
            return !state.refreshInProgress.get() &&
                System.currentTimeMillis() - state.refreshedAtMs < VARIANT_REFRESH_INTERVAL_MS
        }

        try {
            if (!startAtMaster && refreshFromVariantUrl(state, state.headers)) {
                logi("VARIANT REFRESHED: ${stream.audioType}/${state.quality} → ${state.segments.size} segments")
                return true
            }

            logw("Variant URL refresh failed; trying master re-mint for ${stream.audioType}/${state.quality}")
            if (refreshFromMasterUrl(state, state.headers)) {
                logi("MASTER RE-MINTED: ${stream.audioType}/${state.quality} → ${state.segments.size} segments")
                return true
            }

            logw("Master re-mint failed; fully re-resolving ${stream.audioType}/${state.quality}")
            if (refreshFromResolvedStream(stream, state)) {
                logi("FULL RE-RESOLVE SUCCEEDED: ${stream.audioType}/${state.quality} → ${state.segments.size} segments")
                return true
            }
        } catch (e: Exception) {
            logw("URL-chain refresh failed: ${e.message}")
        } finally {
            state.refreshInProgress.set(false)
        }
        return false
    }

    private fun refreshFromVariantUrl(state: VariantState, headers: Headers): Boolean {
        val url = state.playlistUrl.takeIf { it.isNotBlank() } ?: return false
        return try {
            val fresh = HlsPlaylistParser.parseVariantSegments(fetchPlaylistText(url, headers), url)
            updateVariantState(state, fresh)
        } catch (e: Exception) {
            logw("Variant playlist refresh failed (${e.message})")
            false
        }
    }

    private fun refreshFromMasterUrl(state: VariantState, headers: Headers): Boolean {
        val masterUrl = state.masterUrl.takeIf { it.isNotBlank() } ?: return false
        return try {
            val master = HlsPlaylistParser.parseMasterPlaylist(fetchPlaylistText(masterUrl, headers), masterUrl)
            val variant = selectMatchingVariant(master, state.quality) ?: return false
            val fresh = HlsPlaylistParser.parseVariantSegments(fetchPlaylistText(variant.url, headers), variant.url)
            if (fresh.isEmpty()) return false
            state.playlistUrl = variant.url
            updateVariantState(state, fresh)
        } catch (e: Exception) {
            logw("Master playlist refresh failed (${e.message})")
            false
        }
    }

    private fun refreshFromResolvedStream(stream: AudioStream, state: VariantState): Boolean {
        val resolver = reResolveStream ?: return false
        return try {
            val resolved = resolver(stream) ?: return false
            val variant = resolved.variants.firstOrNull { it.quality == state.quality }
                ?: selectClosestVariant(resolved.variants, state.quality)
                ?: return false
            if (variant.segments.isEmpty()) return false
            state.playlistUrl = variant.playlistUrl
            state.masterUrl = variant.masterUrl
            state.headers = resolved.headers
            updateVariantState(state, variant.segments)
        } catch (e: Exception) {
            logw("Full stream re-resolve failed (${e.message})")
            false
        }
    }

    private fun updateVariantState(state: VariantState, fresh: List<SegmentInfo>): Boolean {
        if (fresh.isEmpty()) return false
        for (i in fresh.indices) {
            if (i < state.segments.size) {
                state.segments[i] = fresh[i]
            } else {
                state.segments.add(fresh[i])
            }
        }
        state.refreshedAtMs = System.currentTimeMillis()
        return true
    }

    private fun selectMatchingVariant(variants: List<VariantInfo>, quality: String): VariantInfo? = variants.firstOrNull { it.quality == quality }
        ?: variants.minByOrNull { kotlin.math.abs(it.resolution - quality.filter(Char::isDigit).toIntOrNull().orZero()) }

    private fun selectClosestVariant(variants: List<VariantData>, quality: String): VariantData? = variants.minByOrNull { kotlin.math.abs(it.resolution - quality.filter(Char::isDigit).toIntOrNull().orZero()) }

    private fun Int?.orZero(): Int = this ?: 0

    private fun fetchPlaylistText(url: String, headers: Headers): String {
        if (isWafBlockedHost(url) && webViewFetcher != null) {
            try {
                return webViewFetcher.fetchText(url)
            } catch (e: Exception) {
                loge("fetchPlaylistText: WebView fetch failed for ${url.take(60)}: ${e.message}")
                throw e
            }
        }
        val request = Request.Builder().url(url).headers(headers).build()
        try {
            fetchClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpstreamHttpException(response.code, url, "Upstream ${response.code}")
                }
                val body = response.body ?: throw RuntimeException("Empty body")
                val text = body.string()
                if (!text.startsWith("#EXTM3U")) {
                    throw RuntimeException("Not an m3u8 (starts with ${text.take(30)})")
                }
                return text
            }
        } catch (e: UpstreamHttpException) {
            if (e.code !in BROWSER_FALLBACK_CODES) throw e
            return fetchPlaylistViaWebView(url, e)
        } catch (e: Exception) {
            return fetchPlaylistViaWebView(url, e)
        }
    }

    /** CDN hosts rotate behind Cloudflare; the app's WebView is what gets past the challenge. */
    private fun fetchPlaylistViaWebView(url: String, cause: Exception): String {
        val fallback = webViewFetcher
            ?: throw (cause as? RuntimeException ?: RuntimeException(cause.message, cause))
        logi("fetchPlaylistText: ${cause.message?.take(50)} — retrying via WebView for ${url.take(60)}")
        val text = fallback.fetchText(url)
        if (!text.startsWith("#EXTM3U")) {
            throw RuntimeException("WebView response is not an m3u8 (starts with ${text.take(30)})")
        }
        return text
    }

    /** Thrown when an upstream host rejects a request (e.g. an expired segment URL). */
    private class UpstreamHttpException(
        val code: Int,
        val url: String,
        message: String,
    ) : RuntimeException(message)

    private fun fetchSegment(url: String, headers: Headers, retry: Boolean = true): ByteArray {
        if (isWafBlockedHost(url) && webViewFetcher != null) {
            try {
                return webViewFetcher.fetchBytes(url)
            } catch (e: Exception) {
                loge("fetchSegment: WebView fetch failed for ${url.take(60)}: ${e.message}")
                throw e
            }
        }
        val request = Request.Builder().url(url).headers(headers).build()
        var lastError: Exception? = null
        var usedWebView = false
        val attempts = if (retry) 3 else 1
        for (i in 0 until attempts) {
            try {
                fetchClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw UpstreamHttpException(response.code, url, "Upstream ${response.code}")
                    }
                    val body = response.body ?: throw RuntimeException("Empty body")
                    return body.bytes()
                }
            } catch (e: Exception) {
                lastError = e
                // Any CDN host may be Cloudflare-challenged, not just the known WAF ones, so try the
                // app's WebView once before giving up on this segment.
                val browserNeeded = e !is UpstreamHttpException || e.code in BROWSER_FALLBACK_CODES
                if (!usedWebView && browserNeeded && webViewFetcher != null) {
                    usedWebView = true
                    logi("fetchSegment: ${e.message?.take(50)} — retrying via WebView for ${url.take(60)}")
                    try {
                        return webViewFetcher.fetchBytes(url)
                    } catch (eFallback: Exception) {
                        loge("fetchSegment: WebView fallback also failed: ${eFallback.message}")
                        lastError = eFallback
                    }
                }
                logw("Fetch attempt ${i + 1} failed: ${e.message}")
                if (i < attempts - 1) {
                    Thread.sleep(500L)
                }
            }
        }
        throw lastError ?: RuntimeException("Unknown fetch error")
    }

    private fun triggerPrefetch(
        state: VariantState,
        audioType: String,
        quality: String,
        currentIndex: Int,
    ) {
        if (prefetchCount <= 0) return
        val gen = prefetchGeneration.get()
        val totalSegs = state.segments.size
        if (totalSegs <= 0) return
        val prefetchAhead = max((prefetchCount * totalSegs) / 100, 1)
        val maxIndex = min(currentIndex + prefetchAhead, totalSegs - 1)
        var submitted = 0

        for (i in (currentIndex + 1)..maxIndex) {
            val key = "$audioType/$quality/$i"
            if (!segmentCache.containsKey(key) && fetching[key] != true) {
                submitted++
                if (submitted <= MAX_CONCURRENT_PREFETCHES) {
                    executor.execute {
                        triggerPrefetchTask(gen, key, state, i)
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun triggerPrefetchTask(gen: Long, key: String, state: VariantState, index: Int) {
        if (prefetchGeneration.get() != gen) {
            logi("PREFETCH CANCELED (gen changed): $key")
            return
        }
        if (segmentCache.containsKey(key) || fetching[key] == true) return
        val inFlight = activePrefetches.incrementAndGet()
        if (inFlight > MAX_CONCURRENT_PREFETCHES) {
            activePrefetches.decrementAndGet()
            return
        }
        fetching[key] = true
        try {
            if (prefetchGeneration.get() != gen) return
            val stream = playlist?.streams?.firstOrNull { it.audioType == key.substringBefore("/") }
                ?: return
            // Keep prefetched URLs young so playback never catches an expired segment.
            maybeRefreshVariant(stream, state)
            val seg = state.segments.getOrNull(index) ?: return
            logi("PREFETCH: $key → ${seg.url.take(60)}...")
            val bytes = try {
                fetchSegment(seg.url, state.headers, retry = false)
            } catch (e: Exception) {
                if (refreshVariant(stream, state, waitForExisting = true)) {
                    val reMinted = state.segments.getOrNull(index) ?: throw e
                    logi("PREFETCH RETRY after ${e.message}: $key")
                    fetchSegment(reMinted.url, state.headers, retry = true)
                } else {
                    throw e
                }
            }
            val offset = detectSegmentOffset(bytes)
            requireValidSegment(bytes, offset, key)
            cacheSegment(key, bytes, offset)
            logi("PREFETCH DONE: $key (${bytes.size - offset} bytes)")
        } catch (e: Exception) {
            logw("PREFETCH FAILED: $key — ${e.message}")
        } finally {
            fetching.remove(key)
            activePrefetches.decrementAndGet()
        }
    }

    private fun serveSubtitle(audioType: String, subIndex: Int, output: OutputStream) {
        val pl = playlist ?: return sendError(output, 500, "No playlist")
        val stream = pl.streams.firstOrNull { it.audioType == audioType }
            ?: return sendError(output, 404, "Audio type not found: $audioType")
        val sub = stream.subtitles.getOrNull(subIndex)
            ?: return sendError(output, 404, "Subtitle $subIndex not found")

        try {
            val request = Request.Builder().url(sub.url).headers(stream.headers).build()
            fetchClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    loge("Subtitle fetch failed: ${response.code} for ${sub.url}")
                    sendError(output, 502, "Subtitle fetch ${response.code}")
                    return
                }
                val body = response.body ?: throw RuntimeException("Empty body")
                val text = body.string()
                logi("SUBTITLE served: $audioType/$subIndex (${text.length} chars)")
                sendText(output, "text/vtt", text)
            }
        } catch (e: Exception) {
            loge("Subtitle fetch error: ${e.message}")
            sendError(output, 502, "Subtitle error: ${e.message}")
        }
    }

    fun getSubtitleTracks(audioType: String): List<Track> = subtitleTracksFor(playlist, audioType)

    private fun requireValidSegment(data: ByteArray, offset: Int, key: String) {
        val servedSize = data.size - offset
        if (servedSize <= 0) throw RuntimeException("Empty segment payload: $key")

        // Anikoto's known providers return MPEG-TS, sometimes hidden after a PNG wrapper. Some CDNs
        // answer an expired/missing segment with an HTTP 200 anti-bot or error page (HTML/JSON);
        // caching that as video makes the player fail later and blocks the retry/re-mint path.
        // Only reject clearly textual payloads — MPEG-TS and fMP4/CMAF both start with binary bytes.
        if (data[offset] != 0x47.toByte() && looksLikeTextPayload(data, offset)) {
            val preview = data.copyOfRange(offset, min(offset + 64, data.size))
                .toString(Charsets.UTF_8)
                .replace(Regex("\\s+"), " ")
                .take(60)
            throw RuntimeException("Upstream returned a non-video page for $key: ${preview.take(60)}")
        }
    }

    private fun looksLikeTextPayload(data: ByteArray, offset: Int): Boolean {
        val sample = data.copyOfRange(offset, min(offset + 32, data.size))
        if (sample.isEmpty()) return false
        // Allow only ASCII printable bytes plus whitespace — binary media data fails this.
        return sample.all { it in 0x09..0x0D.toByte() || it in 0x20..0x7E.toByte() }
    }

    private fun cacheSegment(key: String, data: ByteArray, offset: Int) {
        val servedSize = data.size - offset
        if (servedSize <= 0) return

        try {
            val safeKey = key.replace("/", "_")
            val tempFile = File(proxyCacheDir, "seg_${safeKey}_${System.currentTimeMillis()}.tmp")
            tempFile.outputStream().use { os ->
                os.write(data, offset, servedSize)
            }

            val cached = CachedSegment(tempFile, servedSize.toLong())

            synchronized(cacheOrder) {
                val existing = segmentCache.remove(key)
                if (existing != null) {
                    cacheOrder.remove(key)
                    existing.file.delete()
                }

                while (cacheOrder.isNotEmpty() && segmentCache.size >= MAX_CACHE_ENTRIES) {
                    val evictedKey = cacheOrder.removeAt(0)
                    segmentCache.remove(evictedKey)?.file?.delete()
                }

                segmentCache[key] = cached
                cacheOrder.add(key)
            }
        } catch (e: Exception) {
            logw("Failed to write segment to disk cache: ${e.message}")
        }
    }

    private fun sendFile(output: OutputStream, contentType: String, file: File) {
        // Streamed straight from disk by serve(); record it rather than buffering the segment.
        val meta = reply.get()
        meta.status = 200
        meta.contentType = contentType
        meta.file = file
    }

    private fun detectSegmentOffset(data: ByteArray): Int {
        if (data.size < 8) return 0
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return 0
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return 0
        val tsLength = data.size - videoStart
        if (tsLength < 188) return videoStart
        val iMin = min(tsLength - 188, 400)
        for (offset in 0 until iMin) {
            val actualOffset = videoStart + offset
            if (data[actualOffset] == 0x47.toByte() && data[actualOffset + 188] == 0x47.toByte()) {
                return actualOffset
            }
        }
        return videoStart
    }

    private fun sendText(output: OutputStream, contentType: String, text: String) {
        val meta = reply.get()
        meta.status = 200
        meta.contentType = contentType
        meta.file = null
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {
            logw("sendText failed: ${e.message}")
        }
    }

    private fun sendBytes(output: OutputStream, contentType: String, body: ByteArray, offset: Int = 0) {
        val meta = reply.get()
        meta.status = 200
        meta.contentType = contentType
        meta.file = null
        try {
            output.write(body, offset, body.size - offset)
            output.flush()
        } catch (e: Exception) {
            logw("sendBytes failed: ${e.message}")
        }
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val meta = reply.get()
        meta.status = code
        meta.contentType = "text/plain"
        meta.file = null
        try {
            output.write("$code: $message\n".toByteArray(Charsets.UTF_8))
            output.flush()
        } catch (e: Exception) {
            // ignore
        }
    }
}

package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Track
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class LocalProxyServer(
    client: OkHttpClient,
    private val segmentHeaders: Headers,
) {
    companion object {
        private const val IDLE_TIMEOUT_MS = 600000L
        private const val MAX_CACHE_ENTRIES = 50
        private const val SOCKET_READ_TIMEOUT_MS = 120000
        private const val TAG = "AnikotoProxy"
    }

    private val fetchClient: OkHttpClient = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    var prefetchCount: Int = 10
    private val running = AtomicBoolean(false)
    private val lastActivityMs = AtomicLong(System.currentTimeMillis())
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AnikotoProxy-Worker").apply { isDaemon = true }
    }

    private val idleMonitorThread = Thread({ idleMonitor() }, "AnikotoProxy-IdleMonitor").apply {
        isDaemon = true
    }

    private val segmentCache = ConcurrentHashMap<String, ByteArray>()
    private val cacheOrder = Collections.synchronizedList(mutableListOf<String>())
    private val fetching = ConcurrentHashMap<String, Boolean>()
    private val prefetchGeneration = AtomicLong(0L)

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    var playlist: Playlist? = null

    val port: Int
        get() = serverSocket?.localPort ?: -1

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun logw(msg: String) = Log.w(TAG, msg)
    private fun loge(msg: String) = Log.e(TAG, msg)

    data class VariantData(
        val quality: String,
        val bandwidth: Int,
        val resolution: Int,
        val segments: List<SegmentInfo>,
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
    )

    data class Playlist(
        val streams: List<AudioStream>,
    )

    fun start() {
        if (running.get()) return
        val ss = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        ss.soTimeout = 0
        serverSocket = ss
        running.set(true)
        lastActivityMs.set(System.currentTimeMillis())

        acceptThread = Thread({ acceptLoop() }, "AnikotoProxy-Accept").apply {
            isDaemon = true
            start()
        }
        idleMonitorThread.start()
        logi("Proxy server started on $baseUrl (prefetch=$prefetchCount% of total segments)")
    }

    fun stop() {
        if (running.getAndSet(false)) {
            logi("Stopping proxy server")
            runCatching { serverSocket?.close() }
            runCatching { acceptThread?.interrupt() }
            runCatching { executor.shutdownNow() }
            segmentCache.clear()
            cacheOrder.clear()
            fetching.clear()
            prefetchGeneration.incrementAndGet()
        }
    }

    fun onQualitySwitch() {
        logi("Quality switch — canceling prefetches, bumping generation")
        prefetchGeneration.incrementAndGet()
        fetching.clear()
    }

    private fun touchActivity() {
        lastActivityMs.set(System.currentTimeMillis())
    }

    private fun idleMonitor() {
        while (running.get()) {
            try {
                Thread.sleep(5000L)
                val idleMs = System.currentTimeMillis() - lastActivityMs.get()
                if (idleMs > IDLE_TIMEOUT_MS) {
                    logi("Idle ${idleMs / 1000}s — auto-shutting down")
                    stop()
                    return
                }
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running.get()) {
            try {
                val socket = ss.accept()
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                executor.execute { handleClient(socket) }
            } catch (e: Exception) {
                if (running.get()) {
                    loge("accept() failed: ${e.message}")
                }
                break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val line = readLine(input) ?: return
            touchActivity()
            val parts = line.split(" ")
            if (parts.size >= 3 && parts[0] == "GET") {
                val path = parts[1]
                var nextLine: String?
                while (true) {
                    nextLine = readLine(input)
                    if (nextLine.isNullOrEmpty()) break
                }
                runCatching {
                    routeRequest(path, output)
                }.onFailure { e ->
                    loge("Route error for $path: ${e.message}")
                    runCatching { sendError(output, 500, "Internal Server Error: ${e.message}") }
                }
            } else {
                sendError(output, 405, "Method Not Allowed")
            }
        }
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

        val maxDuration = variant.segments.maxOfOrNull { it.duration } ?: 0.0
        val targetDuration = (maxDuration.toInt() + 1)

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:$targetDuration\n")
        sb.append("#EXT-X-MEDIA-SEQUENCE:0\n")
        variant.segments.forEachIndexed { i, seg ->
            sb.append("#EXTINF:${seg.duration},\n")
            sb.append("$baseUrl/seg/$audioType/$quality/$i\n")
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
        val seg = variant.segments.getOrNull(index)
            ?: return sendError(output, 404, "Segment $index not found")

        val cacheKey = "$audioType/$quality/$index"
        touchActivity()
        val cached = segmentCache[cacheKey]
        if (cached != null) {
            logi("CACHE HIT: $cacheKey (${cached.size} bytes)")
            sendBytes(output, "video/MP2T", cached)
            triggerPrefetch(stream, variant, audioType, quality, index)
            return
        }

        if (fetching[cacheKey] == true) {
            logi("WAIT FOR FETCH: $cacheKey")
            var waited = 0
            while (fetching[cacheKey] == true && waited < 15000) {
                Thread.sleep(50L)
                waited += 50
            }
            val waitedBytes = segmentCache[cacheKey]
            if (waitedBytes != null) {
                logi("FETCH WAIT SUCCEEDED: $cacheKey (${waitedBytes.size} bytes)")
                sendBytes(output, "video/MP2T", waitedBytes)
                triggerPrefetch(stream, variant, audioType, quality, index)
                return
            }
            logw("FETCH WAIT FAILED, fetching synchronously: $cacheKey")
        }

        logi("FETCH: $cacheKey → ${seg.url.take(80)}...")
        fetching[cacheKey] = true
        try {
            val segBytes = fetchSegmentWithRetry(seg.url)
            val stripped = stripPngHeader(segBytes)
            val firstByte = if (stripped.isNotEmpty()) stripped[0] else 0.toByte()
            val isTsSync = firstByte == 0x47.toByte()
            logi("STRIPPED: $cacheKey ${segBytes.size}→${stripped.size} bytes, first=0x${String.format("%02x", firstByte)}, tsSync=$isTsSync")
            if (!isTsSync && stripped.isNotEmpty()) {
                val hexStr = stripped.take(8).joinToString("") { String.format("%02x", it) }
                logw("WARNING: segment $cacheKey doesn't start with 0x47! First 8 bytes: $hexStr")
            }
            cacheSegment(cacheKey, stripped)
            sendBytes(output, "video/MP2T", stripped)
            triggerPrefetch(stream, variant, audioType, quality, index)
        } catch (e: Exception) {
            loge("Segment fetch failed ($cacheKey): ${e.message}")
            sendError(output, 502, "Fetch error: ${e.message}")
        } finally {
            fetching.remove(cacheKey)
        }
    }

    private fun fetchSegmentWithRetry(url: String): ByteArray {
        var lastError: Exception? = null
        for (i in 0 until 2) {
            try {
                val request = Request.Builder().url(url).headers(segmentHeaders).build()
                fetchClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw RuntimeException("Upstream ${response.code}")
                    }
                    val body = response.body ?: throw RuntimeException("Empty body")
                    return body.bytes()
                }
            } catch (e: Exception) {
                lastError = e
                logw("Fetch attempt ${i + 1} failed: ${e.message}")
                if (i < 1) {
                    Thread.sleep(500L)
                }
            }
        }
        throw lastError ?: RuntimeException("Unknown fetch error")
    }

    private fun triggerPrefetch(
        stream: AudioStream,
        variant: VariantData,
        audioType: String,
        quality: String,
        currentIndex: Int,
    ) {
        if (prefetchCount <= 0) return
        val gen = prefetchGeneration.get()
        val totalSegs = variant.segments.size
        val prefetchAhead = max((prefetchCount * totalSegs) / 100, 1)
        val maxIndex = min(currentIndex + prefetchAhead, totalSegs - 1)
        var submitted = 0

        for (i in (currentIndex + 1)..maxIndex) {
            val key = "$audioType/$quality/$i"
            if (!segmentCache.containsKey(key) && fetching[key] != true) {
                submitted++
                if (submitted <= 5) {
                    executor.execute {
                        triggerPrefetchTask(gen, key, variant, i)
                    }
                } else {
                    break
                }
            }
        }
    }

    private fun triggerPrefetchTask(gen: Long, key: String, variant: VariantData, index: Int) {
        if (prefetchGeneration.get() != gen) {
            logi("PREFETCH CANCELED (gen changed): $key")
            return
        }
        if (segmentCache.containsKey(key) || fetching[key] == true) return
        fetching[key] = true
        try {
            if (prefetchGeneration.get() != gen) return
            val seg = variant.segments[index]
            logi("PREFETCH: $key → ${seg.url.take(60)}...")
            val bytes = fetchSegmentWithRetry(seg.url)
            val stripped = stripPngHeader(bytes)
            cacheSegment(key, stripped)
            logi("PREFETCH DONE: $key (${stripped.size} bytes)")
        } catch (e: Exception) {
            logw("PREFETCH FAILED: $key — ${e.message}")
        } finally {
            fetching.remove(key)
        }
    }

    private fun serveSubtitle(audioType: String, subIndex: Int, output: OutputStream) {
        val pl = playlist ?: return sendError(output, 500, "No playlist")
        val stream = pl.streams.firstOrNull { it.audioType == audioType }
            ?: return sendError(output, 404, "Audio type not found: $audioType")
        val sub = stream.subtitles.getOrNull(subIndex)
            ?: return sendError(output, 404, "Subtitle $subIndex not found")

        try {
            val request = Request.Builder().url(sub.url).headers(segmentHeaders).build()
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

    fun getSubtitleTracks(audioType: String): List<Track> {
        val pl = playlist ?: return emptyList()
        val stream = pl.streams.firstOrNull { it.audioType == audioType } ?: return emptyList()
        val tracks = stream.subtitles.mapIndexed { i, sub ->
            Track("$baseUrl/sub/$audioType/$i", sub.label)
        }
        logi("getSubtitleTracks($audioType): ${tracks.size} tracks")
        return tracks
    }

    private fun cacheSegment(key: String, data: ByteArray) {
        if (segmentCache.size >= MAX_CACHE_ENTRIES) {
            synchronized(cacheOrder) {
                if (cacheOrder.isNotEmpty()) {
                    segmentCache.remove(cacheOrder.removeAt(0))
                }
            }
        }
        segmentCache[key] = data
        synchronized(cacheOrder) {
            cacheOrder.remove(key)
            cacheOrder.add(key)
        }
    }

    private fun stripPngHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return data
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return data
        val tsData = data.copyOfRange(videoStart, data.size)
        val iMin = min(tsData.size - 188, 400)
        for (offset in 0 until iMin) {
            if (tsData[offset] == 0x47.toByte() && tsData[offset + 188] == 0x47.toByte()) {
                return tsData.copyOfRange(offset, tsData.size)
            }
        }
        return tsData
    }

    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (baos.size() == 0) return null
                return baos.toString("UTF-8")
            }
            if (b != 10) {
                if (b != 13) {
                    baos.write(b)
                }
            } else {
                return baos.toString("UTF-8")
            }
        }
    }

    private fun sendText(output: OutputStream, contentType: String, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        try {
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
        } catch (e: Exception) {
            logw("sendText failed: ${e.message}")
        }
    }

    private fun sendBytes(output: OutputStream, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\nAccept-Ranges: bytes\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\n\r\n"
        try {
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
        } catch (e: Exception) {
            logw("sendBytes failed: ${e.message}")
        }
    }

    private fun sendError(output: OutputStream, code: Int, message: String) {
        val body = "$code: $message\n".toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $message\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        try {
            output.write(header.toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
        } catch (e: Exception) {
            // ignore
        }
    }
}

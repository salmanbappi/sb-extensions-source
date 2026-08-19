package eu.kanade.tachiyomi.animeextension.all.lunar

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class FlixProxyServer(
    private val headers: Headers,
    private var segmentMask: ByteArray,
) : NanoHTTPD(0) {

    fun updateSegmentMask(newMask: ByteArray) {
        if (!newMask.contentEquals(segmentMask)) {
            segmentMask = newMask
        }
    }

    private val proxyClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(30, 2, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun createProxyUrl(originalUrl: String, wPayload: String): String {
        val params = "url=${URLEncoder.encode(originalUrl, "UTF-8")}&w_payload=${URLEncoder.encode(wPayload, "UTF-8")}"
        return "http://127.0.0.1:$listeningPort/proxy?$params"
    }

    fun wrapInDecApi(originalUrl: String, wPayload: String): String {
        if (originalUrl.contains(encDecUrl)) return originalUrl
        val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8").replace("+", "%20")
        val encodedWPayload = URLEncoder.encode(wPayload, "UTF-8").replace("+", "%20")
        return "$decApi/parse-flixcloud?url=$encodedUrl&w_payload=$encodedWPayload"
    }

    fun ensureToken(segmentUrl: String, parentUrl: String): String = try {
        val segHttpUrl = segmentUrl.toHttpUrl()
        var token: String? = segHttpUrl.queryParameter("token")
        if (token == null) {
            var currentUrl = parentUrl
            repeat(3) {
                val httpUrl = currentUrl.toHttpUrl()
                if (token == null) token = httpUrl.queryParameter("token")
                if (token == null) {
                    val nestedUrl = httpUrl.queryParameter("url")
                    if (nestedUrl != null) currentUrl = nestedUrl
                }
            }
        }

        segHttpUrl.newBuilder().apply {
            if (token != null && segHttpUrl.queryParameter("token") == null) {
                addQueryParameter("token", token)
            }
        }.build().toString()
    } catch (_: Exception) {
        segmentUrl
    }

    override fun serve(session: IHTTPSession): Response {
        val params = session.parameters
        val url = params["url"]?.firstOrNull() ?: return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing url")
        val wPayload = params["w_payload"]?.firstOrNull() ?: ""

        return try {
            val isManifest = url.contains(".m3u8")
            val finalUrl = if (isManifest) wrapInDecApi(url, wPayload) else url

            val proxyHeaders = headers.newBuilder()
                .set("Accept", "*/*")
                .removeAll("Origin").removeAll("Referer")
                .removeAll("Sec-Fetch-Dest").removeAll("Sec-Fetch-Mode")
                .removeAll("Sec-Fetch-Site").removeAll("Accept-Encoding")
                .apply {
                    if (url.contains(encDecUrl)) {
                        add("Origin", encDecUrl)
                        add("Referer", "$encDecUrl/")
                    } else {
                        add("Origin", flixCloudUrl)
                        add("Referer", "$flixCloudUrl/")
                        add("Sec-Fetch-Dest", "empty")
                        add("Sec-Fetch-Mode", "cors")
                        add("Sec-Fetch-Site", "same-site")
                    }
                }.build()

            if (!isManifest) {
                serveSegment(finalUrl, proxyHeaders)
            } else {
                serveManifest(url, finalUrl, wPayload, proxyHeaders)
            }
        } catch (e: Exception) {
            val status = if (e is java.net.SocketTimeoutException) {
                Status.SERVICE_UNAVAILABLE
            } else {
                Status.INTERNAL_ERROR
            }
            newFixedLengthResponse(status, "text/plain", e.toString())
        }
    }

    private fun serveSegment(
        finalUrl: String,
        proxyHeaders: Headers,
    ): Response {
        val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()
        val response = proxyClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return newFixedLengthResponse(
                Status.lookup(code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "CDN Error: $code",
            )
        }

        val body = response.body
        val source = body.source()

        val headerBytes = try {
            source.peek().readByteArray(13)
        } catch (_: java.io.EOFException) {
            ByteArray(0)
        }

        val headerSize = detectHeader(headerBytes)
        val shouldXor = headerSize > 0

        val originalLength = body.contentLength()
        val outputLength = if (originalLength > 0 && headerSize > 0) {
            originalLength - headerSize
        } else {
            originalLength
        }

        val xorSource = FlixcloudSegmentSource(source, segmentMask, headerSize, shouldXor)
        val inputStream = xorSource.buffer().inputStream()

        return if (outputLength > 0) {
            newFixedLengthResponse(Status.OK, "video/mp2t", inputStream, outputLength)
        } else {
            newChunkedResponse(Status.OK, "video/mp2t", inputStream)
        }
    }

    private fun serveManifest(
        url: String,
        finalUrl: String,
        wPayload: String,
        proxyHeaders: Headers,
    ): Response {
        val request = Request.Builder().url(finalUrl).headers(proxyHeaders).build()

        var response: okhttp3.Response? = null
        var attempt = 0
        while (response == null) {
            try {
                response = proxyClient.newCall(request).execute()
            } catch (e: java.net.SocketTimeoutException) {
                attempt++
                if (attempt >= 3) throw e
                Log.w("Lunar", "Manifest timeout, retrying... (Attempt $attempt/3)")
            }
        }

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            response.close()
            return newFixedLengthResponse(
                Status.lookup(response.code) ?: Status.INTERNAL_ERROR,
                "text/plain",
                "Manifest Error: $errorBody",
            )
        }

        val bodyText = response.body.string()
        response.close()

        val parentHttpUrl = if (url.contains(encDecUrl)) {
            url.toHttpUrl().queryParameter("url")?.toHttpUrl() ?: url.toHttpUrl()
        } else {
            url.toHttpUrl()
        }

        val modifiedText = bodyText.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@joinToString ""

            if (trimmed.startsWith("#")) {
                val cleanedLine = if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    val peakBw = BANDWIDTH_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
                    val avgBw = AVERAGE_BANDWIDTH_REGEX.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()

                    if (peakBw != null && peakBw < 100_000L) {
                        val finalBw = if (avgBw != null && avgBw > 100_000L) {
                            avgBw
                        } else {
                            peakBw * 1000L
                        }
                        trimmed.replace(BANDWIDTH_REGEX, "BANDWIDTH=$finalBw")
                    } else {
                        trimmed
                    }
                } else {
                    trimmed
                }

                if (cleanedLine.contains("URI=\"")) {
                    val uri = URI_REGEX.find(cleanedLine)?.groupValues?.get(1) ?: ""
                    if (uri.isNotEmpty()) {
                        var resolvedUri = parentHttpUrl.resolve(uri).toString()
                        resolvedUri = ensureToken(resolvedUri, url)
                        val newUri = createProxyUrl(resolvedUri, wPayload)
                        cleanedLine.replace(URI_REGEX, "URI=\"$newUri\"")
                    } else {
                        cleanedLine
                    }
                } else {
                    cleanedLine
                }
            } else {
                var resolvedUrl = parentHttpUrl.resolve(trimmed).toString()
                resolvedUrl = ensureToken(resolvedUrl, url)
                createProxyUrl(resolvedUrl, wPayload)
            }
        }

        return newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", modifiedText)
    }

    companion object {
        const val flixCloudUrl = "https://flixcloud.cc"
        const val encDecUrl = "https://enc-dec.app"
        const val decApi = "$encDecUrl/api"
        private val URI_REGEX = Regex("URI=\"(.*?)\"")
        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private val AVERAGE_BANDWIDTH_REGEX = Regex("""AVERAGE-BANDWIDTH=(\d+)""")

        private fun detectHeader(data: ByteArray): Int = when {
            data.size >= 12 &&
                data[0] == 0x52.toByte() && data[1] == 0x49.toByte() &&
                data[2] == 0x46.toByte() && data[3] == 0x46.toByte() &&
                data[8] == 0x57.toByte() && data[9] == 0x45.toByte() &&
                data[10] == 0x42.toByte() && data[11] == 0x50.toByte() -> 12

            data.size >= 4 &&
                data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
                data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> 8

            else -> 0
        }
    }
}

private class FlixcloudSegmentSource(
    upstream: Source,
    private val mask: ByteArray,
    private val skipBytes: Int,
    private val shouldXor: Boolean,
) : ForwardingSource(upstream) {

    private var bytesSkipped = 0
    private var xorIndex = 0

    override fun read(sink: Buffer, byteCount: Long): Long {
        while (bytesSkipped < skipBytes) {
            val toSkip = (skipBytes - bytesSkipped).toLong()
            val temp = Buffer()
            val skipped = super.read(temp, toSkip)
            if (skipped == -1L) return -1L
            bytesSkipped += skipped.toInt()
        }

        val temp = Buffer()
        val n = super.read(temp, byteCount)
        if (n == -1L) return -1L

        if (shouldXor) {
            val bytes = temp.readByteArray()
            for (i in bytes.indices) {
                bytes[i] = (bytes[i].toInt() xor mask[xorIndex and 15].toInt()).toByte()
                xorIndex++
            }
            sink.write(bytes)
        } else {
            sink.write(temp, n)
        }

        return n
    }
}

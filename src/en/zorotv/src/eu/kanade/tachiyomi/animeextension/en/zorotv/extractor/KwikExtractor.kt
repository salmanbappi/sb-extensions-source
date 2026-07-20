package eu.kanade.tachiyomi.animeextension.en.zorotv.extractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

data class KwikContent(val cookies: String, val html: String, val finalUrl: String)
private data class HlsStream(val url: String, val referer: String)

class KwikExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val cookieFreeClient by lazy {
        client.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .build()
    }

    private val kwikHeaders by lazy {
        headers.newBuilder()
            .set("Origin", "https://kwik.cx")
            .set("Referer", "https://kwik.cx/")
            .build()
    }

    suspend fun getHlsVideo(kwikUrl: String, referer: String, quality: String = ""): Video {
        val hlsStream = getHlsStream(kwikUrl, referer)

        return Video(
            videoUrl = hlsStream.url,
            videoTitle = quality,
            headers = kwikHeaders.newBuilder()
                .set("Referer", hlsStream.referer)
                .build(),
        )
    }

    private suspend fun getHlsStream(kwikUrl: String, referer: String): HlsStream {
        val content = fetchKwikHtml(kwikUrl, referer)
        val eContent = Jsoup.parse(content.html, content.finalUrl)
        val script = eContent.selectFirst("script:containsData(eval\\(function)")?.data()
            ?.substringAfterLast("eval(function(")
            ?: throw Exception("JsUnpacker not found.")
        val unpacked = JsUnpacker.unpackAndCombine("eval(function(\$script")
            ?: throw Exception("JsUnpacker failed to unpack Kwik script.")

        return HlsStream(
            url = unpacked.substringAfter("const source=\\'").substringBefore("\\';"),
            referer = content.finalUrl,
        )
    }

    private suspend fun fetchKwikHtml(kwikUrl: String, referer: String): KwikContent {
        suspend fun attemptKwikFetch(cfResult: CloudFlareBypassResult?): KwikContent? {
            val reqHeaders = Headers.Builder()
                .set("Origin", "https://kwik.cx")
                .set("Referer", referer)
                .apply {
                    if (cfResult != null) {
                        set("Cookie", cfResult.cookies)
                        set("User-Agent", cfResult.userAgent)
                    } else {
                        set("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                    }
                }
                .build()

            return try {
                cookieFreeClient.newCall(GET(kwikUrl, reqHeaders)).awaitSuccess().use { resp ->
                    val html = resp.body.string()
                    if (html.contains("eval(function(")) {
                        val respCookies = resp.headers("set-cookie").joinToString("; ") { it.substringBefore(";") }
                        val finalCookies = listOfNotNull(respCookies.ifBlank { null }, cfResult?.cookies?.ifBlank { null }).joinToString("; ")
                        KwikContent(finalCookies, html, resp.request.url.toString())
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        attemptKwikFetch(null)?.let { return it }

        val cfResult = CloudflareBypass().getCookies(kwikUrl)
            ?: throw Exception("Failed to bypass Kwik Cloudflare.")

        attemptKwikFetch(cfResult)?.let { return it }

        throw Exception("Failed to bypass Kwik Cloudflare after bypass.")
    }
}

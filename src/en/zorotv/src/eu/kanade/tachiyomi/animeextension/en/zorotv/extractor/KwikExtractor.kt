package eu.kanade.tachiyomi.animeextension.en.zorotv.extractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class KwikExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    suspend fun getHlsVideo(kwikUrl: String, referer: String, quality: String = ""): Video {
        val reqHeaders = headers.newBuilder()
            .set("Origin", "https://kwik.cx")
            .set("Referer", referer)
            .build()

        val html = client.newCall(GET(kwikUrl, reqHeaders)).awaitSuccess().use { resp ->
            resp.body.string()
        }

        val eContent = Jsoup.parse(html, kwikUrl)
        val script = eContent.selectFirst("script:containsData(eval\\(function)")?.data()
            ?.substringAfterLast("eval(function(")
            ?: throw Exception("JsUnpacker not found.")

        val unpacked = JsUnpacker.unpackAndCombine("eval(function($script")
            ?: throw Exception("JsUnpacker failed to unpack Kwik script.")

        val hlsUrl = unpacked.substringAfter("const source=\\'").substringBefore("\\';")
            .takeIf { it.startsWith("http", ignoreCase = true) }
            ?: throw Exception("Kwik HLS source URL not found.")

        return Video(
            videoUrl = hlsUrl,
            videoTitle = quality,
            headers = headers.newBuilder()
                .set("Origin", "https://kwik.cx")
                .set("Referer", kwikUrl)
                .build(),
        )
    }
}

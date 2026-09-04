// ============================================================================
// Vendored from lib/streamtape-extractor/StreamTapeExtractor.kt via `cli.py vendor`
// Original Package: eu.kanade.tachiyomi.lib.streamtapeextractor
// Managed Vendor Copy — Safe to customize locally without breaking other sources
// ============================================================================

package eu.kanade.tachiyomi.animeextension.en.chikianimation.extractors

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class StreamTapeExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<Track> = emptyList()): Video? {
        val baseUrl = "https://streamtape.com/e/"
        val newUrl = if (!url.startsWith(baseUrl)) {
            // ["https", "", "<domain>", "<???>", "<id>", ...]
            val id = url.split("/").getOrNull(4) ?: return null
            baseUrl + id
        } else {
            url
        }

        val document = client.newCall(GET(newUrl)).execute().asJsoup()
        val targetLine = "document.getElementById('robotlink')"
        val script = document.selectFirst("script:containsData($targetLine)")
            ?.data()
            ?.substringAfter("$targetLine.innerHTML = '")
            ?: return null
        val firstPart = script.substringBefore("'").trim()
        val secondPart = script.substringAfter("+ ('xcd", "").substringBefore("'").trim()
        if (firstPart.isBlank() || secondPart.isBlank()) return null
        val hostPath = (firstPart + secondPart).removePrefix("/")
        val videoUrl = "https://$hostPath&stream=1"
        if (!videoUrl.contains("/get_video?id=")) return null

        return Video(
            videoUrl = videoUrl,
            videoTitle = quality,
            headers = okhttp3.Headers.Builder()
                .add("Referer", newUrl)
                .add("User-Agent", "Mozilla/5.0")
                .build(),
            subtitleTracks = subtitleList,
        )
    }

    fun videosFromUrl(url: String, quality: String = "Streamtape", subtitleList: List<Track> = emptyList()): List<Video> = videoFromUrl(url, quality, subtitleList)?.let(::listOf).orEmpty()
}

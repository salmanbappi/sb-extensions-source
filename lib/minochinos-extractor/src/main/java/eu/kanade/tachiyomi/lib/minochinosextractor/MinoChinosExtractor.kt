package eu.kanade.tachiyomi.lib.minochinosextractor

import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.unpacker.autoUnpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class MinoChinosExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    suspend fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val response = client.newCall(GET(url)).awaitSuccess()
        val document = response.useAsJsoup()

        val script = document.select("script").find {
            it.html().contains("eval(function(p,a,c,k,e,d)")
        }?.html() ?: return emptyList()

        val unpacked = autoUnpacker(script) ?: return emptyList()
        Log.d("MinoChinosExtractor", "Unpacked script: $unpacked")

        // Extract links from the unpacked script
        // var links={"hls3":"...","hls4":"...","hls2":"..."};
        val linksJson = unpacked.substringAfter("var links=", "").substringBefore(";", "")
        Log.d("MinoChinosExtractor", "Links JSON: $linksJson")
        if (linksJson.isEmpty()) return emptyList()

        val videoEntries = linkRegex.findAll(linksJson).map {
            it.groupValues[1] to it.groupValues[2]
        }.filter { (key, _) -> key == "hls3" }.toList()

        return videoEntries.parallelCatchingFlatMap { (key, videoUrl) ->
            val fixedUrl = if (videoUrl.startsWith("/")) {
                val urlObj = url.toHttpUrl()
                "${urlObj.scheme}://${urlObj.host}$videoUrl"
            } else {
                videoUrl
            }

            Log.d("MinoChinosExtractor", "Processing $key: $fixedUrl")

            playlistUtils.extractFromHls(
                fixedUrl,
                referer = url,
                videoNameGen = { quality ->
                    if (prefix.isNotBlank()) {
                        "$prefix$quality"
                    } else {
                        "MinoChinos - $quality"
                    }
                },
            )
        }
    }

    companion object {
        private val linkRegex = Regex(""""(hls\d?)"\s*:\s*"([^"]+)"""")
    }
}

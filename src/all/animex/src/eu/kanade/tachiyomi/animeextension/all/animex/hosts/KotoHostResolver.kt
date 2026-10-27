package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import eu.kanade.tachiyomi.animeextension.all.animex.AnimexProxy
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

object KotoHostResolver : HostResolver {
    override val id = "koto"
    override val displayName = "Koto"

    override suspend fun getVideos(
        slug: String,
        epNum: String,
        type: String,
        subStyle: String,
        client: OkHttpClient,
        baseHeaders: Headers,
        proxy: AnimexProxy,
        json: Json,
    ): List<Video> {
        val lang = if (type == "dub") "dub" else "sub"
        val embedUrl = "https://megaplay.buzz/stream/ani/$slug/$epNum/$lang"
        val kotoHeaders = baseHeaders.newBuilder()
            .set("Referer", "https://animex.one/")
            .build()

        return try {
            val response = client.newCall(GET(embedUrl, kotoHeaders)).execute()
            if (!response.isSuccessful) {
                response.close()
                return emptyList()
            }
            val doc = response.asJsoup()
            val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")
                ?: doc.selectFirst("source[src]")?.attr("src")
                ?: return emptyList()

            if (iframeSrc.contains(".m3u8")) {
                val streamHeaders = baseHeaders.newBuilder()
                    .set("Referer", "https://megaplay.buzz/")
                    .set("Origin", "https://megaplay.buzz")
                    .build()
                val playlistUtils = PlaylistUtils(client, baseHeaders)
                playlistUtils.extractFromHls(
                    playlistUrl = iframeSrc,
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                    videoNameGen = { q -> "Koto: $q (${type.uppercase()})" },
                ).map { video ->
                    Video(
                        videoUrl = proxy.getProxyUrl(video.videoUrl, streamHeaders),
                        videoTitle = video.videoTitle,
                        subtitleTracks = video.subtitleTracks,
                        audioTracks = video.audioTracks,
                        headers = streamHeaders,
                    )
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

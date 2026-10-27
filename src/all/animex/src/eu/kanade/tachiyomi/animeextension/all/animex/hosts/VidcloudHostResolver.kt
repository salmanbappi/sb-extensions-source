package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import eu.kanade.tachiyomi.animeextension.all.animex.AnimexProxy
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.megacloudextractor.MegaCloudExtractor
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient

object VidcloudHostResolver : HostResolver {
    override val id = "vidcloud"
    override val displayName = "Zoro (Vidcloud)"

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
        val anilistId = slug.substringAfterLast("-").toIntOrNull() ?: return emptyList()
        val lang = if (type == "dub") "dub" else "sub"
        val embedUrl = "https://vidcloud.se/s-1/$anilistId/$epNum/$lang"

        val vidcloudHeaders = baseHeaders.newBuilder()
            .set("Referer", "https://animex.one/")
            .build()

        return try {
            val extractor = MegaCloudExtractor(
                client = client,
                headers = vidcloudHeaders,
                megaCloudAPI = "https://megacloud-decrypter.vercel.app/api/dec",
            )
            extractor.getVideosFromUrl(
                url = embedUrl,
                type = lang,
                name = "Zoro ($lang)",
            )
        } catch (_: Exception) {
            emptyList()
        }
    }
}

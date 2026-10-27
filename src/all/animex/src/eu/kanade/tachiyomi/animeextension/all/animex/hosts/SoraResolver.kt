package eu.kanade.tachiyomi.animeextension.all.animex.hosts

import eu.kanade.tachiyomi.animeextension.all.animex.AnimexProxy
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object SoraResolver : BaseAnmxResolver() {
    override val id = "sora"
    override val displayName = "Sora"

    override fun modifyVideoHeaders(
        headersBuilder: Headers.Builder,
        providerId: String,
        sourcesHeaders: Map<String, String>?,
    ) {
        super.modifyVideoHeaders(headersBuilder, providerId, sourcesHeaders)
        headersBuilder.set("Origin", "https://krussdomi.com")
        if (headersBuilder.get("Referer") == null) {
            headersBuilder.set("Referer", "https://kaa.lt/")
        }
    }

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
        // Enforce a strict short timeout for Sora since its CDN host frequently hangs
        val soraClient = client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()

        return try {
            super.getVideos(slug, epNum, type, subStyle, soraClient, baseHeaders, proxy, json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

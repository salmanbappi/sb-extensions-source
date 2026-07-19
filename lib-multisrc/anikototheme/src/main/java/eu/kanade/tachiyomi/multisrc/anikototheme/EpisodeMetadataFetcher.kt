package eu.kanade.tachiyomi.multisrc.anikototheme

import extensions.utils.EpisodeMetadataFetcher as CommonFetcher
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class EpisodeMetadataFetcher(
    client: OkHttpClient,
    json: Json,
    webViewFetcher: WebViewFetcher? = null,
    tmdbApiKey: String = "",
) {
    private val delegate = CommonFetcher(
        client = client,
        json = json,
        webViewFetchText = webViewFetcher?.let { fetcher -> { url -> fetcher.fetchText(url) } },
        webViewPostJson = webViewFetcher?.let { fetcher -> { url, body -> fetcher.postJson(url, body) } },
        tmdbApiKey = tmdbApiKey
    )

    data class EpisodeMetadata(
        val title: String?,
        val description: String?,
        val thumbnailUrl: String?,
        val airdate: String?,
    )

    suspend fun fetch(malId: String, fallbackThumbnailUrl: String?): Map<Int, EpisodeMetadata> {
        return fetch(malId, null, fallbackThumbnailUrl)
    }

    suspend fun fetch(
        malId: String,
        animeTitle: String?,
        fallbackThumbnailUrl: String?,
    ): Map<Int, EpisodeMetadata> {
        return delegate.fetch(malId, animeTitle, fallbackThumbnailUrl).mapValues { (_, meta) ->
            EpisodeMetadata(
                title = meta.title,
                description = meta.description,
                thumbnailUrl = meta.thumbnailUrl,
                airdate = meta.airdate
            )
        }
    }
}

package eu.kanade.tachiyomi.multisrc.anikototheme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import extensions.utils.EpisodeMetadataFetcher as CommonFetcher

class EpisodeMetadataFetcher(
    private val client: OkHttpClient,
    private val json: Json,
    webViewFetcher: WebViewFetcher? = null,
    tmdbApiKey: String = "",
) {
    private val delegate = CommonFetcher(
        client = client,
        json = json,
        webViewFetchText = webViewFetcher?.let { fetcher -> { url -> fetcher.fetchText(url) } },
        webViewPostJson = webViewFetcher?.let { fetcher -> { url, body -> fetcher.postJson(url, body) } },
        tmdbApiKey = tmdbApiKey,
    )

    data class EpisodeMetadata(
        val title: String?,
        val description: String?,
        val thumbnailUrl: String?,
        val airdate: String?,
    )

    /** One entry of a MAL relation group (a sequel/prequel/side story, ...). */
    data class RelatedEntry(
        val malId: Int,
        val title: String,
    )

    suspend fun fetch(malId: String, fallbackThumbnailUrl: String?): Map<Int, EpisodeMetadata> = fetch(malId, null, fallbackThumbnailUrl)

    suspend fun fetch(
        malId: String,
        animeTitle: String?,
        fallbackThumbnailUrl: String?,
    ): Map<Int, EpisodeMetadata> = delegate.fetch(malId, animeTitle, fallbackThumbnailUrl).mapValues { (_, meta) ->
        EpisodeMetadata(
            title = meta.title,
            description = meta.description,
            thumbnailUrl = meta.thumbnailUrl,
            airdate = meta.airdate,
        )
    }

    /**
     * Related anime for [malId], grouped by MAL relation name ("Sequel", "Prequel", ...).
     *
     * Uses Jikan's `/anime/{id}/relations`, the same API the episode enrichment already relies on.
     * Entries carry only a MAL id and title; the caller resolves each to a source slug.
     *
     * @since extensions-lib 17 (feeds `AnimeSource.getRelatedAnimeList`)
     */
    suspend fun fetchRelations(malId: String): Map<String, List<RelatedEntry>> {
        if (malId.isBlank()) return emptyMap()
        return try {
            val request = Request.Builder()
                .url("https://api.jikan.moe/v4/anime/$malId/relations")
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyMap()
                response.body?.string() ?: return emptyMap()
            }

            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray?.mapNotNull { group ->
                val obj = group.jsonObject
                val relation = obj["relation"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val entries = obj["entry"]?.jsonArray?.mapNotNull { entry ->
                    val e = entry.jsonObject
                    val id = e["mal_id"]?.jsonPrimitive?.intOrNull
                    val name = e["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    if (id == null || name == null) null else RelatedEntry(id, name)
                } ?: emptyList()
                if (entries.isEmpty()) null else relation to entries
            }?.toMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

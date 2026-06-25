package eu.kanade.tachiyomi.animeextension.all.anikoto

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class EpisodeMetadataFetcher(
    private val client: OkHttpClient,
    private val json: Json,
) {
    companion object {
        private const val TAG = "EpisodeMetadataFetcher"
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    data class EpisodeMetadata(
        val title: String? = null,
        val description: String? = null,
        val thumbnailUrl: String? = null,
        val airdate: String? = null,
    )

    // In-memory cache keyed by malId
    private val cache = mutableMapOf<String, Map<Int, EpisodeMetadata>>()

    private val apiHeaders: Headers = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Accept", "application/json")
        .build()

    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun loge(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    private fun fetchString(url: String): String? = try {
        val response = client.newCall(Request.Builder().url(url).headers(apiHeaders).build()).execute()
        if (response.isSuccessful) response.body.string() else null
    } catch (e: Exception) {
        loge("fetchString FAILED: $url", e)
        null
    }

    private fun postJson(url: String, body: String): String? = try {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val response = client.newCall(
            Request.Builder().url(url).post(reqBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", BROWSER_UA)
                .build(),
        ).execute()
        if (response.isSuccessful) response.body.string() else null
    } catch (e: Exception) {
        loge("postJson FAILED: $url", e)
        null
    }

    private fun stripHtml(text: String): String = text.replace(Regex("<[^>]+>"), "").trim()

    /** Fetches metadata for all episodes of an anime identified by MAL ID.
     *  Returns map of episode number → EpisodeMetadata.
     *  Results are cached per malId. */
    suspend fun fetch(malId: String, fallbackThumbnailUrl: String? = null): Map<Int, EpisodeMetadata> {
        cache[malId]?.let { return it }

        logi("fetch: malId=$malId")
        val jikan = fetchJikanEpisodes(malId)
        val anilistId = fetchAniListId(malId)
        val anikage = if (anilistId != null) fetchAnikageEpisodes(anilistId) else emptyMap()
        val kitsu = fetchKitsuEpisodes(malId)
        val bannerUrl = if (anilistId != null) fetchAniListBanner(anilistId) else null

        val merged = mergeEpisodes(jikan, anikage, kitsu, emptyList(), emptyMap())
        val result = applyFallbackThumbnail(merged, bannerUrl, fallbackThumbnailUrl)
        logi("fetch: malId=$malId → ${result.size} episodes")
        cache[malId] = result
        return result
    }

    private fun applyFallbackThumbnail(
        episodes: Map<Int, EpisodeMetadata>,
        bannerUrl: String?,
        animeCoverUrl: String?,
    ): Map<Int, EpisodeMetadata> {
        val fallback = bannerUrl ?: animeCoverUrl ?: return episodes
        return episodes.mapValues { (_, meta) ->
            if (meta.thumbnailUrl.isNullOrEmpty()) meta.copy(thumbnailUrl = fallback) else meta
        }
    }

    private fun mergeEpisodes(
        jikan: Map<Int, JikanEpisode>,
        anikage: Map<Int, EpisodeMetadata>,
        kitsu: Map<Int, EpisodeMetadata>,
        anilistStreaming: List<AniListStreamingEpisode>,
        anilistStreamingByNum: Map<Int, AniListStreamingEpisode>,
    ): Map<Int, EpisodeMetadata> {
        val allNums = (jikan.keys + anikage.keys + kitsu.keys).toSet()
        return allNums.associateWith { num ->
            val j = jikan[num]
            val a = anikage[num]
            val k = kitsu[num]
            val title = a?.title?.takeIf { it.isNotBlank() }
                ?: k?.title?.takeIf { it.isNotBlank() }
                ?: j?.title?.takeIf { it.isNotBlank() }
            val description = a?.description?.takeIf { it.isNotBlank() }
                ?: k?.description?.takeIf { it.isNotBlank() }
                ?: j?.synopsis?.takeIf { it.isNotBlank() }
            val thumbnailUrl = k?.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: a?.thumbnailUrl?.takeIf { it.isNotBlank() }
            val airdate = j?.aired?.takeIf { it.isNotBlank() }
            EpisodeMetadata(title, description, thumbnailUrl, airdate)
        }
    }

    private fun fetchAniListId(malId: String): String? = try {
        val query = """{"query":"query { Media(idMal: $malId, type: ANIME) { id bannerImage } }"}"""
        val body = postJson("https://graphql.anilist.co", query) ?: return null
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("Media")?.jsonObject ?: return null
        data["id"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        loge("fetchAniListId FAILED", e)
        null
    }

    private fun fetchAniListBanner(anilistId: String): String? = try {
        val query = """{"query":"query { Media(id: $anilistId, type: ANIME) { bannerImage } }"}"""
        val body = postJson("https://graphql.anilist.co", query) ?: return null
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?.get("Media")?.jsonObject ?: return null
        data["bannerImage"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    internal fun fetchAnikageEpisodes(anilistId: String): Map<Int, EpisodeMetadata> = try {
        val body = fetchString("https://anikage.cc/api/media/anime/$anilistId/episodes") ?: return emptyMap()
        val episodes = json.decodeFromString<List<AnikageEpisode>>(body)
        episodes.mapNotNull { ep ->
            val num = ep.number ?: return@mapNotNull null
            num to EpisodeMetadata(
                title = ep.title?.takeIf { it.isNotBlank() },
                description = ep.description?.takeIf { it.isNotBlank() }?.let { stripHtml(it) },
                thumbnailUrl = ep.img?.takeIf { it.startsWith("http") },
                airdate = ep.airDate,
            )
        }.toMap()
    } catch (e: Exception) {
        loge("fetchAnikageEpisodes FAILED anilistId=$anilistId", e)
        emptyMap()
    }

    internal fun fetchJikanEpisodes(malId: String): Map<Int, JikanEpisode> = try {
        val body = fetchString("https://api.jikan.moe/v4/anime/$malId/episodes") ?: return emptyMap()
        val resp = json.decodeFromString<JikanEpisodesResponse>(body)
        resp.data.associateBy { it.malId }
    } catch (e: Exception) {
        loge("fetchJikanEpisodes FAILED malId=$malId", e)
        emptyMap()
    }

    private fun fetchKitsuId(malId: String): String? = try {
        val body = fetchString(
            "https://kitsu.app/api/edge/mappings?filter[externalSite]=myanimelist/anime&filter[externalId]=$malId&include=item",
        ) ?: return null
        val resp = json.decodeFromString<KitsuMappingResponse>(body)
        resp.included.firstOrNull()?.id
    } catch (e: Exception) {
        null
    }

    internal fun fetchKitsuEpisodes(malId: String): Map<Int, EpisodeMetadata> = try {
        val kitsuId = fetchKitsuId(malId) ?: return emptyMap()
        val result = mutableMapOf<Int, EpisodeMetadata>()
        var url: String? = "https://kitsu.app/api/edge/anime/$kitsuId/episodes?page[limit]=20&sort=number"
        while (url != null) {
            val body = fetchString(url) ?: break
            val resp = json.decodeFromString<KitsuEpisodesResponse>(body)
            for (ep in resp.data) {
                val num = ep.attributes.number ?: continue
                result[num] = EpisodeMetadata(
                    title = ep.attributes.canonicalTitle?.takeIf { it.isNotBlank() },
                    description = ep.attributes.synopsis?.takeIf { it.isNotBlank() },
                    thumbnailUrl = ep.attributes.thumbnail?.original?.takeIf { it.startsWith("http") },
                )
            }
            url = resp.links.next
        }
        result
    } catch (e: Exception) {
        loge("fetchKitsuEpisodes FAILED malId=$malId", e)
        emptyMap()
    }

    // ---- Data classes ----

    @Serializable
    data class AniListStreamingEpisode(
        val title: String? = null,
        val thumbnail: String? = null,
    )

    @Serializable
    data class AnikageEpisode(
        val number: Int? = null,
        val title: String? = null,
        val description: String? = null,
        val img: String? = null,
        @SerialName("air_date") val airDate: String? = null,
    )

    @Serializable
    data class JikanEpisodesResponse(val data: List<JikanEpisode> = emptyList())

    @Serializable
    data class JikanEpisode(
        @SerialName("mal_id") val malId: Int = 0,
        val title: String? = null,
        val synopsis: String? = null,
        val aired: String? = null,
    )

    @Serializable
    data class KitsuMappingResponse(
        val data: List<JsonObject> = emptyList(),
        val included: List<KitsuAnime> = emptyList(),
    )

    @Serializable
    data class KitsuAnime(val id: String? = null)

    @Serializable
    data class KitsuEpisodesResponse(
        val data: List<KitsuEpisode> = emptyList(),
        val links: KitsuLinks = KitsuLinks(),
    )

    @Serializable
    data class KitsuEpisode(val attributes: KitsuEpisodeAttributes = KitsuEpisodeAttributes())

    @Serializable
    data class KitsuEpisodeAttributes(
        val number: Int? = null,
        val canonicalTitle: String? = null,
        val synopsis: String? = null,
        val thumbnail: KitsuImage? = null,
    )

    @Serializable
    data class KitsuImage(val original: String? = null)

    @Serializable
    data class KitsuLinks(val next: String? = null)
}

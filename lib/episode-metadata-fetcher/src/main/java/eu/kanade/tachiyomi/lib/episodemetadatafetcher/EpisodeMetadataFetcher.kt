package eu.kanade.tachiyomi.lib.episodemetadatafetcher

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class EpisodeMetadataFetcher(
    private val client: OkHttpClient,
    private val json: Json,
    private val webViewFetchText: (suspend (String) -> String?)? = null,
    private val webViewPostJson: (suspend (String, String) -> String?)? = null,
    tmdbApiKey: String = "",
) {
    private val effectiveTmdbKey: String = tmdbApiKey.ifBlank { DEFAULT_TMDB_API_KEY }

    private val metaClient: OkHttpClient = client.newBuilder()
        .connectTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(FAST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(FAST_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    data class EpisodeMetadata(
        val title: String?,
        val description: String?,
        val thumbnailUrl: String?,
        val airdate: String?,
    )

    private data class CachedData(
        val episodes: Map<Int, EpisodeMetadata>,
        val bannerUrl: String?,
    )

    private val localJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val cache = ConcurrentHashMap<String, CachedData>()
    private val anilistStreamingCache = ConcurrentHashMap<String, List<AniListStreamingEpisode>>()
    private val anilistBannerCache = java.util.Collections.synchronizedMap(mutableMapOf<String, String?>())
    private val apiHeaders = Headers.Builder()
        .set("User-Agent", BROWSER_UA)
        .set("Accept", "application/json, application/vnd.api+json, text/html, */*")
        .build()

    private fun logd(msg: String) = Log.d(TAG, msg)
    private fun logi(msg: String) = Log.i(TAG, msg)
    private fun logw(msg: String, e: Throwable? = null) {
        if (e != null) Log.w(TAG, msg, e) else Log.w(TAG, msg)
    }
    private fun loge(msg: String, e: Throwable? = null) {
        if (e != null) Log.e(TAG, msg, e) else Log.e(TAG, msg)
    }

    suspend fun fetch(malId: String, fallbackThumbnailUrl: String?): Map<Int, EpisodeMetadata> {
        return fetch(malId, null, fallbackThumbnailUrl)
    }

    suspend fun fetchAnimeSynopsis(title: String): String? {
        if (title.isBlank()) return null
        return withContext(Dispatchers.IO) {
            try {
                val cleanTitle = sanitizeTitle(title)
                // 1. Try AniList GraphQL (clean text, no HTML, fastest ~300ms)
                val aniListSyn = fetchAniListSynopsis(cleanTitle)
                if (!aniListSyn.isNullOrBlank()) {
                    return@withContext aniListSyn
                }
                // 2. Try Kitsu
                val searchUrl = "https://kitsu.app/api/edge/anime?filter[text]=${URLEncoder.encode(cleanTitle, "UTF-8")}&page[limit]=1"
                val body = fetchString(searchUrl)
                if (body != null) {
                    val response = localJson.decodeFromString(KitsuSearchResponse.serializer(), body)
                    val syn = response.data.firstOrNull()?.attributes?.synopsis
                        ?: response.data.firstOrNull()?.attributes?.description
                    if (!syn.isNullOrBlank()) {
                        return@withContext stripHtml(syn)
                    }
                }
                // 3. Try Jikan
                val jikanUrl = "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(cleanTitle, "UTF-8")}&limit=1"
                val jikanBody = fetchString(jikanUrl)
                if (jikanBody != null) {
                    val obj = JSONObject(jikanBody)
                    val dataArr = obj.optJSONArray("data")
                    val syn = dataArr?.optJSONObject(0)?.optString("synopsis")
                    if (!syn.isNullOrBlank()) {
                        return@withContext stripHtml(syn)
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun fetch(
        malId: String,
        animeTitle: String?,
        fallbackThumbnailUrl: String?,
    ): Map<Int, EpisodeMetadata> {
        if (malId.isBlank() && animeTitle.isNullOrBlank()) return emptyMap()

        val cacheKey = malId.ifBlank { animeTitle ?: "" }

        synchronized(cache) {
            cache[cacheKey]?.let { return applyFallbackThumbnail(it.episodes, it.bannerUrl, fallbackThumbnailUrl) }
        }

        return withContext(Dispatchers.IO) {
            try {
                val startTime = System.currentTimeMillis()
                logd("fetching for malId=$malId title=$animeTitle")

                var resolvedMalId = malId
                var resolvedAniListId: String? = null
                var bannerUrl: String? = null

                // 1. Direct Tier-1 Ani.zip fast-path if malId is provided
                if (resolvedMalId.isNotBlank()) {
                    val anizipData = fetchAniZipData(malId = resolvedMalId)
                    if (anizipData.episodes.isNotEmpty()) {
                        val isComplete = anizipData.episodes.values.all {
                            !it.description.isNullOrBlank() && !it.thumbnailUrl.isNullOrBlank()
                        }
                        if (isComplete) {
                            val cached = CachedData(anizipData.episodes, anizipData.bannerUrl)
                            synchronized(cache) { cache[cacheKey] = cached }
                            val elapsed = System.currentTimeMillis() - startTime
                            logi("Tier-1 Ani.zip fast-path resolved ${anizipData.episodes.size} episodes in ${elapsed}ms")
                            return@withContext applyFallbackThumbnail(anizipData.episodes, anizipData.bannerUrl, fallbackThumbnailUrl)
                        }
                    }
                }

                // 2. If MAL ID was missing or Ani.zip was partial, resolve via AniList GraphQL
                if (resolvedMalId.isBlank() && !animeTitle.isNullOrBlank()) {
                    val anilistRes = fetchAniListByTitle(animeTitle)
                    if (anilistRes != null) {
                        resolvedAniListId = anilistRes.anilistId
                        resolvedMalId = anilistRes.malId ?: ""
                        bannerUrl = anilistRes.bannerUrl
                    }
                    if (resolvedMalId.isBlank() && resolvedAniListId == null) {
                        val kitsuSearchResult = fetchKitsuByTitle(animeTitle)
                        if (kitsuSearchResult != null) {
                            resolvedMalId = kitsuSearchResult.malId ?: ""
                        }
                    }
                    if (resolvedMalId.isBlank() && resolvedAniListId == null) {
                        resolvedMalId = fetchMalIdByJikan(animeTitle) ?: ""
                    }
                }

                // 3. Query Ani.zip with resolved IDs
                var anizipEps = emptyMap<Int, EpisodeMetadata>()
                if (resolvedMalId.isNotBlank() || !resolvedAniListId.isNullOrBlank()) {
                    val anizipData = fetchAniZipData(malId = resolvedMalId, anilistId = resolvedAniListId)
                    anizipEps = anizipData.episodes
                    if (bannerUrl == null) bannerUrl = anizipData.bannerUrl
                }

                var merged = anizipEps

                // 4. Fallback if Ani.zip was missing or incomplete
                val needsMoreMetadata = merged.isEmpty() || merged.values.any { it.description.isNullOrBlank() || it.thumbnailUrl.isNullOrBlank() }
                if (needsMoreMetadata) {
                    val cinemetaDeferred = async {
                        if (!animeTitle.isNullOrBlank()) {
                            try { fetchCinemetaEpisodes(animeTitle, season = 1, isTv = true) } catch (_: Exception) { emptyMap() }
                        } else emptyMap()
                    }
                    val kitsuDeferred = async {
                        if (resolvedMalId.isNotBlank()) {
                            try { fetchKitsuEpisodes(resolvedMalId) } catch (_: Exception) { emptyMap() }
                        } else emptyMap()
                    }
                    val tmdbDeferred = async {
                        if (!animeTitle.isNullOrBlank() && effectiveTmdbKey.isNotBlank()) {
                            try { fetchTmdbEpisodes(animeTitle) } catch (_: Exception) { emptyMap() }
                        } else emptyMap()
                    }

                    val cinemetaEps = cinemetaDeferred.await()
                    if (cinemetaEps.isNotEmpty()) {
                        merged = mergeWithFallback(merged, cinemetaEps)
                    }

                    val stillNeeds = merged.isEmpty() || merged.values.any { it.description.isNullOrBlank() || it.thumbnailUrl.isNullOrBlank() }
                    if (stillNeeds) {
                        val kitsuEps = kitsuDeferred.await()
                        if (kitsuEps.isNotEmpty()) {
                            merged = mergeWithFallback(merged, kitsuEps)
                        }
                        val tmdbEps = tmdbDeferred.await()
                        if (tmdbEps.isNotEmpty()) {
                            merged = mergeWithFallback(merged, tmdbEps)
                        }
                    }
                }

                val cached = CachedData(merged, bannerUrl)
                synchronized(cache) { cache[cacheKey] = cached }

                val result = applyFallbackThumbnail(merged, bannerUrl, fallbackThumbnailUrl)
                val elapsed = System.currentTimeMillis() - startTime
                logi("fetched ${result.size} episodes in ${elapsed}ms")
                result
            } catch (e: Exception) {
                logw("failed for malId=$malId — ${e.message}. Episodes will load without enrichment.")
                synchronized(cache) { cache[cacheKey] = CachedData(emptyMap(), null) }
                emptyMap()
            }
        }
    }

    /**
     * Unified multi-provider media fetcher for Movies, TV Series, and Anime.
     * Cascades: Cinemeta (IMDb) -> TMDB (Fuzzy) -> TVMaze -> Ani.zip.
     * Guarantees field-level fallback and clean empty return on unknown titles.
     */
    suspend fun fetchMedia(
        title: String,
        season: Int = 1,
        isTv: Boolean = true,
        year: Int? = null,
    ): Map<Int, EpisodeMetadata> {
        if (title.isBlank()) return emptyMap()
        val (cleanTitle, extractedYear) = sanitizeTitleWithYear(title)
        val targetYear = year ?: extractedYear
        val cacheKey = "media|$cleanTitle|s$season|$isTv|$targetYear"

        synchronized(cache) {
            cache[cacheKey]?.let { return it.episodes }
        }

        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var merged = mutableMapOf<Int, EpisodeMetadata>()

            // 1. Cinemeta (Stremio / IMDb)
            val cinemetaDeferred = async {
                try {
                    fetchCinemetaEpisodes(cleanTitle, season, isTv, targetYear)
                } catch (_: Exception) {
                    emptyMap()
                }
            }

            // 2. TMDB (with fuzzy candidate scoring)
            val tmdbDeferred = async {
                try {
                    fetchTmdbEpisodes(cleanTitle, season, targetYear)
                } catch (_: Exception) {
                    emptyMap()
                }
            }

            // 3. TVMaze (for TV shows, 100% free zero-key API)
            val tvMazeDeferred = async {
                if (isTv) {
                    try {
                        fetchTvMazeEpisodes(cleanTitle, season, targetYear)
                    } catch (_: Exception) {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
            }

            val cinemetaEps = cinemetaDeferred.await()
            if (cinemetaEps.isNotEmpty()) {
                merged = mergeWithFallback(merged, cinemetaEps).toMutableMap()
            }

            val isComplete = merged.isNotEmpty() && merged.values.all {
                !it.description.isNullOrBlank() && !it.thumbnailUrl.isNullOrBlank()
            }

            if (!isComplete) {
                val tvMazeEps = tvMazeDeferred.await()
                if (tvMazeEps.isNotEmpty()) {
                    merged = mergeWithFallback(merged, tvMazeEps).toMutableMap()
                }

                val stillIncomplete = merged.isEmpty() || merged.values.any {
                    it.description.isNullOrBlank() || it.thumbnailUrl.isNullOrBlank()
                }
                if (stillIncomplete) {
                    val tmdbEps = tmdbDeferred.await()
                    if (tmdbEps.isNotEmpty()) {
                        merged = mergeWithFallback(merged, tmdbEps).toMutableMap()
                    }
                }
            }

            // If still missing metadata, try Ani.zip mapping for anime
            val isMissing = merged.isEmpty() || merged.values.any { it.description.isNullOrBlank() || it.thumbnailUrl.isNullOrBlank() }
            if (isMissing) {
                try {
                    val anilistRes = fetchAniListByTitle(cleanTitle)
                    val malId = anilistRes?.malId ?: fetchMalIdByJikan(cleanTitle)
                    val alId = anilistRes?.anilistId
                    if (!malId.isNullOrBlank() || !alId.isNullOrBlank()) {
                        val anizipEps = fetchAniZipEpisodes(malId = malId, anilistId = alId)
                        if (anizipEps.isNotEmpty()) {
                            merged = mergeWithFallback(merged, anizipEps).toMutableMap()
                        }
                    }
                } catch (_: Exception) {}
            }

            synchronized(cache) {
                cache[cacheKey] = CachedData(merged, null)
            }
            val elapsed = System.currentTimeMillis() - startTime
            logi("fetchMedia resolved ${merged.size} episodes in ${elapsed}ms for '$cleanTitle' S$season")
            merged
        }
    }

    /**
     * Resolves metadata for single movies ("Play Movie" cards).
     */
    fun fetchMovieMetadata(title: String, year: Int? = null): EpisodeMetadata? {
        if (title.isBlank()) return null
        val (cleanTitle, extractedYear) = sanitizeTitleWithYear(title)
        val targetYear = year ?: extractedYear

        // 1. Try TMDB Movie endpoint
        try {
            val q = URLEncoder.encode(cleanTitle, "UTF-8")
            val yearParam = if (targetYear != null) "&year=$targetYear" else ""
            val url = "$TMDB_BASE_URL/search/movie?api_key=$effectiveTmdbKey&query=$q$yearParam&language=en-US"
            val body = fetchString(url)
            if (body != null) {
                val res = JSONObject(body).optJSONArray("results")
                if (res != null && res.length() > 0) {
                    val m = res.getJSONObject(0)
                    val candName = m.optString("title").ifBlank { m.optString("original_title") }
                    val relDate = m.optString("release_date")
                    val candYear = relDate.take(4).toIntOrNull()
                    val score = calculateSimilarityScore(cleanTitle, candName, targetYear, candYear)
                    if (score >= 50) {
                        val backdrop = m.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                        val poster = m.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                        val overview = m.optString("overview").takeIf { it.isNotBlank() }
                        return EpisodeMetadata(
                            title = candName,
                            description = overview,
                            thumbnailUrl = backdrop ?: poster,
                            airdate = relDate,
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Try Cinemeta Movie catalog
        try {
            val q = URLEncoder.encode(cleanTitle, "UTF-8")
            val url = "$CINEMETA_BASE_URL/catalog/movie/top/search=$q.json"
            val body = fetchString(url)
            if (body != null) {
                val metas = JSONObject(body).optJSONArray("metas")
                if (metas != null && metas.length() > 0) {
                    val m = metas.getJSONObject(0)
                    val candName = m.optString("name")
                    val candYear = m.optString("year").take(4).toIntOrNull()
                    val score = calculateSimilarityScore(cleanTitle, candName, targetYear, candYear)
                    if (score >= 50) {
                        val desc = m.optString("description").takeIf { it.isNotBlank() }
                        val poster = m.optString("poster").takeIf { it.isNotBlank() }
                        val bg = m.optString("background").takeIf { it.isNotBlank() }
                        return EpisodeMetadata(
                            title = candName,
                            description = desc,
                            thumbnailUrl = bg ?: poster,
                            airdate = m.optString("year"),
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun applyFallbackThumbnail(
        episodes: Map<Int, EpisodeMetadata>,
        bannerUrl: String?,
        animeCoverUrl: String?,
    ): Map<Int, EpisodeMetadata> {
        return episodes
    }

    private fun mergeWithFallback(
        primary: Map<Int, EpisodeMetadata>,
        fallback: Map<Int, EpisodeMetadata>
    ): Map<Int, EpisodeMetadata> {
        if (primary.isEmpty()) return fallback
        val allKeys = primary.keys + fallback.keys
        return allKeys.associateWith { num ->
            val p = primary[num]
            val f = fallback[num]
            EpisodeMetadata(
                title = p?.title ?: f?.title,
                description = p?.description ?: f?.description,
                thumbnailUrl = p?.thumbnailUrl ?: f?.thumbnailUrl,
                airdate = p?.airdate ?: f?.airdate
            )
        }
    }

    private fun mergeEpisodes(
        anikage: Map<Int, EpisodeMetadata>,
        kitsu: Map<Int, EpisodeMetadata>,
        anilistStreaming: List<AniListStreamingEpisode>,
        jikan: Map<Int, JikanEpisode>,
    ): Map<Int, EpisodeMetadata> {
        val anilistByNum = anilistStreaming.mapIndexedNotNull { idx, ep ->
            (idx + 1) to ep
        }.toMap()

        val allKeys = anikage.keys + kitsu.keys + anilistByNum.keys + jikan.keys
        return allKeys.associateWith { num ->
            val ak = anikage[num]
            val al = anilistByNum[num]
            val k = kitsu[num]
            val jk = jikan[num]
            EpisodeMetadata(
                title = jk?.title ?: ak?.title ?: k?.title,
                description = ak?.description ?: k?.description,
                thumbnailUrl = ak?.thumbnailUrl ?: al?.thumbnail ?: k?.thumbnailUrl,
                airdate = jk?.aired ?: ak?.airdate ?: k?.airdate,
            )
        }
    }

    private fun fetchAniListId(malId: String): String? {
        val query = "query { Media(idMal: $malId, type: ANIME) { id bannerImage streamingEpisodes { title thumbnail } } }"
        val body = """{"query":"$query"}"""
        val respBody = postJson("https://graphql.anilist.co", body) ?: return null
        return try {
            val resp = localJson.decodeFromString(AniListMediaResponse.serializer(), respBody)
            val media = resp.data?.media
            val id = media?.id
            logd("AniList ID for malId=$malId → $id (streamingEpisodes=${media?.streamingEpisodes?.size ?: 0})")

            if (id != null) {
                val streamingThumbs = media?.streamingEpisodes ?: emptyList()
                anilistStreamingCache[malId] = streamingThumbs
                anilistBannerCache[malId] = media?.bannerImage
            }

            id?.toString()
        } catch (e: Exception) {
            logd("AniList ID parse failed — ${e.message}")
            null
        }
    }

    private data class AniListResolved(
        val anilistId: String?,
        val malId: String?,
        val bannerUrl: String?,
        val description: String?,
    )

    private fun fetchAniListByTitle(title: String): AniListResolved? {
        val cleanTitle = sanitizeTitle(title)
        val query = """query (${'$'}search: String) { Media(search: ${'$'}search, type: ANIME) { id idMal bannerImage description(asHtml: false) } }"""
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().put("search", cleanTitle))
        }.toString()
        val respBody = postJson("https://graphql.anilist.co", body) ?: return null
        return try {
            val json = JSONObject(respBody)
            val media = json.optJSONObject("data")?.optJSONObject("Media") ?: return null
            val id = media.optInt("id").takeIf { it > 0 }?.toString()
            val malId = media.optInt("idMal").takeIf { it > 0 }?.toString()
            val banner = media.optString("bannerImage").takeIf { it.isNotBlank() }
            val desc = media.optString("description").takeIf { it.isNotBlank() }
            AniListResolved(id, malId, banner, desc)
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchAniListSynopsis(title: String): String? {
        val cleanTitle = sanitizeTitle(title)
        val query = """query (${'$'}search: String) { Media(search: ${'$'}search, type: ANIME) { description(asHtml: false) } }"""
        val body = JSONObject().apply {
            put("query", query)
            put("variables", JSONObject().put("search", cleanTitle))
        }.toString()
        val respBody = postJson("https://graphql.anilist.co", body) ?: return null
        return try {
            val json = JSONObject(respBody)
            val desc = json.optJSONObject("data")?.optJSONObject("Media")?.optString("description")
            desc?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchAnikageEpisodes(anilistId: String): Map<Int, EpisodeMetadata> {
        // Anikage API is obsolete / Cloudflare-gated
        return emptyMap()
    }

    private data class KitsuSearchResult(
        val kitsuId: String,
        val malId: String?,
        val synopsis: String?,
        val episodes: Map<Int, EpisodeMetadata>,
    )

    private fun fetchKitsuByTitle(title: String): KitsuSearchResult? {
        val cleanTitle = sanitizeTitle(title)
        val searchUrl = "https://kitsu.app/api/edge/anime?filter[text]=${URLEncoder.encode(cleanTitle, "UTF-8")}&page[limit]=1"
        val body = fetchString(searchUrl) ?: return null
        return try {
            val response = localJson.decodeFromString(KitsuSearchResponse.serializer(), body)
            val anime = response.data.firstOrNull() ?: return null
            val kitsuId = anime.id
            val synopsis = anime.attributes?.synopsis?.takeIf { it.isNotBlank() }?.let { stripHtml(it) }
            val malId = fetchMalIdFromKitsuMappings(kitsuId)
            val episodes = fetchKitsuEpisodesByKitsuId(kitsuId)
            KitsuSearchResult(kitsuId, malId, synopsis, episodes)
        } catch (e: Exception) {
            logd("Kitsu search by title failed — ${e.message}")
            null
        }
    }

    private fun fetchMalIdFromKitsuMappings(kitsuId: String): String? {
        val url = "https://kitsu.app/api/edge/anime/$kitsuId/mappings"
        val body = fetchString(url) ?: return null
        return try {
            val response = localJson.decodeFromString(KitsuMappingsListResponse.serializer(), body)
            response.data.firstOrNull { it.attributes?.externalSite == "myanimelist/anime" }?.attributes?.externalId
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchMalIdByJikan(title: String): String? {
        val cleanTitle = sanitizeTitle(title)
        val jikanUrl = "https://api.jikan.moe/v4/anime?q=${URLEncoder.encode(cleanTitle, "UTF-8")}&limit=1"
        val body = fetchString(jikanUrl) ?: return null
        return try {
            val obj = JSONObject(body)
            val dataArr = obj.optJSONArray("data")
            val id = dataArr?.optJSONObject(0)?.optInt("mal_id")
            if (id != null && id > 0) id.toString() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchKitsuEpisodes(malId: String): Map<Int, EpisodeMetadata> {
        val kitsuId = fetchKitsuId(malId) ?: return emptyMap()
        return fetchKitsuEpisodesByKitsuId(kitsuId)
    }

    private fun fetchKitsuEpisodesByKitsuId(kitsuId: String): Map<Int, EpisodeMetadata> {
        val result = mutableMapOf<Int, EpisodeMetadata>()
        var nextUrl: String? = "https://kitsu.app/api/edge/anime/$kitsuId/episodes?page[limit]=20&sort=number"
        var pageCount = 0
        val maxPages = 10

        while (nextUrl != null && pageCount < maxPages) {
            pageCount++
            val body = fetchString(nextUrl) ?: break
            val response = try {
                localJson.decodeFromString(KitsuEpisodesResponse.serializer(), body)
            } catch (e: Exception) {
                break
            }
            for (ep in response.data) {
                val attrs = ep.attributes ?: continue
                val number = attrs.number ?: continue
                val thumbUrl = attrs.thumbnail?.original
                result[number] = EpisodeMetadata(
                    title = attrs.canonicalTitle?.takeIf { it.isNotBlank() },
                    description = (attrs.description ?: attrs.synopsis)?.takeIf { it.isNotBlank() }?.let { stripHtml(it) },
                    thumbnailUrl = thumbUrl?.takeIf { it.isNotBlank() },
                    airdate = attrs.airdate?.takeIf { it.isNotBlank() },
                )
            }
            nextUrl = response.links?.next
        }
        logd("Kitsu returned ${result.size} episodes")
        return result
    }

    private fun fetchKitsuId(malId: String): String? {
        val url = "https://kitsu.app/api/edge/mappings" +
            "?filter[externalSite]=myanimelist/anime" +
            "&filter[externalId]=$malId" +
            "&include=item"
        val body = fetchString(url) ?: return null
        val response = try {
            localJson.decodeFromString(KitsuMappingResponse.serializer(), body)
        } catch (e: Exception) {
            return null
        }
        val anime = response.included?.firstOrNull { it.type == "anime" }
        return anime?.id
    }

    private fun fetchJikanEpisodes(malId: String): Map<Int, JikanEpisode> {
        val url = "https://api.jikan.moe/v4/anime/$malId/episodes"
        val body = fetchString(url) ?: return emptyMap()
        val response = try {
            localJson.decodeFromString(JikanEpisodesResponse.serializer(), body)
        } catch (e: Exception) {
            logd("Jikan parse failed — ${e.message}")
            return emptyMap()
        }
        val result = mutableMapOf<Int, JikanEpisode>()
        for (ep in response.data) {
            val num = ep.malId ?: continue
            result[num] = JikanEpisode(
                title = ep.title?.takeIf { it.isNotBlank() },
                aired = ep.aired?.takeIf { it.isNotBlank() },
            )
        }
        logd("Jikan returned ${result.size} episodes")
        return result
    }

    private fun isCloudflareHost(url: String): Boolean = url.contains("anilist.co") || url.contains("kitsu.app")

    private fun fetchString(url: String): String? {
        if (isCloudflareHost(url) && webViewFetchText != null) {
            return try {
                logd("using WebView for ${url.take(60)}")
                kotlinx.coroutines.runBlocking { webViewFetchText.invoke(url) }
            } catch (e: Exception) {
                logd("WebView fetch failed for ${url.take(60)} — ${e.message}")
                null
            }
        }
        return try {
            val req = Request.Builder().url(url).headers(apiHeaders).build()
            metaClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logd("HTTP ${resp.code} for ${url.take(80)}")
                    return null
                }
                resp.body.string()
            }
        } catch (e: Exception) {
            logd("fetch failed for ${url.take(60)} — ${e.message}")
            null
        }
    }

    private fun postJson(url: String, jsonBody: String): String? {
        if (isCloudflareHost(url) && webViewPostJson != null) {
            return try {
                logd("using WebView POST for ${url.take(60)}")
                kotlinx.coroutines.runBlocking { webViewPostJson.invoke(url, jsonBody) }
            } catch (e: Exception) {
                logd("WebView POST failed — ${e.message}")
                null
            }
        }
        return try {
            val body = okhttp3.RequestBody.create(
                "application/json; charset=utf-8".toMediaTypeOrNull(),
                jsonBody,
            )
            val req = Request.Builder().url(url).headers(apiHeaders).post(body).build()
            metaClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logd("POST HTTP ${resp.code} for ${url.take(60)}")
                    return null
                }
                resp.body.string()
            }
        } catch (e: Exception) {
            logd("POST failed for ${url.take(60)} — ${e.message}")
            null
        }
    }

    private fun stripHtml(text: String): String = text.replace(Regex("<[^>]+>"), "").trim()

    // ── TMDB Fallback ──────────────────────────────────────────────────────────

    private fun fetchTmdbEpisodes(title: String, season: Int = 1, year: Int? = null): Map<Int, EpisodeMetadata> {
        val (cleanTitle, extractedYear) = sanitizeTitleWithYear(title)
        val targetYear = year ?: extractedYear
        var result = performTmdbSearch(title, season, targetYear)
        if (result.isEmpty() && cleanTitle != title && cleanTitle.isNotBlank()) {
            result = performTmdbSearch(cleanTitle, season, targetYear)
        }
        return result
    }

    private fun performTmdbSearch(query: String, season: Int = 1, year: Int? = null): Map<Int, EpisodeMetadata> {
        return try {
            val yearParam = if (year != null) "&year=$year" else ""
            val searchUrl = "$TMDB_BASE_URL/search/multi?api_key=$effectiveTmdbKey&query=${URLEncoder.encode(query, "UTF-8")}$yearParam&language=en-US"
            val response = fetchString(searchUrl) ?: return emptyMap()
            val results = JSONObject(response).getJSONArray("results")
            if (results.length() == 0) return emptyMap()

            var bestId: Int? = null
            var bestType: String? = null
            var highestScore = -1
            var maxVotes = -1
            var bestIsAnimation = false

            for (i in 0 until minOf(results.length(), 10)) {
                val res = results.getJSONObject(i)
                val mType = res.optString("media_type")
                if (mType != "movie" && mType != "tv") continue

                val resultTitle = res.optString("name").ifBlank { res.optString("title") }
                val resultOriginalTitle = res.optString("original_name").ifBlank { res.optString("original_title") }
                val relDate = res.optString("release_date").ifBlank { res.optString("first_air_date") }
                val candYear = relDate.take(4).toIntOrNull()

                val score = maxOf(
                    calculateSimilarityScore(query, resultTitle, year, candYear),
                    calculateSimilarityScore(query, resultOriginalTitle, year, candYear)
                )

                if (score < 50) {
                    val resId = res.getInt("id")
                    val altUrl = "$TMDB_BASE_URL/$mType/$resId/alternative_titles?api_key=$effectiveTmdbKey"
                    var altScore = 0
                    try {
                        val altRes = fetchString(altUrl)
                        if (altRes != null) {
                            val altArray = JSONObject(altRes).optJSONArray("results") ?: JSONObject(altRes).optJSONArray("titles")
                            if (altArray != null) {
                                for (j in 0 until altArray.length()) {
                                    val alt = altArray.getJSONObject(j).optString("title")
                                    altScore = maxOf(altScore, calculateSimilarityScore(query, alt, year, candYear))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    if (altScore < 50) continue
                }

                val votes = res.optInt("vote_count", 0)
                val genreIds = res.optJSONArray("genre_ids")
                val isAnimation = genreIds?.let { ids ->
                    (0 until ids.length()).any { ids.getInt(it) == 16 }
                } ?: false

                val finalScore = maxOf(score, 50) + (if (isAnimation) 25 else 0)
                if (finalScore > highestScore) {
                    highestScore = finalScore
                    maxVotes = votes
                    bestIsAnimation = isAnimation
                    bestId = res.getInt("id")
                    bestType = mType
                } else if (finalScore == highestScore) {
                    if (isAnimation && !bestIsAnimation) {
                        bestIsAnimation = true
                        maxVotes = votes
                        bestId = res.getInt("id")
                        bestType = mType
                    } else if (isAnimation == bestIsAnimation && votes > maxVotes) {
                        maxVotes = votes
                        bestId = res.getInt("id")
                        bestType = mType
                    }
                }
            }

            if (bestId != null && bestType != null) {
                constructTmdbMetadata(bestId, bestType, season)
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            logd("TMDB search failed — ${e.message}")
            emptyMap()
        }
    }

    private fun constructTmdbMetadata(id: Int, mediaType: String, season: Int): Map<Int, EpisodeMetadata> {
        val detailUrl = "$TMDB_BASE_URL/$mediaType/$id?api_key=$effectiveTmdbKey&language=en-US"
        val detailResponse = fetchString(detailUrl) ?: return emptyMap()
        return try {
            val detailJson = JSONObject(detailResponse)
            if (mediaType == "movie") {
                val movieTitle = detailJson.optString("title").ifBlank { detailJson.optString("name") }
                val backdrop = detailJson.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                val mainSummary = detailJson.optString("overview").takeIf { it.isNotBlank() }
                val mainPoster = detailJson.optString("poster_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                val releaseDate = detailJson.optString("release_date").takeIf { it.isNotBlank() }
                val epMeta = EpisodeMetadata(
                    title = movieTitle,
                    description = mainSummary,
                    thumbnailUrl = backdrop ?: mainPoster,
                    airdate = releaseDate
                )
                mapOf(1 to epMeta)
            } else {
                val seasonUrl = "$TMDB_BASE_URL/tv/$id/season/$season?api_key=$effectiveTmdbKey&language=en-US"
                val seasonBody = fetchString(seasonUrl) ?: return emptyMap()
                val seasonJson = JSONObject(seasonBody)
                val episodes = seasonJson.optJSONArray("episodes") ?: JSONArray()

                val epMap = mutableMapOf<Int, EpisodeMetadata>()
                val genericRegex = Regex("(?i)^(Episode|Épisode)\\s*\\d+$|^第\\d+[話回]$|^\\d+$")

                for (i in 0 until episodes.length()) {
                    val ep = episodes.getJSONObject(i)
                    val num = ep.getInt("episode_number")
                    val name = ep.optString("name").trim().takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                    val summary = ep.optString("overview").trim().takeIf { it.isNotBlank() }
                    val thumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                    val airdate = ep.optString("air_date").takeIf { it.isNotBlank() }

                    epMap[num] = EpisodeMetadata(
                        title = name,
                        description = summary,
                        thumbnailUrl = thumb,
                        airdate = airdate
                    )
                }

                if (season > 0) {
                    val tmdbSeasons = detailJson.optJSONArray("seasons")
                    var offset = 0
                    if (tmdbSeasons != null) {
                        for (i in 0 until tmdbSeasons.length()) {
                            val s = tmdbSeasons.getJSONObject(i)
                            val sNum = s.optInt("season_number")
                            if (sNum in 1 until season) {
                                offset += s.optInt("episode_count")
                            }
                        }
                    }
                    if (offset > 0) {
                        try {
                            val s1Url = "$TMDB_BASE_URL/tv/$id/season/1?api_key=$effectiveTmdbKey&language=en-US"
                            val s1Body = fetchString(s1Url)
                            if (s1Body != null) {
                                val s1Episodes = JSONObject(s1Body).optJSONArray("episodes")
                                if (s1Episodes != null) {
                                    for (i in 0 until s1Episodes.length()) {
                                        val ep = s1Episodes.getJSONObject(i)
                                        val absNum = ep.getInt("episode_number")
                                        if (absNum > offset) {
                                            val relNum = absNum - offset
                                            if (epMap[relNum]?.title == null) {
                                                val name = ep.optString("name").trim().takeIf { it.isNotBlank() && !it.matches(genericRegex) }
                                                val summary = ep.optString("overview").trim().takeIf { it.isNotBlank() }
                                                val thumb = ep.optString("still_path").takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w500$it" }
                                                val airdate = ep.optString("air_date").takeIf { it.isNotBlank() }
                                                epMap[relNum] = EpisodeMetadata(
                                                    title = name,
                                                    description = summary,
                                                    thumbnailUrl = thumb,
                                                    airdate = airdate
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                epMap
            }
        } catch (e: Exception) {
            logd("TMDB construct failed — ${e.message}")
            emptyMap()
        }
    }

    // ── Cinemeta (Stremio / IMDb) Provider ─────────────────────────────────────

    private fun fetchCinemetaEpisodes(
        title: String,
        season: Int = 1,
        isTv: Boolean = true,
        year: Int? = null,
    ): Map<Int, EpisodeMetadata> {
        val (cleanTitle, extractedYear) = sanitizeTitleWithYear(title)
        val targetYear = year ?: extractedYear
        val metaType = if (isTv) "series" else "movie"
        val q = URLEncoder.encode(cleanTitle, "UTF-8")
        val searchUrl = "$CINEMETA_BASE_URL/catalog/$metaType/top/search=$q.json"
        val body = fetchString(searchUrl) ?: return emptyMap()

        return try {
            val jsonRes = JSONObject(body)
            val metas = jsonRes.optJSONArray("metas") ?: return emptyMap()
            var bestId: String? = null
            var bestScore = -1

            for (i in 0 until minOf(metas.length(), 10)) {
                val meta = metas.getJSONObject(i)
                val candName = meta.optString("name")
                val candYear = meta.optString("year").take(4).toIntOrNull()
                val score = calculateSimilarityScore(cleanTitle, candName, targetYear, candYear)
                if (score > bestScore) {
                    bestScore = score
                    bestId = meta.optString("imdb_id").takeIf { it.isNotBlank() } ?: meta.optString("id")
                }
            }

            if (bestId != null && bestScore >= 50) {
                val detailUrl = "$CINEMETA_BASE_URL/meta/$metaType/$bestId.json"
                val detailBody = fetchString(detailUrl) ?: return emptyMap()
                val detailJson = JSONObject(detailBody)
                val metaObj = detailJson.optJSONObject("meta") ?: return emptyMap()
                val videos = metaObj.optJSONArray("videos") ?: return emptyMap()
                val result = mutableMapOf<Int, EpisodeMetadata>()

                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    val sNum = v.optInt("season", 1)
                    if (sNum == season) {
                        val epNum = if (v.has("number")) v.getInt("number") else if (v.has("episode")) v.getInt("episode") else 1
                        val name = v.optString("name").trim().takeIf { it.isNotBlank() }
                        val overview = v.optString("overview").trim().takeIf { it.isNotBlank() }
                            ?: v.optString("description").trim().takeIf { it.isNotBlank() }
                        val thumb = v.optString("thumbnail").trim().takeIf { it.isNotBlank() }
                        val released = v.optString("released").trim().takeIf { it.isNotBlank() }
                        result[epNum] = EpisodeMetadata(
                            title = name,
                            description = overview?.let { stripHtml(it) },
                            thumbnailUrl = thumb,
                            airdate = released
                        )
                    }
                }
                result
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            logd("Cinemeta fetch failed — ${e.message}")
            emptyMap()
        }
    }

    // ── TVMaze Provider ────────────────────────────────────────────────────────

    private fun fetchTvMazeEpisodes(
        title: String,
        season: Int = 1,
        year: Int? = null,
    ): Map<Int, EpisodeMetadata> {
        val (cleanTitle, extractedYear) = sanitizeTitleWithYear(title)
        val targetYear = year ?: extractedYear
        val q = URLEncoder.encode(cleanTitle, "UTF-8")
        val url = "$TVMAZE_BASE_URL/singlesearch/shows?q=$q&embed=episodes"
        val body = fetchString(url) ?: return emptyMap()

        return try {
            val show = JSONObject(body)
            val showName = show.optString("name")
            val premiered = show.optString("premiered")
            val candYear = premiered.take(4).toIntOrNull()
            val score = calculateSimilarityScore(cleanTitle, showName, targetYear, candYear)
            if (score < 50) return emptyMap()

            val embedded = show.optJSONObject("_embedded") ?: return emptyMap()
            val episodes = embedded.optJSONArray("episodes") ?: return emptyMap()
            val result = mutableMapOf<Int, EpisodeMetadata>()

            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                val sNum = ep.optInt("season", 1)
                if (sNum == season) {
                    val epNum = ep.optInt("number", 1)
                    val name = ep.optString("name").trim().takeIf { it.isNotBlank() }
                    val summary = ep.optString("summary").trim().takeIf { it.isNotBlank() }
                    val imgObj = ep.optJSONObject("image")
                    val thumb = imgObj?.optString("original")?.takeIf { it.isNotBlank() }
                        ?: imgObj?.optString("medium")?.takeIf { it.isNotBlank() }
                    val airdate = ep.optString("airdate").trim().takeIf { it.isNotBlank() }
                    result[epNum] = EpisodeMetadata(
                        title = name,
                        description = summary?.let { stripHtml(it) },
                        thumbnailUrl = thumb,
                        airdate = airdate
                    )
                }
            }
            result
        } catch (e: Exception) {
            logd("TVMaze fetch failed — ${e.message}")
            emptyMap()
        }
    }

    // ── Ani.zip Provider ───────────────────────────────────────────────────────

    private data class AniZipData(
        val episodes: Map<Int, EpisodeMetadata>,
        val bannerUrl: String? = null,
        val malId: String? = null,
        val anilistId: String? = null,
    )

    private fun fetchAniZipData(
        malId: String? = null,
        anilistId: String? = null,
    ): AniZipData {
        val param = when {
            !anilistId.isNullOrBlank() -> "anilist_id=$anilistId"
            !malId.isNullOrBlank() -> "mal_id=$malId"
            else -> return AniZipData(emptyMap())
        }
        val url = "$ANIZIP_BASE_URL?$param"
        val body = fetchString(url) ?: return AniZipData(emptyMap())

        return try {
            val root = JSONObject(body)
            val epsObj = root.optJSONObject("episodes") ?: return AniZipData(emptyMap())
            val result = mutableMapOf<Int, EpisodeMetadata>()
            val keys = epsObj.keys()

            while (keys.hasNext()) {
                val key = keys.next()
                val epNum = key.toIntOrNull() ?: continue
                val ep = epsObj.getJSONObject(key)
                val titleObj = ep.optJSONObject("title")
                val epTitle = titleObj?.optString("en")?.takeIf { it.isNotBlank() }
                    ?: titleObj?.optString("x-jat")?.takeIf { it.isNotBlank() }
                    ?: titleObj?.optString("ja")?.takeIf { it.isNotBlank() }
                    ?: ep.optString("name").takeIf { it.isNotBlank() }

                val summary = ep.optString("overview").takeIf { it.isNotBlank() }
                    ?: ep.optString("summary").takeIf { it.isNotBlank() }

                val image = ep.optString("image").takeIf { it.isNotBlank() }
                val airDate = ep.optString("airDate").takeIf { it.isNotBlank() }
                    ?: ep.optString("airdate").takeIf { it.isNotBlank() }

                result[epNum] = EpisodeMetadata(
                    title = epTitle,
                    description = summary?.let { stripHtml(it) },
                    thumbnailUrl = image,
                    airdate = airDate,
                )
            }

            var bannerUrl: String? = null
            val images = root.optJSONArray("images")
            if (images != null) {
                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    if (img.optString("coverType") == "Banner") {
                        bannerUrl = img.optString("url").takeIf { it.isNotBlank() }
                        if (bannerUrl != null) break
                    }
                }
            }

            val mappings = root.optJSONObject("mappings")
            val mappedMalId = mappings?.optString("mal_id")?.takeIf { it.isNotBlank() }
            val mappedAniListId = mappings?.optString("anilist_id")?.takeIf { it.isNotBlank() }

            AniZipData(result, bannerUrl, mappedMalId, mappedAniListId)
        } catch (e: Exception) {
            logd("Ani.zip fetch failed — ${e.message}")
            AniZipData(emptyMap())
        }
    }

    private fun fetchAniZipEpisodes(
        malId: String? = null,
        anilistId: String? = null,
    ): Map<Int, EpisodeMetadata> = fetchAniZipData(malId, anilistId).episodes

    // ── Title Sanitizer & Fuzzy Matcher ────────────────────────────────────────

    fun sanitizeTitleWithYear(title: String): Pair<String, Int?> {
        val yearMatch = YEAR_REGEX.find(title)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

        var clean = title
            .replace(SEASON_EP_REGEX, " ")
            .replace(AUDIO_DUB_REGEX, " ")
            .replace(QUALITY_CODEC_REGEX, " ")
            .replace(TAGS_REGEX, " ")
            .replace(BRACKETS_REGEX, " ")
            .replace(SEPARATORS_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.isBlank()) {
            clean = title.trim()
        }
        return Pair(clean, year)
    }

    private fun sanitizeTitle(title: String): String = sanitizeTitleWithYear(title).first

    fun calculateSimilarityScore(
        query: String,
        candidate: String,
        queryYear: Int? = null,
        candidateYear: Int? = null,
    ): Int {
        fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val s1 = normalize(query)
        val s2 = normalize(candidate)
        if (s1.isBlank() || s2.isBlank()) return 0
        var score = 0
        if (s1 == s2) {
            score = 100
        } else {
            val flat1 = s1.replace(" ", "")
            val flat2 = s2.replace(" ", "")
            if (flat1 == flat2) {
                score = 95
            } else if (s1.contains(s2) || s2.contains(s1)) {
                val longer = maxOf(s1.length, s2.length)
                val shorter = minOf(s1.length, s2.length)
                score = Math.max(0, 85 - (longer - shorter) * 4)
            } else {
                val words1 = s1.split(" ").filter { it.length >= 2 }
                val words2 = s2.split(" ").filter { it.length >= 2 }
                if (words1.isNotEmpty() && words2.isNotEmpty()) {
                    val matchingWords = words1.count { w1 -> words2.any { w2 -> w1 == w2 } }
                    score = (matchingWords.toDouble() / maxOf(words1.size, words2.size) * 80).toInt()
                }
            }
        }
        if (queryYear != null && candidateYear != null && queryYear == candidateYear) {
            score = minOf(100, score + 15)
        }
        return score
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    @Serializable
    private data class AniListMediaResponse(
        val data: AniListMediaData? = null,
    )

    @Serializable
    private data class AniListMediaData(
        @SerialName("Media") val media: AniListMedia? = null,
    )

    @Serializable
    private data class AniListMedia(
        val id: Int? = null,
        @SerialName("bannerImage") val bannerImage: String? = null,
        @SerialName("streamingEpisodes") val streamingEpisodes: List<AniListStreamingEpisode>? = null,
    )

    @Serializable
    private data class AniListStreamingEpisode(
        val title: String? = null,
        val thumbnail: String? = null,
    )

    @Serializable
    private data class AnikageEpisode(
        val number: Int? = null,
        val title: String? = null,
        val description: String? = null,
        val img: String? = null,
        @SerialName("airDate") val airDate: String? = null,
    )

    @Serializable
    private data class KitsuMappingResponse(
        val data: List<KitsuMappingData> = emptyList(),
        val included: List<KitsuAnime>? = null,
    )

    @Serializable
    private data class KitsuMappingData(
        val id: String,
        val type: String,
    )

    @Serializable
    private data class KitsuAnime(
        val id: String,
        val type: String,
    )

    @Serializable
    private data class KitsuSearchResponse(
        val data: List<KitsuSearchItem> = emptyList(),
    )

    @Serializable
    private data class KitsuSearchItem(
        val id: String,
        val type: String,
        val attributes: KitsuSearchAttributes? = null,
    )

    @Serializable
    private data class KitsuSearchAttributes(
        val canonicalTitle: String? = null,
        val synopsis: String? = null,
        val description: String? = null,
    )

    @Serializable
    private data class KitsuMappingsListResponse(
        val data: List<KitsuMappingItem> = emptyList(),
    )

    @Serializable
    private data class KitsuMappingItem(
        val id: String,
        val type: String,
        val attributes: KitsuMappingItemAttributes? = null,
    )

    @Serializable
    private data class KitsuMappingItemAttributes(
        val externalSite: String? = null,
        val externalId: String? = null,
    )

    @Serializable
    private data class KitsuEpisodesResponse(
        val data: List<KitsuEpisode> = emptyList(),
        val links: KitsuLinks? = null,
    )

    @Serializable
    private data class KitsuEpisode(
        val id: String,
        val type: String,
        val attributes: KitsuEpisodeAttributes? = null,
    )

    @Serializable
    private data class KitsuEpisodeAttributes(
        val number: Int? = null,
        @SerialName("canonicalTitle") val canonicalTitle: String? = null,
        val description: String? = null,
        val synopsis: String? = null,
        val thumbnail: KitsuImage? = null,
        val airdate: String? = null,
    )

    @Serializable
    private data class KitsuImage(
        val original: String? = null,
    )

    @Serializable
    private data class KitsuLinks(
        val next: String? = null,
    )

    @Serializable
    private data class JikanEpisode(
        val title: String? = null,
        val aired: String? = null,
    )

    @Serializable
    private data class JikanEpisodesResponse(
        val data: List<JikanEpisodeData> = emptyList(),
    )

    @Serializable
    private data class JikanEpisodeData(
        @SerialName("mal_id") val malId: Int? = null,
        val title: String? = null,
        val aired: String? = null,
    )

    companion object {
        const val VERSION = 2
        private const val TAG = "EpisodeMetadataFetcher"
        private const val FAST_TIMEOUT_MS = 2500L
        private const val FAST_CALL_TIMEOUT_MS = 3000L
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
        private const val DEFAULT_TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        private const val TVMAZE_BASE_URL = "https://api.tvmaze.com"
        private const val ANIZIP_BASE_URL = "https://api.ani.zip/mappings"

        private val YEAR_REGEX = Regex("""\b(19\d\d|20\d\d)\b""")
        private val SEASON_EP_REGEX = Regex("""(?i)\b(season\s*\d+|s\d+|part\s*\d+|cour\s*\d+|episode\s*\d+|ep\s*\d+)\b""")
        private val AUDIO_DUB_REGEX = Regex("""(?i)\b(hindi|tamil|telugu|bengali|malayalam|kannada|english|japanese|dual\s*audio|multi\s*audio|multi\s*sub|dubbed|dub|sub|subbed|esub)\b""")
        private val QUALITY_CODEC_REGEX = Regex("""(?i)\b(1080p|720p|480p|2160p|4k|uhd|fhd|hd|web-?dl|web-?rip|hdrip|bluray|brrip|dvdrip|h\.?26[45]|hevc|x26[45]|aac|atmos)\b""")
        private val TAGS_REGEX = Regex("""(?i)\b(complete|batch|remastered|extended|unrated|directors\s*cut)\b""")
        private val BRACKETS_REGEX = Regex("""\[.*?\]|\(.*?\)|<.*?>|\{.*?\}""")
        private val SEPARATORS_REGEX = Regex("""[\-_:|]+""")
    }
}

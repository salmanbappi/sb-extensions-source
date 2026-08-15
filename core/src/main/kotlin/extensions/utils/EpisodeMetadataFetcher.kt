package extensions.utils

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

class EpisodeMetadataFetcher(
    private val client: OkHttpClient,
    private val json: Json,
    private val webViewFetchText: (suspend (String) -> String?)? = null,
    private val webViewPostJson: (suspend (String, String) -> String?)? = null,
    private val tmdbApiKey: String = "",
) {
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

    private val cache = mutableMapOf<String, CachedData>()
    private val anilistStreamingCache = mutableMapOf<String, List<AniListStreamingEpisode>>()
    private val anilistBannerCache = mutableMapOf<String, String?>()
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
                // 1. Try Kitsu
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
                // 2. Try Jikan
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
                var kitsuEpsByTitle = emptyMap<Int, EpisodeMetadata>()

                if (resolvedMalId.isBlank() && !animeTitle.isNullOrBlank()) {
                    val kitsuSearchResult = fetchKitsuByTitle(animeTitle)
                    if (kitsuSearchResult != null) {
                        kitsuEpsByTitle = kitsuSearchResult.episodes
                        resolvedMalId = kitsuSearchResult.malId ?: ""
                    }
                    if (resolvedMalId.isBlank()) {
                        resolvedMalId = fetchMalIdByJikan(animeTitle) ?: ""
                    }
                }

                // 1. Fetch AniList and Jikan concurrently
                val jikanDeferred = async { if (resolvedMalId.isNotBlank()) fetchJikanEpisodes(resolvedMalId) else emptyMap() }
                val anilistIdDeferred = async { if (resolvedMalId.isNotBlank()) fetchAniListId(resolvedMalId) else null }

                val jikanEps = jikanDeferred.await()
                val anilistId = anilistIdDeferred.await()

                val anikageEps = if (anilistId != null) fetchAnikageEpisodes(anilistId) else emptyMap()
                val anilistStreaming = if (resolvedMalId.isNotBlank()) anilistStreamingCache[resolvedMalId] ?: emptyList() else emptyList()
                val bannerUrl = if (resolvedMalId.isNotBlank()) anilistBannerCache[resolvedMalId] else null

                // Merge primary metadata
                var merged = mergeEpisodes(anikageEps, kitsuEpsByTitle, anilistStreaming, jikanEps)

                // 2. If primary metadata is incomplete (missing synopsis/thumbnail), query TMDB fallback
                val needsMoreMetadata = merged.isEmpty() || merged.values.any { it.description.isNullOrBlank() || it.thumbnailUrl.isNullOrBlank() }

                if (needsMoreMetadata && !animeTitle.isNullOrBlank() && tmdbApiKey.isNotBlank()) {
                    logd("Primary metadata incomplete/empty. Fetching from TMDB fallback...")
                    val tmdbEps = fetchTmdbEpisodes(animeTitle)
                    if (tmdbEps.isNotEmpty()) {
                        merged = mergeWithFallback(merged, tmdbEps)
                    }
                }

                // 3. Last resort: If still incomplete, query Kitsu with resolvedMalId
                val stillNeedsMetadata = merged.isEmpty() || merged.values.any { it.description.isNullOrBlank() || it.thumbnailUrl.isNullOrBlank() }
                if (stillNeedsMetadata && resolvedMalId.isNotBlank() && kitsuEpsByTitle.isEmpty()) {
                    logd("Metadata still incomplete. Fetching from Kitsu fallback...")
                    val kitsuEps = fetchKitsuEpisodes(resolvedMalId)
                    if (kitsuEps.isNotEmpty()) {
                        merged = mergeWithFallback(merged, kitsuEps)
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

    private fun fetchAnikageEpisodes(anilistId: String): Map<Int, EpisodeMetadata> {
        val url = "https://anikage.cc/api/media/anime/$anilistId/episodes"
        val body = fetchString(url) ?: return emptyMap()
        val episodes = try {
            localJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AnikageEpisode.serializer()), body)
        } catch (e: Exception) {
            logd("Anikage parse failed — ${e.message}")
            return emptyMap()
        }
        val result = mutableMapOf<Int, EpisodeMetadata>()
        for (ep in episodes) {
            val num = ep.number ?: continue
            result[num] = EpisodeMetadata(
                title = ep.title?.takeIf { it.isNotBlank() },
                description = ep.description?.takeIf { it.isNotBlank() }?.let { stripHtml(it) },
                thumbnailUrl = ep.img?.takeIf { it.isNotBlank() },
                airdate = ep.airDate?.takeIf { it.isNotBlank() },
            )
        }
        logd("Anikage returned ${result.size} episodes")
        return result
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
            client.newCall(req).execute().use { resp ->
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
            client.newCall(req).execute().use { resp ->
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

    private fun fetchTmdbEpisodes(title: String, season: Int = 1): Map<Int, EpisodeMetadata> {
        val cleanTitle = sanitizeTitle(title)
        var result = performTmdbSearch(title, season)
        if (result.isEmpty() && cleanTitle != title && cleanTitle.isNotBlank()) {
            result = performTmdbSearch(cleanTitle, season)
        }
        return result
    }

    private fun performTmdbSearch(query: String, season: Int = 1): Map<Int, EpisodeMetadata> {
        return try {
            val searchUrl = "$TMDB_BASE_URL/search/multi?api_key=$tmdbApiKey&query=${URLEncoder.encode(query, "UTF-8")}&language=en-US"
            val response = fetchString(searchUrl) ?: return emptyMap()
            val results = JSONObject(response).getJSONArray("results")
            if (results.length() == 0) return emptyMap()

            var bestId: Int? = null
            var bestType: String? = null
            var highestScore = -1
            var maxVotes = -1
            var bestIsAnimation = false

            for (i in 0 until results.length()) {
                val res = results.getJSONObject(i)
                val mType = res.optString("media_type")
                if (mType != "movie" && mType != "tv") continue

                val resultTitle = res.optString("name").ifBlank { res.optString("title") }
                val resultOriginalTitle = res.optString("original_name").ifBlank { res.optString("original_title") }

                val score = maxOf(
                    calculateSimilarityScore(query, resultTitle),
                    calculateSimilarityScore(query, resultOriginalTitle)
                )

                if (score < 50) {
                    val resId = res.getInt("id")
                    val altUrl = "$TMDB_BASE_URL/$mType/$resId/alternative_titles?api_key=$tmdbApiKey"
                    var altScore = 0
                    try {
                        val altRes = fetchString(altUrl)
                        if (altRes != null) {
                            val altArray = JSONObject(altRes).getJSONArray("results")
                            for (j in 0 until altArray.length()) {
                                val alt = altArray.getJSONObject(j).optString("title")
                                altScore = maxOf(altScore, calculateSimilarityScore(query, alt))
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
        val detailUrl = "$TMDB_BASE_URL/$mediaType/$id?api_key=$tmdbApiKey&language=en-US"
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
                val seasonUrl = "$TMDB_BASE_URL/tv/$id/season/$season?api_key=$tmdbApiKey&language=en-US"
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
                            val s1Url = "$TMDB_BASE_URL/tv/$id/season/1?api_key=$tmdbApiKey&language=en-US"
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

    private fun sanitizeTitle(title: String): String = title
        .replace(Regex("(?i)\\(TV\\)|\\(Films?s?\\)|\\(OAVs?\\)|\\(ONAs?\\)|\\(Specials?\\)|VF|VOSTFR"), "")
        .replace(Regex("(?i)\\s*(?:Saison|Season|Part(?:ie)?)\\s*\\d+.*"), "")
        .replace(Regex("\\s+-\\s+.*$"), "")
        .replace(Regex("\\s+\\d+$"), "")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun calculateSimilarityScore(query: String, candidate: String): Int {
        fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        val s1 = normalize(query)
        val s2 = normalize(candidate)
        if (s1.isBlank() || s2.isBlank()) return 0
        if (s1 == s2) return 100

        val flat1 = s1.replace(" ", "")
        val flat2 = s2.replace(" ", "")
        if (flat1 == flat2) return 95

        if (s1.contains(s2) || s2.contains(s1)) {
            val longer = maxOf(s1.length, s2.length)
            val shorter = minOf(s1.length, s2.length)
            return Math.max(0, 85 - (longer - shorter) * 5)
        }

        val words1 = s1.split(" ").filter { it.length >= 2 }
        val words2 = s2.split(" ").filter { it.length >= 2 }
        if (words1.isEmpty() || words2.isEmpty()) return 0

        val matchingWords = words1.count { w1 -> words2.any { w2 -> w1 == w2 } }
        val wordScore = (matchingWords.toDouble() / maxOf(words1.size, words2.size) * 70).toInt()

        return wordScore
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
        private const val TAG = "EpisodeMetadataFetcher"
        private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TMDB_BASE_URL = "https://api.themoviedb.org/3"
    }
}

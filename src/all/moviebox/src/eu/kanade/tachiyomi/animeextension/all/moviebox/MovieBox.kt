package eu.kanade.tachiyomi.animeextension.all.moviebox

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.moviebox.extractors.M3u8Integration
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.EpisodeMetadataFetcher
import extensions.utils.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MovieBox : Source() {

    override val name = "MovieBox"
    override val baseUrl = "https://moviebox.ph"
    override val lang = "all"
    override val supportsLatest = false
    override val id: Long = 3508466391484419848L

    private val m3u8Integration by lazy { M3u8Integration(client) }

    private val detailsCache = object : LinkedHashMap<String, Pair<Long, JsonElement>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, JsonElement>>?): Boolean = size > 20
    }

    private fun getCachedDetails(id: String): JsonElement? = synchronized(detailsCache) {
        val entry = detailsCache[id] ?: return null
        if (System.currentTimeMillis() - entry.first < 60_000L) {
            entry.second
        } else {
            detailsCache.remove(id)
            null
        }
    }

    private fun putCachedDetails(id: String, element: JsonElement) {
        synchronized(detailsCache) {
            detailsCache[id] = Pair(System.currentTimeMillis(), element)
        }
    }

    private val metadataFetcher by lazy {
        EpisodeMetadataFetcher(client, json)
    }

    private val videoListCache = ConcurrentHashMap<String, Pair<Long, List<Video>>>()

    override suspend fun getVideoList(episode: SEpisode): List<Video> = withContext(Dispatchers.IO) {
        val episodeUrl = episode.url
        val parts = episodeUrl.split("|")
        if (parts.size < 4) return@withContext emptyList()

        val se = parts[0]
        val ep = parts[1]
        val idsString = parts[2]
        val mainDetailPath = parts[3]

        val cacheKey = "$se|$ep|$mainDetailPath"
        val cached = videoListCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.first < 15 * 60 * 1000L && cached.second.isNotEmpty()) {
            return@withContext cached.second
        }

        val subjectIds = idsString.split("~~").mapNotNull {
            val p = it.split(":", limit = 2)
            if (p.size == 2) {
                val sid = p[0]
                val langAndDp = p[1]
                val lang = langAndDp.substringBefore("^")
                val dp = if (langAndDp.contains("^")) langAndDp.substringAfter("^") else mainDetailPath
                Triple(sid, lang, dp)
            } else null
        }

        val excluded = preferences.getStringSet(PREF_AUDIO_EXCLUDE_KEY, emptySet()) ?: emptySet()
        val activeSubjects = subjectIds.filterNot { sub ->
            val langTag = sub.second.replace("dub", "").replace("dubbed", "").trim()
            excluded.contains(sub.second) || excluded.contains(langTag)
        }.ifEmpty { subjectIds }

        val videos = activeSubjects.map { (sid, lang, dp) ->
            async {
                fetchVideosForSubject(sid, lang, se, ep, dp)
            }
        }.awaitAll().flatten()

        val sorted = videos.sortVideos()
        val processed = m3u8Integration.processVideoList(sorted)
        if (processed.isNotEmpty()) {
            videoListCache[cacheKey] = Pair(System.currentTimeMillis(), processed)
            val nextEpNum = ep.toIntOrNull()?.plus(1)
            if (nextEpNum != null && se.toIntOrNull() != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    prefetchEpisodeVideos(activeSubjects, se, nextEpNum.toString(), mainDetailPath)
                }
            }
        }
        processed
    }

    private suspend fun prefetchEpisodeVideos(
        subjects: List<Triple<String, String, String>>,
        se: String,
        nextEp: String,
        mainDetailPath: String,
    ) {
        val nextCacheKey = "$se|$nextEp|$mainDetailPath"
        if (videoListCache.containsKey(nextCacheKey)) return
        try {
            val nextVideos = coroutineScope {
                subjects.map { (sid, lang, dp) ->
                    async { fetchVideosForSubject(sid, lang, se, nextEp, dp) }
                }.awaitAll().flatten()
            }
            val sorted = nextVideos.sortVideos()
            val processed = m3u8Integration.processVideoList(sorted)
            if (processed.isNotEmpty()) {
                videoListCache[nextCacheKey] = Pair(System.currentTimeMillis(), processed)
            }
        } catch (_: Exception) {
            // Background prefetch failure ignored
        }
    }

    private val apiHosts = listOf(
        "https://apig.inmoviebox.com",
        "https://api.inmoviebox.com",
        "https://api-in.inmoviebox.com",
        "https://api3.aoneroom.com",
        "https://api6.aoneroom.com",
        "https://api5.aoneroom.com",
        "https://api4.aoneroom.com",
        "https://api7.aoneroom.com",
        "https://api4sg.aoneroom.com",
        "https://netfilm.world",
        "https://h5-api.aoneroom.com",
    )

    private val signingKeyDefault = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="

    // Alternate request-signing key (observed in the official app and used as a
    // retry leg by CNC's CloudStream MovieBoxProvider): when the primary key
    // stops producing accepted signatures (server-side rotation), the same
    // canonical string is re-signed with this key and the request retried once.
    private val signingKeyAlt = "WHFuMm5uTzQxL0w5Mm8xaXVYaFNMSFRiWHZZNFo1Wlo2Mm04bVNMQQ=="

    // The stream/resource CDNs (sacdn/macdn/bcdn.hakunaymatata.com, *.aoneroom.com)
    // reject desktop browser UAs with HTTP 428 Forbidden; only the Android app's
    // Cronet UA is served. Video and API requests must both carry this UA
    // (verified live 2026-09).
    private val MOVIEBOX_APP_UA =
        "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; SM-S918B; Build/UD1A.230803.041; Cronet/145.0.7582.0)"

    private fun saveToken(token: String?) {
        if (!token.isNullOrBlank() && isTokenValid(token)) {
            bearerToken = token
            preferences.edit().putString("moviebox_bearer_token", token).apply()
        }
    }

    private fun getCachedToken(): String {
        synchronized(this) {
            if (isTokenValid(bearerToken)) return bearerToken!!
            val saved = preferences.getString("moviebox_bearer_token", null)
            if (isTokenValid(saved)) {
                bearerToken = saved
                return saved!!
            }

            val preferredHost = getPreferredHost()
            val candidateHosts = listOf(preferredHost) + apiHosts.filter { it != preferredHost }
            for (host in candidateHosts) {
                val url = "$host/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
                val request = GET(url, getApiHeaders(url, forceNoToken = true))
                try {
                    client.newCall(request).execute().use { response ->
                        val xUser = response.header("x-user")
                        if (!xUser.isNullOrBlank()) {
                            val tokenRegex = """"token"\s*:\s*"([^"]+)"""".toRegex()
                            val token = tokenRegex.find(xUser)?.groupValues?.get(1)
                            if (!token.isNullOrBlank() && isTokenValid(token)) {
                                bearerToken = token
                                preferences.edit().putString("moviebox_bearer_token", token).apply()
                                return token
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            return ""
        }
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "https://moviebox.ph/")
        .add("Origin", "https://moviebox.ph")
        .add("Accept", "application/json")

    private fun getApiHeaders(url: String, method: String = "GET", body: String? = null, token: String? = null, isDetails: Boolean = false, isPlayback: Boolean = false, forceNoToken: Boolean = false, useAltKey: Boolean = false): Headers {
        val timestamp = System.currentTimeMillis()
        val contentType = if (method == "POST") "application/json; charset=utf-8" else "application/json"

        return Headers.Builder()
            .add("user-agent", MOVIEBOX_APP_UA)
            .add("accept", "application/json")
            .add("content-type", contentType)
            .add("connection", "keep-alive")
            .add("x-client-token", generateXClientToken(timestamp))
            .add("x-tr-signature", generateXTrSignature(method, "application/json", contentType, url, body, timestamp = timestamp, useAltKey = useAltKey))
            .add("x-client-info", getClientInfo())
            .add("x-client-status", "0")
            .apply {
                if (isDetails) add("x-play-mode", "2")
                if (isPlayback) add("x-play-mode", "1")
                if (!forceNoToken) {
                    val activeToken = if (isTokenValid(token)) token!! else getCachedToken()
                    if (activeToken.isNotEmpty()) {
                        add("Authorization", "Bearer $activeToken")
                    }
                }
            }
            .build()
    }

    private fun getClientInfo(): String = JsonObject(
        mapOf(
            "package_name" to kotlinx.serialization.json.JsonPrimitive("com.community.mbox.in"),
            "version_name" to kotlinx.serialization.json.JsonPrimitive("4.0.03.0920.03"),
            "version_code" to kotlinx.serialization.json.JsonPrimitive(50020130),
            "os" to kotlinx.serialization.json.JsonPrimitive("android"),
            "os_version" to kotlinx.serialization.json.JsonPrimitive("14"),
            "device_id" to kotlinx.serialization.json.JsonPrimitive(deviceId),
            "install_store" to kotlinx.serialization.json.JsonPrimitive("official"),
            "gaid" to kotlinx.serialization.json.JsonPrimitive("1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d"),
            "brand" to kotlinx.serialization.json.JsonPrimitive("samsung"),
            "model" to kotlinx.serialization.json.JsonPrimitive("SM-S918B"),
            "system_language" to kotlinx.serialization.json.JsonPrimitive("en"),
            "net" to kotlinx.serialization.json.JsonPrimitive("NETWORK_WIFI"),
            "region" to kotlinx.serialization.json.JsonPrimitive("IN"),
            "timezone" to kotlinx.serialization.json.JsonPrimitive("Asia/Calcutta"),
            "sp_code" to kotlinx.serialization.json.JsonPrimitive(""),
        ),
    ).toString()

    private fun generateXClientToken(timestamp: Long): String {
        val tsStr = timestamp.toString()
        val hash = tsStr.reversed().md5()
        return "$tsStr,$hash"
    }

    private val deviceId by lazy {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    }

    private fun String.md5(): String = toByteArray().md5()

    private fun ByteArray.md5(): String = MessageDigest.getInstance("MD5").digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun buildCanonicalString(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestamp: Long,
    ): String {
        val parsed = Uri.parse(url)
        val path = parsed.path ?: ""

        val query = if (!parsed.queryParameterNames.isNullOrEmpty()) {
            parsed.queryParameterNames.sorted().joinToString("&") { key ->
                parsed.getQueryParameters(key).joinToString("&") { value ->
                    "$key=$value"
                }
            }
        } else {
            ""
        }

        val canonicalUrl = if (query.isNotEmpty()) "$path?$query" else path

        val bodyBytes = body?.toByteArray(Charsets.UTF_8)
        val bodyHash = if (bodyBytes != null) {
            val trimmed = if (bodyBytes.size > 102400) bodyBytes.copyOfRange(0, 102400) else bodyBytes
            md5ByteArray(trimmed)
        } else {
            ""
        }

        val bodyLength = bodyBytes?.size?.toString() ?: ""
        return "${method.uppercase()}\n${accept ?: ""}\n${contentType ?: ""}\n$bodyLength\n$timestamp\n$bodyHash\n$canonicalUrl"
    }

    private fun md5ByteArray(input: ByteArray): String = MessageDigest.getInstance("MD5").digest(input)
        .joinToString("") { "%02x".format(it) }

    private fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String? = null,
        timestamp: Long,
        useAltKey: Boolean = false,
    ): String {
        val canonical = buildCanonicalString(method, accept, contentType, url, body, timestamp)

        val secretStr = String(Base64.decode(if (useAltKey) signingKeyAlt else signingKeyDefault, Base64.DEFAULT))
        val secretBytes = Base64.decode(secretStr, Base64.DEFAULT)

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(secretBytes, "HmacMD5"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
        val signatureB64 = Base64.encodeToString(signature, Base64.NO_WRAP)

        return "$timestamp|2|$signatureB64"
    }

    private fun isMobileApi(host: String): Boolean = host.contains("inmoviebox") || host.contains("aoneroom")

    private fun getPreferredHost(): String = preferences.getString(PREF_HOST_KEY, PREF_HOST_DEFAULT) ?: PREF_HOST_DEFAULT

    private fun extractRealStreamUrl(rawUrl: String, signCookie: String?): String {
        if (!signCookie.isNullOrBlank()) {
            if (signCookie.contains("urlprefix=")) {
                try {
                    val b64 = signCookie.substringAfter("urlprefix=").substringBefore(";").substringBefore(":sign=")
                        .replace('-', '+').replace('_', '/')
                    val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                    val decoded = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8).trim()
                    if (decoded.startsWith("http")) {
                        val base = if (decoded.endsWith("/")) decoded else "$decoded/"
                        return "${base}index.mpd"
                    }
                } catch (_: Exception) {
                    // ignore and fallback
                }
            }
            if (signCookie.contains("CloudFront-Policy=")) {
                try {
                    val b64 = signCookie.substringAfter("CloudFront-Policy=").substringBefore(";")
                        .replace('-', '+').replace('_', '/')
                    val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
                    val jsonStr = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                    val resource = json.parseToJsonElement(jsonStr).obj?.get("Statement")?.arr?.firstOrNull()?.obj?.get("Resource")?.str
                    if (resource != null && resource.startsWith("http")) {
                        val base = resource.substringBeforeLast("*")
                        val cleanBase = if (base.endsWith("/")) base else "$base/"
                        return "${cleanBase}index.mpd"
                    }
                } catch (_: Exception) {
                    // ignore and fallback
                }
            }
        }
        return rawUrl
    }

    private fun safeGetJsonWithHeaders(urlPath: String, isPost: Boolean = false, bodyData: String? = null, token: String? = null, isDetails: Boolean = false, isPlayback: Boolean = false): Pair<JsonElement, Headers>? {
        val preferredHost = getPreferredHost()
        val candidateHosts = listOf(preferredHost) + apiHosts.filter { it != preferredHost }
        // Throttled-but-valid response kept as a last resort: if every host
        // reports cdnThrottleLevel=1 we still hand back something playable.
        var throttledFallback: Pair<JsonElement, Headers>? = null
        for (host in candidateHosts) {
            val adaptivePath = if (isMobileApi(host)) {
                urlPath
                    .replace("/wefeed-h5api-bff/detail", "/wefeed-mobile-bff/subject-api/get")
                    .replace("/wefeed-h5api-bff/subject/play", "/wefeed-mobile-bff/subject-api/play-info")
                    .replace("/wefeed-h5api-bff/subject/search", "/wefeed-mobile-bff/subject-api/search/v2")
                    .replace("/wefeed-h5api-bff/subject/filter", "/wefeed-mobile-bff/subject-api/list")
                    .replace("/wefeed-h5api-bff/ranking-list/content", "/wefeed-mobile-bff/tab/ranking-list")
            } else {
                urlPath
                    .replace("/wefeed-mobile-bff/subject-api/get", "/wefeed-h5api-bff/detail")
                    .replace("/wefeed-mobile-bff/subject-api/season-info", "/wefeed-h5api-bff/detail")
                    .replace("/wefeed-mobile-bff/subject-api/play-info", "/wefeed-h5api-bff/subject/play")
                    .replace("/wefeed-mobile-bff/subject-api/search/v2", "/wefeed-h5api-bff/subject/search")
                    .replace("/wefeed-mobile-bff/subject-api/list", "/wefeed-h5api-bff/subject/filter")
                    .replace("/wefeed-mobile-bff/tab/ranking-list", "/wefeed-h5api-bff/ranking-list/content")
            }

            val url = host + adaptivePath

            // Signature rejection / key rotation: retry the same host once with
            // the alternate signing key before moving to the next host.
            for (useAltKey in listOf(false, true)) {
                val request = if (isPost) {
                    val body = bodyData.orEmpty().toRequestBody("application/json; charset=utf-8".toMediaType())
                    POST(url, getApiHeaders(url, "POST", bodyData, token = token, isDetails = isDetails, isPlayback = isPlayback, useAltKey = useAltKey), body)
                } else {
                    GET(url, getApiHeaders(url, token = token, isDetails = isDetails, isPlayback = isPlayback, useAltKey = useAltKey))
                }
                try {
                    val response = client.newCall(request).execute()
                    val body = response.body.string().trim()
                    if (body.isEmpty() || body.contains("<html", ignoreCase = true) || !body.startsWith("{")) continue
                    val jsonRes = json.parseToJsonElement(body)
                    if (jsonRes.obj?.get("code")?.jsonPrimitive?.intOrNull != 0) continue
                    if (isPlayback && urlPath.contains("play-info")) {
                        val dataObj = jsonRes.obj?.get("data")?.obj
                        val streams = dataObj?.get("streams")?.arr
                        if (streams.isNullOrEmpty()) continue
                        // Hosts report cdnThrottleLevel=1 when they front the
                        // throttled sbcdn Edge-Cache CDN (~0.6 MB/s aggregate —
                        // constant buffering) and 0 for the unthrottled
                        // sacdn/CloudFront CDN (~6.7 MB/s). Prefer the fast
                        // CDN; a throttled response is only a last resort.
                        if (dataObj?.get("cdnThrottleLevel")?.jsonPrimitive?.intOrNull == 1) {
                            if (throttledFallback == null) throttledFallback = Pair(jsonRes, response.headers)
                            continue
                        }
                        // sacdn.hakunaymatata.com is a WORKING CDN: its URL is
                        // derived from the stream's signCookie (CloudFront-Policy
                        // resource) and plays fine with the full cookie + app UA
                        // (verified live 2026-09). Only genuinely dead hosts
                        // disqualify a response.
                        val onlyBrokenCdn = streams.all {
                            val u = it.obj?.get("url")?.str.orEmpty()
                            val cookie = it.obj?.get("signCookie")?.str.orEmpty()
                            val resolved = extractRealStreamUrl(u, cookie)
                            resolved.contains("b164fbfb43477929") ||
                                resolved.contains("macdn.aoneroom.com/other/")
                        }
                        if (onlyBrokenCdn) continue
                    }
                    return Pair(jsonRes, response.headers)
                } catch (e: Exception) {
                    continue
                }
            }
        }
        return throttledFallback
    }

    // Popular
    // Uses the discovery/filter endpoint (paged, ~1M catalog) instead of the
    // ranking list, whose fixed ~52 entries made the catalogue stop scrolling
    // after three pages. Same request shape as the default browse filters.
    override fun popularAnimeRequest(page: Int): Request {
        val host = getPreferredHost()
        val path = if (isMobileApi(host)) "/wefeed-mobile-bff/subject-api/list" else "/wefeed-h5api-bff/subject/filter"
        val url = "$host$path"
        val bodyData = JsonObject(
            mutableMapOf(
                "page" to kotlinx.serialization.json.JsonPrimitive(page),
                "perPage" to kotlinx.serialization.json.JsonPrimitive(20),
                "keyword" to kotlinx.serialization.json.JsonPrimitive(""),
                "sort" to kotlinx.serialization.json.JsonPrimitive("ForYou"),
                "channelId" to kotlinx.serialization.json.JsonPrimitive("0"),
                "classify" to kotlinx.serialization.json.JsonPrimitive("All"),
                "genre" to kotlinx.serialization.json.JsonPrimitive("All"),
                "year" to kotlinx.serialization.json.JsonPrimitive("All"),
                "country" to kotlinx.serialization.json.JsonPrimitive("All"),
            ),
        ).toString()
        val body = bodyData.toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(url, getApiHeaders(url, "POST", bodyData), body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = parseDataObject(response) ?: return AnimesPage(emptyList(), false)
        return parseSubjectListPage(data)
    }

    // Search & Filters
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val host = getPreferredHost()
        val isMobile = isMobileApi(host)

        // 1. Search with keyword
        if (query.isNotBlank()) {
            val path = if (isMobile) "/wefeed-mobile-bff/subject-api/search/v2" else "/wefeed-h5api-bff/subject/search"
            val url = "$host$path"
            val bodyData = """{"page":$page,"perPage":20,"keyword":"${escapeJson(query)}"}"""
            val body = bodyData.toRequestBody("application/json; charset=utf-8".toMediaType())
            return POST(url, getApiHeaders(url, "POST", bodyData), body)
        }

        // 2. Filtered Browse or Ranking
        val rankingFilter = filters.find { it is RankingFilter } as? RankingFilter
        if (rankingFilter != null && rankingFilter.state > 0) {
            val rankingId = rankingFilter.toId()
            val path = if (isMobile) "/wefeed-mobile-bff/tab/ranking-list" else "/wefeed-h5api-bff/ranking-list/content"
            val url = "$host$path?tabId=0&categoryType=$rankingId&page=$page&perPage=20"
            return GET(url, getApiHeaders(url))
        }

        // 3. Discovery Filters
        val typeFilter = filters.find { it is TypeFilter } as? TypeFilter
        val languageFilter = filters.find { it is LanguageFilter } as? LanguageFilter
        val genreFilter = filters.find { it is GenreFilter } as? GenreFilter
        val yearFilter = filters.find { it is YearFilter } as? YearFilter
        val countryFilter = filters.find { it is CountryFilter } as? CountryFilter
        val sortFilter = filters.find { it is SortFilter } as? SortFilter

        val typeId = typeFilter?.toId() ?: "0"
        val bodyMap = mutableMapOf<String, JsonElement>(
            "page" to kotlinx.serialization.json.JsonPrimitive(page),
            "perPage" to kotlinx.serialization.json.JsonPrimitive(20),
            "keyword" to kotlinx.serialization.json.JsonPrimitive(""),
            "sort" to kotlinx.serialization.json.JsonPrimitive(sortFilter?.toId() ?: "ForYou"),
            "channelId" to kotlinx.serialization.json.JsonPrimitive(if (typeId == "ANIMATION") "0" else typeId),
            "classify" to kotlinx.serialization.json.JsonPrimitive(languageFilter?.toId() ?: "All"),
            "genre" to kotlinx.serialization.json.JsonPrimitive(if (typeId == "ANIMATION") "Animation" else (genreFilter?.toId() ?: "All")),
            "year" to kotlinx.serialization.json.JsonPrimitive(yearFilter?.toId() ?: "All"),
            "country" to kotlinx.serialization.json.JsonPrimitive(countryFilter?.toId() ?: "All"),
        )

        val path = if (isMobile) "/wefeed-mobile-bff/subject-api/list" else "/wefeed-h5api-bff/subject/filter"
        val url = "$host$path"
        val bodyData = JsonObject(bodyMap).toString()
        val body = bodyData.toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST(url, getApiHeaders(url, "POST", bodyData), body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = parseDataObject(response) ?: return AnimesPage(emptyList(), false)
        return parseSubjectListPage(data)
    }

    /**
     * Extracts the response's "data" object, transparently retrying through the
     * other API hosts when the preferred one answered with something that is
     * not JSON (e.g. a Cloudflare interstitial or an error page). Returns null
     * when nothing usable came back so callers can return an empty page.
     */
    private fun parseDataObject(response: Response): JsonObject? {
        val body = response.body.string().trim()
        if (body.startsWith("{")) {
            return runCatching { json.parseToJsonElement(body) }
                .getOrNull()
                ?.obj
                ?.get("data")
                ?.obj
        }

        val url = response.request.url.toString()
        val path = "/" + url.substringAfter(".com/").substringAfter(".world/")
        return if (response.request.body != null) {
            val buffer = Buffer()
            response.request.body!!.writeTo(buffer)
            val bodyData = buffer.readUtf8()
            safeGetJsonWithHeaders(path, isPost = true, bodyData = bodyData)?.first?.obj?.get("data")?.obj
        } else {
            safeGetJsonWithHeaders(path)?.first?.obj?.get("data")?.obj
        }
    }

    // Details
    override fun animeDetailsRequest(anime: SAnime): Request {
        val id = anime.url.substringAfterLast("/").substringBefore("|")
        val host = getPreferredHost()
        val path = if (isMobileApi(host)) "/wefeed-mobile-bff/subject-api/get" else "/wefeed-h5api-bff/detail"
        val param = if (id.all { it.isDigit() }) "subjectId" else "detailPath"
        val url = "$host$path?$param=$id"
        return GET(url, getApiHeaders(url, isDetails = true))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val xUser = response.header("x-user")
        var token = xUser?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
        saveToken(token)

        val id = response.request.url.queryParameter("subjectId") ?: response.request.url.toString().substringAfterLast("/")
        val cached = getCachedDetails(id)
        val body = if (cached == null) response.body.string() else ""
        val jsonRes = cached ?: if (!body.trim().startsWith("{")) {
            val result = safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?subjectId=$id", isDetails = true)
            token = result?.second?.get("x-user")?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
            saveToken(token)
            result?.first
        } else {
            json.parseToJsonElement(body)
        }

        if (jsonRes != null) {
            putCachedDetails(id, jsonRes)
        }

        val data = jsonRes?.obj?.get("data")?.obj ?: throw Exception("Details not found")
        val subject = data["subject"]?.obj ?: data
        val sid = subject["subjectId"]?.str ?: data["subjectId"]?.str ?: id
        val dp = subject["detailPath"]?.str
            ?: subject["detailUrl"]?.str?.substringAfterLast("/")?.substringBefore("?")
            ?: data["detailPath"]?.str
            ?: data["detailUrl"]?.str?.substringAfterLast("/")?.substringBefore("?")
            ?: ""

        return SAnime.create().apply {
            title = subject["title"]?.str ?: ""
            description = subject["description"]?.str
            genre = subject["genre"]?.str
            author = subject["countryName"]?.str
            url = if (dp.isNotBlank()) "/movies/$sid|$dp" else "/movies/$sid"
            status = SAnime.UNKNOWN
            initialized = true
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = super.getEpisodeList(anime)
        val showThumbnails = preferences.getBoolean(PREF_THUMBNAILS_KEY, PREF_THUMBNAILS_DEFAULT)
        val showTitle = anime.title

        if (showTitle.isBlank() || episodes.isEmpty()) return episodes

        // 1. Single movie enrichment
        if (episodes.size == 1 && episodes[0].name.startsWith("Play Movie")) {
            val movieMeta = try {
                metadataFetcher.fetchMovieMetadata(showTitle)
            } catch (_: Exception) {
                null
            }
            if (movieMeta != null) {
                val ep = episodes[0]
                if (!movieMeta.description.isNullOrBlank()) {
                    ep.summary = movieMeta.description
                }
                if (showThumbnails && !movieMeta.thumbnailUrl.isNullOrBlank()) {
                    ep.preview_url = movieMeta.thumbnailUrl
                }
            }
            return episodes
        }

        // 2. TV Series enrichment
        val seasons = episodes.mapNotNull {
            val se = it.url.substringBefore("|").toIntOrNull()
            if (se != null && se > 0) se else null
        }.distinct()

        val metaMap = mutableMapOf<Pair<Int, Int>, EpisodeMetadataFetcher.EpisodeMetadata>()
        for (seNum in seasons) {
            try {
                val sMeta = metadataFetcher.fetchMedia(showTitle, season = seNum, isTv = true)
                sMeta.forEach { (epNum, meta) ->
                    metaMap[Pair(seNum, epNum)] = meta
                }
            } catch (_: Exception) {}
        }

        episodes.forEach { ep ->
            val parts = ep.url.split("|")
            val seNum = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val epNum = parts.getOrNull(1)?.toIntOrNull() ?: ep.episode_number.toInt()
            val metaEp = metaMap[Pair(seNum, epNum)] ?: return@forEach

            if (!metaEp.title.isNullOrBlank()) {
                ep.name = "Season $seNum - Episode $epNum - ${metaEp.title}"
            }
            if (!metaEp.description.isNullOrBlank()) {
                ep.summary = metaEp.description
            }
            if (showThumbnails && !metaEp.thumbnailUrl.isNullOrBlank()) {
                ep.preview_url = metaEp.thumbnailUrl
            }
            val date = parseDate(metaEp.airdate)
            if (date > 0L) {
                ep.date_upload = date
            }
        }

        return episodes
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val xUser = response.header("x-user")
        val headerToken = xUser?.let { runCatching { json.parseToJsonElement(it).obj?.get("token")?.str }.getOrNull() }
        saveToken(headerToken)
        val urlParts = response.request.url.toString().split("|")
        val token = headerToken ?: (if (urlParts.size > 1) urlParts[1] else null)

        val id = response.request.url.queryParameter("subjectId") ?: response.request.url.toString().substringAfterLast("/")
        val cached = getCachedDetails(id)
        val body = if (cached == null) response.body.string() else ""
        val jsonRes = cached ?: if (!body.trim().startsWith("{")) {
            safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?subjectId=$id", token = token, isDetails = true)?.first
        } else {
            json.parseToJsonElement(body).also { putCachedDetails(id, it) }
        }

        val data = jsonRes?.obj?.get("data")?.obj ?: return emptyList()
        val subjectObj = data["subject"]?.obj ?: data
        val mainSubjectId = subjectObj["subjectId"]?.str
            ?: data["subjectId"]?.str ?: return emptyList()
        val fallbackDp = if (urlParts.size > 1 && urlParts[1].isNotBlank() && !urlParts[1].all { it.isDigit() }) urlParts[1] else ""
        val detailPath = subjectObj["detailPath"]?.str
            ?: subjectObj["detailUrl"]?.str?.substringAfterLast("/")?.substringBefore("?")
            ?: data["detailPath"]?.str
            ?: data["detailUrl"]?.str?.substringAfterLast("/")?.substringBefore("?")
            ?: fallbackDp

        val allIds = mutableListOf<Pair<String, String>>()
        allIds.add(Pair(mainSubjectId, subjectObj["lanName"]?.str ?: "Original"))
        val dubsArray = subjectObj["dubs"]?.arr ?: data["dubs"]?.arr
        dubsArray?.forEach {
            val sid = it.obj?.get("subjectId")?.str
            val lang = it.obj?.get("lanName")?.str ?: "Unknown"
            val dubDp = it.obj?.get("detailPath")?.str
                ?: it.obj?.get("detailUrl")?.str?.substringAfterLast("/")?.substringBefore("?")
                ?: ""
            val storedLang = if (dubDp.isNotBlank()) "$lang^$dubDp" else lang
            if (sid != null && allIds.none { p -> p.first == sid }) allIds.add(Pair(sid, storedLang))
        }

        val episodes = mutableListOf<SEpisode>()
        val seasonsMap = mutableMapOf<Int, MutableSet<Int>>()

        val seasonsRes = safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/season-info?subjectId=$mainSubjectId", token = token, isDetails = true)?.first
        val seasonsData = seasonsRes?.obj?.get("data")?.obj ?: seasonsRes?.obj ?: data
        val resource = seasonsData["resource"]?.obj ?: seasonsData
        var seasons = resource["seasons"]?.arr ?: seasonsData["seasons"]?.arr

        if (seasons.isNullOrEmpty() && allIds.size > 1) {
            for ((sid, _) in allIds.drop(1)) {
                val fallbackRes = safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/season-info?subjectId=$sid", token = token, isDetails = true)?.first
                val fbData = fallbackRes?.obj?.get("data")?.obj ?: fallbackRes?.obj
                val fbResource = fbData?.get("resource")?.obj ?: fbData
                val fbSeasons = fbResource?.get("seasons")?.arr ?: fbData?.get("seasons")?.arr
                if (!fbSeasons.isNullOrEmpty()) {
                    seasons = fbSeasons
                    break
                }
            }
        }

        seasons?.forEach { seasonEl ->
            val season = seasonEl.obj ?: return@forEach
            val seNum = season["se"]?.jsonPrimitive?.intOrNull ?: 1
            val allEpRaw = season["allEp"]?.str.orEmpty()
            val maxEp = if (allEpRaw.isNotBlank()) allEpRaw.split(",").filter { it.isNotBlank() }.size else season["maxEp"]?.jsonPrimitive?.intOrNull ?: 1
            val epSet = seasonsMap.getOrPut(seNum) { mutableSetOf() }
            if (maxEp > 0) {
                for (i in 1..maxEp) epSet.add(i)
            }
        }

        val defaultSummary = data["subject"]?.obj?.get("description")?.str
            ?: data["description"]?.str
            ?: data["subject"]?.obj?.get("brief")?.str
            ?: data["brief"]?.str

        val defaultThumbnail = data["subject"]?.obj?.get("cover")?.obj?.get("url")?.str
            ?: data["cover"]?.obj?.get("url")?.str
            ?: data["stills"]?.arr?.firstOrNull()?.obj?.get("url")?.str
            ?: data["subject"]?.obj?.get("stills")?.arr?.firstOrNull()?.obj?.get("url")?.str

        val showThumbnails = preferences.getBoolean(PREF_THUMBNAILS_KEY, PREF_THUMBNAILS_DEFAULT)
        val idsString = allIds.joinToString("~~") { "${it.first}:${it.second}" }
        seasonsMap.forEach { (seNum, epSet) ->
            epSet.sorted().forEach { epNum ->
                episodes.add(
                    SEpisode.create().apply {
                        name = "Season $seNum - Episode $epNum"
                        episode_number = epNum.toFloat()
                        url = "$seNum|$epNum|$idsString|$detailPath"
                        date_upload = System.currentTimeMillis()
                    },
                )
            }
        }

        return if (episodes.isNotEmpty()) {
            episodes.reversed()
        } else {
            listOf(
                SEpisode.create().apply {
                    name = "Play Movie"
                    episode_number = 1f
                    url = "0|0|$idsString|$detailPath"
                    date_upload = System.currentTimeMillis()
                    if (!defaultSummary.isNullOrBlank()) {
                        summary = defaultSummary
                    }
                    if (showThumbnails && !defaultThumbnail.isNullOrBlank()) {
                        preview_url = defaultThumbnail
                    }
                },
            )
        }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(dateStr)?.time ?: 0L
            } catch (_: Exception) {
                try {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.parse(dateStr)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException("Not used")

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException("Not used")

    private suspend fun fetchVideosForSubject(sid: String, lang: String, se: String, ep: String, detailPath: String = ""): List<Video> {
        val enableDash = preferences.getBoolean(PREF_ENABLE_DASH_KEY, PREF_ENABLE_DASH_DEFAULT)
        val videos = mutableListOf<Video>()
        var activeDetailPath = detailPath.trim()

        if (activeDetailPath.isBlank()) {
            val detailsJson = safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?subjectId=$sid", isDetails = true)?.first
            val sObj = detailsJson?.obj?.get("data")?.obj?.let { it["subject"]?.obj ?: it }
            activeDetailPath = sObj?.get("detailPath")?.str
                ?: sObj?.get("detailUrl")?.str?.substringAfterLast("/")?.substringBefore("?")
                ?: ""
        }

        // 1. Try web/H5 play endpoint (matches CNCVerse MovieBoxProviderIN)
        if (activeDetailPath.isNotBlank()) {
            val subDomain = "https://movie-box.co"
            val mbUrl = "$subDomain/wefeed-h5api-bff/subject/play?subjectId=$sid&se=$se&ep=$ep&detailPath=$activeDetailPath&streamSignType=1&supportCodecs%5Bhevc%5D=1&supportCodecs%5Bh264%5D=1"
            val token = getCachedToken()
            val mbHeaders = Headers.Builder()
                .add("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36")
                .add("Origin", subDomain)
                .add("Referer", "$subDomain/movies/$activeDetailPath?id=$sid&type=/movie/detail&detailSe=&detailEp=&lang=en")
                .add("Accept", "application/json")
                .add("x-client-info", """{"timezone":"Asia/Calcutta"}""")
                .add("x-request-lang", "en")
                .add("x-vip-restrict", "0")
                .add("x-no-high-risk-restrict", "0")
                .add("x-source", "")
                .apply {
                    if (token.isNotBlank()) add("Authorization", "Bearer $token")
                }
                .build()

            try {
                val req = GET(mbUrl, mbHeaders)
                client.newCall(req).execute().use { resp ->
                    val respBody = resp.body.string()
                    if (resp.isSuccessful && respBody.trim().startsWith("{")) {
                        val root = json.parseToJsonElement(respBody).obj
                        val dataObj = root?.get("data")?.obj
                        val streams = dataObj?.get("streams")?.arr
                        val dash = dataObj?.get("dash")?.arr

                        // MP4 progressive streams
                        streams?.forEach { streamEl ->
                            val s = streamEl.obj ?: return@forEach
                            val streamUrl = s["url"]?.str ?: return@forEach
                            if (streamUrl.isBlank() || streamUrl.startsWith("magnet:")) return@forEach
                            val res = s["resolutions"]?.str ?: "Auto"
                            val streamId = s["id"]?.str ?: ""
                            val headers = Headers.Builder()
                                .add("Referer", "https://movie-box.co/")
                                .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36")
                                .build()

                            val subTracks = mutableListOf<Track>()
                            if (streamId.isNotBlank()) {
                                subTracks.addAll(fetchExternalCaptions(sid, streamId, se, ep, s["resourceId"]?.str))
                            }
                            val distinctSubs = subTracks.distinctBy { it.url }

                            val langTag = lang.replace("dub", "").replace("dubbed", "").trim()
                            val langSuffix = if (langTag.isNotBlank() && !langTag.equals("original", ignoreCase = true)) " - $langTag" else ""
                            res.split(",").forEach { r ->
                                val cleanRes = r.trim()
                                val resInt = cleanRes.toIntOrNull()
                                videos.add(
                                    Video(
                                        videoUrl = streamUrl,
                                        videoTitle = "$cleanRes (MP4$langSuffix)",
                                        headers = headers,
                                        resolution = resInt,
                                        subtitleTracks = distinctSubs,
                                    ),
                                )
                            }
                        }

                        // DASH streams
                        if (enableDash) {
                            dash?.forEach { dashEl ->
                                val d = dashEl.obj ?: return@forEach
                                val dashUrl = d["url"]?.str ?: return@forEach
                                if (dashUrl.isBlank()) return@forEach
                                val res = d["resolutions"]?.str ?: "Auto"
                                val signCookie = d["signCookie"]?.str?.trim()?.trimEnd(';')
                                val signHeaderKey = d["signHeaderKey"]?.str?.ifBlank { "X-MB-Token" } ?: "X-MB-Token"
                                val streamId = d["id"]?.str ?: ""

                                val headers = Headers.Builder()
                                    .add("Origin", subDomain)
                                    .add("Referer", "$subDomain/movies/$activeDetailPath?id=$sid&type=/movie/detail&detailSe=&detailEp=&lang=en")
                                    .add("User-Agent", "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36")
                                    .apply {
                                        if (!signCookie.isNullOrBlank()) {
                                            add(signHeaderKey, signCookie)
                                            add("Cookie", signCookie)
                                        }
                                    }
                                    .build()

                                val subTracks = mutableListOf<Track>()
                                if (streamId.isNotBlank()) {
                                    subTracks.addAll(fetchExternalCaptions(sid, streamId, se, ep, d["resourceId"]?.str))
                                }
                                val distinctSubs = subTracks.distinctBy { it.url }

                                val langTag = lang.replace("dub", "").replace("dubbed", "").trim()
                                val langSuffix = if (langTag.isNotBlank() && !langTag.equals("original", ignoreCase = true)) " - $langTag" else ""
                                res.split(",").forEach { r ->
                                    val cleanRes = r.trim()
                                    val resInt = cleanRes.toIntOrNull()
                                    videos.add(
                                        Video(
                                            videoUrl = dashUrl,
                                            videoTitle = "$cleanRes (DASH$langSuffix)",
                                            headers = headers,
                                            resolution = resInt,
                                            subtitleTracks = distinctSubs,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore and fall back to play-info below
            }
        }

        if (videos.isNotEmpty()) return videos

        // 2. Fallback to mobile play-info endpoint
        val playUrl = "/wefeed-mobile-bff/subject-api/play-info?subjectId=$sid&se=$se&ep=$ep"
        val jsonRes = safeGetJsonWithHeaders(playUrl, isPlayback = true)?.first
        val dataObj = jsonRes?.obj?.get("data")?.obj
        val internalSubs = parseCaptionTracks(dataObj)
        dataObj?.get("streams")?.arr?.forEach { stream ->
            val obj = stream.obj ?: return@forEach
            val rawUrl = obj["url"]?.str ?: return@forEach
            val signCookie = obj["signCookie"]?.str
            val cleanCookie = signCookie?.trim()?.trimEnd(';')
            val resolvedUrl = extractRealStreamUrl(rawUrl, cleanCookie)
            if (resolvedUrl.startsWith("magnet:") ||
                resolvedUrl.contains("b164fbfb43477929") ||
                resolvedUrl.contains("macdn.aoneroom.com/other/")
            ) {
                return@forEach
            }

            val res = obj["resolutions"]?.str ?: "Auto"
            val streamId = obj["id"]?.str ?: ""
            val headers = Headers.Builder()
                .add("User-Agent", MOVIEBOX_APP_UA)
                .add("Referer", "https://moviebox.ph/")
                .apply { if (!cleanCookie.isNullOrBlank()) add("Cookie", cleanCookie) }
                .build()

            val subtitleTracks = mutableListOf<Track>()
            subtitleTracks.addAll(internalSubs)
            subtitleTracks.addAll(parseCaptionTracks(obj))
            if (streamId.isNotBlank()) {
                val dataResourceId = dataObj?.get("resourceId")?.str
                subtitleTracks.addAll(fetchExternalCaptions(sid, streamId, se, ep, obj["resourceId"]?.str ?: dataResourceId))
            }
            val distinctSubs = subtitleTracks.distinctBy { it.url }

            val isDash = resolvedUrl.endsWith(".mpd") || resolvedUrl.contains("/dash/")
            if (isDash && !enableDash) return@forEach
            val formatTag = if (isDash) "DASH" else "MP4"
            val langTag = lang.replace("dub", "").replace("dubbed", "").trim()
            val langSuffix = if (langTag.isNotBlank() && !langTag.equals("original", ignoreCase = true)) " - $langTag" else ""
            res.split(",").forEach { r ->
                val cleanRes = r.trim()
                val resInt = cleanRes.toIntOrNull()
                videos.add(
                    Video(
                        videoUrl = resolvedUrl,
                        videoTitle = "$cleanRes ($formatTag$langSuffix)",
                        headers = headers,
                        resolution = resInt,
                        subtitleTracks = distinctSubs,
                    ),
                )
            }
        }

        // 3. Fallback to resourceDetectors
        if (videos.isEmpty()) {
            videos.addAll(fetchDetectorFallbackVideos(sid, lang))
        }
        return videos
    }

    private fun fetchDetectorFallbackVideos(sid: String, lang: String): List<Video> {
        val enableDash = preferences.getBoolean(PREF_ENABLE_DASH_KEY, PREF_ENABLE_DASH_DEFAULT)
        val jsonRes = safeGetJsonWithHeaders("/wefeed-mobile-bff/subject-api/get?subjectId=$sid", isDetails = true)
            ?: return emptyList()
        val dataObj = jsonRes.first.obj?.get("data")?.obj
        val detectors = dataObj?.get("resourceDetectors")?.arr ?: return emptyList()

        val videos = mutableListOf<Video>()
        val langTag = lang.replace("dub", "").replace("dubbed", "").trim()
        val langSuffix = if (langTag.isNotBlank() && !langTag.equals("original", ignoreCase = true)) " - $langTag" else ""

        val fallbackHeaders = Headers.Builder()
            .add("User-Agent", MOVIEBOX_APP_UA)
            .add("Referer", "https://moviebox.ph/")
            .build()

        detectors.forEach { detectorEl ->
            val detector = detectorEl.obj ?: return@forEach
            val detectorSubs = parseCaptionTracks(detector)
            detector["resolutionList"]?.arr?.forEach { videoEl ->
                val obj = videoEl.obj ?: return@forEach
                val link = obj["resourceLink"]?.str ?: return@forEach
                if (link.startsWith("magnet:")) return@forEach
                if (link.contains("b164fbfb43477929") || link.contains("/other/2026/09/04/")) return@forEach

                val isDash = link.contains("/dash/") || link.endsWith(".mpd")
                if (isDash && !enableDash) return@forEach
                val resLabel = obj["resolution"]?.str ?: "Auto"
                val resInt = resLabel.filter { it.isDigit() }.toIntOrNull()
                val formatTag = if (isDash) "DASH" else "MP4"
                val subtitleTracks = (detectorSubs + parseCaptionTracks(obj)).distinctBy { it.url }
                videos.add(
                    Video(
                        videoUrl = link,
                        videoTitle = "$resLabel ($formatTag$langSuffix)",
                        headers = fallbackHeaders,
                        resolution = resInt,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }
        }
        return videos
    }

    private fun parseCaptionTracks(dataObj: JsonObject?): List<Track> {
        if (dataObj == null) return emptyList()
        val keys = listOf("subTitleList", "subtitleList", "subtitles", "captions", "extCaptions", "extcaptions", "captionList")
        for (key in keys) {
            val arr = dataObj[key]?.arr ?: continue
            if (arr.isEmpty()) continue
            val tracks = arr.mapNotNull { cap ->
                val capObj = cap.obj ?: return@mapNotNull null
                val capUrl = capObj["url"]?.str?.ifBlank { capObj["file"]?.str }
                    ?: capObj["src"]?.str ?: return@mapNotNull null
                if (capUrl.isBlank()) return@mapNotNull null
                val label = capObj["lanName"]?.str
                    ?: capObj["language"]?.str
                    ?: capObj["lang"]?.str
                    ?: capObj["name"]?.str
                    ?: capObj["label"]?.str
                    ?: capObj["title"]?.str
                    ?: "Unknown"
                Track(url = capUrl, lang = label)
            }
            if (tracks.isNotEmpty()) return tracks
        }
        return emptyList()
    }

    private suspend fun fetchExternalCaptions(sid: String, streamId: String, se: String, ep: String, resourceId: String?): List<Track> = coroutineScope {
        val paths = mutableListOf(
            "/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=$sid&streamId=$streamId",
        )
        if (!resourceId.isNullOrBlank()) {
            paths.add("/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$sid&resourceId=$resourceId&episode=$ep")
            paths.add("/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$sid&resourceId=$resourceId&se=$se&ep=$ep")
        }
        paths.add("/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=$sid&streamId=$streamId&episode=$ep")
        val results = paths.map { path ->
            async(Dispatchers.IO) {
                runCatching {
                    safeGetJsonWithHeaders(path, isPlayback = true)?.first?.obj?.get("data")?.obj
                }.getOrNull()?.let { parseCaptionTracks(it) } ?: emptyList()
            }
        }.awaitAll()
        results.firstOrNull { it.isNotEmpty() } ?: emptyList()
    }

    private val blockedKeywords = listOf(
        "mma", "ufc", "wrestling", "boxing", "kickboxing", "muay thai", "rizin", "nfc", "highlights",
        "esports", "e-sports", "gaming", "gameplay", "pubg", "free fire", "dota", "league of legends", "valorant", "fifa", "fc 24", "roblox", "minecraft",
        "dj mix", "mixtape", "mashup", "remix", "song", "lyrics", "audio porn", "massage", "therapist",
    )

    private fun isAllowedSubject(subject: JsonObject): Boolean {
        val title = subject["title"]?.str?.lowercase() ?: ""
        val type = subject["subjectType"]?.jsonPrimitive?.intOrNull ?: 1
        if (blockedKeywords.any { title.contains(it) }) return false
        if (type != 1 && type != 2 && type != 100) return false
        return true
    }

    private fun parseSubjectListPage(data: JsonObject): AnimesPage {
        val items = data["subjectList"]?.arr ?: data["items"]?.arr ?: data["subjects"]?.arr ?: data["results"]?.arr?.mapNotNull { it.obj?.get("subjects")?.arr }?.flatten() ?: return AnimesPage(emptyList(), false)
        val animes = (items as List<JsonElement>).mapNotNull { item ->
            val obj = item.obj ?: return@mapNotNull null
            val subject = obj["subject"]?.obj ?: obj
            if (!isAllowedSubject(subject)) return@mapNotNull null
            val id = subject["subjectId"]?.str ?: subject["id"]?.str ?: return@mapNotNull null
            val rating = subject["imdbRatingValue"]?.str ?: ""
            val corner = subject["corner"]?.str ?: ""
            val detailPath = subject["detailPath"]?.str
                ?: subject["detailUrl"]?.str?.substringAfterLast("/")?.substringBefore("?")
                ?: ""
            SAnime.create().apply {
                title = subject["title"]?.str ?: ""
                url = if (detailPath.isNotBlank()) "/movies/$id|$detailPath" else "/movies/$id"
                thumbnail_url = subject["cover"]?.obj?.get("url")?.str
                status = if (rating.isNotBlank()) SAnime.COMPLETED else SAnime.UNKNOWN
                author = if (corner.isNotBlank()) "[$corner] $rating" else rating
            }
        }
        val pager = data["pager"]?.obj
        val currentPage = pager?.get("page")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        val perPage = pager?.get("perPage")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20
        val totalCount = pager?.get("totalCount")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val nextPage = pager?.get("nextPage")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: currentPage
        val hasMore = animes.isNotEmpty() && (
            pager?.get("hasMore")?.jsonPrimitive?.booleanOrNull == true ||
                animes.size >= perPage ||
                totalCount > currentPage * perPage ||
                nextPage > currentPage
            )
        return AnimesPage(animes, hasMore)
    }

    private val JsonElement?.obj get() = this as? JsonObject
    private val JsonElement?.arr get() = this as? JsonArray
    private val JsonElement?.str get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private val JsonElement?.bool get() = (this as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false

    private fun escapeJson(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val audio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("MP4", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(audio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality, ignoreCase = true) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_HOST_KEY
            title = PREF_HOST_TITLE
            entries = PREF_HOST_ENTRIES
            entryValues = PREF_HOST_VALUES
            setDefaultValue(PREF_HOST_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = PREF_AUDIO_TITLE
            entries = PREF_AUDIO_ENTRIES
            entryValues = PREF_AUDIO_VALUES
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }

        MultiSelectListPreference(screen.context).apply {
            key = PREF_AUDIO_EXCLUDE_KEY
            title = PREF_AUDIO_EXCLUDE_TITLE
            entries = PREF_AUDIO_ENTRIES
            entryValues = PREF_AUDIO_VALUES
            setDefaultValue(emptySet<String>())
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENABLE_DASH_KEY
            title = PREF_ENABLE_DASH_TITLE
            summary = PREF_ENABLE_DASH_SUMMARY
            setDefaultValue(PREF_ENABLE_DASH_DEFAULT)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_THUMBNAILS_KEY
            title = PREF_THUMBNAILS_TITLE
            setDefaultValue(PREF_THUMBNAILS_DEFAULT)
        }.also { screen.addPreference(it) }
    }
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        TypeFilter(),
        LanguageFilter(),
        GenreFilter(),
        YearFilter(),
        CountryFilter(),
        AnimeFilter.Separator(),
        RankingFilter(),
    )

    private class SortFilter : AnimeFilter.Select<String>("Sort", arrayOf("Default", "ForYou", "Hottest", "Rating", "Latest")) {
        fun toId() = when (state) {
            1 -> "ForYou"
            2 -> "Hottest"
            3 -> "Rating"
            4 -> "Latest"
            else -> "ForYou"
        }
    }

    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movie", "TV Series", "Animation")) {
        fun toId() = when (state) {
            1 -> "1"

            // Movie
            2 -> "2"

            // TV Series
            3 -> "ANIMATION"

            else -> "0"
        }
    }

    private class LanguageFilter :
        AnimeFilter.Select<String>(
            "Language/Dub",
            arrayOf(
                "All", "English Dub", "Hindi Dub", "Bangla dub", "French Dub", "Urdu Dub", "Tamil Dub", "Telugu Dub", "Punjabi Dub", "Malayalam Dub", "Kannada Dub", "Arabic Dub", "Arabic Sub", "Tagalog Dub", "Indonesian Dub", "Russian Dub", "Kurdish Sub", "Spanish Dub", "Spanish Sub", "SpanishLatam Dub",
            ),
        ) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Genre",
            arrayOf(
                "All", "Action", "Adventure", "Animation", "Anime", "Biography", "Bollywood", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy", "Film-Noir", "Game-Show", "History", "Horror", "Music", "Musical", "Mystery", "News", "Nollywood", "Reality-TV", "Romance", "Sci-Fi", "Sci-Fi & Fantasy", "Short", "Sport", "Talk-Show", "Thriller", "War", "Western", "Other",
            ),
        ) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class YearFilter : AnimeFilter.Select<String>("Year", arrayOf("All") + (2026 downTo 2020).map { it.toString() }.toTypedArray() + arrayOf("2010s", "2000s", "1990s", "1980s", "Other")) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class CountryFilter :
        AnimeFilter.Select<String>(
            "Country",
            arrayOf(
                "All", "United States", "United Kingdom", "Korea", "Japan", "Bangladesh", "China", "Egypt", "France", "Germany", "India", "Indonesia", "Iraq", "Italy", "Ivory Coast", "Kenya", "Lebanon", "Mexico", "Morocco", "Nigeria", "Pakistan", "Philippines", "Russia", "Saudi Arabia", "South Africa", "Spain", "Syria", "Thailand", "Malaysia", "Turkey", "Other",
            ),
        ) {
        fun toId() = if (state == 0) "All" else values[state]
    }

    private class RankingFilter :
        AnimeFilter.Select<String>(
            "Ranking List (Global)",
            arrayOf(
                "None",
                "Trending Now",
                "Cinema",
                "Bollywood",
                "Hollywood",
                "South Indian",
                "Hot Short TV",
                "Trending Bengali Movies",
                "Trending Bengali TV",
                "Asian",
                "Top Series This Week",
                "Anime",
                "Korean Drama",
                "Chinese Drama",
                "Indian Drama",
                "Reality-TV",
                "Western TV",
                "Turkish Drama",
            ),
        ) {
        fun toId() = when (state) {
            1 -> "8610422883619422240"
            2 -> "5692654647815587592"
            3 -> "414907768299210008"
            4 -> "8019599703232971616"
            5 -> "3859721901924910512"
            6 -> "5740267679764693592"
            7 -> "5837669637445565960"
            8 -> "735765054104261208"
            9 -> "5429170738815291968"
            10 -> "5606549574572819920"
            11 -> "8434602210994128512"
            12 -> "7878715743607948784"
            13 -> "8788126208987989488"
            14 -> "4903182713986896328"
            15 -> "1255898847918934600"
            16 -> "3910636007619709856"
            17 -> "5177200225164885656"
            else -> ""
        }
    }

    companion object {
        private const val PREF_HOST_KEY = "api_host"
        private const val PREF_HOST_TITLE = "API Host"

        // aoneroom hosts report cdnThrottleLevel=0 and serve streams from the
        // unthrottled sacdn (CloudFront) CDN (~6.7 MB/s aggregate); inmoviebox
        // hosts report cdnThrottleLevel=1 and route to the throttled sbcdn3
        // Edge-Cache CDN (~0.6 MB/s aggregate — constant buffering). Default
        // and ordering follow the working reference providers (api3 first).
        private const val PREF_HOST_DEFAULT = "https://api3.aoneroom.com"
        private val PREF_HOST_ENTRIES = arrayOf(
            "api3.aoneroom.com (Recommended)",
            "api6.aoneroom.com",
            "api5.aoneroom.com",
            "api4.aoneroom.com",
            "api7.aoneroom.com",
            "api4sg.aoneroom.com",
            "apig.inmoviebox.com (slow CDN)",
            "api.inmoviebox.com (slow CDN)",
            "api-in.inmoviebox.com (slow CDN)",
            "netfilm.world",
            "h5-api.aoneroom.com",
        )
        private val PREF_HOST_VALUES = arrayOf(
            "https://api3.aoneroom.com",
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api7.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://apig.inmoviebox.com",
            "https://api.inmoviebox.com",
            "https://api-in.inmoviebox.com",
            "https://netfilm.world",
            "https://h5-api.aoneroom.com",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_AUDIO_KEY = "preferred_audio"
        private const val PREF_AUDIO_TITLE = "Preferred Audio (Dub/Sub)"
        private const val PREF_AUDIO_DEFAULT = "English"
        private val PREF_AUDIO_ENTRIES = arrayOf("English", "Original", "Original Audio", "Japanese", "Hindi", "Telugu", "Tamil", "Portuguese (Brazil)", "Tagalog")
        private val PREF_AUDIO_VALUES = arrayOf("English", "Original", "Original Audio", "Japanese", "Hindi", "Telugu", "Tamil", "ptbr", "Tagalog")

        private const val PREF_AUDIO_EXCLUDE_KEY = "pref_audio_exclude"
        private const val PREF_AUDIO_EXCLUDE_TITLE = "Excluded Audio Languages"

        private const val PREF_ENABLE_DASH_KEY = "pref_enable_dash"
        private const val PREF_ENABLE_DASH_TITLE = "Enable DASH streams"
        private const val PREF_ENABLE_DASH_SUMMARY = "Include DASH (.mpd) streams alongside MP4. Disabled by default."
        private const val PREF_ENABLE_DASH_DEFAULT = false

        private const val PREF_THUMBNAILS_KEY = "pref_show_thumbnails"
        private const val PREF_THUMBNAILS_TITLE = "Show episode thumbnails"
        private const val PREF_THUMBNAILS_DEFAULT = true

        private var bearerToken: String? = null

        fun decodeJwtExpiry(token: String): Long {
            return try {
                val payload = token.split(".").getOrNull(1) ?: return 0L
                val padded = payload.replace("-", "+").replace("_", "/")
                    .let { it + "=".repeat((4 - it.length % 4) % 4) }
                val jsonStr = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                val expRegex = """"exp"\s*:\s*(\d+)""".toRegex()
                expRegex.find(jsonStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
                0L
            }
        }

        fun isTokenValid(token: String?): Boolean {
            if (token.isNullOrBlank()) return false
            val exp = decodeJwtExpiry(token)
            return exp > System.currentTimeMillis() / 1000 + 3600
        }
    }
}

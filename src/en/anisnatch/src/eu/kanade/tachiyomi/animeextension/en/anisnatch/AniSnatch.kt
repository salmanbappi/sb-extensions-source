package eu.kanade.tachiyomi.animeextension.en.anisnatch

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

class AniSnatch :
    Source(),
    ConfigurableAnimeSource {

    override val name = "AniSnatch"
    override val baseUrl = "https://anisnatch.top"
    override val lang = "en"
    override val supportsLatest = true

    // ─── Dynamic Crypto Config ───────────────────────────────────────────────

    private var smcAlphabet: String? = null
    private var markerBytes: ByteArray? = null
    private var snatchToken: String? = null

    private fun ensureSnatchToken(): String? {
        if (snatchToken != null) return snatchToken
        synchronized(this) {
            if (snatchToken != null) return snatchToken
            val initData = apiPost("api/init", mapOf("token" to ""))
            snatchToken = initData?.get("token")?.jsonPrimitive?.content
            return snatchToken
        }
    }

    private fun ensureConfig() {
        if (smcAlphabet != null && markerBytes != null) return
        synchronized(this) {
            if (smcAlphabet != null && markerBytes != null) return
            try {
                val request = okhttp3.Request.Builder()
                    .url(baseUrl)
                    .headers(headers.newBuilder().set("User-Agent", USER_AGENT).build())
                    .build()
                val response = client.newCall(request).execute()
                val html = response.body.string()

                val tokenMatch = Regex("""<meta name="token" content="([^"]+)">""").find(html)
                val token = tokenMatch?.groupValues?.get(1) ?: return

                val configTokenMatch = Regex("""configToken\s*=\s*'([^']+)'""").find(html)
                val configToken = configTokenMatch?.groupValues?.get(1) ?: return

                val versionMatch = Regex("""version\s*=\s*'([^']+)'""").find(html)
                val version = versionMatch?.groupValues?.get(1) ?: return

                val serverTimeMatch = Regex("""serverTime\s*=\s*(\d+)""").find(html)
                val serverTime = serverTimeMatch?.groupValues?.get(1) ?: return

                val tBytes = Base64.decode(configToken, Base64.DEFAULT)
                val xorKey = (token + version + serverTime).toByteArray(Charsets.UTF_8)

                val decrypted = ByteArray(tBytes.size) { i ->
                    (tBytes[i].toInt() xor xorKey[i % xorKey.size].toInt()).toByte()
                }

                val jsonObj = Json.parseToJsonElement(String(decrypted, Charsets.UTF_8)).jsonObject
                smcAlphabet = jsonObj["key"]?.jsonPrimitive?.content
                val markArr = jsonObj["mark"]?.jsonArray
                if (markArr != null) {
                    markerBytes = ByteArray(markArr.size) { idx ->
                        markArr[idx].jsonPrimitive.content.toInt().toByte()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        // Filter data — cached in companion object per skill rules
        val GENRES = listOf(
            Pair("All", ""),
            Pair("Action", "0"),
            Pair("Adult Cast", "1"),
            Pair("Adventure", "2"),
            Pair("Anthropomorphic", "3"),
            Pair("Avant Garde", "4"),
            Pair("Award Winning", "5"),
            Pair("Boys Love", "6"),
            Pair("CGDCT", "7"),
            Pair("Childcare", "8"),
            Pair("Combat Sports", "9"),
            Pair("Comedy", "10"),
            Pair("Crossdressing", "11"),
            Pair("Delinquents", "12"),
            Pair("Detective", "13"),
            Pair("Drama", "14"),
            Pair("Ecchi", "15"),
            Pair("Educational", "16"),
            Pair("Erotica", "17"),
            Pair("Fantasy", "18"),
            Pair("Gag Humor", "19"),
            Pair("Girls Love", "20"),
            Pair("Gore", "21"),
            Pair("Gourmet", "22"),
            Pair("Harem", "23"),
            Pair("Hentai", "24"),
            Pair("High Stakes Game", "25"),
            Pair("Historical", "26"),
            Pair("Horror", "27"),
            Pair("Idols (Female)", "28"),
            Pair("Idols (Male)", "29"),
            Pair("Isekai", "30"),
            Pair("Iyashikei", "31"),
            Pair("Josei", "32"),
            Pair("Kids", "33"),
            Pair("Love Polygon", "34"),
            Pair("Love Status Quo", "35"),
            Pair("Magical Sex Shift", "36"),
            Pair("Mahou Shoujo", "37"),
            Pair("Martial Arts", "38"),
            Pair("Mecha", "39"),
            Pair("Medical", "40"),
            Pair("Military", "41"),
            Pair("Music", "42"),
            Pair("Mystery", "43"),
            Pair("Mythology", "44"),
            Pair("Organized Crime", "45"),
            Pair("Otaku Culture", "46"),
            Pair("Parody", "47"),
            Pair("Performing Arts", "48"),
            Pair("Pets", "49"),
            Pair("Psychological", "50"),
            Pair("Racing", "51"),
            Pair("Reincarnation", "52"),
            Pair("Reverse Harem", "53"),
            Pair("Romance", "54"),
            Pair("Samurai", "55"),
            Pair("School", "56"),
            Pair("Sci-Fi", "57"),
            Pair("Seinen", "58"),
            Pair("Shoujo", "59"),
            Pair("Shounen", "60"),
            Pair("Showbiz", "61"),
            Pair("Slice of Life", "62"),
            Pair("Space", "63"),
            Pair("Sports", "64"),
            Pair("Strategy Game", "65"),
            Pair("Super Power", "66"),
            Pair("Supernatural", "67"),
            Pair("Survival", "68"),
            Pair("Suspense", "69"),
            Pair("Team Sports", "70"),
            Pair("Time Travel", "71"),
            Pair("Urban Fantasy", "72"),
            Pair("Vampire", "73"),
            Pair("Video Game", "74"),
            Pair("Villainess", "75"),
            Pair("Visual Arts", "76"),
            Pair("Workplace", "77"),
        )

        val TYPES = listOf(
            Pair("All", ""),
            Pair("Movie", "1"),
            Pair("TV", "2"),
            Pair("OVA", "3"),
            Pair("ONA", "4"),
            Pair("Special", "5"),
            Pair("Music", "6"),
        )

        val STATUS = listOf(
            Pair("All", ""),
            Pair("Finished Airing", "1"),
            Pair("Currently Airing", "2"),
            Pair("Not yet aired", "3"),
        )

        val SORT = listOf(
            Pair("Default", "default"),
            Pair("Recently Added", "recently"),
            Pair("Score", "score"),
            Pair("Name A-Z", "a-z"),
            Pair("Newest", "newest"),
            Pair("Oldest", "oldest"),
            Pair("Popularity", "popularity"),
        )

        val RATED = listOf(
            Pair("All", ""),
            Pair("G", "1"),
            Pair("PG", "2"),
            Pair("PG-13", "3"),
            Pair("R", "4"),
            Pair("R+", "5"),
            Pair("Rx", "6"),
        )

        val LANGUAGE = listOf(
            Pair("All", ""),
            Pair("SUB", "1"),
            Pair("DUB", "2"),
        )

        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val MEGAPLAY_AES_KEY = "i?LMTAx0Q6,:}50U"
        private const val MEGAPLAY_AES_IV = "W0;27ToaUpl_P%'c"

        val SERVERS = listOf(
            "AniVibe",
            "SnatchCDN",
            "Vidplay",
            "Vidwish",
            "Megaplay",
            "AniYT",
            "Pika",
            "OkCDN",
            "Ani4",
            "MyCloud",
            "AniAra",
            "StreamHG",
            "OtakuVid",
            "Swift",
            "Onsen",
            "FlixCloud",
        )

        // Preference keys
        const val PREF_QUALITY_KEY = "pref_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        const val PREF_SERVER_KEY = "pref_server"
        const val PREF_SERVER_DEFAULT = "AniVibe"
        const val PREF_TYPE_KEY = "pref_type"
        const val PREF_TYPE_DEFAULT = "sub"
        const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"
        const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"
    }

    private fun decryptEncPayload(enc: String): String {
        if (enc.isBlank()) return ""
        return try {
            val raw = Base64.decode(enc, Base64.URL_SAFE or Base64.NO_WRAP)
            val keyBytes = ByteArray(32)
            val keySrc = MEGAPLAY_AES_KEY.toByteArray(Charsets.UTF_8)
            System.arraycopy(keySrc, 0, keyBytes, 0, minOf(keySrc.size, 32))
            val ivBytes = MEGAPLAY_AES_IV.toByteArray(Charsets.UTF_8).copyOf(16)
            val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.IvParameterSpec(ivBytes),
            )
            val plain = cipher.doFinal(raw).toString(Charsets.UTF_8)
            val plainObj = Json.parseToJsonElement(plain).jsonObject
            plainObj["file"]?.jsonPrimitive?.content ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    // ─── SMC Cipher ───────────────────────────────────────────────────────────

    private fun smcShift(input: String, enc: Boolean, passes: Int): String {
        ensureConfig()
        val alphabet = smcAlphabet ?: return input
        val smcLen = alphabet.length
        var result = input
        repeat(passes) {
            val sb = StringBuilder(result.length)
            for (ch in result) {
                val pos = alphabet.indexOf(ch)
                if (pos == -1) {
                    sb.append(ch)
                } else {
                    val newPos = if (enc) (pos + 5) % smcLen else (pos - 5 + smcLen) % smcLen
                    sb.append(alphabet[newPos])
                }
            }
            result = sb.reverse().toString()
        }
        return result
    }

    /** Encode a string: base64 → hex → SMC encrypt until marker present in result */
    private fun str2enc(input: String): String {
        val hexStr = Base64.encodeToString(input.toByteArray(), Base64.NO_WRAP).bin2hex()
        // Try passes 1..10 for first encryption
        val f1 = (System.currentTimeMillis() % 10 + 1).toInt()
        val smc1 = smcShift(hexStr, enc = true, passes = f1)
        val combined = "${smc1}strSMCconvert$f1"
        // Find minimum passes such that result does NOT contain "strSMCconv" (strUNIQconvert enc mode)
        for (v in 1..10) {
            val h = smcShift(combined, enc = true, passes = v)
            if (!h.contains("strSMCconv")) return h
        }
        return smcShift(combined, enc = true, passes = 1)
    }

    private fun String.bin2hex(): String {
        val sb = StringBuilder()
        for (ch in this) sb.append(ch.code.toString(16).padStart(2, '0'))
        return sb.toString()
    }

    private fun str2aci(token: String): String = token.sumOf { it.code }.toString()

    private fun accurateTime(): Long = System.currentTimeMillis() / 1000L + 60L

    private fun buildPayload(json: String): Pair<String, String> {
        val encoded = str2enc(json)
        val chunks = encoded.chunked(25)

        // Pair original index with chunk, then shuffle them
        val items = chunks.mapIndexed { idx, chunk -> idx to chunk }.shuffled()

        val timeFuture = accurateTime() + 500
        val tokenStr = str2enc(timeFuture.toString())
        val authenticator = str2aci(tokenStr)

        val payload = buildJsonObject {
            put("data", JsonArray(items.map { JsonPrimitive(it.second) }))
            put("key", JsonArray(items.map { JsonPrimitive(it.first) }))
            put("token", JsonPrimitive(tokenStr))
            put("authenticator", JsonPrimitive(authenticator))
        }
        return Pair(payload.toString(), authenticator)
    }

    /** Decrypt the binary API response using XOR + gzip */
    private fun decryptResponse(bytes: ByteArray, authenticator: String): ByteArray? {
        ensureConfig()
        val marker = markerBytes ?: return null
        var markerIdx = -1
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) {
                if (bytes[i + j] != marker[j]) continue@outer
            }
            markerIdx = i
            break
        }
        if (markerIdx == -1) return null

        val encrypted = bytes.copyOfRange(markerIdx + marker.size, bytes.size)
        val keyBytes = authenticator.toByteArray()
        val decrypted = ByteArray(encrypted.size) { i ->
            (encrypted[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return GZIPInputStream(ByteArrayInputStream(decrypted)).readBytes()
    }

    /** Execute a POST request to an api endpoint and return parsed JSON */
    private fun apiPost(endpoint: String, body: Map<String, Any>, isRetry: Boolean = false): JsonObject? {
        ensureConfig()
        val jsonBody = buildJsonObject {
            for ((k, v) in body) {
                when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                }
            }
        }.toString()

        val (payloadJson, authenticator) = buildPayload(jsonBody)
        val timestamp = accurateTime()
        val url = "$baseUrl/$endpoint/$timestamp"

        val requestHeaders = headers.newBuilder()
            .set("Content-Type", "application/json")
            .set("Accept", "*/*")
            .set("User-Agent", USER_AGENT)
            .set("Referer", "$baseUrl/")
            .build()
        val requestBody = payloadJson.toRequestBody("application/json".toMediaType())
        val request = POST(url, requestHeaders, requestBody)

        return try {
            val response = client.newCall(request).execute()
            val bytes = response.body.bytes()
            val decrypted = decryptResponse(bytes, authenticator)
            if (decrypted == null) {
                if (!isRetry) {
                    synchronized(this) {
                        smcAlphabet = null
                        markerBytes = null
                    }
                    return apiPost(endpoint, body, isRetry = true)
                }
                return null
            }
            val jsonObj = Json.parseToJsonElement(String(decrypted, Charsets.UTF_8)).jsonObject
            val success = jsonObj["success"]?.jsonPrimitive?.booleanOrNull ?: true
            val msg = jsonObj["message"]?.jsonPrimitive?.content ?: ""
            if (!success && msg.contains("Session expired", ignoreCase = true) && !isRetry) {
                synchronized(this) {
                    snatchToken = null
                }
                ensureSnatchToken()
                val newBody = body.toMutableMap()
                if (newBody.containsKey("token")) {
                    newBody["token"] = snatchToken ?: ""
                }
                return apiPost(endpoint, newBody, isRetry = true)
            }
            jsonObj
        } catch (e: Exception) {
            if (!isRetry) {
                synchronized(this) {
                    smcAlphabet = null
                    markerBytes = null
                }
                return apiPost(endpoint, body, isRetry = true)
            }
            null
        }
    }

    // ─── Anime mapping ────────────────────────────────────────────────────────

    private fun JsonObject.toSAnime(): SAnime {
        val id = this["id"]?.jsonPrimitive?.content ?: ""
        val title = this["title_en"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: this["title"]?.jsonPrimitive?.content ?: "Unknown"
        val picture = this["picture"]?.jsonPrimitive?.content ?: ""
        return SAnime.create().apply {
            url = id
            this.title = title
            thumbnail_url = picture
        }
    }

    // ─── Browse ───────────────────────────────────────────────────────────────

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val data = apiPost("api/home", emptyMap()) ?: return AnimesPage(emptyList(), false)
        val trending = data["trending"] as? JsonArray ?: return AnimesPage(emptyList(), false)
        val animes = trending.mapNotNull { (it as? JsonObject)?.toSAnime() }
        return AnimesPage(animes, false)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val data = apiPost("api/home", emptyMap()) ?: return AnimesPage(emptyList(), false)
        val airing = data["airing"] as? JsonArray ?: return AnimesPage(emptyList(), false)
        val animes = airing.mapNotNull { (it as? JsonObject)?.toSAnime() }
        return AnimesPage(animes, false)
    }

    // ─── Search & Filter ──────────────────────────────────────────────────────

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.isNotBlank()) {
            // Text search via api/search
            val data = apiPost("api/search", mapOf("keyword" to query, "page" to page)) ?: return AnimesPage(emptyList(), false)
            val animeArr = (data["data"] as? JsonObject)?.get("anime") as? JsonArray ?: return AnimesPage(emptyList(), false)
            val totalPages = data["totalPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            AnimesPage(animeArr.mapNotNull { (it as? JsonObject)?.toSAnime() }, page < totalPages)
        } else {
            // Filter search via api/filter
            val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.getSelectedValue() ?: "default"
            val rated = filters.filterIsInstance<RatedFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.getSelectedValue() ?: ""

            val queryParts = mutableListOf("sort=$sort")
            if (genre.isNotBlank()) queryParts.add("genresActive=$genre")
            if (type.isNotBlank()) queryParts.add("type=$type")
            if (status.isNotBlank()) queryParts.add("status=$status")
            if (rated.isNotBlank()) queryParts.add("rated=$rated")
            if (language.isNotBlank()) queryParts.add("language=$language")
            val queryStr = queryParts.joinToString("&")

            val b64 = Base64.encodeToString(queryStr.toByteArray(), Base64.NO_WRAP).replace("=", "")
            val queryStrEncoded = b64.reversed()

            val data = apiPost("api/filter", mapOf("query" to queryStrEncoded, "page" to page)) ?: return AnimesPage(emptyList(), false)
            val animeArr = data["data"]?.jsonObject?.get("anime")?.jsonArray ?: return AnimesPage(emptyList(), false)
            val totalPages = data["totalPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            AnimesPage(animeArr.map { it.jsonObject.toSAnime() }, page < totalPages)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters below"),
        SortFilter(),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        RatedFilter(),
        LanguageFilter(),
    )

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.startsWith("$prefType - ", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Type",
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
            entries = listOf("Sub", "Dub", "Soft-Sub"),
            entryValues = listOf("sub", "dub", "soft-sub"),
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = SERVERS,
            entryValues = SERVERS,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from the video list",
            entries = SERVERS,
            entryValues = SERVERS,
            default = emptySet(),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Types",
            summary = "Select audio/release types to hide",
            entries = listOf("SUB", "DUB", "SOFT-SUB"),
            entryValues = listOf("SUB", "DUB", "SOFT-SUB"),
            default = emptySet(),
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = "Show episode thumbnails",
            summary = "Fetch and display thumbnail images in the episode list.",
            default = true,
        )
    }

    // ─── Filter classes ───────────────────────────────────────────────────────

    private class SortFilter :
        AnimeFilter.Select<String>(
            "Sort By",
            SORT.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = SORT[state].second
    }

    private class GenreFilter :
        AnimeFilter.Select<String>(
            "Genre",
            GENRES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = GENRES[state].second
    }

    private class TypeFilter :
        AnimeFilter.Select<String>(
            "Type",
            TYPES.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = TYPES[state].second
    }

    private class StatusFilter :
        AnimeFilter.Select<String>(
            "Status",
            STATUS.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = STATUS[state].second
    }

    private class RatedFilter :
        AnimeFilter.Select<String>(
            "Rating",
            RATED.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = RATED[state].second
    }

    private class LanguageFilter :
        AnimeFilter.Select<String>(
            "Language",
            LANGUAGE.map { it.first }.toTypedArray(),
        ) {
        fun getSelectedValue() = LANGUAGE[state].second
    }

    // ─── Details ──────────────────────────────────────────────────────────────

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeId = anime.url.toIntOrNull() ?: return anime
        val data = apiPost("api/anime", mapOf("id" to animeId)) ?: return anime
        val info = (data["data"] as? JsonObject)?.get("anime") as? JsonObject ?: return anime

        val title = info["title_en"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: info["title"]?.jsonPrimitive?.content ?: anime.title
        val picture = info["picture"]?.jsonPrimitive?.content ?: ""
        val synopsis = info["description"]?.jsonPrimitive?.content?.trim() ?: ""
        val genresRaw = info["genres"]?.jsonPrimitive?.content ?: ""
        val statusRaw = info["status"]?.jsonPrimitive?.content ?: ""
        val typeRaw = info["type"]?.jsonPrimitive?.content ?: ""
        val score = info["score"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val studio = info["studios"]?.jsonPrimitive?.content
            ?: info["studio"]?.jsonPrimitive?.content ?: ""

        val animeStatus = when {
            statusRaw.contains("Currently Airing", ignoreCase = true) -> SAnime.ONGOING
            statusRaw.contains("Finished Airing", ignoreCase = true) -> SAnime.COMPLETED
            statusRaw.contains("Not yet aired", ignoreCase = true) -> SAnime.LICENSED
            else -> SAnime.UNKNOWN
        }

        val scoreStr = formatScore(score)
        val desc = buildString {
            if (scoreStr != null) {
                append(scoreStr)
                append("\n\n")
            }
            if (synopsis.isNotBlank()) append(synopsis)
            if (typeRaw.isNotBlank()) append("\nType: $typeRaw")
            if (statusRaw.isNotBlank()) append("\nStatus: $statusRaw")
            if (studio.isNotBlank()) append("\nStudio: $studio")
        }

        return SAnime.create().apply {
            url = anime.url
            this.title = title
            thumbnail_url = picture.ifBlank { anime.thumbnail_url }
            description = desc
            genre = genresRaw
            status = animeStatus
            author = studio.ifBlank { null }
            initialized = true
        }
    }

    private fun formatScore(score: Double?): String? {
        if (score == null || score <= 0.0) return null
        val full = (score / 2).toInt().coerceIn(0, 5)
        return "${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(score)}"
    }

    // ─── Episodes ─────────────────────────────────────────────────────────────

    private fun parseDate(dateStr: String): Long {
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd", "MMM dd, yyyy", "MMM d, yyyy")) {
            val date = runCatching {
                SimpleDateFormat(pattern, Locale.ENGLISH).parse(dateStr)?.time
            }.getOrNull()
            if (date != null) return date
        }
        return 0L
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.toIntOrNull() ?: return emptyList()
        val data = apiPost("api/anime", mapOf("id" to animeId))
        val dataObj = data?.get("data") as? JsonObject
        val info = dataObj?.get("anime") as? JsonObject

        val lastEp = info?.get("lastep")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)
        var listArray = dataObj?.get("list") as? JsonArray

        if (listArray == null || listArray.isEmpty()) {
            val token = ensureSnatchToken() ?: ""
            val epsData = apiPost("api/loadEPs", mapOf("id" to animeId, "token" to token))
            listArray = (epsData?.get("episodes") as? JsonArray) ?: (epsData?.get("list") as? JsonArray)
        }

        val episodes = if (listArray != null && listArray.isNotEmpty()) {
            listArray.mapNotNull { element ->
                val epObj = element as? JsonObject ?: return@mapNotNull null
                val epNum = epObj["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return@mapNotNull null

                SEpisode.create().apply {
                    url = "$animeId|${epNum.toInt()}"
                    episode_number = epNum

                    val title = epObj["title"]?.jsonPrimitive?.content
                    name = if (!title.isNullOrBlank()) {
                        "Episode ${epNum.toInt()}: $title"
                    } else {
                        "Episode ${epNum.toInt()}"
                    }

                    val desc = epObj["description"]?.jsonPrimitive?.content
                    if (!desc.isNullOrBlank()) {
                        summary = desc
                    }

                    val img = epObj["image"]?.jsonPrimitive?.content
                    if (showThumbnails && !img.isNullOrBlank()) {
                        preview_url = img
                    }

                    val dateStr = epObj["airDate"]?.jsonPrimitive?.content
                    if (!dateStr.isNullOrBlank()) {
                        date_upload = parseDate(dateStr)
                    }

                    val subVal = epObj["sub"]?.jsonPrimitive
                    val dubVal = epObj["dub"]?.jsonPrimitive
                    val hasSub = subVal?.booleanOrNull ?: (subVal?.content?.toIntOrNull() ?: 0 > 0)
                    val hasDub = dubVal?.booleanOrNull ?: (dubVal?.content?.toIntOrNull() ?: 0 > 0)

                    scanlator = when {
                        hasSub && hasDub -> "Sub, Dub"
                        hasDub -> "Dub"
                        hasSub -> "Sub"
                        else -> null
                    }
                }
            }
        } else if (lastEp > 0) {
            (1..lastEp).map { ep ->
                SEpisode.create().apply {
                    url = "$animeId|$ep"
                    name = "Episode $ep"
                    episode_number = ep.toFloat()
                    scanlator = buildString {
                        val dub = info?.get("dub")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        append("Sub")
                        if (dub == 1) append(" / Dub")
                    }
                }
            }
        } else {
            emptyList()
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    // ─── Video ────────────────────────────────────────────────────────────────

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val m3u8Integration by lazy { M3u8Integration(client) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        val animeId = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()

        val token = ensureSnatchToken() ?: ""
        val data = apiPost("api/loadSVs", mapOf("id" to animeId, "ep" to epNum, "token" to token)) ?: return emptyList()
        val serverObj = data["server"] as? JsonObject ?: return emptyList()

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedTypes = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()

        val hosterMap = mutableMapOf<String, MutableList<Pair<String, String>>>()

        for ((audioType, serversEl) in serverObj) {
            val audioLabel = when (audioType) {
                "sub" -> "SUB"
                "dub" -> "DUB"
                "soft-sub" -> "SOFT-SUB"
                else -> audioType.uppercase()
            }
            if (excludedTypes.any { it.equals(audioLabel, ignoreCase = true) }) continue

            val serverList = serversEl as? JsonArray ?: continue
            for (serverEl in serverList) {
                val serverInfo = serverEl as? JsonObject ?: continue
                val title = serverInfo["title"]?.jsonPrimitive?.content ?: continue
                val source = serverInfo["source"]?.jsonPrimitive?.content ?: continue
                if (excludedServers.any { it.equals(title, ignoreCase = true) }) continue

                hosterMap.getOrPut(title) { mutableListOf() }.add(audioLabel to source)
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT

        return hosterMap.map { (title, sources) ->
            Hoster(
                hosterName = title,
                hosterUrl = sources.joinToString(",") { "${it.first}|${it.second}" },
            )
        }.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.hosterUrl.contains(prefType, ignoreCase = true) },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val serverName = hoster.hosterName
        val sourcesEncoded = hoster.hosterUrl.split(",")

        return sourcesEncoded.parallelCatchingFlatMap { item ->
            val parts = item.split("|")
            val audioPrefix = parts.getOrNull(0) ?: ""
            val source = parts.getOrNull(1) ?: return@parallelCatchingFlatMap emptyList<Video>()

            val sourceParts = source.split("/")
            val sourceType = sourceParts.getOrNull(0) ?: return@parallelCatchingFlatMap emptyList<Video>()

            when (sourceType.lowercase()) {
                "vibeplayer" -> {
                    val sourceData = sourceParts.getOrNull(1) ?: return@parallelCatchingFlatMap emptyList<Video>()
                    val playerUrl = try {
                        String(Base64.decode(sourceData, Base64.DEFAULT), Charsets.UTF_8)
                    } catch (e: Exception) {
                        null
                    }
                    if (playerUrl != null) {
                        resolveVibePlayer(playerUrl, audioPrefix, serverName)
                    } else {
                        resolveVideoPage(source, audioPrefix, serverName)
                    }
                }

                else -> {
                    resolveVideoPage(source, audioPrefix, serverName)
                }
            }
        }
    }

    private fun resolveVideoPage(source: String, audioPrefix: String, serverName: String): List<Video> = try {
        val videoUrl = "$baseUrl/video/$source"
        val pageResp = client.newCall(
            okhttp3.Request.Builder()
                .url(videoUrl)
                .headers(
                    headers.newBuilder()
                        .set("User-Agent", USER_AGENT)
                        .set("Referer", "$baseUrl/")
                        .build(),
                )
                .build(),
        ).execute()
        val html = pageResp.body.string()

        var targetUrl = videoUrl
        var targetHtml = html

        // Check if iframe or streamURL is present
        val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
        val streamUrlMatch = Regex("""streamURL\s*=\s*['"]([^'"]+)['"]""").find(html)
        val rawEmbedUrl = iframeMatch?.groupValues?.get(1) ?: streamUrlMatch?.groupValues?.get(1)

        if (!rawEmbedUrl.isNullOrBlank()) {
            val resolvedEmbedUrl = when {
                rawEmbedUrl.startsWith("//") -> "https:$rawEmbedUrl"
                rawEmbedUrl.startsWith("/") -> "$baseUrl$rawEmbedUrl"
                rawEmbedUrl.startsWith("http") -> rawEmbedUrl
                else -> "https://$rawEmbedUrl"
            }
            targetUrl = resolvedEmbedUrl
            val embedResp = client.newCall(
                okhttp3.Request.Builder()
                    .url(targetUrl)
                    .headers(
                        headers.newBuilder()
                            .set("User-Agent", USER_AGENT)
                            .set("Referer", videoUrl)
                            .build(),
                    )
                    .build(),
            ).execute()
            targetHtml = embedResp.body.string()
        }

        val videos = mutableListOf<Video>()
        val subtitleTracks = mutableListOf<Track>()

        // Extract subtitles if present
        try {
            val subsMatch = Regex("""subtitles:\s*(\[[\s\S]*?\])""").find(targetHtml)
            if (subsMatch != null) {
                val subsArr = json.parseToJsonElement(subsMatch.groupValues[1]) as? JsonArray
                if (subsArr != null) {
                    for (sub in subsArr) {
                        val subObj = sub as? JsonObject ?: continue
                        val file = subObj["file"]?.jsonPrimitive?.content ?: continue
                        val label = subObj["label"]?.jsonPrimitive?.content ?: "English"
                        subtitleTracks.add(Track(file, label))
                    }
                }
            }
        } catch (_: Exception) {}

        val origin = targetUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: ""
        val streamHeaders = headers.newBuilder()
            .set("User-Agent", USER_AGENT)
            .set("Referer", targetUrl)
            .apply {
                if (origin.isNotEmpty()) set("Origin", origin)
            }
            .build()

        // 1. Extract from srcs: [...]
        val srcsMatch = Regex("""srcs:\s*(\[[\s\S]*?\])""").find(targetHtml)
        if (srcsMatch != null) {
            val jsonArr = json.parseToJsonElement(srcsMatch.groupValues[1]) as? JsonArray
            if (jsonArr != null) {
                for (item in jsonArr) {
                    val itemObj = item as? JsonObject ?: continue
                    val url = itemObj["url"]?.jsonPrimitive?.content ?: continue
                    val label = itemObj["label"]?.jsonPrimitive?.content ?: "HD"
                    val type = itemObj["type"]?.jsonPrimitive?.content ?: if (url.contains(".m3u8")) "hls" else "mp4"

                    when (type.lowercase()) {
                        "hls" -> {
                            val hlsVideos = try {
                                playlistUtils.extractFromHls(
                                    playlistUrl = url,
                                    referer = targetUrl,
                                    masterHeaders = streamHeaders,
                                    videoHeaders = streamHeaders,
                                    videoNameGen = { quality -> "$audioPrefix - $serverName - $quality" },
                                    subtitleList = subtitleTracks,
                                )
                            } catch (_: Exception) {
                                emptyList()
                            }
                            if (hlsVideos.isNotEmpty()) {
                                videos.addAll(m3u8Integration.processVideoList(hlsVideos))
                            } else {
                                videos.addAll(
                                    m3u8Integration.processVideoList(
                                        listOf(
                                            Video(
                                                videoUrl = url,
                                                videoTitle = "$audioPrefix - $serverName - $label",
                                                headers = streamHeaders,
                                                subtitleTracks = subtitleTracks,
                                            ),
                                        ),
                                    ),
                                )
                            }
                        }

                        "dash" -> {
                            val dashVideos = try {
                                playlistUtils.extractFromDash(
                                    mpdUrl = url,
                                    videoNameGen = { quality -> "$audioPrefix - $serverName - $quality" },
                                    mpdHeaders = streamHeaders,
                                    videoHeaders = streamHeaders,
                                    referer = targetUrl,
                                    subtitleList = subtitleTracks,
                                )
                            } catch (_: Exception) {
                                emptyList()
                            }
                            videos.addAll(dashVideos)
                        }

                        else -> {
                            videos.add(
                                Video(
                                    videoUrl = url,
                                    videoTitle = "$audioPrefix - $serverName - $label",
                                    headers = streamHeaders,
                                    subtitleTracks = subtitleTracks,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // 2. Extract from src: {...}
        if (videos.isEmpty()) {
            val srcMatch = Regex("""src:\s*(\{[\s\S]*?\})""").find(targetHtml)
            if (srcMatch != null) {
                val jsonObj = json.parseToJsonElement(srcMatch.groupValues[1]) as? JsonObject
                var url = jsonObj?.get("url")?.jsonPrimitive?.content
                if (url.isNullOrBlank()) {
                    val enc = jsonObj?.get("enc")?.jsonPrimitive?.content ?: ""
                    url = decryptEncPayload(enc).takeIf { it.isNotBlank() }
                }
                val type = jsonObj?.get("type")?.jsonPrimitive?.content ?: if (url?.contains(".mpd") == true) "dash" else "hls"
                if (!url.isNullOrBlank()) {
                    when (type.lowercase()) {
                        "dash" -> {
                            val dashVideos = try {
                                playlistUtils.extractFromDash(
                                    mpdUrl = url,
                                    videoNameGen = { quality -> "$audioPrefix - $serverName - $quality" },
                                    mpdHeaders = streamHeaders,
                                    videoHeaders = streamHeaders,
                                    referer = targetUrl,
                                    subtitleList = subtitleTracks,
                                )
                            } catch (_: Exception) {
                                emptyList()
                            }
                            videos.addAll(dashVideos)
                        }

                        "mp4" -> {
                            videos.add(
                                Video(
                                    videoUrl = url,
                                    videoTitle = "$audioPrefix - $serverName",
                                    headers = streamHeaders,
                                    subtitleTracks = subtitleTracks,
                                ),
                            )
                        }

                        else -> {
                            val hlsVideos = try {
                                playlistUtils.extractFromHls(
                                    playlistUrl = url,
                                    referer = targetUrl,
                                    masterHeaders = streamHeaders,
                                    videoHeaders = streamHeaders,
                                    videoNameGen = { quality -> "$audioPrefix - $serverName - $quality" },
                                    subtitleList = subtitleTracks,
                                )
                            } catch (_: Exception) {
                                emptyList()
                            }
                            if (hlsVideos.isNotEmpty()) {
                                videos.addAll(m3u8Integration.processVideoList(hlsVideos))
                            } else {
                                videos.addAll(
                                    m3u8Integration.processVideoList(
                                        listOf(
                                            Video(
                                                videoUrl = url,
                                                videoTitle = "$audioPrefix - $serverName",
                                                headers = streamHeaders,
                                                subtitleTracks = subtitleTracks,
                                            ),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Fallback: search for m3u8 URL anywhere in page
        if (videos.isEmpty()) {
            val m3u8Regex = Regex("""["']?(https?://[^"'s\s]+\.m3u8[^"'s\s]*)["']?""")
            val m3u8Url = m3u8Regex.find(targetHtml)?.groupValues?.get(1)
            if (!m3u8Url.isNullOrBlank()) {
                val hlsVideos = try {
                    playlistUtils.extractFromHls(
                        playlistUrl = m3u8Url,
                        referer = targetUrl,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        videoNameGen = { quality -> "$audioPrefix - $serverName - $quality" },
                        subtitleList = subtitleTracks,
                    )
                } catch (_: Exception) {
                    emptyList()
                }
                if (hlsVideos.isNotEmpty()) {
                    videos.addAll(m3u8Integration.processVideoList(hlsVideos))
                } else {
                    videos.addAll(
                        m3u8Integration.processVideoList(
                            listOf(
                                Video(
                                    videoUrl = m3u8Url,
                                    videoTitle = "$audioPrefix - $serverName",
                                    headers = streamHeaders,
                                    subtitleTracks = subtitleTracks,
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        videos
    } catch (e: Exception) {
        emptyList()
    }

    private fun resolveVibePlayer(playerUrl: String, audioPrefix: String, serverName: String): List<Video> {
        return try {
            val pageResp = client.newCall(
                okhttp3.Request.Builder()
                    .url(playerUrl)
                    .headers(
                        headers.newBuilder()
                            .set("User-Agent", USER_AGENT)
                            .set("Referer", "$baseUrl/")
                            .build(),
                    )
                    .build(),
            ).execute()
            val html = pageResp.body.string()

            // Look for m3u8 URL in page source
            val m3u8Regex = Regex("""["']?(https?://[^"'s\s]+\.m3u8[^"'s\s]*)["']?""")
            val m3u8Url = m3u8Regex.find(html)?.groupValues?.get(1) ?: return emptyList()

            val origin = playerUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: ""
            val streamHeaders = headers.newBuilder()
                .set("User-Agent", USER_AGENT)
                .set("Referer", playerUrl)
                .apply {
                    if (origin.isNotEmpty()) set("Origin", origin)
                }
                .build()

            val hlsVideos = try {
                playlistUtils.extractFromHls(
                    playlistUrl = m3u8Url,
                    referer = playerUrl,
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                    videoNameGen = { quality -> "$audioPrefix - $serverName - $quality" },
                )
            } catch (_: Exception) {
                emptyList()
            }

            if (hlsVideos.isNotEmpty()) {
                m3u8Integration.processVideoList(hlsVideos)
            } else {
                m3u8Integration.processVideoList(
                    listOf(
                        Video(
                            videoUrl = m3u8Url,
                            videoTitle = "$audioPrefix - $serverName",
                            headers = streamHeaders,
                        ),
                    ),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

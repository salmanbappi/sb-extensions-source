package eu.kanade.tachiyomi.animeextension.en.anisnatch

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class AniSnatch : Source(), ConfigurableAnimeSource {

    override val name = "AniSnatch"
    override val baseUrl = "https://anisnatch.top"
    override val lang = "en"
    override val supportsLatest = true



    // ─── Crypto constants ─────────────────────────────────────────────────────

    companion object {
        // SMC cipher alphabet (62 alphanumeric chars, shift-by-5 substitution cipher)
        private const val SMC_ALPHABET = "0ghijklHIJenopLQR1Tmy4cdAK6XYBstu923rvwSabGU7CfzDEMN5qx8OPFWVZ"
        private const val SMC_LEN = 62

        // "AniSnatch" marker bytes that precede encrypted payload in response
        private val MARKER = byteArrayOf(65, 110, 105, 83, 110, 97, 116, 99, 104)

        // Filter data — cached in companion object per skill rules
        val GENRES = listOf(
            Pair("All", ""),
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Cars", "Cars"),
            Pair("Comedy", "Comedy"),
            Pair("Dementia", "Dementia"),
            Pair("Demons", "Demons"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Game", "Game"),
            Pair("Harem", "Harem"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Magic", "Magic"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Parody", "Parody"),
            Pair("Police", "Police"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shounen", "Shounen"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Space", "Space"),
            Pair("Sports", "Sports"),
            Pair("Super Power", "Super Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
            Pair("Vampire", "Vampire"),
        )

        val TYPES = listOf(
            Pair("All", ""),
            Pair("TV", "TV"),
            Pair("Movie", "Movie"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "Special"),
            Pair("Music", "Music"),
        )

        val STATUS = listOf(
            Pair("All", ""),
            Pair("Currently Airing", "Currently Airing"),
            Pair("Finished Airing", "Finished Airing"),
            Pair("Not yet aired", "Not yet aired"),
        )

        val SORT = listOf(
            Pair("Default", "default"),
            Pair("Recently Added", "recently_added"),
            Pair("Title A-Z", "title_az"),
            Pair("Score", "score"),
            Pair("Most Watched", "most_watched"),
        )

        val SEASONS = listOf(
            Pair("All", ""),
            Pair("Winter", "winter"),
            Pair("Spring", "spring"),
            Pair("Summer", "summer"),
            Pair("Fall", "fall"),
        )

        val RATED = listOf(
            Pair("All", ""),
            Pair("G", "G - All Ages"),
            Pair("PG", "PG - Children"),
            Pair("PG-13", "PG-13 - Teens 13 or older"),
            Pair("R", "R - 17+"),
            Pair("R+", "R+ - Mild Nudity"),
        )

        val LANGUAGE = listOf(
            Pair("All", ""),
            Pair("Sub", "sub"),
            Pair("Dub", "dub"),
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
    }

    // ─── SMC Cipher ───────────────────────────────────────────────────────────

    private fun smcShift(input: String, enc: Boolean, passes: Int): String {
        var result = input
        repeat(passes) {
            val sb = StringBuilder(result.length)
            for (ch in result) {
                val pos = SMC_ALPHABET.indexOf(ch)
                if (pos == -1) {
                    sb.append(ch)
                } else {
                    val newPos = if (enc) {
                        if (pos + 5 > SMC_LEN - 1) 5 - (SMC_LEN - 1 - pos) else pos + 5
                    } else {
                        if (pos - 5 < 0) SMC_LEN - 1 + pos - 4 else pos - 5
                    }
                    sb.append(SMC_ALPHABET[newPos])
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

    private fun str2aci(token: String): String {
        return token.sumOf { it.code }.toString()
    }

    private fun accurateTime(): Long = System.currentTimeMillis() / 1000L + 60L

    /** Build the encrypted POST payload matching str2ArrayEnc(json) */
    private fun buildPayload(json: String): Pair<String, String> {
        val encoded = str2enc(json)
        // Chunk data into 25-char pieces
        val dataChunks = encoded.chunked(25)

        val timeFuture = accurateTime() + 500
        val tokenStr = str2enc(timeFuture.toString())
        val authenticator = str2aci(tokenStr)

        val payload = buildJsonObject {
            put("data", JsonArray(dataChunks.mapIndexed { idx, chunk ->
                buildJsonObject {
                    put("id", JsonPrimitive(idx))
                    put("chunk", JsonPrimitive(chunk))
                }
            }))
            put("key", JsonArray(listOf(JsonPrimitive(0))))
            put("token", JsonPrimitive(tokenStr))
            put("authenticator", JsonPrimitive(authenticator))
        }
        return Pair(payload.toString(), authenticator)
    }

    /** Decrypt the binary API response using XOR + gzip */
    private fun decryptResponse(bytes: ByteArray, authenticator: String): ByteArray? {
        // Find "AniSnatch" marker
        var markerIdx = -1
        outer@ for (i in 0..bytes.size - MARKER.size) {
            for (j in MARKER.indices) {
                if (bytes[i + j] != MARKER[j]) continue@outer
            }
            markerIdx = i
            break
        }
        if (markerIdx == -1) return null

        val encrypted = bytes.copyOfRange(markerIdx + MARKER.size, bytes.size)
        val keyBytes = authenticator.toByteArray()
        val decrypted = ByteArray(encrypted.size) { i ->
            (encrypted[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte()
        }
        return GZIPInputStream(ByteArrayInputStream(decrypted)).readBytes()
    }

    /** Execute a POST request to an api endpoint and return parsed JSON */
    private fun apiPost(endpoint: String, body: Map<String, Any>): JsonObject? {
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

        val requestHeaders = headers.newBuilder().set("Content-Type", "application/json").set("Accept", "*/*").build()
        val requestBody = payloadJson.toRequestBody("application/json".toMediaType())
        val request = POST(url, requestHeaders, requestBody)

        return try {
            val response = client.newCall(request).execute()
            val bytes = response.body.bytes()
            val decrypted = decryptResponse(bytes, authenticator) ?: return null
            Json.parseToJsonElement(String(decrypted, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
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
        val trending = data["data"]?.jsonObject?.get("trending")?.jsonArray ?: return AnimesPage(emptyList(), false)
        val animes = trending.map { it.jsonObject.toSAnime() }
        return AnimesPage(animes, false)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val data = apiPost("api/home", emptyMap()) ?: return AnimesPage(emptyList(), false)
        val airing = data["data"]?.jsonObject?.get("airing")?.jsonArray ?: return AnimesPage(emptyList(), false)
        val animes = airing.map { it.jsonObject.toSAnime() }
        return AnimesPage(animes, false)
    }

    // ─── Search & Filter ──────────────────────────────────────────────────────

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.isNotBlank()) {
            // Text search via api/search
            val data = apiPost("api/search", mapOf("keyword" to query, "page" to page)) ?: return AnimesPage(emptyList(), false)
            val animeArr = data["data"]?.jsonObject?.get("anime")?.jsonArray ?: return AnimesPage(emptyList(), false)
            val totalPages = data["totalPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            AnimesPage(animeArr.map { it.jsonObject.toSAnime() }, page < totalPages)
        } else {
            // Filter search via api/filter
            val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val type = filters.filterIsInstance<TypeFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.getSelectedValue() ?: "default"
            val season = filters.filterIsInstance<SeasonFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val rated = filters.filterIsInstance<RatedFilter>().firstOrNull()?.getSelectedValue() ?: ""
            val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.getSelectedValue() ?: ""

            val queryParts = mutableListOf("sort=$sort")
            if (genre.isNotBlank()) queryParts.add("genresActive=$genre")
            if (type.isNotBlank()) queryParts.add("type=$type")
            if (status.isNotBlank()) queryParts.add("status=$status")
            if (season.isNotBlank()) queryParts.add("season=$season")
            if (rated.isNotBlank()) queryParts.add("rated=$rated")
            if (language.isNotBlank()) queryParts.add("language=$language")
            val queryStr = queryParts.joinToString("&")

            val data = apiPost("api/filter", mapOf("query" to queryStr, "page" to page)) ?: return AnimesPage(emptyList(), false)
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
        SeasonFilter(),
        RatedFilter(),
        LanguageFilter(),
    )

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefType, ignoreCase = true) }
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
            entries = listOf("AniVibe", "NekoVibe", "Kwik", "Megaplay", "Vidwish"),
            entryValues = listOf("AniVibe", "NekoVibe", "Kwik", "Megaplay", "Vidwish"),
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
            entries = listOf("AniVibe", "NekoVibe", "Kwik", "AniBD", "AniYT", "OkCdn", "AniAra", "MP4", "UNI", "Megaplay", "Vidwish"),
            entryValues = listOf("AniVibe", "NekoVibe", "Kwik", "AniBD", "AniYT", "OkCdn", "AniAra", "MP4", "UNI", "Megaplay", "Vidwish"),
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
    }

    // ─── Filter classes ───────────────────────────────────────────────────────

    private class SortFilter : AnimeFilter.Select<String>(
        "Sort By",
        SORT.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = SORT[state].second
    }

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        GENRES.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = GENRES[state].second
    }

    private class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        TYPES.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = TYPES[state].second
    }

    private class StatusFilter : AnimeFilter.Select<String>(
        "Status",
        STATUS.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = STATUS[state].second
    }

    private class SeasonFilter : AnimeFilter.Select<String>(
        "Season",
        SEASONS.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = SEASONS[state].second
    }

    private class RatedFilter : AnimeFilter.Select<String>(
        "Rating",
        RATED.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = RATED[state].second
    }

    private class LanguageFilter : AnimeFilter.Select<String>(
        "Language",
        LANGUAGE.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = LANGUAGE[state].second
    }

    // ─── Details ──────────────────────────────────────────────────────────────

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeId = anime.url.toIntOrNull() ?: return anime
        val data = apiPost("api/anime", mapOf("id" to animeId)) ?: return anime
        val info = data["data"]?.jsonObject?.get("anime")?.jsonObject ?: return anime

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
            if (scoreStr != null) { append(scoreStr); append("\n\n") }
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

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.toIntOrNull() ?: return emptyList()
        val data = apiPost("api/anime", mapOf("id" to animeId)) ?: return emptyList()
        val info = data["data"]?.jsonObject?.get("anime")?.jsonObject ?: return emptyList()

        val lastEp = info["lastep"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (lastEp == 0) return emptyList()

        val episodes = (1..lastEp).map { ep ->
            SEpisode.create().apply {
                url = "$animeId|$ep"
                name = "Episode $ep"
                episode_number = ep.toFloat()
                scanlator = buildString {
                    val dub = info["dub"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    append("Sub")
                    if (dub == 1) append(" / Dub")
                }
            }
        }.reversed()

        return episodes
    }

    // ─── Video ────────────────────────────────────────────────────────────────

    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        val animeId = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()

        val data = apiPost("api/loadSVs", mapOf("id" to animeId, "ep" to epNum)) ?: return emptyList()
        val serverObj = data["server"]?.jsonObject ?: return emptyList()

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedTypes = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()

        val hosters = mutableListOf<Hoster>()

        for ((audioType, serversEl) in serverObj) {
            val audioLabel = when (audioType) {
                "sub" -> "SUB"
                "dub" -> "DUB"
                "soft-sub" -> "SOFT-SUB"
                else -> audioType.uppercase()
            }
            if (excludedTypes.any { it.equals(audioLabel, ignoreCase = true) }) continue

            val serverList = serversEl.jsonArray
            for (serverEl in serverList) {
                val serverInfo = serverEl.jsonObject
                val title = serverInfo["title"]?.jsonPrimitive?.content ?: continue
                val source = serverInfo["source"]?.jsonPrimitive?.content ?: continue
                if (excludedServers.any { it.equals(title, ignoreCase = true) }) continue

                hosters.add(
                    Hoster(
                        hosterName = "$audioLabel - $title",
                        hosterUrl = source,
                    ),
                )
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val source = hoster.hosterUrl
        val audioPrefix = hoster.hosterName.split(" - ").firstOrNull() ?: hoster.hosterName
        val serverName = hoster.hosterName.substringAfter(" - ")

        // source format: type/data/animeId-epNum
        val sourceParts = source.split("/")
        val sourceType = sourceParts.getOrNull(0) ?: return emptyList()
        val sourceData = sourceParts.getOrNull(1) ?: return emptyList()

        return when (sourceType.lowercase()) {
            "vibeplayer" -> {
                // data is base64-encoded player URL, optionally followed by sub= param after second /
                val playerUrl = try {
                    String(Base64.decode(sourceData, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    return emptyList()
                }
                resolveVibePlayer(playerUrl, audioPrefix, serverName)
            }
            "kwik" -> {
                // data is base64-encoded JSON of quality→kwik URLs
                val kwikJson = try {
                    String(Base64.decode(sourceData.replace("%3D", "="), Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) {
                    return emptyList()
                }
                resolveKwik(kwikJson, audioPrefix, serverName)
            }
            "megaplay", "vidwish" -> {
                // These are StreamWish-compatible embed servers
                val embedId = sourceData
                val embedUrl = "https://megaplay.buzz/stream/$embedId"
                streamWishExtractor.videosFromUrl(embedUrl) { "$audioPrefix - $serverName - $it" }
            }
            else -> emptyList()
        }
    }

    private fun resolveVibePlayer(playerUrl: String, audioPrefix: String, serverName: String): List<Video> {
        return try {
            val pageResp = client.newCall(
                okhttp3.Request.Builder()
                    .url(playerUrl)
                    .header("Referer", "$baseUrl/")
                    .header("User-Agent", "Mozilla/5.0")
                    .build(),
            ).execute()
            val html = pageResp.body.string()

            // Look for m3u8 URL in page source
            val m3u8Regex = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")
            val m3u8Url = m3u8Regex.find(html)?.groupValues?.get(1) ?: return emptyList()

            listOf(
                Video(
                    videoUrl = m3u8Url,
                    videoTitle = "$audioPrefix - $serverName",
                    headers = headers.newBuilder()
                        .set("Referer", playerUrl)
                        .set("Origin", playerUrl.substringBefore("/", playerUrl).let { proto ->
                            val host = playerUrl.substringAfter("://").substringBefore("/")
                            "$proto//$host"
                        })
                        .build(),
                ),
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolveKwik(kwikJson: String, audioPrefix: String, serverName: String): List<Video> {
        return try {
            val obj = Json.parseToJsonElement(kwikJson).jsonObject
            obj.entries.mapNotNull { (quality, urlEl) ->
                val kwikUrl = urlEl.jsonPrimitive.content
                if (kwikUrl.isBlank()) return@mapNotNull null
                // Kwik requires fetching the redirect to get the final m3u8
                val resp = client.newCall(
                    okhttp3.Request.Builder()
                        .url(kwikUrl)
                        .header("Referer", "https://kwik.cx/")
                        .header("User-Agent", "Mozilla/5.0")
                        .build(),
                ).execute()
                val m3u8 = resp.request.url.toString()
                Video(
                    videoUrl = m3u8,
                    videoTitle = "$audioPrefix - $serverName - ${quality}p",
                    resolution = quality.toIntOrNull(),
                    headers = headers.newBuilder()
                        .set("Referer", "https://kwik.cx/")
                        .build(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

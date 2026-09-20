package eu.kanade.tachiyomi.animeextension.en.senshi

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Senshi :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Senshi"

    override val baseUrl = "https://senshi.to"

    override val lang = "en"

    override val supportsLatest = true

    override val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val streamProxy by lazy { SenshiStreamProxy(client) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", USER_AGENT)
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .add("Sec-CH-UA", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"")
        .add("Sec-CH-UA-Mobile", "?0")
        .add("Sec-CH-UA-Platform", "\"Windows\"")

    private fun streamHeaders(): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Accept", "*/*")
        .add("Accept-Language", "en-US,en;q=0.5")
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")
        .build()

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val period = preferences.getString(PREF_TRENDING_KEY, PREF_TRENDING_DEFAULT) ?: PREF_TRENDING_DEFAULT
        // `/anime/trending/{day,week,month}` returns a bare JSON array (not a
        // paged envelope), always in one shot — page 1 carries everything.
        val animes = runCatching {
            val element = json.parseToJsonElement(fetchText("$baseUrl/anime/trending/$period", headers))
            (if (element is JsonArray) element else JsonArray(emptyList())).mapNotNull { parseAnime(it.jsonObject) }
        }.getOrNull() ?: return AnimesPage(emptyList(), false)
        return AnimesPage(if (page == 1) animes else emptyList(), false)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val period = preferences.getString(PREF_TRENDING_KEY, PREF_TRENDING_DEFAULT) ?: PREF_TRENDING_DEFAULT
        val animes = runCatching {
            val element = json.parseToJsonElement(fetchText("$baseUrl/anime/trending/$period", headers))
            (if (element is JsonArray) element else JsonArray(emptyList())).mapNotNull { parseAnime(it.jsonObject) }
        }.getOrNull() ?: return AnimesPage(emptyList(), false)
        return AnimesPage(if (page == 1) animes else emptyList(), false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val body = buildJsonObject {
            put("page", page)
            put("limit", PAGE_SIZE)
            if (query.isNotBlank()) put("search", query)
            filters.filterIsInstance<Filters.SortFilter>().firstOrNull()
                ?.let { put("sort", it.toUriPart()) }
            filters.filterIsInstance<Filters.TypeFilter>().firstOrNull()
                ?.let { if (it.toUriPart().isNotBlank()) put("type", it.toUriPart()) }
            filters.filterIsInstance<Filters.StatusFilter>().firstOrNull()
                ?.let { if (it.toUriPart().isNotBlank()) put("status", it.toUriPart()) }
            filters.filterIsInstance<Filters.SeasonFilter>().firstOrNull()
                ?.let { if (it.toUriPart().isNotBlank()) put("season", it.toUriPart()) }
            filters.filterIsInstance<Filters.LanguageFilter>().firstOrNull()
                ?.let { if (it.toUriPart().isNotBlank()) put("language", it.toUriPart()) }
            val genres = filters.filterIsInstance<Filters.GenreFilter>().firstOrNull()?.getSelectedValues().orEmpty()
            if (genres.isNotEmpty()) {
                putJsonArray("genres") { genres.forEach(::add) }
            }
            filters.filterIsInstance<Filters.YearFilter>().firstOrNull()
                ?.let { it.state.toIntOrNull()?.let { year -> put("year", year) } }
        }

        val root = runCatching { postJson("$baseUrl/anime/filter", body).jsonObject }.getOrNull()
            ?: return AnimesPage(emptyList(), false)
        val animes = root.array("data").mapNotNull { parseAnime(it.jsonObject) }
        val totalPages = root.int("totalPages")
        val hasNextPage = totalPages?.let { page < it }
            ?: (animes.size >= PAGE_SIZE)
        return AnimesPage(animes, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.SortFilter(),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.LanguageFilter(),
        Filters.GenreFilter(),
        Filters.YearFilter(),
    )

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val publicId = anime.url.substringAfter("/anime/").substringBefore("#")
        // `/anime/{public_id}` resolves the short id, and the numeric `id` it
        // returns is what `/episodes/` and `/episode-embeds/` require.
        val detail = getAnime("$baseUrl/anime/$publicId")
        val numericId = detail["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (numericId.isNotBlank()) {
            anime.setUrlWithoutDomain("/anime/$numericId#$publicId")
        }
        return anime.apply {
            title = detail.string("title").ifBlank { title }
            val picture = detail.string("anime_picture")
            if (picture.isNotBlank()) thumbnail_url = fixImageUrl(picture)
            val synopsis = detail.string("ani_description").ifBlank { detail.string("description") }
            val year = detail.string("ani_year").ifBlank { detail.string("year") }
            val season = detail.string("ani_season").ifBlank { detail.string("season") }
            val score = detail.string("score")
            val studios = detail.string("studios")
            description = buildDescription(synopsis, year, season, score, studios)
            genre = detail.genres("genres")
            status = when (detail.string("ani_status").lowercase()) {
                "currently airing", "releasing", "ongoing" -> SAnime.ONGOING
                "finished airing", "completed" -> SAnime.COMPLETED
                "not yet aired", "not_yet_aired", "upcoming" -> SAnime.UNKNOWN
                else -> SAnime.UNKNOWN
            }
            if (detail.string("studios").isNotBlank()) author = detail.string("studios")
            if (detail.string("producers").isNotBlank()) artist = detail.string("producers")
            initialized = true
        }
    }

    private fun buildDescription(synopsis: String, year: String, season: String, score: String, studios: String): String {
        val meta = buildList {
            if (score.isNotBlank() && score != "null") add("Score: $score")
            if (season.isNotBlank() && season != "null") {
                add(if (year.isNotBlank() && year != "null") "Season: $season $year" else "Season: $season")
            } else if (year.isNotBlank() && year != "null") {
                add("Year: $year")
            }
            if (studios.isNotBlank() && studios != "null") add("Studios: $studios")
        }
        return (meta + synopsis.trim()).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        // anime.url is "/anime/{numericId}#{publicId}" after details (or the
        // plain "/anime/{publicId}" from a search row).
        val rawId = anime.url.substringAfter("/anime/").substringBefore("#")
        var numericId = rawId.toIntOrNull()?.toString().orEmpty()
        val publicId = anime.url.substringAfter("#", "")
        if (numericId.isBlank() && publicId.isNotBlank()) {
            numericId = runCatching {
                getAnime("$baseUrl/anime/$rawId")["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }.getOrNull().orEmpty()
            if (numericId.isNotBlank()) {
                anime.setUrlWithoutDomain("/anime/$numericId#$rawId")
            }
        }
        if (numericId.isBlank()) return emptyList()
        val episodes = runCatching {
            getJson("$baseUrl/episodes/$numericId").jsonArray
        }.getOrNull() ?: return emptyList()

        val showThumbnails = preferences.getBoolean(PREF_THUMBNAILS_KEY, true)
        val list = episodes.mapNotNull { item ->
            val episode = item.jsonObject
            val number = episode["ep_id"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                ?: episode["episode_number"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                ?: return@mapNotNull null
            val title = episode.string("ep_title").ifBlank { episode.string("title") }
            val label = formatEpNum(number)
            SEpisode.create().apply {
                url = "/anime/$numericId#season=1&ep=$label"
                name = if (title.isNotBlank() && !title.equals("Episode $label", ignoreCase = true)) {
                    "Episode $label: $title"
                } else {
                    "Episode $label"
                }
                episode_number = number
                date_upload = parseDate(episode.string("created_at").ifBlank { episode.string("airing_date") })
                if (showThumbnails) {
                    val thumb = episode.string("ep_thumbnail").ifBlank { episode.string("thumbnail") }
                    if (thumb.isNotBlank()) preview_url = fixImageUrl(thumb)
                }
                scanlator = when {
                    episode["ep_filler"]?.jsonPrimitive?.contentOrNull == "true" -> "Filler"
                    episode["ep_recap"]?.jsonPrimitive?.contentOrNull == "true" -> "Recap"
                    else -> null
                }
            }
        }
        // Aniyomi requires descending order (highest episode at index 0).
        return list.sortedByDescending { it.episode_number }
    }

    private fun formatEpNum(num: Float): String =
        if (num % 1f == 0f) num.toInt().toString() else num.toString()

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank() || dateStr == "null") return 0L
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)
                .parse(dateStr.substringBefore("."))?.time ?: 0L
        }.getOrDefault(0L)
    }

    // ============================ Hoster List =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val anchor = episode.url.substringAfter("#")
        val season = Regex("""season=(\d+)""").find(anchor)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val ep = Regex("""ep=([\d.]+)""").find(anchor)?.groupValues?.get(1)?.toFloatOrNull()
            ?: return emptyList()
        // Zero-base episode math: season 1 is the raw number; later seasons are
        // offset by (season - 1) * 100 so SEpisode keys stay unique per series.
        val episodeId = if (season <= 1) ep.toInt() else (season - 1) * 100 + ep.toInt()
        // The episode URL carries the numeric anime id (`/anime/{numericId}#…`);
        // search rows that skipped details still hold the public id — resolve it.
        val rawId = episode.url.substringAfter("/anime/").substringBefore("#")
        val animeId = rawId.toIntOrNull()?.toString() ?: runCatching {
            getAnime("$baseUrl/anime/$rawId")["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrNull().orEmpty()
        if (animeId.isBlank()) return emptyList()
        val embeds = runCatching {
            getJson("$baseUrl/episode-embeds/$animeId/$episodeId").jsonArray
        }.getOrNull() ?: return emptyList()

        return embeds.mapNotNull { item ->
            val embed = item.jsonObject
            val remoteId = embed["remote_source_id"]?.jsonPrimitive?.intOrNull
                ?: embed["remote_source_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: return@mapNotNull null
            val audio = embed.string("status").ifBlank { "HardSub" }
            val label = if (audio.equals("Dub", ignoreCase = true)) "Dub" else "Sub"
            Hoster(
                hosterName = "Senshi $label",
                hosterUrl = "$remoteId|$label|$episodeId",
            )
        }.distinctBy { it.hosterUrl }
    }

    // ============================ Video List ==============================

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        val remoteId = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
        val audioBadge = parts.getOrNull(1)?.ifBlank { "Sub" } ?: "Sub"

        val sources = runCatching {
            val element = json.parseToJsonElement(fetchText("$VIDCLOUD_SOURCES?id=$remoteId", streamHeaders()))
            if (element is JsonArray) element else JsonArray(emptyList())
        }.getOrNull() ?: return emptyList()

        val preferredVersion = preferences.getString(PREF_VERSION_KEY, PREF_VERSION_DEFAULT) ?: PREF_VERSION_DEFAULT
        val showDub = preferredVersion != "sub-only"

        return sources.mapNotNull { it.jsonObject }.parallelCatchingFlatMap { entry ->
            val src = entry["source"]?.jsonObject ?: return@parallelCatchingFlatMap emptyList()
            val masterUrl = src.string("src")
            if (!masterUrl.startsWith("http")) return@parallelCatchingFlatMap emptyList()
            val audio = src.string("audio")
            if (!showDub && (audio.contains("dub", ignoreCase = true) || audioBadge.equals("Dub", ignoreCase = true))) {
                // The master carries both languages anyway; keep the entry and just
                // let sorting prefer sub — dropping it here would lose exclusives.
            }

            val subtitles = entry.array("tracks").mapNotNull { item ->
                val track = item.jsonObject
                val label = track.string("label")
                if (label.equals("chapter", ignoreCase = true)) return@mapNotNull null
                val file = track.string("vtt_url").ifBlank { track.string("url") }
                if (file.isBlank() || label.isBlank()) return@mapNotNull null
                Track(fixImageUrl(file), label)
            }

            val master = try {
                decryptMaster(fetchText(masterUrl, streamHeaders()))
            } catch (_: Exception) {
                return@parallelCatchingFlatMap emptyList()
            } ?: return@parallelCatchingFlatMap emptyList()

            val audioLines = master.lines().filter { it.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") }
            val streamInfos = Regex("""#EXT-X-STREAM-INF:([^\n]+)\n([^\n]+)""").findAll(master).toList()
            if (streamInfos.isEmpty()) return@parallelCatchingFlatMap emptyList()

            val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
            val base = masterUrl.substringBeforeLast("/")
            val videos = streamInfos.mapNotNull { match ->
                val attrs = match.groupValues[1]
                val uri = match.groupValues[2].trim()
                val height = Regex("""RESOLUTION=\d+x(\d+)""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                val quality = when {
                    height != null -> "${height}p"
                    else -> Regex("""(\d{3,4})p""").find(uri)?.groupValues?.get(1)?.let { "${it}p" } ?: "Auto"
                }

                // Preserve the whole decrypted master (with its independent audio
                // renditions) as a quality-scoped mini-master, so ExoPlayer keeps
                // both Japanese and English audio in sync instead of going silent.
                val miniMaster = buildString {
                    append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
                    audioLines.forEach { append(it).append('\n') }
                    append("#EXT-X-STREAM-INF:").append(attrs).append('\n')
                    append(resolveUrl(base, uri)).append('\n')
                }
                val proxyUrl = streamProxy.serveText(miniMaster, MIME_HLS)
                Video(
                    videoUrl = proxyUrl,
                    videoTitle = "Senshi $quality [$audioBadge]",
                    headers = streamHeaders(),
                    subtitleTracks = subtitles,
                    resolution = quality.filter { it.isDigit() }.toIntOrNull(),
                )
            }

            // List the entry's own declared quality first when the master omits it.
            if (videos.isEmpty()) {
                val declared = src.string("quality")
                if (declared.isNotBlank()) {
                    val miniMaster = buildString {
                        append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
                        audioLines.forEach { append(it).append('\n') }
                        append("#EXT-X-STREAM-INF:BANDWIDTH=0\n")
                        append(masterUrl).append('\n')
                    }
                    return@parallelCatchingFlatMap listOf(
                        Video(
                            videoUrl = streamProxy.serveText(miniMaster, MIME_HLS),
                            videoTitle = "Senshi $declared [$audioBadge]",
                            headers = streamHeaders(),
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            }
            videos.filter {
                preferredQuality == "auto" || it.videoTitle.contains(preferredQuality, ignoreCase = true) || videos.size == 1
            }.ifEmpty { videos }
        }
    }

    private fun resolveUrl(base: String, uri: String): String =
        base.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri

    // ========================= EM3U8 Cryptography =========================

    /**
     * Decrypts an `EM3U8v1:` envelope with the AES-256-GCM key shipped inside the
     * site's own watch-page bundle (`WatchPage-*.js`: `lr[i] ^ ir[i]`). Layout is
     * `base64(iv[12] || ciphertext || tag[16])`. Plain playlists pass through.
     */
    private fun decryptMaster(payload: String): String? {
        val trimmed = payload.trimStart().removePrefix("﻿")
        if (!trimmed.startsWith(EM3U8_PREFIX)) {
            return trimmed.takeIf { it.startsWith("#EXTM3U") || it.startsWith("#EXT-X-") }
        }
        return try {
            val raw = Base64.decode(trimmed.removePrefix(EM3U8_PREFIX).trim(), Base64.DEFAULT)
            if (raw.size < 12 + 16 + 1) return null
            val iv = raw.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(EM3U8_KEY, "AES"),
                GCMParameterSpec(128, iv),
            )
            // JCE expects ciphertext || tag in a single buffer.
            val plain = cipher.doFinal(raw, 12, raw.size - 12)
            String(plain, Charsets.UTF_8).takeIf { it.trimStart().startsWith("#EXTM3U") }
        } catch (_: Exception) {
            null
        }
    }

    // ============================== Sorting ===============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val version = preferences.getString(PREF_VERSION_KEY, PREF_VERSION_DEFAULT) ?: PREF_VERSION_DEFAULT
        return sortedWith(
            compareByDescending<Video> {
                when (version) {
                    "sub-only" -> it.videoTitle.contains("[Sub]", ignoreCase = true)
                    "dub-only" -> it.videoTitle.contains("[Dub]", ignoreCase = true)
                    else -> it.videoTitle.contains("[Sub]", ignoreCase = true)
                }
            }.thenByDescending {
                if (quality == "auto") 0 else if (it.videoTitle.contains(quality, ignoreCase = true)) 1 else 0
            }.thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TRENDING_KEY,
            title = "Trending window (Popular / Latest)",
            entries = listOf("Today", "This week", "This month"),
            entryValues = listOf("day", "week", "month"),
            default = PREF_TRENDING_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("Auto (all qualities)", "1080p", "480p"),
            entryValues = listOf("auto", "1080p", "480p"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_VERSION_KEY,
            title = "Preferred version",
            summary = "%s",
            entries = listOf("Sub preferred", "Sub only", "Dub only"),
            entryValues = listOf("sub-pref", "sub-only", "dub-only"),
            default = PREF_VERSION_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_THUMBNAILS_KEY,
            title = "Show episode thumbnails",
            summary = "Display episode preview images in the episode list.",
            default = true,
        )
    }

    // ============================== Helpers ===============================

    private fun parseAnime(anime: JsonObject): SAnime? {
        val publicId = anime.string("public_id").ifBlank { return null }
        return SAnime.create().apply {
            title = anime.string("title_english").ifBlank { anime.string("title") }.ifBlank { return null }
            setUrlWithoutDomain("/anime/$publicId")
            val picture = anime.string("anime_picture")
            if (picture.isNotBlank()) thumbnail_url = fixImageUrl(picture)
            genre = anime.genres("genres")
            status = when (anime.string("ani_status").lowercase()) {
                "currently airing", "releasing", "ongoing" -> SAnime.ONGOING
                "finished airing", "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            fetch_type = FetchType.Episodes
        }
    }

    private fun fixImageUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        url.isBlank() -> url
        else -> "$baseUrl/$url"
    }

    private fun getAnime(animeUrl: String): JsonObject = getJson("$baseUrl$animeUrl").jsonObject

    private fun postJson(url: String, body: JsonObject): JsonObject = json.parseToJsonElement(
        client.newCall(POST(url, headers, body.toString().toRequestBody(JSON_MEDIA_TYPE))).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            response.body.string()
        },
    ).jsonObject

    private fun getJson(url: String): JsonObject = json.parseToJsonElement(
        client.newCall(GET(url, headers)).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            response.body.string()
        },
    ).jsonObject

    private fun fetchText(url: String, requestHeaders: Headers): String =
        client.newCall(GET(url, requestHeaders)).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            response.body.string()
        }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.genres(key: String): String {
        val element = this[key] ?: return ""
        return when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.contentOrNull }.joinToString(", ")
            else -> element.jsonPrimitive.contentOrNull.orEmpty()
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MIME_HLS = "application/vnd.apple.mpegurl"
        const val PAGE_SIZE = 24

        const val VIDCLOUD_SOURCES = "https://s.vidcloud.se/_v1/sources"

        const val EM3U8_PREFIX = "EM3U8v1:"

        /**
         * AES-256-GCM key shipped in the site's watch-page bundle
         * (`WatchPage-*.js`): `key[i] = lr[i] ^ ir[i]`. Static per deployment;
         * if streams stop decrypting, re-derive it from a fresh bundle.
         */
        val EM3U8_KEY = byteArrayOf(
            0x6e.toByte(), 0xe2.toByte(), 0x72.toByte(), 0x13.toByte(),
            0x27.toByte(), 0xed.toByte(), 0x46.toByte(), 0x9b.toByte(),
            0xb6.toByte(), 0xd9.toByte(), 0x3a.toByte(), 0xb9.toByte(),
            0xb7.toByte(), 0xa8.toByte(), 0x38.toByte(), 0x04.toByte(),
            0x51.toByte(), 0x90.toByte(), 0xb5.toByte(), 0xba.toByte(),
            0x85.toByte(), 0xd9.toByte(), 0xce.toByte(), 0xa3.toByte(),
            0xb1.toByte(), 0xe1.toByte(), 0x78.toByte(), 0x05.toByte(),
            0xf7.toByte(), 0xb4.toByte(), 0xae.toByte(), 0xf6.toByte(),
        )

        const val PREF_TRENDING_KEY = "pref_trending_window"
        const val PREF_TRENDING_DEFAULT = "day"
        const val PREF_QUALITY_KEY = "pref_quality"
        const val PREF_QUALITY_DEFAULT = "auto"
        const val PREF_VERSION_KEY = "pref_version"
        const val PREF_VERSION_DEFAULT = "sub-pref"
        const val PREF_THUMBNAILS_KEY = "pref_thumbnails"
    }
}

// ========================= Loopback HLS Proxy =============================

/**
 * Minimal loopback server for Senshi streams. Every playlist in the vidcloud
 * chain is `EM3U8v1:`-encrypted and its chunks are MPEG-TS served under fake
 * `.jpg` names with an `image/jpeg` content type, so remote URLs are never
 * handed to the player directly:
 *
 * - [serveText] hosts the extension-decrypted mini-master playlists.
 * - Extensionless `/s/*` URLs relay variant playlists, audio renditions and
 *   chunks; playlists are re-served as HLS and chunks as `video/mp2t`, with
 *   clean TS passed through untouched. No `.m3u8` suffix ever appears, so the
 *   shared m3u8server (if ever stacked) leaves these URLs alone.
 */
private class SenshiStreamProxy(private val client: okhttp3.OkHttpClient) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var port: Int = 0

    private val textStore = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, String>>()

    /** Hosts an already-decrypted playlist and returns its loopback URL. */
    fun serveText(content: String, mime: String): String {
        start()
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        textStore[id] = content.toByteArray(Charsets.UTF_8) to mime
        return "$LOOPBACK:$port$t/idata?id=$id"
    }

    /** Returns the loopback URL relaying [targetUrl] (playlist or chunk). */
    fun relay(targetUrl: String, referer: String): String {
        start()
        return "$LOOPBACK:$port$s/relay?u=${encode(targetUrl)}&r=${encode(referer)}"
    }

    private fun start() {
        if (serverSocket?.isClosed == false) return
        synchronized(this) {
            if (serverSocket?.isClosed == false) return
            val socket = try {
                ServerSocket(0)
            } catch (_: Exception) {
                return
            }
            serverSocket = socket
            port = socket.localPort
            executor.execute {
                while (!socket.isClosed) {
                    val connection = try {
                        socket.accept()
                    } catch (_: Exception) {
                        return@execute
                    }
                    executor.execute { handle(connection) }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.setSoLinger(true, 5)

            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
            }
            val isHead = requestLine.startsWith("HEAD ")
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val params = path.substringAfter("?", "")
                .split("&")
                .mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator <= 0) null else pair.take(separator) to pair.drop(separator + 1)
                }
                .toMap()

            if (path.startsWith("$t/idata")) {
                val stored = params["id"]?.let(textStore::get)
                if (stored == null) {
                    respond(socket, 404, MIME_TEXT, ByteArray(0), isHead)
                } else {
                    val (body, mime) = stored
                    respond(socket, 200, mime, body, isHead)
                }
                return
            }

            val target = params["u"]?.let(::decode) ?: return respond(socket, 400, MIME_TEXT, ByteArray(0))
            val referer = params["r"]?.let(::decode).orEmpty()

            val request = okhttp3.Request.Builder()
                .url(target)
                .header("User-Agent", Senshi.USER_AGENT)
                .header("Accept", "*/*")
                .apply {
                    if (referer.isNotBlank()) {
                        header("Referer", referer)
                        header("Origin", referer.trimEnd('/'))
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    respond(socket, response.code, MIME_TEXT, ByteArray(0))
                    return
                }
                val body = response.body.bytes()
                val effectiveUrl = response.request.url.toString()

                if (isPlaylist(body)) {
                    val rewritten = rewritePlaylist(String(body, Charsets.UTF_8), effectiveUrl, referer)
                    respond(socket, 200, Senshi.MIME_HLS, rewritten.toByteArray(Charsets.UTF_8), isHead)
                } else {
                    respond(socket, 200, MIME_TS, body, isHead)
                }
            }
        } catch (_: Exception) {
            // Dropped connection or upstream failure: the socket just closes.
            return
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun rewritePlaylist(playlist: String, baseUrl: String, referer: String): String = buildString {
        playlist.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> append('\n')

                trimmed.startsWith(MEDIA_TAG) -> append(
                    MEDIA_URI_REGEX.replace(line) { match ->
                        "URI=\"${relay(resolve(match.groupValues[1], baseUrl), referer)}\""
                    },
                ).append('\n')

                trimmed.startsWith(KEY_TAG) -> append(
                    MEDIA_URI_REGEX.replace(line) { match ->
                        "URI=\"${relay(resolve(match.groupValues[1], baseUrl), referer)}\""
                    },
                ).append('\n')

                trimmed.startsWith("#") -> append(line).append('\n')

                else -> append(relay(resolve(trimmed, baseUrl), referer)).append('\n')
            }
        }
    }

    private fun resolve(uri: String, baseUrl: String): String = baseUrl.toHttpUrlOrNull()?.resolve(uri)?.toString() ?: uri

    private fun isPlaylist(body: ByteArray): Boolean {
        val head = body.decodeToString(0, minOf(body.size, 512)).trimStart('﻿', ' ', '\n', '\r', '\t')
        if (head.startsWith("#EXTM3U") || head.startsWith("#EXT-X-")) return true
        // The vidcloud CDN encrypts every playlist (`EM3U8v1:` envelope), which
        // is opaque base64 until decrypted — detect by shape, not by prefix.
        val compact = head.filterNot { it.isWhitespace() }
        return compact.startsWith(Senshi.EM3U8_PREFIX) ||
            (body.size > 64 && compact.length >= 64 && compact.all { it in BASE64_CHARS })
    }

    private fun respond(socket: Socket, status: Int, mime: String, body: ByteArray, headOnly: Boolean = false) {
        val out: OutputStream = socket.getOutputStream()
        val reason = if (status == 200) "OK" else "Upstream Error"
        out.write("HTTP/1.1 $status $reason\r\n".toByteArray())
        out.write("Content-Type: $mime\r\n".toByteArray())
        out.write("Content-Length: ${body.size}\r\n".toByteArray())
        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(value: String): String? = runCatching {
        String(Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val LOOPBACK = "http://127.0.0.1"
        const val t = "/senshi-t"
        const val s = "/senshi"
        const val MIME_TS = "video/mp2t"
        const val MIME_TEXT = "text/plain"
        const val MEDIA_TAG = "#EXT-X-MEDIA:"
        const val KEY_TAG = "#EXT-X-KEY:"
        const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val MEDIA_URI_REGEX = Regex("""URI="([^"]+)"""")
    }
}

// ================================ DTOs ====================================

@Serializable
data class SenshiFilterRequestDto(
    val page: Int? = null,
    val limit: Int? = null,
    val search: String? = null,
    val sort: String? = null,
    val type: String? = null,
    val status: String? = null,
    val season: String? = null,
    val language: String? = null,
    val genres: List<String>? = null,
    val year: Int? = null,
)

fun buildFilterRequestJson(json: Json, page: Int, query: String, filters: AnimeFilterList): String {
    val genres = filters.filterIsInstance<Filters.GenreFilter>().firstOrNull()?.getSelectedValues().orEmpty()
    val dto = SenshiFilterRequestDto(
        page = page,
        limit = 24,
        search = query.ifBlank { null },
        sort = filters.filterIsInstance<Filters.SortFilter>().firstOrNull()?.toUriPart(),
        type = filters.filterIsInstance<Filters.TypeFilter>().firstOrNull()?.toUriPart()?.ifBlank { null },
        status = filters.filterIsInstance<Filters.StatusFilter>().firstOrNull()?.toUriPart()?.ifBlank { null },
        season = filters.filterIsInstance<Filters.SeasonFilter>().firstOrNull()?.toUriPart()?.ifBlank { null },
        language = filters.filterIsInstance<Filters.LanguageFilter>().firstOrNull()?.toUriPart()?.ifBlank { null },
        genres = genres.ifEmpty { null },
        year = filters.filterIsInstance<Filters.YearFilter>().firstOrNull()?.state?.toIntOrNull(),
    )
    return json.encodeToString(dto)
}

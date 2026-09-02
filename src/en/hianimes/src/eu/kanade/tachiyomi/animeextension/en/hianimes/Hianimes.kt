package eu.kanade.tachiyomi.animeextension.en.hianimes

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class Hianimes : Source() {

    override val name = "Hianimes"
    override val baseUrl = "https://hianimes.se"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://animehot.cc/api"

    override val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val m3u8Integration by lazy { M3u8Integration(client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularAnime(page: Int): AnimesPage = parseAnimePage(getJson("$apiUrl/anime/popular?page=$page&limit=20"))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = parseAnimePage(getJson("$apiUrl/latest/anime?page=$page&limit=20"))

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val type = filters.filterIsInstance<Filters.TypeFilter>().firstOrNull()?.toUriPart().orEmpty()
        val status = filters.filterIsInstance<Filters.StatusFilter>().firstOrNull()?.toUriPart().orEmpty()
        val genres = filters.filterIsInstance<Filters.GenreFilter>().firstOrNull()?.getSelectedValues().orEmpty()

        // A keyword only works through POST /api/search, which accepts no filters and returns
        // every match at once; the site itself then narrows and paginates that array in the
        // browser, so mirror that. Without a keyword, POST /api/filter does both server-side.
        return if (query.isNotBlank()) {
            searchByKeyword(page, query, type, status, genres)
        } else {
            filterAnime(page, type, status, genres)
        }
    }

    private suspend fun searchByKeyword(
        page: Int,
        query: String,
        type: String,
        status: String,
        genres: List<String>,
    ): AnimesPage {
        val body = buildJsonObject { put("title", query) }
        val results = runCatching {
            postJson("$apiUrl/search", body).jsonArray
        }.getOrNull() ?: return AnimesPage(emptyList(), false)

        val matches = results.map { it.jsonObject }.filter { anime ->
            val animeGenres = anime.array("genres").map { it.jsonPrimitive.content.lowercase() }
            (type.isBlank() || anime.string("Type").equals(type, ignoreCase = true)) &&
                (status.isBlank() || anime.string("Status").equals(status, ignoreCase = true)) &&
                genres.all { it.lowercase() in animeGenres }
        }

        val fromIndex = (page - 1) * PAGE_SIZE
        if (fromIndex >= matches.size) return AnimesPage(emptyList(), false)
        val toIndex = minOf(fromIndex + PAGE_SIZE, matches.size)
        return AnimesPage(
            matches.subList(fromIndex, toIndex).mapNotNull(::parseAnime),
            toIndex < matches.size,
        )
    }

    private suspend fun filterAnime(page: Int, type: String, status: String, genres: List<String>): AnimesPage {
        if (type.isBlank() && status.isBlank() && genres.isEmpty()) return getPopularAnime(page)

        val body = buildJsonObject {
            if (type.isNotBlank()) put("type", type)
            if (status.isNotBlank()) put("status", status)
            if (genres.isNotEmpty()) {
                putJsonArray("genre") { genres.forEach(::add) }
            }
            put("page", page)
            put("limit", PAGE_SIZE)
        }
        val root = runCatching {
            postJson("$apiUrl/filter", body).jsonObject
        }.getOrNull() ?: return AnimesPage(emptyList(), false)

        val animes = root.array("results").mapNotNull { parseAnime(it.jsonObject) }
        val totalPages = root.int("totalPages") ?: page
        return AnimesPage(animes, page < totalPages)
    }

    override fun getFilterList() = AnimeFilterList(
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.GenreFilter(),
    )

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val detail = getAnime(anime.url)
        return anime.apply {
            title = detail.string("title").ifBlank { title }
            thumbnail_url = detail.string("image").ifBlank { thumbnail_url.orEmpty() }
            description = detail.string("synopsis").replace(HTML_TAGS, "").trim()
            genre = detail.array("genres").joinToString(", ") { it.jsonPrimitive.content }
            status = when (detail.string("Status").lowercase()) {
                "currently airing", "releasing", "ongoing" -> SAnime.ONGOING
                "finished airing", "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            author = detail.string("Producers").ifBlank { null }
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = getAnime(anime.url).array("episodes")
        return episodes.mapNotNull { item ->
            val episode = item.jsonObject
            val number = episode["episodeNumber"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return@mapNotNull null
            SEpisode.create().apply {
                url = "${anime.url}#ep=$number"
                name = episode.string("title").ifBlank { "Episode $number" }
                episode_number = number
                val links = episode.objectOrNull("link")
                val hasSub = links?.array("sub")?.isNotEmpty() == true
                val hasDub = links?.array("dub")?.isNotEmpty() == true
                scanlator = when {
                    hasSub && hasDub -> "Sub / Dub"
                    hasDub -> "Dub"
                    hasSub -> "Sub"
                    else -> null
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeUrl = episode.url.substringBefore("#")
        val episodeNumber = episode.url.substringAfter("#ep=").toFloatOrNull() ?: return emptyList()
        val target = getAnime(animeUrl).array("episodes").firstOrNull {
            it.jsonObject["episodeNumber"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() == episodeNumber
        }?.jsonObject ?: return emptyList()
        val links = target.objectOrNull("link") ?: return emptyList()

        // One hoster per provider, with its SUB and DUB streams nested inside it,
        // instead of a flat provider-times-audio list.
        val byProvider = linkedMapOf<String, MutableList<String>>()
        listOf("sub" to AUDIO_SUB, "dub" to AUDIO_DUB).forEach { (key, audio) ->
            links.array(key).forEach { source ->
                val url = source.jsonPrimitive.contentOrNull ?: return@forEach
                val label = serverLabel(url.toHttpUrl().host.removePrefix("www."))
                byProvider.getOrPut(label) { mutableListOf() }.add("$audio|$url")
            }
        }

        return byProvider.map { (label, entries) ->
            Hoster(hosterName = label, hosterUrl = entries.joinToString(ENTRY_SEPARATOR))
        }.let(::sortHostersByPreference)
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    private fun serverLabel(host: String) = when {
        host.contains("megaplay") -> "MegaPlay"
        host.contains("zokoanime") -> "ZokoAnime"
        else -> host
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()

        return hoster.hosterUrl.split(ENTRY_SEPARATOR).flatMap { entry ->
            val audio = entry.substringBefore("|", "")
            val embedUrl = entry.substringAfter("|", "")
            if (audio.isBlank() || embedUrl.isBlank()) return@flatMap emptyList()

            // The provider resolvers already tag each quality with the audio type;
            // only the generic fallback needs it applied here.
            when {
                embedUrl.contains("zokoanime.video") -> zokoVideos(embedUrl, audio)
                embedUrl.contains("megaplay") -> megaPlayVideos(embedUrl, audio)
                else -> universalExtractor
                    .videosFromUrl(embedUrl, embedHeaders, prefix = "${hoster.hosterName} - ")
                    .map { video -> video.withAudioTag(audio, embedHeaders) }
            }
        }.sortVideos()
    }

    private fun Video.withAudioTag(audio: String, fallbackHeaders: Headers) = Video(
        videoUrl = videoUrl,
        videoTitle = "$videoTitle [$audio]",
        headers = headers ?: fallbackHeaders,
        resolution = resolution,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    )

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefAudio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = listOf("MegaPlay", "ZokoAnime"),
            entryValues = listOf("megaplay", "zokoanime"),
        )
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio",
            summary = "%s",
            entries = listOf("Subbed", "Dubbed"),
            entryValues = listOf("SUB", "DUB"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
    }

    private suspend fun zokoVideos(embedUrl: String, audio: String): List<Video> {
        val page = client.newCall(GET(embedUrl, headers)).execute().body.string()
        val encrypted = ZOKO_PAYLOAD.find(page)?.groupValues?.get(1) ?: return emptyList()
        val decrypted = runCatching { decodeZokoPayload(encrypted) }.getOrNull() ?: return emptyList()
        val source = json.parseToJsonElement(decrypted).jsonObject.string("src")
        if (!source.startsWith("https://") && !source.startsWith("http://")) return emptyList()
        return playlistUtils.extractFromHls(
            playlistUrl = source,
            referer = embedUrl,
            videoNameGen = { quality -> "$quality [$audio]" },
        )
    }

    private suspend fun megaPlayVideos(embedUrl: String, audio: String): List<Video> {
        val playerHeaders = headers.newBuilder().set("Referer", embedUrl).build()
        val page = client.newCall(GET(embedUrl, playerHeaders)).execute().body.string()
        val playerId = MEGAPLAY_ID.find(page)?.groupValues?.get(1) ?: return emptyList()
        val origin = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }

        val sourceHeaders = playerHeaders.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
        val sourcesJson = client.newCall(GET("$origin/stream/getSources?id=$playerId", sourceHeaders))
            .execute().body.string()
        val root = runCatching { json.parseToJsonElement(sourcesJson).jsonObject }.getOrNull() ?: return emptyList()

        val master = root.objectOrNull("sources")?.string("file").orEmpty()
        if (!master.startsWith("http")) return emptyList()

        val subtitles = root.array("tracks").mapNotNull { item ->
            val track = item.jsonObject
            if (track.string("kind") != "captions") return@mapNotNull null
            val file = track.string("file").ifBlank { return@mapNotNull null }
            Track(file, track.string("label").ifBlank { "Unknown" })
        }

        // MegaPlay segments carry fake .jpg/.html/.js extensions; route through the local
        // M3U8 proxy so ExoPlayer receives a clean playlist it will actually decode.
        val streamHeaders = headers.newBuilder().set("Referer", "$origin/").build()
        val videos = playlistUtils.extractFromHls(
            playlistUrl = master,
            referer = "$origin/",
            masterHeaders = streamHeaders,
            videoHeaders = streamHeaders,
            videoNameGen = { quality -> "$quality [$audio]" },
            subtitleList = subtitles,
        )
        return m3u8Integration.processVideoList(videos)
    }

    private fun decodeZokoPayload(payload: String): String {
        val encoded = Base64.decode(payload, Base64.DEFAULT)
        return encoded.mapIndexed { index, byte ->
            (byte.toInt() xor ZOKO_KEY[index % ZOKO_KEY.length].code).toChar()
        }.joinToString("")
    }

    private suspend fun getAnime(animeUrl: String): JsonObject = getJson("$apiUrl${animeUrl.substringBefore("#")}").jsonObject["anime"]!!.jsonObject

    private suspend fun postJson(url: String, body: JsonObject) = json.parseToJsonElement(
        client.newCall(POST(url, headers, body.toString().toRequestBody(JSON_MEDIA_TYPE))).execute().body.string(),
    )

    private suspend fun getJson(url: String) = json.parseToJsonElement(client.newCall(GET(url, headers)).execute().body.string()).jsonObject

    private fun parseAnime(anime: JsonObject): SAnime? {
        val slug = anime.string("slug")
            .ifBlank { anime.array("slugs").firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (slug.isBlank()) return null
        return SAnime.create().apply {
            title = anime.string("title")
            thumbnail_url = anime.string("image")
            setUrlWithoutDomain("/anime/$slug")
            fetch_type = FetchType.Episodes
        }
    }

    private fun parseAnimePage(root: JsonObject): AnimesPage {
        val animes = root.array("animes").mapNotNull { parseAnime(it.jsonObject) }

        // `/anime/popular` nests pagination under `meta`; `/latest/anime` returns it at the root.
        val meta = root.objectOrNull("meta") ?: root
        val page = meta.int("page") ?: 1
        val totalPages = meta.int("totalPages") ?: page
        return AnimesPage(animes, page < totalPages)
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject

    private companion object {
        const val AUDIO_SUB = "SUB"
        const val AUDIO_DUB = "DUB"
        const val ENTRY_SEPARATOR = ";;"
        const val PAGE_SIZE = 20
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val HTML_TAGS = Regex("<[^>]*>")
        val ZOKO_PAYLOAD = Regex("""window\.__P="([^"]+)"""")
        val MEGAPLAY_ID = Regex("""data-id="(\d+)"""")
        const val ZOKO_KEY = "otaku-embed-v1"

        const val PREF_SERVER_KEY = "preferred_server"
        const val PREF_SERVER_DEFAULT = "megaplay"
        const val PREF_AUDIO_KEY = "preferred_audio"
        const val PREF_AUDIO_DEFAULT = "SUB"
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
    }
}

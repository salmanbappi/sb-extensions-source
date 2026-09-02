package eu.kanade.tachiyomi.animeextension.en.hianimes

import android.util.Base64
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import extensions.utils.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl

class Hianimes : Source() {

    override val name = "Hianimes"
    override val baseUrl = "https://hianimes.se"
    override val lang = "en"
    override val supportsLatest = true

    private val apiUrl = "https://animehot.cc/api"
    private val json = Json { ignoreUnknownKeys = true }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val m3u8Integration by lazy { M3u8Integration(client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularAnime(page: Int): AnimesPage = parseAnimePage(getJson("$apiUrl/anime/popular?page=$page&limit=20"))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = parseAnimePage(getJson("$apiUrl/latest/anime?page=$page&limit=20"))

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val type = filters.filterIsInstance<AnimeFilter.Select<String>>().firstOrNull()?.let { TYPE_VALUES[it.state] }
        val url = "$apiUrl/anime".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "20")
            if (query.isNotBlank()) addQueryParameter("query", query)
            if (!type.isNullOrBlank()) addQueryParameter("type", type)
        }.build()
        return parseAnimePage(getJson(url.toString()))
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Select("Type", TYPE_NAMES),
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

        return listOf("sub" to "SUB", "dub" to "DUB").flatMap { (key, audio) ->
            links.array(key).mapNotNull { source ->
                val url = source.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                val host = url.toHttpUrl().host.removePrefix("www.")
                Hoster(hosterName = "$host [$audio]", hosterUrl = "$audio|$url")
            }
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val (audio, embedUrl) = hoster.hosterUrl.split("|", limit = 2).let {
            it.getOrNull(0) to it.getOrNull(1)
        }
        if (audio == null || embedUrl.isNullOrBlank()) return emptyList()
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val videos = when {
            embedUrl.contains("zokoanime.video") -> zokoVideos(embedUrl, audio)
            embedUrl.contains("megaplay") -> megaPlayVideos(embedUrl, audio)
            else -> universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "${hoster.hosterName} - ")
        }
        return videos.map { video ->
            Video(
                videoUrl = video.videoUrl,
                videoTitle = "${video.videoTitle} [$audio]",
                headers = video.headers ?: embedHeaders,
                resolution = video.resolution,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }.sortedByDescending { it.resolution ?: 0 }
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

    private suspend fun getJson(url: String) = json.parseToJsonElement(client.newCall(GET(url, headers)).execute().body.string()).jsonObject

    private fun parseAnimePage(root: JsonObject): AnimesPage {
        val animes = root.array("animes").mapNotNull { item ->
            val anime = item.jsonObject
            val slug = anime.string("slug").ifBlank { anime.array("slugs").firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty() }
            if (slug.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                title = anime.string("title")
                thumbnail_url = anime.string("image")
                setUrlWithoutDomain("/anime/$slug")
                fetch_type = FetchType.Episodes
            }
        }
        val hasNext = root.objectOrNull("meta")?.get("page")?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { page ->
            page < (root.objectOrNull("meta")?.get("totalPages")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: page)
        } ?: (root["page"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1) <
            (root["totalPages"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1)
        return AnimesPage(animes, hasNext)
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.objectOrNull(key: String) = this[key] as? JsonObject

    private companion object {
        val TYPE_NAMES = arrayOf("All", "TV", "Movie", "OVA", "ONA", "Special")
        val TYPE_VALUES = arrayOf("", "TV", "Movie", "OVA", "ONA", "Special")
        val HTML_TAGS = Regex("<[^>]*>")
        val ZOKO_PAYLOAD = Regex("window\\.__P=\\\"([^\\\"]+)\\\"")
        val MEGAPLAY_ID = Regex("data-id=\\\"(\\d+)\\\"")
        const val ZOKO_KEY = "otaku-embed-v1"
    }
}

package eu.kanade.tachiyomi.animeextension.en.anilab

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

@Serializable
data class HomeResponseDto(
    val featured: PostDetailsDto? = null,
    val sections: List<SectionDto>? = null,
)

@Serializable
data class SectionDto(
    val name: String? = null,
    val posts: List<PostDto>? = null,
)

@Serializable
data class PostsResponseDto(
    val posts: List<PostDto>? = null,
)

@Serializable
data class PostDto(
    val id: Long? = null,
    val poster: String? = null,
    val age: String? = null,
)

@Serializable
data class PostDetailsDto(
    val id: Long? = null,
    val type: String? = null,
    val title: String? = null,
    val poster: String? = null,
    val overview: String? = null,
    val status: String? = null,
    val runtime: String? = null,
    val premiered: String? = null,
    val rating: String? = null,
    val age: String? = null,
    val score: Float? = null,
    val genres: String? = null,
)

@Serializable
data class EpisodesResponseDto(
    val list: List<EpisodeItemDto>? = null,
)

@Serializable
data class EpisodeItemDto(
    val id: String? = null,
    val name: String? = null,
    val number: String? = null,
    val filler: Boolean = false,
)

@Serializable
data class ServersResponseDto(
    val list: List<ServerItemDto>? = null,
)

@Serializable
data class ServerItemDto(
    val id: String? = null,
    val name: String? = null,
    val lang: String? = null,
)

@Serializable
data class IframeDto(
    val link: String? = null,
)

class AniLab : Source() {

    override val name = "AniLab"

    override val baseUrl = "https://anilab2.amdapi.click/api"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("User-Agent", "okhttp/4.9.2")
        .set("Accept", "application/json")

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/home", headers)
        } else {
            GET("$baseUrl/category?id=1&page=$page", headers)
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val requestUrl = response.request.url.toString()
        val posts = if (requestUrl.endsWith("/home")) {
            val homeData = response.parseAs<HomeResponseDto>()
            val items = mutableListOf<PostDto>()
            homeData.sections?.forEach { section ->
                section.posts?.let { items.addAll(it) }
            }
            items.distinctBy { it.id }
        } else {
            val data = response.parseAs<PostsResponseDto>()
            data.posts ?: emptyList()
        }

        return parsePosts(posts, hasNextPage = posts.isNotEmpty())
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val data = response.parseAs<PostsResponseDto>()
        val posts = data.posts ?: emptyList()
        return parsePosts(posts, hasNextPage = posts.isNotEmpty())
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val categoryFilter = filters.filterIsInstance<Filters.CategoryFilter>().firstOrNull()
        return when {
            query.isNotBlank() -> {
                val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
                GET("$baseUrl/search?query=$encodedQuery&page=$page", headers)
            }
            categoryFilter != null && !categoryFilter.isDefault() -> {
                GET("$baseUrl/category?id=${categoryFilter.toUriPart()}&page=$page", headers)
            }
            else -> {
                GET("$baseUrl/latest?page=$page", headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<PostsResponseDto>()
        val posts = data.posts ?: emptyList()
        return parsePosts(posts, hasNextPage = posts.isNotEmpty())
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.CategoryFilter(),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl/post?id=${anime.url}", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = response.parseAs<PostDetailsDto>()
        return SAnime.create().apply {
            title = data.title ?: ""
            thumbnail_url = data.poster
            description = data.overview
            genre = data.genres
            status = when {
                data.status?.contains("airing", ignoreCase = true) == true -> SAnime.ONGOING
                data.status?.contains("finished", ignoreCase = true) == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            author = data.type
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("https://play.anidb.app/api/anime/${anime.url}/episodes", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = response.parseAs<EpisodesResponseDto>()
        val episodes = data.list ?: emptyList()

        val minEpNumber = episodes.minOfOrNull { it.number?.toFloatOrNull() ?: 1f } ?: 1f
        val offset = if (minEpNumber > 1f) minEpNumber - 1f else 0f

        val numberingMode = preferences.getString(PREF_EP_RENUMBER_KEY, PREF_EP_RENUMBER_DEFAULT) ?: PREF_EP_RENUMBER_DEFAULT

        return episodes.mapNotNull { ep ->
            val epId = ep.id ?: return@mapNotNull null
            val rawNum = ep.number?.toFloatOrNull() ?: 1f
            val adjustedNum = if (offset > 0f) rawNum - offset else rawNum

            val epNumber = when (numberingMode) {
                "absolute" -> rawNum
                else -> adjustedNum
            }

            val baseName = when (numberingMode) {
                "season" -> "Episode ${adjustedNum.toInt()}"

                "absolute" -> ep.name ?: "Episode ${rawNum.toInt()}"

                else -> { // "both"
                    if (offset > 0f) {
                        "Episode ${adjustedNum.toInt()} (#${rawNum.toInt()})"
                    } else {
                        ep.name ?: "Episode ${rawNum.toInt()}"
                    }
                }
            }

            SEpisode.create().apply {
                name = if (ep.filler) "$baseName (Filler)" else baseName
                episode_number = epNumber
                url = epId
            }
        }.reversed()
    }

    // ============================== Hosters (2-Tier Folders) ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val req = GET("https://play.anidb.app/api/episode/${episode.url}/servers", headers)
        val res = client.newCall(req).execute()
        val data = res.parseAs<ServersResponseDto>()
        val servers = data.list ?: emptyList()

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT) ?: PREF_LANG_DEFAULT

        return servers.mapIndexed { index, s ->
            val serverName = s.name ?: "Server #${index + 1}"
            Hoster(
                hosterName = serverName,
                hosterUrl = s.id ?: "",
            )
        }.sortedWith(
            compareByDescending<Hoster> { it.hosterName.contains(prefLang, ignoreCase = true) }
                .thenByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
                .thenBy { if (it.hosterName.contains("Server #1")) 1 else 2 },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val serverId = hoster.hosterUrl
        if (serverId.isBlank()) return emptyList()

        val iframeReq = GET(
            "https://play.anidb.app/api/episode/$serverId/iframe",
            headers.newBuilder()
                .set("Referer", "https://play.app/")
                .set("X-Requested-With", "PLAY")
                .build(),
        )
        val iframeResp = client.newCall(iframeReq).execute()
        val linkJson = iframeResp.parseAs<IframeDto>()
        val m3u8Url = linkJson.link ?: return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl = m3u8Url,
            referer = "https://play.app/",
            masterHeaders = headers,
            videoHeaders = headers,
            videoNameGen = { quality -> quality },
        ).sortVideos()
    }

    // ============================ Fallback Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET("https://play.anidb.app/api/episode/${episode.url}/servers", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = response.parseAs<ServersResponseDto>()
        val servers = data.list ?: emptyList()

        return servers.parallelCatchingFlatMapBlocking { server ->
            val serverId = server.id ?: return@parallelCatchingFlatMapBlocking emptyList()
            val iframeReq = GET(
                "https://play.anidb.app/api/episode/$serverId/iframe",
                headers.newBuilder()
                    .set("Referer", "https://play.app/")
                    .set("X-Requested-With", "PLAY")
                    .build(),
            )
            val iframeResp = client.newCall(iframeReq).execute()
            val linkJson = iframeResp.parseAs<IframeDto>()
            val m3u8Url = linkJson.link ?: return@parallelCatchingFlatMapBlocking emptyList()

            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "https://play.app/",
                masterHeaders = headers,
                videoHeaders = headers,
                videoNameGen = { quality -> "${server.name ?: "Server"} - $quality" },
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality) }
                .thenByDescending { getResolution(it.videoTitle) },
        )
    }

    private fun getResolution(title: String): Int {
        val match = Regex("""(\d+)p""").find(title)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_LANG_KEY,
            title = PREF_LANG_TITLE,
            entries = PREF_LANG_ENTRIES,
            entryValues = PREF_LANG_VALUES,
            default = PREF_LANG_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = PREF_SERVER_TITLE,
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_VALUES,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_EP_RENUMBER_KEY,
            title = PREF_EP_RENUMBER_TITLE,
            entries = PREF_EP_RENUMBER_ENTRIES,
            entryValues = PREF_EP_RENUMBER_VALUES,
            default = PREF_EP_RENUMBER_DEFAULT,
            summary = "%s",
        )
    }

    // ============================= Utilities ==============================

    private fun parsePosts(posts: List<PostDto>, hasNextPage: Boolean): AnimesPage {
        val animeList = posts.parallelMapNotNullBlocking { post ->
            val id = post.id ?: return@parallelMapNotNullBlocking null
            runCatching {
                val detailResponse = client.newCall(GET("$baseUrl/post?id=$id", headers)).execute()
                val detail = detailResponse.parseAs<PostDetailsDto>()
                SAnime.create().apply {
                    title = detail.title ?: "Anime #$id"
                    thumbnail_url = detail.poster ?: post.poster
                    url = id.toString()
                }
            }.getOrElse {
                SAnime.create().apply {
                    title = "Anime #$id"
                    thumbnail_url = post.poster
                    url = id.toString()
                }
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "360p")

        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred audio language"
        private const val PREF_LANG_DEFAULT = "sub"
        private val PREF_LANG_ENTRIES = listOf("SUB", "DUB")
        private val PREF_LANG_VALUES = listOf("sub", "dub")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "Server #1"
        private val PREF_SERVER_ENTRIES = listOf("Server #1", "Server #2")
        private val PREF_SERVER_VALUES = listOf("Server #1", "Server #2")

        private const val PREF_EP_RENUMBER_KEY = "ep_renumber_mode"
        private const val PREF_EP_RENUMBER_TITLE = "Episode numbering for sequels"
        private const val PREF_EP_RENUMBER_DEFAULT = "both"
        private val PREF_EP_RENUMBER_ENTRIES = listOf(
            "Season numbering with absolute (Episode 1 (#73))",
            "Season numbering only (Episode 1)",
            "Absolute numbering (Episode 73)",
        )
        private val PREF_EP_RENUMBER_VALUES = listOf("both", "season", "absolute")
    }
}

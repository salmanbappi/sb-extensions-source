package eu.kanade.tachiyomi.animeextension.all.lunar

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.luluextractor.LuluExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.parseAs
import extensions.utils.toJsonString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class Lunar : Source() {

    override val name = "Lunar"
    override val baseUrl = "https://lunarx.to"
    override val lang = "all"
    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val luluExtractor by lazy { LuluExtractor(client, headers) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== POPULAR ANIME ==============================

    override fun popularAnimeRequest(page: Int): Request = GET("$API_BASE/api/animes/search?query=", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val searchData = response.parseAs<SearchResponse>(json)
        val allAnime = searchData.animes.map { item ->
            SAnime.create().apply {
                url = "/anime/${item.slug}"
                title = item.title.orEmpty()
                thumbnail_url = item.poster_url
            }
        }
        return paginateList(allAnime, 1)
    }

    // ============================== LATEST UPDATES ==============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/anime/latest-aired?limit=25", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val latestData = response.parseAs<LatestResponse>(json)
        val animeList = latestData.data.map { item ->
            SAnime.create().apply {
                url = "/anime/${item.anime_id}"
                title = item.title.orEmpty()
                thumbnail_url = item.cover
            }
        }
        return AnimesPage(animeList, latestData.has_more)
    }

    // ============================== SEARCH ANIME ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$API_BASE/api/animes/search?query=$encodedQuery", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val searchData = response.parseAs<SearchResponse>(json)
        val animes = searchData.animes.map { item ->
            SAnime.create().apply {
                url = "/anime/${item.slug}"
                title = item.title.orEmpty()
                thumbnail_url = item.poster_url
            }
        }
        return AnimesPage(animes, false)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // ============================== ANIME DETAILS ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = extractSlug(anime.url)
        return GET("$API3_BASE/api/anime/$slug", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        runCatching {
            val res = response.parseAs<AnimeDetailResponse>(json)
            val item = res.data.firstOrNull()
            if (item != null) {
                anime.title = item.title.orEmpty()
                anime.thumbnail_url = item.poster_url
                anime.description = item.description
                anime.genre = item.genres.joinToString(", ")
                anime.status = when {
                    item.end_year != null -> SAnime.COMPLETED
                    else -> SAnime.ONGOING
                }
            }
        }
        anime.initialized = true
        return anime
    }

    // ============================== EPISODE LIST ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = extractSlug(anime.url)
        return GET("$API_BASE/api/animes/seasons?slug=$slug", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val slug = response.request.url.queryParameter("slug") ?: return emptyList()
        val seasonsCount = runCatching {
            val seasonsData = response.parseAs<SeasonsResponse>(json)
            maxOf(1, seasonsData.seasons)
        }.getOrDefault(1)

        val episodes = mutableListOf<SEpisode>()
        var globalEpisodeNum = 1F

        for (season in 1..seasonsCount) {
            val seasonReq = GET("$API_BASE/api/animes/episodes?slug=$slug&season=$season", headers)
            val epCount = runCatching {
                val seasonResp = client.newCall(seasonReq).execute()
                val countData = seasonResp.parseAs<EpisodesCountResponse>(json)
                countData.episodes
            }.getOrDefault(0)

            for (ep in 1..epCount) {
                val epData = EpisodeData(slug = slug, season = season, episode = ep)
                episodes.add(
                    SEpisode.create().apply {
                        url = epData.toJsonString(json)
                        name = if (seasonsCount > 1) "Season $season Episode $ep" else "Episode $ep"
                        episode_number = globalEpisodeNum++
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // ============================== VIDEO LIST ==============================

    override fun videoListRequest(episode: SEpisode): Request {
        val data = runCatching {
            episode.url.parseAs<EpisodeData>(json)
        }.getOrDefault(EpisodeData(slug = extractSlug(episode.url), season = 1, episode = 1))

        return GET("$API_BASE/api/stream?slug=${data.slug}&season=${data.season}&episode=${data.episode}", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val streamData = runCatching {
            response.parseAs<StreamResponse>(json)
        }.getOrNull() ?: return emptyList()

        val hosters = streamData.episodes.flatMap { it.hosters }

        return hosters.parallelCatchingFlatMapBlocking { hosterItem ->
            val hoster = hosterItem.hoster.orEmpty().lowercase()
            val lang = hosterItem.language.orEmpty()
            val uri = hosterItem.redirect_uri.orEmpty()
            if (uri.isBlank()) return@parallelCatchingFlatMapBlocking emptyList()

            val prefix = "[${hoster.uppercase()}${if (lang.isNotBlank()) " - ${lang.uppercase()}" else ""}] "

            when {
                hoster == "vidmoly" || uri.contains("vidmoly") -> {
                    vidMolyExtractor.videosFromUrl(uri, prefix = prefix)
                }

                hoster == "voe" || uri.contains("voe.") -> {
                    voeExtractor.videosFromUrl(uri, prefix = prefix)
                }

                hoster == "filemoon" || uri.contains("filemoon") -> {
                    filemoonExtractor.videosFromUrl(uri, prefix = prefix)
                }

                hoster == "doodstream" || hoster == "dood" || uri.contains("dood") || uri.contains("ds2play") || uri.contains("bysezejataos") -> {
                    doodExtractor.videosFromUrl(uri, quality = "${prefix}DoodStream")
                }

                hoster == "streamtape" || uri.contains("streamtape") -> {
                    streamTapeExtractor.videosFromUrl(uri, quality = "${prefix}StreamTape")
                }

                hoster == "luluvdo" || hoster == "lulu" || uri.contains("luluvdo") -> {
                    luluExtractor.videosFromUrl(uri, prefix = prefix)
                }

                hoster == "streamwish" || uri.contains("streamwish") || uri.contains("wishembed") || uri.contains("swish") -> {
                    streamWishExtractor.videosFromUrl(uri, prefix = prefix)
                }

                hoster == "vidguard" || uri.contains("vidguard") || uri.contains("vgfplay") || uri.contains("vembed") -> {
                    vidGuardExtractor.videosFromUrl(uri, prefix = prefix)
                }

                uri.contains(".m3u8") -> {
                    playlistUtils.extractFromHls(uri, videoNameGen = { q -> prefix + q })
                }

                else -> {
                    universalExtractor.videosFromUrl(uri, headers, prefix = prefix)
                }
            }
        }.sortVideos()
    }

    // ============================== PREFERENCES & SORTING ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredHoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT).orEmpty().lowercase()
        val preferredLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT).orEmpty().lowercase()
        val preferredQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT).orEmpty()

        return sortedWith(
            compareByDescending<Video> { video ->
                val q = video.videoTitle.lowercase()
                when {
                    preferredHoster.isNotBlank() && q.contains(preferredHoster) -> 1
                    else -> 0
                }
            }.thenByDescending { video ->
                val q = video.videoTitle.lowercase()
                when {
                    preferredLang.isNotBlank() && preferredLang != "all" && q.contains(preferredLang) -> 1
                    else -> 0
                }
            }.thenByDescending { video ->
                when {
                    preferredQuality.isNotBlank() && video.videoTitle.contains(preferredQuality) -> 1
                    else -> 0
                }
            },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Preferred Server / Hoster"
            entries = arrayOf("Vidmoly", "VOE", "Filemoon", "DoodStream", "StreamTape", "LuluStream", "StreamWish", "Vidguard")
            entryValues = arrayOf("vidmoly", "voe", "filemoon", "dood", "streamtape", "lulu", "streamwish", "vidguard")
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Preferred Language"
            entries = arrayOf("All", "German Dub (GER-DUB)", "German Sub (GER-SUB)", "English Sub (ENG-SUB)")
            entryValues = arrayOf("all", "ger-dub", "ger-sub", "eng-sub")
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080p", "720p", "480p", "360p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== UTILITIES & DTOS ==============================

    private fun extractSlug(url: String): String = url.removePrefix("/anime/")
        .removePrefix("/")
        .substringBefore("?")
        .substringBefore("#")

    private fun paginateList(list: List<SAnime>, page: Int, perPage: Int = 25): AnimesPage {
        val startIdx = (page - 1) * perPage
        val endIdx = minOf(startIdx + perPage, list.size)
        if (startIdx >= list.size) return AnimesPage(emptyList(), false)
        val sublist = list.subList(startIdx, endIdx)
        return AnimesPage(sublist, endIdx < list.size)
    }

    companion object {
        private const val API_BASE = "https://api.lunarx.to"
        private const val API3_BASE = "https://api3.lunarx.to"

        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_DEFAULT = "voe"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "all"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
    }

    @Serializable
    data class LatestResponse(
        val data: List<LatestAnimeItem> = emptyList(),
        val has_more: Boolean = false,
        val next_cursor: String? = null,
    )

    @Serializable
    data class LatestAnimeItem(
        val anime_id: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val format: String? = null,
        val subbed: Int = 0,
        val dubbed: Int = 0,
        val episode_number: Int = 0,
        val episode_title: String? = null,
        val aired: String? = null,
    )

    @Serializable
    data class SearchResponse(
        val animes: List<AnimeItem> = emptyList(),
        val message: String? = null,
    )

    @Serializable
    data class AnimeItem(
        val slug: String? = null,
        val title: String? = null,
        val poster_url: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val alt_titles: List<String> = emptyList(),
        val start_year: Int? = null,
        val end_year: Int? = null,
        val tmdb_id: String? = null,
    )

    @Serializable
    data class AnimeDetailResponse(
        val data: List<AnimeDetailItem> = emptyList(),
    )

    @Serializable
    data class AnimeDetailItem(
        val slug: String? = null,
        val title: String? = null,
        val poster_url: String? = null,
        val description: String? = null,
        val genres: List<String> = emptyList(),
        val alt_titles: List<String> = emptyList(),
        val start_year: Int? = null,
        val end_year: Int? = null,
        val movie: Boolean = false,
    )

    @Serializable
    data class SeasonsResponse(
        val seasons: Int = 1,
    )

    @Serializable
    data class EpisodesCountResponse(
        val episodes: Int = 0,
    )

    @Serializable
    data class StreamResponse(
        val episodes: List<StreamEpisodeItem> = emptyList(),
        val message: String? = null,
    )

    @Serializable
    data class StreamEpisodeItem(
        val episode: Int = 1,
        val season: Int = 1,
        val title: String? = null,
        val hosters: List<HosterItem> = emptyList(),
    )

    @Serializable
    data class HosterItem(
        val hoster: String? = null,
        val language: String? = null,
        val redirect_uri: String? = null,
        val owned: Boolean = false,
    )

    @Serializable
    data class EpisodeData(
        val slug: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )
}

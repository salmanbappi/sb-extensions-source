package eu.kanade.tachiyomi.animeextension.en.anichan

import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import kotlin.time.Duration.Companion.seconds

class Anichan : Source() {

    override val name = "AniChan"

    override val baseUrl = "https://anichan.net"

    override val lang = "en"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$baseUrl/search?sort=POPULARITY_DESC&page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseSearchPage(response, page)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = "$baseUrl/search?sort=START_DATE_DESC&page=$page"
        val response = client.newCall(GET(url, headers)).execute()
        return parseSearchPage(response, page)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }

        var sortApplied = false
        for (filter in filters) {
            when (filter) {
                is Filters.SortFilter -> {
                    val sort = filter.toUriPart()
                    if (sort.isNotBlank()) {
                        urlBuilder.addQueryParameter("sort", sort)
                        sortApplied = true
                    }
                }

                is Filters.FormatFilter -> {
                    val format = filter.toUriPart()
                    if (format.isNotBlank()) {
                        urlBuilder.addQueryParameter("fmt", format)
                    }
                }

                is Filters.StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotBlank()) {
                        urlBuilder.addQueryParameter("status", status)
                    }
                }

                is Filters.SeasonFilter -> {
                    val season = filter.toUriPart()
                    if (season.isNotBlank()) {
                        urlBuilder.addQueryParameter("season", season)
                    }
                }

                is Filters.YearFilter -> {
                    val year = filter.state.trim()
                    if (year.isNotBlank()) {
                        urlBuilder.addQueryParameter("year", year)
                    }
                }

                is Filters.GenreFilter -> {
                    val included = filter.getIncluded()
                    if (included.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genre", included.joinToString(","))
                    }
                }

                else -> {}
            }
        }

        if (!sortApplied && query.isBlank()) {
            urlBuilder.addQueryParameter("sort", "POPULARITY_DESC")
        }

        val response = client.newCall(GET(urlBuilder.build().toString(), headers)).execute()
        return parseSearchPage(response, page)
    }

    private fun parseSearchPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()
        val animeElements = doc.select("a.card[href*=/anime/]")
        val animeList = animeElements.mapNotNull { el ->
            val href = el.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = el.selectFirst("img")
            val titleText = el.selectFirst(".title")?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: return@mapNotNull null
            val thumbnail = img?.attr("src")?.takeIf { it.isNotBlank() }

            SAnime.create().apply {
                title = titleText
                url = href.removePrefix(baseUrl)
                thumbnail_url = thumbnail
                fetch_type = FetchType.Episodes
            }
        }

        val hasNextPage = doc.select("a[href*='page=${page + 1}']").isNotEmpty() || animeElements.size >= 30
        return AnimesPage(animeList, hasNextPage)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val cleanUrl = anime.url.substringBefore("#")
        val response = client.newCall(GET("$baseUrl$cleanUrl", headers)).execute()
        val doc = response.asJsoup()

        val scriptTag = doc.selectFirst("script[type=application/ld+json]")
        val jsonLd = scriptTag?.data()?.let {
            runCatching { json.decodeFromString<JsonLdDto>(it) }.getOrNull()
        }

        return anime.apply {
            if (jsonLd != null) {
                title = jsonLd.name?.takeIf { it.isNotBlank() } ?: title
                thumbnail_url = jsonLd.image ?: thumbnail_url
                genre = jsonLd.genre?.joinToString()
                description = buildString {
                    jsonLd.description?.let {
                        append(it.replace(Regex("<[^>]*>"), "").trim())
                        append("\n\n")
                    }
                    jsonLd.numberOfEpisodes?.let { append("Episodes: $it\n") }
                    jsonLd.datePublished?.let { append("Year: $it\n") }
                    jsonLd.aggregateRating?.ratingValue?.let { append("Rating: $it/10\n") }
                }.trim().takeIf { it.isNotBlank() }
            } else {
                val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore("—")?.substringBefore("·")?.trim()
                if (!ogTitle.isNullOrBlank()) {
                    title = ogTitle
                }
                thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: thumbnail_url
                description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val cleanUrl = anime.url.substringBefore("#")
        val anilistId = cleanUrl.removePrefix("/anime/").substringBefore("/")
        if (anilistId.isBlank()) return emptyList()

        val response = client.newCall(GET("$baseUrl/api/watch/episodes?anilistId=$anilistId", headers)).execute()
        val dto = response.parseAs<EpisodesResponseDto>(json)
        val count = dto.episodes ?: 0
        if (count <= 0) return emptyList()

        val dubAvail = dto.dubAvailable == true

        return (1..count).map { ep ->
            SEpisode.create().apply {
                name = "Episode $ep"
                episode_number = ep.toFloat()
                url = "$cleanUrl#id=$anilistId&ep=$ep"
                scanlator = if (dubAvail) "Sub & Dub" else "Sub"
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val fragment = episode.url.substringAfter("#", "")
        val params = fragment.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        val anilistId = params["id"]?.takeIf { it.isNotBlank() }
            ?: episode.url.removePrefix("/anime/").substringBefore("/").substringBefore("#")
        val ep = params["ep"]?.takeIf { it.isNotBlank() }
            ?: if (episode.episode_number % 1f == 0f) episode.episode_number.toInt().toString() else "${episode.episode_number}"

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedTypes = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()

        val categories = listOf("sub", "dub").filter { it.uppercase() !in excludedTypes }

        val hosterMap = mutableMapOf<String, MutableList<HosterAudioSource>>()

        for (cat in categories) {
            val url = "$baseUrl/api/watch/servers?anilistId=$anilistId&ep=$ep&category=$cat"
            val resp = runCatching { client.newCall(GET(url, headers)).execute() }.getOrNull() ?: continue
            val dto = runCatching { resp.parseAs<ServersResponseDto>(json) }.getOrNull() ?: continue

            for (server in dto.servers ?: emptyList()) {
                val rawName = server.label?.trim() ?: server.name?.trim() ?: "Server"
                val cleanName = rawName.replace("★", "").trim()
                if (cleanName in excludedServers) continue

                val audioLabel = if (cat.equals("dub", ignoreCase = true)) "Dub" else "Sub"
                val source = HosterAudioSource(
                    audioType = audioLabel,
                    type = server.type ?: "hls",
                    streamUrl = server.stream,
                    embedUrl = server.embed,
                    subtitles = server.subtitles ?: emptyList(),
                )
                hosterMap.getOrPut(cleanName) { mutableListOf() }.add(source)
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return hosterMap.map { (serverName, sources) ->
            Hoster(
                hosterName = serverName,
                hosterUrl = json.encodeToString(sources),
            )
        }.sortedWith(
            compareByDescending { it.hosterName.contains(prefServer, ignoreCase = true) },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val sources = runCatching {
            json.decodeFromString<List<HosterAudioSource>>(hoster.hosterUrl)
        }.getOrElse { emptyList() }

        val videoList = sources.parallelCatchingFlatMap { src ->
            val audio = src.audioType ?: "Sub"
            val subTracks = (src.subtitles ?: emptyList()).mapNotNull { sub ->
                val trackUrl = sub.ass ?: sub.url ?: return@mapNotNull null
                val fullUrl = if (trackUrl.startsWith("/")) "$baseUrl$trackUrl" else trackUrl
                Track(
                    url = fullUrl,
                    lang = sub.lang ?: "Subtitle",
                )
            }

            if (src.type == "hls" && !src.streamUrl.isNullOrBlank()) {
                val fullM3u8Url = if (src.streamUrl.startsWith("/")) "$baseUrl${src.streamUrl}" else src.streamUrl
                val hlsVideos = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = fullM3u8Url,
                        referer = "$baseUrl/",
                        videoNameGen = { quality -> "$quality [$audio]" },
                        subtitleList = subTracks,
                    )
                }.getOrElse { emptyList() }

                if (hlsVideos.isNotEmpty()) {
                    hlsVideos
                } else {
                    listOf(
                        Video(
                            videoUrl = fullM3u8Url,
                            videoTitle = "${hoster.hosterName} [$audio]",
                            headers = headers,
                            subtitleTracks = subTracks,
                        ),
                    )
                }
            } else if (!src.embedUrl.isNullOrBlank()) {
                val embedVideos = runCatching {
                    universalExtractor.videosFromUrl(
                        origRequestUrl = src.embedUrl,
                        origRequestHeader = headers,
                        prefix = "${hoster.hosterName} [$audio]",
                    )
                }.getOrElse { emptyList() }

                embedVideos.map { vid ->
                    Video(
                        videoUrl = vid.videoUrl,
                        videoTitle = vid.videoTitle,
                        headers = vid.headers ?: headers,
                        resolution = vid.resolution,
                        subtitleTracks = vid.subtitleTracks ?: subTracks,
                        audioTracks = vid.audioTracks,
                    )
                }
            } else {
                emptyList()
            }
        }

        return videoList.sortVideos()
    }

    // ============================= Preferences ============================

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefAudio = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefAudio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio Type",
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("sub", "dub"),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("AniChan", "FHD1", "FHD4", "FHD8", "FHD12"),
            entryValues = listOf("AniChan", "FHD1", "FHD4", "FHD8", "FHD12"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from the video list",
            entries = listOf("AniChan", "FHD1", "FHD4", "FHD8", "FHD12"),
            entryValues = listOf("AniChan", "FHD1", "FHD4", "FHD8", "FHD12"),
            default = emptySet(),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Audio Types",
            summary = "Select audio formats to hide",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
            default = emptySet(),
        )
    }

    companion object {
        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = "sub"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "AniChan"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class EpisodesResponseDto(
    val episodes: Int? = null,
    val dubAvailable: Boolean? = null,
)

@Serializable
data class ServersResponseDto(
    val servers: List<ServerItemDto>? = null,
)

@Serializable
data class ServerItemDto(
    val name: String? = null,
    val label: String? = null,
    val host: String? = null,
    val type: String? = null,
    val stream: String? = null,
    val embed: String? = null,
    val subtitles: List<SubtitleItemDto>? = null,
    val audios: List<AudioItemDto>? = null,
    val subType: String? = null,
)

@Serializable
data class SubtitleItemDto(
    val lang: String? = null,
    val url: String? = null,
    val ass: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class AudioItemDto(
    val name: String? = null,
    val lang: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class JsonLdDto(
    val name: String? = null,
    val alternateName: List<String>? = null,
    val url: String? = null,
    val image: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val datePublished: String? = null,
    val numberOfEpisodes: Int? = null,
    val aggregateRating: RatingDto? = null,
)

@Serializable
data class RatingDto(
    val ratingValue: String? = null,
)

@Serializable
data class HosterAudioSource(
    val audioType: String? = null,
    val type: String? = null,
    val streamUrl: String? = null,
    val embedUrl: String? = null,
    val subtitles: List<SubtitleItemDto>? = null,
)

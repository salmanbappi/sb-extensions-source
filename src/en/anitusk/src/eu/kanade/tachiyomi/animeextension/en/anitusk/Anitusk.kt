package eu.kanade.tachiyomi.animeextension.en.anitusk

import android.net.Uri
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class Anitusk :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Anitusk"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val apiBaseUrl: String
        get() = baseUrl.replace("https://", "https://miruro.")

    override val lang = "en"

    override val supportsLatest = true

    private val localProxy by lazy { LocalProxy(client) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override val json: Json by lazy {
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular / Latest ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = POPULAR_QUERY,
            variables = GraphQLVariables(page = page),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val queryBody = GraphQLRequest(
            query = LATEST_QUERY,
            variables = GraphQLVariables(page = page),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    // ============================== Search ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var selectedSort = listOf("TRENDING_DESC")
        var selectedGenres: List<String>? = null
        var selectedFormats: List<String>? = null
        var selectedStatus: List<String>? = null
        var selectedSeason: String? = null
        var selectedYear: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    selectedSort = listOf(filter.toValue())
                }

                is GenreFilter -> {
                    val genres = filter.getCheckedValues()
                    if (genres.isNotEmpty()) selectedGenres = genres
                }

                is FormatFilter -> {
                    val formats = filter.getCheckedValues()
                    if (formats.isNotEmpty()) selectedFormats = formats
                }

                is StatusFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedStatus = listOf(value)
                }

                is SeasonFilter -> {
                    val value = filter.toValue()
                    if (value.isNotEmpty()) selectedSeason = value
                }

                is YearFilter -> {
                    val value = filter.state
                    if (value.isNotBlank()) selectedYear = value.toIntOrNull()
                }

                else -> {}
            }
        }

        val queryBody = GraphQLRequest(
            query = SEARCH_QUERY,
            variables = GraphQLVariables(
                page = page,
                search = query.takeIf { it.isNotBlank() },
                sort = selectedSort,
                genres = selectedGenres,
                format = selectedFormats,
                status = selectedStatus,
                season = selectedSeason,
                seasonYear = selectedYear,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseBody = response.body.string()
        val anilistRes = json.decodeFromString<AnilistGraphQLResponse>(responseBody)
        val pageInfo = anilistRes.data.Page
        if (pageInfo == null || pageInfo.media.isEmpty()) {
            return AnimesPage(emptyList(), false)
        }

        val animeList = pageInfo.media.map { media ->
            SAnime.create().apply {
                url = "/anime/${media.id}"
                val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, "english") ?: "english"
                title = when (titleLang) {
                    "romaji" -> media.title.romaji ?: media.title.english ?: media.title.native ?: "Unknown Title"
                    "native" -> media.title.native ?: media.title.english ?: media.title.romaji ?: "Unknown Title"
                    else -> media.title.english ?: media.title.romaji ?: media.title.native ?: "Unknown Title"
                }
                thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large
                description = media.description
                genre = media.genres.joinToString()
            }
        }
        return AnimesPage(animeList, pageInfo.pageInfo?.hasNextPage ?: (animeList.size == 24))
    }

    // ============================== Anime Details ==============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val anilistId = anime.url.substringAfter("/anime/").toInt()
        val queryBody = GraphQLRequest(
            query = DETAILS_QUERY,
            variables = GraphQLVariables(id = anilistId),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        return POST("https://graphql.anilist.co", headers, body)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseBody = response.body.string()
        val anilistRes = json.decodeFromString<AnilistGraphQLResponse>(responseBody)
        val media = anilistRes.data.Media ?: throw Exception("Anime details not found")

        return SAnime.create().apply {
            val titleLang = preferences.getString(PREF_TITLE_LANG_KEY, "english") ?: "english"
            title = when (titleLang) {
                "romaji" -> media.title.romaji ?: media.title.english ?: media.title.native ?: "Unknown Title"
                "native" -> media.title.native ?: media.title.english ?: media.title.romaji ?: "Unknown Title"
                else -> media.title.english ?: media.title.romaji ?: media.title.native ?: "Unknown Title"
            }
            thumbnail_url = media.coverImage?.extraLarge ?: media.coverImage?.large
            genre = media.genres.joinToString()
            status = when (media.status) {
                "FINISHED" -> SAnime.COMPLETED
                "RELEASING" -> SAnime.ONGOING
                "NOT_YET_RELEASED" -> SAnime.UNKNOWN
                "CANCELLED" -> SAnime.CANCELLED
                "HIATUS" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            author = media.studios?.nodes?.firstOrNull()?.name

            val scorePosition = preferences.getString(PREF_SCORE_POSITION_KEY, "top") ?: "top"
            val scoreVal = media.averageScore?.toDouble()?.let { it / 10.0 }
            description = buildDescription(media.description, scoreVal, scorePosition)
        }
    }

    private fun formatScore(score: Double?): String? {
        if (score == null || score <= 0.0) return null
        val stars = buildString {
            val full = (score / 2.0).toInt().coerceIn(0, 5)
            repeat(full) { append("★") }
            repeat(5 - full) { append("☆") }
        }
        return "$stars ${"%.2f".format(score)}"
    }

    private fun buildDescription(raw: String?, score: Double?, position: String): String {
        val cleanRaw = raw?.replace(Regex("<[^>]+>"), "")?.trim() ?: ""
        val scoreStr = formatScore(score) ?: return cleanRaw
        return when (position) {
            "top" -> "$scoreStr\n\n$cleanRaw"
            "bottom" -> "$cleanRaw\n\n$scoreStr"
            else -> cleanRaw
        }
    }

    // ============================== Episode List ==============================

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(dateStr.trim())?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatEpNum(num: Double): String = if (num % 1.0 == 0.0) num.toInt().toString() else num.toString()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val anilistId = anime.url.substringAfter("/anime/")
        val request = GET("$apiBaseUrl/episodes/$anilistId", headers)
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("Failed to fetch episodes list")
        }

        val epResponse = json.decodeFromString<EpisodeListResponse>(response.body.string())
        val providerKeys = listOf("kiwi", "bonk", "ally", "pewe", "moo", "bee", "hop")

        var providerData: ProviderData? = null
        var episodesMap: Map<String, List<EpisodeItem>> = emptyMap()

        for (key in providerKeys) {
            if (epResponse.providers.containsKey(key)) {
                val data = epResponse.providers[key]
                if (data != null) {
                    val map = data.getEpisodeMap(json)
                    if (map["sub"]?.isNotEmpty() == true || map["dub"]?.isNotEmpty() == true) {
                        providerData = data
                        episodesMap = map
                        break
                    }
                }
            }
        }

        if (providerData == null) {
            for (data in epResponse.providers.values) {
                val map = data.getEpisodeMap(json)
                if (map["sub"]?.isNotEmpty() == true || map["dub"]?.isNotEmpty() == true) {
                    providerData = data
                    episodesMap = map
                    break
                }
            }
        }

        if (providerData == null) {
            throw Exception("No episode providers found")
        }

        val subEps = episodesMap["sub"] ?: emptyList()
        val dubEps = episodesMap["dub"] ?: emptyList()

        val allEpNumbers = (subEps.map { it.number } + dubEps.map { it.number }).distinct().sorted()
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)

        val list = allEpNumbers.map { epNum ->
            val subEp = subEps.find { it.number == epNum }
            val dubEp = dubEps.find { it.number == epNum }
            val epItem = subEp ?: dubEp!!

            SEpisode.create().apply {
                url = "/watch/$anilistId/${formatEpNum(epNum)}"
                val epTitle = epItem.title?.replace(Regex("^.*? - "), "") ?: ""
                name = if (epTitle.isNotBlank() && !epTitle.equals("Episode ${formatEpNum(epNum)}", ignoreCase = true)) {
                    "Episode ${formatEpNum(epNum)}: $epTitle"
                } else {
                    "Episode ${formatEpNum(epNum)}"
                }
                episode_number = epNum.toFloat()
                date_upload = parseDate(epItem.airDate)
                summary = epItem.description?.takeIf { it.isNotBlank() }
                preview_url = if (showThumbnails) epItem.image else null
                scanlator = when {
                    subEp != null && dubEp != null -> "Sub, Dub"
                    dubEp != null -> "Dub"
                    subEp != null -> "Sub"
                    else -> null
                }
            }
        }
        return list.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = emptyList()

    // ============================== Video / Hoster List ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("/")
        val anilistId = parts[2]
        val episodeNumber = parts[3]
        return listOf(
            Hoster(hosterName = "Kiwi (AnimePahe)", hosterUrl = "/watch/kiwi/$anilistId/animepahe-$episodeNumber"),
            Hoster(hosterName = "Net (AnimeDao)", hosterUrl = "/watch/bonk/$anilistId/animedao-$episodeNumber"),
            Hoster(hosterName = "Ally (AllManga)", hosterUrl = "/watch/ally/$anilistId/allmanga-$episodeNumber"),
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val urlPath = hoster.hosterUrl
        if (urlPath.isBlank()) return emptyList()

        val audioType = preferences.getString(PREF_TYPE_KEY, "sub") ?: "sub"
        val audioTypes = listOf("sub", "dub")

        val videos = audioTypes.parallelCatchingFlatMapBlocking { type ->
            val resolvedUrl = "$apiBaseUrl${urlPath.replace("/watch/", "/watch/")}"
                .replace(Regex("/(kiwi|bonk|ally)/(\\d+)/"), "/$1/$2/$type/")

            val request = GET(resolvedUrl, headers)
            val response = try {
                client.newCall(request).execute()
            } catch (_: Exception) {
                null
            }

            if (response == null || !response.isSuccessful) {
                response?.close()
                return@parallelCatchingFlatMapBlocking emptyList<Video>()
            }

            val resData = try {
                json.decodeFromString<StreamResponse>(response.body.string())
            } catch (_: Exception) {
                null
            }

            if (resData == null) {
                return@parallelCatchingFlatMapBlocking emptyList<Video>()
            }

            val typeVideos = mutableListOf<Video>()
            val customHeaders = headersBuilder()
                .set("Referer", "https://vivibebe.site/")
                .build()

            resData.streams.parallelCatchingFlatMapBlocking { stream ->
                val videoList = mutableListOf<Video>()
                when {
                    stream.type == "hls" -> {
                        val streamUrl = stream.url
                        val isProxied = streamUrl.contains("vivibebe.site") || streamUrl.contains("workers.dev")
                        val finalUrl = if (isProxied) {
                            localProxy.getProxyUrl(streamUrl, customHeaders)
                        } else {
                            streamUrl
                        }
                        val refererUrl = stream.referer ?: "https://kwik.cx/"
                        val refHeaders = headersBuilder().set("Referer", refererUrl).build()

                        playlistUtils.extractFromHls(
                            finalUrl,
                            referer = refererUrl,
                            videoNameGen = { quality -> "${hoster.hosterName} - $quality (${type.uppercase()})" },
                        ).forEach { v ->
                            videoList.add(
                                Video(
                                    videoUrl = v.videoUrl,
                                    videoTitle = v.videoTitle,
                                    headers = refHeaders,
                                ),
                            )
                        }
                    }

                    stream.type == "mp4" -> {
                        val refererUrl = stream.referer ?: "https://allmanga.to/"
                        val refHeaders = headersBuilder().set("Referer", refererUrl).build()
                        videoList.add(
                            Video(
                                videoUrl = stream.url,
                                videoTitle = "${hoster.hosterName} - MP4 (${stream.quality ?: "1080p"}) (${type.uppercase()})",
                                headers = refHeaders,
                            ),
                        )
                    }

                    stream.type == "embed" -> {
                        val embedUrl = stream.url
                        when {
                            embedUrl.contains("playmogo.com") || embedUrl.contains("dood") -> {
                                val extractor = DoodExtractor(client)
                                extractor.videosFromUrl(embedUrl, quality = "${hoster.hosterName} (${type.uppercase()})").forEach { v ->
                                    videoList.add(
                                        Video(
                                            videoUrl = v.videoUrl,
                                            videoTitle = v.videoTitle,
                                            headers = v.headers,
                                            subtitleTracks = v.subtitleTracks,
                                        ),
                                    )
                                }
                            }

                            embedUrl.contains("otakuhg.site") || embedUrl.contains("otakuvid.online") || embedUrl.contains("bysekoze.com") -> {
                                val extractor = VidHideExtractor(client, headers)
                                extractor.videosFromUrl(embedUrl) { quality -> "${hoster.hosterName} - $quality (${type.uppercase()})" }.forEach { v ->
                                    videoList.add(
                                        Video(
                                            videoUrl = v.videoUrl,
                                            videoTitle = v.videoTitle,
                                            headers = v.headers,
                                            subtitleTracks = v.subtitleTracks,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                videoList
            }.let { typeVideos.addAll(it) }

            typeVideos
        }

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        return videos.filter { video ->
            !excludedServers.any { video.videoTitle.contains(it, ignoreCase = true) }
        }
    }

    // ============================== Video Sorting ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, "1080p")!!
        val audioType = preferences.getString(PREF_TYPE_KEY, "sub")!!
        return this.sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("(${audioType.uppercase()})") }
                .thenByDescending { it.videoTitle.contains(quality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_DOMAIN_KEY,
            title = "Preferred Domain",
            entries = listOf("anitusk.com"),
            entryValues = listOf("https://anitusk.com"),
            default = PREF_DOMAIN_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TITLE_LANG_KEY,
            title = "Preferred Title Language",
            entries = listOf("English", "Romaji/Japanese", "Native"),
            entryValues = listOf("english", "romaji", "native"),
            default = "english",
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Audio Type",
            entries = listOf("Subbed", "Dubbed"),
            entryValues = listOf("sub", "dub"),
            default = "sub",
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
            default = "1080p",
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            title = "Score Display Position",
            entries = listOf("Top", "Bottom", "Disabled"),
            entryValues = listOf("top", "bottom", "disabled"),
            default = "top",
            summary = "%s",
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude Servers",
            summary = "Select servers to exclude from the video list",
            entries = listOf("Kiwi", "Net", "Ally", "Doodstream", "StreamHG", "Earnvids"),
            entryValues = listOf("Kiwi", "Net", "Ally", "Doodstream", "StreamHG", "Earnvids"),
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = "Show episode thumbnails",
            summary = "Fetch and display images in the episode list.",
            default = true,
        )
    }

    // ============================== Filters ==============================

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toValue() = vals[state].second
    }

    private class CheckBoxVal(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    open class CheckBoxFilterList(name: String, val vals: Array<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { CheckBoxVal(it.first, false) }) {
        fun getCheckedValues(): List<String> = state.mapIndexedNotNull { index, checkbox ->
            if (checkbox.state) vals[index].second else null
        }
    }

    class GenreFilter : CheckBoxFilterList("Genres", GENRES)
    class FormatFilter : CheckBoxFilterList("Formats", FORMATS)
    class StatusFilter : UriPartFilter("Status", STATUSES)
    class SeasonFilter : UriPartFilter("Seasons", SEASONS)
    class SortFilter : UriPartFilter("Sort By", SORT_BY)
    class YearFilter : AnimeFilter.Text("Year")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
        AnimeFilter.Separator(),
        FormatFilter(),
        AnimeFilter.Separator(),
        StatusFilter(),
        AnimeFilter.Separator(),
        SeasonFilter(),
        AnimeFilter.Separator(),
        YearFilter(),
    )

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://anitusk.com"
        private const val PREF_TITLE_LANG_KEY = "preferred_title_lang"
        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_SCORE_POSITION_KEY = "pref_score_position"
        private const val PREF_EXCLUDE_SERVERS_KEY = "exclude_servers"
        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private val POPULAR_QUERY = """
            query(${"$"}page: Int) {
              Page(page: ${"$"}page, perPage: 24) {
                pageInfo {
                  hasNextPage
                }
                media(sort: [TRENDING_DESC], type: ANIME, isAdult: false) {
                  id
                  title { english romaji native }
                  coverImage { large extraLarge }
                  description(asHtml: false)
                  genres
                }
              }
            }
        """.trimIndent()

        private val LATEST_QUERY = """
            query(${"$"}page: Int) {
              Page(page: ${"$"}page, perPage: 24) {
                pageInfo {
                  hasNextPage
                }
                media(sort: [START_DATE_DESC], type: ANIME, isAdult: false) {
                  id
                  title { english romaji native }
                  coverImage { large extraLarge }
                  description(asHtml: false)
                  genres
                }
              }
            }
        """.trimIndent()

        private val SEARCH_QUERY = """
            query(${"$"}page: Int, ${"$"}search: String, ${"$"}sort: [MediaSort], ${"$"}genres: [String], ${"$"}format: [MediaFormat], ${"$"}status: [MediaStatus], ${"$"}season: MediaSeason, ${"$"}seasonYear: Int) {
              Page(page: ${"$"}page, perPage: 24) {
                pageInfo {
                  hasNextPage
                }
                media(search: ${"$"}search, sort: ${"$"}sort, genre_in: ${"$"}genres, format_in: ${"$"}format, status_in: ${"$"}status, season: ${"$"}season, seasonYear: ${"$"}seasonYear, type: ANIME, isAdult: false) {
                  id
                  title { english romaji native }
                  coverImage { large extraLarge }
                  description(asHtml: false)
                  genres
                }
              }
            }
        """.trimIndent()

        private val DETAILS_QUERY = """
            query(${"$"}id: Int) {
              Media(id: ${"$"}id, type: ANIME) {
                id
                title { english romaji native }
                coverImage { large extraLarge }
                bannerImage
                description(asHtml: false)
                status
                genres
                averageScore
                episodes
                format
                source
                studios(isMain: true) {
                  nodes {
                    name
                  }
                }
                nextAiringEpisode {
                  episode
                  airingAt
                }
                streamingEpisodes {
                  title
                  thumbnail
                  url
                }
              }
            }
        """.trimIndent()

        private val GENRES = arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Horror", "Horror"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Mecha", "Mecha"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Thriller", "Thriller"),
        )

        private val FORMATS = arrayOf(
            Pair("TV", "TV"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Movie", "MOVIE"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        )

        private val STATUSES = arrayOf(
            Pair("Any", ""),
            Pair("Finished", "FINISHED"),
            Pair("Airing", "RELEASING"),
            Pair("Upcoming", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
            Pair("Hiatus", "HIATUS"),
        )

        private val SEASONS = arrayOf(
            Pair("Any", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        )

        private val SORT_BY = arrayOf(
            Pair("Trending", "TRENDING_DESC"),
            Pair("Popularity", "POPULARITY_DESC"),
            Pair("Score", "SCORE_DESC"),
            Pair("Search Match", "SEARCH_MATCH"),
            Pair("Start Date", "START_DATE_DESC"),
        )
    }
}

class LocalProxy(private val client: OkHttpClient) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    var port: Int = 0
        private set

    init {
        try {
            serverSocket = ServerSocket(0)
            port = serverSocket!!.localPort
            executor.execute {
                while (serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket!!.accept()
                        executor.execute { handleSocket(socket) }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    fun getProxyUrl(targetUrl: String, headers: Headers?): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val headersStr = headers?.let { h ->
            val sb = StringBuilder()
            for (i in 0 until h.size) {
                sb.append(h.name(i)).append(":").append(h.value(i)).append("\n")
            }
            sb.toString()
        } ?: ""
        val encodedHeaders = Base64.encodeToString(headersStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun handleSocket(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val reader = input.bufferedReader()
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1]

            if (!path.startsWith("/proxy")) {
                sendError(socket, 404, "Not Found")
                return
            }

            val httpUrl = ("http://127.0.0.1$path").toHttpUrl()
            val encodedUrl = httpUrl.queryParameter("url")
            val encodedHeaders = httpUrl.queryParameter("headers") ?: ""

            if (encodedUrl.isNullOrEmpty()) {
                sendError(socket, 400, "Missing url parameter")
                return
            }

            val targetUrl = String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val isM3u8Request = targetUrl.contains(".m3u8") || path.contains("playlist.m3u8")

            val targetHeaders = Headers.Builder()
            if (encodedHeaders.isNotEmpty()) {
                val headersStr = String(Base64.decode(encodedHeaders, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
                headersStr.split("\n").forEach { line ->
                    val headerParts = line.split(":", limit = 2)
                    if (headerParts.size == 2) {
                        targetHeaders.set(headerParts[0].trim(), headerParts[1].trim())
                    }
                }
            }

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val headerParts = line!!.split(":", limit = 2)
                if (headerParts.size == 2) {
                    val name = headerParts[0].trim()
                    val value = headerParts[1].trim()
                    if (name.equals("Range", ignoreCase = true) && !isM3u8Request) {
                        targetHeaders.set(name, value)
                    }
                }
            }

            val request = Request.Builder()
                .url(targetUrl)
                .headers(targetHeaders.build())
                .build()

            client.newCall(request).execute().use { response ->
                sendResponse(socket, response, targetUrl, encodedHeaders)
            }
        } catch (e: Exception) {
            try {
                sendError(socket, 500, e.message ?: "Internal Error")
            } catch (_: Exception) {}
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendResponse(socket: Socket, response: Response, targetUrl: String, encodedHeaders: String) {
        val out = socket.getOutputStream()
        val isM3u8 = targetUrl.contains(".m3u8") || response.header("Content-Type")?.contains("mpegurl") == true

        var modifiedContentBytes: ByteArray? = null
        if (isM3u8) {
            val bodyString = response.body.string()
            val modifiedContent = processM3u8(bodyString, targetUrl, encodedHeaders)
            modifiedContentBytes = modifiedContent.toByteArray()
        }

        out.write("HTTP/1.1 ${response.code} ${response.message}\r\n".toByteArray())

        val headers = response.headers
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            val value = headers.value(i)
            if (name.equals("Connection", ignoreCase = true) ||
                name.equals("Transfer-Encoding", ignoreCase = true) ||
                name.equals("Content-Type", ignoreCase = true) ||
                (name.equals("Content-Length", ignoreCase = true) && isM3u8)
            ) {
                continue
            }
            out.write("$name: $value\r\n".toByteArray())
        }

        if (isM3u8 && modifiedContentBytes != null) {
            out.write("Content-Length: ${modifiedContentBytes.size}\r\n".toByteArray())
            out.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(modifiedContentBytes)
        } else {
            out.write("Content-Type: video/mp2t\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())

            val rawBytes = response.body.bytes()
            val stripped = stripPngHeader(rawBytes)
            out.write(stripped)
        }
        out.flush()
    }

    private fun processM3u8(content: String, playlistUrl: String, encodedHeaders: String): String {
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MAP") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                    uriRegex.find(trimmed)?.let { match ->
                        val uriValue = match.groupValues[1]
                        val resolvedUri = resolveUrl(playlistUrl, uriValue)
                        val proxiedUri = getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders)
                        builder.append(trimmed.replace(uriValue, proxiedUri))
                    } ?: builder.append(trimmed)
                } else {
                    builder.append(trimmed)
                }
            } else {
                val resolvedUri = resolveUrl(playlistUrl, trimmed)
                builder.append(getProxyUrlWithEncodedHeaders(resolvedUri, encodedHeaders))
            }
            builder.append("\n")
        }

        return builder.toString()
    }

    private fun getProxyUrlWithEncodedHeaders(targetUrl: String, encodedHeaders: String): String {
        val encodedUrl = Base64.encodeToString(targetUrl.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val ext = if (targetUrl.contains(".m3u8") || targetUrl.contains("mpegurl")) "playlist.m3u8" else "segment.ts"
        return "http://127.0.0.1:$port/proxy/$ext?url=$encodedUrl&headers=$encodedHeaders"
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String = try {
        baseUrl.toHttpUrl().resolve(relativeUrl)?.toString() ?: relativeUrl
    } catch (_: Exception) {
        relativeUrl
    }

    private fun stripPngHeader(data: ByteArray): ByteArray {
        if (data.size < 8) return data
        val isPng = data[0] == (-119).toByte() && data[1] == 80.toByte() && data[2] == 78.toByte() && data[3] == 71.toByte()
        if (!isPng) return data
        var videoStart = -1
        val length = data.size - 4
        for (i in 0 until length) {
            if (data[i] == 73.toByte() && data[i + 1] == 69.toByte() && data[i + 2] == 78.toByte() && data[i + 3] == 68.toByte()) {
                videoStart = i + 8
                break
            }
        }
        if (videoStart < 0 || videoStart >= data.size) return data
        val tsData = data.copyOfRange(videoStart, data.size)
        val iMin = min(tsData.size - 188, 400)
        for (offset in 0 until iMin) {
            if (tsData[offset] == 0x47.toByte() && tsData[offset + 188] == 0x47.toByte()) {
                return tsData.copyOfRange(offset, tsData.size)
            }
        }
        return tsData
    }

    private fun sendError(socket: Socket, code: Int, message: String) {
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $message\r\n".toByteArray())
        out.write("Content-Type: text/plain\r\n".toByteArray())
        out.write("\r\n".toByteArray())
        out.write(message.toByteArray())
        out.flush()
    }
}

@Serializable
data class GraphQLRequest(
    val query: String,
    val variables: GraphQLVariables? = null,
)

@Serializable
data class GraphQLVariables(
    val page: Int? = null,
    val search: String? = null,
    val sort: List<String>? = null,
    val genres: List<String>? = null,
    val format: List<String>? = null,
    val status: List<String>? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val id: Int? = null,
)

@Serializable
data class AnilistGraphQLResponse(
    val data: AnilistData,
)

@Serializable
data class AnilistData(
    val Page: AnilistPage? = null,
    val Media: AnilistMedia? = null,
)

@Serializable
data class AnilistPage(
    val pageInfo: AnilistPageInfo? = null,
    val media: List<AnilistMedia> = emptyList(),
)

@Serializable
data class AnilistPageInfo(
    val hasNextPage: Boolean,
)

@Serializable
data class AnilistMedia(
    val id: Int,
    val title: AnilistTitle,
    val coverImage: AnilistCoverImage? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val status: String? = null,
    val genres: List<String> = emptyList(),
    val averageScore: Int? = null,
    val episodes: Int? = null,
    val format: String? = null,
    val source: String? = null,
    val studios: AnilistStudios? = null,
    val nextAiringEpisode: AnilistNextAiringEpisode? = null,
    val streamingEpisodes: List<AnilistStreamingEpisode> = emptyList(),
)

@Serializable
data class AnilistTitle(
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null,
)

@Serializable
data class AnilistCoverImage(
    val large: String? = null,
    val extraLarge: String? = null,
)

@Serializable
data class AnilistStudios(
    val nodes: List<AnilistStudioNode> = emptyList(),
)

@Serializable
data class AnilistStudioNode(
    val name: String,
)

@Serializable
data class AnilistNextAiringEpisode(
    val episode: Int,
)

@Serializable
data class AnilistStreamingEpisode(
    val title: String,
    val thumbnail: String? = null,
    val url: String? = null,
)

@Serializable
data class StreamResponse(
    val streams: List<StreamItem> = emptyList(),
    val download: String? = null,
)

@Serializable
data class StreamItem(
    val url: String,
    val type: String? = null,
    val quality: String? = null,
    val audio: String? = null,
    val fansub: String? = null,
    val isActive: Boolean? = null,
    val referer: String? = null,
    val server: String? = null,
    val priority: Int? = null,
)

@Serializable
data class EpisodeListResponse(
    val providers: Map<String, ProviderData> = emptyMap(),
)

@Serializable
data class ProviderData(
    val episodes: JsonElement? = null,
) {
    fun getEpisodeMap(json: Json): Map<String, List<EpisodeItem>> {
        val element = episodes ?: return emptyMap()
        return try {
            if (element is JsonObject) {
                json.decodeFromJsonElement<Map<String, List<EpisodeItem>>>(element)
            } else if (element is JsonArray) {
                val list = json.decodeFromJsonElement<List<EpisodeItem>>(element)
                mapOf("sub" to list)
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

@Serializable
data class EpisodeItem(
    val id: String,
    val number: Double,
    val title: String? = null,
    val image: String? = null,
    val airDate: String? = null,
    val description: String? = null,
    val filler: Boolean? = null,
)

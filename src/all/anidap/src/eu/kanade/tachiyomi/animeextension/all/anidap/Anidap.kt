package eu.kanade.tachiyomi.animeextension.all.anidap

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class Anidap :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Anidap"

    override val baseUrl = "https://anidap.lol"

    override val lang = "all"

    override val supportsLatest = true

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val queryBody = GraphQLRequest(
            query = CATALOG_QUERY,
            variables = GraphQLVariables(
                sort = listOf(AnimeSortInput("POPULARITY", "DESC")),
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = client.newCall(POST(GRAPHQL_URL, headers, body)).execute()
        return parseGraphQLAnimePage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val queryBody = GraphQLRequest(
            query = CATALOG_QUERY,
            variables = GraphQLVariables(
                sort = listOf(AnimeSortInput("CREATED_AT", "DESC")),
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )
        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = client.newCall(POST(GRAPHQL_URL, headers, body)).execute()
        return parseGraphQLAnimePage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        var selectedSort = listOf(AnimeSortInput("POPULARITY", "DESC"))
        var selectedGenres: List<String>? = null
        var selectedFormats: List<String>? = null
        var selectedStatus: List<String>? = null
        var selectedSeason: List<String>? = null
        var selectedYear: Int? = null

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> if (!filter.isDefault()) selectedFormats = listOf(filter.toUriPart())

                is Filters.StatusFilter -> if (!filter.isDefault()) selectedStatus = listOf(filter.toUriPart())

                is Filters.SeasonFilter -> if (!filter.isDefault()) selectedSeason = listOf(filter.toUriPart())

                is Filters.YearFilter -> if (!filter.isDefault()) selectedYear = filter.toUriPart().toIntOrNull()

                is Filters.SortFilter -> filter.toUriPart()?.let {
                    val field = when (it) {
                        "START_DATE_DESC" -> "CREATED_AT"
                        "POPULARITY_DESC" -> "POPULARITY"
                        "SCORE_DESC" -> "AVERAGE_SCORE"
                        else -> "POPULARITY"
                    }
                    selectedSort = listOf(AnimeSortInput(field, "DESC"))
                }

                is Filters.GenreFilter -> {
                    val selected = filter.toQueries()
                    if (selected.isNotEmpty()) selectedGenres = selected
                }

                else -> {}
            }
        }

        val filterInput = AnimeCatalogFilterInput(
            query = if (query.isNotBlank()) query else null,
            statusIn = selectedStatus,
            seasonIn = selectedSeason,
            seasonYearMin = selectedYear,
            seasonYearMax = selectedYear,
            formatIn = selectedFormats,
            genres = selectedGenres,
        )

        val queryBody = GraphQLRequest(
            query = CATALOG_QUERY,
            variables = GraphQLVariables(
                filter = filterInput,
                sort = selectedSort,
                limit = 30,
                offset = (page - 1) * 30,
            ),
        )

        val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
        val response = client.newCall(POST(GRAPHQL_URL, headers, body)).execute()
        return parseGraphQLAnimePage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply when text search is blank"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SeasonFilter(),
        Filters.YearFilter(),
        Filters.SortFilter(),
        Filters.GenreFilter(),
    )

    private fun parseGraphQLAnimePage(response: Response): AnimesPage {
        val responseBody = response.body.string()
        val result = runCatching {
            json.decodeFromString<CatalogAnimeResponse>(responseBody)
        }.getOrNull() ?: return AnimesPage(emptyList(), false)

        val items = result.data.catalogAnime.items
        val animes = items.map { item ->
            SAnime.create().apply {
                url = item.id
                title = item.titleEnglish ?: item.titleRomaji ?: "Anime"
                thumbnail_url = item.coverImage
                genre = item.genres.joinToString()
            }
        }
        return AnimesPage(animes, items.size >= 30)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = anime.url.removePrefix("/").substringBefore("?")
        val anilistId = slug.substringAfterLast("-").toIntOrNull()

        if (anilistId != null) {
            val queryBody = GraphQLRequest(
                query = GET_ANIME_QUERY,
                variables = GraphQLVariables(anilistId = anilistId),
            )
            val body = json.encodeToString(queryBody).toRequestBody("application/json; charset=utf-8".toMediaType())
            val response = runCatching {
                client.newCall(POST(GRAPHQL_URL, headers, body)).execute()
            }.getOrNull()

            if (response != null && response.isSuccessful) {
                val detailsData = runCatching {
                    json.decodeFromString<GetAnimeResponse>(response.body.string())
                }.getOrNull()

                detailsData?.data?.anime?.let { detail ->
                    return SAnime.create().apply {
                        url = anime.url
                        title = detail.titleEnglish ?: detail.titleRomaji ?: anime.title
                        thumbnail_url = detail.coverImage ?: anime.thumbnail_url
                        genre = detail.genres.joinToString()
                        status = when (detail.status?.uppercase()) {
                            "RELEASING" -> SAnime.ONGOING
                            "FINISHED" -> SAnime.COMPLETED
                            "NOT_YET_RELEASED" -> SAnime.LICENSED
                            else -> SAnime.UNKNOWN
                        }
                        initialized = true
                        description = buildString {
                            val score = detail.averageScore
                            if (score != null && score > 0) {
                                val fullStars = (score / 20).toInt().coerceIn(0, 5)
                                append("${"★".repeat(fullStars)}${"☆".repeat(5 - fullStars)} ${"%.1f".format(score / 10.0)}/10\n\n")
                            }
                            detail.description?.let { append(it.replace(Regex("<[^>]*>"), "")) }
                            if (detail.season != null) append("\n\nSeason: ${detail.season} ${detail.seasonYear ?: ""}")
                            if (detail.format != null) append("\nFormat: ${detail.format}")
                        }.trim()
                    }
                }
            }
        }
        return anime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.removePrefix("/").substringBefore("?")
        val request = GET("https://chad.anidap.lol/rest/api/episodes?id=$slug", headers)
        val response = client.newCall(request).execute()
        val body = response.body.string()

        val episodes = runCatching {
            val jsonElement = json.parseToJsonElement(body)
            when {
                jsonElement is JsonArray -> json.decodeFromJsonElement<List<EpisodeItem>>(jsonElement)
                jsonElement is JsonObject && jsonElement["data"] is JsonArray -> json.decodeFromJsonElement<List<EpisodeItem>>(jsonElement["data"]!!)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

        val loadThumbnails = preferences.getBoolean("pref_load_thumbnails", true)
        val loadTitles = preferences.getBoolean("pref_load_titles", true)
        val loadDescriptions = preferences.getBoolean("pref_load_descriptions", true)

        val episodeList = episodes.map { ep ->
            SEpisode.create().apply {
                val num = ep.number ?: ep.episodeNumber ?: 1f
                episode_number = num
                name = if (loadTitles && !ep.title.isNullOrBlank()) {
                    "Episode $num: ${ep.title}"
                } else {
                    "Episode $num"
                }
                url = "$slug?ep=$num"
                if (loadThumbnails && !ep.img.isNullOrBlank()) {
                    preview_url = ep.img
                }
                if (loadDescriptions && !ep.description.isNullOrBlank()) {
                    summary = ep.description
                }
                scanlator = when {
                    ep.hasSub == true && ep.hasDub == true -> "Sub / Dub"
                    ep.hasDub == true -> "Dub"
                    ep.hasSub == true -> "Sub"
                    else -> null
                }
            }
        }

        return episodeList.sortedBy { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val animeId = episode.url.substringBefore("?")
        val epNum = episode.url.substringAfter("ep=").substringBefore("&")

        val serversRequest = GET("https://chad.anidap.lol/rest/api/servers?id=$animeId&epNum=$epNum", headers)
        val response = client.newCall(serversRequest).execute()
        val serversData = runCatching {
            json.decodeFromString<ServersResponse>(response.body.string())
        }.getOrNull() ?: ServersResponse()

        val disabledServers = preferences.getStringSet("pref_disabled_servers", emptySet()) ?: emptySet()
        val preferredType = preferences.getString("pref_audio_type", "sub") ?: "sub"

        val hosters = mutableListOf<Hoster>()
        val subProviders = serversData.data?.subProviders ?: serversData.subProviders ?: emptyList()
        val dubProviders = serversData.data?.dubProviders ?: serversData.dubProviders ?: emptyList()

        if (preferredType == "sub" || preferredType == "both") {
            subProviders.forEach { server ->
                if (!disabledServers.contains(server.id)) {
                    hosters.add(Hoster(hosterName = "SUB - ${server.id.uppercase()}", hosterUrl = "$animeId|$epNum|sub|${server.id}"))
                }
            }
        }

        if (preferredType == "dub" || preferredType == "both") {
            dubProviders.forEach { server ->
                if (!disabledServers.contains(server.id)) {
                    hosters.add(Hoster(hosterName = "DUB - ${server.id.uppercase()}", hosterUrl = "$animeId|$epNum|dub|${server.id}"))
                }
            }
        }

        return sortHostersByPreference(hosters)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 4) return emptyList()

        val animeId = parts[0]
        val epNum = parts[1]
        val type = parts[2]
        val providerId = parts[3]

        val requestUrl = "https://chad.anidap.lol/rest/api/sources?id=$animeId&epNum=$epNum&type=$type&providerId=$providerId"
        val response = client.newCall(GET(requestUrl, headers)).execute()
        val sourcesData = runCatching {
            json.decodeFromString<SourcesResponse>(response.body.string())
        }.getOrNull() ?: return emptyList()

        val sources = sourcesData.data?.sources ?: sourcesData.sources ?: emptyList()
        if (sources.isEmpty()) return emptyList()

        val rawSubs = sourcesData.data?.subtitles ?: sourcesData.subtitles ?: sourcesData.data?.tracks ?: sourcesData.tracks ?: emptyList()
        val subtitles = rawSubs.mapNotNull { track ->
            val trackUrl = track.url ?: return@mapNotNull null
            Track(url = trackUrl, lang = track.label ?: track.lang ?: "Sub")
        }

        val videos = mutableListOf<Video>()
        for (src in sources) {
            val rawUrl = src.url ?: continue
            val finalUrl = transformSourceUrl(rawUrl, providerId)
            val titleLabel = "${type.uppercase()} - ${providerId.uppercase()} - ${src.quality ?: "Auto"}"

            when {
                providerId.equals("mp4upload", ignoreCase = true) -> {
                    videos.addAll(mp4uploadExtractor.videosFromUrl(finalUrl, headers))
                }

                providerId.equals("okru", ignoreCase = true) -> {
                    videos.addAll(okruExtractor.videosFromUrl(finalUrl))
                }

                finalUrl.contains(".m3u8") -> {
                    val playlistVideos = playlistUtils.extractFromHls(
                        playlistUrl = finalUrl,
                        masterHeaders = headers,
                        videoHeaders = headers,
                        videoNameGen = { quality -> "$titleLabel - $quality" },
                        subtitleList = subtitles,
                    )
                    videos.addAll(playlistVideos)
                }

                else -> {
                    videos.add(
                        Video(
                            videoUrl = finalUrl,
                            videoTitle = titleLabel,
                            headers = headers,
                            subtitleTracks = subtitles,
                        ),
                    )
                }
            }
        }

        return videos.sortVideos()
    }

    private fun transformSourceUrl(url: String, providerId: String): String = when (providerId.lowercase()) {
        "shiro" -> "${b(url)}&origin=https://kem.clvd.xyz/"

        "kami" -> "${b(url)}&origin=https://krussdomi.com"

        "vee" -> if (url.startsWith("https://cdn.animeonsen.xyz")) url else "${b(url)}&origin=https://www.animeonsen.xyz/"

        "yuki" -> f(url, "https://megaplay.buzz")

        "uwu" -> f(url, "https://kwik.cx/")

        "miku" -> f(url, "https://allanime.uns.bio")

        "mochi" -> url.replace("https://tools.fast4speed.rsvp", "https://mp4.24stream.xyz/storage")

        "beep" -> when {
            url.startsWith("https://bd.24stream.xyz/media") -> url
            url.startsWith("/") -> "https://bd.24stream.xyz/media${url.replace("/r2", "")}"
            else -> "https://bd.24stream.xyz/media${url.replace(Regex("https?://[^/]+"), "").replace("/r2", "")}"
        }

        "mimi" -> url.replace("https://vivibebe.site/public/stream/", "https://hawk.aniwatchtv.site/media/")

        else -> url
    }

    private fun b(url: String): String {
        val bytes = url.toByteArray()
        val xored = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor 137).toByte() }
        val hex = xored.joinToString("") { "%02x".format(it) }
        return "https://crs.24stream.xyz/media/$hex"
    }

    private fun f(url: String, referer: String): String {
        val urlBytes = url.toByteArray()
        val refBytes = referer.toByteArray()
        val combined = ByteArray(urlBytes.size + 1 + refBytes.size)
        System.arraycopy(urlBytes, 0, combined, 0, urlBytes.size)
        combined[urlBytes.size] = 0
        System.arraycopy(refBytes, 0, combined, urlBytes.size + 1, refBytes.size)

        val key = "10b06cdc1ca48c9fb0b94af97cc040cf".toByteArray()
        for (i in combined.indices) {
            combined[i] = (combined[i].toInt() xor key[i % key.size].toInt()).toByte()
        }

        val base64 = Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val domain = SITES_DOMAINS[siteIndex % SITES_DOMAINS.size]
        siteIndex++
        return "$domain/uwu/$base64"
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val preferredServer = preferences.getString("pref_preferred_server", "mimi") ?: "mimi"
        return hosters.sortedWith(
            compareBy { hoster ->
                val name = hoster.hosterName.lowercase()
                !name.contains(preferredServer.lowercase())
            },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString("pref_quality", "1080p") ?: "1080p"
        return this.sortedWith(
            compareBy { video ->
                val title = video.videoTitle.lowercase()
                !title.contains(quality.lowercase())
            },
        )
    }

    // =============================== Preferences ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "pref_preferred_server"
            title = "Preferred Server"
            summary = "Preferred video server hoster"
            entries = arrayOf("Mimi", "Beep", "Yuki", "Kiwi", "Vee", "Miku", "Mochi")
            entryValues = arrayOf("mimi", "beep", "yuki", "kiwi", "vee", "miku", "mochi")
            setDefaultValue("mimi")
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = "pref_audio_type"
            title = "Preferred Audio Category"
            summary = "Show Subbed, Dubbed, or Both servers"
            entries = arrayOf("Sub", "Dub", "Both")
            entryValues = arrayOf("sub", "dub", "both")
            setDefaultValue("sub")
        }.also { screen.addPreference(it) }

        MultiSelectListPreference(screen.context).apply {
            key = "pref_disabled_servers"
            title = "Disabled Servers"
            summary = "Select servers to exclude from video list"
            entries = arrayOf("Beep", "Mimi", "Vee", "Yuki", "Loli", "Uwu", "Kiwi", "Miku", "Mochi")
            entryValues = arrayOf("beep", "mimi", "vee", "yuki", "loli", "uwu", "kiwi", "miku", "mochi")
            setDefaultValue(emptySet<String>())
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_load_thumbnails"
            title = "Load Episode Thumbnails"
            summary = "Fetch preview images for episode items"
            setDefaultValue(true)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_load_titles"
            title = "Load Episode Titles"
            summary = "Fetch custom names for episode items"
            setDefaultValue(true)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "pref_load_descriptions"
            title = "Load Episode Descriptions"
            summary = "Fetch synopsis descriptions for episodes"
            setDefaultValue(true)
        }.also { screen.addPreference(it) }
    }

    // Data Models
    @Serializable
    private data class GraphQLRequest(
        val query: String,
        val variables: GraphQLVariables? = null,
    )

    @Serializable
    private data class GraphQLVariables(
        val filter: AnimeCatalogFilterInput? = null,
        val sort: List<AnimeSortInput>? = null,
        val limit: Int? = null,
        val offset: Int? = null,
        val anilistId: Int? = null,
    )

    @Serializable
    private data class AnimeCatalogFilterInput(
        val query: String? = null,
        val genres: List<String>? = null,
        val formatIn: List<String>? = null,
        val statusIn: List<String>? = null,
        val seasonIn: List<String>? = null,
        val seasonYearMin: Int? = null,
        val seasonYearMax: Int? = null,
    )

    @Serializable
    private data class AnimeSortInput(
        val field: String,
        val direction: String,
    )

    @Serializable
    private data class CatalogAnimeResponse(
        val data: CatalogData,
    )

    @Serializable
    private data class CatalogData(
        val catalogAnime: CatalogAnimeContainer,
    )

    @Serializable
    private data class CatalogAnimeContainer(
        val items: List<CatalogAnimeItem>,
    )

    @Serializable
    private data class CatalogAnimeItem(
        val id: String,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val titleRomaji: String? = null,
        val titleEnglish: String? = null,
        val coverImage: String? = null,
        val bannerImage: String? = null,
        val description: String? = null,
        val status: String? = null,
        val format: String? = null,
        val averageScore: Double? = null,
        val popularity: Int? = null,
        val seasonYear: Int? = null,
        val season: String? = null,
        val genres: List<String> = emptyList(),
    )

    @Serializable
    private data class GetAnimeResponse(
        val data: GetAnimeData,
    )

    @Serializable
    private data class GetAnimeData(
        val anime: CatalogAnimeItem? = null,
    )

    @Serializable
    private data class EpisodeItem(
        val number: Float? = null,
        val episodeNumber: Float? = null,
        val title: String? = null,
        val img: String? = null,
        val description: String? = null,
        val isFiller: Boolean? = false,
        val hasSub: Boolean? = false,
        val hasDub: Boolean? = false,
    )

    @Serializable
    private data class ServersResponse(
        val data: ServersData? = null,
        val subProviders: List<ServerItem>? = null,
        val dubProviders: List<ServerItem>? = null,
    )

    @Serializable
    private data class ServersData(
        val subProviders: List<ServerItem>? = null,
        val dubProviders: List<ServerItem>? = null,
    )

    @Serializable
    private data class ServerItem(
        val id: String,
    )

    @Serializable
    private data class SourcesResponse(
        val data: SourcesData? = null,
        val sources: List<SourceItem>? = null,
        val subtitles: List<SubtitleItem>? = null,
        val tracks: List<SubtitleItem>? = null,
    )

    @Serializable
    private data class SourcesData(
        val sources: List<SourceItem>? = null,
        val subtitles: List<SubtitleItem>? = null,
        val tracks: List<SubtitleItem>? = null,
    )

    @Serializable
    private data class SourceItem(
        val url: String? = null,
        val quality: String? = null,
    )

    @Serializable
    private data class SubtitleItem(
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
    )

    companion object {
        private const val GRAPHQL_URL = "https://graphql.animex.one/graphql"

        private const val CATALOG_QUERY = """
            query CatalogAnime(${'$'}filter: AnimeCatalogFilterInput, ${'$'}sort: [AnimeSortInput!], ${'$'}limit: Int, ${'$'}offset: Int) {
              catalogAnime(filter: ${'$'}filter, sort: ${'$'}sort, limit: ${'$'}limit, offset: ${'$'}offset) {
                items {
                  id
                  anilistId
                  titleRomaji
                  titleEnglish
                  coverImage
                  bannerImage
                  description
                  status
                  format
                  averageScore
                  popularity
                  seasonYear
                  season
                  genres
                }
              }
            }
        """

        private const val GET_ANIME_QUERY = """
            query GetAnime(${'$'}anilistId: Int) {
              anime(anilistId: ${'$'}anilistId) {
                id
                anilistId
                titleRomaji
                titleEnglish
                description
                coverImage
                bannerImage
                status
                format
                genres
                averageScore
                seasonYear
                season
              }
            }
        """

        private val SITES_DOMAINS = listOf(
            "https://cx.aniwatchtv.site",
            "https://nsx.aniwatchtv.site",
            "https://pro.aniwatchtv.site",
            "https://rl2.aniwatchtv.site",
            "https://rrl.aniwatchtv.site",
        )
        private var siteIndex = 0
    }
}

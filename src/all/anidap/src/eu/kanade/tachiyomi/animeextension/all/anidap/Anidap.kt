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
import extensions.utils.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
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
        val request = GET("$baseUrl/api/anime/advanced-search?sort=POPULARITY_DESC&page=$page", headers)
        val response = client.newCall(request).execute()
        return parseAnimePage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/anime/advanced-search?sort=START_DATE_DESC&page=$page", headers)
        val response = client.newCall(request).execute()
        return parseAnimePage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/anime/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimePage(response)
        }

        val urlBuilder = "$baseUrl/api/anime/advanced-search".toHttpUrl().newBuilder()
        urlBuilder.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("format", filter.toUriPart())

                is Filters.StatusFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())

                is Filters.SeasonFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())

                is Filters.YearFilter -> if (!filter.isDefault()) urlBuilder.addQueryParameter("year", filter.toUriPart())

                is Filters.SortFilter -> filter.toUriPart()?.let { urlBuilder.addQueryParameter("sort", it) }

                is Filters.GenreFilter -> {
                    val selected = filter.toQueries()
                    if (selected.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genres", selected.joinToString(","))
                    }
                }

                else -> {}
            }
        }

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseAnimePage(response)
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

    private fun parseAnimePage(response: Response): AnimesPage {
        val body = response.body.string()
        val jsonElement = json.parseToJsonElement(body).jsonObject
        val dataElement = jsonElement["data"] ?: jsonElement

        val itemsArray = when (dataElement) {
            is JsonArray -> dataElement
            is JsonObject -> dataElement["results"]?.jsonArray ?: dataElement["data"]?.jsonArray
            else -> jsonElement["results"]?.jsonArray
        } ?: JsonArray(emptyList())

        val animes = itemsArray.mapNotNull { element ->
            runCatching {
                val item = json.decodeFromJsonElement<AnimeItem>(element)
                val idStr = item.id?.content ?: return@mapNotNull null
                val animeTitle = item.title?.english ?: item.title?.userPreferred ?: item.title?.romaji ?: "Anime"
                val animeThumb = item.coverImage?.extraLarge ?: item.coverImage?.large ?: item.coverImage?.medium ?: item.image

                SAnime.create().apply {
                    url = idStr
                    title = animeTitle
                    thumbnail_url = animeThumb
                }
            }.getOrNull()
        }

        val hasNext = (dataElement as? JsonObject)?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull
            ?: (jsonElement["hasNextPage"]?.jsonPrimitive?.booleanOrNull ?: (animes.isNotEmpty()))

        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val idOrSlug = anime.url.removePrefix("/").substringBefore("?")
        val resolved = resolveSlugNative(idOrSlug)
        if (resolved != null) {
            val obj = resolved.second
            return SAnime.create().apply {
                url = resolved.first
                title = obj["titleEnglish"]?.jsonPrimitive?.content
                    ?: obj["titleRomaji"]?.jsonPrimitive?.content
                    ?: anime.title
                thumbnail_url = (obj["coverImage"] as? JsonObject)
                    ?.let { c -> c["extraLarge"]?.jsonPrimitive?.content ?: c["large"]?.jsonPrimitive?.content ?: c["medium"]?.jsonPrimitive?.content }
                    ?: obj["coverImage"]?.jsonPrimitive?.content
                    ?: anime.thumbnail_url
                genre = obj["genres"]?.jsonArray
                    ?.mapNotNull { el ->
                        when (el) {
                            is JsonObject -> el["name"]?.jsonPrimitive?.content
                            else -> el.jsonPrimitive.content.takeIf { it.isNotBlank() }
                        }
                    }
                    ?.joinToString()
                status = when (obj["status"]?.jsonPrimitive?.content?.uppercase()) {
                    "RELEASING" -> SAnime.ONGOING
                    "FINISHED" -> SAnime.COMPLETED
                    "NOT_YET_RELEASED" -> SAnime.LICENSED
                    else -> SAnime.UNKNOWN
                }
                initialized = true
                description = buildString {
                    obj["description"]?.jsonPrimitive?.content
                        ?.let { append(it.replace(Regex("<[^>]*>"), "")) }
                    obj["season"]?.jsonPrimitive?.content?.let {
                        append("\n\nSeason: $it ${obj["seasonYear"]?.jsonPrimitive?.content ?: ""}")
                    }
                    obj["format"]?.jsonPrimitive?.content?.let { append("\nFormat: $it") }
                }.trim()
            }
        }
        return anime
    }

    /** Resolves an AniList numeric ID (or existing slug) to Pair<slug, raw JsonObject>.
     *  Uses anidap.lol/api/anime/{id} which returns data.id == backend slug. */
    private fun resolveSlugNative(idOrSlug: String): Pair<String, JsonObject>? {
        return runCatching {
            val response = client.newCall(GET("$baseUrl/api/anime/$idOrSlug", headers)).execute()
            val obj = json.parseToJsonElement(response.body.string()).jsonObject
            val data = obj["data"]?.jsonObject ?: return null
            val slug = data["id"]?.jsonPrimitive?.content ?: return null
            Pair(slug, data)
        }.getOrNull()
    }

    private fun resolveSlug(idOrSlug: String): String = resolveSlugNative(idOrSlug)?.first ?: idOrSlug

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val rawId = anime.url.removePrefix("/").substringBefore("?")
        // If rawId is numeric (AniList ID), resolve to backend slug first
        val slug = if (rawId.toIntOrNull() != null) resolveSlug(rawId) else rawId

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
                val epStr = if (num == num.toLong().toFloat()) num.toLong().toString() else num.toString()
                url = "$slug?ep=$epStr"
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

        // Add preferred type first, then the other
        val addSub = { providers: List<ServerItem> ->
            providers.forEach { server ->
                if (!disabledServers.contains(server.id)) {
                    hosters.add(Hoster(hosterName = "SUB - ${server.id.uppercase()}", hosterUrl = "$animeId|$epNum|sub|${server.id}"))
                }
            }
        }
        val addDub = { providers: List<ServerItem> ->
            providers.forEach { server ->
                if (!disabledServers.contains(server.id)) {
                    hosters.add(Hoster(hosterName = "DUB - ${server.id.uppercase()}", hosterUrl = "$animeId|$epNum|dub|${server.id}"))
                }
            }
        }

        if (preferredType == "dub") {
            addDub(dubProviders)
            addSub(subProviders)
        } else {
            addSub(subProviders)
            addDub(dubProviders)
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
    private data class AnimeItem(
        val id: JsonPrimitive? = null,
        val malId: Long? = null,
        val title: TitleItem? = null,
        val image: String? = null,
        val coverImage: CoverImageItem? = null,
    )

    @Serializable
    private data class TitleItem(
        val english: String? = null,
        val romaji: String? = null,
        val userPreferred: String? = null,
    )

    @Serializable
    private data class CoverImageItem(
        val extraLarge: String? = null,
        val large: String? = null,
        val medium: String? = null,
    )

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

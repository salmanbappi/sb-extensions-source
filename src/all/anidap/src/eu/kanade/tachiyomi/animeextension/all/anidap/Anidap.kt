package eu.kanade.tachiyomi.animeextension.all.anidap

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Anidap :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Anidap"

    override val baseUrl = "https://anidap.lol"

    override val lang = "all"

    override val supportsLatest = true

    private val json: Json by lazy {
        Injekt.get<Json>()
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Application.MODE_PRIVATE)
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/anime/popular?page=$page", headers)
        val response = client.newCall(request).execute()
        return parseAnimePage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = GET("$baseUrl/api/anime/recent?page=$page", headers)
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
        val data = json.decodeFromString<AnimeListApiResponse>(body)
        val items = data.data ?: data.results ?: emptyList()
        val animes = items.map { item ->
            SAnime.create().apply {
                title = item.title?.english ?: item.title?.userPreferred ?: item.title?.romaji ?: "Anime"
                setUrlWithoutDomain(item.id.toString())
                thumbnail_url = item.coverImage?.extraLarge ?: item.coverImage?.large ?: item.coverImage?.medium
            }
        }
        val hasNext = data.hasNextPage ?: (animes.isNotEmpty() && data.currentPage != null)
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val animeId = anime.url.removePrefix("/").substringBefore("?")
        val request = GET("$baseUrl/api/anime/$animeId", headers)
        val response = client.newCall(request).execute()
        val detailsData = json.decodeFromString<AnimeDetailsApiResponse>(response.body.string())
        val detail = detailsData.data ?: throw Exception("Failed to parse anime details")

        return SAnime.create().apply {
            title = detail.title?.english ?: detail.title?.userPreferred ?: detail.title?.romaji ?: anime.title
            thumbnail_url = detail.coverImage?.extraLarge ?: detail.coverImage?.large ?: anime.thumbnail_url
            genre = detail.genres?.joinToString()
            author = detail.studios?.joinToString { it.name }
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
                    val fullStars = (score / 20).coerceIn(0, 5)
                    append("${"★".repeat(fullStars)}${"☆".repeat(5 - fullStars)} ${"%.1f".format(score / 10.0)}/10\n\n")
                }
                detail.description?.let { append(it.replace(Regex("<[^>]*>"), "")) }
                if (detail.season != null) append("\n\nSeason: ${detail.season} ${detail.year ?: ""}")
                if (detail.format != null) append("\nFormat: ${detail.format}")
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeId = anime.url.removePrefix("/").substringBefore("?")
        val request = GET("https://chad.anidap.lol/rest/api/episodes?id=$animeId", headers)
        val response = client.newCall(request).execute()
        val episodes = json.decodeFromString<List<EpisodeItem>>(response.body.string())

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
                url = "$animeId?ep=$num"
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
        val serversData = json.decodeFromString<ServersResponse>(response.body.string())

        val disabledServers = preferences.getStringSet("pref_disabled_servers", emptySet()) ?: emptySet()
        val preferredType = preferences.getString("pref_audio_type", "sub") ?: "sub"

        val allTasks = (serversData.subProviders.map { it to "sub" } + serversData.dubProviders.map { it to "dub" })
            .filter { (provider, _) -> provider.id.lowercase() !in disabledServers }

        val videos = coroutineScope {
            allTasks.map { (provider, apiType) ->
                async {
                    val providerId = provider.id
                    val categoryLabel = apiType.uppercase()
                    val subStyle = if (apiType == "dub") "" else " [Sub]"

                    if (provider.type == "embed" && provider.url != null) {
                        val embedUrl = provider.url
                        when {
                            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> {
                                runCatching {
                                    okruExtractor.videosFromUrl(embedUrl, prefix = "${providerId.uppercase()} ($categoryLabel)$subStyle")
                                }.getOrDefault(emptyList())
                            }

                            embedUrl.contains("mp4upload") -> {
                                runCatching {
                                    mp4uploadExtractor.videosFromUrl(embedUrl, prefix = "${providerId.uppercase()}: ($categoryLabel)$subStyle ")
                                }.getOrDefault(emptyList())
                            }

                            else -> {
                                listOf(Video(videoUrl = embedUrl, videoTitle = "${providerId.uppercase()} ($categoryLabel)$subStyle", headers = headers))
                            }
                        }
                    } else {
                        val sourcesRequest = GET("https://chad.anidap.lol/rest/api/sources?id=$animeId&epNum=$epNum&type=$apiType&providerId=$providerId", headers)
                        runCatching {
                            client.newCall(sourcesRequest).execute().use { sourcesResponse ->
                                if (!sourcesResponse.isSuccessful) return@async emptyList<Video>()
                                val sourcesData = json.decodeFromString<SourcesResponse>(sourcesResponse.body.string())
                                val subtitleTracks = sourcesData.tracks?.map { track ->
                                    Track(track.url, track.label ?: track.lang ?: "English")
                                } ?: emptyList()

                                sourcesData.sources.mapNotNull { source ->
                                    val rawUrl = source.url ?: return@mapNotNull null
                                    val transformedUrl = transformSourceUrl(rawUrl, providerId)
                                    val quality = source.quality ?: "Auto"
                                    val videoTitle = "${providerId.uppercase()}: $quality ($categoryLabel)$subStyle"

                                    if (transformedUrl.contains(".m3u8", ignoreCase = true)) {
                                        runCatching {
                                            playlistUtils.extractFromHls(
                                                playlistUrl = transformedUrl,
                                                videoNameGen = { hlsQuality -> "${providerId.uppercase()}: $hlsQuality ($categoryLabel)$subStyle" },
                                                subtitleList = subtitleTracks,
                                            )
                                        }.getOrElse {
                                            listOf(Video(transformedUrl, videoTitle, headers, subtitleTracks = subtitleTracks))
                                        }
                                    } else {
                                        listOf(Video(transformedUrl, videoTitle, headers, subtitleTracks = subtitleTracks))
                                    }
                                }.flatten()
                            }
                        }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten().toMutableList()
        }

        videos.sortWith(
            compareBy<Video> { video ->
                val matchesType = when (preferredType) {
                    "sub" -> video.videoTitle.contains("SUB", ignoreCase = true)
                    "dub" -> video.videoTitle.contains("DUB", ignoreCase = true)
                    else -> true
                }
                if (matchesType) 0 else 1
            }.thenBy { video ->
                val prefServer = preferences.getString("pref_server", "auto") ?: "auto"
                if (prefServer != "auto" && video.videoTitle.startsWith(prefServer, ignoreCase = true)) 0 else 1
            },
        )

        return listOf(Hoster(hosterName = "Anidap Servers", hosterUrl = "", videoList = videos))
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = hoster.videoList

    // ============================ Stream Transformations =============================
    private fun transformSourceUrl(url: String, providerId: String): String = when (providerId.lowercase()) {
        "shiro" -> build24StreamUrl(url, "https://kem.clvd.xyz/")

        "kami" -> build24StreamUrl(url, "https://krussdomi.com")

        "vee" -> if (url.startsWith("https://cdn.animeonsen.xyz")) url else build24StreamUrl(url, "https://www.animeonsen.xyz/")

        "yuki" -> buildAniwatchUrl(url, "https://megaplay.buzz")

        "uwu" -> buildAniwatchUrl(url, "https://kwik.cx/")

        "miku" -> buildAniwatchUrl(url, "https://allanime.uns.bio")

        "mochi" -> url.replace("https://tools.fast4speed.rsvp", "https://mp4.24stream.xyz/storage")

        "beep" -> when {
            url.startsWith("https://bd.24stream.xyz/media") -> url
            url.startsWith("/") -> "https://bd.24stream.xyz/media${url.replace("/r2", "")}"
            else -> "https://bd.24stream.xyz/media${url.replace(Regex("https?://[^/]+"), "").replace("/r2", "")}"
        }

        "mimi" -> url.replace("https://vivibebe.site/public/stream/", "https://hawk.aniwatchtv.site/media/")

        else -> url
    }

    private fun build24StreamUrl(url: String, origin: String): String {
        val bytes = url.toByteArray(Charsets.UTF_8)
        val xorKey = 137
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() xor xorKey) }
        return "https://crs.24stream.xyz/media/$hex&origin=$origin"
    }

    private fun buildAniwatchUrl(url: String, referer: String): String {
        val key = "10b06cdc1ca48c9fb0b94af97cc040cf".toByteArray(Charsets.UTF_8)
        val textBytes = "$url\u0000$referer".toByteArray(Charsets.UTF_8)
        val encrypted = ByteArray(textBytes.size)
        for (i in textBytes.indices) {
            encrypted[i] = (textBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        val encoded = Base64.encodeToString(encrypted, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")

        val aniwatchDomains = arrayOf(
            "https://cx.aniwatchtv.site",
            "https://nsx.aniwatchtv.site",
            "https://pro.aniwatchtv.site",
            "https://rl2.aniwatchtv.site",
            "https://rrl.aniwatchtv.site",
        )
        val host = aniwatchDomains.random()
        return "$host/uwu/$encoded"
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = "pref_audio_type",
            default = "sub",
            title = "Preferred Audio Type",
            summary = "Select preferred audio format",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("sub", "dub"),
        )

        screen.addListPreference(
            key = "pref_server",
            default = "auto",
            title = "Preferred Server",
            summary = "Select default server to play first",
            entries = listOf("Auto", "Mimi", "Beep", "Yuki", "Kiwi", "Loli", "Miku", "Shiro", "Kami"),
            entryValues = listOf("auto", "mimi", "beep", "yuki", "kiwi", "loli", "miku", "shiro", "kami"),
        )

        screen.addSetPreference(
            key = "pref_disabled_servers",
            default = emptySet(),
            title = "Disabled Servers",
            summary = "Select servers to disable",
            entries = listOf("Mimi", "Beep", "Yuki", "Kiwi", "Loli", "Miku", "Shiro", "Kami"),
            entryValues = listOf("mimi", "beep", "yuki", "kiwi", "loli", "miku", "shiro", "kami"),
        )

        screen.addSwitchPreference(
            key = "pref_load_thumbnails",
            default = true,
            title = "Load Episode Thumbnails",
            summary = "Fetch and show preview thumbnails for episodes",
        )

        screen.addSwitchPreference(
            key = "pref_load_titles",
            default = true,
            title = "Load Episode Titles",
            summary = "Fetch custom titles for episodes",
        )

        screen.addSwitchPreference(
            key = "pref_load_descriptions",
            default = true,
            title = "Load Episode Summaries",
            summary = "Fetch synopsis descriptions for episodes",
        )
    }

    // Data Models
    @Serializable
    private data class AnimeListApiResponse(
        val currentPage: Int? = null,
        val hasNextPage: Boolean? = null,
        val data: List<AnimeItem>? = null,
        val results: List<AnimeItem>? = null,
    )

    @Serializable
    private data class AnimeItem(
        val id: Long? = null,
        val title: TitleItem? = null,
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
    private data class AnimeDetailsApiResponse(
        val data: AnimeDetailsItem? = null,
    )

    @Serializable
    private data class AnimeDetailsItem(
        val id: Long? = null,
        val title: TitleItem? = null,
        val coverImage: CoverImageItem? = null,
        val description: String? = null,
        val averageScore: Double? = null,
        val genres: List<String>? = null,
        val status: String? = null,
        val season: String? = null,
        val year: Int? = null,
        val format: String? = null,
        val studios: List<StudioItem>? = null,
    )

    @Serializable
    private data class StudioItem(
        val name: String,
    )

    @Serializable
    private data class EpisodeItem(
        val number: Float? = null,
        val episodeNumber: Float? = null,
        val title: String? = null,
        val img: String? = null,
        val description: String? = null,
        val hasSub: Boolean? = null,
        val hasDub: Boolean? = null,
    )

    @Serializable
    private data class ServersResponse(
        val subProviders: List<ProviderItem> = emptyList(),
        val dubProviders: List<ProviderItem> = emptyList(),
    )

    @Serializable
    private data class ProviderItem(
        val id: String,
        val type: String? = null,
        val url: String? = null,
    )

    @Serializable
    private data class SourcesResponse(
        val sources: List<SourceItem> = emptyList(),
        val tracks: List<TrackItem>? = null,
    )

    @Serializable
    private data class SourceItem(
        val url: String? = null,
        val quality: String? = null,
    )

    @Serializable
    private data class TrackItem(
        val url: String,
        val label: String? = null,
        val lang: String? = null,
    )
}

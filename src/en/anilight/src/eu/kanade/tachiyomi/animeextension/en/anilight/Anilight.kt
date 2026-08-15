package eu.kanade.tachiyomi.animeextension.en.anilight

import androidx.preference.ListPreference
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class Anilight : Source() {

    override val name = "AniLight"

    override val baseUrl = "https://anilight.live"

    override val lang = "en"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val m3u8Integration by lazy { M3u8Integration(client) }

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 6, period = 1.seconds)
            .build()
    }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = "$API_BASE/filter".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "POPULARITY_DESC")
            .build()
            .toString()

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<FilterResponseDto>(json)
        val animes = (dto.media ?: emptyList()).map { it.toSAnime(getTitleLangPref()) }
        return AnimesPage(animes, dto.pageInfo?.hasNextPage ?: false)
    }

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page == 1) {
            val response = client.newCall(GET("$API_BASE/homepage", headers)).execute()
            val dto = response.parseAs<HomepageResponseDto>(json)
            val recentList = dto.recentlyAddedEpisodes
            if (!recentList.isNullOrEmpty()) {
                return AnimesPage(recentList.map { it.toSAnime(getTitleLangPref()) }, true)
            }
        }

        val url = "$API_BASE/filter".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "TRENDING_DESC")
            .build()
            .toString()

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<FilterResponseDto>(json)
        val animes = (dto.media ?: emptyList()).map { it.toSAnime(getTitleLangPref()) }
        return AnimesPage(animes, dto.pageInfo?.hasNextPage ?: false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = "$API_BASE/filter".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("search", query.trim())
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
                        urlBuilder.addQueryParameter("format", format)
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
                    if (year.isNotBlank() && year.toIntOrNull() != null) {
                        urlBuilder.addQueryParameter("seasonYear", year)
                    }
                }

                is Filters.GenreFilter -> {
                    val included = filter.getIncluded()
                    if (included.isNotEmpty()) {
                        urlBuilder.addQueryParameter("genres", included.joinToString(","))
                    }
                }

                else -> {}
            }
        }

        if (!sortApplied && query.isBlank()) {
            urlBuilder.addQueryParameter("sort", "POPULARITY_DESC")
        }

        val response = client.newCall(GET(urlBuilder.build().toString(), headers)).execute()
        val dto = response.parseAs<FilterResponseDto>(json)
        val animes = (dto.media ?: emptyList()).map { it.toSAnime(getTitleLangPref()) }
        return AnimesPage(animes, dto.pageInfo?.hasNextPage ?: false)
    }

    override fun getFilterList(): AnimeFilterList = Filters.getFilterList()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val slug = anime.url.substringBefore("#").trim('/')
        val response = client.newCall(GET("$API_BASE/anime/$slug", headers)).execute()
        val item = response.parseAs<MediaItemDto>(json)

        return anime.apply {
            val titlePref = getTitleLangPref()
            title = item.getTitle(titlePref)
            thumbnail_url = item.coverImage?.extraLarge ?: item.coverImage?.large
            description = buildString {
                item.description?.let {
                    append(it.replace(Regex("<[^>]*>"), "").trim())
                    append("\n\n")
                }
                item.season?.let { s ->
                    append("Season: ${s.lowercase().replaceFirstChar { it.uppercase() }}")
                    item.seasonYear?.let { y -> append(" $y") }
                    append("\n")
                }
                item.format?.let { append("Format: $it\n") }
                item.episodes?.let { append("Episodes: $it\n") }
                item.averageScore?.let { append("Score: $it%\n") }
                item.studios?.nodes?.mapNotNull { it.name }?.distinct()?.takeIf { it.isNotEmpty() }?.let {
                    append("Studios: ${it.joinToString()}\n")
                }
            }.trim()
            genre = item.genres?.joinToString()
            status = when (item.status?.uppercase()) {
                "RELEASING", "ONGOING" -> SAnime.ONGOING
                "FINISHED", "COMPLETED" -> SAnime.COMPLETED
                "CANCELLED" -> SAnime.CANCELLED
                "HIATUS", "ON_HIATUS" -> SAnime.ON_HIATUS
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val slug = anime.url.substringBefore("#").trim('/')
        val response = client.newCall(GET("$API_BASE/watch/$slug", headers)).execute()
        val dto = response.parseAs<WatchResponseDto>(json)
        val animeId = dto.id ?: return emptyList()

        return (dto.episodes ?: emptyList()).map { ep ->
            val epNum = ep.number ?: 1f
            val epNumInt = if (epNum % 1f == 0f) epNum.toInt().toString() else epNum.toString()
            val epTitle = ep.title?.trim()

            SEpisode.create().apply {
                name = when {
                    epTitle.isNullOrBlank() || epTitle.equals("Episode $epNumInt", true) -> "Episode $epNumInt"
                    else -> "Episode $epNumInt: $epTitle"
                }
                episode_number = epNum
                url = "$slug#id=$animeId&ep=$epNum"
                scanlator = when {
                    ep.embed_url?.sub != null && ep.embed_url.dub != null -> "Sub & Dub"
                    ep.embed_url?.dub != null -> "Dub"
                    else -> "Sub"
                }
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val slug = episode.url.substringBefore("#")
        val fragment = episode.url.substringAfter("#", "")
        val params = fragment.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }

        val animeId = params["id"]?.takeIf { it.isNotBlank() }
        val epNum = params["ep"]?.takeIf { it.isNotBlank() } ?: episode.episode_number.toString()

        if (animeId == null) {
            return emptyList()
        }

        val response = client.newCall(GET("$API_BASE/watch/$slug", headers)).execute()
        val dto = response.parseAs<WatchResponseDto>(json)
        val servers = dto.servers

        val providerMap = mutableMapOf<String, MutableList<String>>()

        servers?.subProviders?.forEach { p ->
            p.id?.let { pid ->
                val typeLabel = if (p.tip?.contains("Soft Sub", ignoreCase = true) == true) "soft-sub" else "sub"
                providerMap.getOrPut(pid) { mutableListOf() }.add(typeLabel)
            }
        }

        servers?.dubProviders?.forEach { p ->
            p.id?.let { pid ->
                providerMap.getOrPut(pid) { mutableListOf() }.add("dub")
            }
        }

        if (providerMap.isEmpty()) {
            listOf("misa", "near", "rem", "misora", "light", "raye", "ryu").forEach { pid ->
                providerMap[pid] = mutableListOf("sub", "dub")
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return providerMap.map { (providerId, types) ->
            val displayName = providerId.replaceFirstChar { it.uppercase() }
            Hoster(
                hosterName = displayName,
                hosterUrl = "$animeId|$epNum|$providerId|${types.distinct().joinToString(",")}",
            )
        }.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        if (parts.size < 4) return emptyList()

        val animeId = parts[0]
        val epNum = parts[1]
        val providerId = parts[2]
        val types = parts[3].split(",").filter { it.isNotBlank() }

        val videos = types.parallelCatchingFlatMap { rawType ->
            val apiType = if (rawType.equals("dub", ignoreCase = true)) "dub" else "sub"
            val url = "$API_BASE/sources?id=${URLEncoder.encode(animeId, "UTF-8")}&epNum=${URLEncoder.encode(epNum, "UTF-8")}&type=${URLEncoder.encode(apiType, "UTF-8")}&providerId=${URLEncoder.encode(providerId, "UTF-8")}"

            val response = try {
                client.newCall(GET(url, headers)).execute()
            } catch (_: Exception) {
                return@parallelCatchingFlatMap emptyList<Video>()
            }

            if (!response.isSuccessful) return@parallelCatchingFlatMap emptyList<Video>()

            val sourcesDto = try {
                response.parseAs<SourcesResponseDto>(json)
            } catch (_: Exception) {
                return@parallelCatchingFlatMap emptyList<Video>()
            }

            val subtitleTracks = (sourcesDto.tracks ?: emptyList()).mapNotNull { track ->
                val subUrl = track.url ?: return@mapNotNull null
                val resolvedSubUrl = if (subUrl.contains("1oe.lostproject.club")) {
                    "$API_BASE/proxy/captions?url=${URLEncoder.encode(subUrl, "UTF-8")}"
                } else {
                    subUrl
                }
                Track(
                    url = resolvedSubUrl,
                    lang = track.label ?: track.lang ?: "English",
                )
            }

            val audioBadge = when {
                apiType == "dub" -> "[Dub]"
                rawType.equals("soft-sub", ignoreCase = true) || subtitleTracks.isNotEmpty() -> "[Soft Sub]"
                else -> "[Sub]"
            }

            val typeVideos = mutableListOf<Video>()

            for (src in sourcesDto.sources ?: emptyList()) {
                val rawUrl = src.url ?: continue
                val streamUrl = resolveStreamUrl(rawUrl)
                val streamHeaders = resolveStreamHeaders(streamUrl)

                try {
                    if (streamUrl.contains(".m3u8", ignoreCase = true) || streamUrl.contains("/proxy", ignoreCase = true)) {
                        val hlsVideos = playlistUtils.extractFromHls(
                            playlistUrl = streamUrl,
                            masterHeaders = streamHeaders,
                            videoHeaders = streamHeaders,
                            videoNameGen = { quality -> "$quality $audioBadge" },
                            subtitleList = subtitleTracks,
                        )
                        typeVideos.addAll(hlsVideos)
                    } else {
                        typeVideos.add(
                            Video(
                                videoUrl = streamUrl,
                                videoTitle = "${src.quality ?: "HD"} $audioBadge",
                                headers = streamHeaders,
                                subtitleTracks = subtitleTracks,
                            ),
                        )
                    }
                } catch (_: Exception) {
                    typeVideos.add(
                        Video(
                            videoUrl = streamUrl,
                            videoTitle = "${src.quality ?: "HD"} $audioBadge",
                            headers = streamHeaders,
                            subtitleTracks = subtitleTracks,
                        ),
                    )
                }
            }

            typeVideos
        }

        val sorted = videos.sortVideos()
        return m3u8Integration.processVideoList(sorted)
    }

    private fun resolveStreamUrl(rawUrl: String): String {
        val url = rawUrl
            .replace("vibeplayer.site", "vivibebe.site")
            .replace("bd.24stream.xyz", "bd.aniwatchtv.site")
            .replace("ncdn.mewstream.buzz/anime/", "03nc1.livedns.my/anime/")
            .replace("cdn.mewstream.buzz/anime/", "03nc1.livedns.my/anime/")
            .replace("s2.cinewave2.site/anime/", "03nc1.livedns.my/anime/")
            .replace("s1.streamzone1.site/anime/", "03nc1.livedns.my/anime/")

        val workerDomains = listOf(
            "cdn.mewstream.buzz",
            "j5b9s.streamzone1.site",
            "9hjkrt.nekostream.site",
            "e7nv.sparqle.click",
            "j3nd.voltara.click",
            "p4m9q.cinewave2.site",
            "megap.kotocdn.site",
            "03nc1.livedns.my",
        )
        val apiProxyDomains = listOf(
            "vivibebe.site",
            "vibeplayer.site",
            "bd.24stream.xyz",
            "bd.aniwatchtv.site",
        )

        return when {
            workerDomains.any { url.contains(it) } -> {
                "$API_BASE/lb/misa/proxy?url=${URLEncoder.encode(url, "UTF-8")}"
            }

            url.contains("hls.anidb.app") -> {
                "$API_BASE/lb/near/proxy?url=${URLEncoder.encode(url, "UTF-8")}"
            }

            apiProxyDomains.any { url.contains(it) } -> {
                "$API_BASE/proxy?url=${URLEncoder.encode(url, "UTF-8")}"
            }

            else -> url
        }
    }

    private fun resolveStreamHeaders(streamUrl: String): Headers = when {
        streamUrl.contains("animegg.org") -> headers.newBuilder()
            .set("Referer", "https://www.animegg.org/")
            .build()

        else -> headers
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains("[$prefAudio]", ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    private fun getTitleLangPref(): String = preferences.getString(PREF_TITLE_LANG_KEY, PREF_TITLE_LANG_DEFAULT) ?: PREF_TITLE_LANG_DEFAULT

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TITLE_LANG_KEY
            title = "Preferred Title Language"
            entries = arrayOf("English", "Romaji", "Native")
            entryValues = arrayOf("english", "romaji", "native")
            setDefaultValue(PREF_TITLE_LANG_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred Audio"
            entries = arrayOf("Soft Sub", "Sub", "Dub")
            entryValues = arrayOf("Soft Sub", "Sub", "Dub")
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = arrayOf("Misa", "Near", "Rem", "Misora", "Light", "Raye", "Ryu")
            entryValues = arrayOf("misa", "near", "rem", "misora", "light", "raye", "ryu")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Auto")
            entryValues = arrayOf("1080", "720", "480", "360", "Auto")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val API_BASE = "https://api.anilight.live/api"

        private const val PREF_TITLE_LANG_KEY = "pref_title_lang"
        private const val PREF_TITLE_LANG_DEFAULT = "english"

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "Soft Sub"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "misa"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class MediaTitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class ImageDto(
    val large: String? = null,
    val extraLarge: String? = null,
    val color: String? = null,
)

@Serializable
data class MediaItemDto(
    val id: Long? = null,
    val slug: String? = null,
    val anilistId: Long? = null,
    val idMal: Long? = null,
    val title: MediaTitleDto? = null,
    val coverImage: ImageDto? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val episodes: Int? = null,
    val duration: Int? = null,
    val status: String? = null,
    val source: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val format: String? = null,
    val studios: StudiosDto? = null,
) {
    fun getTitle(pref: String = "english"): String = when (pref.lowercase()) {
        "romaji" -> title?.romaji?.takeIf { it.isNotBlank() }
        "native" -> title?.native?.takeIf { it.isNotBlank() }
        else -> title?.english?.takeIf { it.isNotBlank() }
    } ?: title?.english?.takeIf { it.isNotBlank() }
        ?: title?.romaji?.takeIf { it.isNotBlank() }
        ?: title?.native?.takeIf { it.isNotBlank() }
        ?: "Untitled"

    fun toSAnime(titlePref: String = "english"): SAnime = SAnime.create().apply {
        title = getTitle(titlePref)
        url = slug ?: id?.toString() ?: ""
        thumbnail_url = coverImage?.extraLarge ?: coverImage?.large
        description = this@MediaItemDto.description?.replace(Regex("<[^>]*>"), "")?.trim()
        genre = genres?.joinToString()
        status = when (this@MediaItemDto.status?.uppercase()) {
            "RELEASING", "ONGOING" -> SAnime.ONGOING
            "FINISHED", "COMPLETED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            "HIATUS", "ON_HIATUS" -> SAnime.ON_HIATUS
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
    }
}

@Serializable
data class PageInfoDto(
    val currentPage: Int? = null,
    val hasNextPage: Boolean? = null,
    val lastPage: Int? = null,
    val perPage: Int? = null,
    val total: Int? = null,
)

@Serializable
data class FilterResponseDto(
    val pageInfo: PageInfoDto? = null,
    val media: List<MediaItemDto>? = null,
)

@Serializable
data class HomepageResponseDto(
    val recentlyAddedEpisodes: List<MediaItemDto>? = null,
)

@Serializable
data class EmbedUrlDto(
    val sub: String? = null,
    val dub: String? = null,
)

@Serializable
data class EpisodeDto(
    val number: Float? = null,
    val title: String? = null,
    val jp_title: String? = null,
    val description: String? = null,
    val img: String? = null,
    val isFiller: Boolean? = null,
    val embed_url: EmbedUrlDto? = null,
)

@Serializable
data class ProviderDto(
    val id: String? = null,
    val tip: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class ServersDto(
    val subProviders: List<ProviderDto>? = null,
    val dubProviders: List<ProviderDto>? = null,
)

@Serializable
data class WatchResponseDto(
    val id: Long? = null,
    val episodes: List<EpisodeDto>? = null,
    val servers: ServersDto? = null,
)

@Serializable
data class SourceStreamDto(
    val url: String? = null,
    val quality: String? = null,
)

@Serializable
data class TrackDto(
    val id: String? = null,
    val url: String? = null,
    val kind: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val default: Boolean? = null,
)

@Serializable
data class SourcesResponseDto(
    val sources: List<SourceStreamDto>? = null,
    val tracks: List<TrackDto>? = null,
)

@Serializable
data class StudioNodeDto(
    val id: Long? = null,
    val name: String? = null,
)

@Serializable
data class StudiosDto(
    val nodes: List<StudioNodeDto>? = null,
)

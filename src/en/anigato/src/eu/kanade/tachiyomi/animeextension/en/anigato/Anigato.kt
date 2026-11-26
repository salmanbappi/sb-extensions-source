package eu.kanade.tachiyomi.animeextension.en.anigato

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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.addSwitchPreference
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

class Anigato : Source() {

    override val name = "Anigato"

    override val baseUrl = "https://anigato.lol"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept", "application/json, text/plain, */*")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchFeed("popular", page)

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = fetchFeed("recent", page)

    private fun fetchFeed(kind: String, page: Int): AnimesPage {
        val url = "$baseUrl/api/catalog/feed".toHttpUrl().newBuilder()
            .addQueryParameter("kind", kind)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", PER_PAGE.toString())
            .build()
        val dto = client.newCall(GET(url, headers)).execute().parseAs<CatalogResponseDto>(json)
        val animes = dto.media.orEmpty().map { it.toSAnime(titlePref()) }
        return AnimesPage(animes, dto.hasNextPage ?: false)
    }

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val sort = filters.filterIsInstance<Filters.SortFilter>().firstOrNull()?.toUriPart()
            ?.takeIf { it.isNotEmpty() } ?: "POPULARITY_DESC"
        val format = filters.filterIsInstance<Filters.FormatFilter>().firstOrNull()?.toUriPart().orEmpty()
        val status = filters.filterIsInstance<Filters.StatusFilter>().firstOrNull()?.toUriPart().orEmpty()
        val season = filters.filterIsInstance<Filters.SeasonFilter>().firstOrNull()?.toUriPart().orEmpty()
        val year = filters.filterIsInstance<Filters.YearFilter>().firstOrNull()?.state?.trim().orEmpty()
        val genres = filters.filterIsInstance<Filters.GenreFilter>().firstOrNull()?.getIncluded().orEmpty()

        val url = "$baseUrl/api/catalog/search".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            if (genres.isNotEmpty()) addQueryParameter("genres", genres.joinToString(","))
            if (season.isNotEmpty()) addQueryParameter("season", season)
            if (year.isNotEmpty()) addQueryParameter("year", year)
            if (format.isNotEmpty()) addQueryParameter("format", format)
            if (status.isNotEmpty()) addQueryParameter("status", status)
            addQueryParameter("sort", sort)
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", PER_PAGE.toString())
        }.build()

        val dto = client.newCall(GET(url, headers)).execute().parseAs<CatalogResponseDto>(json)
        val animes = dto.media.orEmpty().map { it.toSAnime(titlePref()) }
        return AnimesPage(animes, dto.hasNextPage ?: false)
    }

    override fun getFilterList(): AnimeFilterList = Filters.build()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val id = anime.url.trim('/')
        val dto = client.newCall(GET("$baseUrl/api/catalog/anime/$id", headers))
            .execute()
            .parseAs<AnimeMediaDto>(json)
        return dto.toSAnime(titlePref()).apply {
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val id = anime.url.trim('/')
        val epUrl = "$baseUrl/api/episodes/$id".toHttpUrl().newBuilder()
            .addQueryParameter("title", anime.title)
            .build()

        val dto = runCatching {
            client.newCall(GET(epUrl, headers)).execute().parseAs<EpisodesResponseDto>(json)
        }.getOrNull()

        val episodes = dto?.episodes.orEmpty()
        if (episodes.isNotEmpty()) {
            return episodes.mapIndexed { idx, ep ->
                val num = ep.number ?: (idx + 1).toFloat()
                val numInt = num.toInt()
                val numStr = if (num == numInt.toFloat()) numInt.toString() else num.toString()
                val epTitle = ep.title?.trim().orEmpty()
                SEpisode.create().apply {
                    episode_number = num
                    name = if (epTitle.isNotBlank() && !epTitle.equals("Episode $numStr", ignoreCase = true)) {
                        "Episode $numStr: $epTitle"
                    } else {
                        "Episode $numStr"
                    }
                    date_upload = parseIsoDate(ep.airDate)
                    url = json.encodeToString(
                        EpisodeData(
                            id = id,
                            number = numStr,
                            title = anime.title,
                        ),
                    )
                }
            }.reversed()
        }

        // Fallback using total episode count from anime details
        val details = runCatching {
            client.newCall(GET("$baseUrl/api/catalog/anime/$id", headers))
                .execute()
                .parseAs<AnimeMediaDto>(json)
        }.getOrNull()
        val count = details?.episodes ?: 0
        if (count <= 0) return emptyList()

        return (count downTo 1).map { epNum ->
            SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episode $epNum"
                url = json.encodeToString(
                    EpisodeData(
                        id = id,
                        number = epNum.toString(),
                        title = anime.title,
                    ),
                )
            }
        }
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val data = runCatching { episode.url.parseAs<EpisodeData>(json) }.getOrNull()
            ?: return emptyList()

        val audioPref = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val modes = when (audioPref) {
            "sub" -> listOf("sub")
            "dub" -> listOf("dub")
            "dub_first" -> listOf("dub", "sub")
            else -> listOf("sub", "dub")
        }

        val hosters = mutableListOf<Hoster>()
        for (mode in modes) {
            val watchUrl = "$baseUrl/api/watch/${data.id}/$mode/${data.number}".toHttpUrl().newBuilder()
                .addQueryParameter("title", data.title)
                .build()

            val watchDto = runCatching {
                client.newCall(GET(watchUrl, headers)).execute().parseAs<WatchResponseDto>(json)
            }.getOrNull() ?: continue

            for (stream in watchDto.streams.orEmpty()) {
                val rawUrl = stream.url?.takeIf { it.isNotBlank() } ?: continue
                val absUrl = toAbsoluteUrl(rawUrl)
                val serverLabel = listOfNotNull(
                    stream.serverName?.takeIf { it.isNotBlank() },
                    stream.name?.takeIf { it.isNotBlank() && !it.equals(stream.serverName, ignoreCase = true) },
                ).joinToString(" - ").ifBlank { "Server" }

                hosters.add(
                    Hoster(
                        hosterName = "$serverLabel [${mode.uppercase()}]",
                        hosterUrl = json.encodeToString(
                            HosterData(
                                url = absUrl,
                                headers = stream.headers,
                                subtitles = stream.subtitles.orEmpty(),
                            ),
                        ),
                    ),
                )
            }
        }
        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val data = runCatching { hoster.hosterUrl.parseAs<HosterData>(json) }.getOrNull()
            ?: return emptyList()

        val includeMachineSubs = preferences.getBoolean(PREF_MACHINE_SUBS_KEY, false)
        val tracks = data.subtitles
            .filter { includeMachineSubs || it.machine != true }
            .mapNotNull { sub ->
                val subUrl = sub.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = sub.label?.takeIf { it.isNotBlank() }
                    ?: sub.lang?.takeIf { it.isNotBlank() }
                    ?: "Subtitle"
                Track(toAbsoluteUrl(subUrl), label)
            }

        val streamHeadersBuilder = headers.newBuilder()
        data.headers?.forEach { (k, v) ->
            streamHeadersBuilder.set(k, v)
        }
        val streamHeaders = streamHeadersBuilder.build()

        val videos = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = data.url,
                referer = "$baseUrl/",
                masterHeaders = streamHeaders,
                videoHeaders = streamHeaders,
                subtitleList = tracks,
                videoNameGen = { quality -> quality },
            )
        }.getOrDefault(emptyList())

        val result = videos.ifEmpty {
            listOf(
                Video(
                    videoUrl = data.url,
                    videoTitle = "Auto",
                    headers = streamHeaders,
                    subtitleTracks = tracks,
                ),
            )
        }
        return result.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(quality, ignoreCase = true) },
        )
    }

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = arrayOf("1080p", "720p", "480p", "360p"),
            entryValues = arrayOf("1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            title = "Preferred audio",
            entries = arrayOf("Sub & Dub (Sub first)", "Sub & Dub (Dub first)", "Sub only", "Dub only"),
            entryValues = arrayOf("both", "dub_first", "sub", "dub"),
            default = PREF_AUDIO_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_TITLE_KEY,
            title = "Preferred title language",
            entries = arrayOf("English", "Romaji", "Native"),
            entryValues = arrayOf("english", "romaji", "native"),
            default = PREF_TITLE_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_MACHINE_SUBS_KEY,
            title = "Include machine-translated subtitles",
            summary = "Show auto-translated subtitle tracks in addition to human-authored ones",
            default = false,
        )
    }

    // ============================== Helpers ===============================

    private fun titlePref(): String =
        preferences.getString(PREF_TITLE_KEY, PREF_TITLE_DEFAULT) ?: PREF_TITLE_DEFAULT

    private fun toAbsoluteUrl(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    private fun parseIsoDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching {
            ISO_DATE_FORMAT.get()?.parse(raw)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val PER_PAGE = 24

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "both"

        private const val PREF_TITLE_KEY = "pref_title"
        private const val PREF_TITLE_DEFAULT = "english"

        private const val PREF_MACHINE_SUBS_KEY = "pref_machine_subs"

        private val ISO_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}

// ==============================================================================
// DTOs
// ==============================================================================

@Serializable
data class CatalogResponseDto(
    val media: List<AnimeMediaDto>? = null,
    val hasNextPage: Boolean? = null,
)

@Serializable
data class AnimeMediaDto(
    val id: Int? = null,
    val title: TitleDto? = null,
    val coverImage: CoverImageDto? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val format: String? = null,
    val status: String? = null,
    val episodes: Int? = null,
    val genres: List<String>? = null,
    val studios: StudiosDto? = null,
) {
    fun toSAnime(titlePref: String): SAnime = SAnime.create().apply {
        url = (this@AnimeMediaDto.id ?: 0).toString()
        title = this@AnimeMediaDto.title?.pick(titlePref) ?: ""
        thumbnail_url = coverImage?.extraLarge
            ?: coverImage?.large
            ?: coverImage?.medium
            ?: bannerImage
        description = this@AnimeMediaDto.description?.let {
            Jsoup.parse(it).text().trim()
        }
        genre = genres?.joinToString()
        author = studios?.nodes?.mapNotNull { it.name?.takeIf(String::isNotBlank) }?.joinToString()
        status = when (this@AnimeMediaDto.status?.uppercase()) {
            "RELEASING", "HIATUS" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
    }
}

@Serializable
data class TitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
) {
    fun pick(pref: String): String = when (pref) {
        "romaji" -> romaji ?: english ?: userPreferred ?: native ?: ""
        "native" -> native ?: romaji ?: english ?: userPreferred ?: ""
        else -> english ?: romaji ?: userPreferred ?: native ?: ""
    }
}

@Serializable
data class CoverImageDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class StudiosDto(
    val nodes: List<StudioNodeDto>? = null,
)

@Serializable
data class StudioNodeDto(
    val name: String? = null,
)

@Serializable
data class EpisodesResponseDto(
    val success: Boolean? = null,
    val episodes: List<EpisodeDto>? = null,
)

@Serializable
data class EpisodeDto(
    val id: String? = null,
    val number: Float? = null,
    val title: String? = null,
    val airDate: String? = null,
)

@Serializable
data class EpisodeData(
    val id: String = "",
    val number: String = "",
    val title: String = "",
)

@Serializable
data class WatchResponseDto(
    val success: Boolean? = null,
    val streams: List<StreamDto>? = null,
)

@Serializable
data class StreamDto(
    val serverName: String? = null,
    val name: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<SubtitleDto>? = null,
)

@Serializable
data class SubtitleDto(
    val url: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val machine: Boolean? = null,
)

@Serializable
data class HosterData(
    val url: String = "",
    val headers: Map<String, String>? = null,
    val subtitles: List<SubtitleDto> = emptyList(),
)

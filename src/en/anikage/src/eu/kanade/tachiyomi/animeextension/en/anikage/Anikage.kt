package eu.kanade.tachiyomi.animeextension.en.anikage

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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

class Anikage : Source() {

    override val name = "Anikage"

    override val baseUrl = "https://anikage.cc"

    private val apiUrl = "$baseUrl/api/media"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse(page, sort = "popularity")

    // ============================== Latest ================================

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse(page, sort = "updated")

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val sort = filters.filterIsInstance<Filters.SortFilter>().firstOrNull()?.toUriPart()
            ?.takeIf { it.isNotEmpty() } ?: if (query.isBlank()) "popularity" else "score"
        val format = filters.filterIsInstance<Filters.FormatFilter>().firstOrNull()?.toUriPart().orEmpty()
        val status = filters.filterIsInstance<Filters.StatusFilter>().firstOrNull()?.toUriPart().orEmpty()
        val season = filters.filterIsInstance<Filters.SeasonFilter>().firstOrNull()?.toUriPart().orEmpty()
        val year = filters.filterIsInstance<Filters.YearFilter>().firstOrNull()?.state?.trim().orEmpty()
        val genres = filters.filterIsInstance<Filters.GenreFilter>().firstOrNull()?.getIncluded().orEmpty()

        return browse(
            page = page,
            sort = sort,
            query = query,
            format = format,
            status = status,
            season = season,
            year = year,
            genres = genres,
        )
    }

    override fun getFilterList(): AnimeFilterList = Filters.build()

    private suspend fun browse(
        page: Int,
        sort: String,
        query: String = "",
        format: String = "",
        status: String = "",
        season: String = "",
        year: String = "",
        genres: List<String> = emptyList(),
    ): AnimesPage {
        val showAdult = preferences.getBoolean(PREF_ADULT_KEY, false)
        val url = "$apiUrl/anime/browse".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            addQueryParameter("sort", sort)
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "25")
            addQueryParameter("adult", showAdult.toString())
            if (format.isNotEmpty()) addQueryParameter("format", format)
            if (status.isNotEmpty()) addQueryParameter("status", status)
            if (season.isNotEmpty()) addQueryParameter("season", season)
            if (year.isNotEmpty()) {
                addQueryParameter("yearMin", year)
                addQueryParameter("yearMax", year)
            }
            if (genres.isNotEmpty()) addQueryParameter("genres", genres.joinToString(","))
        }.build()

        val response = client.newCall(GET(url, headers)).execute()
        val dto = response.parseAs<BrowseResponseDto>()
        val animes = dto.data.map { it.toSAnime() }
        return AnimesPage(animes, dto.hasNext)
    }

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$apiUrl/anime/${anime.url}", headers)).execute()
        val dto = response.parseAs<DetailsResponseDto>()
        return (dto.anime ?: AnimeItemDto(slug = anime.url)).toSAnime().apply {
            initialized = true
        }
    }

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$apiUrl/anime/${anime.url}/episodes", headers)).execute()
        val episodes = response.parseAs<List<EpisodeDto>>()
        return episodes
            .map { it.toSEpisode(anime.url) }
            .sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val (slug, epNum) = parseEpisodeUrl(episode.url)

        val serversResponse = client.newCall(
            GET("$apiUrl/anime/$slug/episodes/$epNum/servers", headers),
        ).execute()
        val serverData = serversResponse.parseAs<ServersResponseDto>()

        // Only the "koto" provider exposes resolvable MegaPlay embeds.
        val koto = serverData.servers.firstOrNull { it.id == "koto" } ?: return emptyList()

        val hosters = koto.subTypes.flatMap { lang ->
            val srcResponse = client.newCall(
                GET("$apiUrl/anime/$slug/episodes/$epNum/sources?provider=koto&lang=$lang&server=koto", headers),
            ).execute()
            val sources = runCatching { srcResponse.parseAs<SourcesResponseDto>() }.getOrNull()
                ?: return@flatMap emptyList<Hoster>()

            sources.sources
                .mapNotNull { it.embedUrl }
                .filter { "megaplay.buzz" in it }
                .distinct()
                .map { embed ->
                    val variant = when {
                        embed.endsWith("/hsub") -> "Hardsub"
                        embed.endsWith("/dub") -> "Dub"
                        else -> "Softsub"
                    }
                    val display = "MegaPlay - ${lang.uppercase()} ($variant)"
                    Hoster(hosterName = display, hosterUrl = embed)
                }
        }.distinctBy { it.hosterUrl }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = runCatching { megaPlayVideos(hoster.hosterName, hoster.hosterUrl) }
        .getOrDefault(emptyList())

    private fun megaPlayVideos(label: String, embedUrl: String): List<Video> {
        val embedHost = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val pageHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val html = client.newCall(GET(embedUrl, pageHeaders)).execute().body.string()

        val dataId = DATA_ID_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()

        val apiHeaders = headers.newBuilder()
            .set("Referer", embedUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val sourcesResponse = client.newCall(
            GET("$embedHost/stream/getSources?id=$dataId", apiHeaders),
        ).execute()
        val data = sourcesResponse.parseAs<MegaPlaySourcesDto>()

        val masterUrl = data.sources?.file ?: return emptyList()
        val subtitles = data.tracks
            .filter { it.kind == "captions" && !it.file.isNullOrBlank() }
            .map { Track(it.file!!, it.label ?: "Subtitle") }

        val streamHeaders = headers.newBuilder().set("Referer", "$embedHost/").build()

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = "$embedHost/",
                masterHeaders = streamHeaders,
                videoHeaders = streamHeaders,
                subtitleList = subtitles,
                videoNameGen = { quality -> "$label - $quality" },
            )
        }.getOrElse {
            listOf(
                Video(
                    videoUrl = masterUrl,
                    videoTitle = "$label - Auto",
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(type, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    private fun parseEpisodeUrl(url: String): Pair<String, String> {
        val slug = url.substringBefore("#")
        val epNum = url.substringAfter("#ep=", "1")
        return slug to epNum
    }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred audio type",
            summary = "%s",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
            default = PREF_TYPE_DEFAULT,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "360p"),
            entryValues = listOf("1080p", "720p", "360p"),
            default = PREF_QUALITY_DEFAULT,
        )
        screen.addSwitchPreference(
            key = PREF_ADULT_KEY,
            title = "Show adult content",
            summary = "Include 18+ titles in browse and search results.",
            default = false,
        )
    }

    companion object {
        private val DATA_ID_REGEX by lazy { Regex("""data-id=["']?(\d+)""") }

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "SUB"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_ADULT_KEY = "show_adult"
    }
}

// ==============================================================================
// Null-Safe DTO Data Classes (v16 Compliant)
// ==============================================================================

@Serializable
data class BrowseResponseDto(
    val data: List<AnimeItemDto> = emptyList(),
    val hasNext: Boolean = false,
)

@Serializable
data class DetailsResponseDto(
    val anime: AnimeItemDto? = null,
)

@Serializable
data class TitleDto(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
    val userPreferred: String? = null,
) {
    fun best(): String = english ?: romaji ?: userPreferred ?: native ?: ""
}

@Serializable
data class CoverDto(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
) {
    fun best(): String? = extraLarge ?: large ?: medium
}

@Serializable
data class StudioDto(val name: String? = null)

@Serializable
data class AnimeItemDto(
    val slug: String? = null,
    val title: TitleDto? = null,
    val coverImage: CoverDto? = null,
    val bannerImage: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val studios: List<StudioDto>? = null,
    val status: String? = null,
    val format: String? = null,
    val year: Int? = null,
    val season: String? = null,
    val totalEpisodes: Int? = null,
) {
    fun toSAnime(): SAnime = SAnime.create().apply {
        title = this@AnimeItemDto.title?.best().orEmpty()
        url = slug.orEmpty()
        thumbnail_url = coverImage?.best() ?: bannerImage
        description = buildDescription()
        genre = genres?.joinToString()
        author = studios?.mapNotNull { it.name }?.joinToString()?.takeIf { it.isNotBlank() }
        status = when (this@AnimeItemDto.status?.uppercase()) {
            "RELEASING" -> SAnime.ONGOING
            "FINISHED" -> SAnime.COMPLETED
            "NOT_YET_RELEASED" -> SAnime.LICENSED
            "HIATUS" -> SAnime.ON_HIATUS
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
        fetch_type = FetchType.Episodes
    }

    private fun buildDescription(): String {
        val plot = description?.replace(Regex("<br\\s*/?>"), "\n")?.replace(Regex("<[^>]+>"), "")?.trim()
        val meta = buildList {
            format?.let { add("Format: $it") }
            year?.let { add("Year: $it") }
            season?.let { add("Season: ${it.lowercase().replaceFirstChar { c -> c.uppercase() }}") }
            totalEpisodes?.let { add("Episodes: $it") }
        }.joinToString(" • ")
        return listOf(plot, meta).filter { !it.isNullOrBlank() }.joinToString("\n\n")
    }
}

@Serializable
data class EpisodeDto(
    val number: Float? = null,
    val title: String? = null,
    val seasonNumber: Int? = null,
    val image: String? = null,
    val description: String? = null,
    val airDate: String? = null,
    val isFiller: Boolean? = null,
) {
    fun toSEpisode(slug: String): SEpisode = SEpisode.create().apply {
        val num = number ?: 1f
        val epLabel = if (num == num.toInt().toFloat()) num.toInt().toString() else num.toString()
        val fillerTag = if (isFiller == true) " (Filler)" else ""
        name = "Episode $epLabel${title?.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}$fillerTag"
        episode_number = num
        url = "$slug#ep=$epLabel"
        preview_url = image
        summary = description
        date_upload = parseDate(airDate)
    }

    private fun parseDate(date: String?): Long {
        date ?: return 0L
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH).parse(date)?.time ?: 0L
        }.getOrDefault(0L)
    }
}

@Serializable
data class ServersResponseDto(
    val servers: List<ServerDto> = emptyList(),
)

@Serializable
data class ServerDto(
    val id: String? = null,
    val subTypes: List<String> = emptyList(),
)

@Serializable
data class SourcesResponseDto(
    val sources: List<SourceItemDto> = emptyList(),
)

@Serializable
data class SourceItemDto(
    val embedUrl: String? = null,
    val quality: String? = null,
    val isM3U8: Boolean? = null,
)

@Serializable
data class MegaPlaySourcesDto(
    val sources: MegaPlayFileDto? = null,
    val tracks: List<MegaPlayTrackDto> = emptyList(),
)

@Serializable
data class MegaPlayFileDto(
    val file: String? = null,
)

@Serializable
data class MegaPlayTrackDto(
    val file: String? = null,
    val label: String? = null,
    val kind: String? = null,
)

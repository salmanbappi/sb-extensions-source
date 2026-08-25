package eu.kanade.tachiyomi.animeextension.en.anikage

import android.util.Base64
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

        val serverData = client.newCall(
            GET("$apiUrl/anime/$slug/episodes/$epNum/servers", headers),
        ).execute().parseAs<ServersResponseDto>()

        // Every provider/audio pair is fetched up front: each source carries an obfuscated
        // `url` token that decodes to a directly playable stream plus the referer it needs,
        // so no embed page has to be scraped for any provider.
        val fetched = serverData.servers.mapNotNull { server ->
            val serverId = server.id ?: return@mapNotNull null
            val byLang = server.subTypes.mapNotNull { lang ->
                val dto = runCatching {
                    client.newCall(
                        GET("$apiUrl/anime/$slug/episodes/$epNum/sources?provider=$serverId&lang=$lang&server=$serverId", headers),
                    ).execute().use { it.parseAs<SourcesResponseDto>() }
                }.getOrNull() ?: return@mapNotNull null
                lang to dto
            }
            if (byLang.isEmpty()) null else serverId to byLang
        }

        // `uwu` mirrors the other providers' streams but reports an empty referer for them,
        // so reuse whatever referer another provider gave for the same host.
        val refererByHost = mutableMapOf<String, String>()
        fetched.forEach { (_, byLang) ->
            byLang.forEach { (_, dto) ->
                dto.sources.forEach { src ->
                    val decoded = decodeStreamToken(src.url) ?: return@forEach
                    if (decoded.referer.isEmpty()) return@forEach
                    val host = decoded.url.hostOrEmpty()
                    if (host.isNotEmpty() && host !in refererByHost) {
                        refererByHost[host] = decoded.referer
                    }
                }
            }
        }

        return fetched.mapNotNull { (serverId, byLang) ->
            val entries = byLang.flatMap { (lang, dto) -> dto.toStreamEntries(lang, refererByHost) }
            if (entries.isEmpty()) return@mapNotNull null
            Hoster(
                hosterName = serverId.replaceFirstChar { it.uppercase() },
                hosterUrl = json.encodeToString(entries),
            )
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val entries = runCatching { hoster.hosterUrl.parseAs<List<StreamEntry>>(json) }
            .getOrNull() ?: return emptyList()

        return entries
            .flatMap { entry -> runCatching { resolveEntry(hoster.hosterName, entry) }.getOrDefault(emptyList()) }
            .distinctBy { it.videoUrl }
    }

    private suspend fun resolveEntry(providerLabel: String, entry: StreamEntry): List<Video> {
        val prefix = listOf(providerLabel, entry.lang, entry.label)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
        val tracks = entry.subtitles.map { Track(it.url, it.label) }
        val candidates = entry.refererCandidates()

        if (entry.url.isNotBlank()) {
            // `megg` hands out a progressive MP4 instead of a playlist.
            if (!entry.isM3U8) {
                return listOf(
                    Video(
                        videoUrl = entry.url,
                        videoTitle = prefix,
                        headers = refererHeaders(candidates.first()),
                        subtitleTracks = tracks,
                    ),
                )
            }

            // Most CDNs here reject a wrong Referer, and a handful of sources ship none at
            // all, so walk the candidates until one actually returns a playlist.
            candidates.forEach { referer ->
                val streamHeaders = refererHeaders(referer)
                val videos = runCatching {
                    playlistUtils.extractFromHls(
                        playlistUrl = entry.url,
                        referer = referer,
                        masterHeaders = streamHeaders,
                        videoHeaders = streamHeaders,
                        subtitleList = tracks,
                        videoNameGen = { quality -> "$prefix - $quality" },
                    )
                }.getOrDefault(emptyList())
                if (videos.isNotEmpty()) return videos
            }
        }

        // Safety net in case the token key is rotated: scrape the embed page like before.
        return when {
            entry.embedUrl.isEmpty() -> emptyList()
            "megaplay.buzz" in entry.embedUrl || "vidtube.site" in entry.embedUrl ->
                megaPlayVideos(prefix, entry.embedUrl)
            else -> vibePlayerVideos(prefix, entry.embedUrl)
        }
    }

    private fun refererHeaders(referer: String): Headers = headers.newBuilder()
        .apply { if (referer.isBlank()) removeAll("Referer") else set("Referer", referer) }
        .build()

    /**
     * Resolves VibePlayer-style embeds (vivibebe.site, bibiemb.xyz, ...).
     * The player page exposes a plain master playlist in its source:
     * `const src = "https://<host>/public/stream/<id>/master.m3u8"`
     * plus an optional external subtitle passed through the `?sub=` query parameter.
     */
    private suspend fun vibePlayerVideos(label: String, embedUrl: String): List<Video> {
        val embedHost = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val html = client.newCall(GET(embedUrl, refererHeaders("$baseUrl/"))).execute().body.string()

        val masterUrl = VIBE_SRC_REGEX.find(html)?.groupValues?.get(1)
            ?: M3U8_REGEX.find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val subtitles = embedUrl.toHttpUrl().queryParameter("sub")
            ?.takeIf { it.isNotBlank() }
            ?.let { listOf(Track(it, "English")) }
            ?: emptyList()

        val streamHeaders = refererHeaders("$embedHost/")

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = "$embedHost/",
            masterHeaders = streamHeaders,
            videoHeaders = streamHeaders,
            subtitleList = subtitles,
            videoNameGen = { quality -> "$label - $quality" },
        )
    }

    private fun megaPlayVideos(label: String, embedUrl: String): List<Video> {
        val embedHost = embedUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val html = client.newCall(GET(embedUrl, refererHeaders("$baseUrl/"))).execute().body.string()

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

        val streamHeaders = refererHeaders("$embedHost/")

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

        // VibePlayer-style pages embed the master playlist in plain source.
        private val VIBE_SRC_REGEX by lazy { Regex("""const\s+src\s*=\s*"([^"]+\.m3u8[^"]*)"""") }
        private val M3U8_REGEX by lazy { Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)""") }

        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_DEFAULT = "SUB"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_ADULT_KEY = "show_adult"
    }
}

// ==============================================================================
// Stream Token Decoding
// ==============================================================================

/**
 * Repeating XOR key the site's stream proxy uses for the `url`/`file` tokens. Recovered
 * from a known-plaintext pair: an encoded subtitle token against the very same subtitle
 * url that a `?sub=` embed parameter exposes in the clear.
 */
private val TOKEN_KEY = "dj5D455Lzl2LKJXEtFwb5gy2oGFSYPnBKp7PTgFPm6Gn2MGb".toByteArray()

/** The decoded plaintext is `url<NUL>referer<NUL>providerTag`. */
private const val TOKEN_SEPARATOR = '\u0000'

data class DecodedStream(
    val url: String,
    val referer: String,
)

/**
 * Decodes the obfuscated `url`/`file` tokens the sources endpoint returns. A token is
 * base64url over a repeating-key XOR, and the plaintext holds the playable stream url
 * together with the referer that host expects — which is why none of the providers need
 * their embed page scraped.
 */
fun decodeStreamToken(token: String?): DecodedStream? {
    if (token.isNullOrBlank()) return null
    val raw = runCatching {
        Base64.decode(token.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

    val plain = String(
        ByteArray(raw.size) { i -> (raw[i].toInt() xor TOKEN_KEY[i % TOKEN_KEY.size].toInt()).toByte() },
    )
    val parts = plain.split(TOKEN_SEPARATOR)
    val url = parts.firstOrNull()?.trim().orEmpty()
    if (!url.startsWith("http")) return null

    return DecodedStream(url = url, referer = parts.getOrNull(1)?.trim().orEmpty())
}

fun String.hostOrEmpty(): String = toHttpUrlOrNull()?.host.orEmpty()

/**
 * A single playable stream, resolved while the hoster list is built and carried to
 * [Anikage.getVideoList] as JSON inside `Hoster.hosterUrl`.
 */
@Serializable
data class StreamEntry(
    val lang: String,
    val url: String,
    val referer: String,
    val isM3U8: Boolean,
    val label: String,
    val embedUrl: String,
    val subtitles: List<SubtitleEntry> = emptyList(),
) {
    /**
     * Referers to try, in order. Most sources name their own, but the aggregator servers
     * ship an empty one, and those CDNs answer 403/404 without a matching Referer: some
     * accept the stream host itself, others only the registrable domain above it.
     */
    fun refererCandidates(): List<String> = buildList {
        if (referer.isNotBlank()) add(referer)
        url.toHttpUrlOrNull()?.let { httpUrl ->
            add("${httpUrl.scheme}://${httpUrl.host}/")
            val apex = httpUrl.host.split('.').takeLast(2).joinToString(".")
            add("${httpUrl.scheme}://$apex/")
        }
        add("")
    }.distinct()
}

@Serializable
data class SubtitleEntry(
    val url: String,
    val label: String,
)

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
    val subtitles: List<ApiSubtitleDto> = emptyList(),
    val embeds: List<EmbedDto> = emptyList(),
    val embedOptions: List<EmbedOptionDto> = emptyList(),
) {
    /**
     * Labels the site shows for each embed. `embedOptions` wins over `embeds` because it
     * carries the player names users actually see (E-Koto, E-Wish, E-Neko, E-Ken).
     */
    private fun labelsByUrl(): Map<String, String> = buildMap {
        embeds.forEach { embed ->
            val url = embed.url ?: return@forEach
            embed.server?.takeIf { it.isNotBlank() }?.let { put(url, it) }
        }
        embedOptions.forEach { option ->
            val url = option.url ?: return@forEach
            option.label?.takeIf { it.isNotBlank() }?.let { put(url, it) }
        }
    }

    private fun captionTracks(): List<ApiSubtitleDto> = subtitles.filter {
        val kind = it.kind?.lowercase()
        kind == null || kind == "captions" || kind == "subtitles"
    }

    fun toStreamEntries(lang: String, refererByHost: Map<String, String>): List<StreamEntry> {
        val labels = labelsByUrl()
        val tracks = captionTracks().mapNotNull { sub ->
            val url = decodeStreamToken(sub.file ?: sub.url)?.url ?: return@mapNotNull null
            SubtitleEntry(url = url, label = sub.label ?: "English")
        }

        return sources.mapNotNull { src ->
            val decoded = decodeStreamToken(src.url)
            val streamUrl = decoded?.url.orEmpty()
            val embedUrl = src.embedUrl?.takeIf { it.startsWith("http") }.orEmpty()
            if (streamUrl.isEmpty() && embedUrl.isEmpty()) return@mapNotNull null

            StreamEntry(
                lang = lang.uppercase(),
                url = streamUrl,
                referer = decoded?.referer?.takeIf { it.isNotEmpty() }
                    ?: refererByHost[streamUrl.hostOrEmpty()].orEmpty(),
                isM3U8 = src.isM3U8 ?: true,
                label = src.embedUrl?.let { labels[it] } ?: src.server ?: src.quality.orEmpty(),
                embedUrl = embedUrl,
                subtitles = tracks,
            )
        }
    }
}

@Serializable
data class ApiSubtitleDto(
    val file: String? = null,
    val url: String? = null,
    val label: String? = null,
    val kind: String? = null,
)

@Serializable
data class EmbedDto(
    val url: String? = null,
    val server: String? = null,
)

@Serializable
data class EmbedOptionDto(
    val url: String? = null,
    val label: String? = null,
)

@Serializable
data class SourceItemDto(
    val embedUrl: String? = null,
    val url: String? = null,
    val quality: String? = null,
    val isM3U8: Boolean? = null,
    val server: String? = null,
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

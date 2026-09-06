package eu.kanade.tachiyomi.animeextension.en.gogoanime

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.byseextractor.ByseExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.UrlUtils
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Gogoanime : Source() {

    override val name = "Gogoanime"

    override val baseUrl = "https://gogoanime.or.at"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // Shared Video Extractors
    private val playlistUtils by lazy { PlaylistUtils(client) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client) }
    private val byseExtractor by lazy { ByseExtractor(client, playlistUtils) }
    private val universalExtractor by lazy { UniversalExtractor(client) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/anime/?order=popular&page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/anime/?order=latest&page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            if (page == 1) "$baseUrl/?s=$encodedQuery" else "$baseUrl/page/$page/?s=$encodedQuery"
        } else {
            buildFilteredUrl(page, filters)
        }
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        AnimeFilter.Separator(),
        Filters.OrderByFilter(),
        Filters.StatusFilter(),
        Filters.TypeFilter(),
        Filters.SubFilter(),
        Filters.GenreFilter(),
    )

    private fun buildFilteredUrl(page: Int, filters: AnimeFilterList): String {
        val builder = "$baseUrl/anime/?page=$page".toHttpUrlOrNull()!!.newBuilder()
        filters.forEach { filter ->
            when (filter) {
                is Filters.OrderByFilter -> if (!filter.isDefault()) builder.setQueryParameter("order", filter.toUriPart())
                is Filters.StatusFilter -> if (!filter.isDefault()) builder.setQueryParameter("status", filter.toUriPart())
                is Filters.TypeFilter -> if (!filter.isDefault()) builder.setQueryParameter("type", filter.toUriPart())
                is Filters.SubFilter -> if (!filter.isDefault()) builder.setQueryParameter("sub", filter.toUriPart())
                is Filters.GenreFilter -> filter.getIncluded().forEach { builder.addQueryParameter("genre[]", it) }
                else -> {}
            }
        }
        return builder.toString()
    }

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.bs").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href").orEmpty()
            val path = href.toHttpUrlOrNull()?.encodedPath ?: href
            // Skip layout widgets (trending slider, etc.): real entries link to /anime/{slug}/
            if (!path.startsWith("/anime/") || path == "/anime/") return@mapNotNull null
            val title = element.selectFirst("div.tt h2, div.tt")?.text()?.trim()
                ?: element.selectFirst("a")?.attr("title")?.trim()
                ?: ""
            if (title.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                url = path
                this.title = title
                thumbnail_url = UrlUtils.fixUrl(element.selectFirst("img")?.attr("src") ?: "", baseUrl)
                fetch_type = FetchType.Episodes
            }
        }
        val hasNext = doc.selectFirst("div.hpage a.r") != null
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET(baseUrl + anime.url, headers)).execute().asJsoup()

        val statusRaw = doc.selectFirst("div.spe span:containsOwn(Status:)")?.text()
            ?.substringAfter("Status:")?.trim().orEmpty()
        val studio = doc.selectFirst("div.spe span:has(a[href*=studio]) a")?.text()?.trim()
        val ratingText = doc.selectFirst("div.rt div.rating strong")?.text()?.trim()
        val altTitles = doc.selectFirst("span.alter")?.text()?.trim().orEmpty()

        return anime.apply {
            title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: anime.title
            thumbnail_url = UrlUtils.fixUrl(
                doc.selectFirst("div.thumbook div.thumb img")?.attr("src") ?: anime.thumbnail_url ?: "",
                baseUrl,
            )
            genre = doc.select("div.genxed a").joinToString(", ") { it.text().trim() }
            status = when (statusRaw.lowercase()) {
                "ongoing" -> SAnime.ONGOING
                "completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            author = studio

            val synopsis = doc.selectFirst("div.entry-content[itemprop=description]")?.text()?.trim()
            val shortDesc = doc.selectFirst("div.info-content div.desc")?.text()?.trim()
            val baseDescription = when {
                !synopsis.isNullOrBlank() -> synopsis
                !shortDesc.isNullOrBlank() -> shortDesc
                else -> description
            }

            description = buildString {
                val score = ratingText?.substringAfter("Rating")?.trim()?.toDoubleOrNull()
                if (score != null && score > 0.0) append("★ %.2f".format(score))
                if (altTitles.isNotBlank()) append("\n\nAlternate titles: $altTitles")
                if (!baseDescription.isNullOrBlank()) append("\n\n$baseDescription")
            }.trim()

            initialized = true
        }
    }

    // ============================== Episodes ==============================
    // Note: SEpisode.url MUST be permanent and deterministic ("${anime.url}#ep=$e").
    // Never embed dynamic tokens in SEpisode.url to avoid Tachiyomi/AniZen database invalidation cycles.
    // Episode numbering: For Season 1, episode_number MUST start at 1.0f (never with a +1000 base offset).
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET(baseUrl + anime.url, headers)).execute().asJsoup()
        return doc.select("div.eplister ul li").mapNotNull { element ->
            val numText = element.selectFirst("div.epl-num")?.text()?.trim().orEmpty()
            if (numText.isBlank()) return@mapNotNull null

            val epNum = numText.toFloatOrNull()
                ?: Regex("""\d+(\.\d+)?""").find(numText)?.value?.toFloatOrNull()
                ?: 0f

            val subStatus = element.selectFirst("div.epl-sub span")?.attr("class").orEmpty()

            SEpisode.create().apply {
                url = "${anime.url}#ep=${epNum.displayString()}"
                name = "Episode ${epNum.displayString()}"
                episode_number = epNum
                scanlator = when {
                    subStatus.contains("Dub", ignoreCase = true) -> "Dub"
                    subStatus.contains("Raw", ignoreCase = true) -> "RAW"
                    else -> "Sub"
                }

                val dateStr = element.selectFirst("div.epl-date")?.text()?.trim().orEmpty()
                if (dateStr.isNotBlank()) {
                    date_upload = runCatching { DATE_FORMAT.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
                }
            }
        }.sortedByDescending { it.episode_number } // Aniyomi requires descending order
    }

    private fun Float.displayString(): String = if (this == toInt().toFloat()) toInt().toString() else toString()

    // ============================ Video Links =============================
    // 2-Tier Model:
    // 1. getHosterList(episode) returns List<Hoster> representing server folders (e.g. "HD-1", "Omega", "Moon").
    // 2. getVideoList(hoster) resolves and returns List<Video> with qualities.
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val numText = episode.url.substringAfter("#ep=", "")
        if (numText.isBlank()) return emptyList()

        val response = client.newCall(GET(resolveEpisodePageUrl(episode, numText), headers)).execute()
        if (response.isSuccessful) return parseHosters(response.asJsoup())
        response.close()

        // Fallback: locate the episode permalink on the series page by its number
        val seriesDoc = client.newCall(GET(baseUrl + episode.url.substringBefore("#"), headers)).execute().asJsoup()
        val href = seriesDoc.select("div.eplister ul li")
            .firstOrNull { it.selectFirst("div.epl-num")?.text()?.trim()?.let(::normalizeNumber) == numText }
            ?.selectFirst("a")?.attr("href")
            ?: return emptyList()

        val epDoc = client.newCall(GET(UrlUtils.fixUrl(href, baseUrl), headers)).execute()
        return if (epDoc.isSuccessful) parseHosters(epDoc.asJsoup()) else emptyList<Hoster>().also { epDoc.close() }
    }

    private fun parseHosters(doc: Document): List<Hoster> {
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val hosters = doc.select("select.mirror option").mapNotNull { option ->
            val encoded = option.attr("value")
            if (encoded.isBlank()) return@mapNotNull null

            val serverName = option.text().trim()
            if (serverName.isBlank() || serverName in excludedServers) return@mapNotNull null

            val embedUrl = decodeEmbedUrl(encoded) ?: return@mapNotNull null

            Hoster(
                hosterName = serverName,
                hosterUrl = embedUrl,
            )
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    /** Mirror options carry a base64-encoded `<iframe src="...">` snippet. */
    private fun decodeEmbedUrl(encoded: String): String? {
        val html = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null

        return Regex("""iframe[^>]+src=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }
    }

    /**
     * Rebuilds the episode post permalink from its permanent anchor:
     * "/anime/{slug}/#ep={n}" -> "/{slug}-episode-{n}/" (WP slugs hyphenate decimals, e.g. 12.5 -> 12-5).
     */
    private fun resolveEpisodePageUrl(episode: SEpisode, numText: String): String {
        val slug = episode.url.substringBefore("#").trimEnd('/').substringAfterLast('/')
        return "$baseUrl/$slug-episode-${numText.replace('.', '-')}/"
    }

    private fun normalizeNumber(text: String): String {
        val value = text.toFloatOrNull() ?: return text
        return value.displayString()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl
        val url = embedUrl.toHttpUrlOrNull() ?: return emptyList()

        val videos = when {
            // "HD-1" / "HD-2": wrapper page that embeds a MegaPlay stream
            url.host.endsWith("gogoanime.me.uk") || url.encodedPath.contains("newplayer.php") ->
                megaPlayVideos(hoster, embedUrl)

            // "Omega": VidMoly
            url.host.contains("vidmoly") -> vidmolyExtractor.videosFromUrl(embedUrl)

            // "Moon": Byse
            url.encodedPath.startsWith("/e/") -> byseExtractor.videosFromUrl(embedUrl)

            else -> universalExtractor.videosFromUrl(embedUrl, headers)
        }

        return videos.sortVideos()
    }

    /**
     * Resolves the MegaPlay stream chain:
     * 1. wrapper page (newplayer.php) -> iframe src on megaplay.buzz/stream/...
     * 2. stream page -> data-id
     * 3. getSources?id=... -> master HLS playlist + subtitle tracks
     */
    private suspend fun megaPlayVideos(hoster: Hoster, wrapperUrl: String): List<Video> {
        val wrapperDoc = runCatching {
            client.newCall(
                GET(wrapperUrl, headers.newBuilder().set("Referer", "$baseUrl/").build()),
            ).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        val streamUrl = wrapperDoc.selectFirst("iframe")?.attr("src")
            ?.takeIf { it.contains("megaplay") }
            ?: return emptyList()

        val streamDoc = runCatching {
            client.newCall(GET(streamUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())).execute().asJsoup()
        }.getOrNull() ?: return emptyList()

        val streamId = streamDoc.selectFirst("[data-id]")?.attr("data-id")
            ?: Regex("""<title>File (\d+)""").find(streamDoc.outerHtml())?.groupValues?.get(1)
            ?: return emptyList()

        val sourcesHeaders = headers.newBuilder()
            .set("Referer", streamUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val sources = runCatching {
            client.newCall(GET("https://megaplay.buzz/stream/getSources?id=$streamId&id=$streamId", sourcesHeaders))
                .execute()
                .parseAs<MegaPlaySources>()
        }.getOrNull() ?: return emptyList()

        val masterUrl = sources.sources?.file ?: return emptyList()
        val subtitleTracks = sources.tracks
            ?.filter { it.kind == "captions" && !it.file.isNullOrBlank() && !it.label.isNullOrBlank() }
            ?.map { Track(it.file!!, it.label!!) }
            ?: emptyList()

        val videoHeaders = headers.newBuilder()
            .set("Referer", "https://megaplay.buzz/")
            .build()

        return playlistUtils.extractFromHls(
            masterUrl,
            referer = "https://megaplay.buzz/",
            videoNameGen = { quality -> "$quality" },
            subtitleList = subtitleTracks,
        ).map { video ->
            Video(
                videoUrl = video.videoUrl,
                videoTitle = video.videoTitle,
                headers = videoHeaders,
                resolution = video.resolution,
                subtitleTracks = video.subtitleTracks,
                audioTracks = video.audioTracks,
            )
        }
    }

    @Serializable
    private data class MegaPlaySources(
        val sources: MegaPlaySourceFile? = null,
        val tracks: List<MegaPlayTrack>? = null,
    )

    @Serializable
    private data class MegaPlaySourceFile(
        val file: String? = null,
    )

    @Serializable
    private data class MegaPlayTrack(
        val file: String? = null,
        val label: String? = null,
        val kind: String? = null,
    )

    // ============================ Preferences =============================
    override fun List<Video>.sortVideos(): List<Video> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefServer, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = SERVER_ENTRIES,
            entryValues = SERVER_ENTRIES,
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080", "720", "480", "360"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from playback",
            entries = SERVER_ENTRIES,
            entryValues = SERVER_ENTRIES,
            default = emptySet(),
        )
    }

    companion object {
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "HD-1"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"

        private val SERVER_ENTRIES = listOf("HD-1", "HD-2", "Omega", "Moon")

        private val DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy", Locale.US)
    }
}

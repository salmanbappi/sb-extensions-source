package eu.kanade.tachiyomi.animeextension.en.animotvslash

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.vidaraextractor.VidaraExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import extensions.utils.delegate
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class Animotvslash : Source() {

    override val name = "ANIMOTVSLASH"

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    // Preferences with property delegates
    private val preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val preferredAudio by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val preferredServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)
    private val scorePosition by preferences.delegate(PREF_SCORE_POSITION_KEY, PREF_SCORE_POSITION_DEFAULT)
    private val showThumbnails by preferences.delegate(PREF_SHOW_THUMBNAILS_KEY, true)

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidaraExtractor by lazy { VidaraExtractor(client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // ============================== Popular ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/anime/?order=popular" else "$baseUrl/anime/?page=$page&order=popular"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ==============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/anime/?order=update" else "$baseUrl/anime/?page=$page&order=update"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Search ==============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = if (query.isNotBlank()) {
            "$baseUrl/".toHttpUrl().newBuilder().apply {
                addQueryParameter("s", query)
                if (page > 1) addQueryParameter("page", page.toString())
            }
        } else {
            "$baseUrl/anime/".toHttpUrl().newBuilder().apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }
        }

        filters.forEach { filter ->
            when (filter) {
                is Filters.TypeFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("type", filter.toUriPart())
                }
                is Filters.StatusFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())
                }
                is Filters.OrderFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("order", filter.toUriPart())
                }
                is Filters.YearFilter -> {
                    if (!filter.isDefault()) urlBuilder.addQueryParameter("year", filter.toUriPart())
                }
                is Filters.GenreFilter -> {
                    filter.selectedGenres().forEach { genre ->
                        urlBuilder.addQueryParameter("genre[]", genre)
                    }
                }
                else -> {}
            }
        }

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filters apply only when search is blank"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.OrderFilter(),
        Filters.YearFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article.bs, div.bsx, div.animposx").mapNotNull { element ->
            val link = element.selectFirst("a[itemprop=url], a.tip, a") ?: return@mapNotNull null
            val titleStr = element.selectFirst("h2, .tt")?.text()?.trim() ?: link.attr("title").ifBlank { link.text() }
            if (titleStr.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                title = titleStr
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
                    ?: element.selectFirst("img")?.attr("src")
            }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst("div.hpage a.r, div.hpage a:contains(Next), a.next") != null
        return AnimesPage(animeList, hasNext)
    }

    // ============================== Details ==============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val titleVal = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: anime.title
        val thumbnailVal = doc.selectFirst("div.thumb img, div.poster img")?.attr("abs:src")
            ?: anime.thumbnail_url

        val genres = doc.select("div.genxed a, div.genres a").joinToString { it.text() }
        val studios = doc.select("span:contains(Studio) a").joinToString { it.text() }
        val statusText = doc.selectFirst("span:contains(Status)")?.text() ?: ""
        val synopsis = doc.selectFirst("div.ts-syn-body, div.entry-content, div.desc")?.text()?.trim()
        val scoreStr = doc.selectFirst("div.rating strong, span.num")?.text()?.trim()
        val scoreVal = scoreStr?.substringAfter(" ")?.toDoubleOrNull() ?: scoreStr?.toDoubleOrNull()

        return SAnime.create().apply {
            title = titleVal
            thumbnail_url = thumbnailVal
            genre = genres.ifBlank { null }
            author = studios.ifBlank { null }
            status = when {
                statusText.contains("Ongoing", ignoreCase = true) -> SAnime.ONGOING
                statusText.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildDescription(synopsis, scoreVal, scorePosition, statusText, studios)
            initialized = true
        }
    }

    private fun buildDescription(
        synopsis: String?,
        score: Double?,
        position: String,
        statusText: String,
        studios: String,
    ): String = buildString {
        val scoreStars = score?.let {
            val full = (it / 2).toInt().coerceIn(0, 5)
            "★".repeat(full) + "☆".repeat(5 - full) + " ${"%.2f".format(it)}"
        }

        if (position == "top" && scoreStars != null) {
            append(scoreStars).append("\n\n")
        }

        if (!synopsis.isNullOrBlank()) {
            append(synopsis).append("\n\n")
        }

        if (statusText.isNotBlank()) append(statusText).append("\n")
        if (studios.isNotBlank()) append("Studio: ").append(studios).append("\n")

        if (position == "bottom" && scoreStars != null) {
            append("\n").append(scoreStars)
        }
    }.trim()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val episodeElements = doc.select("ul#ts-ep-list li, div.eplister ul li")
        val animeThumb = doc.selectFirst("div.thumb img, div.poster img")?.attr("abs:src") ?: anime.thumbnail_url

        return episodeElements.mapNotNull { li ->
            val link = li.selectFirst("a") ?: return@mapNotNull null
            val epUrl = link.attr("href")
            val epNumStr = li.selectFirst(".epl-num, .numep")?.text()?.trim()
                ?: link.text().substringAfter("Episode ").substringBefore(" ")
            val epNum = epNumStr.toFloatOrNull() ?: 0.0f
            val epTitle = li.selectFirst(".epl-title, .title")?.text()?.trim()
                ?: link.text().trim()

            val nameFormatted = if (epTitle.isNotBlank() && !epTitle.equals("Episode $epNumStr", ignoreCase = true)) {
                if (epTitle.startsWith("Episode ", ignoreCase = true)) epTitle else "Episode $epNumStr - $epTitle"
            } else {
                "Episode $epNumStr"
            }

            val dateStr = li.selectFirst(".epl-date, .date")?.text()?.trim()
            val dateUpload = parseDate(dateStr)

            val subBadge = li.selectFirst(".sb, .sub, .typez")?.text()?.trim() ?: "Sub"

            SEpisode.create().apply {
                setUrlWithoutDomain(epUrl)
                name = nameFormatted
                episode_number = epNum
                date_upload = dateUpload
                scanlator = subBadge
                if (showThumbnails) {
                    preview_url = animeThumb
                }
            }
        }.sortedByDescending { it.episode_number }
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return runCatching {
            DATE_FORMATTER.parse(dateStr)?.time ?: 0L
        }.getOrDefault(0L)
    }

    // ============================== Hoster & Videos ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).execute()
        val doc = response.asJsoup()

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()

        val hosters = mutableListOf<Hoster>()

        // 1. Check default iframe player (Rumble/Direct)
        val mainIframeSrc = doc.selectFirst("div.player-embed iframe, div#pembed iframe")?.attr("src")
        if (!mainIframeSrc.isNullOrBlank()) {
            val hosterData = "$mainIframeSrc|SUB"
            if ("Rumble" !in excludedServers && "SUB" !in excludedAudios) {
                hosters.add(Hoster(hosterName = "Rumble (Default)", hosterUrl = hosterData))
            }
        }

        // 2. Parse select.mirror option values (Base64 encoded HTML)
        doc.select("select.mirror option[value]").forEach { option ->
            val label = option.text().trim()
            val base64Value = option.attr("value").trim()
            if (base64Value.isBlank()) return@forEach

            val decodedHtml = runCatching {
                String(Base64.decode(base64Value, Base64.DEFAULT))
            }.getOrNull() ?: return@forEach

            val iframeSrc = JsoupParseSrc(decodedHtml) ?: return@forEach

            val audioType = when {
                label.contains("SoftSub", ignoreCase = true) -> "SOFTSUB"
                label.contains("Dub", ignoreCase = true) -> "DUB"
                else -> "SUB"
            }

            val serverName = label.substringAfter("- ").trim().ifBlank { label }

            if (serverName in excludedServers || audioType in excludedAudios) return@forEach

            hosters.add(
                Hoster(
                    hosterName = "$serverName ($audioType)",
                    hosterUrl = "$iframeSrc|$audioType",
                ),
            )
        }

        return sortHostersByPreference(hosters)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        val embedUrl = parts.getOrNull(0) ?: return emptyList()
        val audioType = parts.getOrNull(1) ?: "SUB"

        val videoList = mutableListOf<Video>()

        runCatching {
            when {
                embedUrl.contains("/jw-player/") || embedUrl.contains("/vidstack-player/") -> {
                    val b64 = when {
                        embedUrl.contains("/vidstack-player/") -> embedUrl.substringAfter("/vidstack-player/").substringBefore("?")
                        embedUrl.contains("/jw-player/") -> embedUrl.substringAfter("/jw-player/").substringBefore("?")
                        else -> ""
                    }
                    val jsonStr = String(Base64.decode(b64, Base64.DEFAULT or Base64.NO_WRAP))
                    val m3u8Url = jsonStr.substringAfter("\"url\":\"").substringBefore("\"").replace("\\/", "/")
                    if (m3u8Url.isNotBlank() && m3u8Url.contains("m3u8")) {
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = m3u8Url,
                                referer = "$baseUrl/",
                                videoNameGen = { quality -> "$audioType - $quality" },
                            ),
                        )
                    }
                }
                embedUrl.contains("streamwish") || embedUrl.contains("bysezoxexe") || embedUrl.contains("filemoon") -> {
                    val extracted = streamWishExtractor.videosFromUrl(embedUrl) { quality -> "$audioType - Moon:$quality" }
                    if (extracted.isNotEmpty()) {
                        videoList.addAll(extracted)
                    } else {
                        videoList.addAll(filemoonExtractor.videosFromUrl(embedUrl, "$audioType - Moon:"))
                    }
                }
                embedUrl.contains("vidhide") || embedUrl.contains("minochinos") -> {
                    videoList.addAll(
                        vidHideExtractor.videosFromUrl(embedUrl) { quality -> "$audioType - VidHide:$quality" },
                    )
                }
                embedUrl.contains("vidara") -> {
                    videoList.addAll(
                        vidaraExtractor.videosFromUrl(embedUrl, "$audioType - "),
                    )
                }
                embedUrl.contains("p2pplay") -> {
                    val html = client.newCall(GET(embedUrl, headers)).execute().body.string()
                    val m3u8Url = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""").find(html)?.groupValues?.get(1)
                    if (!m3u8Url.isNullOrBlank()) {
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = m3u8Url,
                                referer = embedUrl,
                                videoNameGen = { quality -> "$audioType - P2PPlay:$quality" },
                            ),
                        )
                    }
                }
                else -> {
                    val html = client.newCall(GET(embedUrl, headers)).execute().body.string()
                    val m3u8Url = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""").find(html)?.groupValues?.get(1)
                    if (!m3u8Url.isNullOrBlank()) {
                        videoList.addAll(
                            playlistUtils.extractFromHls(
                                playlistUrl = m3u8Url,
                                referer = embedUrl,
                                videoNameGen = { quality -> "$audioType - $quality" },
                            ),
                        )
                    }
                }
            }
        }

        return videoList.sortVideos()
    }

    private fun JsoupParseSrc(html: String): String? {
        val doc = Jsoup.parse(html)
        return doc.selectFirst("iframe")?.attr("src")
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferredQuality
        val audio = preferredAudio

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(audio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(quality, ignoreCase = true) }
                .thenByDescending { it.resolution },
        )
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val server = preferredServer
        if (server == "auto") return hosters

        return hosters.sortedByDescending {
            it.hosterName.contains(server, ignoreCase = true)
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_DOMAIN_DEFAULT,
            title = "Base URL",
            key = PREF_DOMAIN_KEY,
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred Server",
            summary = "%s",
            entries = listOf("Auto", "Rumble", "ANIMO-M", "ANIMO-H", "ANIMO-D", "StreamWish", "VidHide", "Vidara", "Hydrax (Abyss)", "P2PPlay"),
            entryValues = listOf("auto", "Rumble", "ANIMO-M", "ANIMO-H", "ANIMO-D", "StreamWish", "VidHide", "Vidara", "Hydrax", "P2PPlay"),
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred Quality",
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080p", "720p", "480p", "360p"),
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred Audio/Subtitle Type",
            summary = "%s",
            entries = listOf("Subbed", "SoftSub", "Dubbed"),
            entryValues = listOf("SUB", "SOFTSUB", "DUB"),
        )

        screen.addListPreference(
            key = PREF_SCORE_POSITION_KEY,
            default = PREF_SCORE_POSITION_DEFAULT,
            title = "Score Display Position",
            summary = "%s",
            entries = listOf("Top of description", "Bottom of description", "Disabled"),
            entryValues = listOf("top", "bottom", "disabled"),
        )

        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            default = true,
            title = "Show episode thumbnails",
            summary = "Display preview thumbnails in episode list",
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude Servers",
            summary = "Select servers to hide from stream options",
            entries = listOf("Rumble", "ANIMO-M", "ANIMO-H", "ANIMO-D", "StreamWish", "VidHide", "Vidara", "Hydrax (Abyss)", "P2PPlay"),
            entryValues = listOf("Rumble", "ANIMO-M", "ANIMO-H", "ANIMO-D", "StreamWish", "VidHide", "Vidara", "Hydrax", "P2PPlay"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_AUDIO_KEY,
            default = emptySet(),
            title = "Exclude Audio Format",
            summary = "Select audio formats to hide from stream options",
            entries = listOf("Subbed", "SoftSub", "Dubbed"),
            entryValues = listOf("SUB", "SOFTSUB", "DUB"),
        )
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://animotvslash.org"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "SUB"

        private const val PREF_SCORE_POSITION_KEY = "pref_score_position"
        private const val PREF_SCORE_POSITION_DEFAULT = "top"

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"

        private val DATE_FORMATTER = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
    }
}

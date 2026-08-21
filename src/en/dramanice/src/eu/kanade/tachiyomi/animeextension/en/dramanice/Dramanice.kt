package eu.kanade.tachiyomi.animeextension.en.dramanice

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.parallelCatchingFlatMap
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Dramanice :
    Source(),
    ConfigurableAnimeSource {

    override val name = "Dramanice"

    override val baseUrl = "https://dramanice.cl"

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
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val vidMolyExtractor by lazy { VidMolyExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/most-popular-drama/" else "$baseUrl/most-popular-drama/page/$page/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            if (page == 1) "$baseUrl/?keyword_search=$encodedQuery" else "$baseUrl/page/$page/?keyword_search=$encodedQuery"
        } else {
            var category = ""
            var country = ""
            var genre = ""
            var year = ""

            filters.forEach { filter ->
                when (filter) {
                    is Filters.CategoryFilter -> if (!filter.isDefault()) category = filter.toUriPart()
                    is Filters.CountryFilter -> if (!filter.toUriPart().isBlank()) country = filter.toUriPart()
                    is Filters.GenreFilter -> if (!filter.toUriPart().isBlank()) genre = filter.toUriPart()
                    is Filters.YearFilter -> if (!filter.toUriPart().isBlank()) year = filter.toUriPart()
                    else -> {}
                }
            }

            when {
                category.isNotBlank() -> if (page == 1) "$baseUrl/$category/" else "$baseUrl/$category/page/$page/"
                genre.isNotBlank() -> if (page == 1) "$baseUrl/genre/$genre/" else "$baseUrl/genre/$genre/page/$page/"
                country.isNotBlank() -> if (page == 1) "$baseUrl/country/$country/" else "$baseUrl/country/$country/page/$page/"
                year.isNotBlank() -> if (page == 1) "$baseUrl/release-year/$year/" else "$baseUrl/release-year/$year/page/$page/"
                else -> if (page == 1) "$baseUrl/" else "$baseUrl/page/$page/"
            }
        }

        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.CategoryFilter(),
        Filters.CountryFilter(),
        Filters.GenreFilter(),
        Filters.YearFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val elements = doc.select("ul.items > li, .main_body ul.items li, .content_left ul.items li")

        val animes = elements.mapNotNull { element ->
            val a = element.selectFirst(".img a, .bottom .name a, a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { return@mapNotNull null }
            val titleText = element.selectFirst(".bottom .name a, .name a")?.text()?.ifBlank { null }
                ?: a.attr("title").ifBlank { null }
                ?: a.text().ifBlank { return@mapNotNull null }

            val img = element.selectFirst(".img img, img")
            val thumb = img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:") }

            SAnime.create().apply {
                title = titleText.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = thumb
                fetch_type = FetchType.Episodes
            }
        }

        val hasNext = doc.selectFirst("div.pagination li.next a, div.pagination a:contains(>)") != null ||
            (elements.size >= 30 && doc.selectFirst("div.pagination") == null)

        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        var doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        // If anime.url is an episode URL (e.g. from homepage latest updates), follow the drama link
        if (!anime.url.contains("/drama/")) {
            val dramaHref = doc.selectFirst("a[href*=/drama/]")?.attr("href")
            if (!dramaHref.isNullOrBlank()) {
                doc = client.newCall(GET(if (dramaHref.startsWith("http")) dramaHref else "$baseUrl$dramaHref", headers)).execute().asJsoup()
            }
        }

        val synopsis = doc.selectFirst("div.info_des, .description, .synopsis")?.text() ?: ""
        val statusRaw = doc.selectFirst("div.info_right p:contains(Status:), .status")?.text() ?: ""
        val country = doc.selectFirst("div.info_right p:contains(Country:) a")?.text() ?: ""
        val director = doc.selectFirst("div.info_right p:contains(Director:)")?.text()?.substringAfter("Director:")?.trim() ?: ""
        val releaseYear = doc.selectFirst("div.info_right p:contains(Released:) a")?.text() ?: ""
        val genres = doc.select("div.info_right p:contains(Genre:) a").joinToString { it.text() }
        val otherNames = doc.select("div.info_right .other_name a").joinToString { it.text().trim() }

        val thumb = doc.selectFirst(".img_cover img, .info_left img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return SAnime.create().apply {
            title = doc.selectFirst(".info_right h2, h1.label_coming")?.text()?.trim() ?: anime.title
            thumbnail_url = thumb ?: anime.thumbnail_url
            genre = genres.ifBlank { null }
            status = when {
                statusRaw.contains("Ongoing", ignoreCase = true) || statusRaw.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
                statusRaw.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            description = buildString {
                if (director.isNotBlank()) append("Director: $director\n")
                if (country.isNotBlank()) append("Country: $country\n")
                if (releaseYear.isNotBlank()) append("Released: $releaseYear\n")
                if (otherNames.isNotBlank()) append("Other names: $otherNames\n\n")
                if (synopsis.isNotBlank()) append(synopsis)
            }.trim()
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val elements = doc.select(".drama_info_episodes ul.list_episode li, ul.list_episode li")

        return elements.mapIndexed { idx, element ->
            val a = element.selectFirst("a") ?: element
            val rawHref = a.attr("href").ifBlank { a.attr("data-href") }
            val epName = a.select("span").last()?.text()?.ifBlank { null } ?: a.text().ifBlank { "Episode ${idx + 1}" }

            val numMatch = Regex("""(?:Ep|Episode)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(epName)
            val epNum = numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: (idx + 1).toFloat()

            val isSub = a.hasClass("SUB") || element.selectFirst(".SUB, span:contains(SUB)") != null
            val isDub = a.hasClass("DUB") || element.selectFirst(".DUB, span:contains(DUB)") != null

            SEpisode.create().apply {
                setUrlWithoutDomain(if (rawHref.isNotBlank()) rawHref else "${anime.url}#ep=$epNum")
                name = epName
                episode_number = epNum
                scanlator = when {
                    isSub && isDub -> "Sub / Dub"
                    isDub -> "Dub"
                    isSub -> "Sub"
                    else -> null
                }
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.newCall(GET("$baseUrl${episode.url}", headers)).execute().asJsoup()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val hosters = mutableListOf<Hoster>()

        // 1. Check embedded server items
        val serverElements = doc.select(".anime_muti_link ul li[data-video], .play-video iframe[data-src], .play-video iframe[src], iframe[data-src], iframe[src]")

        for ((idx, el) in serverElements.withIndex()) {
            val videoUrl = el.attr("data-video").ifBlank { el.absUrl("data-src") }.ifBlank { el.absUrl("src") }
            if (videoUrl.isBlank()) continue

            val resolvedUrl = when {
                videoUrl.startsWith("//") -> "https:$videoUrl"
                videoUrl.startsWith("/") -> "$baseUrl$videoUrl"
                else -> videoUrl
            }

            when {
                resolvedUrl.contains("dramavideo") -> {
                    val subHosters = fetchDramavideoServers(resolvedUrl)
                    if (subHosters.isNotEmpty()) {
                        hosters.addAll(subHosters)
                    } else {
                        hosters.add(Hoster(hosterName = "Dramanice - Server ${idx + 1}", hosterUrl = "dramavideo_direct|$resolvedUrl"))
                    }
                }

                resolvedUrl.contains("kisskh") -> {
                    val subHosters = fetchKisskhServers(resolvedUrl)
                    if (subHosters.isNotEmpty()) {
                        hosters.addAll(subHosters)
                    } else {
                        hosters.add(Hoster(hosterName = "KissKH", hosterUrl = resolvedUrl))
                    }
                }

                else -> {
                    val name = el.text().trim().ifBlank { "Server ${idx + 1}" }
                    hosters.add(Hoster(hosterName = name, hosterUrl = resolvedUrl))
                }
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters
            .filter { it.hosterName !in excludedServers }
            .distinctBy { it.hosterUrl }
            .sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    private fun fetchDramavideoServers(watchUrl: String): List<Hoster> {
        val result = mutableListOf<Hoster>()
        val dvHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val doc = runCatching { client.newCall(GET(watchUrl, dvHeaders)).execute().asJsoup() }.getOrNull() ?: return emptyList()

        doc.select(".list-server-items li.linkserver").forEach { li ->
            val provider = li.attr("data-provider")
            val videoCode = li.attr("data-video")
            val label = li.text().trim().ifBlank { "Server $provider" }

            if (provider.isNotBlank() && videoCode.isNotBlank()) {
                result.add(
                    Hoster(
                        hosterName = "Dramanice - $label",
                        hosterUrl = "dramavideo|$videoCode|$provider",
                    ),
                )
            }
        }
        return result
    }

    private fun fetchKisskhServers(kisskhUrl: String): List<Hoster> {
        val result = mutableListOf<Hoster>()
        val kHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val doc = runCatching { client.newCall(GET(kisskhUrl, kHeaders)).execute().asJsoup() }.getOrNull() ?: return emptyList()

        doc.select(".server-item[data-video], li.linkserver[data-video]").forEach { item ->
            val video = item.attr("data-video")
            val srvName = item.attr("data-server").ifBlank { item.text().trim() }.ifBlank { "Vidmoly" }
            if (video.isNotBlank()) {
                result.add(
                    Hoster(
                        hosterName = "KissKH - $srvName",
                        hosterUrl = video,
                    ),
                )
            }
        }

        if (result.isEmpty()) {
            doc.selectFirst("iframe#embedvideo[src]")?.attr("src")?.let { src ->
                if (src.isNotBlank()) {
                    result.add(Hoster(hosterName = "KissKH - Video", hosterUrl = src))
                }
            }
        }

        return result
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val rawUrl = hoster.hosterUrl
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()

        val videos: List<Video> = when {
            rawUrl.startsWith("dramavideo|") -> {
                val parts = rawUrl.split("|")
                if (parts.size >= 3) {
                    val code = parts[1]
                    val provider = parts[2]
                    extractDramavideoHls(code, provider)
                } else {
                    emptyList()
                }
            }

            rawUrl.startsWith("dramavideo_direct|") -> {
                val watchUrl = rawUrl.substringAfter("dramavideo_direct|")
                val servers = fetchDramavideoServers(watchUrl)
                servers.parallelCatchingFlatMap { getVideoList(it) }
            }

            rawUrl.contains("vidmoly") -> {
                vidMolyExtractor.videosFromUrl(rawUrl)
            }

            rawUrl.contains("streamtape") -> {
                streamtapeExtractor.videoFromUrl(rawUrl)?.let(::listOf) ?: emptyList()
            }

            rawUrl.contains("mixdrop") -> {
                mixDropExtractor.videoFromUrl(rawUrl)
            }

            rawUrl.contains("dood") || rawUrl.contains("ds2play") -> {
                doodExtractor.videosFromUrl(rawUrl)
            }

            rawUrl.contains("filemoon") || rawUrl.contains("moonplayer") -> {
                filemoonExtractor.videosFromUrl(rawUrl, headers = embedHeaders)
            }

            rawUrl.endsWith(".m3u8") || rawUrl.contains(".m3u8?") -> {
                playlistUtils.extractFromHls(
                    playlistUrl = rawUrl,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> quality },
                )
            }

            else -> {
                universalExtractor.videosFromUrl(rawUrl, embedHeaders)
            }
        }

        return videos.sortVideos()
    }

    private fun extractDramavideoHls(code: String, provider: String): List<Video> {
        val playerUrl = "https://player.dramavideo.se/?id=" + URLEncoder.encode(code, "UTF-8") + "&sv=" + URLEncoder.encode(provider, "UTF-8")
        val playerHeaders = headers.newBuilder()
            .set("Referer", "https://dramavideo.se/")
            .build()

        val html = runCatching { client.newCall(GET(playerUrl, playerHeaders)).execute().body.string() }.getOrNull() ?: return emptyList()

        val encData = Regex("""encData\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val keyHex = Regex("""keyHex\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val ivHex = Regex("""ivHex\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return emptyList()

        val decrypted = CryptoAES.decrypt(encData, keyHex.decodeHex(), ivHex.decodeHex())
        if (decrypted.isBlank()) return emptyList()

        val m3u8Matches = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").findAll(decrypted)
            .map { it.value }
            .distinct()
            .toList()

        return m3u8Matches.flatMap { m3u8Url ->
            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "https://player.dramavideo.se/",
                videoNameGen = { quality -> quality },
            )
        }
    }

    private fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return sortedWith(
            compareBy(
                { it.videoTitle.contains(server, ignoreCase = true) },
                { it.videoTitle.contains(quality, ignoreCase = true) },
            ),
        ).reversed()
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_QUALITY_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = arrayOf("Dramanice Fast", "Dramanice HD", "Dramanice Standard", "Vidmoly", "Streamtape", "MixDrop")
            entryValues = arrayOf("Fast", "HD", "Standard", "Vidmoly", "Streamtape", "MixDrop")
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(PREF_SERVER_KEY, newValue as String).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_SERVERS_KEY
            title = "Exclude Video Servers"
            entries = arrayOf("Dramanice - Fast Server", "Dramanice - HD Server", "Dramanice - Standard Server", "KissKH - Vidmoly", "KissKH - Streamtape", "KissKH - MixDrop")
            entryValues = arrayOf("Dramanice - Fast Server", "Dramanice - HD Server", "Dramanice - Standard Server", "KissKH - Vidmoly", "KissKH - Streamtape", "KissKH - MixDrop")
            setDefaultValue(emptySet<String>())
            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(PREF_EXCLUDE_SERVERS_KEY, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Fast"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
    }
}

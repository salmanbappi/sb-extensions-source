package eu.kanade.tachiyomi.animeextension.en.myasiantv

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.asJsoup
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

class Myasiantv : Source() {

    override val name = "MyAsianTV"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // Shared Video Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/popular-series/" else "$baseUrl/popular-series/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeListPage(response)

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/recently-added/" else "$baseUrl/recently-added/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeListPage(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = if (page == 1) {
                "$baseUrl/?type=movies&s=${query.trim()}"
            } else {
                "$baseUrl/page/$page/?type=movies&s=${query.trim()}"
            }
            return GET(url, headers)
        }

        var path = "popular-series"
        filters.forEach { filter ->
            when (filter) {
                is Filters.GenreFilter -> if (!filter.isDefault()) path = filter.toUriPart()
                is Filters.CountryFilter -> if (!filter.isDefault()) path = filter.toUriPart()
                is Filters.BrowseFilter -> if (!filter.isDefault()) path = filter.toUriPart()
                else -> {}
            }
        }

        val url = if (page == 1) "$baseUrl/$path/" else "$baseUrl/$path/page/$page/"
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeListPage(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.GenreFilter(),
        Filters.CountryFilter(),
        Filters.BrowseFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val elements = doc.select("ul.list-episode-item > li, ul.switch-block > li, div.tab-content ul > li")
        val animes = elements.mapNotNull { element ->
            val link = element.selectFirst("a.img, a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val img = element.selectFirst("img")
            val title = element.selectFirst("h3.title, .title")?.text()
                ?: link.attr("title").ifBlank { img?.attr("alt") ?: "" }
            if (title.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                this.title = title.trim()
                setUrlWithoutDomain(href)
                thumbnail_url = img?.attr("data-original")?.takeIf { it.isNotBlank() } ?: img?.attr("src")
            }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst("div.pagination ul.page-numbers a.next, div.pagination a.next, a.page-numbers:contains(Next)") != null
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        val categoryLink = doc.selectFirst("div.category a, div.block.watch-drama div.category a")?.attr("href")
        val detailDoc = if (!categoryLink.isNullOrBlank() && !categoryLink.contains(anime.url.trimEnd('/'))) {
            runCatching { client.newCall(GET(categoryLink, headers)).execute().asJsoup() }.getOrDefault(doc)
        } else {
            doc
        }

        val title = detailDoc.selectFirst("div.details h1, div.info h1, h1")?.text()
            ?.replace(Regex("(?i)\\s*English SUB.*"), "")
            ?.replace(Regex("(?i)\\s*\\|\\s*Dramacool"), "")
            ?.trim() ?: anime.title
        val thumbnail = detailDoc.selectFirst("div.details div.img img, div.img img")?.attr("src")
            ?: anime.thumbnail_url

        val infoParas = detailDoc.select("div.info p, div.details p")
        val statusRaw = infoParas.find { it.text().contains("Status", ignoreCase = true) }?.text() ?: ""
        val genres = infoParas.find { it.text().contains("Genre", ignoreCase = true) }?.select("a")?.eachText()?.joinToString()
        val director = infoParas.find { it.text().contains("Director", ignoreCase = true) }?.select("a")?.eachText()?.joinToString()
        val country = infoParas.find { it.text().contains("Country", ignoreCase = true) }?.select("a")?.text()
        val network = infoParas.find { it.text().contains("Original Network", ignoreCase = true) }?.select("a")?.text()
        val otherName = detailDoc.selectFirst("p.other_name")?.text()
        val synopsis = detailDoc.selectFirst("div.details div.desc, div.info div.desc, div.block-watch")?.text() ?: ""

        return SAnime.create().apply {
            this.title = title
            this.thumbnail_url = thumbnail
            this.genre = genres
            this.author = director
            this.status = when {
                statusRaw.contains("Ongoing", ignoreCase = true) || statusRaw.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
                statusRaw.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            this.description = buildString {
                if (!otherName.isNullOrBlank()) append("$otherName\n\n")
                if (!country.isNullOrBlank()) append("Country: $country\n")
                if (!network.isNullOrBlank()) append("Network: $network\n")
                if (director != null && director.isNotBlank()) append("Director: $director\n")
                if (synopsis.isNotBlank()) append("\n$synopsis")
            }.trim()
            this.initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        var doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        var epElements = doc.select("ul.all-episode li, ul.list-episode-item-2.all-episode li")

        if (epElements.isEmpty()) {
            val categoryLink = doc.selectFirst("div.category a, div.block.watch-drama div.category a")?.attr("href")
            if (!categoryLink.isNullOrBlank()) {
                val fullCat = if (categoryLink.startsWith("http")) categoryLink else "$baseUrl$categoryLink"
                doc = client.newCall(GET(fullCat, headers)).execute().asJsoup()
                epElements = doc.select("ul.all-episode li, ul.list-episode-item-2.all-episode li")
            }
        }

        val total = epElements.size
        val episodes = epElements.mapNotNull { element ->
            val link = element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (href.isBlank()) return@mapNotNull null

            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                val titleText = element.selectFirst("h3.title, .title")?.text() ?: link.text()
                name = titleText.trim().ifBlank { "Episode" }
                episode_number = Regex("""Episode\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

                scanlator = element.selectFirst("span.type")?.text() ?: "SUB"
                val timeText = element.selectFirst("span.time")?.text() ?: ""
                if (timeText.isNotBlank()) {
                    date_upload = parseEpisodeDate(timeText)
                }
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private fun parseEpisodeDate(dateStr: String): Long {
        val trimmed = dateStr.trim().lowercase()
        val now = System.currentTimeMillis()
        return when {
            trimmed.contains("second") -> {
                val num = Regex("""\d+""").find(trimmed)?.value?.toLongOrNull() ?: 1L
                now - num * 1000L
            }
            trimmed.contains("minute") -> {
                val num = Regex("""\d+""").find(trimmed)?.value?.toLongOrNull() ?: 1L
                now - num * 60 * 1000L
            }
            trimmed.contains("hour") -> {
                val num = Regex("""\d+""").find(trimmed)?.value?.toLongOrNull() ?: 1L
                now - num * 60 * 60 * 1000L
            }
            trimmed.contains("day") -> {
                val num = Regex("""\d+""").find(trimmed)?.value?.toLongOrNull() ?: 1L
                now - num * 24 * 60 * 60 * 1000L
            }
            trimmed.contains("week") -> {
                val num = Regex("""\d+""").find(trimmed)?.value?.toLongOrNull() ?: 1L
                now - num * 7 * 24 * 60 * 60 * 1000L
            }
            trimmed.contains("month") -> {
                val num = Regex("""\d+""").find(trimmed)?.value?.toLongOrNull() ?: 1L
                now - num * 30 * 24 * 60 * 60 * 1000L
            }
            trimmed.contains("year") -> {
                val num = Regex("""\d+""").find(trimmed)?.value?.toLongOrNull() ?: 1L
                now - num * 365 * 24 * 60 * 60 * 1000L
            }
            else -> runCatching { DATE_FORMAT.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
        }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val epUrl = if (episode.url.startsWith("http")) episode.url else "$baseUrl${episode.url}"
        val doc = client.newCall(GET(epUrl, headers)).execute().asJsoup()
        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()

        val hosters = mutableListOf<Hoster>()
        val serverElements = doc.select("div.anime_muti_link ul li")

        if (serverElements.isNotEmpty()) {
            serverElements.forEach { li ->
                val rawUrl = li.attr("data-video").trim()
                val videoUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                val sName = li.ownText().trim().ifBlank {
                    li.text().replace("Choose this server", "", ignoreCase = true).trim()
                }.ifBlank { "Server" }

                if (videoUrl.isNotBlank() && sName !in excludedServers) {
                    hosters.add(Hoster(hosterName = sName, hosterUrl = videoUrl))
                }
            }
        } else {
            doc.select("div.watch_video iframe, iframe[src]").forEachIndexed { idx, iframe ->
                val rawUrl = iframe.attr("src").trim()
                val videoUrl = if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
                val sName = "Server ${idx + 1}"
                if (videoUrl.isNotBlank() && sName !in excludedServers) {
                    hosters.add(Hoster(hosterName = sName, hosterUrl = videoUrl))
                }
            }
        }

        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return hosters.sortedByDescending { it.hosterName.contains(prefServer, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val videoList = mutableListOf<Video>()

        val extracted = when {
            embedUrl.contains("megaplay.su") ->
                extractMegaplay(embedUrl)
            embedUrl.contains("megavid.buzz") || embedUrl.contains("/kisskh/") || embedUrl.contains("kissasian") ->
                extractMegavid(embedUrl)
            embedUrl.contains("vidb.top") || embedUrl.contains("vidbasic.top") ->
                extractVidb(embedUrl)
            embedUrl.contains("vidbasic.live") || embedUrl.contains("/stream/s-1/") ->
                extractVidbasicLive(embedUrl)
            embedUrl.contains("dood") || embedUrl.contains("ds2play") || embedUrl.contains("doodstream") ->
                doodExtractor.videosFromUrl(embedUrl)
            embedUrl.contains("streamtape") ->
                streamtapeExtractor.videoFromUrl(embedUrl)?.let { listOf(it) } ?: emptyList()
            embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") ->
                filemoonExtractor.videosFromUrl(embedUrl, prefix = "", headers = embedHeaders)
            embedUrl.contains("streamwish") || embedUrl.contains("wish") ->
                streamWishExtractor.videosFromUrl(embedUrl, videoNameGen = { it })
            embedUrl.contains("vidhide") ->
                vidHideExtractor.videosFromUrl(embedUrl, videoNameGen = { it })
            embedUrl.contains("mp4upload") ->
                mp4uploadExtractor.videosFromUrl(embedUrl, embedHeaders)
            embedUrl.endsWith(".m3u8") || embedUrl.contains(".m3u8?") ->
                playlistUtils.extractFromHls(embedUrl, referer = "$baseUrl/", videoNameGen = { it })
            else ->
                extractGenericOrUniversal(embedUrl, embedHeaders)
        }

        videoList.addAll(
            extracted.map { v ->
                Video(
                    videoUrl = v.videoUrl,
                    videoTitle = v.videoTitle,
                    headers = v.headers ?: embedHeaders,
                    resolution = v.resolution,
                    subtitleTracks = v.subtitleTracks,
                )
            },
        )

        return videoList.sortVideos()
    }

    private fun extractMegaplay(url: String): List<Video> {
        return runCatching {
            val resp = client.newCall(GET(url, headers.newBuilder().set("Referer", "$baseUrl/").build())).execute()
            val body = resp.body.string()
            val m3u8Url = Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""").find(body)?.groupValues?.get(1)
                ?: return@runCatching emptyList()
            playlistUtils.extractFromHls(
                playlistUrl = m3u8Url,
                referer = "https://megaplay.su/",
                videoNameGen = { it },
            )
        }.getOrDefault(emptyList())
    }

    private fun extractMegavid(url: String): List<Video> {
        return runCatching {
            val id = Regex("""/kisskh/(\d+)""").find(url)?.groupValues?.get(1)
                ?: url.substringAfter("/kisskh/").substringBefore("/")
            val apiUrl = "https://megavid.buzz/kisskh/$id/source"
            val req = GET(apiUrl, headers.newBuilder().set("Referer", "https://megavid.buzz/kisskh/$id").build())
            val res = client.newCall(req).execute().body.string()
            val json = JSONObject(res)
            val source = json.optString("source")
            if (source.isNullOrBlank()) return@runCatching emptyList()

            val tracks = mutableListOf<Track>()
            val tracksArr = json.optJSONArray("tracks")
            if (tracksArr != null) {
                for (i in 0 until tracksArr.length()) {
                    val tObj = tracksArr.getJSONObject(i)
                    val f = tObj.optString("file")
                    val l = tObj.optString("label", "Sub")
                    if (f.isNotBlank()) {
                        tracks.add(Track(url = f, lang = l))
                    }
                }
            }

            playlistUtils.extractFromHls(
                playlistUrl = source,
                referer = "https://megavid.buzz/",
                subtitleList = tracks,
                videoNameGen = { it },
            )
        }.getOrDefault(emptyList())
    }

    private fun extractVidb(url: String): List<Video> {
        return runCatching {
            var targetUrl = url
            if (!targetUrl.contains("3rdplayer.html")) {
                val resp = client.newCall(GET(targetUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())).execute()
                val body = resp.body.string()
                val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(body)?.groupValues?.get(1)
                if (!iframeSrc.isNullOrBlank()) {
                    targetUrl = if (iframeSrc.startsWith("http")) iframeSrc else {
                        val base = targetUrl.substringBefore("/embed/")
                        "$base$iframeSrc"
                    }
                }
            }

            val resp = client.newCall(GET(targetUrl, headers.newBuilder().set("Referer", "$baseUrl/").build())).execute()
            val html = resp.body.string()
            val keyParam = Regex("""key=([^&"']+)""").find(targetUrl)?.groupValues?.get(1)?.let {
                URLDecoder.decode(it, "UTF-8")
            }
            val dataVal = Regex("""data-name=["']crypto["']\s+data-value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: keyParam ?: return@runCatching emptyList()

            val decryptedM3u8 = decryptVidb(dataVal) ?: return@runCatching emptyList()
            val subParam = Regex("""sub=([^&"']+)""").find(targetUrl)?.groupValues?.get(1)?.let {
                URLDecoder.decode(it, "UTF-8")
            }
            val subList = mutableListOf<Track>()
            if (!subParam.isNullOrBlank()) {
                decryptVidb(subParam)?.let { subUrl ->
                    if (subUrl.startsWith("http")) {
                        subList.add(Track(url = subUrl, lang = "English"))
                    }
                }
            }

            playlistUtils.extractFromHls(
                playlistUrl = decryptedM3u8,
                referer = "https://vidb.top/",
                subtitleList = subList,
                videoNameGen = { it },
            )
        }.getOrDefault(emptyList())
    }

    private fun extractVidbasicLive(url: String): List<Video> {
        return runCatching {
            val id = Regex("""/stream/(?:s-\d+/)?(\d+)""").find(url)?.groupValues?.get(1)
                ?: url.trimEnd('/').substringAfterLast('/')
            if (id.isNotBlank() && id.all { it.isDigit() }) {
                val bridgeUrl = "https://megavid.buzz/kisskh/$id"
                extractMegavid(bridgeUrl)
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun extractGenericOrUniversal(embedUrl: String, embedHeaders: okhttp3.Headers): List<Video> {
        return runCatching {
            val resp = client.newCall(GET(embedUrl, embedHeaders)).execute()
            val html = resp.body.string()
            val innerIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (!innerIframe.isNullOrBlank() && innerIframe != embedUrl) {
                val fullInner = if (innerIframe.startsWith("http")) innerIframe else "$baseUrl$innerIframe"
                val nestedVideos = when {
                    fullInner.contains("megaplay.su") -> extractMegaplay(fullInner)
                    fullInner.contains("megavid.buzz") -> extractMegavid(fullInner)
                    fullInner.contains("vidb.top") || fullInner.contains("vidbasic.top") -> extractVidb(fullInner)
                    else -> emptyList()
                }
                if (nestedVideos.isNotEmpty()) {
                    return@runCatching nestedVideos
                }
            }
            val directM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.groupValues?.get(1)
            if (!directM3u8.isNullOrBlank()) {
                playlistUtils.extractFromHls(directM3u8, referer = embedUrl, videoNameGen = { it })
            } else {
                universalExtractor.videosFromUrl(embedUrl, embedHeaders, prefix = "")
            }
        }.getOrDefault(emptyList())
    }

    private fun decryptVidb(cipherTextB64: String): String? {
        return runCatching {
            val keyBytes = "94588293375053432799222445521289".toByteArray(Charsets.UTF_8)
            val ivBytes = "5259228356829423".toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes),
            )
            val decoded = Base64.decode(cipherTextB64, Base64.DEFAULT)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        }.getOrNull()
    }

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
        screen.addBaseUrlPreference(
            preferences = preferences,
            defaultUrl = PREF_BASE_URL_DEFAULT,
            title = "Base URL",
            key = PREF_BASE_URL_KEY,
        )
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Auto", "Standard Server", "DoodStream", "StreamTape", "FileMoon"),
            entryValues = listOf("auto", "Standard Server", "DoodStream", "StreamTape", "FileMoon"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide from playback",
            entries = listOf("Standard Server", "DoodStream", "StreamTape", "FileMoon"),
            entryValues = listOf("Standard Server", "DoodStream", "StreamTape", "FileMoon"),
            default = emptySet(),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://myasiantv.com.lv"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

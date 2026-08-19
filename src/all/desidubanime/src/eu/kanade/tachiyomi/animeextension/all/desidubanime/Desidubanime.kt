package eu.kanade.tachiyomi.animeextension.all.desidubanime

import android.net.Uri
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.buzzheavierextractor.BuzzheavierExtractor
import eu.kanade.tachiyomi.lib.byseextractor.ByseExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.UrlUtils
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class Desidubanime : Source() {

    override val name = "DesiDubAnime"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .rateLimit(permits = 4, period = 1.seconds)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val buzzheavierExtractor by lazy { BuzzheavierExtractor(client, headers) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamP2PExtractor by lazy { StreamP2PExtractor(client, headers) }
    private val localProxy by lazy { LocalProxy(client) }
    private val abyssExtractor by lazy { AbyssExtractor(client, playlistUtils, localProxy) }
    private val byseExtractor by lazy { ByseExtractor(client, playlistUtils) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val request = GET("$baseUrl/anime/page/$page/", headers)
        val response = client.newCall(request).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val request = if (page == 1) {
            GET("$baseUrl/", headers)
        } else {
            GET("$baseUrl/anime/page/$page/", headers)
        }
        val response = client.newCall(request).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val apiUrl = "$baseUrl/wp-json/kiranime/v1/anime/search?query=$encodedQuery"
            val response = client.newCall(GET(apiUrl, headers)).execute()
            val body = response.body.string()
            val jsonObject = Json.parseToJsonElement(body).jsonObject
            val htmlResult = jsonObject["result"]?.jsonPrimitive?.content ?: ""

            if (htmlResult.isBlank()) return AnimesPage(emptyList(), false)

            val doc = Jsoup.parse(htmlResult, baseUrl)
            val animeList = doc.select("a[href]").mapNotNull { el ->
                val href = el.attr("href")
                if (href.isBlank()) return@mapNotNull null

                val title = el.selectFirst("p.font-semibold, p.text-sm")?.text()
                    ?: el.text().substringBefore("|").trim()
                if (title.isBlank()) return@mapNotNull null

                SAnime.create().apply {
                    this.title = title
                    setUrlWithoutDomain(href)
                    thumbnail_url = el.selectFirst("img")?.let { img ->
                        img.attr("src").ifBlank { img.attr("data-src") }
                    }
                    fetch_type = FetchType.Episodes
                }
            }
            return AnimesPage(animeList, false)
        }

        var filterPath = ""
        for (filter in filters) {
            when (filter) {
                is Filters.LanguageFilter -> {
                    if (!filter.isDefault()) {
                        filterPath = filter.toPath()
                        break
                    }
                }

                is Filters.GenreFilter -> {
                    if (!filter.isDefault()) {
                        filterPath = filter.toPath()
                        break
                    }
                }

                is Filters.SeasonFilter -> {
                    if (!filter.isDefault()) {
                        filterPath = filter.toPath()
                        break
                    }
                }

                else -> {}
            }
        }

        val requestUrl = if (filterPath.isNotBlank()) {
            "$baseUrl/$filterPath/page/$page/"
        } else {
            "$baseUrl/anime/page/$page/"
        }

        val response = client.newCall(GET(requestUrl, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        Filters.LanguageFilter(),
        Filters.GenreFilter(),
        Filters.SeasonFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("article.anime-card, article").mapNotNull { article ->
            val infoBtn = article.selectFirst("button[onclick*='/anime/']")
            val infoUrl = infoBtn?.attr("onclick")
                ?.substringAfter("window.location.href='")
                ?.substringBefore("'")

            val rawLink = infoUrl
                ?: article.selectFirst("a[href*='/anime/']")?.attr("href")
                ?: article.selectFirst("h3 a, a.stretched-link, a")?.attr("href")
                ?: return@mapNotNull null

            val cleanUrl = if (rawLink.contains("/watch/")) {
                rawLink.replace("/watch/", "/anime/")
                    .replace(Regex("-episode-\\d+/?$"), "/")
            } else {
                rawLink
            }

            val title = article.selectFirst("h3, h2.entry-title, a[title]")?.let {
                it.attr("title").ifBlank { it.text() }
            } ?: article.selectFirst("img")?.attr("alt")?.replace(" poster", "") ?: ""

            if (title.isBlank()) return@mapNotNull null

            val poster = article.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            }

            SAnime.create().apply {
                this.title = title.trim()
                setUrlWithoutDomain(cleanUrl)
                thumbnail_url = poster
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst("a[data-action='load-more'], a.next-page, a.next, link[rel='next']") != null ||
            doc.select("article.anime-card").size >= 20
        return AnimesPage(animeList, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET(UrlUtils.fixUrl(anime.url, baseUrl), headers)).execute().asJsoup()
        val titleText = doc.selectFirst("h1, h2.entry-title, meta[property=og:title]")?.text()
            ?.substringBefore(" - Desi Dub Anime")
            ?.substringBefore(" - DesiDubAnime")
            ?: anime.title

        val poster = doc.selectFirst("img.anime-main-image, img[src*='anilistcdn'], img[data-src*='anilistcdn'], div.post-thumbnail img, img[alt*='poster']")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        }?.takeIf { !it.contains("Logoo", ignoreCase = true) && !it.contains("logo", ignoreCase = true) }
            ?: anime.thumbnail_url

        val metadataMap = mutableMapOf<String, String>()
        doc.select("dl > div").forEach { item ->
            val dt = item.selectFirst("dt")?.text()?.trim().orEmpty()
            val dd = item.selectFirst("dd")?.text()?.trim().orEmpty()
            if (dt.isNotBlank() && dd.isNotBlank() && dd != "N/A") {
                metadataMap[dt] = dd
            }
        }

        val synopsis = doc.selectFirst("section[aria-label*='Overview'] p, section[aria-label*='Overview'], div.entry-content, #synopsis, div.synopsis")?.text()
            ?.substringBefore("Tags:")
            ?.trim()
            .orEmpty()

        val descriptionBuilder = StringBuilder()
        if (synopsis.isNotBlank()) {
            descriptionBuilder.append(synopsis).append("\n\n")
        }

        listOf("Native", "Synonyms", "English", "Type", "Episodes", "Aired", "Season", "Released Year", "Producers", "Licensors", "Tags").forEach { key ->
            metadataMap[key]?.let { value ->
                descriptionBuilder.append("$key: $value\n")
            }
        }

        val studios = metadataMap["Studios"]
        val genres = doc.select("a[href*='/genre/'], a[href*='/tag/']").map { it.text().trim() }
            .filter { it.isNotBlank() && it != "N/A" }
            .distinct()
            .joinToString(", ")

        val isOngoing = doc.text().contains("Airing", ignoreCase = true) ||
            doc.text().contains("Ongoing", ignoreCase = true)

        return SAnime.create().apply {
            title = titleText.trim()
            setUrlWithoutDomain(anime.url)
            thumbnail_url = poster
            description = descriptionBuilder.toString().trim()
            genre = genres.ifBlank { metadataMap["Genres"] }
            author = studios
            artist = studios
            status = if (isOngoing) SAnime.ONGOING else SAnime.COMPLETED
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val animeUrl = UrlUtils.fixUrl(anime.url, baseUrl)
        val doc = client.newCall(GET(animeUrl, headers)).execute().asJsoup()

        val seasonButtons = doc.select("#seasonButtonsContainer button[data-season]")
        val seasonIds = if (seasonButtons.isNotEmpty()) {
            seasonButtons.mapNotNull { it.attr("data-season").takeIf { s -> s.isNotBlank() } }
        } else {
            val sId = doc.selectFirst("#seasonContent[data-season]")?.attr("data-season")
                ?: doc.selectFirst("[id^=watchlist-]")?.id()?.substringAfter("watchlist-")
                ?: extractPostId(doc)
            if (!sId.isNullOrBlank()) listOf(sId) else emptyList()
        }

        val episodes = mutableListOf<SEpisode>()

        seasonIds.forEachIndexed { seasonIdx, seasonId ->
            var currentPage = 1
            var maxPage = 1

            while (currentPage <= maxPage) {
                val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?action=get_episodes&anime_id=$seasonId&page=$currentPage&order=asc"
                val response = client.newCall(GET(ajaxUrl, headers)).execute()
                val body = response.body.string()

                val jsonElement = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                    ?: break

                if (jsonElement["success"]?.jsonPrimitive?.booleanOrNull != true) break

                val dataObj = jsonElement["data"]?.jsonObject ?: break
                maxPage = dataObj["max_episodes_page"]?.jsonPrimitive?.intOrNull ?: 1

                val epArray = dataObj["episodes"]?.jsonArray ?: break
                epArray.forEach { epItem ->
                    val epObj = epItem.jsonObject
                    val epTitle = epObj["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: epObj["post_title"]?.jsonPrimitive?.contentOrNull
                        ?: "Episode"
                    val numberStr = epObj["number"]?.jsonPrimitive?.contentOrNull ?: ""
                    val metaNumber = epObj["meta_number"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                    val rawEpNum = metaNumber
                        ?: EPISODE_NUMBER_REGEX.find(numberStr)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: (episodes.size + 1).toFloat()

                    val epNum = if (seasonIds.size > 1) {
                        ((seasonIdx) * 100 + rawEpNum)
                    } else {
                        rawEpNum
                    }

                    val epUrl = epObj["url"]?.jsonPrimitive?.contentOrNull ?: ""
                    val dateStr = epObj["released"]?.jsonPrimitive?.contentOrNull ?: ""
                    val uploadTime = if (dateStr.isNotBlank()) {
                        runCatching { DATE_FORMAT.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
                    } else {
                        0L
                    }

                    val episode = SEpisode.create().apply {
                        name = if (epTitle.contains("Episode", ignoreCase = true)) epTitle else "$numberStr: $epTitle"
                        episode_number = epNum
                        url = epUrl.ifBlank { "${anime.url}#season=$seasonId&ep=$rawEpNum" }
                        date_upload = uploadTime
                    }
                    episodes.add(episode)
                }

                currentPage++
            }
        }

        return episodes.reversed()
    }

    private fun extractPostId(doc: Document): String? {
        val html = doc.html()
        val patterns = listOf(
            Regex("""current_anime_id\s*=\s*(\d+)"""),
            Regex("""current_post_data_id\s*=\s*(\d+)"""),
            Regex(""""postId":\s*"(\d+)""""),
            Regex("""postId:\s*'(\d+)'"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val watchUrl = UrlUtils.fixUrl(episode.url, baseUrl)
        val response = client.newCall(GET(watchUrl, headers)).execute()
        val doc = response.asJsoup()

        val iframes = doc.select("iframe[src]").map { it.absUrl("src") }
            .filter { it.isNotBlank() }

        val hosters = mutableListOf<Hoster>()

        iframes.forEach { embedUrl ->
            if (embedUrl.contains("gdmirrorbot") || embedUrl.contains("iqsmartgames") || embedUrl.contains("p2pplay")) {
                try {
                    val embedResponse = client.newCall(GET(embedUrl, headers)).execute()
                    val finalUrl = embedResponse.request.url.toString()
                    embedResponse.close()

                    val sid = embedUrl.substringAfterLast("embed/").substringBefore("?").substringBefore("/")
                    val hostUri = Uri.parse(finalUrl)
                    val host = "${hostUri.scheme}://${hostUri.host}"

                    val formBody = FormBody.Builder()
                        .add("sid", sid)
                        .add("UserFavSite", "")
                        .add("currentDomain", "[]")
                        .build()

                    val helperHeaders = Headers.Builder()
                        .set("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .set("Referer", finalUrl)
                        .set("Origin", host)
                        .set("X-Requested-With", "XMLHttpRequest")
                        .build()

                    var helperBody = ""
                    for (endpoint in listOf("/embedhelper2.php", "/embedhelper.php")) {
                        try {
                            val helperRequest = Request.Builder()
                                .url("$host$endpoint")
                                .post(formBody)
                                .headers(helperHeaders)
                                .build()
                            val helperResponse = client.newCall(helperRequest).execute()
                            if (helperResponse.isSuccessful) {
                                helperBody = helperResponse.body.string()
                                helperResponse.close()
                                if (helperBody.isNotBlank() && !helperBody.contains("error")) {
                                    break
                                }
                            } else {
                                helperResponse.close()
                            }
                        } catch (_: Exception) {}
                    }

                    if (helperBody.isNotBlank()) {
                        val jsonObject = Json.parseToJsonElement(helperBody).jsonObject
                        val sourcesObj = jsonObject["sources"]?.jsonObject
                        val siteUrls = jsonObject["siteUrls"]?.jsonObject ?: emptyMap()
                        val siteFriendlyNames = jsonObject["siteFriendlyNames"]?.jsonObject ?: emptyMap()
                        val mresultElement = jsonObject["mresult"]

                        val mresultString = when {
                            mresultElement == null -> null

                            mresultElement is JsonPrimitive && mresultElement.isString -> {
                                val base64Str = mresultElement.content
                                try {
                                    String(Base64.decode(base64Str, Base64.DEFAULT), Charsets.UTF_8)
                                } catch (_: Exception) {
                                    base64Str
                                }
                            }

                            else -> mresultElement.toString()
                        }

                        if (!mresultString.isNullOrBlank()) {
                            val mresultObject = Json.parseToJsonElement(mresultString).jsonObject
                            for ((key, pathElement) in mresultObject) {
                                val path = pathElement.jsonPrimitive.content.trimStart('/')
                                val srcInfo = sourcesObj?.get(key)?.jsonObject
                                val base = srcInfo?.get("siteUrl")?.jsonPrimitive?.content?.trimEnd('/')
                                    ?: siteUrls[key]?.jsonPrimitive?.content?.trimEnd('/')
                                    ?: continue
                                val fullUrl = if (base.endsWith("/#") || base.endsWith("#")) {
                                    "$base$path"
                                } else {
                                    "$base/$path"
                                }
                                val friendlyName = srcInfo?.get("friendlyName")?.jsonPrimitive?.content
                                    ?: siteFriendlyNames[key]?.jsonPrimitive?.content
                                    ?: key

                                hosters.add(
                                    Hoster(
                                        hosterName = friendlyName.replaceFirstChar { it.uppercase() },
                                        hosterUrl = fullUrl,
                                    ),
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Fallback to direct embed
                    hosters.add(Hoster(hosterName = "Mirror", hosterUrl = embedUrl))
                }
            } else {
                hosters.add(Hoster(hosterName = "Player", hosterUrl = embedUrl))
            }
        }

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        val filteredHosters = hosters.filter { h ->
            excludedServers.none { exc -> h.hosterName.contains(exc, ignoreCase = true) }
        }

        return filteredHosters.distinctBy { it.hosterUrl }.sortedWith(
            compareByDescending<Hoster> {
                if (prefServer != "auto") {
                    it.hosterName.contains(prefServer, ignoreCase = true)
                } else {
                    it.hosterName.contains("byse", ignoreCase = true)
                }
            }.thenByDescending { it.hosterName.contains("byse", ignoreCase = true) },
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()

        val videos = when {
            url.contains("bysetayico") || url.contains("byse") ->
                byseExtractor.videosFromUrl(url)

            url.contains("filemoon") ->
                filemoonExtractor.videosFromUrl(url)

            url.contains("streamwish") || url.contains("hanerix") || url.contains("wish") ->
                streamWishExtractor.videosFromUrl(url, "StreamWish")

            url.contains("vidhide") || url.contains("streamhg") || url.contains("animezia") ->
                vidHideExtractor.videosFromUrl(url) { "VidHide - $it" }

            url.contains("streamtape") ->
                streamtapeExtractor.videoFromUrl(url)?.let { listOf(it) } ?: emptyList()

            url.contains("dood") ->
                doodExtractor.videosFromUrl(url)

            url.contains("buzzheavier") ->
                buzzheavierExtractor.videosFromUrl(url, "Buzzheavier - ")

            url.contains("strp2p") || url.contains("rpmstream") || url.contains("upns") ->
                streamP2PExtractor.videosFromUrl(url, hoster.hosterName)

            url.contains("abyss") ->
                abyssExtractor.videosFromUrl(url)

            url.endsWith(".m3u8") || url.contains(".m3u8?") ->
                playlistUtils.extractFromHls(
                    playlistUrl = url,
                    referer = "$baseUrl/",
                    videoNameGen = { quality -> quality },
                )

            else ->
                universalExtractor.videosFromUrl(url, embedHeaders)
        }

        val excludedAudio = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()
        val filteredVideos = if (excludedAudio.isNotEmpty()) {
            videos.filter { v ->
                excludedAudio.none { exc -> v.videoTitle.contains(exc, ignoreCase = true) }
            }
        } else {
            videos
        }

        return filteredVideos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefAudio = preferences.getString(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT) ?: PREF_AUDIO_DEFAULT
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT

        return sortedWith(
            compareByDescending<Video> { if (prefAudio != "auto") it.videoTitle.contains(prefAudio, ignoreCase = true) else false }
                .thenByDescending { if (prefServer != "auto") it.videoTitle.contains(prefServer, ignoreCase = true) else false }
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

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred Server"
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = PREF_AUDIO_KEY
            title = "Preferred Audio / Dub"
            entries = PREF_AUDIO_ENTRIES
            entryValues = PREF_AUDIO_VALUES
            setDefaultValue(PREF_AUDIO_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also { screen.addPreference(it) }

        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_SERVERS_KEY
            title = "Exclude Servers"
            summary = "Select servers to hide from playback"
            entries = PREF_EXCLUDE_SERVER_ENTRIES
            entryValues = PREF_EXCLUDE_SERVER_VALUES
            setDefaultValue(emptySet<String>())
        }.also { screen.addPreference(it) }

        androidx.preference.MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_AUDIO_KEY
            title = "Exclude Audio / Dub Types"
            summary = "Select languages/audio types to hide"
            entries = PREF_AUDIO_ENTRIES.filter { it != "Auto" }.toTypedArray()
            entryValues = PREF_AUDIO_VALUES.filter { it != "auto" }.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://www.desidubanime.me"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "Byse"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Auto (Recommended - Byse)",
            "Byse (FileMoon) [✅ Working]",
            "Buzzheavier [✅ Working]",
            "VidHide [✅ Working]",
            "Abyss [❌ Broken - CDN Encrypted]",
            "StreamWish [❌ Dead/Expired]",
            "StreamTape [❌ Dead/DNS Down]",
            "DoodStream [❌ Blocked/Cloudflare]",
            "StreamP2P / RPMStream [❌ Dead/404]",
        )
        private val PREF_SERVER_VALUES = arrayOf("auto", "Byse", "Buzzheavier", "VidHide", "Abyss", "StreamWish", "StreamTape", "DoodStream", "StreamP2P")

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "auto"
        private val PREF_AUDIO_ENTRIES = arrayOf("Auto", "Hindi", "Tamil", "Telugu", "English", "Japanese", "Bengali", "Malayalam", "Kannada")
        private val PREF_AUDIO_VALUES = arrayOf("auto", "Hindi", "Tamil", "Telugu", "English", "Japanese", "Bengali", "Malayalam", "Kannada")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private val PREF_EXCLUDE_SERVER_ENTRIES = arrayOf(
            "Byse (FileMoon)",
            "Buzzheavier",
            "VidHide",
            "Abyss (Broken)",
            "StreamWish (Dead)",
            "StreamTape (Dead)",
            "DoodStream (Blocked)",
            "StreamP2P / RPMStream (Dead)",
        )
        private val PREF_EXCLUDE_SERVER_VALUES = arrayOf("Byse", "Buzzheavier", "VidHide", "Abyss", "StreamWish", "StreamTape", "DoodStream", "StreamP2P")

        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"

        private val EPISODE_NUMBER_REGEX = Regex("""(?:Episode|Ep\.?)\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    class StreamP2PExtractor(private val client: OkHttpClient, private val headers: Headers) {
        fun videosFromUrl(url: String, prefix: String = "StreamP2P"): List<Video> {
            val strmp2Id = url.substringAfterLast("embed/").substringAfterLast("/").substringBefore("?").substringBefore("#")
            val apiHost = "https://cloudy.p2pplay.pro"
            val apiUrl = "$apiHost/api/v1/video?id=$strmp2Id&w=1920&h=1080&r=pro.iqsmartgames.com"

            val reqHeaders = headers.newBuilder()
                .set("Referer", "https://clswine.strp2p.com/")
                .set("Origin", "https://clswine.strp2p.com")
                .build()

            val response = client.newCall(GET(apiUrl, reqHeaders)).execute()
            if (response.code != 200) {
                response.close()
                return emptyList()
            }
            val encryptedHex = response.body.string().trim()
            response.close()

            val decryptedJson = tryDecrypt(encryptedHex) ?: return emptyList()
            val jsonObject = Json.parseToJsonElement(decryptedJson).jsonObject
            val streamingConfigStr = jsonObject["streamingConfig"]?.jsonPrimitive?.content ?: return emptyList()
            val streamingConfig = Json.parseToJsonElement(streamingConfigStr).jsonObject
            val order = streamingConfig["order"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            val adjust = streamingConfig["adjust"]?.jsonObject ?: emptyMap()

            val videoList = mutableListOf<Video>()

            fun addVideo(streamPath: String, hostName: String, params: Map<String, String>) {
                val base = if (streamPath.startsWith("//")) {
                    "https:$streamPath"
                } else if (streamPath.startsWith("http")) {
                    streamPath
                } else {
                    "$apiHost/${streamPath.trimStart('/')}"
                }

                val builder = base.toHttpUrlOrNull()?.newBuilder() ?: return
                params.forEach { (k, v) ->
                    builder.setQueryParameter(k, v)
                }
                val finalUrl = builder.build().toString()

                val subtitleTracks = mutableListOf<Track>()
                jsonObject["subtitle"]?.jsonObject?.forEach { (lang, subPathElement) ->
                    val subPath = subPathElement.jsonPrimitive.content.substringBefore("#")
                    val subUrl = if (subPath.startsWith("http")) subPath else "$apiHost/${subPath.trimStart('/')}"
                    subtitleTracks.add(Track(subUrl, lang))
                }

                videoList.add(
                    Video(
                        videoUrl = finalUrl,
                        videoTitle = "$prefix - $hostName",
                        headers = reqHeaders,
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }

            order.forEach { host ->
                val hostConfig = adjust[host]?.jsonObject
                val disabled = hostConfig?.get("disabled")?.jsonPrimitive?.booleanOrNull ?: false
                if (disabled) return@forEach

                val rawParams = hostConfig?.get("params")
                val params = mutableMapOf<String, String>()
                if (rawParams != null && rawParams is JsonObject) {
                    rawParams.forEach { (k, v) ->
                        params[k] = v.jsonPrimitive.content
                    }
                }

                when (host) {
                    "Cloudflare" -> {
                        val cfPath = jsonObject["cf"]?.jsonPrimitive?.contentOrNull
                        if (!cfPath.isNullOrBlank()) {
                            addVideo(cfPath, "Cloudflare", params)
                        }
                    }

                    "Tiktok" -> {
                        val tiktokPath = jsonObject["hlsVideoTiktok"]?.jsonPrimitive?.contentOrNull
                        if (!tiktokPath.isNullOrBlank()) {
                            addVideo(tiktokPath, "Tiktok", params)
                        }
                    }

                    "Google" -> {
                        val googlePath = jsonObject["hlsVideoGoogle"]?.jsonPrimitive?.contentOrNull
                        if (!googlePath.isNullOrBlank()) {
                            addVideo(googlePath, "Google", params)
                        }
                    }

                    "In-House" -> {
                        val sourcePath = jsonObject["source"]?.jsonPrimitive?.contentOrNull
                        if (!sourcePath.isNullOrBlank()) {
                            addVideo(sourcePath, "In-House", params)
                        }
                    }
                }
            }

            return videoList
        }

        private fun tryDecrypt(encryptedHex: String): String? {
            val key = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
            val ivs = listOf("1234567890oiuytr", "0123456789abcdef")

            for (ivStr in ivs) {
                try {
                    val iv = ivStr.toByteArray(Charsets.UTF_8)
                    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                    val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
                    val ivSpec = javax.crypto.spec.IvParameterSpec(iv)
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec)

                    val encryptedBytes = encryptedHex.chunked(2)
                        .map { it.toInt(16).toByte() }
                        .toByteArray()

                    val decryptedBytes = cipher.doFinal(encryptedBytes)
                    val decrypted = String(decryptedBytes, Charsets.UTF_8)
                    if (decrypted.contains("streamingConfig")) {
                        return decrypted
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
            return null
        }
    }
}

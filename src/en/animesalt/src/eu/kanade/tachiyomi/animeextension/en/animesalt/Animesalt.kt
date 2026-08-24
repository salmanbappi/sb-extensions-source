package eu.kanade.tachiyomi.animeextension.en.animesalt

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.universalextractor.UniversalExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import extensions.utils.Source
import extensions.utils.UrlUtils
import extensions.utils.asJsoup
import keiyoushi.utils.addBaseUrlPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

class Animesalt : Source() {

    override val name = "AnimeSalt"

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
        .add("Referer", "$baseUrl/")

    // Shared Video Extractors
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val universalExtractor by lazy { UniversalExtractor(client) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/type/popular/" else "$baseUrl/type/popular/page/$page/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/tv/" else "$baseUrl/tv/page/$page/"
        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            if (page == 1) "$baseUrl/?s=$encodedQuery" else "$baseUrl/page/$page/?s=$encodedQuery"
        } else {
            var targetPath = ""
            for (filter in filters) {
                when (filter) {
                    is Filters.GenreFilter -> {
                        if (!filter.isDefault()) {
                            targetPath = "category/${filter.toUriPart()}"
                            break
                        }
                    }

                    is Filters.LanguageFilter -> {
                        if (!filter.isDefault()) {
                            targetPath = "audio/${filter.toUriPart()}"
                            break
                        }
                    }

                    is Filters.TypeFilter -> {
                        if (!filter.isDefault()) {
                            val part = filter.toUriPart()
                            targetPath = "type/$part"
                            break
                        }
                    }

                    else -> {}
                }
            }

            if (targetPath.isBlank()) {
                if (page == 1) "$baseUrl/type/popular/" else "$baseUrl/type/popular/page/$page/"
            } else {
                val normalizedPath = targetPath.trimEnd('/')
                if (page == 1) {
                    "$baseUrl/$normalizedPath/"
                } else {
                    "$baseUrl/$normalizedPath/page/$page/"
                }
            }
        }

        val response = client.newCall(GET(url, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filter selections"),
        Filters.TypeFilter(),
        Filters.LanguageFilter(),
        Filters.GenreFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.anime-card, article.post, article.type-tv, .movies-list article, .post.dfx, article.inside-article").mapNotNull { element ->
            val link = element.selectFirst("a.card-link, .entry-title a, a.lnk-blk, a") ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank() || href.endsWith("/#")) return@mapNotNull null

            val titleText = element.selectFirst(".card-details h3, .entry-title, h3, h2")?.text()?.trim() ?: link.text().trim()
            if (titleText.isBlank() || titleText.contains("\${item.title}")) return@mapNotNull null

            val img = element.selectFirst(".poster-wrap img, .post-thumbnail img, figure img, img")
            val thumb = img?.let {
                it.attr("abs:src").ifBlank {
                    it.attr("abs:data-src").ifBlank {
                        it.attr("abs:data-lazy-src")
                    }
                }
            }?.takeIf { !it.startsWith("data:") }

            SAnime.create().apply {
                title = titleText
                setUrlWithoutDomain(href)
                thumbnail_url = thumb?.let { UrlUtils.fixUrl(it, baseUrl) }
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }
        val hasNext = doc.select(".pag a, .pagination a, nav a, a.next, a.page-numbers").any {
            it.text().trim().equals("NEXT", ignoreCase = true) || it.attr("class").contains("next")
        } || doc.selectFirst(".pagination .next, a.next, .nav-links .next, a:containsOwn(NEXT)") != null
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val titleText = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: anime.title
        val synopsis = doc.selectFirst("#overview-text p, #overview-text, .overviewCss, .description, .meta-summary")?.text()?.trim()
        val genres = doc.select("a[href*='/category/genre/'], a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val statusRaw = doc.select(".status, .Qlty, a[href*='/status/'], .anime-meta-v2").text()

        val posterImg = doc.selectFirst(
            ".poster-wrap img, article.post .poster img, .single-series .poster img, .s-top .poster img, .poster img, " +
                ".bd img:not(.custom-logo):not(.cn-icon), img[alt^='Image ']:not(.TPostBg), " +
                "img[src*='tmdb.org'], img[data-src*='tmdb.org'], .thumb img.wp-post-image",
        )

        val thumb = posterImg?.let {
            it.attr("abs:src").ifBlank {
                it.attr("abs:data-src").ifBlank {
                    it.attr("abs:data-lazy-src")
                }
            }
        }?.takeIf {
            !it.startsWith("data:") &&
                !it.contains("AnimeSaltLong", ignoreCase = true) &&
                !it.contains("custom-logo", ignoreCase = true)
        } ?: doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("abs:content")

        return SAnime.create().apply {
            title = titleText
            thumbnail_url = (thumb ?: anime.thumbnail_url)?.let { UrlUtils.fixUrl(it, baseUrl) }
            genre = genres.ifBlank { null }
            description = synopsis
            status = when {
                statusRaw.contains("Ongoing", ignoreCase = true) || doc.select("a[href*='/ongoing']").isNotEmpty() -> SAnime.ONGOING
                statusRaw.contains("Completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url.contains("/movies/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1.0f
                    setUrlWithoutDomain(anime.url)
                },
            )
        }

        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val episodes = mutableListOf<SEpisode>()

        // 1. New theme: .ep-tile / .quick-ep-card
        val tiles = doc.select(".ep-tile, .quick-ep-card")
        if (tiles.isNotEmpty()) {
            tiles.forEachIndexed { idx, tile ->
                val onclick = tile.attr("onclick")
                val text = tile.text().trim()
                val match = Regex("""triggerEpisode\s*\(\s*(\[.*?\])\s*,\s*["']([^"']*)["']\s*,\s*["']([^"']*)["']""").find(onclick)

                val epName = match?.groupValues?.get(2)?.ifBlank { text } ?: text.ifBlank { "Episode ${idx + 1}" }
                val epSlug = match?.groupValues?.get(3) ?: tile.attr("data-slug").ifBlank { "ep-${idx + 1}" }
                val serversJson = match?.groupValues?.get(1) ?: "[]"
                val epNum = Regex("""\d+""").find(epName)?.value?.toFloatOrNull() ?: (idx + 1).toFloat()

                episodes.add(
                    SEpisode.create().apply {
                        name = epName
                        episode_number = epNum
                        val encodedServers = Base64.encodeToString(serversJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        url = "${anime.url}#$epSlug|$encodedServers"
                    },
                )
            }
            return episodes.reversed()
        }

        val seasonButtons = doc.select(".season-buttons .season-btn[data-post][data-season]")
        val hasMultipleSeasons = seasonButtons.size > 1

        if (seasonButtons.isNotEmpty()) {
            for (btn in seasonButtons) {
                val seasonNum = btn.attr("data-season").toIntOrNull() ?: 1
                val postId = btn.attr("data-post")

                val seasonDoc = if (seasonNum == 1 || btn.hasClass("active")) {
                    doc
                } else {
                    val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?action=action_select_season&season=$seasonNum&post=$postId"
                    client.newCall(GET(ajaxUrl, headers)).execute().asJsoup()
                }

                val seasonEps = parseEpisodesFromDoc(seasonDoc, seasonNum, anime.title, hasMultipleSeasons)
                episodes.addAll(seasonEps)
            }
        } else {
            episodes.addAll(parseEpisodesFromDoc(doc, 1, anime.title, false))
        }

        return episodes.reversed()
    }

    private fun parseEpisodesFromDoc(doc: Document, seasonNum: Int, animeTitle: String, hasMultipleSeasons: Boolean): List<SEpisode> {
        return doc.select("li:has(article.episodes), article.episodes, .episodes-list li").mapIndexedNotNull { idx, element ->
            val link = element.selectFirst("a.lnk-blk, a") ?: return@mapIndexedNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapIndexedNotNull null

            val rawNum = element.selectFirst(".num-epi")?.text()?.trim()
            val match = Regex("""(?:(\d+)x)?(\d+(?:\.\d+)?)""").find(rawNum.orEmpty())
            val epNum = match?.groupValues?.get(2)?.toFloatOrNull() ?: (idx + 1).toFloat()
            val rawTitle = element.selectFirst(".entry-title, h2")?.text()?.trim() ?: "Episode ${epNum.toInt()}"
            val cleanTitle = cleanEpisodeTitle(rawTitle, animeTitle)

            val epNumber = if (seasonNum > 1) {
                ((seasonNum - 1) * 100 + epNum.toInt()).toFloat()
            } else {
                epNum
            }

            val displayName = if (hasMultipleSeasons) {
                if (cleanTitle.isNotBlank()) "S$seasonNum Ep. ${epNum.toInt()} - $cleanTitle" else "S$seasonNum Ep. ${epNum.toInt()}"
            } else {
                if (cleanTitle.isNotBlank()) "Episode ${epNum.toInt()} - $cleanTitle" else "Episode ${epNum.toInt()}"
            }

            SEpisode.create().apply {
                name = displayName
                episode_number = epNumber
                setUrlWithoutDomain(href)
            }
        }
    }

    private fun cleanEpisodeTitle(rawTitle: String, animeTitle: String): String {
        var title = rawTitle
            .replace(Regex("^(?:Private|Protected):\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        if (animeTitle.isNotBlank() && title.startsWith(animeTitle, ignoreCase = true)) {
            title = title.substring(animeTitle.length).trim(' ', '-', ':', '_')
        }

        return title
            .replace(Regex("""\[[A-Fa-f0-9]{8}\]"""), "")
            .replace(Regex("""\[?(?:1080p|720p|480p|360p|240p|4k|hd|fhd|uhd)\]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[?(?:x264|x265|h264|h265|hevc|avc|10bit|8bit|aac|ac3|dts|web-?dl|bluray|bdrip|dvdrip)\]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[?(?:dual audio|multi audio|multi-sub|sub|dub|softsub)\]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""S\d+\s*E\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d+x\d+\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Season\s*\d+\s*Episode\s*\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Episode\s*\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[\s*\]|\(\s*\)"""), "")
            .trim(' ', '-', ':', '_', '|')
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = mutableListOf<Hoster>()

        if (episode.url.contains("|")) {
            val encodedServers = episode.url.substringAfter("|")
            val serversJson = try {
                String(Base64.decode(encodedServers, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) {
                ""
            }

            if (serversJson.startsWith("[")) {
                try {
                    val rootArray = json.parseToJsonElement(serversJson).jsonArray
                    rootArray.forEachIndexed { idx, element ->
                        val obj = element.jsonObject
                        val sUrl = obj["url"]?.jsonPrimitive?.content?.trim() ?: return@forEachIndexed
                        val sLang = obj["lang"]?.jsonPrimitive?.content?.trim() ?: ""
                        val sName = obj["name"]?.jsonPrimitive?.content?.trim() ?: ""
                        val displayName = listOf(sName, sLang).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "Server ${idx + 1}" }
                        hosters.add(
                            Hoster(
                                hosterName = displayName,
                                hosterUrl = sUrl,
                            ),
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        if (hosters.isNotEmpty()) {
            return hosters
        }

        val doc = client.newCall(GET("$baseUrl${episode.url.substringBefore('#')}", headers)).execute().asJsoup()

        // Direct AS-CDN / FirePlayer iframes
        val cdnIframes = doc.select(".video.aa-tb iframe, iframe[src*='as-cdn'], iframe[data-src*='as-cdn'], iframe[src*='/video/'], iframe[data-src*='/video/']")
        cdnIframes.forEachIndexed { idx, iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.contains("multi-lang-plyr")) {
                hosters.add(
                    Hoster(
                        hosterName = if (cdnIframes.size == 1) "FirePlayer (Multi-Audio)" else "FirePlayer Server ${idx + 1}",
                        hosterUrl = src,
                    ),
                )
            }
        }

        if (hosters.isEmpty()) {
            // Fallback: check any functional iframe on page (excluding dead multi-lang-plyr)
            doc.select("iframe").forEachIndexed { idx, iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() && !src.startsWith("about:") && !src.startsWith("javascript:") && !src.contains("multi-lang-plyr")) {
                    hosters.add(
                        Hoster(
                            hosterName = "Server ${idx + 1}",
                            hosterUrl = src,
                        ),
                    )
                }
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val hosterUrl = hoster.hosterUrl
        val videos = mutableListOf<Video>()

        if (hosterUrl.contains("/video/") || hosterUrl.contains("as-cdn")) {
            // AS-CDN / FirePlayer extractor
            val videoId = Regex("""/video/([a-zA-Z0-9]+)""").find(hosterUrl)?.groupValues?.get(1)
            val host = runCatching { hosterUrl.toHttpUrl().host }.getOrNull() ?: "as-cdn26.top"
            val scheme = runCatching { hosterUrl.toHttpUrl().scheme }.getOrNull() ?: "https"
            val playerBaseUrl = "$scheme://$host"

            if (videoId != null) {
                val postUrl = "$playerBaseUrl/player/index.php?data=$videoId&do=getVideo"
                val formBody = FormBody.Builder()
                    .add("hash", videoId)
                    .add("r", "$baseUrl/")
                    .build()

                val playerHeaders = headers.newBuilder()
                    .set("Referer", hosterUrl)
                    .set("Origin", playerBaseUrl)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .build()

                val res = client.newCall(POST(postUrl, playerHeaders, formBody)).execute()
                val resBody = res.bodyString()
                val jsonObj = runCatching { json.parseToJsonElement(resBody).jsonObject }.getOrNull()
                val masterUrl = jsonObj?.get("videoSource")?.jsonPrimitive?.content ?: jsonObj?.get("securedLink")?.jsonPrimitive?.content

                if (!masterUrl.isNullOrBlank()) {
                    val hlsVideos = playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        referer = "$playerBaseUrl/",
                        videoNameGen = { quality -> quality },
                    )
                    videos.addAll(hlsVideos)
                }
            }
        } else {
            // Generic fallback
            val extracted = universalExtractor.videosFromUrl(hosterUrl, headers)
            videos.addAll(extracted)
        }

        return videos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
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
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://animesalt.me"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}

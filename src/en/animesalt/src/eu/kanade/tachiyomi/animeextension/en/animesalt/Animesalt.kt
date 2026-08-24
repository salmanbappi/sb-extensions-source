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
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidmolyextractor.VidMolyExtractor
import eu.kanade.tachiyomi.animeextension.en.animesalt.extractors.AbyssExtractor
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
    private val abyssExtractor by lazy { AbyssExtractor(client, playlistUtils) }
    private val vidmolyExtractor by lazy { VidMolyExtractor(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        if (page == 1) {
            val url = "$baseUrl/type/popular/"
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimeListPage(response, ajaxTaxonomy = "type", ajaxTerm = "popular", ajaxPostType = "tv")
        }
        return ajaxLoadMore(page, taxonomy = "type", term = "popular", postType = "tv")
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        if (page == 1) {
            val url = "$baseUrl/tv/"
            val response = client.newCall(GET(url, headers)).execute()
            return parseAnimeListPage(response, ajaxTaxonomy = "", ajaxTerm = "", ajaxPostType = "tv")
        }
        return ajaxLoadMore(page, taxonomy = "", term = "", postType = "tv")
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

    /**
     * Makes an AJAX POST to the WordPress Load More endpoint and returns the next page of results.
     */
    private suspend fun ajaxLoadMore(page: Int, taxonomy: String, term: String, postType: String): AnimesPage {
        val formBody = FormBody.Builder()
            .add("action", "animesalt_load_more")
            .add("page", page.toString())
            .add("taxonomy", taxonomy)
            .add("term", term)
            .add("post_type", postType)
            .add("search", "")
            .build()

        val ajaxHeaders = headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

        val response = client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", ajaxHeaders, formBody)).execute()
        val body = response.bodyString()
        if (body.isBlank()) {
            return AnimesPage(emptyList(), hasNextPage = false)
        }

        val doc = org.jsoup.Jsoup.parseBodyFragment(body)
        val animes = doc.select("article").mapNotNull { element ->
            val link = element.selectFirst("a.card-link, a") ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank() || href.endsWith("/#")) return@mapNotNull null

            val titleText = element.selectFirst(".card-details h3, .entry-title, h3, h2")?.text()?.trim()
                ?: link.text().trim()
            if (titleText.isBlank()) return@mapNotNull null

            val img = element.selectFirst(".poster-wrap img, figure img, img")
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

        // The AJAX endpoint doesn't tell us total pages; try next page to see if it returns data.
        // As a heuristic, if we got 10 items (the standard page size), there's likely more.
        val hasNextPage = animes.size >= 10
        return AnimesPage(animes, hasNextPage)
    }

    private fun parseAnimeListPage(
        response: Response,
        ajaxTaxonomy: String? = null,
        ajaxTerm: String? = null,
        ajaxPostType: String? = null,
    ): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.anime-card, article.post, article.type-tv, .movies-list article, article.inside-article").mapNotNull { element ->
            val link = element.selectFirst("a.card-link, .entry-title a, a.lnk-blk, a") ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { link.attr("href") }
            if (href.isBlank() || href.endsWith("/#")) return@mapNotNull null

            val titleText = element.selectFirst(".card-details h3, .entry-title, h3, h2")?.text()?.trim() ?: link.text().trim()
            if (titleText.isBlank()) return@mapNotNull null

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

        // Detect the WordPress Load More button to determine if there are more pages.
        val loadMoreBtn = doc.selectFirst("#load-more-btn")
        val hasNextPage = if (loadMoreBtn != null) {
            val currentPage = loadMoreBtn.attr("data-page").toIntOrNull() ?: 1
            val maxPages = loadMoreBtn.attr("data-max").toIntOrNull() ?: 1
            currentPage < maxPages
        } else {
            // Fallback: check for traditional pagination links
            doc.select(".pag a, .pagination a, nav a, a.next, a.page-numbers").any {
                it.text().trim().equals("NEXT", ignoreCase = true) || it.attr("class").contains("next")
            } || doc.selectFirst(".pagination .next, a.next, .nav-links .next, a:containsOwn(NEXT)") != null
        }

        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        val titleText = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: anime.title
        val synopsis = doc.selectFirst("#overview-text p, #overview-text, .overviewCss, .description, .meta-summary")?.text()?.trim()
        val genres = doc.select("a[href*='/category/genre/'], a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }.distinct().joinToString(", ")
        val statusRaw = doc.select(".status, .Qlty, a[href*='/status/'], .anime-meta-v2").text()

        // The detail page has a single <img class="hero-poster-mini"> with a TMDB or AniList CDN src.
        // Check for .hero-poster-mini first (highest confidence on the new theme),
        // then fall back to broader selectors.
        val posterImg = doc.selectFirst(
            ".hero-poster-mini, .anime-hero-minimal img, " +
                ".poster-wrap img, article.post .poster img, .single-series .poster img, .s-top .poster img, .poster img, " +
                ".bd img:not(.custom-logo):not(.cn-icon), img[alt^='Image ']:not(.TPostBg), " +
                "img[src*='tmdb.org'], img[src*='anilist.co'], img[data-src*='tmdb.org'], img[data-src*='anilist.co'], .thumb img.wp-post-image",
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
                !it.contains("Anime-Salt-Long", ignoreCase = true) &&
                !it.contains("custom-logo", ignoreCase = true) &&
                !it.contains("/wp-content/uploads/", ignoreCase = true) // never use site-uploaded assets
        } ?: doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("abs:content")?.takeIf {
            // Only use og:image if it actually looks like an anime poster, not the site logo
            !it.contains("AnimeSaltLong", ignoreCase = true) &&
                !it.contains("Anime-Salt-Long", ignoreCase = true) &&
                !it.contains("custom-logo", ignoreCase = true) &&
                !it.contains("/wp-content/uploads/", ignoreCase = true)
        }

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

        // 1. New theme: .ep-tile only (NOT .quick-ep-card — those use playQuickEp() with no server data)
        val tiles = doc.select(".ep-tile")
        if (tiles.isNotEmpty()) {
            tiles.forEachIndexed { idx, tile ->
                val onclick = tile.attr("onclick")
                val text = tile.text().trim()
                val match = Regex("""triggerEpisode\s*\(\s*(\[.*?])\s*,\s*["']([^"']*)['"]\s*,\s*["']([^"']*)['"]""").find(onclick)

                // Skip tiles that don't have a triggerEpisode call (e.g. deferred/loading tiles)
                if (match == null && tile.attr("data-slug").isBlank()) return@forEachIndexed

                val epName = match?.groupValues?.get(2)?.ifBlank { text } ?: text.ifBlank { "Episode ${idx + 1}" }
                val epSlug = match?.groupValues?.get(3) ?: tile.attr("data-slug").ifBlank { "ep-${idx + 1}" }
                val serversJson = match?.groupValues?.get(1) ?: "[]"
                val epNum = Regex("""\d+""").find(epName)?.value?.toFloatOrNull()
                    ?: Regex("""\d+""").find(epSlug)?.value?.toFloatOrNull()
                    ?: (idx + 1).toFloat()

                // Only add episode if it has server data OR a valid slug
                if (serversJson == "[]" && epSlug.isBlank()) return@forEachIndexed

                episodes.add(
                    SEpisode.create().apply {
                        name = epName.ifBlank { "Episode ${epNum.toInt()}" }
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
            .replace(Regex("""\[[A-Fa-f0-9]{8}]"""), "")
            .replace(Regex("""\[?(?:1080p|720p|480p|360p|240p|4k|hd|fhd|uhd)\]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[?(?:x264|x265|h264|h265|hevc|avc|10bit|8bit|aac|ac3|dts|web-?dl|bluray|bdrip|dvdrip)\]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[?(?:dual audio|multi audio|multi-sub|sub|dub|softsub)\]?""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""S\d+\s*E\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d+x\d+\b""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Season\s*\d+\s*Episode\s*\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""Episode\s*\d+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[\s*]|\(\s*)"""), "")
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
                    // Deduplicate by URL — videasy often sends duplicate Japanese/English entries
                    val seenUrls = mutableSetOf<String>()
                    rootArray.forEachIndexed { idx, element ->
                        val obj = element.jsonObject
                        val sUrl = obj["url"]?.jsonPrimitive?.content?.trim() ?: return@forEachIndexed
                        if (sUrl.isBlank()) return@forEachIndexed
                        // Skip videasy.net — requires encrypted JS API not reproducible without JS
                        if (sUrl.contains("videasy.net", ignoreCase = true)) return@forEachIndexed
                        if (!seenUrls.add(sUrl)) return@forEachIndexed // skip duplicate URLs
                        val sLang = obj["lang"]?.jsonPrimitive?.content?.trim() ?: ""
                        val sName = obj["name"]?.jsonPrimitive?.content?.trim() ?: ""
                        val displayName = buildString {
                            if (sName.isNotBlank()) append(sName)
                            if (sLang.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(sLang)
                            }
                            if (isEmpty()) append("Server ${idx + 1}")
                        }
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

        // Fallback: scrape the episode/series page for iframes
        val pageUrl = "$baseUrl${episode.url.substringBefore('#').trimEnd('/')}"
        val doc = client.newCall(GET(pageUrl, headers)).execute().asJsoup()

        // Direct AS-CDN / FirePlayer iframes
        val cdnIframes = doc.select(
            ".video.aa-tb iframe, iframe[src*='as-cdn'], iframe[data-src*='as-cdn'], " +
                "iframe[src*='/video/'], iframe[data-src*='/video/']",
        )
        cdnIframes.forEachIndexed { idx, iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.contains("multi-lang-plyr") && !src.contains("videasy")) {
                hosters.add(
                    Hoster(
                        hosterName = if (cdnIframes.size == 1) "FirePlayer" else "FirePlayer ${idx + 1}",
                        hosterUrl = src,
                    ),
                )
            }
        }

        if (hosters.isEmpty()) {
            // Broad iframe fallback (exclude known dead/unextractable iframes)
            doc.select("iframe").forEachIndexed { idx, iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() &&
                    !src.startsWith("about:") &&
                    !src.startsWith("javascript:") &&
                    !src.contains("multi-lang-plyr") &&
                    !src.contains("videasy.net")
                ) {
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

        // MegaPlay extraction: fetch page → extract stream ID → call getSources API
        if (hosterUrl.contains("megaplay.buzz", ignoreCase = true)) {
            return extractMegaPlay(hosterUrl, hoster.hosterName)
        }

        // AbyssPlayer extraction: encrypted media payload with HLS/MP4 sources
        if (hosterUrl.contains("abyssplayer.com", ignoreCase = true) ||
            hosterUrl.contains("abyss.to", ignoreCase = true) ||
            hosterUrl.contains("player.abyssplayer.com", ignoreCase = true)
        ) {
            return abyssExtractor.videosFromUrl(hosterUrl, headers)
        }

        // VidMoly extraction
        if (hosterUrl.contains("vidmoly", ignoreCase = true)) {
            return vidmolyExtractor.videosFromUrl(hosterUrl)
        }

        val isFirePlayer = hosterUrl.contains("as-cdn", ignoreCase = true) ||
            hosterUrl.contains("firevideoplayer.com", ignoreCase = true) ||
            Regex("""as-cdn\d+\.top""").containsMatchIn(hosterUrl)

        if (isFirePlayer && hosterUrl.contains("/video/")) {
            // AS-CDN / FirePlayer extractor — POST to /player/index.php?data=HASH&do=getVideo
            val videoId = Regex("""/video/([a-fA-F0-9]{16,})""").find(hosterUrl)?.groupValues?.get(1)
            val httpUrl = runCatching { hosterUrl.toHttpUrl() }.getOrNull()
            val host = httpUrl?.host ?: "as-cdn26.top"
            val scheme = httpUrl?.scheme ?: "https"
            val playerBaseUrl = "$scheme://$host"

            if (!videoId.isNullOrBlank()) {
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
                val masterUrl = jsonObj?.get("videoSource")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: jsonObj?.get("securedLink")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

                if (!masterUrl.isNullOrBlank()) {
                    val hlsReferer = "$playerBaseUrl/"
                    val hlsVideos = playlistUtils.extractFromHls(
                        playlistUrl = masterUrl,
                        masterHeaders = headers.newBuilder()
                            .set("Referer", hlsReferer)
                            .set("Origin", playerBaseUrl)
                            .build(),
                        videoHeaders = headers.newBuilder()
                            .set("Referer", hlsReferer)
                            .set("Origin", playerBaseUrl)
                            .build(),
                        videoNameGen = { quality -> quality.ifBlank { "HLS" } },
                    )
                    videos.addAll(hlsVideos)
                }
            }
        } else {
            // Generic fallback for any other hoster URL
            val extracted = universalExtractor.videosFromUrl(hosterUrl, headers)
            videos.addAll(extracted)
        }

        return videos.sortVideos()
    }

    /**
     * Extracts videos from megaplay.buzz URLs.
     * Flow: fetch the stream page → extract file/stream ID → call getSources API → parse HLS + subtitles.
     */
    private fun extractMegaPlay(url: String, hosterName: String): List<Video> {
        // 1. Fetch the megaplay stream page
        val pageHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        val pageRes = client.newCall(GET(url, pageHeaders)).execute()
        if (!pageRes.isSuccessful) return emptyList()
        val pageHtml = pageRes.bodyString()

        // 2. Extract the stream file ID from the page
        val streamId = Regex("""<title>File (\d+)""").find(pageHtml)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(pageHtml)?.groupValues?.get(1)
            ?: return emptyList()

        // 3. Call the getSources API
        val sourcesUrl = "https://megaplay.buzz/stream/getSources?id=$streamId&id=$streamId"
        val sourcesHeaders = headers.newBuilder()
            .set("Referer", url)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val sourcesRes = client.newCall(GET(sourcesUrl, sourcesHeaders)).execute()
        if (!sourcesRes.isSuccessful) return emptyList()
        val sourcesBody = sourcesRes.bodyString()

        val sourcesJson = runCatching { json.parseToJsonElement(sourcesBody).jsonObject }.getOrNull()
            ?: return emptyList()

        val masterUrl = sourcesJson["sources"]?.jsonObject?.get("file")?.jsonPrimitive?.content
            ?: return emptyList()

        // 4. Parse subtitle tracks
        val subtitleTracks = sourcesJson["tracks"]?.jsonArray
            ?.filter {
                it.jsonObject["kind"]?.jsonPrimitive?.content == "captions" &&
                    !it.jsonObject["file"]?.jsonPrimitive?.content.isNullOrBlank()
            }
            ?.map {
                Track(
                    it.jsonObject["file"]?.jsonPrimitive?.content ?: "",
                    it.jsonObject["label"]?.jsonPrimitive?.content ?: "",
                )
            }
            ?: emptyList()

        // 5. Extract HLS streams
        val refHeaders = headers.newBuilder()
            .set("Referer", "https://megaplay.buzz/")
            .build()

        val videos = playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            masterHeaders = refHeaders,
            videoHeaders = refHeaders,
            subtitleList = subtitleTracks,
            videoNameGen = { quality -> "$hosterName - $quality" },
        )

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

package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

class AV1Encodes : Source() {

    override val name = "AV1Encodes"
    override val lang = "en"
    override val supportsLatest = true

    override val baseUrl: String
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT) ?: PREF_DOMAIN_DEFAULT

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    override val client: OkHttpClient = network.client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", DESKTOP_UA)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
        .set("Sec-Ch-Ua-Mobile", "?0")
        .set("Sec-Ch-Ua-Platform", "\"Windows\"")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Referer", "${runCatching { baseUrl }.getOrDefault(PREF_DOMAIN_DEFAULT)}/")

    private fun getVideoHeaders(): Headers =
        Headers.Builder()
            .set("User-Agent", DESKTOP_UA)
            .set("Referer", "${runCatching { baseUrl }.getOrDefault(PREF_DOMAIN_DEFAULT)}/")
            .build()

    private val noRedirectClient: OkHttpClient by lazy {
        client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // POPULAR
    // ══════════════════════════════════════════════════════════════════════════

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET(baseUrl, headers)
    } else {
        GET("$baseUrl/anime?page=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val path = response.request.url.encodedPath
        return if (path == "/" || path.isBlank()) {
            parseHomePage(doc)
        } else {
            parseAnimeListPage(doc)
        }
    }

    private fun parseHomePage(doc: Document): AnimesPage {
        val spotlights = doc.select("article.spotlight-slide").mapNotNull { slide ->
            val a = slide.selectFirst("a[href*='/anime/']") ?: return@mapNotNull null
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val titleText = slide.selectFirst(".spotlight-title, h3, h4")?.text()?.trim()
                ?: a.text().trim()
            if (titleText.isBlank() || titleText.equals("Watch Now", ignoreCase = true)) return@mapNotNull null
            val img = slide.selectFirst("img.spotlight-backdrop, img")
            val imgUrl = extractImgUrl(img) ?: extractBg(slide)
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
                thumbnail_url = imgUrl
            }
        }

        val cards = doc.select(
            "article.anime-card, article[class*='card'], div.anime-item, .anime-item, .sidebar-list-panel li, .sidebar-list-panel article, section.content-layout article",
        ).mapNotNull { card ->
            val a = if (card.tagName() == "a") {
                card
            } else {
                card.selectFirst("h3 > a, h4 > a, .card-body a, a[href*='/anime/']")
                    ?: return@mapNotNull null
            }
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            val titleText = card.selectFirst("h3, h4")?.text()?.trim() ?: a.text().trim()
            if (titleText.isBlank() || titleText.equals("Watch Now", ignoreCase = true)) return@mapNotNull null
            val img = card.selectFirst("div.poster-wrap > img, img")
            val imgUrl = extractImgUrl(img) ?: extractBg(card)
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
                thumbnail_url = imgUrl
            }
        }

        val allAnimes = (spotlights + cards).distinctBy { it.url }
        if (allAnimes.isNotEmpty()) {
            return AnimesPage(allAnimes.fetchMissingCovers(), hasNextPage = true)
        }

        // Fallback to stats page parser if homepage cards were not found
        return AnimesPage(parseStatsPage(doc), false)
    }

    private val seasonRegex by lazy { Regex("""\[S\d""") }
    private val animeNameRegex by lazy { Regex("""\[S\d{1,2}(?:-E\d+)?]\s*([^\[]+?)\s*\[""") }
    private val specialCharactersRegex by lazy { Regex("[^a-z0-9]+") }

    private fun parseStatsPage(doc: Document): List<SAnime> {
        val seen = mutableSetOf<String>()
        val animes = mutableListOf<SAnime>()

        var searchContext: Element = doc
        val header = doc.select("h1,h2,h3,h4,h5,h6").firstOrNull {
            it.text().contains("Top Downloads", ignoreCase = true)
        }
        if (header != null) {
            val sibling = header.nextElementSibling()
            searchContext = if (sibling != null && sibling.text().length > 20) {
                sibling
            } else {
                header.parent() ?: doc
            }
        }

        searchContext.select("a[href*='/anime/'],div[class*='card'],div[class*='item'],li")
            .filter { el ->
                val text = el.text().trim()
                text.contains(seasonRegex) || text.length in 10..200
            }
            .forEach { el ->
                val link = el.selectFirst("a[href*='/anime/']")
                    ?: el.takeIf { it.tagName() == "a" && it.attr("href").contains("/anime/") }
                if (link != null) {
                    val url = link.attr("href").let {
                        if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                    }
                    if (url.startsWith("/anime/") && seen.add(url)) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain(url)
                                title = extractCleanTitle(el.text())
                                thumbnail_url = getListImageUrl(el)
                            },
                        )
                    }
                    return@forEach
                }

                val animeName = extractCleanTitle(el.text().trim())
                val slug = animeName.lowercase(Locale.US).replace(specialCharactersRegex, "-").trim('-')
                if (slug.length < 3 || !seen.add("/anime/$slug")) return@forEach
                animes.add(
                    SAnime.create().apply {
                        setUrlWithoutDomain("/anime/$slug")
                        title = animeName
                    },
                )
            }

        if (animes.isEmpty()) {
            animeNameRegex.findAll(searchContext.text())
                .map { it.groupValues[1].trim() }
                .distinct()
                .take(20)
                .forEach { animeName ->
                    val slug = animeName.lowercase(Locale.US)
                        .replace(specialCharactersRegex, "-").trim('-')
                    if (slug.length >= 3 && seen.add("/anime/$slug")) {
                        animes.add(
                            SAnime.create().apply {
                                setUrlWithoutDomain("/anime/$slug")
                                title = extractCleanTitle(animeName)
                            },
                        )
                    }
                }
        }

        return animes.fetchMissingCovers()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LATEST
    // ══════════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val animes = doc.select("article.anime-card").mapNotNull { card ->
            val a = card.selectFirst("h4 > a, .card-body a") ?: return@mapNotNull null
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = a.text().trim()
                thumbnail_url = card.selectFirst("div.poster-wrap > img, img")?.let { img: Element ->
                    img.attr("abs:data-src").ifBlank { null }
                        ?: img.attr("abs:data-lazy-src").ifBlank { null }
                        ?: img.attr("abs:src").ifBlank { null }
                }
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SEARCH
    // ══════════════════════════════════════════════════════════════════════════

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url.toString(), headers)
        }

        var sortValue = ""
        var typeValue = ""
        var genreValue = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortValue = SORT_VALUES.getOrElse(filter.state) { "" }
                is TypeFilter -> typeValue = TYPE_VALUES.getOrElse(filter.state) { "" }
                is GenreFilter -> genreValue = GENRE_VALUES.getOrElse(filter.state) { "" }
                else -> {}
            }
        }

        Log.d(TAG, "searchAnimeRequest: sort=$sortValue type=$typeValue genre=$genreValue")

        // Genre takes priority — uses path-based route
        // With this:
        val url = "$baseUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (genreValue.isNotBlank()) {
            Log.d(TAG, "searchAnimeRequest: routing to /anime?genres=$genreValue&page=$page")
            url.addQueryParameter("genres", genreValue)
        }
        if (sortValue.isNotBlank()) url.addQueryParameter("sort", sortValue)
        if (typeValue.isNotBlank()) url.addQueryParameter("type", typeValue)
        return GET(url.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.useAsJsoup()
        val url = response.request.url
        val path = url.encodedPath
        val hasQueryParams = url.querySize > 0
        Log.d(TAG, "searchAnimeParse: path=$path fullUrl=$url hasQueryParams=$hasQueryParams")
        return when {
            // Search page uses card layout
            path == "/search" -> parseCardList(doc)

            // Airing pages use card layout
            path.startsWith("/airing") -> parseCardList(doc)

            // Anime browse + sort + genres all use the SAME plain list layout
            path == "/anime" -> parseAnimeListPage(doc)

            else -> parseCardList(doc)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CARD LIST PARSER  —  /airing/*, /search, /genre/*
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseCardList(doc: Document): AnimesPage {
        // Try article cards first (same structure as latestUpdatesParse)
        var animes = doc.select("article.anime-card, article[class*='card'], article[class*='anime'], div.anime-item, .anime-item")
            .mapNotNull { card ->
                val a = if (card.tagName() == "a") {
                    card
                } else {
                    card.selectFirst("h3 > a, h4 > a, .card-body a, a.anime-link, a[href*='/anime/']")
                        ?: return@mapNotNull null
                }
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                }
                if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
                val img = card.selectFirst("div.poster-wrap > img, img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = (card.selectFirst("h3, h4")?.text() ?: a.text()).trim()
                    thumbnail_url = img?.let { it: Element ->
                        it.attr("abs:data-src").ifBlank { null }
                            ?: it.attr("abs:data-lazy-src").ifBlank { null }
                            ?: it.attr("abs:src").ifBlank { null }
                    }
                }
            }.distinctBy { it.url }

        // Fallback: h3-based selector (original logic)
        if (animes.isEmpty()) {
            val contentRoot = doc.selectFirst(
                "main, #main, #content, .content, [class*='anime-list'], [class*='anime-grid'], " +
                    "[class*='result'], [class*='listing'], [class*='airing'], section.animes",
            ) ?: doc
            animes = contentRoot.select("h3").mapNotNull { h3 ->
                val block = h3.parent() ?: return@mapNotNull null
                val a = block.selectFirst("a[href*='/anime/']")
                    ?: block.parent()?.selectFirst("a[href*='/anime/']")
                    ?: return@mapNotNull null
                val href = a.attr("href").let {
                    if (it.startsWith("http")) it.removePrefix(baseUrl) else it
                }
                if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
                val img = block.parent()?.selectFirst("img") ?: block.selectFirst("img")
                SAnime.create().apply {
                    setUrlWithoutDomain(href)
                    title = h3.text().trim()
                    thumbnail_url = img?.let { it: Element ->
                        it.attr("abs:data-src").ifBlank { null }
                            ?: it.attr("abs:data-lazy-src").ifBlank { null }
                            ?: it.attr("abs:src").ifBlank { null }
                    }
                }
            }.distinctBy { it.url }
        }

        Log.d(TAG, "parseCardList: found ${animes.size} animes")
        val hasNextPage = doc.selectFirst(
            ".pagination a[rel=next], .pagination .next:not(.disabled), " +
                "nav.pagination a:contains(Next), [aria-label=Next page]",
        ) != null
        return AnimesPage(animes, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLAIN LIST PARSER  —  /anime (A-Z browse)
    // ══════════════════════════════════════════════════════════════════════════

    private fun parseAnimeListPage(doc: Document): AnimesPage {
        val animes = doc.select("li.anime-index-item > a, li > a[href*='/anime/']").mapNotNull { a ->
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/" || href == "/anime/list") return@mapNotNull null
            val titleText = a.text().trim().ifBlank { return@mapNotNull null }
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = titleText
            }
        }.distinctBy { it.url }

        val hasNextPage = doc.selectFirst(
            "a.next-page, a[rel=next], .pagination .next, a:contains(Next)",
        ) != null

        return AnimesPage(animes.fetchMissingCovers(), hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // N+1 COVER FETCHER
    // ══════════════════════════════════════════════════════════════════════════

    private fun List<SAnime>.fetchMissingCovers(): List<SAnime> {
        return parallelMapNotNullBlocking { anime ->
            if (anime.thumbnail_url != null) return@parallelMapNotNullBlocking anime
            runCatching {
                val doc = client.newCall(animeDetailsRequest(anime)).awaitSuccess().useAsJsoup()
                val img = doc.selectFirst(
                    "img.anime-poster, img.poster, .anime-hero img, " +
                        "[class*='poster'] img, [class*='hero'] img, main img",
                )
                anime.thumbnail_url =
                    img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                        ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                anime
            }.getOrDefault(anime)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ANIME DETAIL
    // ══════════════════════════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.useAsJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(
                ".anime-hero h1, h1.anime-title, [class*='anime-hero'] h1, [class*='detail'] h1, main h1, h1",
            )?.text()?.trim() ?: ""

            val img = doc.selectFirst(
                "img.anime-poster, img.poster, .anime-hero img, [class*='poster'] img, [class*='hero'] img, main img",
            )
            thumbnail_url = img?.attr("abs:data-src")?.ifBlank { img.attr("abs:src") }
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: extractBg(
                    doc.selectFirst(
                        ".anime-poster, .poster, .anime-hero, [class*='poster'], [class*='hero']",
                    ) ?: doc,
                )

            description = doc.selectFirst(
                ".anime-synopsis, .synopsis, .description, [class*='synopsis'], [class*='description'], [class*='overview'], .desc",
            )?.text()?.trim()
            genre = doc.select(
                ".genre-tag, .tag, a[href*='/genre/'], a[href*='/tag/'], [class*='genre'] a",
            ).joinToString { it.text().trim() }.ifBlank { null }
            author = doc.selectFirst(".studio, .studio-name, [class*='studio']")?.text()?.trim()
            status = if (doc.selectFirst("[class*='airing'], .status-airing, .airing-badge") != null) {
                SAnime.ONGOING
            } else {
                SAnime.COMPLETED
            }
            initialized = true
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EPISODE LIST
    // ══════════════════════════════════════════════════════════════════════════

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = Jsoup.parse(response.bodyString())
        val urlPath = response.request.url.encodedPath
        val slug = urlPath.split("/").last { it.isNotBlank() }
        Log.d(TAG, "episodeListParse: slug=$slug quality=$prefQuality")

        val seasons = doc.select(".season-tab[data-season], .season-option[data-season], [data-season]")
            .map { it.attr("data-season") }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("1") }
        Log.d(TAG, "episodeListParse: seasons=$seasons")

        val episodeNumberRegex = Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
        val qualityList = listOf(prefQuality) + QUALITY_VALUES.filter { it != prefQuality }

        return seasons.sortedByDescending { it.toIntOrNull() ?: 0 }.parallelCatchingFlatMapBlocking { season ->
            for (quality in qualityList) {
                val encodedRes = URLEncoder.encode(quality, "UTF-8").replace("+", "%20")
                val epPageUrl = "$baseUrl/episodes/$slug/$season/$encodedRes"
                Log.d(TAG, "episodeListParse: fetching episodes page → $epPageUrl")

                val epHtml = try {
                    client.newCall(
                        GET(epPageUrl, headers.newBuilder().set("Referer", "$baseUrl/anime/$slug").build()),
                    ).awaitSuccess().bodyString()
                } catch (e: Exception) {
                    Log.w(TAG, "episodeListParse: failed for $epPageUrl: ${e.message}")
                    continue
                }

                if (epHtml.contains("Access Blocked")) {
                    Log.w(TAG, "episodeListParse: access blocked for $epPageUrl")
                    continue
                }

                if (epHtml.trim().startsWith("[")) {
                    val items = runCatching { epHtml.parseAs<List<EpisodeItem>>() }.getOrNull()
                    if (!items.isNullOrEmpty()) {
                        Log.d(TAG, "episodeListParse: JSON array found ${items.size} episodes")
                        return@parallelCatchingFlatMapBlocking items.sortedByDescending { it.num }.map { item ->
                            val fn = Uri.decode(item.href.substringBefore("?").substringAfterLast("/"))
                            val epNum = if (item.num > 0) item.num.toFloat() else parseEpisodeNumber(fn, season)
                            val finalNum = if (item.num > 0) {
                                val s = season.toIntOrNull() ?: 1
                                if (s > 1) ((s - 1) * 100 + epNum) else epNum
                            } else {
                                epNum
                            }
                            SEpisode.create().apply {
                                setUrlWithoutDomain(item.href)
                                name = if (item.label.isNotBlank()) {
                                    val audioTag = if (item.audio.isNotBlank()) " [${item.audio}]" else ""
                                    if (season != "1" && season.isNotBlank()) {
                                        "Season $season Ep ${item.num} - ${item.label}$audioTag"
                                    } else {
                                        "Ep ${item.num} - ${item.label}$audioTag"
                                    }
                                } else {
                                    buildEpisodeLabel(fn, season)
                                }
                                episode_number = finalNum
                            }
                        }
                    }
                }

                val epDoc = Jsoup.parse(epHtml)
                val downloadLinks = epDoc.select("a[href*='/download/']")
                Log.d(TAG, "episodeListParse: found ${downloadLinks.size} download links for season $season (quality $quality)")

                if (downloadLinks.isNotEmpty()) {
                    return@parallelCatchingFlatMapBlocking downloadLinks.sortedByDescending { link ->
                        episodeNumberRegex.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }.map { link ->
                        val fullHref = link.attr("href")
                        val filename = Uri.decode(fullHref.substringBefore("?").substringAfterLast("/"))
                        SEpisode.create().apply {
                            setUrlWithoutDomain(fullHref)
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename, season)
                        }
                    }
                }

                val filenames = extractFilenames(epHtml)
                if (filenames.isNotEmpty()) {
                    Log.d(TAG, "episodeListParse: regex found ${filenames.size} filenames")
                    return@parallelCatchingFlatMapBlocking filenames.sortedByDescending { parseEpisodeNumber(it, season) }.map { filename ->
                        val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                        SEpisode.create().apply {
                            setUrlWithoutDomain("/download/$slug/$season/$encodedRes/$encodedFilename")
                            name = buildEpisodeLabel(filename, season)
                            episode_number = parseEpisodeNumber(filename, season)
                        }
                    }
                }
            }
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HOSTER & VIDEO LIST
    // ══════════════════════════════════════════════════════════════════════════

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = mutableListOf<Hoster>()
        hosters.add(Hoster(hosterName = "Stream", hosterUrl = "${episode.url}#server=Stream"))
        hosters.add(Hoster(hosterName = "Dash", hosterUrl = "${episode.url}#server=Dash"))
        hosters.add(Hoster(hosterName = "Direct DL", hosterUrl = "${episode.url}#server=Direct DL"))
        if (preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)) {
            hosters.add(Hoster(hosterName = "Torrent", hosterUrl = "${episode.url}#server=Torrent"))
        }

        val prefLinkType = preferences.getString(PREF_LINK_TYPE_KEY, PREF_LINK_TYPE_DEFAULT) ?: PREF_LINK_TYPE_DEFAULT
        return hosters.sortedByDescending { it.hosterName.equals(prefLinkType, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val serverName = hoster.hosterName
        val episodeUrl = hoster.hosterUrl.substringBefore("#server=")
        val allVideos = extractAllVideos(episodeUrl)
        val filtered = allVideos.filter { it.videoTitle.contains(serverName, ignoreCase = true) }
        return if (filtered.isNotEmpty()) filtered else allVideos
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> = extractAllVideos(episode.url).sortVideos()

    private suspend fun extractAllVideos(episodeUrl: String): List<Video> {
        Log.d(TAG, "extractAllVideos: episodeUrl=$episodeUrl")

        val cleanPath = episodeUrl.substringBefore("?")
        val pathSegments = cleanPath.split("/").filter { it.isNotBlank() }
        val slug = if (pathSegments.size >= 2) pathSegments[1] else ""
        val season = if (pathSegments.size >= 3) pathSegments[2] else "1"
        val resSegment = if (pathSegments.size >= 4) pathSegments[3] else ""
        val encodedFilename = pathSegments.lastOrNull() ?: ""
        val filename = Uri.decode(encodedFilename)
        Log.d(TAG, "extractAllVideos: slug=$slug season=$season res=$resSegment filename=$filename")

        var downloadPageUrl = if (episodeUrl.startsWith("http")) episodeUrl else baseUrl + episodeUrl

        var pageHtml = try {
            client.newCall(
                GET(downloadPageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build()),
            ).awaitSuccess().bodyString()
        } catch (e: Exception) {
            Log.e(TAG, "extractAllVideos: initial download page failed — ${e.message}")
            ""
        }

        var ddlToken = extractDdlToken(pageHtml)

        // If token missing or page blocked/expired, re-fetch fresh download URL from episode list page
        if (ddlToken == null && slug.isNotBlank() && resSegment.isNotBlank()) {
            val epPageUrl = "$baseUrl/episodes/$slug/$season/$resSegment"
            Log.d(TAG, "extractAllVideos: re-fetching fresh token from $epPageUrl")
            val freshHtml = try {
                client.newCall(
                    GET(epPageUrl, headers.newBuilder().set("Referer", "$baseUrl/anime/$slug").build()),
                ).awaitSuccess().bodyString()
            } catch (e: Exception) {
                Log.e(TAG, "extractAllVideos: fresh ep page failed — ${e.message}")
                ""
            }

            if (freshHtml.isNotBlank() && !freshHtml.contains("Access Blocked")) {
                val freshLink = Jsoup.parse(freshHtml)
                    .select("a[href*='/download/']")
                    .firstOrNull { it.attr("href").contains(encodedFilename) || Uri.decode(it.attr("href")).contains(filename) }
                    ?.attr("href")

                if (!freshLink.isNullOrBlank()) {
                    downloadPageUrl = if (freshLink.startsWith("http")) freshLink else baseUrl + freshLink
                    Log.d(TAG, "extractAllVideos: fetched fresh downloadPageUrl=$downloadPageUrl")
                    pageHtml = try {
                        client.newCall(
                            GET(downloadPageUrl, headers.newBuilder().set("Referer", "$baseUrl/").build()),
                        ).awaitSuccess().bodyString()
                    } catch (e: Exception) {
                        Log.e(TAG, "extractAllVideos: fresh download page failed — ${e.message}")
                        ""
                    }
                    ddlToken = extractDdlToken(pageHtml)
                }
            }
        }

        val videos = mutableListOf<Video>()
        val resLabel = Regex("""\[(\d+p)]""").find(filename)?.groupValues?.get(1) ?: prefQuality
        val audioTag = Regex("""\[(Dual|Sub|Dub)]""", RegexOption.IGNORE_CASE)
            .find(filename)?.groupValues?.get(1) ?: ""
        val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""

        if (!ddlToken.isNullOrBlank()) {
            val ddlUrl = "$baseUrl/get_ddl/$encodedFilename"
            Log.d(TAG, "extractAllVideos: calling get_ddl → $ddlUrl with token=$ddlToken")
            val ddlRaw = try {
                client.newCall(
                    GET(
                        ddlUrl,
                        headers.newBuilder()
                            .set("Accept", "application/json")
                            .set("Referer", downloadPageUrl)
                            .set("Sec-Fetch-Dest", "empty")
                            .set("Sec-Fetch-Mode", "cors")
                            .set("Sec-Fetch-Site", "same-origin")
                            .set("X-DDL-Token", ddlToken)
                            .build(),
                    ),
                ).awaitSuccess().bodyString()
            } catch (e: Exception) {
                Log.e(TAG, "extractAllVideos: get_ddl failed — ${e.message}")
                ""
            }

            val ddl = runCatching { ddlRaw.parseAs<DdlResponse>() }.getOrNull()
            if (ddl != null && ddl.success) {
                val sizeLabel = ddl.fileSize?.let { " · $it" } ?: ""
                val qualLabel = "AV1 · $resLabel$audioSuffix$sizeLabel"
                val vHeaders = getVideoHeaders()

                val resolvedStream = resolveRedirect(ddl.streamLink)
                val resolvedWatch = resolveRedirect(ddl.watchLink)
                val resolvedDl = resolveRedirect(ddl.downloadLink ?: ddl.ddl)
                val primaryCdnUrl = resolvedStream ?: resolvedWatch ?: resolvedDl

                if (primaryCdnUrl != null) {
                    val dlUrl = toDlUrl(resolvedDl ?: primaryCdnUrl)
                    val streamUrl = toStreamUrl(resolvedStream ?: primaryCdnUrl)

                    Log.d(TAG, "extractAllVideos: Direct DL → $dlUrl")
                    videos.add(Video(videoUrl = dlUrl, videoTitle = "$qualLabel · Direct DL", headers = vHeaders))

                    Log.d(TAG, "extractAllVideos: Stream → $streamUrl")
                    videos.add(Video(videoUrl = streamUrl, videoTitle = "$qualLabel · Stream", headers = vHeaders))
                }

                if (preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)) {
                    val rawTorrent = ddl.torrentLink
                    if (!rawTorrent.isNullOrBlank()) {
                        val torrentUrl = if (rawTorrent.startsWith("magnet:")) rawTorrent else resolveRedirect(rawTorrent)
                        if (torrentUrl != null) {
                            Log.d(TAG, "extractAllVideos: torrent URL → $torrentUrl")
                            videos.add(Video(videoUrl = torrentUrl, videoTitle = "$qualLabel · Torrent", headers = vHeaders))
                        }
                    }
                }
            }
        }

        // If no videos yet, check if page has direct video player iframe or fallback links
        if (videos.isEmpty() && pageHtml.isNotBlank()) {
            val qualLabel = "AV1 · $resLabel$audioSuffix"
            val vHeaders = getVideoHeaders()

            val doc = Jsoup.parse(pageHtml)
            val iframe = doc.selectFirst("iframe#anime-video-player, iframe[src*='/r/'], iframe[src*='/watch/'], iframe[src*='/dash/'], iframe[src*='/stream/']")
            val iframeSrc = iframe?.attr("abs:src")?.ifBlank { iframe.attr("src") }
            val rawLink = if (!iframeSrc.isNullOrBlank()) {
                iframeSrc
            } else {
                Regex("""['"](/r/[a-zA-Z0-9_\-]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("""['"](https?://[^'"]+/(?:stream|watch|dl|dash)/[^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
            }

            if (!rawLink.isNullOrBlank()) {
                val resolved = resolveRedirect(rawLink) ?: rawLink
                if (resolved.contains("/stream/") || resolved.contains("/watch/") || resolved.contains("/dash/") || resolved.contains("/dl/")) {
                    val dlUrl = toDlUrl(resolved)
                    val streamUrl = toStreamUrl(resolved)
                    videos.add(Video(videoUrl = dlUrl, videoTitle = "$qualLabel · Direct DL", headers = vHeaders))
                    videos.add(Video(videoUrl = streamUrl, videoTitle = "$qualLabel · Stream", headers = vHeaders))
                }
            }
        }

        Log.d(TAG, "extractAllVideos: returning ${videos.size} videos")
        return videos
    }

    private suspend fun resolveRedirect(path: String?, depth: Int = 0): String? {
        if (path.isNullOrBlank() || depth > 3) return null
        val target = if (path.startsWith("/")) "$baseUrl$path" else path
        return try {
            noRedirectClient.newCall(
                GET(
                    target,
                    headers.newBuilder()
                        .set("Referer", "$baseUrl/")
                        .set("Sec-Fetch-Site", "same-origin")
                        .build(),
                ),
            ).await().use { resp ->
                val loc = resp.header("Location")
                when {
                    !loc.isNullOrBlank() -> {
                        val nextUrl = if (loc.startsWith("/")) "$baseUrl$loc" else loc
                        if (nextUrl.contains("/r/")) resolveRedirect(nextUrl, depth + 1) else nextUrl
                    }
                    resp.isSuccessful -> resp.request.url.toString()
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveRedirect failed for $path: ${e.message}")
            null
        }
    }

    private fun toStreamUrl(url: String): String = toDlUrl(url)

    private fun toDlUrl(url: String): String {
        return url
            .replace("/watch/", "/dl/")
            .replace("/stream/", "/dl/")
            .replace("/dash/", "/dl/")
            .substringBefore("/manifest.mpd")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXTRACTION HELPERS
    // ══════════════════════════════════════════════════════════════════════════
    private val tokenRegexes by lazy {
        listOf(
            Regex("""['"]X-DDL-Token['"]\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""['"](A{2,}[A-Za-z0-9_\-]{10,})['"]"""),
            Regex("""data-token=['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""(?:token|ddlToken)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        )
    }

    private fun extractDdlToken(html: String): String? {
        for (regex in tokenRegexes) {
            val match = regex.find(html)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) return match
        }
        return null
    }

    private fun extractImgUrl(img: Element?): String? {
        if (img == null) return null
        return img.attr("abs:data-src").ifBlank { null }
            ?: img.attr("abs:data-lazy-src").ifBlank { null }
            ?: img.attr("abs:src").ifBlank { null }
    }

    private val filenameRegex by lazy { Regex("""([a-zA-Z0-9_ \-\[\]().%]+?\.(?:mkv|mp4))""", RegexOption.IGNORE_CASE) }

    private fun extractFilenames(html: String): List<String> {
        val filenames = mutableSetOf<String>()
        val addDecoded = { fn: String ->
            val clean = Uri.decode(fn.trim())
            if (clean.isNotBlank() && !clean.contains("/")) filenames.add(clean)
        }
        Jsoup.parse(html).select("a[href*='/download/']").forEach {
            addDecoded(it.attr("href").substringAfterLast("/").substringBefore("?"))
        }
        filenameRegex
            .findAll(html).forEach { addDecoded(it.groupValues[1]) }
        return filenames.toList()
    }

    private val episodeNameRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]\s*(.+?)\s*\[""") }
    private val subdubRegex by lazy { Regex("""\[(Dual|Sub|Dub|English Dub)]""", RegexOption.IGNORE_CASE) }
    private val qualityRegex by lazy { Regex("""\[\d{3,4}p].*""") }

    private fun buildEpisodeLabel(filename: String, season: String): String {
        val epMatch = episodeNameRegex.find(filename)
        return if (epMatch != null) {
            val e = epMatch.groupValues[1]
            val titlePart = epMatch.groupValues[2].trim()
            val audioTag = subdubRegex
                .find(filename)?.groupValues?.get(1) ?: ""
            val audioSuffix = if (audioTag.isNotBlank()) " [$audioTag]" else ""
            if (season != "1" && season.isNotBlank()) "Season $season Ep $e - $titlePart$audioSuffix" else "Ep $e - $titlePart$audioSuffix"
        } else {
            val cleanName = filename.replace(qualityRegex, "")
                .substringBeforeLast(".").trim()
            if (season != "1" && season.isNotBlank()) "Season $season - $cleanName" else cleanName
        }
    }

    private val episodeSNumberRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]""") }
    private fun parseEpisodeNumber(filename: String, season: String = "1"): Float {
        val epNum = episodeSNumberRegex.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
        val s = season.toIntOrNull() ?: 1
        return if (s > 1) {
            ((s - 1) * 100 + epNum)
        } else {
            epNum
        }
    }

    private val cleanTitleRegex1 by lazy { Regex("""\s*·\s*\d+\s*downloads?.*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex2 by lazy { Regex("""^\[[a-zA-Z0-9_\-]+]\s*""") }
    private val cleanTitleRegex3 by lazy { Regex("""\s*\[\d{3,4}p].*""", RegexOption.IGNORE_CASE) }
    private val cleanTitleRegex4 by lazy { Regex("""\.(mkv|mp4)$""", RegexOption.IGNORE_CASE) }

    private fun extractCleanTitle(raw: String): String {
        var cleaned = raw.replace(cleanTitleRegex1, "")
        cleaned = cleaned.replace(cleanTitleRegex2, "")
        cleaned = cleaned.replace(cleanTitleRegex3, "")
        cleaned = cleaned.replace(cleanTitleRegex4, "")
        return cleaned.trim()
    }

    private fun getListImageUrl(anchor: Element): String? {
        val img = anchor.selectFirst("img")
        if (img != null) {
            val url = img.attr("abs:data-src").ifBlank { img.attr("abs:data-lazy-src") }
                .ifBlank { img.attr("abs:src") }
            if (url.isNotBlank()) return url
        }
        return extractBg(anchor) ?: anchor.allElements.firstNotNullOfOrNull { extractBg(it) }
    }

    private val backgroundUrlRegex by lazy { Regex("""url\(['"](.*?)['"]\)""") }

    private fun extractBg(el: Element): String? {
        val style = el.attr("style")
        if (!style.contains("background", ignoreCase = true)) return null
        val match = backgroundUrlRegex.find(style) ?: return null
        val url = match.groupValues[1].ifBlank { return null }
        return if (url.startsWith("http")) url else "$baseUrl/${url.removePrefix("/")}"
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILTERS
    // ══════════════════════════════════════════════════════════════════════════

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Genre overrides Sort and Type"),
        SortFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ══════════════════════════════════════════════════════════════════════════
    // PREFERENCES
    // ══════════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        buildPreferenceScreen(screen)
    }

    override fun List<Video>.sortVideos(): List<Video> = sortByPreferredQuality(preferences)

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "AV1Encodes"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

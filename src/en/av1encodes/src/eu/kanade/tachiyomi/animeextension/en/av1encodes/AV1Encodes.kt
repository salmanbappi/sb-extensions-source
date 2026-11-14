package eu.kanade.tachiyomi.animeextension.en.av1encodes

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.episodemetadatafetcher.EpisodeMetadataFetcher
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import extensions.utils.Source
import extensions.utils.UrlUtils
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import keiyoushi.utils.parallelMapNotNullBlocking
import keiyoushi.utils.parseAs
import keiyoushi.utils.useAsJsoup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

class AV1Encodes : Source() {

    override val name = "AV1Encodes"
    override val lang = "en"
    override val supportsLatest = true

    override val baseUrl: String
        get() {
            val domain = preferences.getString(PREF_DOMAIN_KEY, PREF_BASE_URL_DEFAULT) ?: PREF_BASE_URL_DEFAULT
            if (domain.contains("av1encodes.com") || domain.contains("av1please.com")) {
                preferences.edit().putString(PREF_DOMAIN_KEY, PREF_BASE_URL_DEFAULT).apply()
                return PREF_BASE_URL_DEFAULT
            }
            return domain
        }

    private val prefQuality: String
        get() = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 10 })
        .build()

    private val metadataFetcher by lazy { EpisodeMetadataFetcher(network.client, json) }

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
        .set("Referer", "$baseUrl/")

    private fun getVideoHeaders(): Headers = Headers.Builder()
        .set("User-Agent", DESKTOP_UA)
        .set("Referer", "$baseUrl/")
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

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = super.getAnimeDetails(anime)
        runCatching {
            val meta = metadataFetcher.fetchAnimeMetadata(details.title)
            if (meta != null) {
                val allAltTitles = meta.altTitles.toTypedArray()
                val altTitlesBlock = if (allAltTitles.isNotEmpty()) {
                    EpisodeMetadataFetcher.formatAlternativeTitles(details.title, *allAltTitles)
                } else {
                    null
                }

                val linksBlock = EpisodeMetadataFetcher.formatDatabaseLinks(
                    malId = meta.malId,
                    anilistId = meta.anilistId,
                    imdbId = meta.imdbId,
                    tmdbId = meta.tmdbId,
                    tvdbId = meta.tvdbId,
                    anidbId = meta.anidbId,
                )

                val synopsis = meta.synopsis?.takeIf { it.isNotBlank() } ?: details.description
                val bannerUrl = meta.bannerUrl

                details.description = EpisodeMetadataFetcher.buildStructuredDescription(
                    synopsis = synopsis,
                    bannerUrl = bannerUrl,
                    altTitlesBlock = altTitlesBlock,
                    detailsBlock = null,
                    linksBlock = linksBlock,
                )

                if (details.thumbnail_url.isNullOrBlank() && !bannerUrl.isNullOrBlank()) {
                    details.thumbnail_url = bannerUrl
                }
            }
        }
        details.initialized = true
        return details
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EPISODE LIST
    // ══════════════════════════════════════════════════════════════════════════

    private class ParsedEp(
        val seasonNum: Int,
        val epNum: Int,
        val href: String,
        val title: String?,
        val audio: String?,
    )

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

        val allParsed = seasons.sortedBy { it.toIntOrNull() ?: 1 }.parallelCatchingFlatMapBlocking { season ->
            val sNum = season.toIntOrNull() ?: 1
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
                        return@parallelCatchingFlatMapBlocking items.map { item ->
                            val fn = Uri.decode(item.href.substringBefore("?").substringAfterLast("/"))
                            val epNum = if (item.num > 0) item.num else parseEpisodeNumberOnly(fn).toInt()
                            val cleanTitle = if (item.label.isNotBlank()) item.label else extractTitleFromFilename(fn)
                            val audio = item.audio.ifBlank { subdubRegex.find(fn)?.groupValues?.get(1) }
                            ParsedEp(sNum, epNum, item.href, cleanTitle, audio)
                        }
                    }
                }

                val epDoc = Jsoup.parse(epHtml)
                val downloadLinks = epDoc.select("a[href*='/download/']")
                Log.d(TAG, "episodeListParse: found ${downloadLinks.size} download links for season $season (quality $quality)")

                if (downloadLinks.isNotEmpty()) {
                    return@parallelCatchingFlatMapBlocking downloadLinks.mapIndexed { idx, link ->
                        val fullHref = link.attr("href")
                        val linkText = link.text().trim()
                        val container = link.closest("tr, li, .episode, .card, [class*='ep']")
                        val containerText = container?.text()?.trim() ?: linkText

                        val epNum = extractEpisodeNumber(linkText, containerText, idx + 1)
                        val cleanTitle = extractEpisodeTitle(linkText, containerText)
                        val audio = subdubRegex.find(containerText)?.groupValues?.get(1)
                            ?: subdubRegex.find(linkText)?.groupValues?.get(1)
                            ?: subdubRegex.find(fullHref)?.groupValues?.get(1)

                        ParsedEp(sNum, epNum, fullHref, cleanTitle, audio)
                    }
                }

                val filenames = extractFilenames(epHtml)
                if (filenames.isNotEmpty()) {
                    Log.d(TAG, "episodeListParse: regex found ${filenames.size} filenames")
                    return@parallelCatchingFlatMapBlocking filenames.mapIndexed { idx, filename ->
                        val encodedFilename = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
                        val epNum = extractEpisodeNumber(filename, filename, idx + 1)
                        val cleanTitle = extractEpisodeTitle(filename, filename)
                        val audio = subdubRegex.find(filename)?.groupValues?.get(1)
                        ParsedEp(sNum, epNum, "/download/$slug/$season/$encodedRes/$encodedFilename", cleanTitle, audio)
                    }
                }
            }
            emptyList()
        }

        if (allParsed.isEmpty()) return emptyList()

        val distinctParsed = allParsed.distinctBy { it.seasonNum to it.epNum }
            .sortedWith(compareBy({ it.seasonNum }, { it.epNum }))

        val episodes = distinctParsed.map { p ->
            val num = ((p.seasonNum - 1) * 100 + p.epNum).toFloat()
            SEpisode.create().apply {
                url = "$urlPath#season=${p.seasonNum}&ep=${p.epNum}"
                name = formatEpisodeName(p.seasonNum, p.epNum, p.title, p.audio)
                episode_number = num
                if (!p.audio.isNullOrBlank()) scanlator = p.audio
            }
        }

        return episodes.reversed()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = super.getEpisodeList(anime)
        if (episodes.isEmpty()) return episodes

        return runCatching {
            val seasonRegex = Regex("""^S(\d+)\s*E(\d+)""")
            val seasons = episodes.mapNotNull { ep ->
                seasonRegex.find(ep.name)?.groupValues?.get(1)?.toIntOrNull()
            }.distinct()

            val cleanAnimeTitle = sanitizeAnimeTitle(anime.title)

            val seasonMetadataMaps = mutableMapOf<Int, Map<Int, EpisodeMetadataFetcher.EpisodeMetadata>>()

            for (s in seasons) {
                val metaMap = if (s == 1) {
                    metadataFetcher.fetch(
                        malId = "",
                        animeTitle = cleanAnimeTitle,
                        fallbackThumbnailUrl = anime.thumbnail_url,
                    ).ifEmpty {
                        metadataFetcher.fetchMedia(cleanAnimeTitle, season = 1, isTv = true)
                    }
                } else {
                    metadataFetcher.fetch(
                        malId = "",
                        animeTitle = "$cleanAnimeTitle Season $s",
                        fallbackThumbnailUrl = anime.thumbnail_url,
                    ).ifEmpty {
                        metadataFetcher.fetch(
                            malId = "",
                            animeTitle = "$cleanAnimeTitle $s",
                            fallbackThumbnailUrl = anime.thumbnail_url,
                        )
                    }.ifEmpty {
                        metadataFetcher.fetchMedia(cleanAnimeTitle, season = s, isTv = true)
                    }
                }
                if (metaMap.isNotEmpty()) {
                    seasonMetadataMaps[s] = metaMap
                }
            }

            episodes.map { episode ->
                val match = seasonRegex.find(episode.name)
                val sNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNum = match?.groupValues?.get(2)?.toIntOrNull() ?: episode.episode_number.toInt()

                val meta = seasonMetadataMaps[sNum]?.get(epNum)
                if (meta != null) {
                    if (!meta.thumbnailUrl.isNullOrBlank()) {
                        episode.preview_url = meta.thumbnailUrl
                    }
                    if (!meta.description.isNullOrBlank()) {
                        episode.summary = meta.description
                    }
                    if (!meta.title.isNullOrBlank()) {
                        val audioTag = episode.scanlator ?: subdubRegex.find(episode.name)?.groupValues?.get(1)
                        episode.name = formatEpisodeName(sNum, epNum, meta.title, audioTag)
                    }
                }
                episode
            }
        }.getOrDefault(episodes)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HOSTER & VIDEO LIST
    // ══════════════════════════════════════════════════════════════════════════

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val hosters = mutableListOf<Hoster>()
        hosters.add(Hoster(hosterName = "Stream", hosterUrl = "${episode.url}#server=Stream"))
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

    private val playbackMutex = Mutex()
    private var cachedPlayback: Triple<String, Long, List<Video>>? = null

    private suspend fun extractAllVideos(episodeUrl: String): List<Video> = playbackMutex.withLock {
        val cacheKey = "$baseUrl|$prefQuality|${preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)}|$episodeUrl"
        val cached = cachedPlayback
        if (cached != null && cached.first == cacheKey && SystemClock.elapsedRealtime() - cached.second < PLAYBACK_CACHE_MS) {
            return@withLock cached.third
        }

        val downloadPageUrl = freshDownloadUrl(episodeUrl)
        val doc = loadDownloadPage(downloadPageUrl)
        val realFilename = doc.selectFirst("#dl-file-name, .dl-file-name")?.text()?.trim()
            ?: doc.selectFirst("#file-details[data-file-name]")?.attr("data-file-name").orEmpty()
        val fileSize = doc.selectFirst("#dl-stat-size")?.text()?.trim()
        val fileQuality = doc.selectFirst("#dl-stat-quality")?.text()?.trim()
            ?: Regex("""\[(\d+p)]""").find(realFilename)?.groupValues?.get(1)
            ?: prefQuality
        val fileCodec = doc.selectFirst("#dl-stat-codec")?.text()?.trim() ?: "AV1"
        val audioTag = subdubRegex.find(realFilename)?.groupValues?.get(1)
        val qualLabel = buildString {
            append("$fileCodec · $fileQuality")
            if (!audioTag.isNullOrBlank()) append(" [$audioTag]")
            if (!fileSize.isNullOrBlank()) append(" · $fileSize")
        }
        val resolution = Regex("""(\d{3,4})p""").find(fileQuality)?.groupValues?.get(1)?.toIntOrNull()
        val vHeaders = getVideoHeaders()
        val videos = mutableListOf<Video>()

        // Older templates exposed the links through get_ddl rather than SSR.
        val ddlToken = Regex("""['"](A{4,}[A-Za-z0-9_\-]{10,})['"]""")
            .find(doc.select("script").joinToString("\n") { it.data() })?.groupValues?.get(1)
        val ddl = if (ddlToken != null) {
            try {
                val encodedFile = downloadPageUrl.toHttpUrl().encodedPath.substringAfterLast('/')
                client.newCall(
                    GET(
                        "$baseUrl/get_ddl/$encodedFile",
                        headers.newBuilder()
                            .set("Accept", "application/json")
                            .set("Referer", downloadPageUrl)
                            .set("X-Ddl-Token", ddlToken)
                            .set("Sec-Fetch-Dest", "empty")
                            .set("Sec-Fetch-Mode", "cors")
                            .build(),
                    ),
                ).awaitSuccess().bodyString().parseAs<DdlResponse>().takeIf { it.success }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "extractAllVideos: legacy DDL lookup failed: ${e.message}")
                null
            }
        } else {
            null
        }

        val downloadLinks = listOfNotNull(
            doc.selectFirst("#download-now-btn[data-href]")?.attr("data-href"),
            doc.selectFirst("a#download-link[href], a[href*='/dl/']")?.attr("href"),
            ddl?.downloadLink,
            ddl?.ddl,
        ).filter { it.isNotBlank() }.distinct()
        for (path in downloadLinks) {
            val link = resolveRedirect(path, downloadPageUrl) ?: continue
            if (link.isHtml) continue
            videos.add(
                Video(
                    videoUrl = withMediaHint(link.url, realFilename),
                    videoTitle = "$qualLabel · Direct DL",
                    headers = vHeaders,
                    resolution = resolution,
                ),
            )
        }

        val streamLinks = listOfNotNull(
            doc.selectFirst("[data-stream]")?.attr("data-stream"),
            doc.selectFirst("iframe#anime-video-player, iframe.anime-video-player")?.attr("src"),
            doc.selectFirst("video source[src], video[src], dash-video[src]")?.attr("src"),
            ddl?.streamLink,
            ddl?.watchLink,
        ).filter { it.isNotBlank() }.distinct()
        for (path in streamLinks) {
            for (url in streamUrls(path, downloadPageUrl)) {
                videos.add(
                    Video(
                        videoUrl = withMediaHint(url, realFilename),
                        videoTitle = "$qualLabel · Stream",
                        headers = vHeaders,
                        resolution = resolution,
                    ),
                )
            }
        }

        if (preferences.getBoolean(PREF_SHOW_TORRENT_KEY, PREF_SHOW_TORRENT_DEFAULT)) {
            val path = doc.selectFirst("a#torrent-btn[href], a[href^='magnet:']")?.attr("href")
                ?: ddl?.torrentLink
            val torrentUrl = if (path?.startsWith("magnet:") == true) path else resolveRedirect(path, downloadPageUrl)?.url
            if (torrentUrl != null) {
                videos.add(Video(videoUrl = torrentUrl, videoTitle = "$qualLabel · Torrent", headers = vHeaders))
            }
        }

        val result = videos.distinctBy { it.videoUrl to it.videoTitle }.sortVideos()
        if (result.isEmpty()) {
            throw IOException("The download page did not provide a playable video. Open the episode in WebView and retry.")
        }
        cachedPlayback = Triple(cacheKey, SystemClock.elapsedRealtime(), result)
        Log.d(TAG, "extractAllVideos: returning ${result.size} videos")
        result
    }

    private suspend fun freshDownloadUrl(episodeUrl: String): String {
        val episode = siteUrl(episodeUrl)
        val download = if (episode.encodedPath.startsWith("/download/")) {
            // Keep old library entries working, but never reuse their expired token.
            episode.newBuilder().fragment(null).build()
        } else {
            val params = episode.fragment.orEmpty().split('&').associate {
                it.substringBefore('=') to Uri.decode(it.substringAfter('=', ""))
            }
            val season = params["season"]?.toIntOrNull() ?: 1
            val number = params["ep"]?.toIntOrNull() ?: throw IOException("Refresh the episode list before playing.")
            val slug = episode.pathSegments.lastOrNull { it.isNotBlank() }
                ?: throw IOException("Missing anime identifier.")
            val qualities = listOf(prefQuality) + QUALITY_VALUES.filter { it != prefQuality }
            var href: String? = null
            for (quality in qualities) {
                val episodesUrl = "$baseUrl/episodes/$slug/$season/${Uri.encode(quality)}"
                val body = client.newCall(
                    GET(episodesUrl, headers.newBuilder().set("Referer", "$baseUrl/anime/$slug").build()),
                ).awaitSuccess().bodyString()
                val page = Jsoup.parse(body, episodesUrl)
                checkWebsiteVerification(page)
                href = if (body.trimStart().startsWith("[")) {
                    body.parseAs<List<EpisodeItem>>().firstOrNull { it.num == number }?.href
                } else {
                    page.select("a[href*='/download/']").firstOrNull { link ->
                        val container = link.closest(".episode-item, tr, li, .episode, .card")
                        extractEpisodeNumber(link.text(), container?.text().orEmpty(), -1) == number
                    }?.attr("href")
                }
                if (!href.isNullOrBlank()) break
            }
            siteUrl(href?.takeIf { it.isNotBlank() } ?: throw IOException("Episode $number is not available at the selected resolutions."))
        }

        val token = client.newCall(
            GET(
                "$baseUrl/get_token",
                headers.newBuilder().set("Accept", "application/json").set("Referer", download.toString()).build(),
                cache = CacheControl.FORCE_NETWORK,
            ),
        ).awaitSuccess().bodyString().parseAs<Av1TokenResponseDto>().token
            ?: throw IOException("The website did not issue a download token. Open the source in WebView and retry.")
        return download.newBuilder().setQueryParameter("token", token).build().toString()
    }

    private suspend fun loadDownloadPage(downloadPageUrl: String): Document {
        val slug = downloadPageUrl.toHttpUrl().pathSegments.getOrNull(1)
        val pageHeaders = headers.newBuilder().set("Referer", "$baseUrl/anime/$slug").build()
        var doc = client.newCall(GET(downloadPageUrl, pageHeaders, cache = CacheControl.FORCE_NETWORK)).awaitSuccess().useAsJsoup()
        var adChecks = 0
        var reloads = 0
        while (true) {
            checkWebsiteVerification(doc)
            val state = doc.selectFirst("#main-content[data-state]")?.attr("data-state").orEmpty()
            Log.d(TAG, "loadDownloadPage: state=$state adChecks=$adChecks reloads=$reloads")
            when (state) {
                "adcheck" -> {
                    if (++adChecks > MAX_ADCHECK_SUBMITS) throw IOException("The download verification failed. Allow website cookies in WebView and retry.")
                    val form = doc.selectFirst("form#ddl-adcheck-form")
                        ?: throw IOException("The download verification form is missing.")
                    val body = FormBody.Builder().apply {
                        form.select("input[name]:not([disabled])").forEach { add(it.attr("name"), it.attr("value")) }
                    }.build()
                    val action = resolveUrl(form.attr("action").ifBlank { downloadPageUrl }, doc.location())
                        ?: throw IOException("Invalid download verification URL.")
                    doc = client.newCall(
                        POST(
                            action,
                            pageHeaders.newBuilder().set("Referer", downloadPageUrl).set("Origin", baseUrl).build(),
                            body,
                        ),
                    ).awaitSuccess().useAsJsoup()
                }
                "preparing" -> {
                    if (++reloads > MAX_PREPARE_RELOADS) throw IOException("The website is still preparing this video. Please retry shortly.")
                    delay(DOWNLOAD_PREPARE_DELAY_MS)
                    doc = client.newCall(GET(downloadPageUrl, pageHeaders, cache = CacheControl.FORCE_NETWORK)).awaitSuccess().useAsJsoup()
                }
                "adblock", "error" -> throw IOException(doc.selectFirst("#error-message")?.text() ?: "The website could not prepare this video.")
                else -> return doc
            }
        }
    }

    private fun checkWebsiteVerification(doc: Document) {
        if (doc.selectFirst("script#anubis_challenge") != null) {
            throw IOException("Website verification required. Open AV1Encodes in WebView, wait for the page to load, then retry.")
        }
        if (doc.location().contains("/home/blocked") || doc.title().contains("Access Blocked", ignoreCase = true)) {
            throw IOException("The download session was rejected. Refresh the episode list and retry.")
        }
    }

    private suspend fun streamUrls(path: String, referer: String): List<String> {
        val link = resolveRedirect(path, referer) ?: return emptyList()
        if (!link.isHtml) return listOf(link.url)
        return try {
            val doc = client.newCall(GET(link.url, getVideoHeaders().newBuilder().set("Referer", referer).build()))
                .awaitSuccess().useAsJsoup()
            checkWebsiteVerification(doc)
            doc.select("dash-video[src], video source[src], video[src]")
                .mapNotNull { resolveUrl(it.attr("src"), doc.location()) }
                .distinct()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "streamUrls: player extraction failed: ${e.message}")
            emptyList()
        }
    }

    private data class ResolvedLink(val url: String, val isHtml: Boolean)

    private suspend fun resolveRedirect(path: String?, refererUrl: String = baseUrl): ResolvedLink? {
        if (path.isNullOrBlank()) return null
        var target = resolveUrl(path, refererUrl)?.toHttpUrlOrNull() ?: return null
        val visited = mutableSetOf(target)
        return try {
            repeat(MAX_REDIRECTS) {
                val next = noRedirectClient.newCall(GET(target.toString(), getVideoHeaders().newBuilder().set("Referer", refererUrl).build()))
                    .await().use { response ->
                        when {
                            response.code in listOf(301, 302, 303, 307, 308) -> response.header("Location")?.let { response.request.url.resolve(it) }
                            response.isSuccessful -> {
                                val type = response.header("Content-Type").orEmpty()
                                val peek = response.peekBody(HTML_PEEK_BYTES).string().trimStart()
                                val isHtml = type.contains("text/html", ignoreCase = true) ||
                                    peek.startsWith("<!doctype html", ignoreCase = true) || peek.startsWith("<html", ignoreCase = true)
                                return ResolvedLink(response.request.url.toString(), isHtml)
                            }
                            else -> return null
                        }
                    } ?: return null
                if (!visited.add(next)) return null
                target = next
            }
            null
        } catch (e: IOException) {
            Log.w(TAG, "resolveRedirect: ${e.message}")
            null
        }
    }

    private fun resolveUrl(path: String, base: String): String? {
        val url = base.toHttpUrlOrNull()?.resolve(path) ?: return null
        return UrlUtils.fixUrl(url.toString(), baseUrl)
    }

    private fun siteUrl(path: String): HttpUrl {
        val url = UrlUtils.fixUrl(path, baseUrl).toHttpUrl()
        // Only migrate the source origin. CDN subdomains still use av1please.com.
        return if (url.host == "av1encodes.com" || url.host == "av1please.com") {
            val origin = baseUrl.toHttpUrl()
            url.newBuilder().scheme(origin.scheme).host(origin.host).build()
        } else {
            url
        }
    }

    private fun withMediaHint(url: String, filename: String): String {
        val parsed = url.toHttpUrl()
        if (parsed.encodedPath.endsWith(".mpd") || parsed.encodedPath.endsWith(".m3u8") || parsed.fragment != null) return url
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
        return if (ext == "mkv" || ext == "mp4") parsed.newBuilder().fragment(".$ext").build().toString() else url
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXTRACTION HELPERS
    // ══════════════════════════════════════════════════════════════════════════

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
    private val episodeSNumberRegex by lazy { Regex("""\[(?:S\d+-)?E(\d+)]""") }

    private fun parseEpisodeNumberOnly(filename: String): Float = episodeSNumberRegex.find(filename)?.groupValues?.get(1)?.toFloatOrNull() ?: 1f

    private fun extractTitleFromFilename(filename: String): String? {
        val epMatch = episodeNameRegex.find(filename)
        if (epMatch != null) {
            val titlePart = epMatch.groupValues[2].trim()
            if (titlePart.isNotBlank()) return titlePart
        }
        val withoutBracket = filename.replace(Regex("""^\[[^]]+]\s*"""), "")
        val cleanName = withoutBracket.replace(qualityRegex, "")
            .replace(subdubRegex, "")
            .substringBeforeLast(".")
            .trim()
        return cleanName.takeIf { it.isNotBlank() }
    }

    private fun extractEpisodeNumber(linkText: String, containerText: String, fallbackIndex: Int): Int {
        val epRegex = Regex("""(?:Episode|Ep\.?|E)\s*(\d+)""", RegexOption.IGNORE_CASE)
        epRegex.find(linkText)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 0..3000) return it }
        epRegex.find(containerText)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 0..3000) return it }

        Regex("""\b(\d{1,4})\b""").find(linkText)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in 0..3000) return it
        }

        return fallbackIndex
    }

    private fun extractEpisodeTitle(linkText: String, containerText: String): String? {
        val epRegex = Regex("""(?:Episode|Ep\.?|E)\s*\d+[\s:\-–—·]*""", RegexOption.IGNORE_CASE)
        val textToUse = if (linkText.isNotBlank() && !linkText.equals("Download", ignoreCase = true)) linkText else containerText
        val withoutEpPrefix = epRegex.replace(textToUse, "").trim()
        val cleaned = withoutEpPrefix
            .replace(qualityRegex, "")
            .replace(subdubRegex, "")
            .replace(cleanTitleRegex1, "")
            .replace(cleanTitleRegex4, "")
            .trim()
        return cleaned.takeIf { it.isNotBlank() && !it.equals("Download", ignoreCase = true) && !it.matches(Regex("""^[0-9a-fA-F\-]{20,}$""")) }
    }

    private fun formatEpisodeName(
        seasonNum: Int,
        epNum: Int,
        title: String?,
        audio: String? = null,
    ): String {
        val s = seasonNum.coerceAtLeast(1)
        val audioSuffix = if (!audio.isNullOrBlank()) " [$audio]" else ""
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() }
        return if (cleanTitle != null &&
            !cleanTitle.equals("Episode $epNum", ignoreCase = true) &&
            !cleanTitle.equals("Ep $epNum", ignoreCase = true) &&
            !cleanTitle.equals("Ep. $epNum", ignoreCase = true) &&
            !cleanTitle.equals("E$epNum", ignoreCase = true) &&
            !cleanTitle.equals("Download", ignoreCase = true) &&
            !cleanTitle.matches(Regex("""^[0-9a-fA-F\-]{20,}$"""))
        ) {
            "S$s E$epNum : $cleanTitle$audioSuffix"
        } else {
            "S$s E$epNum$audioSuffix"
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

    private fun sanitizeAnimeTitle(raw: String): String = raw.replace(Regex("""\[[^]]*]"""), "")
        .replace(Regex("""\((?:Dual|Sub|Dub|English Dub)[^)]*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*·\s*.*"""), "")
        .replace(Regex("""\s+Season\s+\d+.*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+S\d+.*""", RegexOption.IGNORE_CASE), "")
        .trim()

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
        private const val PLAYBACK_CACHE_MS = 60_000L
        private const val DOWNLOAD_PREPARE_DELAY_MS = 5_000L
        private const val MAX_PREPARE_RELOADS = 6
        private const val MAX_ADCHECK_SUBMITS = 2
        private const val MAX_REDIRECTS = 8
        private const val HTML_PEEK_BYTES = 512L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}

package eu.kanade.tachiyomi.animeextension.en.av1encodes

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class AV1EnCodes : Source() {

    override val client = network.client.newBuilder()
        .addInterceptor(AV1EnCodesCloudflareInterceptor(network.client) { baseUrl })
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}

    override val name = "AV1 EnCodes"

    override val baseUrl = "https://av1please.com"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
        .add("Sec-Ch-Ua-Mobile", "?0")
        .add("Sec-Ch-Ua-Platform", "\"Windows\"")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/stats#top-downloads", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        return AnimesPage(parseStatsPage(response.asJsoup()), false)
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
                val slug = animeName.lowercase(java.util.Locale.US).replace(specialCharactersRegex, "-").trim('-')
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
                    val slug = animeName.lowercase(java.util.Locale.US)
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

        return animes
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

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("article.anime-card").mapNotNull { card ->
            val a = card.selectFirst("h4 > a, .card-body a") ?: return@mapNotNull null
            val href = a.attr("href").let {
                if (it.startsWith("http")) it.removePrefix(baseUrl) else it
            }
            if (!href.startsWith("/anime/") || href == "/anime/") return@mapNotNull null
            SAnime.create().apply {
                setUrlWithoutDomain(href)
                title = a.text().trim()
                thumbnail_url = card.selectFirst("div.poster-wrap > img, img")?.let { img ->
                    img.attr("abs:data-src").ifBlank { null }
                        ?: img.attr("abs:data-lazy-src").ifBlank { null }
                        ?: img.attr("abs:src").ifBlank { null }
                }
            }
        }.distinctBy { it.url }
        return AnimesPage(animes, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select(".search-results-grid .anime-item").map { element ->
            SAnime.create().apply {
                title = element.select("h3").text()
                setUrlWithoutDomain(element.select("a.anime-link").attr("href"))
                thumbnail_url = element.select("img").attr("abs:src")
            }
        }
        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            title = doc.selectFirst(".anime-title")?.text() ?: ""
            thumbnail_url = doc.selectFirst(".anime-image img")?.attr("abs:src")
            genre = doc.selectFirst(".anime-meta")?.text()
                ?.substringAfter("Genre:")
                ?.trim() ?: ""
            description = doc.selectFirst(".anime-synopsis")?.text()
                ?.removePrefix("Synopsis:")
                ?.trim() ?: ""
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val animeUrl = response.request.url.encodedPath

        return doc.select(".season-option").map { element ->
            val epNum = element.attr("data-season")
            SEpisode.create().apply {
                name = "Episode $epNum"
                url = "$animeUrl?ep=$epNum"
                episode_number = epNum.toFloatOrNull() ?: 0.0f
            }
        }.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request = GET("$baseUrl${episode.url}", headers)

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val requestUrl = response.request.url
        val ep = requestUrl.queryParameter("ep") ?: "1"

        val resolutionLinks = doc.select("div#season-$ep.episode-container a.episode-card")
        val videos = mutableListOf<Video>()

        for (link in resolutionLinks) {
            val resUrl = link.attr("abs:href")
            val resText = link.text().trim()

            try {
                val pageResponse = client.newCall(GET(resUrl, headers)).execute()
                val pageHtml = pageResponse.body.string()

                val tokenRegex = """'X-DDL-Token':\s*"([^"]+)"""".toRegex()
                val filenameRegex = """const encodedFilename\s*=\s*"([^"]+)"""".toRegex()

                val token = tokenRegex.find(pageHtml)?.groupValues?.get(1)
                val encodedFilename = filenameRegex.find(pageHtml)?.groupValues?.get(1)

                if (token != null && encodedFilename != null) {
                    val decodedFilename = URLDecoder.decode(encodedFilename, "UTF-8")
                    val ddlUrl = "$baseUrl/get_ddl/${URLEncoder.encode(decodedFilename, "UTF-8")}"
                    val ddlHeaders = headersBuilder()
                        .add("X-DDL-Token", token)
                        .add("Accept", "application/json")
                        .add("Referer", resUrl)
                        .build()

                    val ddlResponse = client.newCall(GET(ddlUrl, ddlHeaders)).execute()
                    val ddlJson = ddlResponse.parseAs<DdlResponse>()

                    if (ddlJson.success && ddlJson.download_link != null) {
                        val path = ddlJson.download_link
                        val fullPathUrl = "$baseUrl$path"

                        val redirectRequest = GET(fullPathUrl, headersBuilder().add("Referer", resUrl).build())
                        val redirectResponse = client.newCall(redirectRequest).execute()
                        val finalUrl = redirectResponse.request.url.toString()

                        videos.add(
                            Video(
                                videoUrl = finalUrl,
                                videoTitle = resText,
                                headers = headersBuilder().add("Referer", resUrl).build(),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                // skip
            }
        }

        return videos
    }

    @Serializable
    data class DdlResponse(
        val success: Boolean,
        val download_link: String? = null,
        val error: String? = null,
    )
}

class AV1EnCodesCloudflareInterceptor(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String,
) : Interceptor {
    private val cfInterceptor = CloudflareInterceptor(client)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val isBaseUrl = request.url.host == baseUrlProvider().toHttpUrlOrNull()?.host
        return if (isBaseUrl) {
            cfInterceptor.intercept(chain)
        } else {
            chain.proceed(request)
        }
    }
}

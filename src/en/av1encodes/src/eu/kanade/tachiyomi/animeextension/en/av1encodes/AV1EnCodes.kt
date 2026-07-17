package eu.kanade.tachiyomi.animeextension.en.av1encodes

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import extensions.utils.asJsoup
import extensions.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder

class AV1EnCodes : AnimeHttpSource() {

    override val name = "AV1 EnCodes"

    override val baseUrl = "https://av1please.com"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/anime?sort=latest&page=$page", headers)
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val isRoot = response.request.url.toString() == "$baseUrl/" || response.request.url.toString() == "$baseUrl"

        return if (isRoot) {
            val animes = doc.select(".spotlight-slide").map { element ->
                SAnime.create().apply {
                    title = element.select("h3.spotlight-title").text()
                    setUrlWithoutDomain(element.select("a.cta-btn.primary").attr("href"))
                    thumbnail_url = element.select("img.spotlight-backdrop").attr("abs:src")
                }
            }
            AnimesPage(animes, true)
        } else {
            parseCatalogList(doc)
        }
    }

    private fun parseCatalogList(doc: Document): AnimesPage {
        val animes = doc.select("li.anime-index-item").map { element ->
            SAnime.create().apply {
                val link = element.select("a")
                title = link.text()
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = ""
            }
        }
        val hasNext = doc.select("div.pagination a.page-link:contains(Next)").isNotEmpty() ||
            doc.select("div.pagination").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // ============================== Latest ================================
    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val isRoot = response.request.url.toString() == "$baseUrl/" || response.request.url.toString() == "$baseUrl"

        return if (isRoot) {
            val animes = doc.select("#episodeGrid .anime-card").map { element ->
                SAnime.create().apply {
                    title = element.select("h4 a").text()
                    setUrlWithoutDomain(element.select("h4 a").attr("href"))
                    thumbnail_url = element.select("img").attr("abs:src")
                }
            }
            AnimesPage(animes, true)
        } else {
            parseCatalogList(doc)
        }
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/search?q=${URLEncoder.encode(query, "UTF-8")}", headers)
    }

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

                        videos.add(Video(finalUrl, resText, finalUrl))
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

package eu.kanade.tachiyomi.animeextension.en.chikianimation

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.UrlUtils
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Chikianimation : Source() {
    override val name = "Chikianimation"
    override val baseUrl = "https://chikianimation.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder().build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return parseAnimeList(client.newCall(GET(url, headers)).execute())
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = if (page == 1) "$baseUrl/anime/?status=&type=&order=update" else "$baseUrl/anime/page/$page/?status=&type=&order=update"
        return parseAnimeList(client.newCall(GET(url, headers)).execute())
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) return getPopularAnime(page)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page == 1) "$baseUrl/?s=$encoded" else "$baseUrl/page/$page/?s=$encoded"
        return parseAnimeList(client.newCall(GET(url, headers)).execute())
    }

    override fun getFilterList() = AnimeFilterList()

    private fun parseAnimeList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.listupd article.bs, div.listupd div.bsx, article.bs").mapNotNull { card ->
            val link = card.selectFirst("div.bsx > a, a") ?: return@mapNotNull null
            val href = link.attr("href").ifBlank { return@mapNotNull null }
            if (!href.contains("/anime/")) return@mapNotNull null
            val title = card.selectFirst(".tt, h2, h3")?.text()?.trim()
                ?: link.attr("title").trim().ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = UrlUtils.fixUrl(
                    card.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } } ?: "",
                    baseUrl,
                )
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }
        val hasNext = document.selectFirst("a.next, a.next.page-numbers, .pagination a.r") != null || animes.size >= 20
        return AnimesPage(animes, hasNext)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val document = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        return anime.apply {
            title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: title
            thumbnail_url = UrlUtils.fixUrl(
                document.selectFirst("div.thumb img, .thumb img, meta[property=og:image]")?.let { it.attr("data-src").ifBlank { it.attr("src").ifBlank { it.attr("content") } } } ?: thumbnail_url.orEmpty(),
                baseUrl,
            )
            description = document.selectFirst("div.entry-content[itemprop=description], div.synp .entry-content, div.entry-content")?.text()?.trim()
            genre = document.select("div.genxed a").joinToString(", ") { it.text().trim() }.ifBlank { null }
            val statusText = document.select("div.infox div.spe span").firstOrNull { it.text().contains("Status", true) }?.text().orEmpty()
            status = when {
                statusText.contains("ongoing", true) -> SAnime.ONGOING
                statusText.contains("completed", true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()
        return document.select("div.eplister ul li, div.episodelist ul li").flatMap { item ->
            val link = item.selectFirst("a") ?: return@flatMap emptyList()
            val href = UrlUtils.fixUrl(link.attr("href"), baseUrl)
            val postPath = href.removePrefix(baseUrl).ifBlank { return@flatMap emptyList() }
            val rawNumber = item.selectFirst(".epl-num, .num-epi")?.text()?.trim().orEmpty().ifBlank { item.text() }
            val numbers = parseEpisodeNumbers(rawNumber)
            numbers.map { number ->
                val canonical = number.toString().removeSuffix(".0")
                SEpisode.create().apply {
                    url = "${anime.url}#ep=$canonical&post=$postPath"
                    name = "Episode $canonical"
                    episode_number = number
                    date_upload = parseDate(item.selectFirst(".epl-date")?.text().orEmpty())
                    scanlator = if (item.text().contains("Dub", true)) "Dub" else "Sub"
                }
            }
        }.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val postPath = episode.url.substringAfter("&post=", "").ifBlank { return emptyList() }
        val document = client.newCall(GET(UrlUtils.fixUrl(postPath, baseUrl), headers)).execute().asJsoup()
        val results = mutableListOf<Hoster>()
        val seen = mutableSetOf<String>()
        fun add(name: String, raw: String) {
            var url = UrlUtils.fixUrl(raw.trim(), baseUrl)
            if (name == "StreamTape") url = url.replace("streamtape.com/v/", "streamtape.com/e/")
            if (url.startsWith("http") && seen.add(url)) results += Hoster(hosterName = name, hosterUrl = url)
        }
        val scope = document.select(".entry-content, .post-body, .player, .video-player, .servers, .embed-container")
        val roots = if (scope.isNotEmpty()) scope else document
        roots.select("iframe[src], iframe[data-src], video source[src], a[href]").forEach { element ->
            val url = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("href") }
            val name = when {
                url.contains("streamtape", true) -> "StreamTape"
                url.contains("dailymotion", true) || url.contains("dai.ly", true) -> "Dailymotion"
                else -> "Player"
            }
            add(name, url)
        }
        roots.select("a[href]").forEach { link ->
            val href = link.attr("href")
            when {
                href.contains("streamtape", true) -> add("StreamTape", href)
                href.contains("dailymotion", true) -> add("Dailymotion", href)
            }
        }
        return results
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = runCatching {
        when {
            hoster.hosterUrl.contains("streamtape", true) -> streamTapeExtractor.videoFromUrl(hoster.hosterUrl)?.let { listOf(it) } ?: emptyList()
            hoster.hosterUrl.contains("dailymotion", true) -> dailymotionExtractor.videosFromUrl(hoster.hosterUrl, prefix = "Dailymotion - ")
            else -> listOf(Video(videoUrl = hoster.hosterUrl, videoTitle = hoster.hosterName, headers = headers))
        }
    }.getOrDefault(emptyList())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = "preferred_server",
            title = "Preferred Server",
            default = "auto",
            summary = "%s",
            entries = listOf("Auto", "StreamTape", "Dailymotion"),
            entryValues = listOf("auto", "StreamTape", "Dailymotion"),
        )
    }

    private fun parseEpisodeNumbers(value: String): List<Float> {
        val range = Regex("(?i)(?<!\\d)(\\d+(?:\\.\\d+)?)\\s*(?:to|through|[-–—])\\s*(\\d+(?:\\.\\d+)?)(?!\\d)").find(value)
        if (range != null) {
            val start = range.groupValues[1].toFloat()
            val end = range.groupValues[2].toFloat()
            if (start <= end && end - start <= 500) return generateSequence(start) { (it + 1f).takeIf { next -> next <= end } }.toList()
        }
        return Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)(?![\\d.])").find(value)?.groupValues?.get(1)?.toFloat()?.let(::listOf).orEmpty()
    }

    private fun parseDate(value: String): Long = runCatching {
        SimpleDateFormat("MMMM d, yyyy", Locale.US).parse(value)?.time ?: 0L
    }.getOrDefault(0L)
}

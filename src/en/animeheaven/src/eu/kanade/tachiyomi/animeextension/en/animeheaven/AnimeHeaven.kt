package eu.kanade.tachiyomi.animeextension.en.animeheaven

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.net.URI

class AnimeHeaven : Source() {

    override val name = "AnimeHeaven"

    override val baseUrl = "https://animeheaven.me"

    override val lang = "en"

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/popular.php?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/new.php?page=$page", headers)).execute()
        return parseAnimeListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isBlank()) {
            return getPopularAnime(page)
        }
        val response = client.newCall(GET("$baseUrl/search.php?s=${query.encodeForQuery()}&page=$page", headers)).execute()
        return parseSearchAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("div.chart.bc1").mapNotNull { element ->
            parseChartElement(element)
        }
        val hasNext = animeList.isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    private fun parseSearchAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("div.similarimg").mapNotNull { element ->
            parseSimilarElement(element)
        }
        val hasNext = animeList.isNotEmpty()
        return AnimesPage(animeList, hasNext)
    }

    private fun parseChartElement(element: Element): SAnime? {
        val linkEl = element.selectFirst("a[href^=anime.php]") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank()) return null

        val imgEl = element.selectFirst("img.coverimg")
        val titleText = element.selectFirst("div.charttitle a")?.text()
            ?: imgEl?.attr("alt")
            ?: linkEl.text()
        if (titleText.isBlank()) return null

        return SAnime.create().apply {
            title = titleText.replace("&#039;", "'").trim()
            setUrlWithoutDomain(href)
            thumbnail_url = imgEl?.absUrl("src")
            fetch_type = FetchType.Episodes
        }
    }

    private fun parseSimilarElement(element: Element): SAnime? {
        val linkEl = element.selectFirst("a[href^=anime.php]") ?: return null
        val href = linkEl.attr("href")
        if (href.isBlank()) return null

        val imgEl = element.selectFirst("img")
        val titleText = element.selectFirst("div.similarname a")?.text()
            ?: imgEl?.attr("alt")
            ?: linkEl.text()
        if (titleText.isBlank()) return null

        return SAnime.create().apply {
            title = titleText.replace("&#039;", "'").trim()
            setUrlWithoutDomain(href)
            thumbnail_url = imgEl?.absUrl("src")
            fetch_type = FetchType.Episodes
        }
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()
        val infoDiv = doc.selectFirst("div.infodiv")

        val titleText = infoDiv?.selectFirst("div.infotitle")?.text()
            ?: anime.title

        val synopsis = infoDiv?.selectFirst("div.infodes")?.text()?.replace("&#039;", "'") ?: ""
        val posterUrl = doc.selectFirst("img.posterimg")?.absUrl("src") ?: anime.thumbnail_url

        val tags = infoDiv?.select("div.infotags a, div.infotags div.boxitem")
            ?.map { it.text().trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")

        val yearInfo = infoDiv?.selectFirst("div.infoyear")?.text() ?: ""
        val scoreMatch = Regex("""Score:\s*(\d+(?:\.\d+)?(?:/10)?)""", RegexOption.IGNORE_CASE).find(yearInfo)
        val scoreVal = scoreMatch?.groupValues?.get(1)?.substringBefore("/10")?.toDoubleOrNull()

        return SAnime.create().apply {
            title = titleText.replace("&#039;", "'").trim()
            thumbnail_url = posterUrl
            genre = tags
            status = when {
                yearInfo.contains("202") || yearInfo.contains("2025") || yearInfo.contains("2026") -> SAnime.ONGOING
                yearInfo.contains("-") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            initialized = true

            description = buildString {
                if (scoreVal != null && scoreVal > 0.0) {
                    val full = (scoreVal / 2).toInt().coerceIn(0, 5)
                    append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(scoreVal)}\n\n")
                }
                if (synopsis.isNotBlank()) {
                    append(synopsis)
                }
                if (yearInfo.isNotBlank()) {
                    append("\n\n$yearInfo")
                }
            }.trim()
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val html = response.body.string()

        // Match gatea("HASH") and capture episode number from watch2 div
        val regex = Regex("""onclick='gatea\("([a-f0-9]+)"\)'[^>]*>(?:[\s\S]*?)<div[^>]*\bwatch2\b[^>]*>\s*(\d+)\s*</div>""")
        val animeId = anime.url.removePrefix("/").removePrefix("anime.php?")

        val episodes = regex.findAll(html).mapNotNull { match ->
            val gateKey = match.groupValues[1]
            val epNum = match.groupValues[2].toFloatOrNull() ?: return@mapNotNull null

            SEpisode.create().apply {
                name = "Episode ${epNum.toInt()}"
                episode_number = epNum
                url = "/gate.php?key=$gateKey&anime=$animeId"
                scanlator = "Sub"
            }
        }.toList()

        return episodes.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        return listOf(
            Hoster(
                hosterName = "AnimeHeaven",
                hosterUrl = episode.url,
            )
        )
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val epUrl = hoster.hosterUrl
        val gateKey = epUrl.substringAfter("key=").substringBefore("&")
        val animeId = epUrl.substringAfter("anime=", "")
        val animeReferer = if (animeId.isNotBlank()) "$baseUrl/anime.php?$animeId" else "$baseUrl/anime.php"

        val gateRequest = Request.Builder()
            .url("$baseUrl/gate.php")
            .headers(
                headers.newBuilder()
                    .add("Cookie", "key=$gateKey")
                    .set("Referer", animeReferer)
                    .build()
            )
            .build()

        val html = client.newCall(gateRequest).execute().body.string()

        val sourceUrls = mutableListOf<String>()

        // 1. Grab full video URLs from <source> tags
        val sourceRegex = Regex("""<source[^>]+src=['"]([^'"]+\.mp4[^'"]*)['"]""", RegexOption.IGNORE_CASE)
        sourceRegex.findAll(html).forEach { m ->
            val src = m.groupValues[1]
            if (src.isNotBlank()) sourceUrls.add(src)
        }

        // 2. Fallback: grab from download anchor
        if (sourceUrls.isEmpty()) {
            val dlMatch = Regex("""href=['"](https?://ax\.animeheaven\.me/video\.mp4\?[^'"]+)['"]""").find(html)
            if (dlMatch != null) {
                sourceUrls.add(dlMatch.groupValues[1])
            }
        }

        // 3. Fallback: reconstruct from token match
        if (sourceUrls.isEmpty()) {
            val tokenMatch = Regex("""video\.mp4\?([a-f0-9]+)&([a-f0-9]+)""").find(html)
            if (tokenMatch != null) {
                val t1 = tokenMatch.groupValues[1]
                val t2 = tokenMatch.groupValues[2]
                sourceUrls.add("https://ax.animeheaven.me/video.mp4?$t1&$t2")
            }
        }

        if (sourceUrls.isEmpty()) {
            throw Exception("Video URL not found in gate response")
        }

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val videoHeaders = Headers.headersOf(
            "Referer", "$baseUrl/",
            "Origin", baseUrl,
            "User-Agent", DEFAULT_USER_AGENT
        )

        // Deduplicate URLs while prioritizing URLs without &error
        val validUrls = sourceUrls.distinct().sortedBy { if (it.contains("&error")) 1 else 0 }

        val videos = validUrls.mapIndexedNotNull { index, url ->
            val serverHost = runCatching { URI(url).host?.substringBefore(".") }.getOrNull() ?: "server"
            val serverName = "Server ${index + 1} ($serverHost)"

            if (excludedServers.any { it.equals(serverName, ignoreCase = true) }) {
                return@mapIndexedNotNull null
            }

            Video(
                videoUrl = url,
                videoTitle = "AnimeHeaven - $serverName",
                headers = videoHeaders,
            )
        }

        return videos.sortVideos()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefServer, ignoreCase = true) }
        )
    }

    // ============================ Recommendations ========================
    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select("div.info3 div.similarimg").mapNotNull { element ->
            parseSimilarElement(element)
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
            entries = listOf("Auto", "Server 1", "Server 2"),
            entryValues = listOf("auto", "Server 1", "Server 2"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            title = "Exclude Servers",
            summary = "Select servers to hide",
            entries = listOf("Server 1", "Server 2", "Server 3"),
            entryValues = listOf("Server 1", "Server 2", "Server 3"),
            default = emptySet(),
        )
    }

    private fun String.encodeForQuery(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }

    companion object {
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "auto"
        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
    }
}

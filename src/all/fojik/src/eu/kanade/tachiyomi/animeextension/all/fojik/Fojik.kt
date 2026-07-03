package eu.kanade.tachiyomi.animeextension.all.fojik

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class Fojik : Source(), ConfigurableAnimeSource {

    override val name = "Fojik"

    override val baseUrl = "https://fojik.com"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 859473620158291034L

    override val client: OkHttpClient = super.client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun headersBuilder() = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$baseUrl/")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p")
            entryValues = arrayOf("1080p", "720p", "480p")
            summary = "%s"
            setDefaultValue("1080p")
        }.also(screen::addPreference)

        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION_KEY
            title = "Score Display Position"
            summary = "Where to show the rating (e.g. ★★★★☆ 8.20) in the description"
            entries = arrayOf("Top of description", "Bottom of description", "Disabled")
            entryValues = arrayOf("top", "bottom", "disabled")
            summary = "%s"
            setDefaultValue("top")
        }.also(screen::addPreference)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = if (page == 1) "$baseUrl/movie/" else "$baseUrl/movie/page/$page/"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = Jsoup.parse(response.body!!.string(), baseUrl)
        val animeList = document.select("article.item").map { el ->
            SAnime.create().apply {
                title = el.selectFirst("h3.title")?.text()?.trim()
                    ?: el.selectFirst("img")?.attr("alt")?.trim() ?: ""
                url = el.selectFirst("a")?.attr("href")?.replace(baseUrl, "") ?: ""
                thumbnail_url = el.selectFirst("img")?.attr("abs:src") ?: ""
            }
        }
        val hasNextPage = document.selectFirst("div.pagination a.next") != null
            || document.selectFirst("div.pagination a:contains(Next)") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotEmpty()) {
            val url = if (page == 1) "$baseUrl/?s=$query" else "$baseUrl/page/$page/?s=$query"
            return GET(url, headers)
        }
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val genre = genreFilter?.getSelectedValue() ?: ""
        if (genre.isNotEmpty()) {
            val url = if (page == 1) "$baseUrl/genre/$genre/" else "$baseUrl/genre/$genre/page/$page/"
            return GET(url, headers)
        }
        return popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = Jsoup.parse(response.body!!.string(), baseUrl)
        return SAnime.create().apply {
            title = document.selectFirst("div.data h1")?.text()?.trim() ?: ""
            thumbnail_url = document.selectFirst("div.poster img")?.attr("abs:src") ?: ""
            genre = document.select("div.sgeneros a").joinToString { it.text().trim() }

            val scoreStr = document.selectFirst("b#repimdb strong")?.text()?.trim()
            val score = scoreStr?.toDoubleOrNull()
            val position = preferences.getString(PREF_SCORE_POSITION_KEY, "top") ?: "top"
            val rawDescription = document.selectFirst("div[itemprop=description]")?.text()?.trim() ?: ""
            description = buildDescription(rawDescription, score, position)

            author = document.select("div.person[itemprop=director] div.name a").joinToString { it.text().trim() }
            artist = document.select("div.person[itemprop=actor] div.name a").joinToString { it.text().trim() }
            status = SAnime.UNKNOWN
        }
    }

    private fun formatScore(score: Double?): String? {
        if (score == null || score <= 0.0) return null
        val stars = buildString {
            val full = (score / 2).toInt().coerceIn(0, 5)
            repeat(full) { append("★") }
            repeat(5 - full) { append("☆") }
        }
        return "$stars ${"%.2f".format(score)}"
    }

    private fun buildDescription(raw: String?, score: Double?, position: String): String {
        val scoreStr = formatScore(score) ?: return raw.orEmpty()
        return when (position) {
            "top"    -> "$scoreStr\n\n${raw.orEmpty()}"
            "bottom" -> "${raw.orEmpty()}\n\n$scoreStr"
            else     -> raw.orEmpty()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.body!!.string(), baseUrl)
        val rows = document.select("div#download table tbody tr")
        return rows.mapIndexed { index, row ->
            val form = row.selectFirst("form") ?: throw Exception("Download form not found")
            val fu = form.selectFirst("input[name=FU]")?.attr("value") ?: ""
            val fn = form.selectFirst("input[name=FN]")?.attr("value") ?: ""

            val quality = row.select("td strong.quality").text().trim()
            val language = row.select("td").getOrNull(2)?.text()?.trim() ?: ""
            val size = row.select("td").getOrNull(3)?.text()?.trim() ?: ""

            val epName = buildString {
                if (quality.isNotEmpty()) append("$quality ")
                if (language.isNotEmpty()) append("($language) ")
                if (size.isNotEmpty() && size != "----") append("[$size]")
            }.trim().ifEmpty { "Download Link ${index + 1}" }

            SEpisode.create().apply {
                url = "?fu=${URLEncoder.encode(fu, "UTF-8")}&fn=${URLEncoder.encode(fn, "UTF-8")}"
                name = epName
                episode_number = (index + 1).toFloat()
                date_upload = 0L
            }
        }.reversed()
    }

    // ============================ Video Extraction ============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlParams = episode.url.substringAfter("?").split("&").associate {
            val parts = it.split("=")
            parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        val fu = urlParams["fu"] ?: return emptyList()
        val fn = urlParams["fn"] ?: ""

        val clientHeaders = Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .build()

        val body1 = FormBody.Builder()
            .add("FU", fu)
            .add("FN", fn)
            .build()

        val req1 = Request.Builder()
            .url("https://search.technews24.site/blog.php")
            .post(body1)
            .headers(clientHeaders)
            .header("Referer", baseUrl + "/")
            .build()

        val res1 = client.newCall(req1).execute()
        val html1 = res1.body!!.string()

        val action1 = extractRegex(html1, """action="([^"]+)"""") ?: return emptyList()
        val fu2 = extractRegex(html1, """name="FU2" value="([^"]+)"""") ?: return emptyList()

        val body2 = FormBody.Builder()
            .add("FU2", fu2)
            .build()

        val req2 = Request.Builder()
            .url(action1)
            .post(body2)
            .headers(clientHeaders)
            .header("Referer", "https://search.technews24.site/")
            .build()

        val res2 = client.newCall(req2).execute()
        val html2 = res2.body!!.string()

        val action2 = extractRegex(html2, """action="([^"]+)"""") ?: return emptyList()
        val fu2_2 = extractRegex(html2, """name="FU2" value="([^"]+)"""") ?: return emptyList()

        val body3 = FormBody.Builder()
            .add("FU2", fu2_2)
            .build()

        val req3 = Request.Builder()
            .url(action2)
            .post(body3)
            .headers(clientHeaders)
            .header("Referer", action1)
            .build()

        val res3 = client.newCall(req3).execute()
        val html3 = res3.body!!.string()

        val sss = extractRegex(html3, """var sss = '([^']+)';""") ?: return emptyList()
        val v = extractRegex(html3, """6a480[0-9a-f]+""") ?: return emptyList()

        val apiUrl = action2.toHttpUrlOrNull()!!.newBuilder().encodedPath("/new/l/api/m").build().toString()
        val body4 = FormBody.Builder()
            .add("s", sss)
            .add("v", v)
            .build()

        val req4 = Request.Builder()
            .url(apiUrl)
            .post(body4)
            .headers(clientHeaders)
            .header("Referer", action2)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val res4 = client.newCall(req4).execute()
        val linkUrl = res4.body!!.string().trim()
        if (linkUrl.isBlank()) return emptyList()

        val req5 = Request.Builder()
            .url(linkUrl)
            .headers(clientHeaders)
            .build()

        val res5 = client.newCall(req5).execute()
        val finalHtml = res5.body!!.string()

        val doc = Jsoup.parse(finalHtml, linkUrl)
        val videos = mutableListOf<Video>()

        doc.select("a").forEach { anchor ->
            val href = anchor.attr("abs:href")
            val text = anchor.text().trim()

            if (href.contains("gofile.io/d/")) {
                videos.add(
                    Video(
                        videoUrl = href,
                        videoTitle = "GoFile - $text",
                        headers = clientHeaders
                    )
                )
            } else if (href.contains("go2.php") || href.contains("go.php")) {
                val resolvedUrl = resolveGo2Link(href, clientHeaders)
                if (resolvedUrl != null) {
                    videos.add(
                        Video(
                            videoUrl = resolvedUrl,
                            videoTitle = "R2 Direct - $text",
                            headers = clientHeaders
                        )
                    )
                }
            }
        }
        return videos
    }

    private suspend fun resolveGo2Link(startUrl: String, clientHeaders: Headers): String? {
        return try {
            val req1 = Request.Builder().url(startUrl).headers(clientHeaders).build()
            val res1 = client.newCall(req1).execute()
            val html1 = res1.body!!.string()

            val action1 = extractRegex(html1, """action="([^"]+)"""") ?: return null
            val fu5 = extractRegex(html1, """name="FU5" value="([^"]+)"""") ?: return null

            val body2 = FormBody.Builder().add("FU5", fu5).build()
            val req2 = Request.Builder()
                .url(action1)
                .post(body2)
                .headers(clientHeaders)
                .header("Referer", startUrl)
                .build()
            val res2 = client.newCall(req2).execute()
            val html2 = res2.body!!.string()

            val action2 = extractRegex(html2, """action="([^"]+)"""") ?: return null
            val fu7 = extractRegex(html2, """name="FU7" value="([^"]+)"""") ?: return null

            val body3 = FormBody.Builder().add("FU7", fu7).build()
            val req3 = Request.Builder()
                .url(action2)
                .post(body3)
                .headers(clientHeaders)
                .header("Referer", action1)
                .build()
            val res3 = client.newCall(req3).execute()
            val html3 = res3.body!!.string()

            val sss = extractRegex(html3, """var sss = '([^']+)';""") ?: return null
            val v = extractRegex(html3, """6a480[0-9a-f]+""") ?: return null

            val apiUrl = action2.toHttpUrlOrNull()!!.newBuilder().encodedPath("/l/api/m").build().toString()
            val jsonBody = """{"s":"$sss","v":"$v"}""".toRequestBody("application/json".toMediaType())

            val req4 = Request.Builder()
                .url(apiUrl)
                .post(jsonBody)
                .headers(clientHeaders)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", action2)
                .build()

            val res4 = client.newCall(req4).execute()
            val boabdUrl = res4.body!!.string().trim()
            if (boabdUrl.isBlank()) return null

            val req5 = Request.Builder().url(boabdUrl).headers(clientHeaders).build()
            val res5 = client.newCall(req5).execute()
            val cookies = res5.headers("Set-Cookie")
            val cookieHeader = cookies.joinToString("; ") { it.substringBefore(";") }

            val body6 = FormBody.Builder().add("clouddownload", "").build()
            val req6 = Request.Builder()
                .url(boabdUrl)
                .post(body6)
                .headers(clientHeaders)
                .header("Cookie", cookieHeader)
                .header("Referer", boabdUrl)
                .build()
            val res6 = client.newCall(req6).execute()
            val finalHtml = res6.body!!.string()

            extractRegex(finalHtml, """href='([^']+r2\.cloudflarestorage\.com[^']+)'""")
        } catch (e: Exception) {
            null
        }
    }

    private fun extractRegex(html: String, pattern: String): String? {
        val match = Regex(pattern).find(html) ?: return null
        return if (match.groupValues.size > 1) match.groupValues[1] else match.groupValues[0]
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferred = preferences.getString(PREF_QUALITY_KEY, "1080p") ?: "1080p"
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(preferred, ignoreCase = true) }
                .thenByDescending { it.resolution ?: 0 }
        )
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters are ignored on text search"),
        GenreFilter(),
    )

    private class GenreFilter : AnimeFilter.Select<String>(
        "Genre",
        GENRES.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue(): String = GENRES[state].second
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_SCORE_POSITION_KEY = "pref_score_position"

        private val GENRES = listOf(
            Pair("All", ""),
            Pair("DC, Marvel or Superhero", "dc-marvel-or-other-superhero-movies-tv-series"),
            Pair("Oscar Winning Movies", "oscar-winning-movies"),
            Pair("Bollywood Hindi", "bollywood-hindi"),
            Pair("English (Hollywood)", "hollywood-english"),
            Pair("Tamil", "tamil"),
            Pair("Telugu", "telugu"),
            Pair("Malayalam", "malayalam"),
            Pair("Kannada", "kannada"),
            Pair("Korean", "korean"),
            Pair("Japanese & Chinese", "japanese-chinese"),
            Pair("Turkish", "turkish"),
            Pair("Spanish", "spanish"),
            Pair("Dual Audio", "dual-audio"),
            Pair("Hindi Dubbed", "hindi-dubbed"),
            Pair("HEVC Collection", "hevc-collection"),
            Pair("Tv & Web Series", "tv-web-series"),
            Pair("Pakistani Movies", "pakistani-movies"),
            Pair("TV Show", "tv-show"),
            Pair("Anime", "anime"),
            Pair("Animation & Cartoon", "animation"),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Biography", "biographical"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Documentary", "documentary"),
            Pair("Drama", "drama"),
            Pair("Fantasy", "fantasy"),
            Pair("Horror", "horror"),
            Pair("Mystery", "mystery"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Thriller", "thriller"),
            Pair("Sports", "sports"),
            Pair("War", "war"),
            Pair("Western", "western"),
        )
    }
}

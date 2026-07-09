package eu.kanade.tachiyomi.animeextension.all.fourkhdhub

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import keiyoushi.utils.addSwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.util.Calendar
import java.util.TimeZone

class FourKHDHub : Source() {

    override val name = "4KHDHub"
    override val baseUrl = "https://4khdhub.one"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 1358941295719324683L

    private var dynamic4khdhub = "https://4khdhub.one"
    private var dynamicHubcloud = "https://hubcloud.foo"
    private var domainsFetched = false

    private fun getRealBaseUrl(): String {
        if (!domainsFetched) {
            try {
                val response = client.newCall(
                    GET("https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json", headers),
                ).execute()
                val body = response.body.string()
                response.close()

                val match4k = Regex(""""4khdhub"\s*:\s*"([^"]+)"""").find(body)
                if (match4k != null) {
                    dynamic4khdhub = match4k.groupValues[1]
                }
                val matchHub = Regex(""""hubcloud"\s*:\s*"([^"]+)"""").find(body)
                if (matchHub != null) {
                    dynamicHubcloud = matchHub.groupValues[1]
                }
                domainsFetched = true
            } catch (e: Exception) {
                // fallback
            }
        }
        return dynamic4khdhub
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun popularAnimeRequest(page: Int): Request = if (page == 1) {
        GET(getRealBaseUrl(), headers)
    } else {
        GET("${getRealBaseUrl()}/?pagex=$page", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val realBase = getRealBaseUrl()
        val animeList = doc.select("a.movie-card").map { element ->
            SAnime.create().apply {
                val href = element.attr("href")
                url = href
                title = element.selectFirst(".movie-card-title")?.text() ?: "Unknown"

                val posterImg = element.selectFirst("img")
                val rawImg = posterImg?.attr("src") ?: posterImg?.attr("data-src") ?: ""
                thumbnail_url = if (rawImg.startsWith("http")) rawImg else "$realBase/${rawImg.trimStart('/')}"

                val formats = element.select(".movie-card-format").map { it.text() }
                genre = formats.joinToString(", ")

                status = SAnime.COMPLETED
                fetch_type = FetchType.Episodes
            }
        }

        val hasNextPage = doc.select("link[rel=next]").isNotEmpty() ||
            doc.select("a.pagination-item:contains(Next), a:contains(Next), a.next").isNotEmpty()

        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = if (query.isNotBlank()) {
        val url = getRealBaseUrl().toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("s", query)
        if (page > 1) {
            url.addQueryParameter("pagex", page.toString())
        }
        GET(url.toString(), headers)
    } else {
        popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(getRealBaseUrl() + url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val realBase = getRealBaseUrl()
        val anime = SAnime.create().apply {
            title = doc.selectFirst(".page-title")?.text() ?: doc.selectFirst("title")?.text()?.replace(" - 4KHDHub", "") ?: "Unknown"

            val synopsis = doc.selectFirst(".content-section p.mt-4")?.text() ?: ""

            val detailsImg = doc.selectFirst(".poster-container img")
            val rawDetailsImg = detailsImg?.attr("src") ?: detailsImg?.attr("data-src")
            thumbnail_url = rawDetailsImg?.let {
                if (it.startsWith("http")) it else "$realBase/${it.trimStart('/')}"
            }

            genre = doc.select(".badge.badge-outline a").joinToString { it.text() }

            val stars = doc.select(".metadata-item").firstOrNull {
                it.selectFirst(".metadata-label")?.text()?.contains("Stars", ignoreCase = true) == true
            }
            artist = stars?.selectFirst(".metadata-value")?.text()

            status = SAnime.COMPLETED

            var descriptionText = synopsis
            doc.select(".metadata-item").forEach { item ->
                val label = item.selectFirst(".metadata-label")?.text() ?: ""
                val value = item.selectFirst(".metadata-value")?.text() ?: ""
                if (label.isNotBlank() && value.isNotBlank() && !label.contains("Stars", ignoreCase = true)) {
                    descriptionText += "\n$label $value"
                }
            }
            description = descriptionText.trim()
            fetch_type = FetchType.Episodes
        }
        return anime
    }

    override fun episodeListRequest(anime: SAnime): Request = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = mutableListOf<SEpisode>()
        val pagePath = response.request.url.encodedPath
        val animeTitle = doc.selectFirst(".page-title")?.text()
            ?: doc.selectFirst("title")?.text()?.replace(" - 4KHDHub", "")
            ?: "Unknown"

        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)

        // 1. Series/Individual Episodes logic
        val seriesItems = doc.select(".episode-download-item")
        if (seriesItems.isNotEmpty()) {
            val itemsData = seriesItems.mapNotNull { element ->
                val titleEl = element.selectFirst(".episode-file-title") ?: element.selectFirst(".file-title")
                val filename = titleEl?.text()?.trim() ?: ""

                if (filename.endsWith(".zip", ignoreCase = true) || filename.isEmpty()) {
                    return@mapNotNull null
                }

                val seasonItem = element.parents().firstOrNull { it.hasClass("season-item") || it.hasClass("episode-item") }
                val seasonPrefix = seasonItem?.selectFirst(".episode-number")?.text()?.trim() ?: "S1"

                val epBadge = element.selectFirst(".badge-psa")?.text() ?: ""
                var epNum = epBadge.replace("Episode-", "", ignoreCase = true).trim().toFloatOrNull()

                if (epNum == null) {
                    val match = Regex("""(?i)[SE](\d+)""").find(filename)
                    epNum = match?.groupValues?.get(1)?.toFloatOrNull()
                }

                if (epNum == null) return@mapNotNull null

                val cleanName = if (epBadge.isNotBlank()) epBadge else "Episode ${epNum.toInt()}"

                Triple(seasonPrefix, epNum, cleanName)
            }

            // Fetch TMDB episode details
            val tmdbId = fetchTmdbId(animeTitle, isMovie = false)
            val tmdbEpisodes = mutableMapOf<Pair<Int, Int>, TmdbEpisode>()

            if (tmdbId != null) {
                val seasonsToFetch = itemsData.map { it.first }.distinct().mapNotNull {
                    Regex("""\d+""").find(it)?.value?.toIntOrNull()
                }
                seasonsToFetch.forEach { season ->
                    try {
                        val url = "https://api.themoviedb.org/3/tv/$tmdbId/season/$season?api_key=1865f43a0549ca50d341dd9ab8b29f49"
                        val resp = client.newCall(GET(url, headers)).execute()
                        val text = resp.body.string()
                        resp.close()

                        val root = org.json.JSONObject(text)
                        val episodesArr = root.optJSONArray("episodes")
                        if (episodesArr != null) {
                            for (i in 0 until episodesArr.length()) {
                                val ep = episodesArr.optJSONObject(i) ?: continue
                                val epNum = ep.optInt("episode_number")
                                val name = ep.optString("name").takeIf { it.isNotBlank() }
                                val overview = ep.optString("overview").takeIf { it.isNotBlank() }
                                val stillPath = ep.optString("still_path").takeIf { it.isNotBlank() }
                                val airDateStr = ep.optString("air_date").takeIf { it.isNotBlank() }
                                val airDateMs = parseAirDate(airDateStr)

                                tmdbEpisodes[Pair(season, epNum)] = TmdbEpisode(name, overview, stillPath, airDateMs)
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }

            val grouped = itemsData.groupBy { Pair(it.first, it.second) }

            grouped.forEach { (key, list) ->
                val seasonPrefix = key.first
                val epNum = key.second
                val firstItem = list.first()

                val seasonNumber = Regex("""\d+""").find(seasonPrefix)?.value?.toIntOrNull() ?: 1
                val tmdbEp = tmdbEpisodes[Pair(seasonNumber, epNum.toInt())]

                episodes.add(
                    SEpisode.create().apply {
                        name = tmdbEp?.name?.let { "$seasonPrefix - Episode ${epNum.toInt()}: $it" }
                            ?: "$seasonPrefix - ${firstItem.third}"
                        episode_number = epNum
                        url = "$pagePath?season=${URLDecoder.decode(seasonPrefix, "UTF-8")}&episode=$epNum"
                        summary = tmdbEp?.overview
                        preview_url = if (showThumbnails && !tmdbEp?.stillPath.isNullOrEmpty()) {
                            "https://image.tmdb.org/t/p/original${tmdbEp!!.stillPath}"
                        } else {
                            null
                        }
                        date_upload = tmdbEp?.airDate ?: 0L
                    },
                )
            }
        } else {
            // 2. Movie/Download item logic
            val movieItems = doc.select(".download-item")
            if (movieItems.isNotEmpty()) {
                val validMovieItems = movieItems.filter { element ->
                    val titleEl = element.selectFirst(".file-title") ?: element.selectFirst(".download-header")
                    val filename = titleEl?.text()?.trim() ?: ""
                    !filename.endsWith(".zip", ignoreCase = true)
                }

                if (validMovieItems.isNotEmpty()) {
                    var movieOverview: String? = null
                    var moviePoster: String? = null
                    var movieReleaseDate: Long = 0L

                    val tmdbId = fetchTmdbId(animeTitle, isMovie = true)
                    if (tmdbId != null) {
                        try {
                            val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=1865f43a0549ca50d341dd9ab8b29f49"
                            val resp = client.newCall(GET(url, headers)).execute()
                            val text = resp.body.string()
                            resp.close()

                            val root = org.json.JSONObject(text)
                            movieOverview = root.optString("overview").takeIf { it.isNotBlank() }
                            val backdropPath = root.optString("backdrop_path").takeIf { it.isNotBlank() }
                                ?: root.optString("poster_path").takeIf { it.isNotBlank() }
                            moviePoster = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
                            val releaseDateStr = root.optString("release_date").takeIf { it.isNotBlank() }
                            movieReleaseDate = parseAirDate(releaseDateStr)
                        } catch (e: Exception) {
                            // ignore
                        }
                    }

                    episodes.add(
                        SEpisode.create().apply {
                            name = "Movie"
                            episode_number = 1f
                            url = "$pagePath?movie=true"
                            summary = movieOverview
                            preview_url = if (showThumbnails) moviePoster else null
                            date_upload = movieReleaseDate
                        },
                    )
                }
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    private data class TmdbEpisode(
        val name: String?,
        val overview: String?,
        val stillPath: String?,
        val airDate: Long,
    )

    private fun cleanTitleForTmdb(title: String): String = title
        .replace(Regex("""\(\d{4}\)"""), "")
        .replace(Regex("""(?i)\b(season|series|s)\b\s*\d+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun fetchTmdbId(title: String, isMovie: Boolean): Int? {
        try {
            val query = cleanTitleForTmdb(title)
            val url = "https://api.themoviedb.org/3/search/multi?api_key=1865f43a0549ca50d341dd9ab8b29f49&query=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val response = client.newCall(GET(url, headers)).execute()
            val text = response.body.string()
            response.close()

            val root = org.json.JSONObject(text)
            val results = root.optJSONArray("results") ?: return null
            val targetType = if (isMovie) "movie" else "tv"

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                if (item.optString("media_type") == targetType) {
                    return item.optInt("id")
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    private fun parseAirDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val list = mutableListOf<Video>()
        val urlParam = episode.url
        val pagePath = urlParam.substringBefore("?")
        val query = urlParam.substringAfter("?", "")

        if (query.isEmpty()) return emptyList()

        try {
            val response = client.newCall(GET(getRealBaseUrl() + pagePath, headers)).execute()
            val html = response.body.string()
            response.close()

            val doc = Jsoup.parse(html)

            if (query.contains("movie=true")) {
                doc.select(".download-item").forEach { element ->
                    val titleEl = element.selectFirst(".file-title") ?: element.selectFirst(".download-header")
                    val filename = titleEl?.text()?.trim() ?: ""

                    if (filename.endsWith(".zip", ignoreCase = true)) {
                        return@forEach
                    }

                    val sizeText = element.selectFirst(".badge-size")?.text()?.trim()
                        ?: element.select(".badge")
                            .firstOrNull {
                                it.text().contains("GB", ignoreCase = true) ||
                                    it.text().contains("MB", ignoreCase = true)
                            }?.text()?.trim()
                        ?: ""

                    val suffix = parseLabelSuffix(filename, sizeText)

                    val links = mutableListOf<String>()
                    element.select("a").forEach {
                        val href = it.attr("href")
                        if (href.isNotEmpty() && !href.startsWith("javascript") && !href.startsWith("#")) {
                            links.add(href)
                        }
                    }

                    if (links.isEmpty()) {
                        val fileId = element.selectFirst(".download-header")?.attr("data-file-id")
                        if (fileId != null) {
                            doc.select("#content-$fileId a").forEach {
                                val href = it.attr("href")
                                if (href.isNotEmpty() && !href.startsWith("javascript") && !href.startsWith("#")) {
                                    links.add(href)
                                }
                            }
                        }
                    }

                    links.distinct().forEach { link ->
                        runBlocking {
                            try {
                                list.addAll(resolveVideoUrl(link, suffix))
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                }
            } else if (query.contains("season=") && query.contains("episode=")) {
                val params = query.split("&").associate {
                    val parts = it.split("=")
                    parts[0] to URLDecoder.decode(parts[1], "UTF-8")
                }
                val targetSeason = params["season"] ?: ""
                val targetEpisode = params["episode"]?.toFloatOrNull() ?: -1f

                if (targetSeason.isNotEmpty() && targetEpisode != -1f) {
                    doc.select(".episode-download-item").forEach { element ->
                        val titleEl = element.selectFirst(".episode-file-title") ?: element.selectFirst(".file-title")
                        val filename = titleEl?.text()?.trim() ?: ""

                        if (filename.endsWith(".zip", ignoreCase = true) || filename.isEmpty()) {
                            return@forEach
                        }

                        val seasonItem = element.parents().firstOrNull { it.hasClass("season-item") || it.hasClass("episode-item") }
                        val seasonPrefix = seasonItem?.selectFirst(".episode-number")?.text()?.trim() ?: "S1"

                        if (seasonPrefix.equals(targetSeason, ignoreCase = true)) {
                            val epBadge = element.selectFirst(".badge-psa")?.text() ?: ""
                            var epNum = epBadge.replace("Episode-", "", ignoreCase = true).trim().toFloatOrNull()

                            if (epNum == null) {
                                val match = Regex("""(?i)[SE](\d+)""").find(filename)
                                epNum = match?.groupValues?.get(1)?.toFloatOrNull()
                            }

                            if (epNum == targetEpisode) {
                                val sizeText = element.selectFirst(".badge-size")?.text()?.trim()
                                    ?: element.select(".badge")
                                        .firstOrNull {
                                            it.text().contains("GB", ignoreCase = true) ||
                                                it.text().contains("MB", ignoreCase = true)
                                        }?.text()?.trim()
                                    ?: ""

                                val suffix = parseLabelSuffix(filename, sizeText)

                                val links = mutableListOf<String>()
                                element.select("a").forEach {
                                    val href = it.attr("href")
                                    if (href.isNotEmpty() && !href.startsWith("javascript") && !href.startsWith("#")) {
                                        links.add(href)
                                    }
                                }

                                links.distinct().forEach { link ->
                                    runBlocking {
                                        try {
                                            list.addAll(resolveVideoUrl(link, suffix))
                                        } catch (e: Exception) {
                                            // ignore
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return list.sortVideos()
    }

    private fun parseLabelSuffix(filename: String, sizeText: String): String {
        val quality = when {
            filename.contains("2160p", ignoreCase = true) || filename.contains("4K", ignoreCase = true) -> "4K"
            filename.contains("1080p", ignoreCase = true) -> "1080p"
            filename.contains("720p", ignoreCase = true) -> "720p"
            else -> ""
        }

        val format = when {
            filename.contains("HEVC", ignoreCase = true) || filename.contains("x265", ignoreCase = true) -> "H.265"
            filename.contains("AV1", ignoreCase = true) -> "AV1"
            else -> "H.264"
        }

        return buildString {
            if (quality.isNotEmpty()) {
                append(" [$quality - $format]")
            }
            if (sizeText.isNotEmpty()) {
                append(" ($sizeText)")
            }
        }
    }

    private val REDIRECT_REGEX = Regex("""s\('o','([A-Za-z0-9+/=]+)'|ck\('_wp_http_\d+','([^']+)'""")

    private suspend fun getRedirectLinks(url: String): String = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(GET(url, headers)).execute()
            val html = response.body.string()
            response.close()

            val combined = StringBuilder(128)
            for (m in REDIRECT_REGEX.findAll(html)) {
                val g1 = m.groups[1]?.value
                val g2 = m.groups[2]?.value
                if (g1 != null) {
                    combined.append(g1)
                } else if (g2 != null) {
                    combined.append(g2)
                }
            }

            if (combined.isEmpty()) return@withContext ""

            val step1 = decodeBase64(combined.toString())
            val step2 = decodeBase64(step1)
            val step3 = pen(step2)
            val decoded = decodeBase64(step3)

            val json = org.json.JSONObject(decoded)
            val encodedUrl = decodeBase64(json.optString("o"))
            if (encodedUrl.isNotBlank()) return@withContext encodedUrl.trim()

            val data = decodeBase64(json.optString("data"))
            val wp = json.optString("blog_url")
            if (wp.isBlank() || data.isBlank()) return@withContext ""

            val followResp = client.newCall(GET("$wp?re=$data", headers)).execute()
            val followText = followResp.body.string()
            followResp.close()

            val textDoc = Jsoup.parse(followText)
            textDoc.text().trim()
        } catch (e: Exception) {
            url
        }
    }

    private fun decodeBase64(value: String): String = try {
        val decodedBytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        String(decodedBytes)
    } catch (e: Exception) {
        ""
    }

    private fun pen(value: String): String {
        val out = StringBuilder(value.length)
        for (c in value) {
            out.append(
                when (c) {
                    in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                    in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                    else -> c
                },
            )
        }
        return out.toString()
    }

    private suspend fun resolveVideoUrl(url: String, suffix: String): List<Video> {
        val lower = url.lowercase()
        return when {
            lower.contains("id=") -> {
                val redirected = getRedirectLinks(url)
                if (redirected.isNotEmpty() && redirected != url) {
                    resolveVideoUrl(redirected, suffix)
                } else {
                    emptyList()
                }
            }

            lower.contains("hubcloud") -> resolveHubCloud(url, suffix)

            lower.contains("hubdrive") -> resolveHubDrive(url, suffix)

            lower.contains("hubcdn") -> resolveHubCdn(url, suffix)

            lower.contains("hblinks") -> resolveHblinks(url, suffix)

            lower.contains("pixeldrain") || lower.contains("pixelserver") -> resolvePixelDrain(url, suffix)

            lower.contains("hdstream4u") || lower.contains("hubstream") -> {
                try {
                    VidHideExtractor(client, headers).videosFromUrl(url) { quality ->
                        "VidHide - $quality$suffix"
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }

            else -> {
                if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".m3u8")) {
                    listOf(Video(videoUrl = url, videoTitle = "Direct Link$suffix", headers = headers))
                } else {
                    emptyList()
                }
            }
        }
    }

    private suspend fun resolveHubCloud(hubCloudUrl: String, suffix: String): List<Video> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Video>()
        try {
            val uri = java.net.URI(hubCloudUrl)
            val realUrl = uri.toString()
            val hostBase = "${uri.scheme}://${uri.host}"

            val href = if (realUrl.contains("hubcloud.php")) {
                realUrl
            } else {
                val resp = client.newCall(GET(realUrl, headers)).execute()
                val doc = resp.asJsoup()
                resp.close()
                val raw = doc.selectFirst("#download")?.attr("href") ?: ""
                if (raw.startsWith("http", true)) {
                    raw
                } else {
                    hostBase.trimEnd('/') + "/" + raw.trimStart('/')
                }
            }

            if (href.isBlank()) return@withContext emptyList()

            val resp2 = client.newCall(GET(href, headers)).execute()
            val doc2 = resp2.asJsoup()
            resp2.close()

            val size = doc2.selectFirst("i#size")?.text() ?: ""
            val header = doc2.selectFirst("div.card-header")?.text() ?: ""
            val quality = getIndexQuality(header)

            val labelExtras = buildString {
                if (quality.isNotEmpty()) append(" [$quality]")
                if (size.isNotEmpty()) append(" [$size]")
                if (suffix.isNotEmpty()) append(suffix)
            }

            doc2.select("a.btn, a[class*=btn]").forEach { element ->
                val link = element.attr("href")
                val text = element.ownText()
                val label = text.lowercase()

                when {
                    label.contains("fsl server") || label.contains("fslv2") -> {
                        list.add(Video(videoUrl = link, videoTitle = "HubCloud (FSL)$labelExtras", headers = headers))
                    }

                    label.contains("download file") -> {
                        list.add(Video(videoUrl = link, videoTitle = "HubCloud (Download)$labelExtras", headers = headers))
                    }

                    label.contains("buzzserver") -> {
                        try {
                            val noRedirectClient = client.newBuilder()
                                .followRedirects(false)
                                .followSslRedirects(false)
                                .build()
                            val buzzReq = Request.Builder().url("$link/download").header("Referer", link).build()
                            val buzzResp = noRedirectClient.newCall(buzzReq).execute()
                            val dlink = buzzResp.header("hx-redirect") ?: buzzResp.header("HX-Redirect") ?: ""
                            buzzResp.close()
                            if (dlink.isNotBlank()) {
                                list.add(Video(videoUrl = dlink, videoTitle = "HubCloud (BuzzServer)$labelExtras", headers = headers))
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }

                    label.contains("pixeldra") || label.contains("pixelserver") || label.contains("pixel server") || label.contains("pixeldrain") -> {
                        val base = getBaseUrl(link)
                        val finalUrl = if (link.contains("download")) link else "$base/api/file/${link.substringAfterLast("/")}?download"
                        list.add(Video(videoUrl = finalUrl, videoTitle = "HubCloud (Pixeldrain)$labelExtras", headers = headers))
                    }

                    label.contains("s3 server") -> {
                        list.add(Video(videoUrl = link, videoTitle = "HubCloud (S3 Server)$labelExtras", headers = headers))
                    }

                    label.contains("mega server") -> {
                        list.add(Video(videoUrl = link, videoTitle = "HubCloud (Mega Server)$labelExtras", headers = headers))
                    }

                    label.contains("pdl server") -> {
                        list.add(Video(videoUrl = link, videoTitle = "HubCloud (PDL Server)$labelExtras", headers = headers))
                    }

                    label.contains("10gbps") || label.contains("10 gbps") || label.contains("10gb") -> {
                        try {
                            val gpdlResp = client.newCall(GET(link, headers)).execute()
                            val finalUrl = gpdlResp.request.url.toString()
                            gpdlResp.close()

                            if (finalUrl.contains("gamerxyt.com/dl.php?link=")) {
                                val directLink = finalUrl.substringAfter("dl.php?link=")
                                if (directLink.isNotEmpty()) {
                                    list.add(Video(videoUrl = directLink, videoTitle = "HubCloud (10Gbps)$labelExtras", headers = headers))
                                }
                            } else if (finalUrl.contains("video-downloads.googleusercontent.com")) {
                                list.add(Video(videoUrl = finalUrl, videoTitle = "HubCloud (10Gbps)$labelExtras", headers = headers))
                            }
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        list
    }

    private suspend fun resolveHubDrive(hubDriveUrl: String, suffix: String): List<Video> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Video>()
        try {
            val resp = client.newCall(GET(hubDriveUrl, headers)).execute()
            val html = resp.body.string()
            resp.close()

            val doc = Jsoup.parse(html)
            val href = doc.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
            if (href.isNotEmpty()) {
                if (href.contains("hubcloud", ignoreCase = true)) {
                    return@withContext resolveHubCloud(href, suffix)
                } else {
                    return@withContext resolveVideoUrl(href, suffix)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        list
    }

    private suspend fun resolveHubCdn(url: String, suffix: String): List<Video> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Video>()
        try {
            val response = client.newCall(GET(url, headers)).execute()
            val html = response.body.string()
            response.close()

            val doc = Jsoup.parse(html)
            val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data() ?: ""
            var encodedUrl = Regex("""reurl\s*=\s*"([^"]+)"""")
                .find(scriptText)
                ?.groupValues?.getOrNull(1)
                ?.substringAfter("?r=")

            if (encodedUrl.isNullOrEmpty()) {
                encodedUrl = Regex("""r=([A-Za-z0-9+/=]+)""").find(html)?.groupValues?.getOrNull(1)
            }

            val decodedUrl = encodedUrl?.let {
                val decodedBytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                val decodedStr = String(decodedBytes)
                if (decodedStr.contains("link=")) decodedStr.substringAfterLast("link=") else decodedStr
            }

            if (!decodedUrl.isNullOrEmpty()) {
                list.add(Video(videoUrl = decodedUrl, videoTitle = "HubCDN$suffix", headers = headers))
            }
        } catch (e: Exception) {
            // ignore
        }
        list
    }

    private suspend fun resolveHblinks(url: String, suffix: String): List<Video> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Video>()
        try {
            val response = client.newCall(GET(url, headers)).execute()
            val doc = response.asJsoup()
            response.close()

            val elements = doc.select("h3 a, h5 a, div.entry-content p a")
            elements.forEach { el ->
                val href = el.attr("abs:href").ifBlank { el.attr("href") }.trim()
                if (href.isNotEmpty()) {
                    list.addAll(resolveVideoUrl(href, suffix))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        list
    }

    private fun resolvePixelDrain(url: String, suffix: String): List<Video> {
        val fileId = url.substringAfterLast("/")
        val finalUrl = "https://pixeldrain.com/api/file/$fileId?download"
        return listOf(Video(videoUrl = finalUrl, videoTitle = "PixelDrain$suffix", headers = headers))
    }

    private fun getIndexQuality(str: String): String = Regex("""(\d{3,4})[pP]""")
        .find(str)
        ?.groupValues
        ?.getOrNull(1) ?: ""

    private fun getBaseUrl(url: String): String = try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (e: Exception) {
        ""
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val preferredQuality = preferences.getString(PREF_PREFERRED_QUALITY, DEFAULT_PREFERRED_QUALITY)!!
        val preferredServer = preferences.getString(PREF_PREFERRED_SERVER, DEFAULT_PREFERRED_SERVER)!!

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(preferredQuality, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(preferredServer, ignoreCase = true) },
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_PREFERRED_QUALITY
            title = "Preferred Quality"
            entries = arrayOf("4K", "1080p", "720p")
            entryValues = arrayOf("4K", "1080p", "720p")
            setDefaultValue(DEFAULT_PREFERRED_QUALITY)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_PREFERRED_SERVER
            title = "Preferred Server"
            entries = arrayOf("FSL Server", "Download", "BuzzServer", "Pixeldrain", "S3 Server", "Mega Server", "PDL Server", "10Gbps")
            entryValues = arrayOf("FSL Server", "Download", "BuzzServer", "Pixeldrain", "S3 Server", "Mega Server", "PDL Server", "10Gbps")
            setDefaultValue(DEFAULT_PREFERRED_SERVER)
            summary = "%s"
        }.also(screen::addPreference)

        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            default = true,
            title = "Show episode thumbnails",
            summary = "Fetch and display images in the episode list from TMDB.",
        )
    }

    companion object {
        private const val PREF_PREFERRED_QUALITY = "pref_preferred_quality"
        private const val DEFAULT_PREFERRED_QUALITY = "1080p"
        private const val PREF_PREFERRED_SERVER = "pref_preferred_server"
        private const val DEFAULT_PREFERRED_SERVER = "FSL Server"
        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"
    }
}

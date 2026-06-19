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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
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

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            GET(baseUrl, headers)
        } else {
            GET("$baseUrl/?pagex=$page", headers)
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = doc.select("a.movie-card").map { element ->
            SAnime.create().apply {
                val href = element.attr("href")
                url = href
                title = element.selectFirst(".movie-card-title")?.text() ?: "Unknown"

                val posterImg = element.selectFirst("img")
                val rawImg = posterImg?.attr("src") ?: posterImg?.attr("data-src") ?: ""
                thumbnail_url = if (rawImg.startsWith("http")) rawImg else "$baseUrl/${rawImg.trimStart('/')}"

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("s", query)
            if (page > 1) {
                url.addQueryParameter("pagex", page.toString())
            }
            GET(url.toString(), headers)
        } else {
            popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val anime = SAnime.create().apply {
            title = doc.selectFirst(".page-title")?.text() ?: doc.selectFirst("title")?.text()?.replace(" - 4KHDHub", "") ?: "Unknown"

            val synopsis = doc.selectFirst(".content-section p.mt-4")?.text() ?: ""

            val detailsImg = doc.selectFirst(".poster-container img")
            val rawDetailsImg = detailsImg?.attr("src") ?: detailsImg?.attr("data-src")
            thumbnail_url = rawDetailsImg?.let {
                if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
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

            val grouped = itemsData.groupBy { Pair(it.first, it.second) }

            grouped.forEach { (key, list) ->
                val seasonPrefix = key.first
                val epNum = key.second
                val firstItem = list.first()

                episodes.add(SEpisode.create().apply {
                    name = "$seasonPrefix - ${firstItem.third}"
                    episode_number = epNum
                    url = "$pagePath?season=${URLDecoder.decode(seasonPrefix, "UTF-8")}&episode=$epNum"
                })
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
                    episodes.add(SEpisode.create().apply {
                        name = "Movie"
                        episode_number = 1f
                        url = "$pagePath?movie=true"
                    })
                }
            }
        }

        return episodes.sortedByDescending { it.episode_number }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val list = mutableListOf<Video>()
        val urlParam = episode.url
        val pagePath = urlParam.substringBefore("?")
        val query = urlParam.substringAfter("?", "")

        if (query.isEmpty()) return emptyList()

        try {
            val response = client.newCall(GET(baseUrl + pagePath, headers)).execute()
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

                    val suffix = parseLabelSuffix(filename)

                    val links = mutableListOf<String>()
                    element.select("a[href*='hubcloud'], a[href*='hubdrive']").forEach {
                        links.add(it.attr("href"))
                    }

                    if (links.isEmpty()) {
                        val fileId = element.selectFirst(".download-header")?.attr("data-file-id")
                        if (fileId != null) {
                            doc.select("#content-$fileId a[href*='hubcloud'], #content-$fileId a[href*='hubdrive']").forEach {
                                links.add(it.attr("href"))
                            }
                        }
                    }

                    links.distinct().forEach { link ->
                        when {
                            link.contains("hubcloud.", ignoreCase = true) -> {
                                list.addAll(resolveHubCloud(link, suffix))
                            }
                            link.contains("hubdrive.", ignoreCase = true) -> {
                                list.addAll(resolveHubDrive(link, suffix))
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
                                val suffix = parseLabelSuffix(filename)
                                
                                val links = mutableListOf<String>()
                                element.select("a[href*='hubcloud'], a[href*='hubdrive']").forEach {
                                    links.add(it.attr("href"))
                                }

                                links.distinct().forEach { link ->
                                    when {
                                        link.contains("hubcloud.", ignoreCase = true) -> {
                                            list.addAll(resolveHubCloud(link, suffix))
                                        }
                                        link.contains("hubdrive.", ignoreCase = true) -> {
                                            list.addAll(resolveHubDrive(link, suffix))
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

        // Sort videos list according to user preferences
        val preferredQuality = preferences.getString(PREF_PREFERRED_QUALITY, DEFAULT_PREFERRED_QUALITY)!!
        val preferredServer = preferences.getString(PREF_PREFERRED_SERVER, DEFAULT_PREFERRED_SERVER)!!

        list.sortWith(
            compareByDescending<Video> { it.videoTitle.contains(preferredQuality, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(preferredServer, ignoreCase = true) }
        )

        return list
    }

    private fun parseLabelSuffix(filename: String): String {
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

        return if (quality.isNotEmpty()) " [$quality - $format]" else ""
    }

    private fun resolveHubCloud(hubCloudUrl: String, suffix: String): List<Video> {
        val list = mutableListOf<Video>()
        try {
            val resp = client.newCall(GET(hubCloudUrl, headers)).execute()
            val html = resp.body.string()
            resp.close()

            val doc = Jsoup.parse(html)
            var genLink = doc.select("a[href*=hubcloud.php]").attr("href")
            if (genLink.isEmpty()) {
                val match = Regex("""https://[^/]+/hubcloud\.php\?[^\s"\'<>]+""").find(html)
                if (match != null) {
                    genLink = match.value
                }
            }

            if (genLink.isEmpty()) return emptyList()

            val resp2 = client.newCall(GET(genLink, headers)).execute()
            val html2 = resp2.body.string()
            resp2.close()

            val doc2 = Jsoup.parse(html2)

            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            val minute = calendar.get(Calendar.MINUTE)

            val s3El = doc2.selectFirst("a#s3")
            if (s3El != null) {
                val rawUrl = s3El.attr("href")
                if (rawUrl.isNotEmpty()) {
                    val finalUrl = "${rawUrl}_1$minute"
                    list.add(Video(videoUrl = finalUrl, videoTitle = "HubCloud (FSLv2)$suffix", headers = headers))
                }
            }

            val fslEl = doc2.selectFirst("a#fsl")
            if (fslEl != null) {
                val rawUrl = fslEl.attr("href")
                if (rawUrl.isNotEmpty()) {
                    val finalUrl = "$rawUrl$1$minute"
                    list.add(Video(videoUrl = finalUrl, videoTitle = "HubCloud (FSL)$suffix", headers = headers))
                }
            }

            val pxlEl = doc2.selectFirst("a#pxl-1")
            if (pxlEl != null) {
                val scriptMatch = Regex("""pxl\s*=\s*["\']([^"\']+)["\']""").find(html2)
                if (scriptMatch != null) {
                    val pxlUrl = scriptMatch.groupValues[1]
                    list.add(Video(videoUrl = pxlUrl, videoTitle = "HubCloud (PixelServer)$suffix", headers = headers))
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return list
    }

    private fun resolveHubDrive(hubDriveUrl: String, suffix: String): List<Video> {
        try {
            val resp = client.newCall(GET(hubDriveUrl, headers)).execute()
            val html = resp.body.string()
            resp.close()

            val doc = Jsoup.parse(html)
            val hubCloudUrl = doc.selectFirst("a[href*='hubcloud']")?.attr("href")
            if (!hubCloudUrl.isNullOrEmpty()) {
                return resolveHubCloud(hubCloudUrl, suffix)
            }
        } catch (e: Exception) {
            // ignore
        }
        return emptyList()
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
            entries = arrayOf("FSLv2", "FSL", "PixelServer")
            entryValues = arrayOf("FSLv2", "FSL", "PixelServer")
            setDefaultValue(DEFAULT_PREFERRED_SERVER)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_PREFERRED_QUALITY = "pref_preferred_quality"
        private const val DEFAULT_PREFERRED_QUALITY = "1080p"
        private const val PREF_PREFERRED_SERVER = "pref_preferred_server"
        private const val DEFAULT_PREFERRED_SERVER = "FSLv2"
    }
}

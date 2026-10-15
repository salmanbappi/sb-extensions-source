@file:Suppress("unused")

package eu.kanade.tachiyomi.animeextension.en.fouranimo

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
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.asJsoup
import extensions.utils.delegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.net.URLEncoder

class FourAnimo : Source() {

    override val name = "Zanix"

    override val baseUrl = "https://zanix.tr"

    override val lang = "en"

    override val supportsLatest = true

    private val embedBaseUrl = "https://cdn.4animo.xyz"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // Preferences
    private val preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val preferredAudio by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val preferredServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    // ============================== Helper Utils ==============================
    private fun parseAnimeListFromDocument(document: Document): AnimesPage {
        val animeList = document.select(".film_list-wrap .flw-item").mapNotNull { element ->
            val titleElement = element.selectFirst(".film-name a")
                ?: element.selectFirst(".film-poster-ahref")
                ?: return@mapNotNull null
            val title = titleElement.attr("title").ifEmpty { titleElement.text() }
            val href = titleElement.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val imgElement = element.selectFirst(".film-poster img")
            val poster = imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") } ?: ""
            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = poster
            }
        }
        val hasNextPage = document.select("ul.pagination li.page-item.active + li.page-item:not(.disabled) a").isNotEmpty() ||
            document.select("ul.pagination a[href*='page=']:contains(›)").isNotEmpty()
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/most-popular?page=$page", headers)).execute()
        return parseAnimeListFromDocument(response.asJsoup())
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/recently-updated?page=$page", headers)).execute()
        return parseAnimeListFromDocument(response.asJsoup())
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val targetUrl = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            "$baseUrl/search?keyword=$encodedQuery&page=$page"
        } else {
            val urlBuilder = "$baseUrl/filter".toHttpUrl().newBuilder()
            urlBuilder.addQueryParameter("page", page.toString())
            filters.forEach { filter ->
                when (filter) {
                    is Filters.TypeFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("type", filter.toUriPart())
                    }

                    is Filters.StatusFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("status", filter.toUriPart())
                    }

                    is Filters.SortFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("sort", filter.toUriPart())
                    }

                    is Filters.SeasonFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("season", filter.toUriPart())
                    }

                    is Filters.LanguageFilter -> {
                        if (!filter.isDefault()) urlBuilder.addQueryParameter("language", filter.toUriPart())
                    }

                    is Filters.GenreFilter -> {
                        val selected = filter.selected()
                        if (selected.isNotEmpty()) {
                            urlBuilder.addQueryParameter("genres", selected.joinToString(","))
                        }
                    }

                    else -> {}
                }
            }
            urlBuilder.build().toString()
        }

        val response = client.newCall(GET(targetUrl, headers)).execute()
        return parseAnimeListFromDocument(response.asJsoup())
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply only when search query is blank"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SortFilter(),
        Filters.SeasonFilter(),
        Filters.LanguageFilter(),
        Filters.GenreFilter(),
    )

    // =========================== Anime Details ============================
    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        return GET(url, headers)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        val response = client.newCall(GET(url, headers)).execute()
        val document = response.asJsoup()

        val title = document.selectFirst("h1.film-name")?.text() ?: anime.title
        val poster = document.selectFirst(".anisc-poster img")?.attr("src")?.ifEmpty {
            document.selectFirst(".anisc-poster img")?.attr("data-src")
        } ?: anime.thumbnail_url

        val description = document.selectFirst(".anisc-info .item-title:has(.item-head:contains(Overview)) .text")?.text()
            ?: document.selectFirst(".anisc-info .text")?.text()

        val genres = document.select(".anisc-info .item-list:has(.item-head:contains(Genres)) a").map { it.text() }
        val statusText = document.selectFirst(".anisc-info .item-title:has(.item-head:contains(Status)) .name")?.text().orEmpty()
        val status = when {
            statusText.contains("RELEASING", ignoreCase = true) || statusText.contains("AIRING", ignoreCase = true) -> SAnime.ONGOING
            statusText.contains("COMPLETED", ignoreCase = true) || statusText.contains("FINISHED", ignoreCase = true) -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
        val studios = document.select(".anisc-info .item-title:has(.item-head:contains(Studios)) a").joinToString(", ") { it.text() }

        return anime.apply {
            this.title = title
            this.thumbnail_url = poster
            this.description = description
            this.genre = genres.joinToString(", ")
            this.status = status
            this.author = studios
            this.initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val cleanUrl = anime.url.removePrefix("/")
        val animeId = cleanUrl.substringAfterLast("-").takeWhile { it.isDigit() }
        val watchUrl = "$baseUrl/watch/$cleanUrl?ep=1"
        val watchResponse = client.newCall(GET(watchUrl, headers)).execute()
        val cookieHeader = watchResponse.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
        val watchHtml = watchResponse.body.string()

        val token = """window\.AJAX_TOKEN\s*=\s*"([^"]+)"""".toRegex().find(watchHtml)?.groupValues?.get(1).orEmpty()
        val numericId = """let animeId\s*=\s*(\d+)""".toRegex().find(watchHtml)?.groupValues?.get(1) ?: animeId

        val epHeaders = headers.newBuilder()
            .set("Referer", watchUrl)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("X-Page-Token", token)
            .apply {
                if (cookieHeader.isNotBlank()) {
                    set("Cookie", cookieHeader)
                }
            }
            .build()

        val epResponse = client.newCall(GET("$baseUrl/ajax/episodes?animeId=$numericId", epHeaders)).execute()
        val epJson = epResponse.body.string()

        val episodesList = mutableListOf<SEpisode>()
        try {
            val root = json.parseToJsonElement(epJson).jsonObject
            val epArray = root["episodes"]?.jsonArray ?: return emptyList()

            for (epElem in epArray) {
                val epObj = epElem.jsonObject
                val epId = epObj["id"]?.jsonPrimitive?.content ?: continue
                val epNum = epObj["number"]?.jsonPrimitive?.content?.stripTrailingZero()?.toFloatOrNull() ?: 1.0f
                val embedId = epObj["embed_id"]?.jsonPrimitive?.content ?: epId
                val hasSub = epObj["sub"]?.jsonPrimitive?.content?.toBoolean() ?: true
                val hasDub = epObj["dub"]?.jsonPrimitive?.content?.toBoolean() ?: false

                val titlesObj = epObj["titles"]?.jsonObject
                val epTitle = titlesObj?.get("en")?.jsonPrimitive?.content
                    ?: titlesObj?.get("romaji")?.jsonPrimitive?.content
                    ?: titlesObj?.get("ja")?.jsonPrimitive?.content
                    ?: epObj["title"]?.jsonPrimitive?.content
                    ?: ""

                val thumbnail = "https://s4.zanix.tr/poster/$numericId.jpg"

                val ep = SEpisode.create().apply {
                    name = if (epTitle.isNotBlank() && !epTitle.startsWith("Episode", ignoreCase = true)) {
                        "Episode ${epNum.toInt()}: $epTitle"
                    } else {
                        "Episode ${epNum.toInt()}"
                    }
                    episode_number = epNum
                    setUrlWithoutDomain("$epId|$embedId|$numericId|${epNum.toInt()}|$hasSub|$hasDub")
                    scanlator = when {
                        hasSub && hasDub -> "Sub / Dub"
                        hasDub -> "Dub"
                        hasSub -> "Sub"
                        else -> "Sub"
                    }
                    preview_url = thumbnail
                }
                episodesList.add(ep)
            }
        } catch (_: Exception) {}

        return episodesList.reversed()
    }

    private fun String.stripTrailingZero(): String = if (this.endsWith(".0")) this.dropLast(2) else this

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val cleanUrl = episode.url.removePrefix("/")
        val parts = cleanUrl.split("|")

        val epId = parts.getOrNull(0) ?: return emptyList()
        val embedId = parts.getOrNull(1) ?: epId

        val hasSub = when {
            parts.size >= 6 -> parts[4] != "false"
            parts.size >= 4 -> parts[2] != "false"
            else -> true
        }

        val hasDub = when {
            parts.size >= 6 -> parts[5] == "true"
            parts.size >= 4 -> parts[3] == "true"
            else -> true
        }

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()

        val canSub = hasSub && "SUB" !in excludedAudios
        val canDub = hasDub && "DUB" !in excludedAudios

        val hosters = mutableListOf<Hoster>()

        // 1. M-Cloud (HD-1)
        if ("M-Cloud (HD-1)" !in excludedServers && (canSub || canDub)) {
            val subUrl = if (canSub) "$embedBaseUrl/embed/hd-1/$epId/sub?k=1" else ""
            val dubUrl = if (canDub) "$embedBaseUrl/embed/hd-1/$epId/dub?k=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "M-Cloud (HD-1)",
                    hosterUrl = "M-Cloud (HD-1)|$subUrl|$dubUrl",
                ),
            )
        }

        // 2. R-Cloud (HD-2)
        if ("R-Cloud (HD-2)" !in excludedServers && (canSub || canDub)) {
            val subUrl = if (canSub) "$embedBaseUrl/embed/hd-2/$epId/sub?k=1" else ""
            val dubUrl = if (canDub) "$embedBaseUrl/embed/hd-2/$epId/dub?k=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "R-Cloud (HD-2)",
                    hosterUrl = "R-Cloud (HD-2)|$subUrl|$dubUrl",
                ),
            )
        }

        // 3. ReCloud (s-1)
        if ("ReCloud (s-1)" !in excludedServers && (canSub || canDub)) {
            val subHd2Url = if (canSub) "$embedBaseUrl/embed/s-1/$embedId/sub?k=1" else ""
            val dubHd2Url = if (canDub) "$embedBaseUrl/embed/s-1/$embedId/dub?k=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "ReCloud (s-1)",
                    hosterUrl = "ReCloud (s-1)|$subHd2Url|$dubHd2Url",
                ),
            )
        }

        return sortHostersByPreference(hosters)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = coroutineScope {
        val parts = hoster.hosterUrl.split("|")
        val serverName = parts.getOrNull(0) ?: hoster.hosterName
        val subEmbedUrl = parts.getOrNull(1).orEmpty()
        val dubEmbedUrl = parts.getOrNull(2).orEmpty()

        val subDeferred = async(Dispatchers.IO) {
            if (subEmbedUrl.isNotBlank()) extractVideosFromEmbed("SUB", serverName, subEmbedUrl) else emptyList()
        }
        val dubDeferred = async(Dispatchers.IO) {
            if (dubEmbedUrl.isNotBlank()) extractVideosFromEmbed("DUB", serverName, dubEmbedUrl) else emptyList()
        }

        val videos = subDeferred.await() + dubDeferred.await()
        FourAnimoHlsServer.processVideoList(client, videos.sortVideos())
    }

    private fun extractVideosFromEmbed(audioPrefix: String, serverName: String, embedUrl: String): List<Video> {
        return try {
            val embedReq = Request.Builder()
                .url(embedUrl)
                .headers(headers.newBuilder().set("Referer", "$baseUrl/").build())
                .build()

            val embedHtml = client.newCall(embedReq).execute().body.string()

            val sourcesUrlMatch = """var sourcesUrl\s*=\s*['"]([^'"]+)['"]""".toRegex().find(embedHtml)
                ?: return emptyList()

            val sourcesPath = sourcesUrlMatch.groupValues[1]
            val fullSourcesUrl = if (sourcesPath.startsWith("http")) sourcesPath else "$embedBaseUrl$sourcesPath"

            val sourcesReq = Request.Builder()
                .url(fullSourcesUrl)
                .headers(headers.newBuilder().set("Referer", embedUrl).build())
                .build()

            val sourcesJsonStr = client.newCall(sourcesReq).execute().body.string()
            val jsonRoot = json.parseToJsonElement(sourcesJsonStr).jsonObject

            val rawFile = jsonRoot["sources"]?.jsonArray?.getOrNull(0)?.jsonObject?.get("file")?.jsonPrimitive?.content
                ?: jsonRoot["file"]?.jsonPrimitive?.content
                ?: return emptyList()

            val m3u8Url = if (rawFile.startsWith("http")) rawFile else "$embedBaseUrl$rawFile"

            val subtitleTracks = mutableListOf<Track>()
            jsonRoot["tracks"]?.jsonArray?.forEach { trackElem ->
                val trackObj = trackElem.jsonObject
                val kind = trackObj["kind"]?.jsonPrimitive?.content
                if (kind == "captions" || kind == "subtitles") {
                    val file = trackObj["file"]?.jsonPrimitive?.content ?: return@forEach
                    val label = trackObj["label"]?.jsonPrimitive?.content ?: "Subtitles"
                    val subFileUrl = if (file.startsWith("http")) file else "$embedBaseUrl$file"
                    subtitleTracks.add(Track(subFileUrl, label))
                }
            }

            val m3u8Req = Request.Builder()
                .url(m3u8Url)
                .headers(headers.newBuilder().set("Referer", embedUrl).build())
                .build()
            val m3u8Content = client.newCall(m3u8Req).execute().body.string()

            val videos = mutableListOf<Video>()
            if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                val lines = m3u8Content.lines()
                var currentQuality = "1080p"
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val resMatch = """RESOLUTION=(\d+x\d+)""".toRegex().find(line)
                        if (resMatch != null) {
                            val height = resMatch.groupValues[1].substringAfter("x")
                            currentQuality = "${height}p"
                        }
                    } else if (line.isNotEmpty() && !line.startsWith("#")) {
                        val streamUrl = when {
                            line.startsWith("http") -> line
                            line.startsWith("/") -> "$embedBaseUrl$line"
                            else -> "${m3u8Url.substringBeforeLast("/")}/$line"
                        }
                        videos.add(
                            Video(
                                videoUrl = streamUrl,
                                videoTitle = "$audioPrefix - $serverName - $currentQuality",
                                headers = headers.newBuilder().set("Referer", embedUrl).build(),
                                subtitleTracks = subtitleTracks,
                            ),
                        )
                    }
                }
            }

            if (videos.isEmpty()) {
                videos.add(
                    Video(
                        videoUrl = m3u8Url,
                        videoTitle = "$audioPrefix - $serverName",
                        headers = headers.newBuilder().set("Referer", embedUrl).build(),
                        subtitleTracks = subtitleTracks,
                    ),
                )
            }

            videos
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferredQuality
        val prefAudio = preferredAudio

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefAudio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) },
        )
    }

    private fun sortHostersByPreference(hosters: List<Hoster>): List<Hoster> {
        val prefServer = preferredServer
        if (prefServer == "auto") return hosters

        return hosters.sortedByDescending {
            it.hosterName.contains(prefServer, ignoreCase = true)
        }
    }

    // ============================ Recommendations =============================
    fun relatedAnimeListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        return GET(url, headers)
    }

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        return doc.select(".block_area-realtime li, .film_list-wfeature .flw-item").mapNotNull { el ->
            val titleEl = el.selectFirst(".film-name a") ?: return@mapNotNull null
            val title = titleEl.attr("title").ifEmpty { titleEl.text() }
            val href = titleEl.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val img = el.selectFirst(".film-poster img")
            val poster = img?.attr("data-src")?.ifEmpty { img.attr("src") } ?: ""
            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = poster
            }
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "Sorts streams so this quality is prioritized. Currently: %s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio type",
            summary = "Prioritizes Sub or Dub audio streams. Currently: %s",
            entries = listOf("Subtitled", "Dubbed"),
            entryValues = listOf("SUB", "DUB"),
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "Prioritizes this server in the host list. Currently: %s",
            entries = listOf("Auto", "M-Cloud (HD-1)", "R-Cloud (HD-2)", "ReCloud (s-1)"),
            entryValues = listOf("auto", "M-Cloud (HD-1)", "R-Cloud (HD-2)", "ReCloud (s-1)"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude servers",
            summary = "Select servers to exclude from episode hosters",
            entries = listOf("M-Cloud (HD-1)", "R-Cloud (HD-2)", "ReCloud (s-1)"),
            entryValues = listOf("M-Cloud (HD-1)", "R-Cloud (HD-2)", "ReCloud (s-1)"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_AUDIO_KEY,
            default = emptySet(),
            title = "Exclude audio format",
            summary = "Select audio formats to exclude",
            entries = listOf("Subtitled", "Dubbed"),
            entryValues = listOf("SUB", "DUB"),
        )
    }

    companion object {
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_AUDIO_KEY = "pref_audio"
        private const val PREF_AUDIO_DEFAULT = "SUB"

        private const val PREF_SERVER_KEY = "pref_server"
        private const val PREF_SERVER_DEFAULT = "M-Cloud (HD-1)"

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"
    }
}

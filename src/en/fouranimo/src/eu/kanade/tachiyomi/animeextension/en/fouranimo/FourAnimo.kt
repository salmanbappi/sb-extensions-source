package eu.kanade.tachiyomi.animeextension.en.fouranimo

import android.net.Uri
import android.util.Base64
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
import extensions.utils.delegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FourAnimo : Source() {

    override val name = "Animo"

    override val baseUrl = "https://4animo.xyz"

    override val lang = "en"

    override val supportsLatest = true

    private val imageBaseUrl = "https://cdnanimo.xyz"
    private val embedBaseUrl = "https://cdn.4animo.xyz"

    private var proxy: LocalProxyServer? = null

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
        .add("Referer", "$baseUrl/")

    // Preferences
    private val preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val preferredAudio by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val preferredServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    // ============================== Local Proxy ===============================
    private fun getProxyUrl(url: String, sourceHeaders: Headers? = null): String {
        if (proxy == null) {
            proxy = LocalProxyServer(client, json).apply { start() }
        }
        val encodedUrl = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val encodedHeaders = encodeHeaders(sourceHeaders)
        val path = if (url.contains(".m3u8")) "playlist.m3u8" else "segment.ts"
        val query = "url=$encodedUrl" + if (encodedHeaders != null) "&headers=$encodedHeaders" else ""
        return "http://127.0.0.1:${proxy!!.port}/$path?$query"
    }

    private fun encodeHeaders(hdrs: Headers?): String? {
        if (hdrs == null || hdrs.size == 0) return null
        val map = mutableMapOf<String, String>()
        for (i in 0 until hdrs.size) {
            map[hdrs.name(i)] = hdrs.value(i)
        }
        return try {
            Base64.encodeToString(json.encodeToString(map).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: Exception) {
            null
        }
    }

    // ============================== Helper Utils ==============================
    private fun extractNextPayload(html: String): String {
        val regex = """self\.__next_f\.push\(\[1,\s*"(.*)"\]\)""".toRegex()
        val pushes = regex.findAll(html).map { it.groupValues[1] }.joinToString("")
        return pushes.replace(""""""", """"""").replace("""\""", """""")
    }

    private fun parseAnimeCardList(payload: String): List<SAnime> {
        val animeList = mutableListOf<SAnime>()
        val seen = mutableSetOf<String>()

        // 1. Structured JSON match: {"id":12, "slug":"one-piece-12", "titles":{...}, ... "images":{"poster":"..."}}
        val jsonMatchRegex = """\{"id":(\d+),"slug":"([^"]+)","titles":(\{[^{}]+\})""".toRegex()
        jsonMatchRegex.findAll(payload).forEach { match ->
            val id = match.groupValues[1]
            val slug = match.groupValues[2]
            val titlesJson = match.groupValues[3]
            if (slug !in seen) {
                seen.add(slug)
                val title = extractTitleFromJson(titlesJson)
                val posterUrl = "$imageBaseUrl/poster/$id.jpg"
                animeList.add(
                    SAnime.create().apply {
                        this.title = title
                        setUrlWithoutDomain("/watch/$slug")
                        thumbnail_url = posterUrl
                    },
                )
            }
        }

        // 2. Fallback Card render match: {"href":"/slug","title":"Title", ... "src":"https://cdnanimo.xyz/poster/123.jpg"}
        val cardRegex = """\{"href":"/([^"]+)","title":"([^"]+)".*?"src":"(https://cdnanimo\.xyz/poster/[^"]+)"""".toRegex()
        cardRegex.findAll(payload).forEach { match ->
            val slug = match.groupValues[1].removePrefix("watch/").removePrefix("/")
            val title = match.groupValues[2]
            val poster = match.groupValues[3]
            if (slug !in seen) {
                seen.add(slug)
                animeList.add(
                    SAnime.create().apply {
                        this.title = title
                        setUrlWithoutDomain("/watch/$slug")
                        thumbnail_url = poster
                    },
                )
            }
        }

        // 3. Next.js image element match: "src":"https://cdnanimo.xyz/poster/123.jpg","alt":"Title"
        val altRegex = """"src":"https://cdnanimo\.xyz/poster/(\d+)\.jpg","alt":"([^"]+)"""".toRegex()
        altRegex.findAll(payload).forEach { match ->
            val id = match.groupValues[1]
            val title = match.groupValues[2]
            val poster = "$imageBaseUrl/poster/$id.jpg"
            val startIdx = maxOf(0, match.range.first - 500)
            val endIdx = minOf(payload.length, match.range.last + 500)
            val snippet = payload.substring(startIdx, endIdx)
            val slugMatch = """href["']?:\s*["']?/(?:watch/)?([^"'\s,\}\]]+)""".toRegex().find(snippet)
            val slug = slugMatch?.groupValues?.get(1)?.removePrefix("watch/")?.trim('/')
                ?: "${title.lowercase().replace(" ", "-")}-$id"
            if (slug !in seen) {
                seen.add(slug)
                animeList.add(
                    SAnime.create().apply {
                        this.title = title
                        setUrlWithoutDomain("/watch/$slug")
                        thumbnail_url = poster
                    },
                )
            }
        }

        return animeList
    }

    private fun extractTitleFromJson(titlesJson: String): String = try {
        val element = json.parseToJsonElement(titlesJson).jsonObject
        element["english"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: element["romaji"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: element["native"]?.jsonPrimitive?.content ?: "Anime"
    } catch (_: Exception) {
        "Anime"
    }

    private fun paginateAnimeList(allCards: List<SAnime>, page: Int, perPage: Int = 24): AnimesPage {
        if (allCards.isEmpty()) return AnimesPage(emptyList(), false)
        val fromIndex = (page - 1) * perPage
        if (fromIndex >= allCards.size) return AnimesPage(emptyList(), false)
        val toIndex = minOf(fromIndex + perPage, allCards.size)
        val pageCards = allCards.subList(fromIndex, toIndex)
        val hasNextPage = toIndex < allCards.size
        return AnimesPage(pageCards, hasNextPage)
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/home", headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)

        val mostPopularIndex = payload.indexOf("mostPopular")
        val targetPayload = if (mostPopularIndex != -1) {
            payload.substring(mostPopularIndex)
        } else {
            payload
        }

        val cards = parseAnimeCardList(targetPayload)
        return paginateAnimeList(cards, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/home", headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)

        val latestIndex = payload.indexOf("newAdded").takeIf { it != -1 }
            ?: payload.indexOf("latestEpisode").takeIf { it != -1 }
            ?: -1

        val targetPayload = if (latestIndex != -1) {
            payload.substring(latestIndex)
        } else {
            payload
        }

        val cards = parseAnimeCardList(targetPayload)
        return paginateAnimeList(cards, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val targetUrl = if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            "$baseUrl/search?keyword=$encodedQuery"
        } else {
            var url = "$baseUrl/home"
            filters.forEach { filter ->
                when (filter) {
                    is Filters.SortFilter -> {
                        if (!filter.isDefault()) {
                            url = "$baseUrl/catalog?sort=${filter.toUriPart()}"
                        }
                    }

                    is Filters.TypeFilter -> {
                        if (!filter.isDefault()) {
                            url = "$baseUrl/anime/type/${filter.toUriPart()}"
                        }
                    }

                    is Filters.StatusFilter -> {
                        if (!filter.isDefault()) {
                            url = "$baseUrl/catalog?status=${filter.toUriPart()}"
                        }
                    }

                    is Filters.GenreFilter -> {
                        val selectedGenres = filter.selected()
                        if (selectedGenres.isNotEmpty()) {
                            url = "$baseUrl/anime/genre/${selectedGenres.first()}"
                        }
                    }

                    else -> {}
                }
            }
            url
        }

        val response = client.newCall(GET(targetUrl, headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)
        val cards = parseAnimeCardList(payload)
        return paginateAnimeList(cards, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply only when search query is blank"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SortFilter(),
        Filters.SeasonFilter(),
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
        val html = response.body.string()
        val payload = extractNextPayload(html)

        val animeObjMatch = """"anime":(\{\s*"id":.*?\})\s*,\s*"episodes"""".toRegex().find(payload)
            ?: """"anime":(\{\s*"id":.*?\}),""".toRegex().find(payload)

        val updatedAnime = SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            setUrlWithoutDomain(anime.url)
        }

        if (animeObjMatch != null) {
            val jsonStr = animeObjMatch.groupValues[1]
            try {
                val element = json.parseToJsonElement(jsonStr).jsonObject
                val idStr = element["id"]?.jsonPrimitive?.content ?: ""
                if (idStr.isNotBlank()) {
                    updatedAnime.thumbnail_url = "$imageBaseUrl/poster/$idStr.jpg"
                }

                val titlesObj = element["titles"]?.jsonObject
                val engTitle = titlesObj?.get("english")?.jsonPrimitive?.content
                val romajiTitle = titlesObj?.get("romaji")?.jsonPrimitive?.content
                if (!engTitle.isNullOrBlank()) {
                    updatedAnime.title = engTitle
                } else if (!romajiTitle.isNullOrBlank()) {
                    updatedAnime.title = romajiTitle
                }

                val statusStr = element["status"]?.jsonPrimitive?.content ?: ""
                updatedAnime.status = when {
                    statusStr.contains("RELEASING", ignoreCase = true) -> SAnime.ONGOING
                    statusStr.contains("FINISHED", ignoreCase = true) -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }

                val genresList = element["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val tagsList = element["tags"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()
                updatedAnime.genre = (genresList + tagsList).distinct().joinToString(", ")

                val studioList = element["studios"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()
                val producerList = element["producers"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content } ?: emptyList()
                val authorStr = (studioList + producerList).distinct().joinToString(", ")
                if (authorStr.isNotBlank()) {
                    updatedAnime.author = authorStr
                }

                val rawSynopsis = element["synopsis"]?.jsonPrimitive?.content
                    ?: element["description"]?.jsonPrimitive?.content ?: ""

                val metaMatch = """"(?:name|property)":"(?:og:)?description","content":"([^"]+)"""".toRegex().find(payload)
                val metaDesc = metaMatch?.groupValues?.get(1) ?: ""

                val descriptionStr = when {
                    rawSynopsis.isNotBlank() && !rawSynopsis.startsWith("$") -> rawSynopsis
                    metaDesc.isNotBlank() -> metaDesc
                    else -> ""
                }

                val cleanDescription = descriptionStr
                    .replace(Regex("(?i)<br\s*/?>"), "
")
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace(Regex("
{3,}"), "

")
                    .trim()

                val score = element["score"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: element["score_mal"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val formatType = element["type"]?.jsonPrimitive?.content ?: ""
                val duration = element["duration_min"]?.jsonPrimitive?.content
                val rating = element["rating"]?.jsonPrimitive?.content

                val seasonObj = element["season"]?.jsonObject
                val seasonName = seasonObj?.get("name")?.jsonPrimitive?.content
                val seasonYear = seasonObj?.get("year")?.jsonPrimitive?.content
                val seasonStr = if (seasonName != null && seasonYear != null) "$seasonName $seasonYear" else seasonYear ?: seasonName

                val epObj = element["episode"]?.jsonObject
                val totalEp = epObj?.get("episodes")?.jsonPrimitive?.content
                val subEp = epObj?.get("sub")?.jsonPrimitive?.content
                val dubEp = epObj?.get("dub")?.jsonPrimitive?.content

                updatedAnime.description = buildString {
                    if (cleanDescription.isNotBlank()) {
                        append(cleanDescription)
                        append("

")
                    }
                    if (score != null && score > 0.0) {
                        append("Score: ★ $score
")
                    }
                    if (formatType.isNotBlank()) {
                        append("Format: $formatType
")
                    }
                    if (!duration.isNullOrBlank()) {
                        append("Duration: ${duration}m
")
                    }
                    if (!rating.isNullOrBlank()) {
                        append("Rating: $rating
")
                    }
                    if (!seasonStr.isNullOrBlank()) {
                        append("Season: $seasonStr
")
                    }
                    if (!totalEp.isNullOrBlank()) {
                        append("Episodes: $totalEp (Sub: ${subEp ?: totalEp}, Dub: ${dubEp ?: 0})
")
                    }
                }.trim()
            } catch (_: Exception) {}
        }

        updatedAnime.initialized = true
        return updatedAnime
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        val response = client.newCall(GET(url, headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)

        val animeIdMatch = """"id":(\d+),"slug":""".toRegex().find(payload)
        val animeId = animeIdMatch?.groupValues?.get(1) ?: ""

        val episodesMatch = """"episodes":(\[\{.*?\}\])""".toRegex().find(payload)
            ?: return emptyList()

        val episodesJsonStr = episodesMatch.groupValues[1]
        val episodesList = mutableListOf<SEpisode>()

        try {
            val epArray = json.parseToJsonElement(episodesJsonStr).jsonArray
            for (epElem in epArray) {
                val epObj = epElem.jsonObject
                val epId = epObj["id"]?.jsonPrimitive?.content ?: continue
                val epNum = epObj["number"]?.jsonPrimitive?.content?.stripTrailingZero()?.toFloatOrNull() ?: 1.0f
                val embedId = epObj["embedId"]?.jsonPrimitive?.content ?: epId
                val hasSub = epObj["hasSub"]?.jsonPrimitive?.content?.toBoolean() ?: true
                val hasDub = epObj["hasDub"]?.jsonPrimitive?.content?.toBoolean() ?: false

                val titlesObj = epObj["titles"]?.jsonObject
                val epTitle = titlesObj?.get("english")?.jsonPrimitive?.content
                    ?: titlesObj?.get("romaji")?.jsonPrimitive?.content ?: ""

                val thumbnail = epObj["thumbnail"]?.jsonPrimitive?.content
                    ?: "$imageBaseUrl/episode/$epId.jpg"

                val ep = SEpisode.create().apply {
                    name = if (epTitle.isNotBlank()) "Episode ${epNum.toInt()}: $epTitle" else "Episode ${epNum.toInt()}"
                    episode_number = epNum
                    setUrlWithoutDomain("$epId|$embedId|$animeId|${epNum.toInt()}|$hasSub|$hasDub")
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

        // 1. ReCloud
        if ("ReCloud" !in excludedServers && (canSub || canDub)) {
            val subEmbedUrl = if (canSub) "$embedBaseUrl/embed/a-1/$epId/sub?k=1&autoPlay=1" else ""
            val dubEmbedUrl = if (canDub) "$embedBaseUrl/embed/a-1/$epId/dub?k=1&autoPlay=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "ReCloud",
                    hosterUrl = "ReCloud|$subEmbedUrl|$dubEmbedUrl",
                ),
            )
        }

        // 2. ReCloud HD-2
        if ("ReCloud HD-2" !in excludedServers && (canSub || canDub)) {
            val subHd2Url = if (canSub) "$embedBaseUrl/embed/s-1/$embedId/sub?k=1" else ""
            val dubHd2Url = if (canDub) "$embedBaseUrl/embed/s-1/$embedId/dub?k=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "ReCloud HD-2",
                    hosterUrl = "ReCloud HD-2|$subHd2Url|$dubHd2Url",
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
        videos.sortVideos().map { video ->
            if (video.videoUrl.contains(".m3u8", ignoreCase = true) || video.videoUrl.contains("/p?t=", ignoreCase = true)) {
                Video(
                    videoUrl = getProxyUrl(video.videoUrl, video.headers),
                    videoTitle = video.videoTitle,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                    headers = video.headers,
                )
            } else {
                video
            }
        }
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
        val html = response.body.string()
        val payload = extractNextPayload(html)
        return parseAnimeCardList(payload)
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
            entries = listOf("Auto", "ReCloud", "ReCloud HD-2"),
            entryValues = listOf("auto", "ReCloud", "ReCloud HD-2"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude servers",
            summary = "Select servers to exclude from episode hosters",
            entries = listOf("ReCloud", "ReCloud HD-2"),
            entryValues = listOf("ReCloud", "ReCloud HD-2"),
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
        private const val PREF_SERVER_DEFAULT = "auto"

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"
    }
}

// ========================= Local Proxy Server =============================

private class LocalProxyServer(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    val port: Int
        get() = serverSocket?.let { if (it.isClosed) 0 else it.localPort } ?: 0

    fun start() {
        if (running.get() && serverSocket?.isClosed == false) return
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            running.set(true)
            executor.execute {
                while (running.get() && serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        executor.execute { handleClient(socket) }
                    } catch (_: Exception) {
                        if (serverSocket?.isClosed == true || !running.get()) break
                    }
                }
            }
        } catch (_: Exception) {
            running.set(false)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            val firstLine = input.bufferedReader().readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val path = parts[1]
                routeRequest(path, output)
            }
        }
    }

    private fun routeRequest(path: String, output: OutputStream) {
        val uri = Uri.parse("http://127.0.0.1$path")
        val encodedUrl = uri.getQueryParameter("url") ?: return
        val encodedHeaders = uri.getQueryParameter("headers")
        val targetUrl = try {
            String(Base64.decode(encodedUrl, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } catch (_: Exception) {
            return
        }
        val headers = decodeHeaders(encodedHeaders)

        try {
            when {
                path.contains("playlist.m3u8") -> servePlaylist(targetUrl, headers, encodedHeaders, output)
                path.contains("key.bin") -> serveKey(targetUrl, headers, output)
                else -> serveSegment(targetUrl, headers, output)
            }
        } catch (_: Exception) {
            try {
                output.write("HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n".toByteArray())
            } catch (_: Exception) {}
        }
    }

    private fun decodeHeaders(encoded: String?): Headers {
        val fallback = Headers.Builder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
            .set("Referer", "https://4animo.xyz/")
            .build()
        if (encoded.isNullOrEmpty()) return fallback
        return try {
            val jsonStr = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val map = json.decodeFromString<Map<String, String>>(jsonStr)
            Headers.Builder().apply {
                for (entry in map.entries) {
                    set(entry.key, entry.value)
                }
            }.build()
        } catch (_: Exception) {
            fallback
        }
    }

    private fun getProxyUrl(url: String, headersStr: String?, isKey: Boolean = false): String {
        val encoded = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val path = when {
            isKey || url.contains(".key") || url.contains("key.bin") -> "key.bin"
            url.contains(".m3u8") -> "playlist.m3u8"
            else -> "segment.ts"
        }
        val query = "url=$encoded" + if (!headersStr.isNullOrEmpty()) "&headers=$headersStr" else ""
        return "http://127.0.0.1:$port/$path?$query"
    }

    private fun fetchWithRetry(targetUrl: String, headers: Headers): Response {
        var response = client.newCall(GET(targetUrl, headers)).execute()
        if (response.code == 403) {
            response.close()
            val fallbackHeaders = headers.newBuilder()
                .set("Referer", "https://4animo.xyz/")
                .build()
            response = client.newCall(GET(targetUrl, fallbackHeaders)).execute()
        }
        return response
    }

    private fun servePlaylist(targetUrl: String, headers: Headers, encodedHeaders: String?, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, headers)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }

        val content = response.body.string()
        response.close()
        val lines = content.split(Regex("""\r?\n"""))
        val builder = StringBuilder(content.length * 2)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                builder.append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                val uriRegex = Regex("""URI=["']?([^"',\s>]+)["']?""")
                uriRegex.find(trimmed)?.let { match ->
                    val uriValue = match.groupValues[1]
                    val rawResolved = when {
                        uriValue.startsWith("//") -> "https:$uriValue"
                        else -> targetUrl.toHttpUrl().resolve(uriValue)?.toString() ?: uriValue
                    }
                    val resolvedUri = if (rawResolved.startsWith("//")) "https:$rawResolved" else rawResolved
                    val isKeyLine = trimmed.contains("#EXT-X-KEY") || resolvedUri.contains(".key")
                    val proxiedUri = getProxyUrl(resolvedUri, encodedHeaders, isKey = isKeyLine)
                    builder.append(trimmed.replace(uriValue, proxiedUri))
                } ?: builder.append(trimmed)
            } else {
                val rawResolved = when {
                    trimmed.startsWith("//") -> "https:$trimmed"
                    else -> targetUrl.toHttpUrl().resolve(trimmed)?.toString() ?: trimmed
                }
                val resolvedUri = if (rawResolved.startsWith("//")) "https:$rawResolved" else rawResolved
                val isKeyLine = resolvedUri.contains(".key")
                builder.append(getProxyUrl(resolvedUri, encodedHeaders, isKey = isKeyLine))
            }
            builder.append("\n")
        }

        val bodyBytes = builder.toString().toByteArray()
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bodyBytes.size}\r\n".toByteArray())
        output.write("Content-Type: application/vnd.apple.mpegurl\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bodyBytes)
        output.flush()
    }

    private fun serveKey(targetUrl: String, headers: Headers, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, headers)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }

        val bytes = response.body.bytes()
        response.close()

        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Length: ${bytes.size}\r\n".toByteArray())
        output.write("Content-Type: application/octet-stream\r\n".toByteArray())
        output.write("Connection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun serveSegment(targetUrl: String, headers: Headers, output: OutputStream) {
        val response = fetchWithRetry(targetUrl, headers)
        if (!response.isSuccessful) {
            output.write("HTTP/1.1 ${response.code} Error\r\nConnection: close\r\n\r\n".toByteArray())
            response.close()
            return
        }

        val body = response.body
        val inputStream = body.byteStream()

        val headerBuffer = ByteArray(131072)
        var totalRead = 0
        while (totalRead < headerBuffer.size) {
            val read = inputStream.read(headerBuffer, totalRead, headerBuffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }

        val sample = if (totalRead == headerBuffer.size) headerBuffer else headerBuffer.copyOf(totalRead)
        val skipBytes = detectSkipBytes(sample)
        val contentLength = body.contentLength()
        val payloadLength = if (contentLength > 0) contentLength - skipBytes else -1L

        val headerBuilder = StringBuilder("HTTP/1.1 200 OK\r\n")
        if (payloadLength >= 0) {
            headerBuilder.append("Content-Length: $payloadLength\r\n")
        }
        headerBuilder.append("Content-Type: video/mp2t\r\n")
        headerBuilder.append("Connection: close\r\n\r\n")
        output.write(headerBuilder.toString().toByteArray())

        if (totalRead > skipBytes) {
            output.write(sample, skipBytes, totalRead - skipBytes)
        }

        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
        }
        output.flush()
        response.close()
    }

    private fun detectSkipBytes(data: ByteArray): Int {
        if (data.size < 4) return 0

        val isPng = data[0] == 0x89.toByte() && data[1] == 0x50.toByte() && data[2] == 0x4E.toByte() && data[3] == 0x47.toByte()
        val isJpeg = data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        val isGif = data[0] == 0x47.toByte() && data[1] == 0x49.toByte() && data[2] == 0x46.toByte()

        if (!isPng && !isJpeg && !isGif) return 0

        val maxScan = minOf(data.size, 131072)

        val ftyp = byteArrayOf(0x66.toByte(), 0x74.toByte(), 0x79.toByte(), 0x70.toByte())
        val maxFtyp = minOf(data.size - ftyp.size, maxScan)
        for (i in 0..maxFtyp) {
            if (data[i] == ftyp[0] && data[i + 1] == ftyp[1] && data[i + 2] == ftyp[2] && data[i + 3] == ftyp[3]) {
                return if (i >= 4) i - 4 else i
            }
        }

        if (isPng) {
            val iend = byteArrayOf(0x49.toByte(), 0x45.toByte(), 0x4E.toByte(), 0x44.toByte())
            val maxIend = minOf(data.size - iend.size, maxScan)
            for (i in 0..maxIend) {
                if (data[i] == iend[0] && data[i + 1] == iend[1] && data[i + 2] == iend[2] && data[i + 3] == iend[3]) {
                    if (i + 8 <= data.size) return i + 8
                }
            }
        }

        val maxTs = minOf(data.size - 188 * 2, maxScan)
        for (i in 0..maxTs) {
            if (data[i] == 0x47.toByte()) {
                var validCount = 0
                val limit = minOf(data.size, i + 188 * 4)
                var j = i
                while (j < limit) {
                    if (data[j] == 0x47.toByte()) {
                        validCount++
                    }
                    j += 188
                }
                if (validCount >= 3) {
                    return i
                }
            }
        }
        return 0
    }
}

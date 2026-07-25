@file:Suppress("unused")

package eu.kanade.tachiyomi.animeextension.en.fouranimo

import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class FourAnimo : Source() {

    private val m3u8Integration by lazy { M3u8Integration(client) }

    override val name = "Animo"

    override val baseUrl = "https://4animo.xyz"

    override val lang = "en"

    override val supportsLatest = true

    private val imageBaseUrl = "https://cdnanimo.xyz"
    private val embedBaseUrl = "https://cdn.4animo.xyz"

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
        .add("Referer", "$baseUrl/")

    // Preferences
    private val preferredQuality by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)
    private val preferredAudio by preferences.delegate(PREF_AUDIO_KEY, PREF_AUDIO_DEFAULT)
    private val preferredServer by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    // ============================== Helper Utils ==============================
    private fun extractNextPayload(html: String): String {
        val regex = """self\.__next_f\.push\(\[1,\s*"(.*)"\]\)""".toRegex()
        val pushes = regex.findAll(html).map { it.groupValues[1] }.joinToString("")
        return pushes.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun parseAnimeCardList(payload: String): List<SAnime> {
        val animeList = mutableListOf<SAnime>()
        val seen = mutableSetOf<String>()

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

                val metaMatch = """"\(?:name|property\)":"\(?:og:)?description","content":"\([^"]+\)"""".toRegex().find(payload)
                val metaDesc = metaMatch?.groupValues?.get(1) ?: ""

                val descriptionStr = when {
                    rawSynopsis.isNotBlank() && !rawSynopsis.startsWith("$") -> rawSynopsis
                    metaDesc.isNotBlank() -> metaDesc
                    else -> ""
                }

                val cleanDescription = descriptionStr
                    .replace(Regex("(?i)<br\\s*/?>"), "\n")
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace(Regex("\n{3,}"), "\n\n")
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
                        append("\n\n")
                    }
                    if (score != null && score > 0.0) {
                        append("Score: ★ $score\n")
                    }
                    if (formatType.isNotBlank()) {
                        append("Format: $formatType\n")
                    }
                    if (!duration.isNullOrBlank()) {
                        append("Duration: ${duration}m\n")
                    }
                    if (!rating.isNullOrBlank()) {
                        append("Rating: $rating\n")
                    }
                    if (!seasonStr.isNullOrBlank()) {
                        append("Season: $seasonStr\n")
                    }
                    if (!totalEp.isNullOrBlank()) {
                        append("Episodes: $totalEp (Sub: ${subEp ?: totalEp}, Dub: ${dubEp ?: 0})\n")
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

        // 1. ReCloud HD-2 (Server 1: s-1)
        if ("ReCloud HD-2 (Server 1)" !in excludedServers && (canSub || canDub)) {
            val subHd2Url = if (canSub) "$embedBaseUrl/embed/s-1/$embedId/sub?k=1" else ""
            val dubHd2Url = if (canDub) "$embedBaseUrl/embed/s-1/$embedId/dub?k=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "ReCloud HD-2 (Server 1)",
                    hosterUrl = "ReCloud HD-2 (Server 1)|$subHd2Url|$dubHd2Url",
                ),
            )
        }

        // 3. ReCloud (Server 1: a-1)
        if ("ReCloud (Server 1)" !in excludedServers && (canSub || canDub)) {
            val subEmbedUrl = if (canSub) "$embedBaseUrl/embed/a-1/$epId/sub?k=1&autoPlay=1" else ""
            val dubEmbedUrl = if (canDub) "$embedBaseUrl/embed/a-1/$epId/dub?k=1&autoPlay=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "ReCloud (Server 1)",
                    hosterUrl = "ReCloud (Server 1)|$subEmbedUrl|$dubEmbedUrl",
                ),
            )
        }

        // 4. ReCloud (Server 2: a-2)
        if ("ReCloud (Server 2)" !in excludedServers && (canSub || canDub)) {
            val subEmbedUrl = if (canSub) "$embedBaseUrl/embed/a-2/$epId/sub?k=1&autoPlay=1" else ""
            val dubEmbedUrl = if (canDub) "$embedBaseUrl/embed/a-2/$epId/dub?k=1&autoPlay=1" else ""
            hosters.add(
                Hoster(
                    hosterName = "ReCloud (Server 2)",
                    hosterUrl = "ReCloud (Server 2)|$subEmbedUrl|$dubEmbedUrl",
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
        m3u8Integration.processVideoList(videos.sortVideos())
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
            entries = listOf("Auto", "ReCloud HD-2", "ReCloud (Server 1)", "ReCloud (Server 2)"),
            entryValues = listOf("auto", "ReCloud HD-2", "ReCloud (Server 1)", "ReCloud (Server 2)"),
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude servers",
            summary = "Select servers to exclude from episode hosters",
            entries = listOf("ReCloud HD-2", "ReCloud (Server 1)", "ReCloud (Server 2)"),
            entryValues = listOf("ReCloud HD-2 (Server 1)", "ReCloud (Server 1)", "ReCloud (Server 2)"),
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
        private const val PREF_SERVER_DEFAULT = "ReCloud HD-2 (Server 1)"

        private const val PREF_EXCLUDE_SERVERS_KEY = "pref_exclude_servers"
        private const val PREF_EXCLUDE_AUDIO_KEY = "pref_exclude_audio"
    }
}

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
import extensions.utils.Source
import extensions.utils.addListPreference
import extensions.utils.addSetPreference
import extensions.utils.asJsoup
import extensions.utils.delegate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder

class FourAnimo : Source() {

    override val name = "Animo"

    override val baseUrl = "https://4animo.xyz"

    override val lang = "en"

    override val supportsLatest = true

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
        return pushes.replace("""\"""", """"""").replace("""\\""", """\""")
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
                val posterUrl = "$embedBaseUrl/poster/$id.jpg"
                animeList.add(
                    SAnime.create().apply {
                        this.title = title
                        setUrlWithoutDomain("/watch/$slug")
                        thumbnail_url = posterUrl
                    }
                )
            }
        }

        // 2. Fallback Card render match: {"href":"/slug","title":"Title", ... "src":"https://cdnanimo.xyz/poster/123.jpg"}
        if (animeList.isEmpty()) {
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
                        }
                    )
                }
            }
        }

        return animeList
    }

    private fun extractTitleFromJson(titlesJson: String): String {
        return runCatching {
            val element = json.parseToJsonElement(titlesJson).jsonObject
            element["english"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: element["romaji"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: element["native"]?.jsonPrimitive?.content ?: "Anime"
        }.getOrDefault("Anime")
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/home", headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)

        // Find mostPopular section or fallback to all parsed cards
        val mostPopularIndex = payload.indexOf("mostPopular")
        val targetPayload = if (mostPopularIndex != -1) {
            payload.substring(mostPopularIndex)
        } else {
            payload
        }

        val cards = parseAnimeCardList(targetPayload)
        return AnimesPage(cards, false)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/home", headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)

        // Find newAdded or latestEpisode section or fallback
        val latestIndex = payload.indexOf("newAdded").takeIf { it != -1 }
            ?: payload.indexOf("latestEpisode").takeIf { it != -1 }
            ?: -1

        val targetPayload = if (latestIndex != -1) {
            payload.substring(latestIndex)
        } else {
            payload
        }

        val cards = parseAnimeCardList(targetPayload)
        return AnimesPage(cards, false)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
            val response = client.newCall(GET("$baseUrl/search?keyword=$encodedQuery", headers)).execute()
            val html = response.body.string()
            val payload = extractNextPayload(html)
            val cards = parseAnimeCardList(payload)
            return AnimesPage(cards, false)
        }

        // Apply filter mapping when query is blank
        var targetUrl = "$baseUrl/home"
        filters.forEach { filter ->
            when (filter) {
                is Filters.SortFilter -> {
                    if (!filter.isDefault()) {
                        val sortValue = filter.toUriPart()
                        targetUrl = "$baseUrl/catalog?sort=$sortValue"
                    }
                }
                is Filters.TypeFilter -> {
                    if (!filter.isDefault()) {
                        val typeValue = filter.toUriPart()
                        targetUrl = "$baseUrl/anime/type/$typeValue"
                    }
                }
                is Filters.StatusFilter -> {
                    if (!filter.isDefault()) {
                        val statusValue = filter.toUriPart()
                        targetUrl = "$baseUrl/catalog?status=$statusValue"
                    }
                }
                is Filters.GenreFilter -> {
                    val selectedGenres = filter.selected()
                    if (selectedGenres.isNotEmpty()) {
                        targetUrl = "$baseUrl/anime/genre/${selectedGenres.first()}"
                    }
                }
                else -> {}
            }
        }

        val response = client.newCall(GET(targetUrl, headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)
        val cards = parseAnimeCardList(payload)
        return AnimesPage(cards, false)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply only when search query is blank"),
        Filters.TypeFilter(),
        Filters.StatusFilter(),
        Filters.SortFilter(),
        Filters.SeasonFilter(),
        Filters.GenreFilter()
    )

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val url = if (anime.url.startsWith("http")) anime.url else "$baseUrl${anime.url}"
        val response = client.newCall(GET(url, headers)).execute()
        val html = response.body.string()
        val payload = extractNextPayload(html)

        val animeObjMatch = """\"anime\":(\{\s*\"id\":.*?\})\s*,\s*\"episodes\"""".toRegex().find(payload)
            ?: """\"anime\":(\{\s*\"id\":.*?\}),""".toRegex().find(payload)

        val updatedAnime = SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            url = anime.url
        }

        if (animeObjMatch != null) {
            val jsonStr = animeObjMatch.groupValues[1]
            runCatching {
                val element = json.parseToJsonElement(jsonStr).jsonObject
                val titlesObj = element["titles"]?.jsonObject
                val engTitle = titlesObj?.get("english")?.jsonPrimitive?.content
                val romajiTitle = titlesObj?.get("romaji")?.jsonPrimitive?.content
                if (!engTitle.isNullOrBlank()) updatedAnime.title = engTitle
                else if (!romajiTitle.isNullOrBlank()) updatedAnime.title = romajiTitle

                val statusStr = element["status"]?.jsonPrimitive?.content ?: ""
                updatedAnime.status = when {
                    statusStr.contains("RELEASING", ignoreCase = true) -> SAnime.ONGOING
                    statusStr.contains("FINISHED", ignoreCase = true) -> SAnime.COMPLETED
                    else -> SAnime.UNKNOWN
                }

                val genresArr = element["genres"]?.jsonArray
                updatedAnime.genre = genresArr?.joinToString { it.jsonPrimitive.content }

                val sourceStr = element["source"]?.jsonPrimitive?.content
                val studioStr = element["studio"]?.jsonPrimitive?.content
                updatedAnime.author = studioStr ?: sourceStr

                val synopsis = element["synopsis"]?.jsonPrimitive?.content ?: ""
                val score = element["score"]?.jsonPrimitive?.content?.toDoubleOrNull()
                val typeStr = element["type"]?.jsonPrimitive?.content ?: ""
                val durationStr = element["duration_min"]?.jsonPrimitive?.content

                updatedAnime.description = buildString {
                    if (score != null && score > 0.0) {
                        val full = (score / 2).toInt().coerceIn(0, 5)
                        append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.2f".format(score)}\n\n")
                    }
                    if (synopsis.isNotBlank()) append(synopsis.replace("$20", ""))
                    if (typeStr.isNotBlank()) append("\n\nFormat: $typeStr")
                    if (statusStr.isNotBlank()) append("\nStatus: $statusStr")
                    if (!studioStr.isNullOrBlank()) append("\nStudio: $studioStr")
                    if (!durationStr.isNullOrBlank()) append("\nDuration: ${durationStr}m")
                }.trim()
            }
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

        val episodesMatch = """\"episodes\":(\[\{.*?\}\])""".toRegex().find(payload)
            ?: return emptyList()

        val jsonStr = episodesMatch.groupValues[1]
        val episodesList = mutableListOf<SEpisode>()

        runCatching {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.forEach { epElem ->
                val epObj = epElem.jsonObject
                val epNum = epObj["number"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                val epId = epObj["id"]?.jsonPrimitive?.content ?: ""
                val embedId = epObj["embed_id"]?.jsonPrimitive?.content ?: epId
                val animeId = epObj["anime_id"]?.jsonPrimitive?.content ?: ""
                val hasSub = epObj["sub"]?.jsonPrimitive?.content == "true"
                val hasDub = epObj["dub"]?.jsonPrimitive?.content == "true"

                val titlesObj = epObj["titles"]?.jsonObject
                val epTitle = titlesObj?.get("en")?.jsonPrimitive?.content
                    ?: titlesObj?.get("romaji")?.jsonPrimitive?.content ?: ""

                val thumbnail = epObj["thumbnail"]?.jsonPrimitive?.content
                    ?: "$embedBaseUrl/episode/$epId.jpg"

                val ep = SEpisode.create().apply {
                    name = if (epTitle.isNotBlank()) "Episode $epNum: $epTitle" else "Episode ${epNum.toInt()}"
                    episode_number = epNum
                    // Encode embed params into url: episode_id|embed_id|anime_id|number|sub|dub
                    url = "$epId|$embedId|$animeId|${epNum.toInt()}|$hasSub|$hasDub"
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
        }

        return episodesList.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val parts = episode.url.split("|")
        if (parts.size < 6) return emptyList()

        val epId = parts[0]
        val embedId = parts[1]
        val animeId = parts[2]
        val epNum = parts[3]
        val hasSub = parts[4] == "true"
        val hasDub = parts[5] == "true"

        val excludedServers = preferences.getStringSet(PREF_EXCLUDE_SERVERS_KEY, emptySet()) ?: emptySet()
        val excludedAudios = preferences.getStringSet(PREF_EXCLUDE_AUDIO_KEY, emptySet()) ?: emptySet()

        val hosters = mutableListOf<Hoster>()

        // 1. Sub Embed URL
        if (hasSub && "SUB" !in excludedAudios && "ReCloud" !in excludedServers) {
            val subEmbedUrl = "$embedBaseUrl/embed/a-1/$epId/sub?k=1&autoPlay=1"
            hosters.add(
                Hoster(
                    hosterName = "ReCloud (SUB)",
                    hosterUrl = "SUB|$subEmbedUrl"
                )
            )
        }

        // 2. Dub Embed URL
        if (hasDub && "DUB" !in excludedAudios && "ReCloud" !in excludedServers) {
            val dubEmbedUrl = "$embedBaseUrl/embed/a-1/$epId/dub?k=1&autoPlay=1"
            hosters.add(
                Hoster(
                    hosterName = "ReCloud (DUB)",
                    hosterUrl = "DUB|$dubEmbedUrl"
                )
            )
        }

        // 3. HD-2 / HD-3 Server Fallbacks
        if ("ReCloud HD-2" !in excludedServers) {
            if (hasSub && "SUB" !in excludedAudios) {
                val subHd2Url = "$embedBaseUrl/embed/s-1/$embedId/sub?k=1"
                hosters.add(Hoster(hosterName = "ReCloud HD-2 (SUB)", hosterUrl = "SUB|$subHd2Url"))
            }
            if (hasDub && "DUB" !in excludedAudios) {
                val dubHd2Url = "$embedBaseUrl/embed/s-1/$embedId/dub?k=1"
                hosters.add(Hoster(hosterName = "ReCloud HD-2 (DUB)", hosterUrl = "DUB|$dubHd2Url"))
            }
        }

        return sortHostersByPreference(hosters)
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val parts = hoster.hosterUrl.split("|")
        val audioPrefix = parts.getOrNull(0) ?: "SUB"
        val embedUrl = parts.getOrNull(1) ?: return emptyList()

        return runCatching {
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

            val m3u8Url = jsonRoot["sources"]?.jsonArray?.getOrNull(0)?.jsonObject?.get("file")?.jsonPrimitive?.content
                ?: jsonRoot["file"]?.jsonPrimitive?.content
                ?: return emptyList()

            val subtitleTracks = mutableListOf<Track>()
            jsonRoot["tracks"]?.jsonArray?.forEach { trackElem ->
                val trackObj = trackElem.jsonObject
                val kind = trackObj["kind"]?.jsonPrimitive?.content
                if (kind == "captions" || kind == "subtitles") {
                    val file = trackObj["file"]?.jsonPrimitive?.content ?: return@forEach
                    val label = trackObj["label"]?.jsonPrimitive?.content ?: "Subtitles"
                    subtitleTracks.add(Track(file, label))
                }
            }

            // Extract stream qualities from M3U8 index
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
                        val streamUrl = if (line.startsWith("http")) line else {
                            val baseM3u8 = m3u8Url.substringBeforeLast("/")
                            "$baseM3u8/$line"
                        }
                        videos.add(
                            Video(
                                videoUrl = streamUrl,
                                videoTitle = "$audioPrefix - ${hoster.hosterName} - $currentQuality",
                                headers = headers.newBuilder().set("Referer", embedUrl).build(),
                                subtitleTracks = subtitleTracks
                            )
                        )
                    }
                }
            }

            if (videos.isEmpty()) {
                videos.add(
                    Video(
                        videoUrl = m3u8Url,
                        videoTitle = "$audioPrefix - ${hoster.hosterName}",
                        headers = headers.newBuilder().set("Referer", embedUrl).build(),
                        subtitleTracks = subtitleTracks
                    )
                )
            }

            videos.sortVideos()
        }.getOrDefault(emptyList())
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferredQuality
        val prefAudio = preferredAudio

        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefAudio, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
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
            entryValues = listOf("1080", "720", "480", "360")
        )

        screen.addListPreference(
            key = PREF_AUDIO_KEY,
            default = PREF_AUDIO_DEFAULT,
            title = "Preferred audio type",
            summary = "Prioritizes Sub or Dub audio streams. Currently: %s",
            entries = listOf("Subtitled", "Dubbed"),
            entryValues = listOf("SUB", "DUB")
        )

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "Prioritizes this server in the host list. Currently: %s",
            entries = listOf("Auto", "ReCloud", "ReCloud HD-2"),
            entryValues = listOf("auto", "ReCloud", "ReCloud HD-2")
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_SERVERS_KEY,
            default = emptySet(),
            title = "Exclude servers",
            summary = "Select servers to exclude from episode hosters",
            entries = listOf("ReCloud", "ReCloud HD-2"),
            entryValues = listOf("ReCloud", "ReCloud HD-2")
        )

        screen.addSetPreference(
            key = PREF_EXCLUDE_AUDIO_KEY,
            default = emptySet(),
            title = "Exclude audio format",
            summary = "Select audio formats to exclude",
            entries = listOf("Subtitled", "Dubbed"),
            entryValues = listOf("SUB", "DUB")
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

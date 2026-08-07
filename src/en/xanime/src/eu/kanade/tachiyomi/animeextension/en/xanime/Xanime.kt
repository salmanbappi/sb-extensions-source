package eu.kanade.tachiyomi.animeextension.en.xanime

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class Xanime : Source() {

    override val name = "Xanime"

    override val baseUrl: String
        get() = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT)
            ?.trimEnd('/') ?: PREF_BASE_URL_DEFAULT

    override val lang = "en"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder() = super.headersBuilder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
        )
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/search?sortby=field_popularity&page=$page", headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/search?page=$page", headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val urlBuilder = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder().apply {
                addQueryParameter("word", query)
            }
        } else {
            val sortFilter = filters.filterIsInstance<Filters.SortFilter>().firstOrNull()
            val sortValue = sortFilter?.getSelectedValue() ?: ""

            val genreFilter = filters.filterIsInstance<Filters.GenreFilter>().firstOrNull()
            val selectedGenres = genreFilter?.getSelected() ?: emptyList()

            if (selectedGenres.size == 1 && sortValue.isBlank()) {
                "$baseUrl/latest/${selectedGenres.first()}".toHttpUrl().newBuilder()
            } else {
                "$baseUrl/search".toHttpUrl().newBuilder().apply {
                    if (sortValue.isNotBlank()) {
                        addQueryParameter("sortby", sortValue)
                    }
                    if (selectedGenres.isNotEmpty()) {
                        addQueryParameter("genre", selectedGenres.joinToString(","))
                    }
                }
            }
        }

        urlBuilder.addQueryParameter("page", page.toString())

        val response = client.newCall(GET(urlBuilder.build(), headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList() = Filters.getFilterList()

    private fun parseAnimeListPage(response: Response, defaultPage: Int): AnimesPage {
        val html = response.body.string()
        val qwikMatch = QWIK_JSON_REGEX.find(html)?.groupValues?.get(1)
        val animeList = mutableListOf<SAnime>()
        var totalPages = 1
        var currentPage = defaultPage

        if (!qwikMatch.isNullOrBlank()) {
            runCatching {
                val jsonElement = json.parseToJsonElement(qwikMatch).jsonObject
                val objs = jsonElement["objs"]?.jsonArray ?: return@runCatching

                for (element in objs) {
                    val obj = element as? JsonObject ?: continue

                    if (obj.containsKey("pages") && obj.containsKey("page")) {
                        val tot = obj["pages"]?.jsonPrimitive?.intOrNull
                            ?: getJsonString(obj["pages"])?.let { resolveString(obj["pages"], objs)?.toIntOrNull() }
                        val cur = obj["page"]?.jsonPrimitive?.intOrNull
                            ?: getJsonString(obj["page"])?.let { resolveString(obj["page"], objs)?.toIntOrNull() }
                        if (tot != null && tot > 0) totalPages = tot
                        if (cur != null && cur > 0) currentPage = cur
                    }

                    if (obj.containsKey("aniPath") && obj.containsKey("info_title")) {
                        val titleStr = resolveString(obj["info_title"], objs) ?: continue
                        val pathStr = resolveString(obj["aniPath"], objs) ?: continue
                        val coverStr = resolveString(obj["urlCover600"], objs)
                            ?: resolveString(obj["urlCoverOri"], objs)
                            ?: ""

                        if (titleStr.isNotBlank() && pathStr.contains("/title/")) {
                            val anime = SAnime.create().apply {
                                title = titleStr
                                setUrlWithoutDomain(pathStr)
                                thumbnail_url = fixCoverUrl(coverStr)
                                fetch_type = FetchType.Episodes
                            }
                            animeList.add(anime)
                        }
                    }
                }
            }
        }

        if (animeList.isEmpty()) {
            val doc = Jsoup.parse(html, response.request.url.toString())
            doc.select("a[href*=/title/]").forEach { a ->
                val href = a.attr("href")
                if (href.contains("/title/") && !href.contains("/episode-")) {
                    val titleText = a.selectFirst("h3, h4, .title, span")?.text() ?: a.text()
                    val img = a.selectFirst("img")?.attr("abs:src")
                    if (titleText.isNotBlank() && animeList.none { it.url == href }) {
                        animeList.add(
                            SAnime.create().apply {
                                title = titleText.trim()
                                setUrlWithoutDomain(href)
                                thumbnail_url = img
                                fetch_type = FetchType.Episodes
                            },
                        )
                    }
                }
            }
        }

        val distinctList = animeList.distinctBy { it.url }
        val hasNext = currentPage < totalPages && distinctList.isNotEmpty()
        return AnimesPage(distinctList, hasNextPage = hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val html = response.body.string()
        val qwikMatch = QWIK_JSON_REGEX.find(html)?.groupValues?.get(1)

        val details = SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            setUrlWithoutDomain(anime.url)
            initialized = true
        }

        if (!qwikMatch.isNullOrBlank()) {
            runCatching {
                val jsonElement = json.parseToJsonElement(qwikMatch).jsonObject
                val objs = jsonElement["objs"]?.jsonArray ?: return@runCatching

                for (element in objs) {
                    val obj = element as? JsonObject ?: continue
                    if (obj.containsKey("info_filmdesc") || obj.containsKey("info_meta_status")) {
                        val desc = resolveString(obj["info_filmdesc"], objs) ?: ""
                        val statusRaw = resolveString(obj["info_meta_status"], objs) ?: ""
                        val year = resolveString(obj["info_meta_year"], objs) ?: ""
                        val duration = resolveString(obj["info_meta_duration"], objs) ?: ""
                        val rating = resolveString(obj["info_meta_rating_short"], objs)
                            ?: resolveString(obj["info_meta_rating"], objs) ?: ""
                        val score = obj["info_meta_scores"]?.jsonPrimitive?.doubleOrNull

                        details.status = when {
                            statusRaw.contains("airing", ignoreCase = true) -> SAnime.ONGOING
                            statusRaw.contains("finished", ignoreCase = true) -> SAnime.COMPLETED
                            else -> SAnime.UNKNOWN
                        }

                        details.description = buildString {
                            if (score != null && score > 0.0) {
                                val stars = (score / 20.0 * 5.0).toInt().coerceIn(0, 5)
                                append("${"★".repeat(stars)}${"☆".repeat(5 - stars)} ${"%.1f".format(score / 10.0)}/10\n\n")
                            }
                            if (desc.isNotBlank()) append(desc.trim())
                            if (year.isNotBlank()) append("\n\nYear: $year")
                            if (duration.isNotBlank()) append("\nDuration: $duration")
                            if (rating.isNotBlank()) append("\nRating: $rating")
                        }.trim()
                        break
                    }
                }
            }
        }

        if (details.description.isNullOrBlank()) {
            val doc = Jsoup.parse(html, "$baseUrl${anime.url}")
            details.description = doc.selectFirst("p, div.description, .prose")?.text() ?: ""
        }

        return details
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val detailResponse = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val detailHtml = detailResponse.body.string()

        var sampleEpPath: String? = null
        val detailQwik = QWIK_JSON_REGEX.find(detailHtml)?.groupValues?.get(1)
        if (!detailQwik.isNullOrBlank()) {
            runCatching {
                val objs = json.parseToJsonElement(detailQwik).jsonObject["objs"]?.jsonArray ?: return@runCatching
                for (element in objs) {
                    val obj = element as? JsonObject ?: continue
                    if (obj.containsKey("epPath")) {
                        val path = resolveString(obj["epPath"], objs)
                        if (!path.isNullOrBlank() && path.contains("/title/")) {
                            sampleEpPath = path
                            break
                        }
                    }
                }
            }
        }

        val targetEpUrl = if (!sampleEpPath.isNullOrBlank()) {
            "$baseUrl$sampleEpPath"
        } else {
            "$baseUrl${anime.url}"
        }

        val epResponse = client.newCall(GET(targetEpUrl, headers)).execute()
        val epHtml = epResponse.body.string()
        val epQwik = QWIK_JSON_REGEX.find(epHtml)?.groupValues?.get(1)

        val episodeList = mutableListOf<SEpisode>()

        if (!epQwik.isNullOrBlank()) {
            runCatching {
                val objs = json.parseToJsonElement(epQwik).jsonObject["objs"]?.jsonArray ?: return@runCatching

                for (element in objs) {
                    val obj = element as? JsonObject ?: continue
                    if (obj.containsKey("epPath") && (obj.containsKey("ep_title") || obj.containsKey("ep_index"))) {
                        val epPath = resolveString(obj["epPath"], objs) ?: continue
                        if (!epPath.contains("/title/") || !epPath.contains("-episode-")) continue

                        val rawTitle = resolveString(obj["ep_title"], objs)
                        val rawIndex = obj["ep_index"]?.jsonPrimitive?.floatOrNull
                            ?: getJsonString(obj["ep_index"])?.toFloatOrNull()
                            ?: 0f

                        val epName = if (!rawTitle.isNullOrBlank()) rawTitle else "Episode ${rawIndex.toInt()}"

                        val ep = SEpisode.create().apply {
                            name = epName
                            setUrlWithoutDomain(epPath)
                            episode_number = rawIndex

                            val dateCreate = obj["date_create"]?.jsonPrimitive?.longOrNull
                            if (dateCreate != null && dateCreate > 0L) {
                                date_upload = dateCreate
                            }
                        }
                        episodeList.add(ep)
                    }
                }
            }
        }

        if (episodeList.isEmpty()) {
            val doc = Jsoup.parse(epHtml, targetEpUrl)
            doc.select("a[href*=/title/][href*=-episode-]").forEach { a ->
                val href = a.attr("href")
                val titleText = a.text().trim()
                if (href.isNotBlank() && episodeList.none { it.url == href }) {
                    episodeList.add(
                        SEpisode.create().apply {
                            name = if (titleText.isNotBlank()) titleText else "Episode"
                            setUrlWithoutDomain(href)
                            episode_number = name.substringAfter("Episode ").toFloatOrNull() ?: 0f
                        },
                    )
                }
            }
        }

        return episodeList.distinctBy { it.url }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).execute()
        val html = response.body.string()
        val qwikMatch = QWIK_JSON_REGEX.find(html)?.groupValues?.get(1) ?: return emptyList()

        val excludedTypes = preferences.getStringSet(PREF_EXCLUDE_TYPE_KEY, emptySet()) ?: emptySet()
        val hosterList = mutableListOf<Hoster>()

        runCatching {
            val objs = json.parseToJsonElement(qwikMatch).jsonObject["objs"]?.jsonArray ?: return emptyList()

            for (element in objs) {
                val obj = element as? JsonObject ?: continue
                if (obj.containsKey("souPath")) {
                    val souPath = resolveString(obj["souPath"], objs) ?: continue
                    if (!souPath.contains("m3u8")) continue

                    val srcType = (resolveString(obj["src_type"], objs) ?: "SUB").uppercase()
                    val srcName = resolveString(obj["src_name"], objs) ?: "1"

                    if (excludedTypes.any { it.equals(srcType, ignoreCase = true) }) continue

                    val name = "$srcType - Server $srcName"
                    hosterList.add(Hoster(hosterName = name, hosterUrl = souPath))
                }
            }
        }

        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        return hosterList.sortedByDescending { it.hosterName.startsWith(prefType, ignoreCase = true) }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = playlistUtils.extractFromHls(
        playlistUrl = hoster.hosterUrl,
        referer = "$baseUrl/",
        masterHeaders = headers,
        videoHeaders = headers,
        videoNameGen = { quality -> "${hoster.hosterName} - $quality" },
    )

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        val prefType = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT) ?: PREF_TYPE_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.startsWith(prefType, ignoreCase = true) }
                .thenByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution },
        )
    }

    // ============================ Recommendations ========================
    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val html = response.body.string()
        val doc = Jsoup.parse(html, response.request.url.toString())
        val currentPath = response.request.url.encodedPath
        return doc.select("a[href*=/title/]").mapNotNull { a ->
            val href = a.attr("href")
            if (href.contains("/title/") && !href.contains("/episode-") && href != currentPath) {
                val titleText = a.selectFirst("h3, h4, .title, span")?.text() ?: a.text()
                val img = a.selectFirst("img")?.attr("abs:src")
                if (titleText.isNotBlank()) {
                    SAnime.create().apply {
                        title = titleText.trim()
                        setUrlWithoutDomain(href)
                        thumbnail_url = img
                        fetch_type = FetchType.Episodes
                    }
                } else {
                    null
                }
            } else {
                null
            }
        }.distinctBy { it.url }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_BASE_URL_KEY,
            title = "Site Domain",
            default = PREF_BASE_URL_DEFAULT,
            summary = "%s",
            entries = listOf("https://xanime.me", "https://xanime.app"),
            entryValues = listOf("https://xanime.me", "https://xanime.app"),
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
        )
        screen.addListPreference(
            key = PREF_TYPE_KEY,
            title = "Preferred Type",
            default = PREF_TYPE_DEFAULT,
            summary = "%s",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
        )
        screen.addSetPreference(
            key = PREF_EXCLUDE_TYPE_KEY,
            title = "Exclude Audio Types",
            summary = "Select audio formats to hide",
            entries = listOf("Sub", "Dub"),
            entryValues = listOf("SUB", "DUB"),
            default = emptySet(),
        )
    }

    private fun getJsonString(element: JsonElement?): String? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else primitive.contentOrNull
    }

    private fun resolveRef(ref: JsonElement?, objs: List<JsonElement>): JsonElement? {
        val strVal = getJsonString(ref) ?: return ref
        val idx = strVal.toIntOrNull(36) ?: return ref
        return if (idx in 0 until objs.size) objs[idx] else ref
    }

    private fun resolveString(ref: JsonElement?, objs: List<JsonElement>): String? {
        val resolved = resolveRef(ref, objs)
        return getJsonString(resolved)
    }

    private fun fixCoverUrl(url: String): String {
        if (url.isBlank()) return ""
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> "$baseUrl/$url"
        }
    }

    companion object {
        private const val PREF_BASE_URL_KEY = "pref_base_url"
        private const val PREF_BASE_URL_DEFAULT = "https://xanime.me"
        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private const val PREF_TYPE_KEY = "pref_type"
        private const val PREF_TYPE_DEFAULT = "SUB"
        private const val PREF_EXCLUDE_TYPE_KEY = "pref_exclude_type"

        private val QWIK_JSON_REGEX = Regex("""(?s)<script[^>]*type="qwik/json"[^>]*>(.*?)</script>""")
    }
}

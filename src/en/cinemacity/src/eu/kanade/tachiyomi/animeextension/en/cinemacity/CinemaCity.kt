package eu.kanade.tachiyomi.animeextension.en.cinemacity

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

class CinemaCity : Source() {

    override val name = "CinemaCity"

    override val baseUrl = "https://cinemacity.cc"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor(CloudflareInterceptor(network.client))
            .build()
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder {
        val userAgent = preferences.getString(PREF_USER_AGENT_KEY, DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        return super.headersBuilder()
            .add("User-Agent", userAgent)
            .add("Referer", "$baseUrl/")
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val pageUrl = if (page > 1) "$baseUrl/f/sort=news_read/order=desc/page/$page/" else "$baseUrl/f/sort=news_read/order=desc/"
        val response = client.newCall(GET(pageUrl, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val pageUrl = if (page > 1) "$baseUrl/f/sort=date/order=desc/page/$page/" else "$baseUrl/f/sort=date/order=desc/"
        val response = client.newCall(GET(pageUrl, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val formBody = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("story", query)
                .build()

            val request = POST("$baseUrl/index.php?do=search", headers, formBody)
            val response = client.newCall(request).execute()
            return parseAnimeListPage(response, page)
        }

        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected ?: "movies"
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected
        val qualityFilter = filters.filterIsInstance<QualityFilter>().firstOrNull()?.selected
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected
        val yearFilter = filters.filterIsInstance<YearFilter>().firstOrNull()?.state?.trim() ?: ""

        val genreGroup = filters.filterIsInstance<GenreGroup>().firstOrNull()
        val selectedGenres = genreGroup?.state?.filter { it.state }?.map { it.slug }?.joinToString(",") ?: ""

        val targetUrl = when {
            selectedGenres.isNotBlank() || yearFilter.isNotBlank() || !sortFilter.isNullOrBlank() || !qualityFilter.isNullOrBlank() || !statusFilter.isNullOrBlank() -> {
                val catId = if (typeFilter == "tv-series") "2" else "1"
                buildString {
                    append("$baseUrl/f/cat=$catId/")
                    if (selectedGenres.isNotBlank()) append("genre=$selectedGenres/")
                    if (yearFilter.isNotBlank()) append("year=$yearFilter/")
                    if (!sortFilter.isNullOrBlank()) append("$sortFilter/")
                    if (!qualityFilter.isNullOrBlank()) append("quality=$qualityFilter/")
                    if (!statusFilter.isNullOrBlank()) append("status=$statusFilter/")
                    if (page > 1) append("page/$page/")
                }
            }

            else -> {
                if (page > 1) "$baseUrl/$typeFilter/page/$page/" else "$baseUrl/$typeFilter/"
            }
        }

        val response = client.newCall(GET(targetUrl, headers)).execute()
        return parseAnimeListPage(response, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
        YearFilter(),
        QualityFilter(),
        StatusFilter(),
        GenreGroup(),
    )

    private fun parseAnimeListPage(response: Response, page: Int = 1): AnimesPage {
        val doc = response.asJsoup()
        val animes = mutableListOf<SAnime>()

        // Target main content area (#dle-content) to exclude top carousel/header items (.dle-fast_item)
        val mainContent = doc.selectFirst("#dle-content, .dle-content") ?: doc

        val containers = mainContent.select("div[class*=\"dar-short_item\"], div[class*=\"short-story\"], div[class*=\"_item\"]")
        val targetElements = if (containers.isNotEmpty()) containers else mainContent.select("a[href*=\"/movies/\"], a[href*=\"/tv-series/\"]")

        for (element in targetElements) {
            val linkEl = if (element.tagName() == "a") element else element.selectFirst("a[href*=\"/movies/\"], a[href*=\"/tv-series/\"]") ?: continue
            val href = linkEl.attr("href")
            if (!href.endsWith(".html") || href.contains("#watch")) continue

            val itemTitle = linkEl.text().ifBlank { element.selectFirst("img")?.attr("alt") ?: "" }
            if (itemTitle.isBlank()) continue

            val imgEl = element.selectFirst("img") ?: element.parent()?.selectFirst("img")

            animes.add(
                SAnime.create().apply {
                    title = itemTitle
                    setUrlWithoutDomain(href)
                    thumbnail_url = imgEl?.absUrl("src")
                    fetch_type = FetchType.Episodes
                },
            )
        }

        val distinctAnimes = animes.distinctBy { it.url }

        val nextPageNum = page + 1
        val hasNext = doc.select(".navigation a, .pagination a, a[href*=\"/page/\"]").any { link ->
            val href = link.attr("href")
            href.contains("/page/$nextPageNum/") || link.text().trim() == ">" || link.text().contains("Next", ignoreCase = true)
        }

        return AnimesPage(distinctAnimes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val doc = client.newCall(GET("$baseUrl${anime.url}", headers)).execute().asJsoup()

        val descriptionText = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".dle-comm_text, .ta-full_desc, .synopsis")?.text() ?: ""
        val genres = doc.select("a[href*=\"/genre/\"]").map { it.text() }.distinct().joinToString(", ")

        return SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            genre = genres.ifBlank { null }
            description = descriptionText.trim()
            fetch_type = FetchType.Episodes
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val html = response.body.string()

        val episodes = mutableListOf<SEpisode>()

        // Look for PlayerJS eval(atob("...")) script
        val atobMatches = ATOB_REGEX.findAll(html)
        for (match in atobMatches) {
            val b64 = match.groupValues[1]
            val decoded = runCatching {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull() ?: continue

            if ("file:" !in decoded) continue

            // Extract subtitle parameter if present
            val subStr = SUBTITLE_PARAM_REGEX.find(decoded)?.groupValues?.get(1) ?: ""

            val fileContent = decoded.substringAfter("file:").substringBefore(", poster:").substringBefore(", default_quality:").trim('\'', '"', ' ')

            if (fileContent.startsWith("[")) {
                // TV Series or Movie playlist JSON
                runCatching {
                    val jsonArray = json.parseToJsonElement(fileContent).jsonArray
                    var epNum = 1.0f
                    for (itemObj in jsonArray) {
                        val itemTitle = itemObj.jsonObject["title"]?.jsonPrimitive?.content ?: ""
                        val folder = itemObj.jsonObject["folder"]?.jsonArray
                        val directFile = itemObj.jsonObject["file"]?.jsonPrimitive?.content

                        if (folder != null) {
                            // TV Series (Seasons & Episodes)
                            for (epObj in folder) {
                                val epTitle = epObj.jsonObject["title"]?.jsonPrimitive?.content ?: "Episode"
                                val streamUrl = epObj.jsonObject["file"]?.jsonPrimitive?.content ?: continue

                                episodes.add(
                                    SEpisode.create().apply {
                                        name = "$itemTitle $epTitle".trim()
                                        url = packEpisodeUrl(streamUrl, subStr)
                                        episode_number = epNum++
                                    },
                                )
                            }
                        } else if (!directFile.isNullOrBlank()) {
                            // Movie in JSON
                            episodes.add(
                                SEpisode.create().apply {
                                    name = if (itemTitle.isNotBlank()) itemTitle else anime.title
                                    url = packEpisodeUrl(directFile, subStr)
                                    episode_number = 1.0f
                                },
                            )
                        }
                    }
                }
            } else if (fileContent.contains(".m3u8")) {
                // Single Movie stream
                episodes.add(
                    SEpisode.create().apply {
                        name = anime.title
                        url = packEpisodeUrl(fileContent, subStr)
                        episode_number = 1.0f
                    },
                )
            }
        }

        // Fallback regex for direct m3u8 if playerjs atob wasn't matched
        if (episodes.isEmpty()) {
            val directM3u8 = M3U8_REGEX.find(html)?.groupValues?.get(1)
            if (!directM3u8.isNullOrBlank()) {
                episodes.add(
                    SEpisode.create().apply {
                        name = anime.title
                        url = directM3u8
                        episode_number = 1.0f
                    },
                )
            }
        }

        if (episodes.isEmpty()) {
            throw Exception("No episodes available for this title on CinemaCity")
        }

        return episodes.reversed()
    }

    private fun packEpisodeUrl(streamUrl: String, subStr: String): String = if (subStr.isNotBlank()) {
        "{\"url\":\"$streamUrl\",\"subs\":\"${subStr.replace("\"", "\\\"")}\"}"
    } else {
        streamUrl
    }

    private fun parseSubtitles(subStr: String): List<Track> {
        if (subStr.isBlank()) return emptyList()
        val regex = Regex("""\[([^\]]+)\](https?:[^\s,]+)""")
        val cleaned = subStr.replace("\\/", "/")
        return regex.findAll(cleaned).mapNotNull { match ->
            val lang = match.groupValues[1]
            val url = match.groupValues[2]
            if (url.isBlank()) null else Track(url, lang)
        }.toList()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val rawUrl = episode.url
        if (rawUrl.isBlank()) return emptyList()

        var masterUrl = rawUrl
        var subtitleTracks = emptyList<Track>()

        if (rawUrl.startsWith("{")) {
            runCatching {
                val jsonObj = json.parseToJsonElement(rawUrl).jsonObject
                masterUrl = jsonObj["url"]?.jsonPrimitive?.content ?: rawUrl
                val subStr = jsonObj["subs"]?.jsonPrimitive?.content ?: ""
                subtitleTracks = parseSubtitles(subStr)
            }
        }

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = "$baseUrl/",
            masterHeaders = headers,
            videoHeaders = headers,
            videoNameGen = { quality -> "CinemaCity - $quality" },
            subtitleList = subtitleTracks,
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred Quality",
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
            entries = listOf("1080p", "720p", "360p"),
            entryValues = listOf("1080p", "720p", "360p"),
        )
        screen.addEditTextPreference(
            key = PREF_USER_AGENT_KEY,
            title = "User-Agent Header",
            summary = "Custom User-Agent header for Cloudflare",
            default = DEFAULT_USER_AGENT,
        )
    }

    companion object {
        private val ATOB_REGEX = Regex("""eval\(atob\("([^"]+)"\)\)""")
        private val M3U8_REGEX = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")
        private val SUBTITLE_PARAM_REGEX = Regex("""["']?subtitle["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_USER_AGENT_KEY = "pref_user_agent"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
    }
}

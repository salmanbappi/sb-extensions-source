package eu.kanade.tachiyomi.animeextension.en.cinemacity

import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.UrlUtils
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Document

class CinemaCity : Source() {

    override val name = "CinemaCity"

    override val baseUrl = "https://cinemacity.cc"

    override val lang = "en"

    override val supportsLatest = true

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder {
        val userAgent = preferences.getString(PREF_USER_AGENT_KEY, DEFAULT_USER_AGENT) ?: DEFAULT_USER_AGENT
        val cfClearance = preferences.getString(PREF_CF_CLEARANCE_KEY, "") ?: ""
        val phpSessId = preferences.getString(PREF_PHPSESSID_KEY, "") ?: ""
        val ccDgDevice = preferences.getString(PREF_CC_DG_DEVICE_KEY, "") ?: ""

        val cookieHeader = buildString {
            if (ccDgDevice.isNotBlank()) append("cc_dg_device=$ccDgDevice; ")
            if (cfClearance.isNotBlank()) append("cf_clearance=$cfClearance; ")
            if (phpSessId.isNotBlank()) append("PHPSESSID=$phpSessId; ")
        }.trimEnd(' ', ';')

        return super.headersBuilder()
            .add("User-Agent", userAgent)
            .add("Referer", "$baseUrl/")
            .apply {
                if (cookieHeader.isNotBlank()) {
                    add("Cookie", cookieHeader)
                }
            }
    }

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val pageUrl = if (page > 1) "$baseUrl/movies/page/$page/" else "$baseUrl/movies/"
        val response = client.newCall(GET(pageUrl, headers)).execute()
        return parseAnimeListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val pageUrl = if (page > 1) "$baseUrl/f/sort=date/order=desc/page/$page/" else "$baseUrl/f/sort=date/order=desc/"
        val response = client.newCall(GET(pageUrl, headers)).execute()
        return parseAnimeListPage(response)
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
            return parseAnimeListPage(response)
        }

        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected ?: "movies"
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()?.selected
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected
        val yearFilter = filters.filterIsInstance<YearFilter>().firstOrNull()?.selected

        val targetUrl = when {
            !genreFilter.isNullOrBlank() -> {
                if (page > 1) "$baseUrl/genre/$genreFilter/page/$page/" else "$baseUrl/genre/$genreFilter/"
            }

            !yearFilter.isNullOrBlank() -> {
                if (page > 1) "$baseUrl/year/$yearFilter/page/$page/" else "$baseUrl/year/$yearFilter/"
            }

            !sortFilter.isNullOrBlank() -> {
                if (page > 1) "$baseUrl/f/$sortFilter/page/$page/" else "$baseUrl/f/$sortFilter/"
            }

            else -> {
                if (page > 1) "$baseUrl/$typeFilter/page/$page/" else "$baseUrl/$typeFilter/"
            }
        }

        val response = client.newCall(GET(targetUrl, headers)).execute()
        return parseAnimeListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        SortFilter(),
        GenreFilter(),
        YearFilter(),
    )

    private fun parseAnimeListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = mutableListOf<SAnime>()

        // Strategy 1: Container-based parsing (.dle-fast_item, .dar-short_item, etc.)
        val containers = doc.select("div[class*=\"dle-fast_item\"], div[class*=\"dar-short_item\"], div[class*=\"short-story\"], div[class*=\"_item\"]")
        if (containers.isNotEmpty()) {
            for (element in containers) {
                val linkEl = element.selectFirst("a[href*=\"/movies/\"], a[href*=\"/tv-series/\"]") ?: continue
                val href = linkEl.attr("href")
                if (!href.endsWith(".html") || href.contains("#watch")) continue

                val itemTitle = linkEl.text().ifBlank { element.selectFirst("img")?.attr("alt") ?: "" }
                if (itemTitle.isBlank()) continue

                val imgEl = element.selectFirst("img")

                animes.add(
                    SAnime.create().apply {
                        title = itemTitle
                        setUrlWithoutDomain(href)
                        thumbnail_url = imgEl?.absUrl("src")
                        fetch_type = FetchType.Episodes
                    },
                )
            }
        }

        // Strategy 2: Direct link lookup fallback (search / custom templates)
        if (animes.isEmpty()) {
            doc.select("a[href*=\"/movies/\"], a[href*=\"/tv-series/\"]").forEach { element ->
                val href = element.attr("href")
                if (!href.endsWith(".html") || href.contains("#watch")) return@forEach

                val itemTitle = element.text().ifBlank { element.selectFirst("img")?.attr("alt") ?: "" }
                if (itemTitle.isBlank()) return@forEach

                val imgEl = element.parent()?.selectFirst("img") ?: element.selectFirst("img")

                animes.add(
                    SAnime.create().apply {
                        title = itemTitle
                        setUrlWithoutDomain(href)
                        thumbnail_url = imgEl?.absUrl("src")
                        fetch_type = FetchType.Episodes
                    },
                )
            }
        }

        val distinctAnimes = animes.distinctBy { it.url }
        val hasNext = doc.select("a[href*=\"/page/\"]").any { it.text().contains("Next", ignoreCase = true) || it.text() == ">" }
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
                                        url = streamUrl
                                        episode_number = epNum++
                                    },
                                )
                            }
                        } else if (!directFile.isNullOrBlank()) {
                            // Movie in JSON
                            episodes.add(
                                SEpisode.create().apply {
                                    name = if (itemTitle.isNotBlank()) itemTitle else anime.title
                                    url = directFile
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
                        url = fileContent
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

        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val masterUrl = episode.url
        if (masterUrl.isBlank()) return emptyList()

        return playlistUtils.extractFromHls(
            playlistUrl = masterUrl,
            referer = "$baseUrl/",
            masterHeaders = headers,
            videoHeaders = headers,
            videoNameGen = { quality -> "CinemaCity - $quality" },
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
        screen.addEditTextPreference(
            key = PREF_CF_CLEARANCE_KEY,
            title = "cf_clearance Cookie",
            summary = "Custom Cloudflare clearance cookie (leave blank to use WebView cookies)",
            default = "",
        )
        screen.addEditTextPreference(
            key = PREF_PHPSESSID_KEY,
            title = "PHPSESSID Cookie",
            summary = "Custom PHP session ID (leave blank to use WebView cookies)",
            default = "",
        )
        screen.addEditTextPreference(
            key = PREF_CC_DG_DEVICE_KEY,
            title = "cc_dg_device Cookie",
            summary = "Custom CinemaCity device guard (leave blank to use WebView cookies)",
            default = "",
        )
    }

    companion object {
        private val ATOB_REGEX = Regex("""eval\(atob\("([^"]+)"\)\)""")
        private val M3U8_REGEX = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"

        private const val PREF_USER_AGENT_KEY = "pref_user_agent"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

        private const val PREF_CF_CLEARANCE_KEY = "pref_cf_clearance"
        private const val PREF_PHPSESSID_KEY = "pref_phpsessid"
        private const val PREF_CC_DG_DEVICE_KEY = "pref_cc_dg_device"
    }
}

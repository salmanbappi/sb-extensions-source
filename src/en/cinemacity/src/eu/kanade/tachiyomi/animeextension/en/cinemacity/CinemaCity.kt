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
import keiyoushi.utils.addEditTextPreference
import keiyoushi.utils.addListPreference
import keiyoushi.utils.UrlUtils
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
        val cfClearance = preferences.getString(PREF_CF_CLEARANCE_KEY, DEFAULT_CF_CLEARANCE) ?: DEFAULT_CF_CLEARANCE
        val phpSessId = preferences.getString(PREF_PHPSESSID_KEY, DEFAULT_PHPSESSID) ?: DEFAULT_PHPSESSID
        val ccDgDevice = preferences.getString(PREF_CC_DG_DEVICE_KEY, DEFAULT_CC_DG_DEVICE) ?: DEFAULT_CC_DG_DEVICE

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
        val animes = doc.select("a[href*=\"/movies/\"], a[href*=\"/tv-series/\"]").mapNotNull { element ->
            val href = element.attr("href")
            if (!href.endsWith(".html") || href.contains("#watch")) return@mapNotNull null

            val imgEl = element.selectFirst("img") ?: return@mapNotNull null
            val itemTitle = imgEl.attr("alt").ifBlank { element.attr("title") }
            if (itemTitle.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                title = itemTitle
                setUrlWithoutDomain(href)
                thumbnail_url = imgEl.absUrl("src")
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }

        val hasNext = doc.select("a[href*=\"/page/\"]").any { it.text().contains("Next", ignoreCase = true) || it.text() == ">" }
        return AnimesPage(animes, hasNext)
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
                // TV Series nested playlist JSON
                runCatching {
                    val jsonArray = json.parseToJsonElement(fileContent).jsonArray
                    var epNum = 1.0f
                    for (seasonObj in jsonArray) {
                        val seasonTitle = seasonObj.jsonObject["title"]?.jsonPrimitive?.content ?: "Season"
                        val folder = seasonObj.jsonObject["folder"]?.jsonArray ?: continue
                        for (epObj in folder) {
                            val epTitle = epObj.jsonObject["title"]?.jsonPrimitive?.content ?: "Episode"
                            val streamUrl = epObj.jsonObject["file"]?.jsonPrimitive?.content ?: continue

                            episodes.add(
                                SEpisode.create().apply {
                                    name = "$seasonTitle $epTitle"
                                    url = streamUrl
                                    episode_number = epNum++
                                }
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
                    }
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
                    }
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
            videoNameGen = { quality -> "CinemaCity - $quality" }
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(prefQuality, ignoreCase = true) }
                .thenByDescending { it.resolution }
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
            summary = "Cloudflare clearance cookie value",
            default = DEFAULT_CF_CLEARANCE,
        )
        screen.addEditTextPreference(
            key = PREF_PHPSESSID_KEY,
            title = "PHPSESSID Cookie",
            summary = "PHP session ID cookie value",
            default = DEFAULT_PHPSESSID,
        )
        screen.addEditTextPreference(
            key = PREF_CC_DG_DEVICE_KEY,
            title = "cc_dg_device Cookie",
            summary = "CinemaCity device guard cookie value",
            default = DEFAULT_CC_DG_DEVICE,
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
        private const val DEFAULT_CF_CLEARANCE = "MV02UEDHUY0lmTjlDfREb0h4nJHGdzjGusswsDJIXsM-1786543032-1.2.1.1-fRDC6k7wKEUfzablSU0D5X0Y9WI3ahGZgnDRHCXjw_YR2kfERxlzqL4NhbbfcGCP1w_V6zaC2b._slsbFAjlUKt6znnI5jww6EuLYrMFzCLVb8ZH9DCmTq4CyiKdD3Nk2x7hCGvpBCN9Bg8s1WJxdyx2XFcXEppyY92SMoZWhJrCHDHt.Y7WoYyudznbhbyBf2qtSC8IRfkdz3K3RYqAeojcltXwIOXtm1XzAPy_INLtystdg4xsKX2Uem_B8GKtw2S77p76Z9K2ZpxZ5AS_.8EwV.82AClEnf3Hc7T18EqykOq5fjQSibMONkHMG48cQNtK7_4udVfF3lvV1gfrQvM2bVrz9pg_.7P_CCIPmlLV5Nb.27qpnRcHCRpFG6854wtIU60TVUTtRJxVUow2cI9GeQBUJVGLE5mqHQJZ6ACCISi4QV2vJsDKYpRNxPUDe4Rt4uZUcdTA2fhtwOF3Ag"

        private const val PREF_PHPSESSID_KEY = "pref_phpsessid"
        private const val DEFAULT_PHPSESSID = "k3566tnsn6qsrd3jvbv6gts9ht"

        private const val PREF_CC_DG_DEVICE_KEY = "pref_cc_dg_device"
        private const val DEFAULT_CC_DG_DEVICE = "f5ce433e261197ad855a1a00883d01bec15a85f5012443743cb0cfeb6bb9bd60"
    }
}

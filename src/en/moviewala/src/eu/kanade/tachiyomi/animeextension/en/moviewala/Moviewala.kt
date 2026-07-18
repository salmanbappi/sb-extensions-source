package eu.kanade.tachiyomi.animeextension.en.moviewala

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.m3u8server.M3u8Integration
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Moviewala : Source() {

    override val name = "Moviewala"

    override val baseUrl = "https://moviewala.tv"

    override val lang = "en"

    override val supportsLatest = true

    private val myJson = Json {
        ignoreUnknownKeys = true
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val m3u8Integration by lazy { M3u8Integration(client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/en/categories/action", headers)).execute()
        return parseListPage(response, page)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(GET("$baseUrl/en/movies-2026", headers)).execute()
        return parseListPage(response, page)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val category = categoryFilter?.selected

        val url = if (!query.isNullOrBlank()) {
            "$baseUrl/en/categories/action"
        } else if (!category.isNullOrBlank() && category != "all") {
            "$baseUrl/en/categories/$category"
        } else {
            "$baseUrl/en/categories/action"
        }

        val response = client.newCall(GET(url, headers)).execute()
        val doc = response.asJsoup()

        val allAnimes = parseAnimeListFromJsonLd(doc)
        val filtered = if (!query.isNullOrBlank()) {
            allAnimes.filter { it.title.contains(query, ignoreCase = true) }
        } else {
            allAnimes
        }

        val pageSize = 20
        val start = (page - 1) * pageSize
        val end = minOf(start + pageSize, filtered.size)
        val hasNext = end < filtered.size

        return AnimesPage(if (start < filtered.size) filtered.subList(start, end) else emptyList(), hasNext)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
    )

    // ======================== Parse Helpers ================================

    private fun parseAnimeListFromJsonLd(doc: Document): List<SAnime> {
        val jsonLdScript = doc.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { it.contains("\"ItemList\"") } ?: return emptyList()

        val urlPattern = Regex(""""url"\s*:\s*"(https://moviewala\.tv/en/(movies|series)/[^"]+)"""")
        val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")

        val urls = urlPattern.findAll(jsonLdScript).map { it.groupValues[1] }.toList()
        val names = namePattern.findAll(jsonLdScript).drop(1).map { it.groupValues[1] }.toList()

        return urls.mapIndexed { index, url ->
            SAnime.create().apply {
                title = names.getOrElse(index) { url.substringAfterLast("/") }
                setUrlWithoutDomain(url.removePrefix(baseUrl))
                thumbnail_url = ""
            }
        }
    }

    private fun parseListPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()
        val cards = doc.select("a.group\\/card[href*='/en/movies/'], a.group\\/card[href*='/en/series/']")
        val animes = if (cards.isNotEmpty()) {
            cards.map { el ->
                SAnime.create().apply {
                    setUrlWithoutDomain(el.attr("href"))
                    title = el.selectFirst("h3")?.text() ?: el.attr("href").substringAfterLast("/")
                    thumbnail_url = el.selectFirst("img")?.attr("abs:src") ?: ""
                }
            }
        } else {
            parseAnimeListFromJsonLd(doc)
        }

        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val movieJsonLd = doc.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { it.contains("\"Movie\"") || it.contains("\"TVSeries\"") }

        return anime.apply {
            if (movieJsonLd != null) {
                val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(movieJsonLd)
                val descMatch = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(movieJsonLd)
                val ratingMatch = Regex(""""ratingValue"\s*:\s*"([^"]+)"""").find(movieJsonLd)
                val genreMatches = Regex(""""genre"\s*:\s*\[([^\]]+)\]""").find(movieJsonLd)
                    ?.groupValues?.get(1)
                    ?.let { Regex(""""([^"]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
                val directorMatch = Regex(""""director"\s*:\s*\{[^}]*"name"\s*:\s*"([^"]+)"""").find(movieJsonLd)
                val trailerMatch = Regex(""""embedUrl"\s*:\s*"https://www\.youtube\.com/embed/([^"]+)"""").find(movieJsonLd)
                val imageMatch = Regex(""""image"\s*:\s*"([^"]+)"""").find(movieJsonLd)

                title = nameMatch?.groupValues?.get(1) ?: anime.title
                thumbnail_url = imageMatch?.groupValues?.get(1) ?: anime.thumbnail_url
                genre = genreMatches?.joinToString(", ")
                author = directorMatch?.groupValues?.get(1)

                val synopsis = descMatch?.groupValues?.get(1)
                    ?.replace("\\n", "\n")
                    ?.replace("\\\"", "\"") ?: ""
                val score = ratingMatch?.groupValues?.get(1)
                val trailerId = trailerMatch?.groupValues?.get(1)

                description = buildString {
                    if (score != null) {
                        val s = score.toDoubleOrNull()
                        if (s != null && s > 0) {
                            val full = (s / 2).toInt().coerceIn(0, 5)
                            append("${"★".repeat(full)}${"☆".repeat(5 - full)} ${"%.1f".format(s)}/10")
                            append("\n\n")
                        }
                    }
                    if (synopsis.isNotBlank()) append(synopsis)
                    if (!trailerId.isNullOrBlank()) {
                        append("\n\n[Trailer](https://www.youtube.com/watch?v=$trailerId)")
                    }
                }.trim()

                status = SAnime.COMPLETED
                initialized = true
            } else {
                val h1 = doc.selectFirst("h1")?.text() ?: anime.title
                title = h1.substringBefore("(").trim()
                thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: anime.thumbnail_url
                description = doc.select("section p.text-\\[15px\\]").text()
                genre = doc.select("a[href*='/en/categories/']").joinToString(", ") { it.text() }
                initialized = true
            }
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()
        val tmdbId = extractTmdbId(doc) ?: throw Exception("TMDB ID not found")

        if (anime.url.contains("/series/")) {
            val playerUrl = "https://player.silverlinehub.org/?tmdb_id=$tmdbId&type=series"
            val playerResponse = client.newCall(GET(playerUrl, headers)).execute()
            val playerHtml = playerResponse.body.string()

            val seasonsRegex = Regex("""seasons\\*":\s*(\[.*?\])\s*,\s*\\*"series\\*"""")
            val seasonsJsonEscaped = seasonsRegex.find(playerHtml)?.groupValues?.get(1)
                ?: throw Exception("Seasons data not found in player page")

            val cleanJson = seasonsJsonEscaped
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            val seasons = myJson.decodeFromString<List<PlayerSeasonDto>>(cleanJson)
            val episodesList = mutableListOf<SEpisode>()

            for (season in seasons) {
                for (episode in season.episodes) {
                    episodesList.add(
                        SEpisode.create().apply {
                            url = "${anime.url}?tmdb_id=$tmdbId&season=${season.season_number}&episode=${episode.episode_number}"
                            name = "S${season.season_number} E${episode.episode_number} ${episode.title ?: ""}".trim()
                            episode_number = episode.episode_number.toFloat()
                        },
                    )
                }
            }

            return episodesList.reversed()
        }

        return listOf(
            SEpisode.create().apply {
                url = "${anime.url}?tmdb_id=$tmdbId"
                name = "Movie"
                episode_number = 1.0f
            },
        )
    }

    // ============================ Video Links =============================

    private fun extractTmdbId(doc: Document): String? {
        val html = doc.html()

        val tmdbIdRegex = Regex("""tmdbId\\*"\s*:\s*(\d+)""")
        val tmdbId = tmdbIdRegex.find(html)?.groupValues?.get(1)
        if (!tmdbId.isNullOrBlank()) return tmdbId

        val playerUrlRegex = Regex("""playerUrl\\*":\\*"(https?://[^\\"]+)""")
        val playerUrl = playerUrlRegex.find(html)?.groupValues?.get(1)
        if (!playerUrl.isNullOrBlank()) {
            val decodedUrl = playerUrl.replace("\\u0026", "&").replace("\\u002f", "/")
            val idFromUrl = Regex("""tmdb_id=(\d+)""").find(decodedUrl)?.groupValues?.get(1)
            if (!idFromUrl.isNullOrBlank()) return idFromUrl
        }

        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val tmdbFromOg = Regex("""image\.tmdb\.org/t/p/\w+/([^.]+)""").find(ogImage)?.groupValues?.get(1)
        if (!tmdbFromOg.isNullOrBlank()) return tmdbFromOg

        return null
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterName = "Silverline", hosterUrl = "$baseUrl${episode.url}"),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val uri = android.net.Uri.parse(hoster.hosterUrl)
        val tmdbId = uri.getQueryParameter("tmdb_id") ?: throw Exception("TMDB ID not found")
        val season = uri.getQueryParameter("season")
        val episode = uri.getQueryParameter("episode")

        val playerUrl = if (season != null && episode != null) {
            "https://player.silverlinehub.org/?tmdb_id=$tmdbId&type=series"
        } else {
            "https://player.silverlinehub.org/?tmdb_id=$tmdbId"
        }

        val response = client.newCall(GET(playerUrl, headers)).execute()
        val html = response.body.string()

        val videoUrl = if (season != null && episode != null) {
            val seasonsRegex = Regex("""seasons\\*":\s*(\[.*?\])\s*,\s*\\*"series\\*"""")
            val seasonsJsonEscaped = seasonsRegex.find(html)?.groupValues?.get(1)
                ?: throw Exception("Seasons data not found in player page")

            val cleanJson = seasonsJsonEscaped
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")

            val seasons = myJson.decodeFromString<List<PlayerSeasonDto>>(cleanJson)
            val matchingEpisode = seasons.firstOrNull { it.season_number == season.toInt() }
                ?.episodes?.firstOrNull { it.episode_number == episode.toInt() }
                ?: throw Exception("Episode S${season}E$episode not found in player page")

            matchingEpisode.playback?.hls ?: throw Exception("HLS stream URL not found for episode")
        } else {
            val hlsRegex = Regex("""hls\\*":\\*"([^\\"]+)""")
            val hlsUrlEscaped = hlsRegex.find(html)?.groupValues?.get(1)
                ?: throw Exception("HLS stream URL not found in player page")

            hlsUrlEscaped.replace("\\u0026", "&").replace("\\/", "/").replace("\\u002f", "/")
        }

        val videos = playlistUtils.extractFromHls(
            playlistUrl = videoUrl,
            referer = playerUrl,
            masterHeaders = headers,
            videoHeaders = headers,
        )
        return m3u8Integration.processVideoList(videos)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString("pref_quality", "1080") ?: "1080"
        return sortedByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
            .sortedByDescending { it.resolution }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = "pref_quality"
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"
            screen.addPreference(this)
        }
    }
}

@Serializable
data class PlayerSeasonDto(
    val episodes: List<PlayerEpisodeDto>,
    val season_number: Int,
)

@Serializable
data class PlayerEpisodeDto(
    val episode_number: Int,
    val playback: PlayerPlaybackDto? = null,
    val title: String? = null,
)

@Serializable
data class PlayerPlaybackDto(
    val hls: String? = null,
)

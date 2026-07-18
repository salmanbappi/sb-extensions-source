package eu.kanade.tachiyomi.animeextension.en.flixhub

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
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

class FlixHub : Source() {

    override val name = "FlixHub"

    override val baseUrl = "https://flixhub.net"

    override val lang = "en"

    override val supportsLatest = true

    private val myJson = Json {
        ignoreUnknownKeys = true
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        // All Movies is the popular list
        val response = client.newCall(GET("$baseUrl/movies?page=$page", headers)).execute()
        return parseListPage(response)
    }

    // ============================== Latest ================================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        // Latest Series is the latest updates
        val response = client.newCall(GET("$baseUrl/tv-series?sort=latest&page=$page", headers)).execute()
        return parseListPage(response)
    }

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isNotBlank()) {
            val response = client.newCall(
                GET(
                    "$baseUrl/search/suggestions?q=${UriEncoder.encode(query)}",
                    headers.newBuilder()
                        .add("X-Requested-With", "XMLHttpRequest")
                        .add("Accept", "application/json")
                        .build(),
                ),
            ).execute()
            val jsonString = response.body.string()
            val searchResult = myJson.decodeFromString<SearchResponseDto>(jsonString)
            val animes = searchResult.results
                .filter { it.is_available && !it.watch_url.isNullOrBlank() }
                .map { dto ->
                    SAnime.create().apply {
                        title = dto.title
                        setUrlWithoutDomain(dto.watch_url!!)
                        thumbnail_url = dto.poster ?: ""
                    }
                }
            return AnimesPage(animes, false)
        }

        // Browse / Filter Mode
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()

        val type = typeFilter?.selected ?: "movies"
        val category = categoryFilter?.selected ?: "all"
        val sort = sortFilter?.selected ?: "latest"

        val url = when {
            category == "kidztime" -> "$baseUrl/kidztime?page=$page"

            type == "movies" -> {
                if (category == "all") {
                    "$baseUrl/movies?page=$page"
                } else {
                    "$baseUrl/movies?category=$category&page=$page"
                }
            }

            else -> {
                if (category == "all") {
                    "$baseUrl/tv-series?sort=$sort&page=$page"
                } else {
                    "$baseUrl/tv-series?category=$category&sort=$sort&page=$page"
                }
            }
        }

        val response = client.newCall(GET(url, headers)).execute()
        return parseListPage(response)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        CategoryFilter(),
        SortFilter(),
    )

    // ======================== Parse Helpers ================================

    private fun parseListPage(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val cards = doc.select("article.flixhub-content-card, article.movie-card-final")
        val animes = cards.map { el ->
            SAnime.create().apply {
                val href = el.selectFirst("a.movie-card-poster-link")?.attr("href")
                    ?: el.attr("data-watch-url")
                    ?: ""
                setUrlWithoutDomain(href)
                title = el.selectFirst("img")?.attr("alt")
                    ?: el.selectFirst("a")?.attr("aria-label")
                    ?: ""
                thumbnail_url = el.selectFirst("img")?.attr("abs:src") ?: ""
            }
        }
        val hasNext = doc.select("#listingLoadMoreBtn").isNotEmpty()
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        return anime.apply {
            title = doc.selectFirst("h1.movie-title, h1.player-movie-details-title")?.text() ?: anime.title
            thumbnail_url = doc.selectFirst("div.player-movie-details-poster img")?.attr("abs:src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: anime.thumbnail_url
            description = doc.selectFirst("#playerMovieDescText")?.text()
                ?: doc.selectFirst(".player-movie-description")?.text()

            genre = doc.select("a.player-movie-badge--link").joinToString(", ") { it.text() }
            author = doc.selectFirst("span.player-movie-crew-name")?.text()
            status = SAnime.COMPLETED
            initialized = true
        }
    }

    // ============================== Episodes ==============================
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        if (anime.url.contains("/watch/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = "Movie"
                    episode_number = 1.0f
                },
            )
        }

        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        val episodeElements = doc.select("article.player-sidebar-card")
        if (episodeElements.isEmpty()) {
            // Fallback to single episode if no episodes found in sidebar
            return listOf(
                SEpisode.create().apply {
                    url = anime.url
                    name = "Episode 1"
                    episode_number = 1.0f
                },
            )
        }

        return episodeElements.mapIndexed { idx, el ->
            SEpisode.create().apply {
                val href = el.selectFirst("a.player-sidebar-card-link")?.attr("href") ?: ""
                setUrlWithoutDomain(href)
                val season = el.attr("data-season-number").ifBlank { "1" }
                val epTitle = el.selectFirst(".player-sidebar-card-title")?.text() ?: "Episode ${idx + 1}"
                name = "S$season $epTitle"

                val epNumMatch = Regex("""E(\d+)""").find(epTitle)
                val parsedEpNum = epNumMatch?.groupValues?.get(1)?.toFloatOrNull()
                episode_number = parsedEpNum ?: (idx + 1).toFloat()
            }
        }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterName = "Direct Stream", hosterUrl = "$baseUrl${episode.url}"),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val response = client.newCall(GET(hoster.hosterUrl, headers)).execute()
        val doc = response.asJsoup()
        val sourceEl = doc.selectFirst("source") ?: throw Exception("Video source not found")
        val videoUrl = sourceEl.attr("abs:src")

        val ext = if (videoUrl.contains(".mkv")) "MKV" else "MP4"
        return listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = "Direct ($ext)",
                headers = headers,
            ),
        )
    }

    override fun List<Video>.sortVideos(): List<Video> = this

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}

@Serializable
data class SearchResponseDto(
    val results: List<SearchResultDto>,
)

@Serializable
data class SearchResultDto(
    val tmdb_id: String? = null,
    val title: String,
    val type: String,
    val year: String? = null,
    val poster: String? = null,
    val is_available: Boolean,
    val watch_url: String? = null,
)

object UriEncoder {
    fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

package eu.kanade.tachiyomi.animeextension.en.moviewala

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import extensions.utils.Source
import extensions.utils.asJsoup
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

    private val playerBaseUrl = "https://player.silverlinehub.org"

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val m3u8Integration by lazy { M3u8Integration(client) }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    // ============================== Popular ===============================
    // Use trending action/drama mix as "popular"
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
            // Search: extract from JSON-LD on category/bollywood pages by matching title
            // Fallback: just return category results since search is client-rendered
            "$baseUrl/en/categories/action"
        } else if (!category.isNullOrBlank() && category != "all") {
            "$baseUrl/en/categories/$category"
        } else {
            "$baseUrl/en/categories/action"
        }

        val response = client.newCall(GET(url, headers)).execute()
        val doc = response.asJsoup()

        // If we have a search query, filter titles from JSON-LD
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

    /**
     * Parses movie list from JSON-LD ItemList schema injected by the Next.js SSR.
     * This is present on category pages, year pages, etc.
     */
    private fun parseAnimeListFromJsonLd(doc: Document): List<SAnime> {
        val jsonLdScript = doc.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { it.contains("\"ItemList\"") } ?: return emptyList()

        // Extract all item URLs and names from the JSON-LD
        val urlPattern = Regex(""""url"\s*:\s*"(https://moviewala\.tv/en/(movies|series)/[^"]+)"""")
        val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")

        val urls = urlPattern.findAll(jsonLdScript).map { it.groupValues[1] }.toList()
        val names = namePattern.findAll(jsonLdScript).drop(1).map { it.groupValues[1] }.toList() // drop list title

        return urls.mapIndexed { index, url ->
            SAnime.create().apply {
                title = names.getOrElse(index) { url.substringAfterLast("/") }
                setUrlWithoutDomain(url.removePrefix(baseUrl))
                // thumbnail will be loaded on details
                thumbnail_url = "" // will be populated in getAnimeDetails
            }
        }
    }

    /**
     * Parses the HTML card grid for thumbnail URLs. Used for category pages
     * that include actual <img> tags with TMDB poster URLs.
     */
    private fun parseListPage(response: Response, page: Int): AnimesPage {
        val doc = response.asJsoup()

        // HTML cards: <a href="/en/movies/{slug}"><img src="..."/><h3>Title</h3>
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
            // Fallback: JSON-LD ItemList
            parseAnimeListFromJsonLd(doc)
        }

        // No real pagination on these pages; show all
        return AnimesPage(animes, false)
    }

    // =========================== Anime Details ============================
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        // Extract from JSON-LD Movie schema (rich structured data)
        val movieJsonLd = doc.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { it.contains("\"Movie\"") || it.contains("\"TVSeries\"") }

        return anime.apply {
            if (movieJsonLd != null) {
                // Parse key fields via regex (safer than full JSON parse for large strings)
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

                // Formatted description with star score
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
                // HTML fallback
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
        // For movies: single episode
        // For series: check if there's a seasons/episodes structure
        val response = client.newCall(GET("$baseUrl${anime.url}", headers)).execute()
        val doc = response.asJsoup()

        // Check if it's a series with episodes listed
        val episodeLinks = doc.select("a[href*='/en/series/'][href*='/episode']")
        if (episodeLinks.isNotEmpty()) {
            return episodeLinks.mapIndexed { idx, el ->
                SEpisode.create().apply {
                    name = el.text().ifBlank { "Episode ${idx + 1}" }
                    setUrlWithoutDomain(el.attr("href"))
                    episode_number = name.substringAfter("Episode ").toFloatOrNull() ?: (idx + 1).toFloat()
                }
            }.reversed()
        }

        // For series with season selector or episode list on page
        val seasonEpisodes = doc.select("div[class*='episode'] a, a[href*='/season/'], a[href*='/ep/']")
        if (seasonEpisodes.isNotEmpty()) {
            return seasonEpisodes.mapIndexed { idx, el ->
                SEpisode.create().apply {
                    name = el.text().ifBlank { "Episode ${idx + 1}" }
                    setUrlWithoutDomain(el.attr("href"))
                    episode_number = (idx + 1).toFloat()
                }
            }.reversed()
        }

        // Default: single movie episode
        val title = doc.selectFirst("h1")?.text()?.substringBefore("(")?.trim()
            ?: anime.title

        return listOf(
            SEpisode.create().apply {
                name = "Movie"
                setUrlWithoutDomain(anime.url)
                episode_number = 1.0f
            },
        )
    }

    // ============================ Video Links =============================

    /**
     * Extracts the TMDB ID from the movie detail page.
     * The player at player.silverlinehub.org accepts ?tmdb_id= or ?imdb_id=
     */
    private fun extractTmdbId(doc: Document): String? {
        // TMDB ID is embedded in image URLs: /api/media/file/{tmdb_backdrop_id}-original-...
        // Or in og:image: https://image.tmdb.org/t/p/w1280/{tmdb_poster_id}.jpg
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
        val tmdbFromOg = Regex("""image\.tmdb\.org/t/p/\w+/([^.]+)""").find(ogImage)?.groupValues?.get(1)
        if (!tmdbFromOg.isNullOrBlank()) return tmdbFromOg

        // Also check JSON-LD for any tmdb reference
        val jsonLd = doc.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { it.contains("\"Movie\"") || it.contains("\"TVSeries\"") } ?: return null

        // Try to extract numeric TMDB ID from image URL inside JSON-LD
        val tmdbFromJsonLd = Regex("""image\.tmdb\.org/t/p/\w+/(\w+)""").find(jsonLd)?.groupValues?.get(1)
        return tmdbFromJsonLd
    }

    /**
     * Gets the IMDB ID from the JSON-LD or page if available.
     */
    private fun extractImdbId(doc: Document): String? {
        val jsonLd = doc.select("script[type=application/ld+json]")
            .map { it.data() }
            .firstOrNull { it.contains("\"Movie\"") || it.contains("\"TVSeries\"") } ?: return null

        return Regex(""""sameAs"\s*:\s*"https://www\.imdb\.com/title/(tt\d+)"""").find(jsonLd)?.groupValues?.get(1)
    }

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.newCall(GET("$baseUrl${episode.url}", headers)).execute().asJsoup()

        // Get TMDB poster path (used as ID for player)
        val tmdbPosterId = extractTmdbId(doc)
        val imdbId = extractImdbId(doc)

        val hosters = mutableListOf<Hoster>()

        // Primary: silverlinehub player
        if (!tmdbPosterId.isNullOrBlank()) {
            hosters.add(
                Hoster(
                    hosterName = "Silverline (TMDB)",
                    hosterUrl = "$playerBaseUrl/?tmdb_id=$tmdbPosterId",
                ),
            )
        }
        if (!imdbId.isNullOrBlank()) {
            hosters.add(
                Hoster(
                    hosterName = "Silverline (IMDB)",
                    hosterUrl = "$playerBaseUrl/?imdb_id=$imdbId",
                ),
            )
        }

        // Fallback: scan page for any embed iframe src or player data attributes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("abs:src")
            if (src.isNotBlank() && src != baseUrl && !src.startsWith("https://www.youtube.com")) {
                hosters.add(Hoster(hosterName = "Embed", hosterUrl = src))
            }
        }

        return hosters
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val embedUrl = hoster.hosterUrl

        return runCatching {
            val html = client.newCall(
                Request.Builder()
                    .url(embedUrl)
                    .headers(
                        headers.newBuilder()
                            .set("Referer", "$baseUrl/")
                            .build(),
                    )
                    .build(),
            ).execute().body.string()

            val videos = mutableListOf<Video>()

            // 1. Look for direct m3u8 URL
            val m3u8Regex = Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?""")
            val m3u8Url = m3u8Regex.find(html)?.groupValues?.get(1)
            if (!m3u8Url.isNullOrBlank()) {
                videos.addAll(
                    m3u8Integration.processVideoList(
                        listOf(
                            Video(
                                videoUrl = m3u8Url,
                                videoTitle = "${hoster.hosterName}",
                                headers = headers.newBuilder()
                                    .set("Referer", embedUrl)
                                    .build(),
                            ),
                        ),
                    ),
                )
            }

            // 2. Look for MP4 direct link
            val mp4Regex = Regex("""["']?(https?://[^"'\s]+\.mp4[^"'\s]*)["']?""")
            val mp4Url = mp4Regex.find(html)?.groupValues?.get(1)
            if (!mp4Url.isNullOrBlank()) {
                videos.add(
                    Video(
                        videoUrl = mp4Url,
                        videoTitle = "${hoster.hosterName} - MP4",
                        headers = headers.newBuilder()
                            .set("Referer", embedUrl)
                            .build(),
                    ),
                )
            }

            // 3. If the player page itself has an iframe, recurse one level
            if (videos.isEmpty()) {
                val iframeSrcRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
                val iframeSrc = iframeSrcRegex.find(html)?.groupValues?.get(1)
                if (!iframeSrc.isNullOrBlank() && iframeSrc != embedUrl) {
                    val innerHtml = client.newCall(
                        Request.Builder()
                            .url(iframeSrc)
                            .headers(headers.newBuilder().set("Referer", embedUrl).build())
                            .build(),
                    ).execute().body.string()

                    val innerM3u8 = m3u8Regex.find(innerHtml)?.groupValues?.get(1)
                    if (!innerM3u8.isNullOrBlank()) {
                        videos.addAll(
                            m3u8Integration.processVideoList(
                                listOf(
                                    Video(
                                        videoUrl = innerM3u8,
                                        videoTitle = "${hoster.hosterName}",
                                        headers = headers.newBuilder()
                                            .set("Referer", iframeSrc)
                                            .build(),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }

            videos
        }.getOrDefault(emptyList())
    }

    // Sort videos by quality preference
    override fun List<Video>.sortVideos(): List<Video> {
        val prefQuality = preferences.getString("pref_quality", "1080") ?: "1080"
        return sortedByDescending { it.videoTitle.contains(prefQuality, ignoreCase = true) }
            .sortedByDescending { it.resolution }
    }

    // ============================ Recommendations ============================

    fun relatedAnimeListRequest(anime: SAnime): Request = GET("$baseUrl${anime.url}", headers)

    fun relatedAnimeListParse(response: Response): List<SAnime> {
        val doc = response.asJsoup()
        // "Similar movies" section
        return doc.select("section:has(h2:contains(Similar)) a.group\\/card").map { el ->
            SAnime.create().apply {
                title = el.selectFirst("h3")?.text() ?: el.attr("href").substringAfterLast("/")
                setUrlWithoutDomain(el.attr("href"))
                thumbnail_url = el.selectFirst("img")?.attr("abs:src") ?: ""
            }
        }
    }

    // ============================== Settings ==============================
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

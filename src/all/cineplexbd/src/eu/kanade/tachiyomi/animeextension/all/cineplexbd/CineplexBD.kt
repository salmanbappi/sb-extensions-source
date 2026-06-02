package eu.kanade.tachiyomi.animeextension.all.cineplexbd

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

class CineplexBD : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Cineplex BD"
    override val baseUrl = "http://cineplexbd.net"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419848L

    private val json: Json by lazy { Injekt.get() }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/search.php?q=&year[]=2026&year[]=2025&page=$page")
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search.php?q=&page=$page")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val hasSearchFilter = filters.any { 
            (it is YearFilter && it.state.any { y -> y.state }) || 
            (it is GenreFilter && it.state.any { g -> g.state })
        }

        if (query.isNotBlank() || hasSearchFilter) {
            val url = "$baseUrl/search.php".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is YearFilter -> {
                        filter.state.forEach { year ->
                            if (year.state) {
                                url.addQueryParameter("year[]", year.name)
                            }
                        }
                    }
                    is GenreFilter -> {
                        filter.state.forEach { genre ->
                            if (genre.state) {
                                url.addQueryParameter("genre[]", genre.name)
                            }
                        }
                    }
                    else -> {}
                }
            }
            return GET(url.build().toString())
        }

        filters.forEach { filter ->
            when (filter) {
                is MovieCategoryFilter -> {
                    if (filter.state != 0) {
                        val url = "$baseUrl/category.php".toHttpUrlOrNull()!!.newBuilder()
                            .addQueryParameter("category", filter.toUriPart())
                            .addQueryParameter("page", page.toString())
                        return GET(url.build().toString())
                    }
                }
                is TvCategoryFilter,
                is AnimationCategoryFilter,
                is ShowsCategoryFilter -> {
                    if ((filter as SelectFilter).state != 0) {
                        val url = "$baseUrl/tcategory.php".toHttpUrlOrNull()!!.newBuilder()
                            .addQueryParameter("category", filter.toUriPart())
                            .addQueryParameter("page", page.toString())
                        return GET(url.build().toString())
                    }
                }
                else -> {}
            }
        }

        return popularAnimeRequest(page)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val page = searchAnimeParse(response)
        val filtered = page.animes.filterNot { 
            val title = it.title.lowercase()
            val genre = it.genre?.lowercase() ?: ""
            title.contains("bangla") || genre.contains("bangla") ||
            title.contains("pakistani") || genre.contains("pakistani")
        }
        return AnimesPage(filtered, page.hasNextPage)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        
        doc.select("a[href*='view.php'], a[href*='watch.php'], a[href*='tview.php'], .movie-card a, a:has(.poster), a:has(img[src*='uploads'])").forEach { element ->
            val item = parseAnimeItem(element)
            if (item.title != "Unknown Title") {
                animeList.add(item)
            }
        }
        
        val hasNextPage = doc.select("ul.pagination li.active + li a, a:contains(Next), a:contains(»), a.next").isNotEmpty()
        return AnimesPage(animeList.distinctBy { it.url }, hasNextPage)
    }

    private fun parseAnimeItem(element: Element): SAnime {
        return SAnime.create().apply {
            val rawUrl = element.attr("href")
            
            // Extract ID carefully
            val id = if (rawUrl.contains("series_id=")) {
                rawUrl.substringAfter("series_id=").substringBefore("&")
            } else {
                rawUrl.substringAfter("id=").substringBefore("&")
            }

            url = when {
                rawUrl.contains("series_id=") -> "/watch.php?series_id=$id"
                rawUrl.contains("tview.php") -> "/tview.php?id=$id"
                rawUrl.contains("watch.php") -> "/watch.php?id=$id"
                else -> "/view.php?id=$id"
            }

            val titleEl = element.selectFirst("span.truncate, div.text-sm, div.cp-title, h2, .card-title, .title")
            val posterImg = element.selectFirst("img.poster, .tvCard img, img[class*='poster'], img[src*='uploads/']")
            title = titleEl?.text() ?: posterImg?.attr("alt") ?: "Unknown Title"
            
            // Extract info for filtering (Generic selector for robustness)
            genre = element.selectFirst("p")?.text()

            var rawImg = posterImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: posterImg?.attr("src")
                ?: element.selectFirst("img")?.attr("data-src")
                ?: element.selectFirst("img")?.attr("src")
            
            if (rawImg.isNullOrEmpty()) {
                 val style = element.selectFirst("div[style*='background-image']")?.attr("style")
                 if (style != null && style.contains("url(")) {
                     rawImg = style.substringAfter("url(").substringBefore(")")
                         .replace("'", "").replace("\"", "")
                 }
            }

            thumbnail_url = rawImg?.let {
                if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
            }
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            val rawTitle = doc.selectFirst("h1, .movie-title, title")?.text()?.replace(" — Watch", "") ?: ""
            title = rawTitle
            description = doc.selectFirst("p.leading-relaxed, #synopsis, .description")?.text() ?: ""
            
            // Metadata parsing
            genre = doc.select("span.chip:contains(,)").text().trim() // e.g., "Drama, Family"
            if (genre.isNullOrBlank()) {
                 genre = doc.select("div.ganre-wrapper a, .meta-cat, .genre a").joinToString { it.text() }
            }
            
            // Strict author parsing to avoid metadata
            author = doc.select("div.mt-4.text-sm:contains(Director:) span").text() ?: 
                     doc.select("a[href*='cast.php'][href*='Director']").text()

            // Status is generally Completed for movies
            status = SAnime.COMPLETED
            
            val detailsImg = doc.selectFirst("img.poster, .tvCard img, .movie-poster img")
            val rawDetailsImg = detailsImg?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: detailsImg?.attr("src")
            thumbnail_url = rawDetailsImg?.let {
                if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
            }
            
            // Extra info extraction for description
            val year = doc.select("span.chip:matches(\\d{4})").text()
            val duration = doc.select("span.chip:matches(\\d+h \\d+m)").text()
            val lang = doc.select("span.chip:contains(Lang:)").text()
            val score = doc.select("span.pill:contains(User Score:)").text()
            
            var extraInfo = ""
            if (!year.isNullOrBlank()) extraInfo += "\nYear: $year"
            if (!duration.isNullOrBlank()) extraInfo += "\nDuration: $duration"
            if (!lang.isNullOrBlank()) extraInfo += "\n$lang"
            if (!score.isNullOrBlank()) extraInfo += "\n$score"
            
            description = (description + extraInfo).trim()
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val episodes = mutableListOf<SEpisode>()

        if (url.contains("view.php") || url.contains("tview.php")) {
            val id = url.substringAfter("id=").substringBefore("&")
            episodes.add(SEpisode.create().apply {
                name = "Movie"
                episode_number = 1f
                this.url = "/player.php?id=$id"
            })
        } else if (url.contains("watch.php")) {
            val doc = response.asJsoup()
            val id = if (url.contains("series_id=")) {
                url.substringAfter("series_id=").substringBefore("&")
            } else {
                url.substringAfter("id=").substringBefore("&")
            }
            
            val seasonOptions = doc.select("select[name=season] option")
            val seasons = if (seasonOptions.isNotEmpty()) {
                seasonOptions.map { it.attr("value") }
            } else {
                listOf("1")
            }

            seasons.forEach { season ->
                try {
                    val metaUrl = "$baseUrl/watch.php?id=$id&season=$season&meta=1"
                    val metaResponse = client.newCall(GET(metaUrl, headers)).execute()
                    val metaJson = json.decodeFromString<JsonObject>(metaResponse.body.string())
                    
                    metaJson["episodes"]?.jsonObject?.entries?.mapNotNull { (key, value) ->
                        try {
                            val epJson = value.jsonObject
                            val rawName = epJson["title"]?.jsonPrimitive?.content ?: "Episode $key"
                            val epPath = epJson["path"]?.jsonPrimitive?.content ?: ""
                            
                            var epNum = epJson["episode_number"]?.jsonPrimitive?.content?.toFloatOrNull() 
                                ?: key.toFloatOrNull() 
                                ?: 0f
                            
                            if (epNum == 0f) {
                                val match = Regex("""(?i)E(\d+)""").find(rawName)
                                epNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                            }

                            SEpisode.create().apply {
                                name = if (seasons.size > 1) "S$season $rawName" else rawName
                                episode_number = epNum + (season.toIntOrNull() ?: 0) * 1000f
                                this.url = epPath
                            }
                        } catch (e: Exception) { null }
                    }?.sortedBy { it.episode_number }?.let { episodes.addAll(it) }
                } catch (e: Exception) {}
            }
        }
        return episodes.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        if (episode.url.startsWith("http")) return GET(episode.url, headers)
        val url = if (episode.url.startsWith("/")) episode.url else "/${episode.url}"
        return GET(baseUrl + url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains("/Data/")) {
            return listOf(Video(url, "Direct", url))
        }

        val html = response.body.string()
        
        // Try regex first (modern player style)
        var videoUrl = Regex("""const videoSrc\s*=\s*["'](.*?)["']""").find(html)?.groupValues?.get(1)
        
        // Fallback to Jsoup (legacy/other pages)
        if (videoUrl.isNullOrBlank()) {
            videoUrl = Jsoup.parse(html).selectFirst("source[type='video/mp4'], source")?.attr("src")
        }
        
        if (!videoUrl.isNullOrBlank()) {
            val finalUrl = if (videoUrl.startsWith("http")) videoUrl else "$baseUrl/${videoUrl.trimStart('/')}"
            val quality = if (finalUrl.contains(".m3u8")) "HLS" else "Original"
            return listOf(Video(finalUrl, quality, finalUrl))
        }
        return emptyList()
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Note: Only one category group works at a time"),
        MovieCategoryFilter(),
        TvCategoryFilter(),
        AnimationCategoryFilter(),
        ShowsCategoryFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Filters (Apply to Search)"),
        YearFilter(),
        GenreFilter()
    )

    private abstract class SelectFilter(name: String, values: Array<String>) : AnimeFilter.Select<String>(name, values) {
        fun toUriPart() = if (state == 0) "" else values[state]
    }

    private class MovieCategoryFilter : SelectFilter("Movies", arrayOf(
        "Any", "3D Movies", "4K Movies", "Animation", "Anime", "Bangla", "Bangla Dubbed", "Bangla Movies",
        "Chinese", "Documentaries", "Dual Audio", "English", "Exclusive Full HD", "Foreign",
        "Hindi", "Indonesian", "Japanese", "Kids Cartoon", "Korean", "Pakistani", "Punjabi", "Romance",
        "Hindi Dubbed/Chinees Movies", "Hindi Dubbed/English Movies", "Hindi Dubbed/Indonesian Movies",
        "Hindi Dubbed/Japanese Movies", "Hindi Dubbed/Korean Movies", "Hindi Dubbed/Tamil Movies"
    ))

    private class TvCategoryFilter : SelectFilter("TV Series", arrayOf(
        "Any", "Bangla Series", "Bangla Drama", "Indian Bangla", "Indian Bangla Drama",
        "Web Series", "Hindi Series", "Pakistani Series", "English Series", "Korean Series", "Japanese Series", "Others Series",
        "Islamic Series", "Bangla Dubbed", "Hindi Dubbed", "Bangla"
    ))

    private class AnimationCategoryFilter : SelectFilter("Animation & Cartoon", arrayOf(
        "Any", "Animation Series", "Bangla Animation", "Hindi Animation", "English Animation", "Others Animation"
    ))

    private class ShowsCategoryFilter : SelectFilter("Shows & Others", arrayOf(
        "Any", "Award Shows", "Bangla Shows", "English Shows", "Hindi Shows", "Others Shows",
        "WWE", "AEW Wrestling", "WWE Wrestling", "Entertainment", "Documentary"
    ))
    
    class YearFilter : AnimeFilter.Group<AnimeFilter.CheckBox>("Year", (2026 downTo 1900).map { MyCheckBox(it.toString()) })
    class MyCheckBox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)
    
    class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", 
        "Drama", "Family", "Fantasy", "Film-Noir", "History", "Horror", "Music", "Musical", 
        "Mystery", "Romance", "Sci-Fi", "Short", "Sport", "Thriller", "War", "Western"
    ).map { MyCheckBox(it) })

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
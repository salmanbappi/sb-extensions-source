package eu.kanade.tachiyomi.animeextension.all.cineplexbd

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.Source
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder

class CineplexBD : Source() {

    override val name = "Cineplex BD"
    override val baseUrl = "http://cineplexbd.net"
    override val lang = "all"
    override val supportsLatest = true
    override val id: Long = 5181466391484419848L

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
                is ShowsCategoryFilter,
                -> {
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

    private fun parseAnimeItem(element: Element): SAnime = SAnime.create().apply {
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

        val isTvShow = url.contains("watch.php") && !url.contains("season=")
        val splitEnabled = preferences.getBoolean(PREF_SEASON_SPLITTER_KEY, PREF_SEASON_SPLITTER_DEFAULT)
        fetch_type = if (isTvShow && splitEnabled) FetchType.Seasons else FetchType.Episodes
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val url = response.request.url.toString()
        val seasonFromUrl = if (url.contains("season=")) {
            url.substringAfter("season=").substringBefore("&").trimEnd('/').trim()
        } else {
            null
        }

        val anime = SAnime.create().apply {
            val rawTitle = doc.selectFirst("h1, .movie-title, title")?.text()?.replace(" — Watch", "") ?: ""
            title = if (seasonFromUrl != null) {
                val seasonOptions = doc.select("select[name=season] option")
                val optionText = seasonOptions.firstOrNull { it.attr("value").trimEnd('/').trim() == seasonFromUrl }?.text()?.trim()
                val seasonText = optionText ?: "Season $seasonFromUrl"
                if (!rawTitle.contains(seasonText, ignoreCase = true)) {
                    "$rawTitle - $seasonText"
                } else {
                    rawTitle
                }
            } else {
                rawTitle
            }
            description = doc.selectFirst("p.leading-relaxed, #synopsis, .description")?.text() ?: ""

            // Metadata parsing
            genre = doc.select("span.chip:contains(,)").text().trim() // e.g., "Drama, Family"
            if (genre.isNullOrBlank()) {
                genre = doc.select("div.ganre-wrapper a, .meta-cat, .genre a").joinToString { it.text() }
            }

            // Strict author parsing to avoid metadata
            author = doc.select("div.mt-4.text-sm:contains(Director:) span").text()
                ?: doc.select("a[href*='cast.php'][href*='Director']").text()

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

        if (url.contains("watch.php")) {
            val paramName = if (url.contains("series_id=")) "series_id" else "id"
            val id = if (url.contains("series_id=")) {
                url.substringAfter("series_id=").substringBefore("&")
            } else {
                url.substringAfter("id=").substringBefore("&")
            }.trimEnd('/').trim()
            try {
                val metaUrl = "$baseUrl/watch.php?$paramName=$id&season=${seasonFromUrl ?: "1"}&meta=1"
                val metaResponse = client.newCall(GET(metaUrl, headers)).execute()
                val responseBodyString = metaResponse.body.string()
                metaResponse.close()
                val metaJson = json.decodeFromString<JsonObject>(responseBodyString)

                val syn = metaJson["synopsis"]?.jsonPrimitive?.content
                val baseDescription = if (!syn.isNullOrBlank()) syn else doc.selectFirst("p.leading-relaxed, #synopsis, .description")?.text() ?: ""

                var extraInfo = ""
                val rating = metaJson["rating"]?.jsonPrimitive?.content
                val ratingSrc = metaJson["ratingSrc"]?.jsonPrimitive?.content
                if (!rating.isNullOrBlank()) {
                    extraInfo += "\nRating: $rating ($ratingSrc)"
                }

                val country = metaJson["country"]?.jsonPrimitive?.content
                if (!country.isNullOrBlank()) {
                    extraInfo += "\nCountry: $country"
                }

                val year = doc.select("span.chip:matches(\\d{4})").text()
                val duration = doc.select("span.chip:matches(\\d+h \\d+m)").text()
                val lang = doc.select("span.chip:contains(Lang:)").text()

                if (!year.isNullOrBlank()) extraInfo += "\nYear: $year"
                if (!duration.isNullOrBlank()) extraInfo += "\nDuration: $duration"
                if (!lang.isNullOrBlank()) extraInfo += "\n$lang"

                anime.description = (baseDescription + extraInfo).trim()

                val castList = metaJson["cast"]?.jsonArray?.mapNotNull {
                    it.jsonObject["name"]?.jsonPrimitive?.content
                }
                if (!castList.isNullOrEmpty()) {
                    anime.artist = castList.joinToString()
                }
            } catch (e: Exception) {}
        } else {
            try {
                val castList = doc.select(".cast-img").map { it.attr("alt") }
                if (castList.isNotEmpty()) {
                    anime.artist = castList.joinToString()
                }

                val trailerBtn = doc.selectFirst("button[onclick*=openTrailer]")
                val trailerKey = trailerBtn?.attr("onclick")?.substringAfter("openTrailer('")?.substringBefore("')")
                if (!trailerKey.isNullOrBlank()) {
                    anime.description = "${anime.description.orEmpty()}\nTrailer: https://www.youtube.com/watch?v=$trailerKey".trim()
                }

                val pillScore = doc.selectFirst(".pill:contains(★)")?.text()
                val desc = anime.description.orEmpty()
                if (!pillScore.isNullOrBlank() && !desc.contains("Score:") && !desc.contains("Rating:")) {
                    anime.description = "$desc\nRating: $pillScore".trim()
                }

                val countryChip = doc.selectFirst(".chip[class*=sky]")?.text()
                if (!countryChip.isNullOrBlank()) {
                    anime.description = "${anime.description.orEmpty()}\nCountry: $countryChip".trim()
                }
            } catch (e: Exception) {}
        }

        val isTvShow = url.contains("watch.php") && !url.contains("season=")
        val splitEnabled = preferences.getBoolean(PREF_SEASON_SPLITTER_KEY, PREF_SEASON_SPLITTER_DEFAULT)
        val seasonOptions = doc.select("select[name=season] option")
        val hasMultipleSeasons = seasonOptions.size > 1

        anime.fetch_type = if (isTvShow && splitEnabled && hasMultipleSeasons) {
            FetchType.Seasons
        } else {
            FetchType.Episodes
        }

        return anime
    }

    override fun seasonListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun seasonListParse(response: Response): List<SAnime> {
        val url = response.request.url.toString()
        if (!preferences.getBoolean(PREF_SEASON_SPLITTER_KEY, PREF_SEASON_SPLITTER_DEFAULT)) {
            throw UnsupportedOperationException("Season splitter is disabled")
        }
        if (url.contains("view.php") || url.contains("tview.php")) {
            throw UnsupportedOperationException("Movies do not have seasons")
        }
        val doc = response.asJsoup()
        val seasonOptions = doc.select("select[name=season] option")
        if (seasonOptions.size <= 1) {
            throw UnsupportedOperationException("Single season series do not need splitting")
        }
        val paramName = if (url.contains("series_id=")) "series_id" else "id"
        val id = if (url.contains("series_id=")) {
            url.substringAfter("series_id=").substringBefore("&")
        } else {
            url.substringAfter("id=").substringBefore("&")
        }.trimEnd('/').trim()
        val detailsImg = doc.selectFirst("img.poster, .tvCard img, .movie-poster img")
        val rawDetailsImg = detailsImg?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: detailsImg?.attr("src")
        val thumbnailUrl = rawDetailsImg?.let {
            if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}"
        }
        return seasonOptions.map { option ->
            val seasonNum = option.attr("value")
            val seasonName = option.text().trim()
            SAnime.create().apply {
                this.url = "/watch.php?$paramName=$id&season=$seasonNum"
                this.title = seasonName
                this.thumbnail_url = thumbnailUrl
                this.fetch_type = FetchType.Episodes
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val url = if (anime.url.startsWith("/")) anime.url else "/${anime.url}"
        return GET(baseUrl + url, headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val url = response.request.url.toString()
        val episodes = mutableListOf<SEpisode>()
        val showStats = preferences.getBoolean(PREF_STATS_KEY, PREF_STATS_DEFAULT)

        if (url.contains("view.php") || url.contains("tview.php")) {
            val id = url.substringAfter("id=").substringBefore("&").trimEnd('/').trim()
            var epViews: String? = null
            var epDate: Long = 0L
            if (showStats) {
                try {
                    val playerResponse = client.newCall(GET("$baseUrl/player.php?id=$id", headers)).execute()
                    val responseBodyString = playerResponse.body.string()
                    playerResponse.close()
                    val playerDoc = Jsoup.parse(responseBodyString)

                    val viewsSpan = playerDoc.selectFirst("span:contains(👁️)")
                    if (viewsSpan != null) {
                        epViews = viewsSpan.text().replace("👁️", "").trim() + " views"
                    }

                    val downloadsSpan = playerDoc.selectFirst("span:contains(⬇️)")
                    if (downloadsSpan != null && epViews != null) {
                        epViews += " | " + downloadsSpan.text().replace("⬇️", "").trim() + " downloads"
                    }

                    val dateSpan = playerDoc.selectFirst("span:contains(Uploaded:)")
                    if (dateSpan != null) {
                        val dateStr = dateSpan.text().substringAfter("Uploaded:").trim()
                        epDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dateStr)?.time ?: 0L
                    }
                } catch (e: Exception) {}
            }

            episodes.add(
                SEpisode.create().apply {
                    name = "Movie"
                    episode_number = 1f
                    this.url = "/player.php?id=$id"
                    if (showStats && epViews != null) {
                        scanlator = epViews
                    }
                    if (epDate != 0L) {
                        date_upload = epDate
                    }
                },
            )
        } else if (url.contains("watch.php")) {
            val doc = response.asJsoup()
            val paramName = if (url.contains("series_id=")) "series_id" else "id"
            val id = if (url.contains("series_id=")) {
                url.substringAfter("series_id=").substringBefore("&")
            } else {
                url.substringAfter("id=").substringBefore("&")
            }.trimEnd('/').trim()

            val seasonFromUrl = if (url.contains("season=")) {
                url.substringAfter("season=").substringBefore("&").trimEnd('/').trim()
            } else {
                null
            }

            val seasons = if (seasonFromUrl != null) {
                listOf(seasonFromUrl)
            } else {
                val seasonOptions = doc.select("select[name=season] option")
                if (seasonOptions.isNotEmpty()) {
                    seasonOptions.map { it.attr("value").trimEnd('/').trim() }
                } else {
                    listOf("1")
                }
            }

            var totalEpisodeCount = 1f
            val firstSeason = seasons.firstOrNull()
            seasons.forEach { season ->
                try {
                    val seasonDoc = if (season == firstSeason) {
                        doc
                    } else {
                        try {
                            val htmlResponse = client.newCall(GET("$baseUrl/watch.php?$paramName=$id&season=$season", headers)).execute()
                            val responseBodyString = htmlResponse.body.string()
                            htmlResponse.close()
                            Jsoup.parse(responseBodyString)
                        } catch (e: Exception) {
                            doc
                        }
                    }

                    val seasonEpisodes = try {
                        val metaUrl = "$baseUrl/watch.php?$paramName=$id&season=$season&meta=1"
                        val metaResponse = client.newCall(GET(metaUrl, headers)).execute()
                        val responseBodyString = metaResponse.body.string()
                        metaResponse.close()
                        val metaJson = json.decodeFromString<JsonObject>(responseBodyString)

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

                                val epName = cleanEpisodeName(rawName, season, epNum.toInt().toString())

                                var epViews: String? = null
                                if (showStats) {
                                    try {
                                        val epCard = seasonDoc.selectFirst("a[data-ep=${epNum.toInt()}]")
                                        val viewsSpan = epCard?.selectFirst(".meta-views")
                                        if (viewsSpan != null) {
                                            epViews = viewsSpan.text().trim()
                                        }
                                    } catch (e: Exception) {}
                                }

                                // Fetch thumbnail
                                var thumbnail: String? = null
                                try {
                                    val jsonStill = epJson["still"]?.jsonPrimitive?.content
                                    if (!jsonStill.isNullOrBlank()) {
                                        thumbnail = jsonStill
                                    }
                                    if (thumbnail.isNullOrBlank()) {
                                        val epCard = seasonDoc.selectFirst("a[data-ep=${epNum.toInt()}]")
                                        val imgElement = epCard?.selectFirst("img")
                                        val imgSrc = imgElement?.attr("src") ?: imgElement?.attr("data-src")
                                        if (!imgSrc.isNullOrBlank()) {
                                            thumbnail = if (imgSrc.startsWith("http")) imgSrc else "$baseUrl/${imgSrc.trimStart('/')}"
                                        }
                                    }
                                } catch (e: Exception) {}

                                // Fetch summary
                                var epSummary: String? = null
                                try {
                                    epSummary = epJson["synopsis"]?.jsonPrimitive?.content
                                        ?: epJson["description"]?.jsonPrimitive?.content
                                        ?: epJson["summary"]?.jsonPrimitive?.content
                                        ?: epJson["overview"]?.jsonPrimitive?.content
                                } catch (e: Exception) {}

                                SEpisode.create().apply {
                                    name = if (seasons.size > 1) "S$season $epName" else epName
                                    episode_number = epNum
                                    this.url = epPath
                                    if (showStats && epViews != null) {
                                        scanlator = epViews
                                    }
                                    if (!thumbnail.isNullOrBlank()) {
                                        preview_url = thumbnail
                                    }
                                    if (!epSummary.isNullOrBlank()) {
                                        summary = epSummary
                                    }
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }?.sortedBy { it.episode_number }
                    } catch (e: Exception) {
                        null
                    }

                    val finalSeasonEpisodes = if (seasonEpisodes.isNullOrEmpty()) {
                        seasonDoc.select("a.ep-card").mapNotNull { card ->
                            try {
                                val epPath = card.attr("href")
                                if (epPath.isNullOrBlank()) return@mapNotNull null
                                val epNumStr = card.attr("data-ep")
                                val epNum = epNumStr.toFloatOrNull() ?: 0f

                                val rawName = card.selectFirst("span.truncate")?.text() ?: "Episode $epNumStr"
                                val epName = cleanEpisodeName(rawName, season, epNum.toInt().toString())

                                val imgEl = card.selectFirst("img")
                                val imgSrc = imgEl?.attr("src") ?: imgEl?.attr("data-src")
                                val thumbnail = if (!imgSrc.isNullOrBlank()) {
                                    if (imgSrc.startsWith("http")) imgSrc else "$baseUrl/${imgSrc.trimStart('/')}"
                                } else {
                                    null
                                }

                                val epViews = if (showStats) card.selectFirst(".meta-views")?.text()?.trim() else null

                                SEpisode.create().apply {
                                    name = if (seasons.size > 1) "S$season $epName" else epName
                                    episode_number = epNum
                                    this.url = epPath
                                    if (showStats && epViews != null) {
                                        scanlator = epViews
                                    }
                                    if (!thumbnail.isNullOrBlank()) {
                                        preview_url = thumbnail
                                    }
                                }
                            } catch (e: Exception) {
                                null
                            }
                        }.sortedBy { it.episode_number }
                    } else {
                        seasonEpisodes
                    }

                    if (finalSeasonEpisodes != null) {
                        for (episode in finalSeasonEpisodes) {
                            episode.episode_number = totalEpisodeCount++
                            episodes.add(episode)
                        }
                    }
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
        if (url.endsWith(".mp4") || url.endsWith(".mkv") || url.contains("/Data/") || url.contains(".m3u8")) {
            val quality = if (url.contains(".m3u8")) "HLS" else "Direct"
            return listOf(Video(videoUrl = url, videoTitle = quality))
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
            return listOf(Video(videoUrl = finalUrl, videoTitle = quality))
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
        GenreFilter(),
    )

    private abstract class SelectFilter(name: String, values: Array<String>) : AnimeFilter.Select<String>(name, values) {
        fun toUriPart() = if (state == 0) "" else values[state]
    }

    private class MovieCategoryFilter :
        SelectFilter(
            "Movies",
            arrayOf(
                "Any", "3D Movies", "4K Movies", "Animation", "Anime", "Bangla", "Bangla Dubbed", "Bangla Movies",
                "Chinese", "Documentaries", "Dual Audio", "English", "Exclusive Full HD", "Foreign",
                "Hindi", "Indonesian", "Japanese", "Kids Cartoon", "Korean", "Pakistani", "Punjabi", "Romance",
                "Hindi Dubbed/Chinees Movies", "Hindi Dubbed/English Movies", "Hindi Dubbed/Indonesian Movies",
                "Hindi Dubbed/Japanese Movies", "Hindi Dubbed/Korean Movies", "Hindi Dubbed/Tamil Movies",
            ),
        )

    private class TvCategoryFilter :
        SelectFilter(
            "TV Series",
            arrayOf(
                "Any", "Bangla Series", "Bangla Drama", "Indian Bangla", "Indian Bangla Drama",
                "Web Series", "Hindi Series", "Pakistani Series", "English Series", "Korean Series", "Japanese Series", "Others Series",
                "Islamic Series", "Bangla Dubbed", "Hindi Dubbed", "Bangla",
            ),
        )

    private class AnimationCategoryFilter :
        SelectFilter(
            "Animation & Cartoon",
            arrayOf(
                "Any", "Animation Series", "Bangla Animation", "Hindi Animation", "English Animation", "Others Animation",
            ),
        )

    private class ShowsCategoryFilter :
        SelectFilter(
            "Shows & Others",
            arrayOf(
                "Any", "Award Shows", "Bangla Shows", "English Shows", "Hindi Shows", "Others Shows",
                "WWE", "AEW Wrestling", "WWE Wrestling", "Entertainment", "Documentary",
            ),
        )

    class YearFilter : AnimeFilter.Group<AnimeFilter.CheckBox>("Year", (2026 downTo 1900).map { MyCheckBox(it.toString()) })
    class MyCheckBox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            listOf(
                "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary",
                "Drama", "Family", "Fantasy", "Film-Noir", "History", "Horror", "Music", "Musical",
                "Mystery", "Romance", "Sci-Fi", "Short", "Sport", "Thriller", "War", "Western",
            ).map { MyCheckBox(it) },
        )

    private fun cleanEpisodeName(rawName: String, season: String, epKey: String): String = "Episode $epKey"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_STATS_KEY
            title = "Show Episode Stats"
            summary = "Show views and downloads in the episode list (takes effect on refresh)."
            setDefaultValue(PREF_STATS_DEFAULT)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SEASON_SPLITTER_KEY
            title = "Season splitter"
            summary = "Split seasons into separate listings (takes effect on refresh)."
            setDefaultValue(PREF_SEASON_SPLITTER_DEFAULT)
        }.also { screen.addPreference(it) }
    }

    companion object {
        private const val PREF_STATS_KEY = "show_episode_stats"
        private const val PREF_STATS_DEFAULT = true

        private const val PREF_SEASON_SPLITTER_KEY = "season_splitter"
        private const val PREF_SEASON_SPLITTER_DEFAULT = true
    }
}

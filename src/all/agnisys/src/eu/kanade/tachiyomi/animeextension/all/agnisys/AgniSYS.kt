package eu.kanade.tachiyomi.animeextension.all.agnisys

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.Sort.Selection
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.jellyfin.ItemDto
import eu.kanade.tachiyomi.multisrc.jellyfin.ItemListDto
import eu.kanade.tachiyomi.multisrc.jellyfin.ItemType
import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin
import eu.kanade.tachiyomi.multisrc.jellyfin.buildAuthHeader
import eu.kanade.tachiyomi.network.GET
import extensions.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class AgniSYS : Jellyfin("AgniSYS") {
    override val defaultBaseUrl = "http://182.252.81.180:8096"
    override val defaultUsername = "vibe"
    override val defaultPassword = "121121"

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(CATEGORIES),
        GenreFilter(),
        SortFilter(),
        YearFilter(),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val startIndex = (page - 1) * 20

        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val parentId = categoryFilter?.selectedId()?.takeIf { it.isNotBlank() }

        val url = baseItemsUrl(startIndex, parentId).newBuilder().apply {
            if (query.isNotBlank()) setQueryParameter("SearchTerm", query)

            for (filter in filters) {
                when (filter) {
                    is GenreFilter -> {
                        val genre = filter.selectedGenre()
                        if (genre != null) setQueryParameter("Genres", genre)
                    }

                    is SortFilter -> {
                        setQueryParameter("SortBy", filter.sortValue())
                        setQueryParameter("SortOrder", if (filter.isAscending()) "Ascending" else "Descending")
                    }

                    is YearFilter -> {
                        val year = filter.state.trim()
                        if (year.isNotBlank()) setQueryParameter("Years", year)
                    }

                    else -> {}
                }
            }
        }.build()
        return parseItemsPageLocal(url, page)
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val startIndex = (page - 1) * 20
        val url = getItemsUrl(startIndex).newBuilder().apply {
            setQueryParameter("SortBy", "DateCreated,SortName")
            setQueryParameter("SortOrder", "Descending")
        }.build()
        return parseItemsPageLocal(url, page)
    }

    private suspend fun parseItemsPageLocal(url: HttpUrl, page: Int): AnimesPage {
        val newUrl = url.newBuilder().apply {
            val existingFields = url.queryParameter("Fields")
            val fields = if (existingFields.isNullOrEmpty()) "Path" else "$existingFields,Path"
            setQueryParameter("Fields", fields)
        }.build()

        val body = client.newCall(GET(newUrl)).execute().use { it.body.string() }
        val jsonElement = json.parseToJsonElement(body).jsonObject
        val itemsArray = jsonElement["Items"]?.jsonArray ?: emptyList()

        val filteredItems = itemsArray.filterNot { item ->
            val path = item.jsonObject["Path"]?.jsonPrimitive?.contentOrNull ?: ""
            val segments = path.split(Regex("[\\\\/]"))
            segments.any { segment ->
                segment.equals("Tutorials", ignoreCase = true) ||
                    segment.equals("Song", ignoreCase = true) ||
                    segment.equals("Playlists", ignoreCase = true)
            }
        }

        val filteredJson = buildJsonObject {
            put("Items", JsonArray(filteredItems))
            put("TotalRecordCount", jsonElement["TotalRecordCount"] ?: JsonPrimitive(0))
        }

        val items = json.decodeFromJsonElement<ItemListDto>(filteredJson)
        val animeList = items.items.map { it.toSAnime(baseUrl, userId) }
        return AnimesPage(animeList, 20 * page < items.totalRecordCount)
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val item = client.newCall(GET(anime.url)).execute().parseAs<ItemDto>(json)
        val showThumbnails = preferences.getBoolean("pref_show_thumbnails", true)
        val showSummary = preferences.getBoolean("pref_show_summary", true)
        val episodeTemplate = preferences.getString("pref_episode_name_template", "{seasonShort}{numberShort} - {title}")!!
        return if (item.type == ItemType.Folder || item.type == ItemType.BoxSet) {
            val childUrl = "$baseUrl/Users/$userId/Items?ParentId=${item.id}&Recursive=true&IncludeItemTypes=Movie&SortBy=SortName&SortOrder=Ascending&Fields=MediaSources,OriginalTitle,SortName,Overview,BackdropImageTags&Limit=500"
            val children = client.newCall(GET(childUrl)).execute().parseAs<ItemListDto>(json)
            children.items.mapIndexed { idx, child ->
                child.toSEpisode(baseUrl, userId, showThumbnails, showSummary, episodeTemplate).also { it.episode_number = (idx + 1).toFloat() }
            }
        } else {
            listOf(item.toSEpisode(baseUrl, userId, showThumbnails, showSummary, episodeTemplate))
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val item = client.newCall(GET(episode.url)).execute().parseAs<ItemDto>(json)
        val videoHeaders = Headers.headersOf("Authorization", buildAuthHeader(deviceInfo, accessToken))
        val streams = mutableListOf<Video>()

        val directUrl = "$baseUrl/Videos/${item.id}/stream?static=True&api_key=$accessToken"
        streams.add(Video(videoUrl = directUrl, videoTitle = "Direct Stream", headers = videoHeaders))

        val hlsUrl = "$baseUrl/Videos/${item.id}/master.m3u8?api_key=$accessToken&VideoCodec=h264&AudioCodec=aac&MaxStreamingBitrate=8000000"
        streams.add(Video(videoUrl = hlsUrl, videoTitle = "HLS (8Mbps)", headers = videoHeaders))

        return streams
    }

    private fun baseItemsUrl(startIndex: Int, parentId: String? = null): HttpUrl = "$baseUrl/Users/$userId/Items".toHttpUrl().newBuilder().apply {
        addQueryParameter("StartIndex", startIndex.toString())
        addQueryParameter("Limit", "20")
        addQueryParameter("ImageTypeLimit", "1")
        addQueryParameter("EnableImageTypes", "Primary")
        addQueryParameter("Fields", "Genres,Studios,Overview,ProductionYear,CommunityRating,OfficialRating")
        addQueryParameter("SortBy", "SortName")
        addQueryParameter("SortOrder", "Ascending")

        if (parentId != null) {
            addQueryParameter("ParentId", parentId)
            addQueryParameter("IncludeItemTypes", "Movie,Folder")
        } else {
            addQueryParameter("Recursive", "true")
            addQueryParameter("IncludeItemTypes", "Movie")
        }
    }.build()

    private class CategoryFilter(cats: List<Pair<String, String>>) : AnimeFilter.Select<String>("Library", cats.map { it.first }.toTypedArray()) {
        val cats = cats
        fun selectedId() = cats[state].second
    }

    private class GenreFilter : AnimeFilter.Select<String>("Genre", GENRES.toTypedArray()) {
        fun selectedGenre() = if (state == 0) null else GENRES[state]
    }

    private class SortFilter : AnimeFilter.Sort("Sort by", SORT_OPTIONS, Selection(0, true)) {
        fun sortValue() = SORT_VALUES[state!!.index]
        fun isAscending() = state!!.ascending
    }

    private class YearFilter : AnimeFilter.Text("Year (e.g. 2024)")

    companion object {
        private val CATEGORIES = listOf(
            Pair("All Libraries", ""),
            Pair("Bangla Movies", "d6d7796e127b01138f8c2c4dc4b60f02"),
            Pair("Bollywood Movies", "21f5d92db0a8d1a5d0f526c8d8bca689"),
            Pair("Hollywood Movies", "cb65dd976efcaf208a83ba21856d1f67"),
            Pair("Animation Movies", "36b7cb06a8877931044683388b8dcc1f"),
            Pair("Horror Movies", "2ee7ab9a0e71901f8c71f54989f7ccdc"),
            Pair("Turkish Movies", "7e8896a2a0224459ee27eca3755892a5"),
            Pair("Iranian Movies", "e9b085bb5dc880331ea45e3b69fdbd02"),
            Pair("Korean & Hindi Movies", "a11aa43a6f987ab76773430ae0dee4db"),
            Pair("Chinese Movies", "b7e59048a8aaa09c96afc730dc18124a"),
            Pair("Web Series", "2704a4f904f147fd945a4f5b25ffa320"),
            Pair("IMDB Top Movies", "cf72f09a5e3ed3b3b412def312048962"),
            Pair("Tutorials", "0c3958d909ab63aeb7021619ffa5cac1"),
            Pair("Collections", "9d7ad6afe9afa2dab1a2f6e00ad28fa6"),
            Pair("Music Videos", "92f75a1a41e354235f4aded775720801"),
        )

        private val GENRES = listOf(
            "Any", "Action", "Adult", "Adventure", "Animation", "Biography",
            "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
            "History", "Horror", "Music", "Musical", "Mystery", "Reality-TV",
            "Romance", "Science Fiction", "Sci-Fi", "Short", "Sport",
            "Talk-Show", "Thriller", "TV Movie", "War", "Western",
        )

        private val SORT_OPTIONS = arrayOf("Name", "Date Added", "Rating", "Release Year", "Play Count")
        private val SORT_VALUES = arrayOf("SortName", "DateCreated,SortName", "CommunityRating,SortName", "ProductionYear,SortName", "PlayCount,SortName")
    }
}

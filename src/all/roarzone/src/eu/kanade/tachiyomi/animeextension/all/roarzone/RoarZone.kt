package eu.kanade.tachiyomi.animeextension.all.roarzone

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter.Sort.Selection
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin
import eu.kanade.tachiyomi.multisrc.jellyfin.ItemListDto
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import extensions.utils.parseAs
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RoarZone : Jellyfin("RoarZone") {
    override val defaultBaseUrl = "https://play.roarzone.net"
    override val defaultUsername = "Roarzone_guest"
    override val defaultPassword = ""

    private var categoriesCache: List<Pair<String, String>>? = null
    private var genresCache: List<Pair<String, String>>? = null

    private fun fetchCategoriesLocal(): List<Pair<String, String>> {
        if (categoriesCache == null) {
            val cachedJson = preferences.getString("pref_cached_categories", null)
            if (cachedJson != null) {
                try {
                    val list = mutableListOf<Pair<String, String>>()
                    val array = json.parseToJsonElement(cachedJson).jsonArray
                    array.forEach {
                        val obj = it.jsonObject
                        val name = obj["name"]!!.jsonPrimitive.content
                        val id = obj["id"]!!.jsonPrimitive.content
                        list.add(Pair(name, id))
                    }
                    categoriesCache = list
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        categoriesCache?.let { if (it.isNotEmpty()) return it }

        val list = mutableListOf<Pair<String, String>>(Pair("All", ""))
        try {
            if (userId.isNotBlank()) {
                val url = "$baseUrl/Users/$userId/Views"
                val resp = client.newCall(GET(url)).execute()
                if (resp.isSuccessful) {
                    val views = resp.parseAs<ItemListDto>(json)
                    views.items.forEach { list.add(Pair(it.name, it.id)) }

                    val jsonArray = buildJsonArray {
                        list.forEach { pair ->
                            add(
                                buildJsonObject {
                                    put("name", pair.first)
                                    put("id", pair.second)
                                },
                            )
                        }
                    }
                    preferences.edit().putString("pref_cached_categories", jsonArray.toString()).apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        categoriesCache = list
        return list
    }

    private fun fetchGenresLocal(): List<Pair<String, String>> {
        if (genresCache == null) {
            val cachedJson = preferences.getString("pref_cached_genres", null)
            if (cachedJson != null) {
                try {
                    val list = mutableListOf<Pair<String, String>>()
                    val array = json.parseToJsonElement(cachedJson).jsonArray
                    array.forEach {
                        val obj = it.jsonObject
                        val name = obj["name"]!!.jsonPrimitive.content
                        val id = obj["id"]!!.jsonPrimitive.content
                        list.add(Pair(name, id))
                    }
                    genresCache = list
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        genresCache?.let { if (it.isNotEmpty()) return it }

        val list = mutableListOf<Pair<String, String>>(Pair("All", ""))
        try {
            val url = "$baseUrl/Genres?Recursive=true&IncludeItemTypes=Movie,Series&Limit=100"
            val resp = client.newCall(GET(url)).execute()
            if (resp.isSuccessful) {
                val genres = resp.parseAs<ItemListDto>(json)
                genres.items.forEach { list.add(Pair(it.name, it.id)) }

                val jsonArray = buildJsonArray {
                    list.forEach { pair ->
                        add(
                            buildJsonObject {
                                put("name", pair.first)
                                put("id", pair.second)
                            },
                        )
                    }
                }
                preferences.edit().putString("pref_cached_genres", jsonArray.toString()).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        genresCache = list
        return list
    }

    override fun getFilterList(): AnimeFilterList {
        val categories = fetchCategoriesLocal()
        val genres = fetchGenresLocal()
        return AnimeFilterList(
            AnimeFilter.Header("Search query is ignored in filters"),
            TypeFilter(),
            CategoryFilter(categories),
            GenreFilter(genres),
            SortFilter(),
        )
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val startIndex = (page - 1) * 20
        val url = getItemsUrl(startIndex).newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("SearchTerm", query)
            filters.forEach { filter ->
                when (filter) {
                    is TypeFilter -> setQueryParameter("IncludeItemTypes", filter.toValue())
                    is CategoryFilter -> if (filter.toValue().isNotBlank()) setQueryParameter("ParentId", filter.toValue())
                    is GenreFilter -> if (filter.toValue().isNotBlank()) setQueryParameter("GenreIds", filter.toValue())
                    is SortFilter -> {
                        setQueryParameter("SortBy", filter.toSortValue())
                        setQueryParameter("SortOrder", if (filter.isAscending()) "Ascending" else "Descending")
                    }
                    else -> {}
                }
            }
        }.build()
        return parseItemsPage(url, page)
    }

    private class TypeFilter : AnimeFilter.Select<String>("Type", arrayOf("All", "Movies", "TV Shows")) {
        fun toValue() = when (state) {
            1 -> "Movie"
            2 -> "Series"
            else -> "Movie,Series"
        }
    }

    private class CategoryFilter(val categories: List<Pair<String, String>>) : AnimeFilter.Select<String>("Category", categories.map { it.first }.toTypedArray()) {
        fun toValue() = categories[state].second
    }

    private class GenreFilter(val genres: List<Pair<String, String>>) : AnimeFilter.Select<String>("Genre", genres.map { it.first }.toTypedArray()) {
        fun toValue() = genres[state].second
    }

    private class SortFilter : AnimeFilter.Sort("Sort by", arrayOf("Name", "Date Added", "Premiere Date"), Selection(0, false)) {
        private val sortables = arrayOf("SortName", "DateCreated", "ProductionYear")
        fun toSortValue() = sortables[state!!.index]
        fun isAscending() = state!!.ascending
    }
}

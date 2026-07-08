package eu.kanade.tachiyomi.animeextension.all.amaderftp

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.multisrc.jellyfin.Jellyfin
import eu.kanade.tachiyomi.multisrc.jellyfin.ItemListDto
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import eu.kanade.tachiyomi.network.GET
import extensions.utils.parseAs

class AmaderFtp : Jellyfin("Amader FTP") {
    override val defaultBaseUrl = "http://amaderftp.net:8096"
    override val defaultUsername = "user"
    override val defaultPassword = "1234"
    override val hasCustomAuthSettings = true

    private val staticCategories = listOf(
        Pair("All", ""),
        Pair("ENGLISH", "4f9a1aee122b0b1d02c34ba39f31e331"),
        Pair("HINDI", "4146eee110de8dd20f0a48dd88ca9f44"),
        Pair("TV SERIES", "ea34d9f8d8b815c9ee04e1b30418f93d"),
        Pair("ANIMATION", "3c31655512f355224c80fb9d26b96a86"),
        Pair("BANGLA", "9b9a8e2554388a4174be75fa66e0fd61"),
        Pair("DUBBED", "5705248032de005e70b2bc776246006f"),
        Pair("TAMIL", "5a4fc1fc9e647e37b145a379afc74171"),
        Pair("3D MOVIES", "162206c46a6e4cafcbeb6afe0bcabd05"),
    )

    override fun getFilterList(): AnimeFilterList {
        val genres = fetchGenres()
        return AnimeFilterList(
            CategoryFilter(staticCategories),
            SortFilter(),
            GenreFilter(genres),
        )
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val startIndex = (page - 1) * 20
        val url = getItemsUrl(startIndex).newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("SearchTerm", query)
            filters.forEach { filter ->
                when (filter) {
                    is CategoryFilter -> if (filter.toValue().isNotBlank()) setQueryParameter("ParentId", filter.toValue())
                    is GenreFilter -> {
                        val genres = filter.state.filter { it.state }.map { it.id }
                        if (genres.isNotEmpty()) setQueryParameter("GenreIds", genres.joinToString(","))
                    }
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

    private var genresCache: List<Pair<String, String>>? = null
    private var isFetchingInternal = false

    private fun fetchGenres(): List<Pair<String, String>> {
        val cache = genresCache
        if (cache != null && cache.isNotEmpty()) return cache

        val cachedJson = preferences.getString("pref_cached_genres", null)
        if (cachedJson != null) {
            try {
                val list = mutableListOf<Pair<String, String>>()
                val array = json.parseToJsonElement(cachedJson).jsonArray
                array.forEach {
                    val obj = it.jsonObject
                    list.add(Pair(obj["name"]!!.jsonPrimitive.content, obj["id"]!!.jsonPrimitive.content))
                }
                if (list.isNotEmpty()) {
                    genresCache = list
                    return list
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (isFetchingInternal) return emptyList()

        val list = mutableListOf<Pair<String, String>>()
        try {
            if (userId.isNotBlank()) {
                isFetchingInternal = true
                val url = "$baseUrl/Genres".toHttpUrl().newBuilder()
                    .addQueryParameter("Recursive", "true")
                    .addQueryParameter("IncludeItemTypes", "Movie,Series")
                    .build()
                val resp = client.newCall(GET(url.toString())).execute()
                if (resp.isSuccessful) {
                    val items = resp.parseAs<ItemListDto>(json)
                    items.items.forEach { list.add(Pair(it.name, it.id)) }

                    if (list.isNotEmpty()) {
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
                        genresCache = list.sortedBy { it.first }
                    }
                }
                isFetchingInternal = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isFetchingInternal = false
        }
        return genresCache ?: emptyList()
    }

    private class GenreFilter(genres: List<Pair<String, String>>) : AnimeFilter.Group<GenreCheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) })
    private class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name)
}

package eu.kanade.tachiyomi.animeextension.all.netmovie

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultIndex: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultIndex) {
        fun toUriPart() = vals[state].second
    }

    class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("Bollywood", "bollywood"),
                Pair("Hollywood", "hollywood"),
                Pair("TV Series", "serials"),
            ),
        )

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        CategoryFilter(),
    )
}

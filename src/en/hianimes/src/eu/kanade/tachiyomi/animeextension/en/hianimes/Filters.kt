package eu.kanade.tachiyomi.animeextension.en.hianimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("Movie", "movie"),
                Pair("TV Series", "tv"),
                Pair("OVA", "ova"),
                Pair("Special", "special"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Ongoing", "ongoing"),
                Pair("Completed", "completed"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Default", ""),
                Pair("Latest Update", "latest"),
                Pair("Most Popular", "popular"),
                Pair("Rating", "rating"),
            ),
        )

    class YearFilter : AnimeFilter.Text("Year", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    class GenreTriState(name: String, val id: String) : AnimeFilter.TriState(name)
    class GenreTriStateFilter(genres: List<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.TriState>("Genres (Include / Exclude)", genres.map { GenreTriState(it.first, it.second) }) {
        fun included(): List<String> = state.filter { it.isIncluded() }.map { (it as GenreTriState).id }
        fun excluded(): List<String> = state.filter { it.isExcluded() }.map { (it as GenreTriState).id }
    }
}

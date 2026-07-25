package eu.kanade.tachiyomi.animeextension.en.fouranimo

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class TypeFilter :
        UriPartFilter(
            "Format / Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "TV"),
                Pair("Movie", "MOVIE"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
                Pair("Special", "SPECIAL"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Releasing (Ongoing)", "RELEASING"),
                Pair("Finished", "FINISHED"),
                Pair("Not Yet Released", "NOT_YET_RELEASED"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Default", ""),
                Pair("Most Popular", "most_popular"),
                Pair("Recently Added", "recently_added"),
                Pair("Latest Update", "latest_update"),
                Pair("Rating", "rating"),
                Pair("Title", "title"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("All", ""),
                Pair("Winter", "WINTER"),
                Pair("Spring", "SPRING"),
                Pair("Summer", "SUMMER"),
                Pair("Fall", "FALL"),
            ),
        )

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            listOf(
                Pair("Action", "Action"),
                Pair("Adventure", "Adventure"),
                Pair("Comedy", "Comedy"),
                Pair("Drama", "Drama"),
                Pair("Ecchi", "Ecchi"),
                Pair("Fantasy", "Fantasy"),
                Pair("Historical", "Historical"),
                Pair("Horror", "Horror"),
                Pair("Isekai", "Isekai"),
                Pair("Magic", "Magic"),
                Pair("Mecha", "Mecha"),
                Pair("Music", "Music"),
                Pair("Mystery", "Mystery"),
                Pair("Psychological", "Psychological"),
                Pair("Romance", "Romance"),
                Pair("School", "School"),
                Pair("Sci-Fi", "Sci-Fi"),
                Pair("Slice of Life", "Slice of Life"),
                Pair("Sports", "Sports"),
                Pair("Supernatural", "Supernatural"),
                Pair("Thriller", "Thriller"),
            ).map { GenreCheckBox(it.first, it.second) },
        ) {
        fun selected(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }
}

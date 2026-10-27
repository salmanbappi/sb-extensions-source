package eu.kanade.tachiyomi.animeextension.en.watchanimez

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultIndex: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultIndex) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Trending", "TRENDING_DESC"),
                Pair("Most Popular", "POPULARITY_DESC"),
                Pair("Top Rated", "SCORE_DESC"),
                Pair("Recently Updated", "UPDATED_AT_DESC"),
                Pair("Upcoming", "START_DATE_DESC"),
            ),
        )

    class GenreModeFilter(defaultIndex: Int = 0) :
        UriPartFilter(
            "Genre Search Mode",
            arrayOf(
                Pair("AND", "and"),
                Pair("OR", "or"),
            ),
            defaultIndex,
        )

    class Genre(name: String, val id: String = name) : AnimeFilter.CheckBox(name, false)

    class GenreFilter :
        AnimeFilter.Group<Genre>(
            "Genres",
            GENRES.map { Genre(it) },
        ) {
        fun getSelectedGenres(): List<String> = state.filter { it.state }.map { it.id }
    }

    class FormatFilter :
        UriPartFilter(
            "Format",
            arrayOf(
                Pair("All", ""),
                Pair("TV Series", "TV"),
                Pair("TV Short", "TV_SHORT"),
                Pair("Movie", "MOVIE"),
                Pair("Special", "SPECIAL"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
                Pair("Music Video", "MUSIC"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Releasing", "RELEASING"),
                Pair("Finished", "FINISHED"),
                Pair("Not Yet Released", "NOT_YET_RELEASED"),
                Pair("On Hiatus", "HIATUS"),
                Pair("Cancelled", "CANCELLED"),
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

    class YearFilter : AnimeFilter.Text("Year (e.g. 2024)", "")

    val GENRES = listOf(
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Ecchi",
        "Fantasy",
        "Horror",
        "Mahou Shoujo",
        "Mecha",
        "Music",
        "Mystery",
        "Psychological",
        "Romance",
        "Sci-Fi",
        "Slice of Life",
        "Sports",
        "Supernatural",
        "Thriller",
    )
}

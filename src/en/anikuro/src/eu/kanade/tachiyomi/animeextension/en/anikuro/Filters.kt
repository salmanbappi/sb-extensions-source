package eu.kanade.tachiyomi.animeextension.en.anikuro

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {

    open class SelectFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun selectedValue() = vals[state].second
        fun isDefault() = state == 0
    }

    class SortFilter : SelectFilter(
        "Sort By",
        arrayOf(
            Pair("Popularity", "POPULARITY_DESC"),
            Pair("Trending", "TRENDING_DESC"),
            Pair("Score", "SCORE_DESC"),
            Pair("Start Date", "START_DATE_DESC"),
            Pair("Episodes", "EPISODES_DESC"),
        ),
    )

    class FormatFilter : SelectFilter(
        "Format",
        arrayOf(
            Pair("All", ""),
            Pair("TV", "TV"),
            Pair("TV Short", "TV_SHORT"),
            Pair("Movie", "MOVIE"),
            Pair("Special", "SPECIAL"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Music", "MUSIC"),
        ),
    )

    class StatusFilter : SelectFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Releasing", "RELEASING"),
            Pair("Finished", "FINISHED"),
            Pair("Not Yet Released", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
            Pair("Hiatus", "HIATUS"),
        ),
    )

    class SeasonFilter : SelectFilter(
        "Season",
        arrayOf(
            Pair("All", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        ),
    )

    class CheckBoxVal(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter : AnimeFilter.Group<CheckBoxVal>(
        "Genres",
        GENRES.map { CheckBoxVal(it.first, it.second) },
    ) {
        fun selectedGenres(): List<String> = state.filter { it.state }.map { it.id }
    }

    private val GENRES = listOf(
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Comedy", "Comedy"),
        Pair("Drama", "Drama"),
        Pair("Ecchi", "Ecchi"),
        Pair("Fantasy", "Fantasy"),
        Pair("Historical", "Historical"),
        Pair("Horror", "Horror"),
        Pair("Mahou Shoujo", "Mahou Shoujo"),
        Pair("Mecha", "Mecha"),
        Pair("Music", "Music"),
        Pair("Mystery", "Mystery"),
        Pair("Psychological", "Psychological"),
        Pair("Romance", "Romance"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Slice of Life", "Slice of Life"),
        Pair("Sports", "Sports"),
        Pair("Supernatural", "Supernatural"),
        Pair("Thriller", "Thriller"),
    )
}

package eu.kanade.tachiyomi.animeextension.en.bingr

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultValue) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Trending", "TRENDING_DESC"),
                Pair("Popularity", "POPULARITY_DESC"),
                Pair("Score", "SCORE_DESC"),
                Pair("Favourites", "FAVOURITES_DESC"),
                Pair("Release Date", "START_DATE_DESC"),
            ),
        )

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
                Pair("Music", "MUSIC"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Finished", "FINISHED"),
                Pair("Releasing", "RELEASING"),
                Pair("Not Yet Released", "NOT_YET_RELEASED"),
                Pair("Cancelled", "CANCELLED"),
                Pair("Hiatus", "HIATUS"),
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

    class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("All", ""),
                Pair("Action", "Action"),
                Pair("Adventure", "Adventure"),
                Pair("Comedy", "Comedy"),
                Pair("Drama", "Drama"),
                Pair("Fantasy", "Fantasy"),
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
            ),
        )
}

package eu.kanade.tachiyomi.animeextension.en.myasiantv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class CountryFilter : UriPartFilter(
        "Country / Region",
        arrayOf(
            Pair("All Countries", ""),
            Pair("Korean Drama", "country/south-korea"),
            Pair("Japanese Drama", "country/japan"),
            Pair("Chinese Drama", "country/china"),
            Pair("Thailand Drama", "country/thailand"),
            Pair("Philippines Drama", "country/philippines"),
            Pair("Taiwanese Drama", "country/taiwan"),
            Pair("Hong Kong Drama", "country/hong-kong"),
        ),
    )

    class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("All Genres", ""),
            Pair("Action", "genres/action"),
            Pair("Business", "genres/business"),
            Pair("Comedy", "genres/comedy"),
            Pair("Drama", "genres/drama"),
            Pair("Fantasy", "genres/fantasy"),
            Pair("Historical", "genres/historical"),
            Pair("Law", "genres/law"),
            Pair("Life", "genres/life"),
            Pair("Melodrama", "genres/melodrama"),
            Pair("Music", "genres/music"),
            Pair("Mystery", "genres/mystery"),
            Pair("Political", "genres/political"),
            Pair("Romance", "genres/romance"),
            Pair("Youth", "genres/youth"),
        ),
    )

    class BrowseFilter : UriPartFilter(
        "Browse Category",
        arrayOf(
            Pair("Popular Dramas", "popular-series"),
            Pair("Recently Added", "recently-added"),
            Pair("Drama List (All)", "drama-list"),
            Pair("Drama List (0-9 / Other)", "drama-list/drama-start-with-other"),
            Pair("Drama List (A)", "drama-list/drama-start-with-a"),
            Pair("Drama List (B)", "drama-list/drama-start-with-b"),
            Pair("Drama List (C)", "drama-list/drama-start-with-c"),
            Pair("Drama List (D)", "drama-list/drama-start-with-d"),
            Pair("Drama List (E)", "drama-list/drama-start-with-e"),
            Pair("Drama List (F)", "drama-list/drama-start-with-f"),
            Pair("Drama List (G)", "drama-list/drama-start-with-g"),
            Pair("Drama List (H)", "drama-list/drama-start-with-h"),
            Pair("Drama List (I)", "drama-list/drama-start-with-i"),
            Pair("Drama List (J)", "drama-list/drama-start-with-j"),
            Pair("Drama List (K)", "drama-list/drama-start-with-k"),
            Pair("Drama List (L)", "drama-list/drama-start-with-l"),
            Pair("Drama List (M)", "drama-list/drama-start-with-m"),
            Pair("Drama List (N)", "drama-list/drama-start-with-n"),
            Pair("Drama List (O)", "drama-list/drama-start-with-o"),
            Pair("Drama List (P)", "drama-list/drama-start-with-p"),
            Pair("Drama List (Q)", "drama-list/drama-start-with-q"),
            Pair("Drama List (R)", "drama-list/drama-start-with-r"),
            Pair("Drama List (S)", "drama-list/drama-start-with-s"),
            Pair("Drama List (T)", "drama-list/drama-start-with-t"),
            Pair("Drama List (U)", "drama-list/drama-start-with-u"),
            Pair("Drama List (V)", "drama-list/drama-start-with-v"),
            Pair("Drama List (W)", "drama-list/drama-start-with-w"),
            Pair("Drama List (X)", "drama-list/drama-start-with-x"),
            Pair("Drama List (Y)", "drama-list/drama-start-with-y"),
            Pair("Drama List (Z)", "drama-list/drama-start-with-z"),
        ),
    )
}

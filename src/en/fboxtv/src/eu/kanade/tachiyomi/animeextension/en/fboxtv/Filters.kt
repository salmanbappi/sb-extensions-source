package eu.kanade.tachiyomi.animeextension.en.fboxtv

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
                Pair("All", "all"),
                Pair("Movies", "movie"),
                Pair("TV Shows", "tv"),
            ),
        )

    class QualityFilter :
        UriPartFilter(
            "Quality",
            arrayOf(
                Pair("All", "all"),
                Pair("HD", "HD"),
                Pair("SD", "SD"),
                Pair("CAM", "CAM"),
            ),
        )

    class YearFilter :
        UriPartFilter(
            "Release year",
            arrayOf(
                Pair("All", "all"),
                Pair("2026", "2026"),
                Pair("2025", "2025"),
                Pair("2024", "2024"),
                Pair("2023", "2023"),
                Pair("2022", "2022"),
                Pair("2021 & older", "older-2021"),
            ),
        )

    class CountryFilter :
        UriPartFilter(
            "Country",
            arrayOf(
                Pair("All", "all"),
                Pair("Argentina", "11"),
                Pair("Australia", "151"),
                Pair("Austria", "4"),
                Pair("Belgium", "44"),
                Pair("Brazil", "190"),
                Pair("Canada", "147"),
                Pair("China", "101"),
                Pair("Czech Republic", "231"),
                Pair("Denmark", "222"),
                Pair("Finland", "158"),
                Pair("France", "3"),
                Pair("Germany", "96"),
                Pair("Hong Kong", "93"),
                Pair("Hungary", "72"),
                Pair("India", "105"),
                Pair("Ireland", "196"),
                Pair("Israel", "24"),
                Pair("Italy", "205"),
                Pair("Japan", "173"),
                Pair("Luxembourg", "116"),
                Pair("Mexico", "40"),
                Pair("Netherlands", "172"),
                Pair("New Zealand", "122"),
                Pair("Norway", "50"),
                Pair("Poland", "23"),
                Pair("Romania", "170"),
                Pair("Russia", "109"),
                Pair("South Africa", "200"),
                Pair("South Korea", "135"),
                Pair("Spain", "62"),
                Pair("Sweden", "114"),
                Pair("Switzerland", "41"),
                Pair("Taiwan", "119"),
                Pair("Thailand", "57"),
                Pair("United Kingdom", "180"),
                Pair("United States of America", "129"),
            ),
        )

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    /**
     * fboxtv.bz accepts multiple genre ids joined with "-" (e.g. `genre=14-2`).
     */
    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            GENRES.map { GenreCheckBox(it.first, it.second) },
        ) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    private val GENRES = listOf(
        Pair("Action", "10"),
        Pair("Action & Adventure", "24"),
        Pair("Adventure", "18"),
        Pair("Animation", "3"),
        Pair("Comedy", "7"),
        Pair("Crime", "2"),
        Pair("Documentary", "11"),
        Pair("Drama", "4"),
        Pair("Family", "9"),
        Pair("Fantasy", "13"),
        Pair("History", "19"),
        Pair("Horror", "14"),
        Pair("Kids", "27"),
        Pair("Music", "15"),
        Pair("Mystery", "1"),
        Pair("News", "34"),
        Pair("Reality", "22"),
        Pair("Romance", "12"),
        Pair("Sci-Fi & Fantasy", "31"),
        Pair("Science Fiction", "5"),
        Pair("Soap", "35"),
        Pair("Talk", "29"),
        Pair("Thriller", "16"),
        Pair("TV Movie", "8"),
        Pair("War", "17"),
        Pair("War & Politics", "28"),
        Pair("Western", "6"),
    )
}

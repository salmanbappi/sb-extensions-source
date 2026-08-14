package eu.kanade.tachiyomi.animeextension.en.movies2watch

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class TypeFilter : UriPartFilter("Type", arrayOf(
        Pair("All", "all"),
        Pair("Movie", "movie"),
        Pair("TV Series", "series"),
    ))

    class GenreFilter : UriPartFilter("Genre", arrayOf(
        Pair("All genres", ""),
        Pair("Drama", "1"),
        Pair("Comedy", "2"),
        Pair("Thriller", "11"),
        Pair("Documentary", "13"),
        Pair("Horror", "10"),
        Pair("Romance", "7"),
        Pair("Action", "8"),
        Pair("Crime", "5"),
        Pair("Mystery", "14"),
        Pair("Adventure", "9"),
        Pair("TV Movie", "19"),
        Pair("Family", "16"),
        Pair("Science Fiction", "15"),
        Pair("Animation", "17"),
        Pair("Fantasy", "12"),
        Pair("Music", "18"),
        Pair("History", "3"),
        Pair("War", "4"),
        Pair("Western", "6"),
        Pair("Sci-Fi & Fantasy", "20"),
        Pair("Action & Adventure", "21"),
        Pair("Reality", "24"),
        Pair("Kids", "26"),
        Pair("War & Politics", "22"),
        Pair("Talk", "23"),
        Pair("Soap", "27"),
        Pair("News", "28"),
        Pair("Musical", "25"),
    ))

    class CountryFilter : UriPartFilter("Country", arrayOf(
        Pair("All countries", ""),
        Pair("United States of America", "1"),
        Pair("United Kingdom", "6"),
        Pair("France", "4"),
        Pair("Canada", "25"),
        Pair("Japan", "10"),
        Pair("Germany", "2"),
        Pair("Italy", "18"),
        Pair("India", "12"),
        Pair("Spain", "26"),
        Pair("South Korea", "29"),
        Pair("Australia", "21"),
        Pair("Hong Kong", "27"),
        Pair("China", "58"),
        Pair("Belgium", "45"),
        Pair("Sweden", "3"),
        Pair("Mexico", "16"),
        Pair("Netherlands", "13"),
        Pair("Poland", "28"),
        Pair("Ireland", "34"),
        Pair("Denmark", "9"),
        Pair("Brazil", "30"),
        Pair("Turkey", "52"),
        Pair("Philippines", "19"),
        Pair("Argentina", "38"),
        Pair("Romania", "75"),
        Pair("Switzerland", "11"),
        Pair("Norway", "24"),
        Pair("Taiwan", "39"),
        Pair("Russia", "70"),
        Pair("Thailand", "51"),
        Pair("South Africa", "50"),
        Pair("Finland", "23"),
        Pair("Indonesia", "64"),
        Pair("Austria", "7"),
        Pair("New Zealand", "56"),
        Pair("Hungary", "17"),
        Pair("Czech Republic", "74"),
        Pair("Israel", "41"),
        Pair("Greece", "37"),
        Pair("Portugal", "14"),
        Pair("Chile", "85"),
        Pair("Colombia", "90"),
        Pair("Nigeria", "43"),
        Pair("Ukraine", "71"),
        Pair("Singapore", "86"),
        Pair("Bulgaria", "36"),
        Pair("Iran", "42"),
        Pair("Vietnam", "66"),
        Pair("Malaysia", "93"),
        Pair("Iceland", "65"),
    ))

    class YearFilter : UriPartFilter("Release Year", arrayOf(
        Pair("All years", ""),
        Pair("2026", "2026"),
        Pair("2025", "2025"),
        Pair("2024", "2024"),
        Pair("2023", "2023"),
        Pair("2022", "2022"),
        Pair("2021", "2021"),
        Pair("2020", "2020"),
        Pair("2019", "2019"),
        Pair("2018", "2018"),
        Pair("2017", "2017"),
        Pair("2016", "2016"),
        Pair("2015", "2015"),
        Pair("2014", "2014"),
        Pair("2013", "2013"),
        Pair("2012", "2012"),
        Pair("2011", "2011"),
        Pair("2010", "2010"),
        Pair("2009", "2009"),
        Pair("2008", "2008"),
        Pair("2007", "2007"),
        Pair("2006", "2006"),
        Pair("2005", "2005"),
        Pair("2004", "2004"),
        Pair("2003", "2003"),
        Pair("2002", "2002"),
        Pair("2001", "2001"),
        Pair("2000", "2000"),
        Pair("1990s", "1990"),
        Pair("1980s", "1980"),
    ))

    class RatingFilter : UriPartFilter("Minimum Rating", arrayOf(
        Pair("Any rating", ""),
        Pair("5+ IMDb", "5"),
        Pair("6+ IMDb", "6"),
        Pair("7+ IMDb", "7"),
        Pair("8+ IMDb", "8"),
        Pair("9+ IMDb", "9"),
    ))

    class SortFilter : UriPartFilter("Sort By", arrayOf(
        Pair("Last updated", "updated"),
        Pair("IMDb rating", "rating"),
        Pair("Release year", "year"),
        Pair("Title", "title"),
    ))

    class OrderFilter : UriPartFilter("Order", arrayOf(
        Pair("Descending", "desc"),
        Pair("Ascending", "asc"),
    ))
}

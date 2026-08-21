package eu.kanade.tachiyomi.animeextension.en.dramanice

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class CategoryFilter : UriPartFilter("Category", arrayOf(
        Pair("All (Latest)", ""),
        Pair("Most Popular Drama", "most-popular-drama"),
        Pair("Drama Movie", "drama-movies"),
        Pair("Drama Show", "drama-show"),
    ))

    class CountryFilter : UriPartFilter("Country", arrayOf(
        Pair("All", ""),
        Pair("South Korea", "south-korea"),
        Pair("China", "china"),
        Pair("Japan", "japan"),
        Pair("Taiwan", "taiwan"),
        Pair("Hong Kong", "hong-kong"),
        Pair("Thailand", "thailand"),
        Pair("Other", "other"),
    ))

    class GenreFilter : UriPartFilter("Genre", arrayOf(
        Pair("All", ""),
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Crime", "crime"),
        Pair("Drama", "drama"),
        Pair("Family", "family"),
        Pair("Fantasy", "fantasy"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Mystery", "mystery"),
        Pair("Romance", "romance"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Thriller", "thriller"),
        Pair("Youth", "youth"),
    ))

    class YearFilter : UriPartFilter("Release Year", arrayOf(
        Pair("All", ""),
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
    ))
}

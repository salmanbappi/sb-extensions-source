package eu.kanade.tachiyomi.animeextension.en.goplay

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("All", ""),
                Pair("K-Drama", "k-drama"),
                Pair("C-Drama", "c-drama"),
                Pair("J-Drama", "j-drama"),
                Pair("Variety Show", "variety"),
                Pair("Movies", "movies"),
                Pair("Anime", "anime"),
            ),
        )

    class CountryFilter :
        UriPartFilter(
            "Country",
            arrayOf(
                Pair("All", ""),
                Pair("South Korea", "korea"),
                Pair("China", "china"),
                Pair("Japan", "japan"),
                Pair("Taiwan", "taiwan"),
                Pair("Thailand", "thailand"),
                Pair("Hong Kong", "hong-kong"),
            ),
        )

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("Drama Series", "drama"),
                Pair("Movie", "movie"),
                Pair("Special", "special"),
                Pair("TV Show", "tv-show"),
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
                Pair("Highest Rated", "rating"),
                Pair("Release Date", "release"),
            ),
        )

    class YearFilter : AnimeFilter.Text("Year", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter(genres: List<Pair<String, String>> = defaultGenres) : AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    val defaultGenres = listOf(
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Crime", "crime"),
        Pair("Drama", "drama"),
        Pair("Family", "family"),
        Pair("Fantasy", "fantasy"),
        Pair("Food", "food"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Idol Drama", "idol-drama"),
        Pair("Law", "law"),
        Pair("Life", "life"),
        Pair("Medical", "medical"),
        Pair("Melodrama", "melodrama"),
        Pair("Military", "military"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Political", "political"),
        Pair("Psychological", "psychological"),
        Pair("Romance", "romance"),
        Pair("School", "school"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Sitcom", "sitcom"),
        Pair("Sports", "sports"),
        Pair("Supernatural", "supernatural"),
        Pair("Suspense", "suspense"),
        Pair("Thriller", "thriller"),
        Pair("Tokusatsu", "tokusatsu"),
        Pair("Tragedy", "tragedy"),
        Pair("Vampire", "vampire"),
        Pair("Wuxia", "wuxia"),
        Pair("Youth", "youth"),
    )
}

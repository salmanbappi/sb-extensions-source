package eu.kanade.tachiyomi.animeextension.en.cinejoy

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("Movie", "movie"),
            Pair("TV Series", "tv"),
        ),
    )

    class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Popularity Descending", "popularity.desc"),
            Pair("Popularity Ascending", "popularity.asc"),
            Pair("Release Date Descending", "primary_release_date.desc"),
            Pair("Release Date Ascending", "primary_release_date.asc"),
            Pair("Rating Descending", "vote_average.desc"),
            Pair("Rating Ascending", "vote_average.asc"),
            Pair("Title (A-Z)", "title.asc"),
            Pair("Title (Z-A)", "title.desc"),
        ),
    )

    class YearFilter : AnimeFilter.Text("Release Year (e.g. 2024)", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Genres",
        listOf(
            GenreCheckBox("Action", "28"),
            GenreCheckBox("Adventure", "12"),
            GenreCheckBox("Animation", "16"),
            GenreCheckBox("Comedy", "35"),
            GenreCheckBox("Crime", "80"),
            GenreCheckBox("Documentary", "99"),
            GenreCheckBox("Drama", "18"),
            GenreCheckBox("Family", "10751"),
            GenreCheckBox("Fantasy", "14"),
            GenreCheckBox("History", "36"),
            GenreCheckBox("Horror", "27"),
            GenreCheckBox("Music", "10402"),
            GenreCheckBox("Mystery", "9648"),
            GenreCheckBox("Romance", "10749"),
            GenreCheckBox("Science Fiction", "878"),
            GenreCheckBox("TV Movie", "10770"),
            GenreCheckBox("Thriller", "53"),
            GenreCheckBox("War", "10752"),
            GenreCheckBox("Western", "37"),
            GenreCheckBox("Action & Adventure (TV)", "10759"),
            GenreCheckBox("Kids (TV)", "10762"),
            GenreCheckBox("News (TV)", "10763"),
            GenreCheckBox("Reality (TV)", "10764"),
            GenreCheckBox("Sci-Fi & Fantasy (TV)", "10765"),
            GenreCheckBox("Soap (TV)", "10766"),
            GenreCheckBox("Talk (TV)", "10767"),
            GenreCheckBox("War & Politics (TV)", "10768"),
        ),
    ) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }
}

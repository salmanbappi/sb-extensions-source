package eu.kanade.tachiyomi.animeextension.en.subdubanime

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
                Pair("All", ""),
                Pair("Series", "Series"),
                Pair("Movie", "Movie"),
                Pair("Drama", "Drama"),
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Language",
            arrayOf(
                Pair("All", ""),
                Pair("Hindi Dubbed (ORG)", "ORG"),
                Pair("Hindi Fan Dubbed", "Fandub"),
                Pair("English Subbed", "English Subbed"),
                Pair("English Dubbed", "English Dubbed"),
                Pair("Hindi Subbed", "Hindi Subbed"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Ongoing", "Ongoing"),
                Pair("Completed", "Completed"),
                Pair("Released", "Released"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Latest Updated", "updated"),
                Pair("Latest Added", "created"),
                Pair("Title (A-Z)", "title_asc"),
                Pair("Title (Z-A)", "title_desc"),
                Pair("Rating", "rating"),
            ),
        )

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    val GENRES = listOf(
        Pair("Action", "Action"),
        Pair("Action & Adventure", "Action & Adventure"),
        Pair("Adventure", "Adventure"),
        Pair("Animation", "Animation"),
        Pair("Comedy", "Comedy"),
        Pair("Crime", "Crime"),
        Pair("Drama", "Drama"),
        Pair("Family", "Family"),
        Pair("Fantasy", "Fantasy"),
        Pair("History", "History"),
        Pair("Horror", "Horror"),
        Pair("Kids", "Kids"),
        Pair("Music", "Music"),
        Pair("Mystery", "Mystery"),
        Pair("Romance", "Romance"),
        Pair("Sci-Fi & Fantasy", "Sci-Fi & Fantasy"),
        Pair("Science Fiction", "Science Fiction"),
        Pair("Thriller", "Thriller"),
        Pair("War", "War"),
        Pair("War & Politics", "War & Politics"),
    )
}

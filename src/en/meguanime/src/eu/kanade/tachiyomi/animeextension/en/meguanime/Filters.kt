package eu.kanade.tachiyomi.animeextension.en.meguanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toValue() = vals[state].second
    }

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Trending", "TRENDING_DESC"),
                Pair("Popularity", "POPULARITY_DESC"),
                Pair("Average Score", "SCORE_DESC"),
                Pair("Title (Romaji)", "TITLE_ROMAJI"),
                Pair("Title (English)", "TITLE_ENGLISH"),
                Pair("Recently Updated", "UPDATED_AT_DESC"),
                Pair("Release Date", "START_DATE_DESC"),
                Pair("Favourites", "FAVOURITES_DESC"),
            ),
        )

    class FormatFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Format",
            listOf(
                "TV",
                "TV Short",
                "Movie",
                "Special",
                "OVA",
                "ONA",
                "Music",
            ).map { FormatCheckBox(it) },
        ) {
        fun getCheckedValues(): List<String> = state.filter { it.state }.map {
            when (it.name) {
                "TV" -> "TV"
                "TV Short" -> "TV_SHORT"
                "Movie" -> "MOVIE"
                "Special" -> "SPECIAL"
                "OVA" -> "OVA"
                "ONA" -> "ONA"
                "Music" -> "MUSIC"
                else -> it.name
            }
        }
    }

    class FormatCheckBox(name: String) : AnimeFilter.CheckBox(name, false)

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("Any", ""),
                Pair("Releasing", "RELEASING"),
                Pair("Finished", "FINISHED"),
                Pair("Not Yet Released", "NOT_YET_RELEASED"),
                Pair("Cancelled", "CANCELLED"),
                Pair("Hiatus", "HIATUS"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("Any", ""),
                Pair("Winter", "WINTER"),
                Pair("Spring", "SPRING"),
                Pair("Summer", "SUMMER"),
                Pair("Fall", "FALL"),
            ),
        )

    class YearFilter : AnimeFilter.Text("Release Year", "")

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            listOf(
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
            ).map { GenreCheckBox(it) },
        ) {
        fun getCheckedValues(): List<String> = state.filter { it.state }.map { it.name }
    }

    class GenreCheckBox(name: String) : AnimeFilter.CheckBox(name, false)
}

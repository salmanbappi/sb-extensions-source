package eu.kanade.tachiyomi.animeextension.en.anigato

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popularity", "POPULARITY_DESC"),
                Pair("Trending", "TRENDING_DESC"),
                Pair("Score", "SCORE_DESC"),
                Pair("Release Date", "START_DATE_DESC"),
                Pair("Favorites", "FAVOURITES_DESC"),
            ),
        )

    class FormatFilter :
        UriPartFilter(
            "Format",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "TV"),
                Pair("TV Short", "TV_SHORT"),
                Pair("Movie", "MOVIE"),
                Pair("Special", "SPECIAL"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Airing", "RELEASING"),
                Pair("Finished", "FINISHED"),
                Pair("Not Yet Aired", "NOT_YET_RELEASED"),
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

    class YearFilter : AnimeFilter.Text("Year", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter(genres: List<Pair<String, String>>) : AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    private val GENRES = listOf(
        "Action" to "Action",
        "Adventure" to "Adventure",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
        "Ecchi" to "Ecchi",
        "Fantasy" to "Fantasy",
        "Horror" to "Horror",
        "Mahou Shoujo" to "Mahou Shoujo",
        "Mecha" to "Mecha",
        "Music" to "Music",
        "Mystery" to "Mystery",
        "Psychological" to "Psychological",
        "Romance" to "Romance",
        "Sci-Fi" to "Sci-Fi",
        "Slice of Life" to "Slice of Life",
        "Sports" to "Sports",
        "Supernatural" to "Supernatural",
        "Thriller" to "Thriller",
    )

    fun build(): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
        GenreFilter(GENRES),
    )
}

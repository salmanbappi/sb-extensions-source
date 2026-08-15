package eu.kanade.tachiyomi.animeextension.en.anilight

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultState: Int = 0,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), defaultState) {
        fun toUriPart() = vals[state].second
    }

    class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Most Popular", "POPULARITY_DESC"),
            Pair("Trending", "TRENDING_DESC"),
            Pair("Highest Rated", "SCORE_DESC"),
            Pair("Most Favorites", "FAVOURITES_DESC"),
            Pair("Most Episodes", "EPISODES_DESC"),
            Pair("Title (A-Z)", "TITLE_ROMAJI"),
        ),
    )

    class FormatFilter : UriPartFilter(
        "Format",
        arrayOf(
            Pair("All", ""),
            Pair("TV Series", "TV"),
            Pair("Movie", "MOVIE"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "SPECIAL"),
        ),
    )

    class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Releasing", "RELEASING"),
            Pair("Completed", "FINISHED"),
            Pair("Not Yet Released", "NOT_YET_RELEASED"),
            Pair("Cancelled", "CANCELLED"),
        ),
    )

    class SeasonFilter : UriPartFilter(
        "Season",
        arrayOf(
            Pair("All", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        ),
    )

    class YearFilter : AnimeFilter.Text("Season Year (e.g. 2024)", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter(genres: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    val GENRES = listOf(
        "Action" to "Action",
        "Adventure" to "Adventure",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
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

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Search query overrides some filters"),
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
        AnimeFilter.Separator(),
        GenreFilter(GENRES),
    )
}

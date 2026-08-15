package eu.kanade.tachiyomi.animeextension.en.anichan

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class SortFilter : UriPartFilter("Sort By", arrayOf(
        Pair("Most Popular", "POPULARITY_DESC"),
        Pair("Highest Rated", "SCORE_DESC"),
        Pair("Newest", "START_DATE_DESC"),
        Pair("Title A-Z", "TITLE"),
    ))

    class FormatFilter : UriPartFilter("Format", arrayOf(
        Pair("Any Format", ""),
        Pair("TV", "TV"),
        Pair("TV Short", "TV_SHORT"),
        Pair("Movie", "MOVIE"),
        Pair("OVA", "OVA"),
        Pair("ONA", "ONA"),
        Pair("Special", "SPECIAL"),
    ))

    class SeasonFilter : UriPartFilter("Season", arrayOf(
        Pair("Any Season", ""),
        Pair("Winter", "WINTER"),
        Pair("Spring", "SPRING"),
        Pair("Summer", "SUMMER"),
        Pair("Fall", "FALL"),
    ))

    class StatusFilter : UriPartFilter("Status", arrayOf(
        Pair("Any Status", ""),
        Pair("Releasing", "RELEASING"),
        Pair("Finished", "FINISHED"),
        Pair("Upcoming", "NOT_YET_RELEASED"),
        Pair("Cancelled", "CANCELLED"),
    ))

    class YearFilter : AnimeFilter.Text("Year", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
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

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search combines with filters on AniChan"),
        SortFilter(),
        FormatFilter(),
        StatusFilter(),
        SeasonFilter(),
        YearFilter(),
        AnimeFilter.Separator(),
        GenreFilter(GENRES),
    )
}

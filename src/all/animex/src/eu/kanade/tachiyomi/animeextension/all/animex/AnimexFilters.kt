package eu.kanade.tachiyomi.animeextension.all.animex

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

open class UriPartFilter(
    displayName: String,
    val vals: Array<Pair<String, String>>,
) : AnimeFilter.Select<String>(
    displayName,
    vals.map { it.first }.toTypedArray(),
) {
    fun toValue() = vals[state].second
}

class SortFilter :
    UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Trending", "TRENDING_DESC"),
            Pair("Popularity", "POPULARITY_DESC"),
            Pair("Average Score", "SCORE_DESC"),
            Pair("Favourites", "FAVOURITES_DESC"),
            Pair("Newest", "START_DATE_DESC"),
            Pair("Oldest", "START_DATE"),
            Pair("Title A-Z", "TITLE_ENGLISH"),
            Pair("Title Z-A", "TITLE_ENGLISH_DESC"),
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Status",
        arrayOf(
            Pair("Any", ""),
            Pair("Finished", "FINISHED"),
            Pair("Releasing", "RELEASING"),
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

class FormatFilter :
    AnimeFilter.Group<FormatCheckBox>(
        "Formats",
        listOf(
            FormatCheckBox("TV Show", "TV"),
            FormatCheckBox("TV Short", "TV_SHORT"),
            FormatCheckBox("Movie", "MOVIE"),
            FormatCheckBox("Special", "SPECIAL"),
            FormatCheckBox("OVA", "OVA"),
            FormatCheckBox("ONA", "ONA"),
            FormatCheckBox("Music Video", "MUSIC"),
        ),
    )

class FormatCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

class GenreFilter :
    AnimeFilter.Group<GenreCheckBox>(
        "Genres",
        listOf(
            GenreCheckBox("Action", "Action"),
            GenreCheckBox("Adventure", "Adventure"),
            GenreCheckBox("Comedy", "Comedy"),
            GenreCheckBox("Drama", "Drama"),
            GenreCheckBox("Ecchi", "Ecchi"),
            GenreCheckBox("Fantasy", "Fantasy"),
            GenreCheckBox("Horror", "Horror"),
            GenreCheckBox("Mahou Shoujo", "Mahou Shoujo"),
            GenreCheckBox("Mecha", "Mecha"),
            GenreCheckBox("Music", "Music"),
            GenreCheckBox("Mystery", "Mystery"),
            GenreCheckBox("Psychological", "Psychological"),
            GenreCheckBox("Romance", "Romance"),
            GenreCheckBox("Sci-Fi", "Sci-Fi"),
            GenreCheckBox("Slice of Life", "Slice of Life"),
            GenreCheckBox("Sports", "Sports"),
            GenreCheckBox("Supernatural", "Supernatural"),
            GenreCheckBox("Thriller", "Thriller"),
        ),
    )

class GenreCheckBox(name: String, val value: String) : AnimeFilter.CheckBox(name)

class YearFilter : AnimeFilter.Text("Release Year")

fun getAnimexFilterList() = AnimeFilterList(
    SortFilter(),
    StatusFilter(),
    SeasonFilter(),
    FormatFilter(),
    GenreFilter(),
    YearFilter(),
)

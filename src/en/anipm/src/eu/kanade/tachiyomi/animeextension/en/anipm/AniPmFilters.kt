package eu.kanade.tachiyomi.animeextension.en.anipm

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class UriPartFilter(
    name: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: Int = 0,
) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), defaultValue) {
    fun toUriPart() = vals[state].second
}

class SortFilter : UriPartFilter(
    "Sort by",
    arrayOf(
        "Trending" to "trending",
        "Most Popular" to "popular",
        "Highest Rated" to "score",
        "Latest" to "latest",
    ),
    defaultValue = 1,
)

class GenreFilter : UriPartFilter(
    "Genre",
    arrayOf(
        "All" to "",
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
    ),
)

class FormatFilter : UriPartFilter(
    "Format",
    arrayOf(
        "All" to "",
        "TV" to "TV",
        "Movie" to "Movie",
        "OVA" to "OVA",
        "ONA" to "ONA",
        "Special" to "Special",
    ),
)

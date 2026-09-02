package eu.kanade.tachiyomi.animeextension.en.hianimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    open class CheckBoxFilterList(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        displayName,
        vals.map { AnimeFilter.CheckBox(it.first, false) },
    ) {
        fun getSelectedValues(): List<String> =
            state.mapIndexedNotNull { index, checkbox -> vals[index].second.takeIf { checkbox.state } }
    }

    /**
     * Values are the exact strings `POST /api/filter` matches on. The site's own UI also offers
     * "Music" and "Horror" types plus "Airing"/"Completed"/"Upcoming" statuses, but the API
     * returns zero results for every one of them, so only verified values are listed.
     */
    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "TV"),
                Pair("Movie", "Movie"),
                Pair("ONA", "ONA"),
                Pair("OVA", "OVA"),
                Pair("Special", "Special"),
                Pair("TV Special", "TV Special"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Currently Airing", "Currently Airing"),
                Pair("Finished Airing", "Finished Airing"),
                Pair("Not yet aired", "Not yet aired"),
            ),
        )

    /** Multiple genres are combined with AND by the API. */
    class GenreFilter :
        CheckBoxFilterList(
            "Genres",
            arrayOf(
                Pair("Action", "Action"),
                Pair("Adventure", "Adventure"),
                Pair("Comedy", "Comedy"),
                Pair("Drama", "Drama"),
                Pair("Ecchi", "Ecchi"),
                Pair("Fantasy", "Fantasy"),
                Pair("Hentai", "Hentai"),
                Pair("Horror", "Horror"),
                Pair("Mecha", "Mecha"),
                Pair("Music", "Music"),
                Pair("Mystery", "Mystery"),
                Pair("Psychological", "Psychological"),
                Pair("Romance", "Romance"),
                Pair("Sci-Fi", "Sci-Fi"),
                Pair("Slice of Life", "Slice of Life"),
                Pair("Sports", "Sports"),
                Pair("Supernatural", "Supernatural"),
                Pair("Thriller", "Thriller"),
            ),
        )
}

package eu.kanade.tachiyomi.animeextension.en.hianimes

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    /**
     * The API exposes no server-side type filter, so these values mirror the exact
     * `Type` strings returned by `POST /api/search` and are matched client-side.
     */
    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "TV"),
                Pair("Movie", "Movie"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
                Pair("Special", "Special"),
                Pair("TV Special", "TV Special"),
            ),
        )
}

package eu.kanade.tachiyomi.animeextension.en.animesalt

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
                Pair("All Types", ""),
                Pair("Series", "series"),
                Pair("Movies", "movies"),
                Pair("Anime Series", "type/anime/?type=series"),
                Pair("Anime Movies", "type/anime/?type=movies"),
                Pair("Cartoon Series", "type/cartoon/?type=series"),
                Pair("Cartoon Movies", "type/cartoon/?type=movies"),
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Language",
            arrayOf(
                Pair("All Languages", ""),
                Pair("Hindi", "language/hindi"),
                Pair("English", "language/english"),
                Pair("Japanese", "language/japanese"),
                Pair("Tamil", "language/tamil"),
                Pair("Telugu", "language/telugu"),
                Pair("Bengali", "language/bengali"),
                Pair("Malayalam", "language/malayalam"),
                Pair("Kannada", "language/kannada"),
            ),
        )

    class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("All Genres", ""),
                Pair("Action", "genre/action"),
                Pair("Adventure", "genre/adventure"),
                Pair("Biographical", "genre/biographical"),
                Pair("Comedy", "genre/comedy"),
                Pair("Delinquents", "genre/delinquents"),
                Pair("Drama", "genre/drama"),
                Pair("Fantasy", "genre/fantasy"),
                Pair("Gourmet", "genre/gourmet"),
                Pair("Kids", "genre/kids"),
                Pair("Romance", "genre/romance"),
                Pair("School", "genre/school"),
                Pair("Sci-Fi", "genre/sci-fi"),
                Pair("Shounen", "genre/shounen"),
                Pair("Sitcom", "genre/sitcom"),
                Pair("Slice of Life", "genre/slice-of-life"),
                Pair("Superhero", "genre/superhero"),
                Pair("Supernatural", "genre/supernatural"),
            ),
        )
}

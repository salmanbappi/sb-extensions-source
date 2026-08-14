package eu.kanade.tachiyomi.animeextension.en.zinkmovies

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class CategoryFilter : UriPartFilter(
        "Category / Genre",
        arrayOf(
            Pair("All", ""),
            Pair("Movies", "movies"),
            Pair("TV / Web Series", "tvshows"),
            Pair("Trending", "trending"),
            Pair("Top Rated", "ratings"),
            Pair("Anime", "genre/anime"),
            Pair("Animation", "genre/animation"),
            Pair("Bollywood", "genre/bollywood"),
            Pair("Hollywood Movies", "genre/hollywood-movies"),
            Pair("South Movies", "genre/south-movies"),
            Pair("Korean TV Series", "genre/korean-tv-series"),
            Pair("Web Series", "genre/web-series"),
            Pair("Action", "genre/action"),
            Pair("Action & Adventure", "genre/action-adventure"),
            Pair("Adventure", "genre/adventure"),
            Pair("Comedy", "genre/comedy"),
            Pair("Crime", "genre/crime"),
            Pair("Documentary", "genre/documentary"),
            Pair("Drama", "genre/drama"),
            Pair("Family", "genre/family"),
            Pair("Fantasy", "genre/fantasy"),
            Pair("Horror", "genre/horror"),
            Pair("Mystery", "genre/mystery"),
            Pair("Romance", "genre/romance"),
            Pair("Sci-Fi & Fantasy", "genre/sci-fi-fantasy"),
            Pair("Science Fiction", "genre/science-fiction"),
            Pair("Thriller", "genre/thriller"),
            Pair("War", "genre/war"),
            Pair("18+", "genre/18"),
        ),
    )
}

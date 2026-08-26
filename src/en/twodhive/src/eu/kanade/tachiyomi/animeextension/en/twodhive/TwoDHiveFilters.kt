package eu.kanade.tachiyomi.animeextension.en.twodhive

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object TwoDHiveFilters {
    open class SelectFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: String = "",
    ) : AnimeFilter.Select<String>(
        name,
        vals.map { it.first }.toTypedArray(),
        vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
    ) {
        val selected: String get() = vals[state].second
    }

    class CatalogFilter : SelectFilter("Catalog List", CATALOG_OPTIONS)
    class GenreFilter : SelectFilter("Genre", GENRE_OPTIONS)

    val FILTER_LIST
        get() = AnimeFilterList(
            AnimeFilter.Header("Search by text ignores filters below"),
            CatalogFilter(),
            GenreFilter(),
        )

    private val CATALOG_OPTIONS = arrayOf(
        Pair("Top / Popular Anime", "top"),
        Pair("Latest Updates", "latest"),
        Pair("Movies Catalog", "movies"),
    )

    private val GENRE_OPTIONS = arrayOf(
        Pair("All Genres", ""),
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Comedy", "Comedy"),
        Pair("Drama", "Drama"),
        Pair("Ecchi", "Ecchi"),
        Pair("Fantasy", "Fantasy"),
        Pair("Horror", "Horror"),
        Pair("Mahou Shoujo", "Mahou Shoujo"),
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
    )
}

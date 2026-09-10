package eu.kanade.tachiyomi.animeextension.en.moviesmod

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("All", ""),
                Pair("Movies", "movies"),
                Pair("TV Series / Web Series", "tv-series"),
                Pair("Hollywood Movies", "movies/hollywood"),
                Pair("English Movies", "movies/hollywood/english-movies"),
                Pair("Latest Released", "movies/latest-released"),
                Pair("IMDB Top Movies", "imdb-top-movies"),
                Pair("Dual Audio", "dual-audio"),
                Pair("Multi Audio", "multi-audio"),
                Pair("MoviesMod Dubbed", "moviesverse-dubbed"),
                Pair("Fan Dubbed", "movies/fan-dubbed"),
                Pair("Anime", "anime"),
                Pair("480p", "quality/480p"),
                Pair("720p", "quality/720p"),
                Pair("1080p", "quality/1080p"),
                Pair("300MB", "size/300mb"),
                Pair("500MB", "size/500mb"),
                Pair("700MB", "size/700mb"),
                Pair("1GB", "size/1gb"),
            ),
        )
}

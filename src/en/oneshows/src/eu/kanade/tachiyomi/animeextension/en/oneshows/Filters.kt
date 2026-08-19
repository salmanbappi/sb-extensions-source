package eu.kanade.tachiyomi.animeextension.en.oneshows

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
                Pair("TV Shows", "tv"),
                Pair("Movies", "movie"),
                Pair("Anime (TV)", "anime_tv"),
                Pair("Anime (Movie)", "anime_movie"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popularity Descending", "popularity.desc"),
                Pair("Release Date Descending", "date.desc"),
                Pair("Rating Descending", "vote_average.desc"),
                Pair("Vote Count Descending", "vote_count.desc"),
            ),
        )

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter :
        AnimeFilter.Group<GenreCheckBox>(
            "Genres",
            GENRES.map { GenreCheckBox(it.first, it.second) },
        ) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { it.id }
    }

    private val GENRES = listOf(
        Pair("Action", "28"),
        Pair("Action & Adventure", "10759"),
        Pair("Animation", "16"),
        Pair("Comedy", "35"),
        Pair("Crime", "80"),
        Pair("Documentary", "99"),
        Pair("Drama", "18"),
        Pair("Family", "10751"),
        Pair("Fantasy", "14"),
        Pair("History", "36"),
        Pair("Horror", "27"),
        Pair("Kids", "10762"),
        Pair("Music", "10402"),
        Pair("Mystery", "9648"),
        Pair("News", "10763"),
        Pair("Reality", "10764"),
        Pair("Romance", "10749"),
        Pair("Sci-Fi & Fantasy", "10765"),
        Pair("Science Fiction", "878"),
        Pair("Soap", "10766"),
        Pair("Talk", "10767"),
        Pair("TV Movie", "10770"),
        Pair("Thriller", "53"),
        Pair("War", "10752"),
        Pair("War & Politics", "10768"),
        Pair("Western", "37"),
    )
}

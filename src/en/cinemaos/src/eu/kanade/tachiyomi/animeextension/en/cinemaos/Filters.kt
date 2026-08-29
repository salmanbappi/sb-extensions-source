package eu.kanade.tachiyomi.animeextension.en.cinemaos

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class SelectFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: String? = null,
    ) : AnimeFilter.Select<String>(
        name,
        vals.map { it.first }.toTypedArray(),
        vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
    ) {
        val selected: String
            get() = vals[state].second
    }

    open class CheckBoxFilter(
        name: String,
        val value: String,
    ) : AnimeFilter.CheckBox(name)

    open class GenreGroup(
        name: String,
        genres: List<GenreVal>,
    ) : AnimeFilter.Group<CheckBoxFilter>(
        name,
        genres.map { CheckBoxFilter(it.name, it.id) },
    )

    data class GenreVal(val name: String, val id: String)

    class MediaTypeFilter :
        SelectFilter(
            "Media Type",
            arrayOf(
                Pair("All (Trending)", "trending"),
                Pair("Movies", "movie"),
                Pair("TV Shows", "tv"),
            ),
            "trending",
        )

    class SortFilter :
        SelectFilter(
            "Sort By",
            arrayOf(
                Pair("Popularity", "popularity.desc"),
                Pair("Release Date", "primary_release_date.desc"),
                Pair("Rating", "vote_average.desc"),
                Pair("Title", "original_title.asc"),
            ),
            "popularity.desc",
        )

    class YearFilter : AnimeFilter.Text("Release Year (e.g. 2024)")

    val GENRES = listOf(
        GenreVal("Action", "28"),
        GenreVal("Adventure", "12"),
        GenreVal("Animation", "16"),
        GenreVal("Comedy", "35"),
        GenreVal("Crime", "80"),
        GenreVal("Documentary", "99"),
        GenreVal("Drama", "18"),
        GenreVal("Family", "10751"),
        GenreVal("Fantasy", "14"),
        GenreVal("History", "36"),
        GenreVal("Horror", "27"),
        GenreVal("Music", "10402"),
        GenreVal("Mystery", "9648"),
        GenreVal("Romance", "10749"),
        GenreVal("Science Fiction", "878"),
        GenreVal("TV Movie", "10770"),
        GenreVal("Thriller", "53"),
        GenreVal("War", "10752"),
        GenreVal("Western", "37"),
    )

    class GenreFilter : GenreGroup("Genres", GENRES)

    fun getFilterList() = AnimeFilterList(
        MediaTypeFilter(),
        SortFilter(),
        YearFilter(),
        GenreFilter(),
    )
}

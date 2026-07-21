package eu.kanade.tachiyomi.animeextension.all.anidap

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

object Filters {

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class TypeFilter : UriPartFilter(
        "Format",
        arrayOf(
            Pair("All", ""),
            Pair("TV", "TV"),
            Pair("Movie", "MOVIE"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "SPECIAL"),
        ),
    )

    class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Releasing", "RELEASING"),
            Pair("Finished", "FINISHED"),
            Pair("Not Yet Released", "NOT_YET_RELEASED"),
            Pair("Hiatus", "HIATUS"),
        ),
    )

    class SeasonFilter : UriPartFilter(
        "Season",
        arrayOf(
            Pair("All", ""),
            Pair("Winter", "WINTER"),
            Pair("Spring", "SPRING"),
            Pair("Summer", "SUMMER"),
            Pair("Fall", "FALL"),
        ),
    )

    class YearFilter : UriPartFilter(
        "Year",
        YEARS,
    ) {
        companion object {
            private val CURRENT_YEAR by lazy {
                Calendar.getInstance().get(Calendar.YEAR)
            }

            private val YEARS = buildList {
                add(Pair("All", ""))
                addAll((CURRENT_YEAR downTo 1970).map { Pair(it.toString(), it.toString()) })
            }.toTypedArray()
        }
    }

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Genres",
        GENRES.map { GenreCheckBox(it.first, it.second) },
    ) {
        fun toQueries(): List<String> = state.filter { it.state }.map {
            (it as GenreCheckBox).id
        }

        companion object {
            private val GENRES = arrayOf(
                Pair("Action", "ACTION"),
                Pair("Adventure", "ADVENTURE"),
                Pair("Comedy", "COMEDY"),
                Pair("Drama", "DRAMA"),
                Pair("Ecchi", "ECCHI"),
                Pair("Fantasy", "FANTASY"),
                Pair("Horror", "HORROR"),
                Pair("Mahou Shoujo", "MAHOU_SHOUJO"),
                Pair("Mecha", "MECHA"),
                Pair("Music", "MUSIC"),
                Pair("Mystery", "MYSTERY"),
                Pair("Psychological", "PSYCHOLOGICAL"),
                Pair("Romance", "ROMANCE"),
                Pair("Sci-Fi", "SCI_FI"),
                Pair("Slice of Life", "SLICE_OF_LIFE"),
                Pair("Sports", "SPORTS"),
                Pair("Supernatural", "SUPERNATURAL"),
                Pair("Thriller", "THRILLER"),
            )
        }
    }

    class SortFilter : AnimeFilter.Sort(
        "Sort By",
        arrayOf("Popularity", "Score", "Release Date", "Title"),
    ) {
        fun toUriPart(): String? = state?.let {
            val key = when (it.index) {
                0 -> "POPULARITY"
                1 -> "SCORE"
                2 -> "START_DATE"
                3 -> "TITLE"
                else -> return@let null
            }
            if (it.ascending) "${key}_ASC" else "${key}_DESC"
        }
    }
}

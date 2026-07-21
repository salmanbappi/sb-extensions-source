package eu.kanade.tachiyomi.animeextension.en.animotvslash

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

object Filters {

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV Series", "series"),
                Pair("Movie", "movies"),
                Pair("OVA", "ova"),
                Pair("ONA", "ona"),
                Pair("Special", "special"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Ongoing", "ongoing"),
                Pair("Completed", "completed"),
            ),
        )

    class OrderFilter :
        UriPartFilter(
            "Order By",
            arrayOf(
                Pair("Latest Update", "update"),
                Pair("Popularity", "popular"),
                Pair("Title", "title"),
                Pair("Recently Added", "latest"),
            ),
        )

    class YearFilter :
        UriPartFilter(
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

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            GENRES.map { GenreCheckBox(it.first, it.second) },
        ) {
        fun selectedGenres(): List<String> = state.filter { it.state }.map { it.id }

        companion object {
            private val GENRES = listOf(
                Pair("Action", "action"),
                Pair("Adventure", "adventure"),
                Pair("Comedy", "comedy"),
                Pair("Drama", "drama"),
                Pair("Fantasy", "fantasy"),
                Pair("Horror", "horror"),
                Pair("Music", "music"),
                Pair("Mystery", "mystery"),
                Pair("Psychological", "psychological"),
                Pair("Romance", "romance"),
                Pair("Sci-Fi", "sci-fi"),
                Pair("Seinen", "seinen"),
                Pair("Shojo", "shojo"),
                Pair("Shonen", "shonen"),
                Pair("Slice of Life", "slice-of-life"),
                Pair("Sports", "sports"),
                Pair("Supernatural", "supernatural"),
                Pair("Thriller", "thriller"),
            )
        }
    }

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
}

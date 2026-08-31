package eu.kanade.tachiyomi.animeextension.en.onetouchtv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    open class SelectFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), defaultValue) {
        val selected: String
            get() = vals[state].second
    }

    class TypeFilter :
        SelectFilter(
            "Type",
            arrayOf(
                "All" to "",
                "Drama" to "drama",
                "Movie" to "movie",
                "Variety" to "variety",
                "Anime" to "anime",
            ),
        )

    class CountryFilter :
        SelectFilter(
            "Country",
            arrayOf(
                "All" to "",
                "Korean" to "korean",
                "Chinese" to "chinese",
                "Japanese" to "japanese",
                "Thai" to "thai",
                "Taiwanese" to "taiwanese",
                "Hong Kong" to "hong kong",
                "Philippines" to "philippines",
                "Western / US" to "western",
            ),
        )

    class StatusFilter :
        SelectFilter(
            "Status",
            arrayOf(
                "All" to "",
                "Ongoing" to "ongoing",
                "Completed" to "completed",
                "Upcoming" to "upcoming",
            ),
        )

    class YearFilter :
        SelectFilter(
            "Year",
            arrayOf(
                "All" to "",
                "2026" to "2026",
                "2025" to "2025",
                "2024" to "2024",
                "2023" to "2023",
                "2022" to "2022",
                "2021" to "2021",
                "2020" to "2020",
                "2019" to "2019",
                "2018" to "2018",
                "2017" to "2017",
                "2016" to "2016",
                "2015" to "2015",
                "2014" to "2014",
                "2013" to "2013",
                "2012" to "2012",
                "2011" to "2011",
                "2010" to "2010",
            ),
        )

    class Genre(name: String, val value: String) : AnimeFilter.CheckBox(name)

    class GenreListFilter(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genres", genres)

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Use search or choose filters below"),
        TypeFilter(),
        CountryFilter(),
        StatusFilter(),
        YearFilter(),
        AnimeFilter.Separator(),
        GenreListFilter(
            listOf(
                Genre("Action", "action"),
                Genre("Adventure", "adventure"),
                Genre("Comedy", "comedy"),
                Genre("Crime", "crime"),
                Genre("Drama", "drama"),
                Genre("Family", "family"),
                Genre("Fantasy", "fantasy"),
                Genre("Historical", "historical"),
                Genre("Horror", "horror"),
                Genre("Law", "law"),
                Genre("Life", "life"),
                Genre("Medical", "medical"),
                Genre("Melodrama", "melodrama"),
                Genre("Music", "music"),
                Genre("Mystery", "mystery"),
                Genre("Psychological", "psychological"),
                Genre("Romance", "romance"),
                Genre("Sci-Fi", "sci-fi"),
                Genre("Sports", "sports"),
                Genre("Supernatural", "supernatural"),
                Genre("Thriller", "thriller"),
                Genre("Wuxia", "wuxia"),
                Genre("Xianxia", "xianxia"),
                Genre("Youth", "youth"),
            ),
        ),
    )

    data class FilterSearchParams(
        val type: String = "",
        val country: String = "",
        val status: String = "",
        val year: String = "",
        val genres: String = "",
    )

    fun getFilterParams(filters: AnimeFilterList): FilterSearchParams {
        var type = ""
        var country = ""
        var status = ""
        var year = ""
        val genres = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> type = filter.selected

                is CountryFilter -> country = filter.selected

                is StatusFilter -> status = filter.selected

                is YearFilter -> year = filter.selected

                is GenreListFilter -> {
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            genres.add(genre.value)
                        }
                    }
                }

                else -> {}
            }
        }

        return FilterSearchParams(
            type = type,
            country = country,
            status = status,
            year = year,
            genres = genres.joinToString(","),
        )
    }
}

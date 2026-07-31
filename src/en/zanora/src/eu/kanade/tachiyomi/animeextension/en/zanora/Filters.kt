package eu.kanade.tachiyomi.animeextension.en.zanora

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

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

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("Movie", "1"),
                Pair("TV", "2"),
                Pair("OVA", "3"),
                Pair("ONA", "4"),
                Pair("Special", "5"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Finished Airing", "1"),
                Pair("Currently Airing", "2"),
                Pair("Not yet aired", "3"),
            ),
        )

    class RatedFilter :
        UriPartFilter(
            "Rating",
            arrayOf(
                Pair("All", ""),
                Pair("G", "1"),
                Pair("PG", "2"),
                Pair("PG-13", "3"),
                Pair("R", "4"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("All", ""),
                Pair("Spring", "1"),
                Pair("Summer", "2"),
                Pair("Fall", "3"),
                Pair("Winter", "4"),
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Language",
            arrayOf(
                Pair("All", ""),
                Pair("SUB", "1"),
                Pair("DUB", "2"),
                Pair("SUB & DUB", "3"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Default", "default"),
                Pair("Recently Added", "recently_added"),
                Pair("Recently Updated", "recently_updated"),
                Pair("Score", "score"),
                Pair("Name A-Z", "name_az"),
            ),
        )

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            arrayOf(
                Pair("Action", "1"),
                Pair("Adventure", "2"),
                Pair("Cars", "3"),
                Pair("Comedy", "4"),
                Pair("Dementia", "5"),
                Pair("Demons", "6"),
                Pair("Drama", "8"),
                Pair("Ecchi", "9"),
                Pair("Fantasy", "10"),
                Pair("Game", "11"),
                Pair("Harem", "35"),
                Pair("Historical", "13"),
                Pair("Horror", "14"),
                Pair("Isekai", "44"),
                Pair("Josei", "43"),
                Pair("Kids", "15"),
                Pair("Magic", "16"),
                Pair("Martial Arts", "17"),
                Pair("Mecha", "18"),
                Pair("Military", "38"),
                Pair("Music", "19"),
                Pair("Mystery", "7"),
                Pair("Parody", "20"),
                Pair("Police", "39"),
                Pair("Psychological", "40"),
                Pair("Romance", "22"),
                Pair("Samurai", "21"),
                Pair("School", "23"),
                Pair("Sci-Fi", "24"),
                Pair("Seinen", "42"),
                Pair("Shoujo", "25"),
                Pair("Shoujo Ai", "26"),
                Pair("Shounen", "27"),
                Pair("Shounen Ai", "28"),
                Pair("Slice of Life", "36"),
                Pair("Space", "29"),
                Pair("Sports", "30"),
                Pair("Super Power", "31"),
                Pair("Supernatural", "37"),
                Pair("Thriller", "41"),
                Pair("Vampire", "32"),
            ).map { GenreCheckBox(it.first, it.second) },
        ) {
        fun toQueries(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }
}

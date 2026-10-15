package eu.kanade.tachiyomi.animeextension.en.fouranimo

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
            "Format / Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV", "tv"),
                Pair("Movie", "movie"),
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
                Pair("Currently Airing", "currently_airing"),
                Pair("Finished Airing", "finished_airing"),
                Pair("Not Yet Aired", "not_yet_aired"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Default", "default"),
                Pair("Recently Updated", "recently-updated"),
                Pair("Recently Added", "recently-added"),
                Pair("Score", "score"),
                Pair("Name A-Z", "name-az"),
                Pair("Released Date", "released-date"),
                Pair("Most Watched", "most-watched"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("All", ""),
                Pair("Spring", "spring"),
                Pair("Summer", "summer"),
                Pair("Fall", "fall"),
                Pair("Winter", "winter"),
            ),
        )

    class LanguageFilter :
        UriPartFilter(
            "Language",
            arrayOf(
                Pair("All", ""),
                Pair("SUB", "sub"),
                Pair("DUB", "dub"),
                Pair("SUB & DUB", "sub_dub"),
            ),
        )

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            listOf(
                Pair("Action", "1"),
                Pair("Adventure", "2"),
                Pair("Cars", "3"),
                Pair("Comedy", "4"),
                Pair("Dementia", "5"),
                Pair("Demons", "6"),
                Pair("Mystery", "7"),
                Pair("Drama", "8"),
                Pair("Ecchi", "9"),
                Pair("Fantasy", "10"),
                Pair("Game", "11"),
                Pair("Historical", "13"),
                Pair("Horror", "14"),
                Pair("Kids", "15"),
                Pair("Magic", "16"),
                Pair("Martial Arts", "17"),
                Pair("Mecha", "18"),
                Pair("Music", "19"),
                Pair("Parody", "20"),
                Pair("Samurai", "21"),
                Pair("Romance", "22"),
                Pair("School", "23"),
                Pair("Sci-Fi", "24"),
                Pair("Shoujo", "25"),
                Pair("Shoujo Ai", "26"),
                Pair("Shounen", "27"),
                Pair("Shounen Ai", "28"),
                Pair("Space", "29"),
                Pair("Sports", "30"),
                Pair("Super Power", "31"),
                Pair("Vampire", "32"),
                Pair("Harem", "35"),
                Pair("Slice of Life", "36"),
                Pair("Supernatural", "37"),
                Pair("Military", "38"),
                Pair("Police", "39"),
                Pair("Psychological", "40"),
                Pair("Thriller", "41"),
                Pair("Seinen", "42"),
                Pair("Josei", "43"),
                Pair("Isekai", "44"),
            ).map { GenreCheckBox(it.first, it.second) },
        ) {
        fun selected(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }
}

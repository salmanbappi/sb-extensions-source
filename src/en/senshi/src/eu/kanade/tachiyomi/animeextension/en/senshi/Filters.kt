package eu.kanade.tachiyomi.animeextension.en.senshi

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class CheckBoxVal(name: String) : AnimeFilter.CheckBox(name, false)

    open class CheckBoxFilterList(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Group<AnimeFilter.CheckBox>(
        displayName,
        vals.map { CheckBoxVal(it.first) },
    ) {
        fun getSelectedValues(): List<String> = state.mapIndexedNotNull { index, checkbox -> vals[index].second.takeIf { checkbox.state } }
    }

    class SortFilter : UriPartFilter(
        "Sort by",
        arrayOf(
            Pair("Best score", "score_desc"),
            Pair("Worst score", "score_asc"),
            Pair("A-Z", "name_asc"),
            Pair("Z-A", "name_desc"),
            Pair("Latest release", "recent"),
        ),
    )

    /** Verified against the live API: type ids are lowercase. */
    class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("All", ""),
            Pair("TV", "tv"),
            Pair("Movie", "movie"),
            Pair("OVA", "ova"),
            Pair("ONA", "ona"),
            Pair("Special", "special"),
            Pair("Music", "music"),
        ),
    )

    /** Verified against the live API: status ids are lowercase. */
    class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Releasing", "releasing"),
            Pair("Completed", "completed"),
            Pair("Not yet aired", "not_yet_aired"),
        ),
    )

    class SeasonFilter : UriPartFilter(
        "Season",
        arrayOf(
            Pair("All", ""),
            Pair("Winter", "winter"),
            Pair("Spring", "spring"),
            Pair("Summer", "summer"),
            Pair("Fall", "fall"),
        ),
    )

    /** Verified against the live API: HardSub == subbed, Dub == dubbed. */
    class LanguageFilter : UriPartFilter(
        "Language",
        arrayOf(
            Pair("All", ""),
            Pair("Subbed", "HardSub"),
            Pair("Dubbed", "Dub"),
        ),
    )

    /** Multiple genres are combined with AND by the API. Exact site genre list. */
    class GenreFilter : CheckBoxFilterList(
        "Genres",
        arrayOf(
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Avant Garde", "Avant Garde"),
            Pair("Boys Love", "Boys Love"),
            Pair("Comedy", "Comedy"),
            Pair("Demons", "Demons"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Girls Love", "Girls Love"),
            Pair("Gourmet", "Gourmet"),
            Pair("Harem", "Harem"),
            Pair("Horror", "Horror"),
            Pair("Isekai", "Isekai"),
            Pair("Iyashikei", "Iyashikei"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Magic", "Magic"),
            Pair("Mahou Shoujo", "Mahou Shoujo"),
            Pair("Martial Arts", "Martial Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Parody", "Parody"),
            Pair("Psychological", "Psychological"),
            Pair("Reverse Harem", "Reverse Harem"),
            Pair("Romance", "Romance"),
            Pair("School", "School"),
            Pair("Sci-Fi", "Sci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Slice of Life", "Slice of Life"),
            Pair("Space", "Space"),
            Pair("Sports", "Sports"),
            Pair("Shounen", "Shounen"),
            Pair("Super Power", "Super Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Suspense", "Suspense"),
            Pair("Thriller", "Thriller"),
            Pair("Vampire", "Vampire"),
        ),
    )

    class YearFilter : AnimeFilter.Text("Year (e.g. 2024)")
}

package eu.kanade.tachiyomi.animeextension.en.anikura

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Popular", "popular"),
            Pair("Trending", "trending"),
            Pair("Top Rated", "score"),
        ),
    )

    class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", ""),
            Pair("Currently Airing", "releasing"),
            Pair("Finished Airing", "finished"),
            Pair("Not Yet Aired", "not_yet_aired"),
        ),
    )

    class FormatFilter : UriPartFilter(
        "Format",
        arrayOf(
            Pair("All", ""),
            Pair("TV", "TV"),
            Pair("Movie", "MOVIE"),
            Pair("ONA", "ONA"),
            Pair("OVA", "OVA"),
            Pair("Special", "SPECIAL"),
            Pair("Music", "MUSIC"),
            Pair("Short", "TV_SHORT"),
        ),
    )

    class AudioFilter : UriPartFilter(
        "Audio",
        arrayOf(
            Pair("All", ""),
            Pair("Dubbed", "dub"),
        ),
    )

    class YearFilter : AnimeFilter.Text("Year", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter(genres: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    val GENRES = listOf(
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Fantasy", "fantasy"),
        Pair("Horror", "horror"),
        Pair("Isekai", "isekai"),
        Pair("Mecha", "mecha"),
        Pair("Mystery", "mystery"),
        Pair("Romance", "romance"),
        Pair("School", "school"),
        Pair("Sci-Fi", "sci-fi"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Sports", "sports"),
        Pair("Supernatural", "supernatural"),
        Pair("Thriller", "thriller"),
        Pair("Shounen", "shounen"),
        Pair("Seinen", "seinen"),
        Pair("Ecchi", "ecchi"),
        Pair("Magic", "magic"),
        Pair("Historical", "historical"),
        Pair("Shoujo", "shoujo"),
        Pair("Super Power", "super-power"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Military", "military"),
        Pair("Music", "music"),
        Pair("Harem", "harem"),
        Pair("Psychological", "psychological"),
        Pair("Kids", "kids"),
        Pair("Space", "space"),
        Pair("Demons", "demons"),
        Pair("Game", "game"),
        Pair("Parody", "parody"),
        Pair("Police", "police"),
        Pair("Vampire", "vampire"),
        Pair("Samurai", "samurai"),
    )
}

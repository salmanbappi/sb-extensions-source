package eu.kanade.tachiyomi.animeextension.all.anikoto

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class TriStateCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

class SortFilter :
    AnimeFilter.Select<String>(
        "Sort by",
        arrayOf(
            "Default",
            "Latest updated",
            "Latest added",
            "Score",
            "Name A-Z",
            "Release date",
            "Most viewed",
            "Number of episodes",
        ),
    ) {
    private val values = arrayOf(
        "default",
        "latest-updated",
        "latest-added",
        "score",
        "name-az",
        "release-date",
        "most-viewed",
        "number_of_episodes",
    )

    fun toQuery(): String? = if (state > 0 && state < values.size) values[state] else null
}

class GenreFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Genres",
        GENRES.map { TriStateCheckBox(it.first, it.second) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

class TypeFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Type",
        TYPES.map { TriStateCheckBox(it.first, it.second) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

class StatusFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Status",
        STATUSES.map { TriStateCheckBox(it.first, it.second) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

class LanguageFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Language",
        LANGUAGES.map { TriStateCheckBox(it.first, it.second) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

class SeasonFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Season",
        SEASONS.map { TriStateCheckBox(it.first, it.second) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

class YearFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Year",
        (2026 downTo 1980).map { TriStateCheckBox(it.toString(), it.toString()) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

class RatingFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Rating",
        RATINGS.map { TriStateCheckBox(it, it) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

class SourceFilter :
    AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Source",
        SOURCES.map { TriStateCheckBox(it.first, it.second) },
    ) {
    fun toQueries(): List<String> = state.filter { it.state }.map {
        (it as TriStateCheckBox).id
    }
}

// name → query-value pairs (matching APK order)
private val GENRES = listOf(
    Pair("Action", "1"),
    Pair("Adventure", "2"),
    Pair("Cars", "538"),
    Pair("Comedy", "8"),
    Pair("Dementia", "453"),
    Pair("Demons", "119"),
    Pair("Drama", "62"),
    Pair("Ecchi", "214"),
    Pair("Fantasy", "3"),
    Pair("Game", "180"),
    Pair("Harem", "215"),
    Pair("Historical", "70"),
    Pair("Horror", "222"),
    Pair("Isekai", "74"),
    Pair("Josei", "404"),
    Pair("Kids", "46"),
    Pair("Magic", "203"),
    Pair("Mahou Shoujo", "2310"),
    Pair("Martial Arts", "114"),
    Pair("Mecha", "123"),
    Pair("Military", "125"),
    Pair("Music", "242"),
    Pair("Mystery", "57"),
    Pair("Parody", "162"),
    Pair("Police", "136"),
    Pair("Psychological", "73"),
    Pair("Romance", "28"),
    Pair("Samurai", "163"),
    Pair("School", "14"),
    Pair("Sci-Fi", "12"),
    Pair("Seinen", "50"),
    Pair("Shoujo", "252"),
    Pair("Shoujo Ai", "235"),
    Pair("Shounen", "15"),
    Pair("Shounen Ai", "233"),
    Pair("Slice of Life", "35"),
    Pair("Space", "124"),
    Pair("Sports", "29"),
    Pair("Super Power", "16"),
    Pair("Supernatural", "9"),
    Pair("Suspense", "2316"),
    Pair("Thriller", "54"),
    Pair("Unknown", "32"),
    Pair("Vampire", "58"),
)

private val TYPES = listOf(
    Pair("TV", "TV"),
    Pair("TV Short", "TV_SHORT"),
    Pair("Movie", "Movie"),
    Pair("ONA", "ONA"),
    Pair("OVA", "OVA"),
    Pair("Special", "Special"),
    Pair("Music", "Music"),
)

private val STATUSES = listOf(
    Pair("Currently Airing", "currently-airing"),
    Pair("Finished Airing", "finished-airing"),
    Pair("Not Yet Aired", "not-yet-aired"),
)

private val LANGUAGES = listOf(
    Pair("Sub", "sub"),
    Pair("Dub", "dub"),
)

private val SEASONS = listOf(
    Pair("Spring", "spring"),
    Pair("Summer", "summer"),
    Pair("Fall", "fall"),
    Pair("Winter", "winter"),
)

private val RATINGS = listOf("G", "PG", "PG-13", "R", "R+", "Rx")

private val SOURCES = listOf(
    Pair("Manga", "manga"),
    Pair("Original", "original"),
    Pair("Light Novel", "light_novel"),
    Pair("Web Novel", "web_novel"),
    Pair("Novel", "novel"),
    Pair("Web Manga", "web_manga"),
    Pair("Visual Novel", "visual_novel"),
    Pair("Game", "game"),
    Pair("Video Game", "video_game"),
    Pair("Card Game", "card_game"),
    Pair("4-Koma Manga", "4-koma_manga"),
    Pair("Music", "music"),
    Pair("Book", "book"),
    Pair("Picture Book", "picture_book"),
    Pair("Mixed Media", "mixed_media"),
    Pair("Radio", "radio"),
    Pair("Other", "other"),
    Pair("Unknown", "unknown"),
)

fun getAnikotoFilters(): AnimeFilterList = AnimeFilterList(
    SortFilter(),
    GenreFilter(),
    TypeFilter(),
    StatusFilter(),
    LanguageFilter(),
    SeasonFilter(),
    YearFilter(),
    RatingFilter(),
    SourceFilter(),
    AnimeFilter.Separator(),
    AnimeFilter.Header("Note: sub/dub filter here filters anime, not episodes."),
)

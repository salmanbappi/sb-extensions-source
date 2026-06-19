package eu.kanade.tachiyomi.animeextension.all.anikoto

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class TriStateCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

class SortFilter : AnimeFilter.Sort(
    "Sort by",
    arrayOf(
        "Default",
        "Latest updated",
        "Latest added",
        "Score",
        "Name A-Z",
        "Release date",
        "Most viewed",
        "Number of episodes"
    ),
    null
) {
    fun toQuery(): String? {
        val values = arrayOf(
            "default",
            "latest-updated",
            "latest-added",
            "score",
            "name-az",
            "release-date",
            "most-viewed",
            "number_of_episodes"
        )
        val st = state ?: return null
        return if (st.index >= 0 && st.index < values.size) {
            values[st.index]
        } else {
            null
        }
    }
}

class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
    "Genres",
    GENRES.map { TriStateCheckBox(it.second, it.first) }
) {
    fun toQueries(): List<String> {
        return state.filter { it.state }.map {
            (it as TriStateCheckBox).id
        }
    }
}

class TypeFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
    "Type",
    TYPES.map { TriStateCheckBox(it.second, it.first) }
) {
    fun toQueries(): List<String> {
        return state.filter { it.state }.map {
            (it as TriStateCheckBox).id
        }
    }
}

class StatusFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
    "Status",
    STATUSES.map { TriStateCheckBox(it.second, it.first) }
) {
    fun toQueries(): List<String> {
        return state.filter { it.state }.map {
            (it as TriStateCheckBox).id
        }
    }
}

class LanguageFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
    "Language",
    LANGUAGES.map { TriStateCheckBox(it.second, it.first) }
) {
    fun toQueries(): List<String> {
        return state.filter { it.state }.map {
            (it as TriStateCheckBox).id
        }
    }
}

private val GENRES = listOf(
    Pair("1", "Action"),
    Pair("2", "Adventure"),
    Pair("538", "Cars"),
    Pair("8", "Comedy"),
    Pair("453", "Dementia"),
    Pair("119", "Demons"),
    Pair("62", "Drama"),
    Pair("214", "Ecchi"),
    Pair("3", "Fantasy"),
    Pair("180", "Game"),
    Pair("215", "Harem"),
    Pair("70", "Historical"),
    Pair("222", "Horror"),
    Pair("74", "Isekai"),
    Pair("404", "Josei"),
    Pair("46", "Kids"),
    Pair("203", "Magic"),
    Pair("2310", "Mahou Shoujo"),
    Pair("114", "Martial Arts"),
    Pair("123", "Mecha"),
    Pair("125", "Military"),
    Pair("242", "Music"),
    Pair("57", "Mystery"),
    Pair("162", "Parody"),
    Pair("136", "Police"),
    Pair("73", "Psychological"),
    Pair("28", "Romance"),
    Pair("163", "Samurai"),
    Pair("14", "School"),
    Pair("12", "Sci-Fi"),
    Pair("50", "Seinen"),
    Pair("252", "Shoujo"),
    Pair("235", "Shoujo Ai"),
    Pair("15", "Shounen"),
    Pair("233", "Shounen Ai"),
    Pair("35", "Slice of Life"),
    Pair("124", "Space"),
    Pair("29", "Sports"),
    Pair("16", "Super Power"),
    Pair("9", "Supernatural"),
    Pair("2316", "Suspense"),
    Pair("54", "Thriller"),
    Pair("32", "Unknown"),
    Pair("58", "Vampire")
)

private val TYPES = listOf(
    Pair("TV", "TV"),
    Pair("Movie", "Movie"),
    Pair("OVA", "OVA"),
    Pair("ONA", "ONA"),
    Pair("Special", "Special"),
    Pair("Music", "Music")
)

private val STATUSES = listOf(
    Pair("finished-airing", "Finished Airing"),
    Pair("currently-airing", "Currently Airing"),
    Pair("not-yet-aired", "Not Yet Aired")
)

private val LANGUAGES = listOf(
    Pair("sub", "Sub"),
    Pair("dub", "Dub")
)

fun getAnikotoFilters(): AnimeFilterList {
    return AnimeFilterList(
        SortFilter(),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        LanguageFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Note: sub/dub filter here filters anime, not episodes.")
    )
}

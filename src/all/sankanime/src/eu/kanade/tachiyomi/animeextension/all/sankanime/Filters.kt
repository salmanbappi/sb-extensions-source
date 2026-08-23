package eu.kanade.tachiyomi.animeextension.all.sankanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class TypeFilter : UriPartFilter("Format / Type", arrayOf(
        Pair("All", ""),
        Pair("TV", "tv"),
        Pair("Movie", "movie"),
        Pair("OVA", "ova"),
        Pair("ONA", "ona"),
        Pair("Special", "special"),
        Pair("Music", "music"),
    ))

    class StatusFilter : UriPartFilter("Status", arrayOf(
        Pair("All", ""),
        Pair("Releasing", "releasing"),
        Pair("Completed", "completed"),
        Pair("Not Yet Aired", "info"),
    ))

    class SeasonFilter : UriPartFilter("Season", arrayOf(
        Pair("All", ""),
        Pair("Spring", "spring"),
        Pair("Summer", "summer"),
        Pair("Fall", "fall"),
        Pair("Winter", "winter"),
    ))

    class SortFilter : UriPartFilter("Sort By", arrayOf(
        Pair("Recently Updated", "updated_date"),
        Pair("Recently Added", "added_date"),
        Pair("Release Date", "release_date"),
        Pair("Trending", "trending"),
        Pair("Name A-Z", "title_az"),
        Pair("Average Score", "avg_score"),
        Pair("MAL Score", "mal_score"),
        Pair("Most Viewed", "most_viewed"),
        Pair("Most Followed", "most_followed"),
        Pair("Episode Count", "episode_count"),
    ))

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter(genres: List<Pair<String, String>>) :
        AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    val GENRES = listOf(
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Avant Garde", "Avant Garde"),
        Pair("Boys Love", "Boys Love"),
        Pair("Cars", "Cars"),
        Pair("Comedy", "Comedy"),
        Pair("Demons", "Demons"),
        Pair("Drama", "Drama"),
        Pair("Ecchi", "Ecchi"),
        Pair("Fantasy", "Fantasy"),
        Pair("Game", "Game"),
        Pair("Girls Love", "Girls Love"),
        Pair("Gourmet", "Gourmet"),
        Pair("Harem", "Harem"),
        Pair("Historical", "Historical"),
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
        Pair("Police", "Police"),
        Pair("Psychological", "Psychological"),
        Pair("Reverse Harem", "Reverse Harem"),
        Pair("Romance", "Romance"),
        Pair("Samurai", "Samurai"),
        Pair("School", "School"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Seinen", "Seinen"),
        Pair("Shoujo", "Shoujo"),
        Pair("Shounen", "Shounen"),
        Pair("Slice of Life", "Slice of Life"),
        Pair("Space", "Space"),
        Pair("Sports", "Sports"),
        Pair("Super Power", "Super Power"),
        Pair("Supernatural", "Supernatural"),
        Pair("Suspense", "Suspense"),
        Pair("Thriller", "Thriller"),
        Pair("Vampire", "Vampire"),
    )

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        StatusFilter(),
        SeasonFilter(),
        SortFilter(),
        AnimeFilter.Separator(),
        GenreFilter(GENRES),
    )
}

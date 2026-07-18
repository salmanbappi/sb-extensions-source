package eu.kanade.tachiyomi.animeextension.en.flixhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class TypeFilter : AnimeFilter.Select<String>(
    "Type",
    types.map { it.first }.toTypedArray()
) {
    val selected: String get() = types[state].second

    companion object {
        val types = listOf(
            Pair("Movies", "movies"),
            Pair("TV Series", "tv-series")
        )
    }
}

class CategoryFilter : AnimeFilter.Select<String>(
    "Category",
    categories.map { it.first }.toTypedArray()
) {
    val selected: String get() = categories[state].second

    companion object {
        val categories = listOf(
            Pair("All", "all"),
            Pair("Hollywood", "hollywood"),
            Pair("Bollywood", "bollywood"),
            Pair("South Indian", "south-indian"),
            Pair("KidzTime", "kidztime"),
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Animation", "Animation"),
            Pair("Comedy", "Comedy"),
            Pair("Crime", "Crime"),
            Pair("Documentary", "Documentary"),
            Pair("Drama", "Drama"),
            Pair("Family", "Family"),
            Pair("Fantasy", "Fantasy"),
            Pair("History", "History"),
            Pair("Horror", "Horror"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi", "Science Fiction"),
            Pair("Thriller", "Thriller"),
            Pair("War", "War")
        )
    }
}

class SortFilter : AnimeFilter.Select<String>(
    "Sort (TV Series only)",
    sorts.map { it.first }.toTypedArray()
) {
    val selected: String get() = sorts[state].second

    companion object {
        val sorts = listOf(
            Pair("Latest", "latest"),
            Pair("Popular", "popular")
        )
    }
}

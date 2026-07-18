package eu.kanade.tachiyomi.animeextension.en.moviewala

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class CategoryFilter :
    AnimeFilter.Select<String>(
        "Category",
        categories.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = categories[state].second

    companion object {
        val categories = listOf(
            Pair("All", "all"),
            Pair("Action", "action"),
            Pair("Drama", "drama"),
            Pair("Comedy", "comedy"),
            Pair("Thriller", "thriller"),
            Pair("Romance", "romance"),
            Pair("Horror", "horror"),
            Pair("Sci-Fi", "science-fiction"),
            Pair("Animation", "animation"),
            Pair("Crime", "crime"),
            Pair("Adventure", "adventure"),
            Pair("Fantasy", "fantasy"),
            Pair("Documentary", "documentary"),
        )
    }
}

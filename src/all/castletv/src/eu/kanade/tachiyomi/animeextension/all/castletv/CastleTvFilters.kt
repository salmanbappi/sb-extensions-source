package eu.kanade.tachiyomi.animeextension.all.castletv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class CategoryFilter :
    AnimeFilter.Select<String>(
        "Category",
        arrayOf(
            "All",
            "Watch Popular Movies",
            "Popular WebSeries",
            "Upcoming",
            "Most Watching Trending Now",
            "Crazy Short Shows",
            "Hottest International Film",
            "Recommended Movies",
            "Bollywood Movie",
            "Anime",
            "Most watched movies in 2025",
            "Hollywood Movie",
            "Most Romantic Movie & Series",
            "Fantasy and Adventure",
        ),
    )

fun getCastleTvFilters(): AnimeFilterList = AnimeFilterList(
    CategoryFilter(),
)

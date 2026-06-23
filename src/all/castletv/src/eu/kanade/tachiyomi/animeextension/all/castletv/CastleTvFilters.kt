package eu.kanade.tachiyomi.animeextension.all.castletv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class TypeFilter :
    AnimeFilter.Select<String>(
        "Type",
        arrayOf("All", "Movies Only", "TV Series Only"),
    )

fun getCastleTvFilters(): AnimeFilterList = AnimeFilterList(
    TypeFilter(),
)

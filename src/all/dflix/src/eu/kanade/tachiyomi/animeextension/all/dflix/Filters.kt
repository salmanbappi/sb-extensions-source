package eu.kanade.tachiyomi.animeextension.all.dflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Search Mode"),
        DflixSelect("Mode", FilterData.MODES),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Filters (Apply based on Mode)"),
        DflixSelect("4K Category", FilterData.CATEGORIES),
        DflixSelect("Genre", FilterData.GENRES),
        DflixSelect("Type", FilterData.CATEGORIES),
        DflixSelect("Year", FilterData.YEARS),
    )

    fun getUrl(query: String, filters: AnimeFilterList, page: Int): String {
        if (query.isNotEmpty()) {
            return "https://dflix.discoveryftp.net/search/$query"
        }

        val modeIndex = if (filters.size > 1) (filters[1] as DflixSelect).state else 0
        return when (modeIndex) {
            0 -> "https://dflix.discoveryftp.net/m/recent/$page"

            1 -> "https://dflix.discoveryftp.net/s/recent/$page"

            2 -> {
                val catIndex = if (filters.size > 4) (filters[4] as DflixSelect).state else 0
                val category = FilterData.CATEGORIES.getOrElse(catIndex) { "Hindi" }
                "https://dflix.discoveryftp.net/m/uhd/$category/$page"
            }

            3 -> {
                val genreIndex = if (filters.size > 5) (filters[5] as DflixSelect).state else 0
                val genre = FilterData.GENRES.getOrElse(genreIndex) { "Action" }
                "https://dflix.discoveryftp.net/m/genre/$genre/$page"
            }

            4 -> {
                val catIndex = if (filters.size > 6) (filters[6] as DflixSelect).state else 0
                val category = FilterData.CATEGORIES.getOrElse(catIndex) { "Hindi" }
                val yearIndex = if (filters.size > 7) (filters[7] as DflixSelect).state else 0
                val year = FilterData.YEARS.getOrElse(yearIndex) { "2026" }
                "https://dflix.discoveryftp.net/m/type/$category/$year/$page"
            }

            5 -> "https://dflix.discoveryftp.net/m/dual/Hindi/$page"

            6 -> "https://dflix.discoveryftp.net/m/lan/English/$page"

            else -> "https://dflix.discoveryftp.net/m/recent/$page"
        }
    }
}

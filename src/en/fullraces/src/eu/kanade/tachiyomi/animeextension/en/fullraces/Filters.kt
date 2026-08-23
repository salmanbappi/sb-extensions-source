package eu.kanade.tachiyomi.animeextension.en.fullraces

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class SeriesFilter : UriPartFilter("Series / Category", arrayOf(
        Pair("All Replays", ""),
        Pair("Formula 1 - 2026", "/2026"),
        Pair("Formula 1 - 2025", "/2025"),
        Pair("Formula 1 - 2024", "/watch/formula_1/formula_1_2024/21"),
        Pair("Formula 1 - 2023", "/formula1-2023"),
        Pair("Formula 1 - 2022", "/formula1-2022"),
        Pair("Formula 1 - 2021", "/formula1-2021"),
        Pair("Formula 1 - 2020", "/formula1-2020"),
        Pair("Formula 1 - 2019", "/formula1-2019"),
        Pair("Formula 1 - All F1 Races", "/formula1-replays"),
        Pair("Formula 1 - Archive (2000-2018)", "/formula1-archive-races"),
        Pair("NASCAR", "/nascar"),
        Pair("IndyCar", "/indycar"),
        Pair("WSBK", "/wsbk"),
        Pair("WRC", "/wrc"),
        Pair("Formula 2", "/f2-full-races"),
        Pair("Formula 3", "/f3-full-races"),
        Pair("Formula E", "/formula-e"),
        Pair("F1 Academy", "/f1-academy"),
    ))
}

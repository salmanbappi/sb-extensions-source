package eu.kanade.tachiyomi.animeextension.all.lunar

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

    class LanguageFilter : UriPartFilter(
        "Language",
        arrayOf(
            Pair("All", ""),
            Pair("German Dub (Ger)", "Ger"),
            Pair("German Sub (GerSub)", "GerSub"),
            Pair("English Sub (EngSub)", "EngSub"),
        ),
    )

    class YearFilter : AnimeFilter.Text("Year (e.g. 2024)", "")

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            GENRES.map { GenreCheckBox(it, it) },
        ) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    val GENRES = arrayOf(
        "Abenteuer",
        "Action",
        "Actiondrama",
        "Actionkomödie",
        "Alltagsdrama",
        "Alltagsleben",
        "Boys Love",
        "Drama",
        "Ecchi",
        "EngSub",
        "Erotik",
        "Fantasy",
        "Fighting-Shounen",
        "Ganbatte",
        "Geistergeschichten",
        "Ger",
        "GerSub",
        "Harem",
        "Horror",
        "Komödie",
        "Krimi",
        "Liebesdrama",
        "Magical Girl",
        "Mecha",
        "Mystery",
        "Nonsense-Komödie",
        "Psychodrama",
        "Romantische Komödie",
        "Romanze",
        "Scifi",
        "Sport",
        "Thriller",
        "Yuri",
        "Übermäßige Gewaltdarstellung",
    )

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        LanguageFilter(),
        YearFilter(),
        AnimeFilter.Separator(),
        GenreFilter(),
    )
}

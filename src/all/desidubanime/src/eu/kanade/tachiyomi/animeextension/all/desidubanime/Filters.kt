package eu.kanade.tachiyomi.animeextension.all.desidubanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {

    open class SelectFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
        state: Int = 0,
    ) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
        fun toPath(): String = vals[state].second
        fun isDefault(): Boolean = state == 0
    }

    class LanguageFilter : SelectFilter(
        "Language / Dub",
        arrayOf(
            "All Languages" to "",
            "Hindi" to "tag/hindi",
            "Tamil" to "tag/tamil",
            "Telugu" to "tag/telugu",
            "English" to "tag/english",
            "Japanese" to "tag/japanese",
            "Bengali" to "tag/bengali",
            "Malayalam" to "tag/malayalam",
            "Kannada" to "tag/kannada",
        ),
    )

    class GenreFilter : SelectFilter(
        "Genre",
        arrayOf(
            "All Genres" to "",
            "Action" to "genre/action",
            "Adventure" to "genre/adventure",
            "Comedy" to "genre/comedy",
            "Drama" to "genre/drama",
            "Ecchi" to "genre/ecchi",
            "Fantasy" to "genre/fantasy",
            "Horror" to "genre/horror",
            "Isekai" to "genre/isekai",
            "Martial Arts" to "genre/martial-arts",
            "Mecha" to "genre/mecha",
            "Music" to "genre/music",
            "Mystery" to "genre/mystery",
            "Psychological" to "genre/psychological",
            "Romance" to "genre/romance",
            "Sci-Fi" to "genre/sci-fi",
            "Shounen" to "genre/shounen",
            "Slice of Life" to "genre/slice-of-life",
            "Sports" to "genre/sports",
            "Supernatural" to "genre/supernatural",
            "Suspense" to "genre/suspense",
        ),
    )

    class SeasonFilter : SelectFilter(
        "Season",
        arrayOf(
            "All Seasons" to "",
            "Winter" to "season/winter",
            "Spring" to "season/spring",
            "Summer" to "season/summer",
            "Fall" to "season/fall",
        ),
    )
}

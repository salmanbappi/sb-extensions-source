package eu.kanade.tachiyomi.animeextension.en.animostream

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class GenreFilter :
        UriPartFilter(
            "Genre / Category",
            arrayOf(
                Pair("All", ""),
                Pair("Action", "Action"),
                Pair("Adventure", "Adventure"),
                Pair("Aliens", "Aliens"),
                Pair("Comedy", "Comedy"),
                Pair("Cooking", "Cooking"),
                Pair("Danmei", "Danmei"),
                Pair("Dark Comedy", "Dark Comedy"),
                Pair("Dark Fantasy", "Dark Fantasy"),
                Pair("Demons", "Demons"),
                Pair("Disaster", "Disaster"),
                Pair("Drama", "Drama"),
                Pair("Ecchi", "Ecchi"),
                Pair("Emotional", "Emotional"),
                Pair("Family Film", "Family Film"),
                Pair("Fantasy", "Fantasy"),
                Pair("Game", "Game"),
                Pair("Ghibli Movies", "Ghibli Movies"),
                Pair("Harem", "Harem"),
                Pair("Historical", "Historical"),
                Pair("Horror", "Horror"),
                Pair("Isekai", "Isekai"),
                Pair("Kids", "Kids"),
                Pair("Magic", "Magic"),
                Pair("Martial Arts", "Martial Arts"),
                Pair("Mecha", "Mecha"),
                Pair("Monsters", "Monsters"),
                Pair("Movies", "Movies"),
                Pair("Music", "Music"),
                Pair("Mystery", "Mystery"),
                Pair("Parody", "Parody"),
                Pair("Psychological", "Psychological"),
                Pair("Romance", "Romance"),
                Pair("Samurai", "Samurai"),
                Pair("School Life", "School Life"),
                Pair("Science Fiction", "Science Fiction"),
                Pair("Seinen", "Seinen"),
                Pair("Series", "Series"),
                Pair("Shojo", "Shojo"),
                Pair("Shonen", "Shonen"),
                Pair("Shounen", "Shounen"),
                Pair("Slice of Life", "Slice of Life"),
                Pair("Sports", "Sports"),
                Pair("Spy", "Spy"),
                Pair("Strategy", "Strategy"),
                Pair("Supernatural", "Supernatural"),
                Pair("Survival", "Survival"),
                Pair("Suspence", "Suspence"),
                Pair("Thriller", "Thriller"),
                Pair("Time Travel", "Time Travel"),
                Pair("Tragedy", "Tragedy"),
                Pair("Vampire", "Vampire"),
            ),
        )

    class LetterFilter :
        UriPartFilter(
            "Alphabetical Letter",
            arrayOf(
                Pair("All", ""),
                Pair("A", "A"),
                Pair("B", "B"),
                Pair("C", "C"),
                Pair("D", "D"),
                Pair("E", "E"),
                Pair("F", "F"),
                Pair("G", "G"),
                Pair("H", "H"),
                Pair("I", "I"),
                Pair("J", "J"),
                Pair("K", "K"),
                Pair("L", "L"),
                Pair("M", "M"),
                Pair("N", "N"),
                Pair("O", "O"),
                Pair("P", "P"),
                Pair("R", "R"),
                Pair("S", "S"),
                Pair("T", "T"),
                Pair("V", "V"),
                Pair("W", "W"),
                Pair("Y", "Y"),
                Pair("Z", "Z"),
            ),
        )
}

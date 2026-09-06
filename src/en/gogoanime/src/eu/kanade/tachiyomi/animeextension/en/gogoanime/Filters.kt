package eu.kanade.tachiyomi.animeextension.en.gogoanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class OrderByFilter :
        UriPartFilter(
            "Order by",
            arrayOf(
                Pair("Latest Update", "update"),
                Pair("Latest Added", "latest"),
                Pair("Popular", "popular"),
                Pair("Rating", "rating"),
                Pair("A-Z", "title"),
                Pair("Z-A", "titlereverse"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Ongoing", "ongoing"),
                Pair("Completed", "completed"),
                Pair("Upcoming", "upcoming"),
                Pair("Hiatus", "hiatus"),
            ),
        )

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV Series", "tv"),
                Pair("Movie", "movie"),
                Pair("OVA", "ova"),
                Pair("ONA", "ona"),
                Pair("Special", "special"),
                Pair("BD", "bd"),
                Pair("Live Action", "live action"),
                Pair("Music", "music"),
            ),
        )

    class SubFilter :
        UriPartFilter(
            "Sub/Dub",
            arrayOf(
                Pair("All", ""),
                Pair("Sub", "sub"),
                Pair("Dub", "dub"),
                Pair("RAW", "raw"),
            ),
        )

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)
    class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>("Genres", GENRES.map { GenreCheckBox(it.first, it.second) }) {
        fun getIncluded(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }
    }

    private val GENRE_IDS = mapOf(
        "Adult Cast" to "adult-cast",
        "Avant Garde" to "avant-garde",
        "Award Winning" to "award-winning",
        "Boys Love" to "boys-love",
        "CGDCT" to "cgdct",
        "Combat Sports" to "combat-sports",
        "Gag Humor" to "gag-humor",
        "Girls Love" to "girls-love",
        "High Stakes Game" to "high-stakes-game",
        "Idols (Female)" to "idols-female",
        "Idols (Male)" to "idols-male",
        "Love Polygon" to "love-polygon",
        "Love Status Quo" to "love-status-quo",
        "Magical Sex Shift" to "magical-sex-shift",
        "Mahou Shoujo" to "mahou-shoujo",
        "Martial Arts" to "martial-arts",
        "Organized Crime" to "organized-crime",
        "Otaku Culture" to "otaku-culture",
        "Performing Arts" to "performing-arts",
        "Reverse Harem" to "reverse-harem",
        "Sci-Fi" to "sci-fi",
        "Slice of Life" to "slice-of-life",
        "Strategy Game" to "strategy-game",
        "Super Power" to "super-power",
        "Team Sports" to "team-sports",
        "Time Travel" to "time-travel",
        "Urban Fantasy" to "urban-fantasy",
        "Video Game" to "video-game",
        "Visual Arts" to "visual-arts",
        "Workplace" to "workplace",
    )

    private val GENRES = listOf(
        "Action", "Adult Cast", "Adventure", "Anthropomorphic", "Avant Garde",
        "Award Winning", "Boys Love", "CGDCT", "Childcare", "Combat Sports",
        "Comedy", "Crossdressing", "Delinquents", "Detective", "Drama",
        "Ecchi", "Educational", "Erotica", "Fantasy", "Gag Humor",
        "Girls Love", "Gore", "Gourmet", "Harem", "Hentai",
        "High Stakes Game", "Historical", "Horror", "Idols (Female)", "Idols (Male)",
        "Isekai", "Iyashikei", "Josei", "Kids", "Love Polygon",
        "Love Status Quo", "Magical Sex Shift", "Mahou Shoujo", "Martial Arts", "Mecha",
        "Medical", "Military", "Music", "Mystery", "Mythology",
        "Organized Crime", "Otaku Culture", "Parody", "Performing Arts", "Pets",
        "Psychological", "Racing", "Reincarnation", "Reverse Harem", "Romance",
        "Samurai", "School", "Sci-Fi", "Seinen", "Shoujo",
        "Shounen", "Showbiz", "Slice of Life", "Space", "Sports",
        "Strategy Game", "Super Power", "Supernatural", "Survival", "Suspense",
        "Team Sports", "Time Travel", "Urban Fantasy", "Vampire", "Video Game",
        "Villainess", "Visual Arts", "Workplace",
    ).map { it to (GENRE_IDS[it] ?: it.lowercase().replace(" ", "-").replace("(", "").replace(")", "")) }
}

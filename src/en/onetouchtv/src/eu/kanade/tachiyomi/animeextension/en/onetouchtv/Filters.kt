package eu.kanade.tachiyomi.animeextension.en.onetouchtv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    open class SelectFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
        defaultValue: Int = 0,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        defaultValue,
    ) {
        val selected: String
            get() = vals[state].second
    }

    class TypeFilter : SelectFilter(
        "Type",
        arrayOf(
            "All" to "",
            "Drama" to "drama",
            "Movies" to "movie",
            "TV Shows" to "tv show",
        ),
    )

    class CountryFilter : SelectFilter(
        "Country",
        arrayOf(
            "All" to "",
            "Asia" to "asia",
            "Chinese" to "chinese",
            "Filipino" to "filipino",
            "Hong Kong" to "hong kong",
            "Japanese" to "japanese",
            "Korean" to "korean",
            "Singapore" to "singapore",
            "Taiwanese" to "taiwanese",
            "Thai" to "thai",
            "USA" to "usa",
        ),
    )

    class StatusFilter : SelectFilter(
        "Status",
        arrayOf(
            "All" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Upcoming" to "upcoming",
        ),
    )

    class YearFilter : AnimeFilter.Text("Year (e.g. 2024)")

    class Genre(name: String, val value: String) : AnimeFilter.CheckBox(name)

    class GenreListFilter(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genres", genres)

    fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores all filters"),
        TypeFilter(),
        CountryFilter(),
        StatusFilter(),
        YearFilter(),
        AnimeFilter.Separator(),
        GenreListFilter(getGenreList()),
    )

    private fun getGenreList(): List<Genre> = listOf(
            Genre("Accident", "accident"),
            Genre("Action", "action"),
            Genre("Adult", "adult"),
            Genre("Adventure", "adventure"),
            Genre("Alien", "alien"),
            Genre("Amnesia", "amnesia"),
            Genre("Ancient Legend", "ancient legend"),
            Genre("Animal", "animal"),
            Genre("Animals", "animals"),
            Genre("Animation", "animation"),
            Genre("Arthouse", "arthouse"),
            Genre("Artificial Intelligence", "artificial intelligence"),
            Genre("Audition", "audition"),
            Genre("Award Winning", "award winning"),
            Genre("Based On A Comic", "based on a comic"),
            Genre("Based On True Story", "based on true story"),
            Genre("Betrayal", "betrayal"),
            Genre("Biography", "biography"),
            Genre("BL", "bl"),
            Genre("Bodyguard", "bodyguard"),
            Genre("Bromance", "bromance"),
            Genre("Business", "business"),
            Genre("Chambara", "chambara"),
            Genre("Childhood", "childhood"),
            Genre("Christmas", "christmas"),
            Genre("Cohabitation", "cohabitation"),
            Genre("Cold Man", "cold man"),
            Genre("Coma", "coma"),
            Genre("Comedy", "comedy"),
            Genre("Concert", "concert"),
            Genre("Conglomerate", "conglomerate"),
            Genre("Conspiracy", "conspiracy"),
            Genre("Contract Relationship", "contract relationship"),
            Genre("Cooking", "cooking"),
            Genre("Corruption", "corruption"),
            Genre("Costume", "costume"),
            Genre("Crime", "crime"),
            Genre("Criminal", "criminal"),
            Genre("Curse", "curse"),
            Genre("Dance", "dance"),
            Genre("Deity", "deity"),
            Genre("Demon", "demon"),
            Genre("Detective", "detective"),
            Genre("Disability", "disability"),
            Genre("Disaster", "disaster"),
            Genre("Documentary", "documentary"),
            Genre("Doujinshi", "doujinshi"),
            Genre("Drama", "drama"),
            Genre("Eastern", "eastern"),
            Genre("Ecchi", "ecchi"),
            Genre("Educational", "educational"),
            Genre("Entertainment", "entertainment"),
            Genre("Environment", "environment"),
            Genre("Erotica", "erotica"),
            Genre("Espionage", "espionage"),
            Genre("Exorcism", "exorcism"),
            Genre("Fairy", "fairy"),
            Genre("Family", "family"),
            Genre("Fantasy", "fantasy"),
            Genre("Fashion", "fashion"),
            Genre("Feminism", "feminism"),
            Genre("Food", "food"),
            Genre("Foreign", "foreign"),
            Genre("Friendship", "friendship"),
            Genre("Game Developer", "game developer"),
            Genre("Gangster", "gangster"),
            Genre("Geishas", "geishas"),
            Genre("Gore", "gore"),
            Genre("Goryeo Dynasty", "goryeo dynasty"),
            Genre("Grudge", "grudge"),
            Genre("Gumiho", "gumiho"),
            Genre("Harem", "harem"),
            Genre("Hidden Identity", "hidden identity"),
            Genre("Historical", "historical"),
            Genre("Horror", "horror"),
            Genre("Hostage", "hostage"),
            Genre("Human", "human"),
            Genre("Hypnotism", "hypnotism"),
            Genre("Idol Drama", "idol drama"),
            Genre("Indie", "indie"),
            Genre("Instructional", "instructional"),
            Genre("Investigation", "investigation"),
            Genre("Jidai Geki", "jidai geki"),
            Genre("Josei", "josei"),
            Genre("Kidnapping", "kidnapping"),
            Genre("Kung Fu", "kung fu"),
            Genre("Law", "law"),
            Genre("Legal", "legal"),
            Genre("Lesbian", "lesbian"),
            Genre("LGBTQ+", "lgbtq+"),
            Genre("Life", "life"),
            Genre("Love Triangle", "love triangle"),
            Genre("Mafia", "mafia"),
            Genre("Magic", "magic"),
            Genre("Manga", "manga"),
            Genre("Manhua", "manhua"),
            Genre("Martial Arts", "martial arts"),
            Genre("Martialarts", "martialarts"),
            Genre("Mature", "mature"),
            Genre("Medical", "medical"),
            Genre("Melodrama", "melodrama"),
            Genre("Mermaid", "mermaid"),
            Genre("Military", "military"),
            Genre("Miniseries", "miniseries"),
            Genre("Monster", "monster"),
            Genre("Murder", "murder"),
            Genre("Music", "music"),
            Genre("Musical", "musical"),
            Genre("Mystery", "mystery"),
            Genre("Myth", "myth"),
            Genre("Mythology", "mythology"),
            Genre("Nature", "nature"),
            Genre("Neighbours", "neighbours"),
            Genre("Noir", "noir"),
            Genre("North Korea", "north korea"),
            Genre("Novel", "novel"),
            Genre("Omnibus", "omnibus"),
            Genre("One Shot", "one shot"),
            Genre("Parody", "parody"),
            Genre("Period", "period"),
            Genre("Period Drama", "period drama"),
            Genre("Phobia", "phobia"),
            Genre("Poison", "poison"),
            Genre("Police", "police"),
            Genre("Political", "political"),
            Genre("Power Struggle", "power struggle"),
            Genre("Prison", "prison"),
            Genre("Professional", "professional"),
            Genre("Programmer", "programmer"),
            Genre("Psychiatry", "psychiatry"),
            Genre("Psychological", "psychological"),
            Genre("Reality", "reality"),
            Genre("Reality Show", "reality show"),
            Genre("Reality Tv", "reality tv"),
            Genre("Rebellion", "rebellion"),
            Genre("Religion", "religion"),
            Genre("Remake", "remake"),
            Genre("Renzoku", "renzoku"),
            Genre("Republic", "republic"),
            Genre("Resurrection", "resurrection"),
            Genre("Revenge", "revenge"),
            Genre("Rich Man", "rich man"),
            Genre("Robot", "robot"),
            Genre("Romance", "romance"),
            Genre("RPG", "rpg"),
            Genre("Rural", "rural"),
            Genre("Samurai", "samurai"),
            Genre("Scholar", "scholar"),
            Genre("School", "school"),
            Genre("Sci-Fi", "sci-fi"),
            Genre("Seinen", "seinen"),
            Genre("Serial Killer", "serial killer"),
            Genre("Short", "short"),
            Genre("Sismance", "sismance"),
            Genre("Sitcom", "sitcom"),
            Genre("Slapstick", "slapstick"),
            Genre("Slice Of Life", "slice of life"),
            Genre("Society", "society"),
            Genre("Soulmates", "soulmates"),
            Genre("Sport", "sport"),
            Genre("Sports", "sports"),
            Genre("Stage Play", "stage play"),
            Genre("Supernatural", "supernatural"),
            Genre("Survival", "survival"),
            Genre("Suspense", "suspense"),
            Genre("Swordsman", "swordsman"),
            Genre("Taiga Drama", "taiga drama"),
            Genre("Teamwork", "teamwork"),
            Genre("Tearjerker", "tearjerker"),
            Genre("Teen", "teen"),
            Genre("Terrorist", "terrorist"),
            Genre("Thief", "thief"),
            Genre("Thriller", "thriller"),
            Genre("Time Travel", "time travel"),
            Genre("Tokusatsu", "tokusatsu"),
            Genre("Tomboy", "tomboy"),
            Genre("Tragedy", "tragedy"),
            Genre("Tragic Past", "tragic past"),
            Genre("Transmigration", "transmigration"),
            Genre("Trauma", "trauma"),
            Genre("Treason", "treason"),
            Genre("Triad", "triad"),
            Genre("Underworld", "underworld"),
            Genre("Unrequited Love", "unrequited love"),
            Genre("Urban Drama", "urban drama"),
            Genre("Vampire", "vampire"),
            Genre("Variety", "variety"),
            Genre("Variety Show", "variety show"),
            Genre("War", "war"),
            Genre("Warrior", "warrior"),
            Genre("Web Series", "web series"),
            Genre("Webtoon", "webtoon"),
            Genre("Werewolf", "werewolf"),
            Genre("Western", "western"),
            Genre("Witch", "witch"),
            Genre("Workplace", "workplace"),
            Genre("Wuxia", "wuxia"),
            Genre("Yakuza", "yakuza"),
            Genre("Yaoi", "yaoi"),
            Genre("Youth", "youth"),
            Genre("Yuri", "yuri"),
            Genre("Zombie", "zombie"),
    )

    data class FilterSearchParams(
        val type: String = "",
        val country: String = "",
        val status: String = "",
        val year: String = "",
        val genres: String = "",
    )

    fun getFilterParams(filters: AnimeFilterList): FilterSearchParams {
        var type = ""
        var country = ""
        var status = ""
        var year = ""
        val genreList = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> type = filter.selected
                is CountryFilter -> country = filter.selected
                is StatusFilter -> status = filter.selected
                is YearFilter -> year = filter.state.trim()
                is GenreListFilter -> {
                    filter.state.forEach { genre ->
                        if (genre.state) {
                            genreList.add(genre.value)
                        }
                    }
                }
                else -> {}
            }
        }

        return FilterSearchParams(
            type = type,
            country = country,
            status = status,
            year = year,
            genres = genreList.joinToString(","),
        )
    }
}

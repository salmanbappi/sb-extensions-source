package eu.kanade.tachiyomi.animeextension.en.xanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun getSelectedValue() = vals[state].second
        fun isDefault() = state == 0
    }

    private class GenreCheckBox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class GenreFilter : AnimeFilter.Group<AnimeFilter.CheckBox>(
        "Genres",
        GENRES.map { GenreCheckBox(it.first) },
    ) {
        fun getSelected(): List<String> = state.mapIndexedNotNull { index, filter ->
            if (filter.state) GENRES[index].second else null
        }
    }

    val GENRES = arrayOf(
        Pair("Action", "action"),
        Pair("Adult Cast", "adult_cast"),
        Pair("Adventure", "adventure"),
        Pair("Anthropomorphic", "anthropomorphic"),
        Pair("Avant Garde", "avant_garde"),
        Pair("Award Winning", "award_winning"),
        Pair("Boys Love", "boys_love"),
        Pair("Cars", "cars"),
        Pair("Cgdct", "cgdct"),
        Pair("Childcare", "childcare"),
        Pair("Combat Sports", "combat_sports"),
        Pair("Comedy", "comedy"),
        Pair("Crossdressing", "crossdressing"),
        Pair("Delinquents", "delinquents"),
        Pair("Dementia", "dementia"),
        Pair("Demons", "demons"),
        Pair("Detective", "detective"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Educational", "educational"),
        Pair("Erotica", "erotica"),
        Pair("Fantasy", "fantasy"),
        Pair("Gag Humor", "gag_humor"),
        Pair("Game", "game"),
        Pair("Girls Love", "girls_love"),
        Pair("Gore", "gore"),
        Pair("Gourmet", "gourmet"),
        Pair("Harem", "harem"),
        Pair("High Stakes Game", "high_stakes_game"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Idols Female", "idols_female"),
        Pair("Idols Male", "idols_male"),
        Pair("Isekai", "isekai"),
        Pair("Iyashikei", "iyashikei"),
        Pair("Josei", "josei"),
        Pair("Kids", "kids"),
        Pair("Love Polygon", "love_polygon"),
        Pair("Love Status Quo", "love_status_quo"),
        Pair("Magic", "magic"),
        Pair("Magical Sex Shift", "magical_sex_shift"),
        Pair("Mahou Shoujo", "mahou_shoujo"),
        Pair("Martial Arts", "martial_arts"),
        Pair("Mecha", "mecha"),
        Pair("Medical", "medical"),
        Pair("Military", "military"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Mythology", "mythology"),
        Pair("NSFW", "nsfw"),
        Pair("Organized Crime", "organized_crime"),
        Pair("Otaku Culture", "otaku_culture"),
        Pair("Parody", "parody"),
        Pair("Performing Arts", "performing_arts"),
        Pair("Pets", "pets"),
        Pair("Police", "police"),
        Pair("Psychological", "psychological"),
        Pair("Racing", "racing"),
        Pair("Reincarnation", "reincarnation"),
        Pair("Reverse Harem", "reverse_harem"),
        Pair("Romance", "romance"),
        Pair("Romantic Subtext", "romantic_subtext"),
        Pair("Samurai", "samurai"),
        Pair("School", "school"),
        Pair("Sci-Fi", "sci_fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shoujo Ai", "shoujo_ai"),
        Pair("Shounen", "shounen"),
        Pair("Shounen Ai", "shounen_ai"),
        Pair("Showbiz", "showbiz"),
        Pair("Slice of Life", "slice_of_life"),
        Pair("Space", "space"),
        Pair("Sports", "sports"),
        Pair("Strategy Game", "strategy_game"),
        Pair("Super Power", "super_power"),
        Pair("Supernatural", "supernatural"),
        Pair("Survival", "survival"),
        Pair("Suspense", "suspense"),
        Pair("Team Sports", "team_sports"),
        Pair("Thriller", "thriller"),
        Pair("Time Travel", "time_travel"),
        Pair("Urban Fantasy", "urban_fantasy"),
        Pair("Vampire", "vampire"),
        Pair("Video Game", "video_game"),
        Pair("Villainess", "villainess"),
        Pair("Visual Arts", "visual_arts"),
        Pair("Workplace", "workplace"),
    )

    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters below"),
        GenreFilter(),
    )
}

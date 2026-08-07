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

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Default / Latest Update", ""),
                Pair("Popularity", "field_popularity"),
                Pair("Latest Created", "date_create"),
                Pair("Latest Updated", "date_update"),
                Pair("Score / Rating", "score"),
                Pair("Views", "views"),
            ),
        )

    class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                Pair("All", ""),
                Pair("TV Series", "TV"),
                Pair("Movie", "Movie"),
                Pair("OVA", "OVA"),
                Pair("ONA", "ONA"),
                Pair("Special", "Special"),
            ),
        )

    class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                Pair("All", ""),
                Pair("Currently Airing", "currently_airing"),
                Pair("Finished Airing", "finished_airing"),
                Pair("Not Yet Aired", "not_yet_aired"),
            ),
        )

    class SeasonFilter :
        UriPartFilter(
            "Season",
            arrayOf(
                Pair("All", ""),
                Pair("Winter", "winter"),
                Pair("Spring", "spring"),
                Pair("Summer", "summer"),
                Pair("Fall", "fall"),
            ),
        )

    class SourceFilter :
        UriPartFilter(
            "Audio / Source",
            arrayOf(
                Pair("All", ""),
                Pair("Subbed", "sub"),
                Pair("Dubbed", "dub"),
            ),
        )

    class EpisodesFilter :
        UriPartFilter(
            "Episodes",
            arrayOf(
                Pair("All", ""),
                Pair("1 Episode", "1"),
                Pair("12 Episodes", "12"),
                Pair("24 Episodes", "24"),
            ),
        )

    class YearFilter :
        UriPartFilter(
            "Year",
            arrayOf(
                Pair("All", ""),
                Pair("2026", "2026"),
                Pair("2025", "2025"),
                Pair("2024", "2024"),
                Pair("2023", "2023"),
                Pair("2022", "2022"),
                Pair("2021", "2021"),
                Pair("2020", "2020"),
                Pair("2019", "2019"),
                Pair("2018", "2018"),
                Pair("2017", "2017"),
                Pair("2016", "2016"),
                Pair("2015", "2015"),
                Pair("2014", "2014"),
                Pair("2013", "2013"),
                Pair("2012", "2012"),
                Pair("2011", "2011"),
                Pair("2010", "2010"),
                Pair("2009", "2009"),
                Pair("2008", "2008"),
                Pair("2007", "2007"),
                Pair("2006", "2006"),
                Pair("2005", "2005"),
                Pair("2004", "2004"),
                Pair("2003", "2003"),
                Pair("2002", "2002"),
                Pair("2001", "2001"),
                Pair("2000", "2000"),
                Pair("1999", "1999"),
                Pair("1998", "1998"),
                Pair("1997", "1997"),
                Pair("1996", "1996"),
                Pair("1995", "1995"),
            ),
        )

    private class GenreCheckBox(name: String, state: Boolean = false) : AnimeFilter.CheckBox(name, state)

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
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
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        SeasonFilter(),
        SourceFilter(),
        EpisodesFilter(),
        YearFilter(),
        GenreFilter(),
    )
}

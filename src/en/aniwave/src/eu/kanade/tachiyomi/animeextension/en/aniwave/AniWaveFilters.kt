package eu.kanade.tachiyomi.animeextension.en.aniwave

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.HttpUrl

object AniWaveFilters {

    class GenreFilter : AnimeFilter.Group<GenreVal>("Genres", GENRES)
    class GenreVal(name: String, val id: String) : AnimeFilter.TriState(name)

    class SeasonFilter : AnimeFilter.Group<SeasonVal>("Seasons", SEASONS)
    class SeasonVal(name: String, val id: String) : AnimeFilter.CheckBox(name)

    class YearFilter : AnimeFilter.Group<YearVal>("Years", YEARS)
    class YearVal(name: String, val id: String) : AnimeFilter.CheckBox(name)

    class TypeFilter : AnimeFilter.Group<TypeVal>("Types", TYPES)
    class TypeVal(name: String, val id: String) : AnimeFilter.CheckBox(name)

    class StatusFilter : AnimeFilter.Group<StatusVal>("Status", STATUSES)
    class StatusVal(name: String, val id: String) : AnimeFilter.CheckBox(name)

    class LanguageFilter : AnimeFilter.Group<LanguageVal>("Language", LANGUAGES)
    class LanguageVal(name: String, val id: String) : AnimeFilter.CheckBox(name)

    class RatingFilter : AnimeFilter.Group<RatingVal>("Rating", RATINGS)
    class RatingVal(name: String, val id: String) : AnimeFilter.CheckBox(name)

    class SortFilter : AnimeFilter.Select<String>("Sort", SORT_OPTIONS.map { it.first }.toTypedArray())

    val FILTER_LIST: AnimeFilterList
        get() = AnimeFilterList(
            AnimeFilter.Header("Text search ignores all filters"),
            GenreFilter(),
            SeasonFilter(),
            YearFilter(),
            TypeFilter(),
            StatusFilter(),
            LanguageFilter(),
            RatingFilter(),
            SortFilter(),
        )

    data class FilterSearchParams(
        val genres: List<String> = emptyList(),
        val seasons: List<String> = emptyList(),
        val years: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val statuses: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val ratings: List<String> = emptyList(),
        val sort: String = "",
    )

    fun getSearchParameters(filters: AnimeFilterList): FilterSearchParams {
        if (filters.isEmpty()) return FilterSearchParams()

        var genres = emptyList<String>()
        var seasons = emptyList<String>()
        var years = emptyList<String>()
        var types = emptyList<String>()
        var statuses = emptyList<String>()
        var languages = emptyList<String>()
        var ratings = emptyList<String>()
        var sort = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    genres = filter.state.mapNotNull {
                        when (it.state) {
                            AnimeFilter.TriState.STATE_INCLUDE -> it.id
                            AnimeFilter.TriState.STATE_EXCLUDE -> "-${it.id}"
                            else -> null
                        }
                    }
                }
                is SeasonFilter -> seasons = filter.state.filter { it.state }.map { it.id }
                is YearFilter -> years = filter.state.filter { it.state }.map { it.id }
                is TypeFilter -> types = filter.state.filter { it.state }.map { it.id }
                is StatusFilter -> statuses = filter.state.filter { it.state }.map { it.id }
                is LanguageFilter -> languages = filter.state.filter { it.state }.map { it.id }
                is RatingFilter -> ratings = filter.state.filter { it.state }.map { it.id }
                is SortFilter -> sort = SORT_OPTIONS[filter.state].second
                else -> {}
            }
        }

        return FilterSearchParams(genres, seasons, years, types, statuses, languages, ratings, sort)
    }

    fun HttpUrl.Builder.addListQueryParameter(name: String, values: List<String>) {
        values.forEach { addQueryParameter("${name}[]", it) }
    }

    fun HttpUrl.Builder.addQueryParameterIfNotEmpty(name: String, value: String) {
        if (value.isNotEmpty()) addQueryParameter(name, value)
    }

    private val GENRES = listOf(
        GenreVal("Action", "1"),
        GenreVal("Adventure", "2"),
        GenreVal("Avant Garde", "2262989"),
        GenreVal("Boys Love", "2262991"),
        GenreVal("Comedy", "4"),
        GenreVal("Demons", "6"),
        GenreVal("Drama", "7"),
        GenreVal("Ecchi", "8"),
        GenreVal("Fantasy", "9"),
        GenreVal("Girls Love", "2263743"),
        GenreVal("Gourmet", "2263289"),
        GenreVal("Harem", "11"),
        GenreVal("Horror", "14"),
        GenreVal("Isekai", "3471041"),
        GenreVal("Iyashikei", "3594047"),
        GenreVal("Josei", "15"),
        GenreVal("Kids", "16"),
        GenreVal("Magic", "17"),
        GenreVal("Mahou Shoujo", "3472337"),
        GenreVal("Martial Arts", "18"),
        GenreVal("Mecha", "19"),
        GenreVal("Military", "20"),
        GenreVal("Music", "21"),
        GenreVal("Mystery", "22"),
        GenreVal("Parody", "23"),
        GenreVal("Psychological", "25"),
        GenreVal("Reverse Harem", "3750033"),
        GenreVal("Romance", "26"),
        GenreVal("Samurai", "28"),
        GenreVal("School", "29"),
        GenreVal("Sci-Fi", "30"),
        GenreVal("Seinen", "31"),
        GenreVal("Shoujo", "32"),
        GenreVal("Shounen", "34"),
        GenreVal("Slice of Life", "36"),
        GenreVal("Space", "37"),
        GenreVal("Sports", "38"),
        GenreVal("Super Power", "39"),
        GenreVal("Supernatural", "40"),
        GenreVal("Suspense", "2263291"),
        GenreVal("Thriller", "41"),
        GenreVal("Vampire", "42"),
    )

    private val SEASONS = listOf(
        SeasonVal("Fall", "fall"),
        SeasonVal("Summer", "summer"),
        SeasonVal("Spring", "spring"),
        SeasonVal("Winter", "winter"),
        SeasonVal("Unknown", "unknown"),
    )

    private val YEARS = listOf(
        YearVal("2026", "2026"),
        YearVal("2025", "2025"),
        YearVal("2024", "2024"),
        YearVal("2023", "2023"),
        YearVal("2022", "2022"),
        YearVal("2021", "2021"),
        YearVal("2020", "2020"),
        YearVal("2019", "2019"),
        YearVal("2018", "2018"),
        YearVal("2017", "2017"),
        YearVal("2016", "2016"),
        YearVal("2015", "2015"),
        YearVal("2014", "2014"),
        YearVal("2013", "2013"),
        YearVal("2012", "2012"),
        YearVal("2011", "2011"),
        YearVal("2010", "2010"),
        YearVal("2000s", "2000s"),
        YearVal("1990s", "1990s"),
        YearVal("1980s", "1980s"),
        YearVal("1970s", "1970s"),
        YearVal("1960s", "1960s"),
        YearVal("1950s", "1950s"),
        YearVal("1940s", "1940s"),
    )

    private val TYPES = listOf(
        TypeVal("Movie", "movie"),
        TypeVal("TV", "tv"),
        TypeVal("OVA", "ova"),
        TypeVal("ONA", "ona"),
        TypeVal("Special", "special"),
        TypeVal("Music", "music"),
    )

    private val STATUSES = listOf(
        StatusVal("Completed", "completed"),
        StatusVal("Releasing", "releasing"),
        StatusVal("Not Yet Aired", "not-yet-aired"),
    )

    private val LANGUAGES = listOf(
        LanguageVal("Sub", "sub"),
        LanguageVal("SoftSub", "softsub"),
        LanguageVal("Dub", "dub"),
    )

    private val RATINGS = listOf(
        RatingVal("G - All Ages", "g"),
        RatingVal("PG - Children", "pg"),
        RatingVal("PG-13 - Teens 13+", "pg-13"),
        RatingVal("R - 17+ (violence & profanity)", "r"),
        RatingVal("R+ - Mild Nudity", "r+"),
        RatingVal("Rx - Hentai", "rx"),
    )

    private val SORT_OPTIONS = listOf(
        "Default" to "",
        "Recently Updated" to "recently_updated",
        "Recently Added" to "recently_added",
        "Release Date" to "release_date",
        "Trending" to "trending",
        "Name A-Z" to "name_az",
        "Scores" to "scores",
        "MAL Scores" to "mal_scores",
        "Most Watched" to "most_watched",
        "Most Favourited" to "most_favourited",
    )
}

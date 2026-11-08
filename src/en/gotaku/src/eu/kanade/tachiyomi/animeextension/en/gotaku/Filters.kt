package eu.kanade.tachiyomi.animeextension.en.gotaku

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.applicationContext
import keiyoushi.utils.parseAs
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.time.Duration.Companion.seconds

object Filters {
    private const val GENRE_PARAM = "genre"
    private const val THEME_PARAM = "tag"

    private val cacheFile by lazy { File(applicationContext.filesDir, "gotaku_filters.json") }

    class FilterData(
        val genres: List<Pair<String, String>>,
        val themes: List<Pair<String, String>>,
        val types: List<Pair<String, String>>,
        val statuses: List<Pair<String, String>>,
        val seasons: List<Pair<String, String>>,
        val ratings: List<Pair<String, String>>,
        val years: List<Pair<String, String>>,
        val countries: List<Pair<String, String>>,
    )

    fun hasCache(): Boolean = runCatching { cacheFile.exists() }.getOrDefault(false)

    fun getFilterList(): AnimeFilterList {
        val data = loadCachedFilters() ?: DEFAULT_DATA
        return buildList(data)
    }

    fun fetchAndCacheFilters(client: OkHttpClient, headers: Headers, apiUrl: String) {
        try {
            val filterClient = client.newBuilder()
                .connectTimeout(5.seconds)
                .readTimeout(8.seconds)
                .build()

            filterClient.newCall(Request.Builder().url("/filters").headers(headers).build()).execute().use { response ->
                if (!response.isSuccessful) return
                val jsonString = response.body.string()
                runCatching { jsonString.parseAs<FiltersResponseDto>() }.getOrNull()?.data ?: return
                if (!cacheFile.exists() || cacheFile.readText() != jsonString) {
                    cacheFile.writeText(jsonString)
                }
            }
        } catch (_: Exception) {}
    }

    private fun loadCachedFilters(): FilterData? {
        return try {
            if (!cacheFile.exists()) return null
            val dto = cacheFile.readText().parseAs<FiltersResponseDto>().data
            FilterData(
                genres = dto.genres.map { it.name to it.id.toString() }.distinct(),
                themes = dto.themes.map { it.name to it.id.toString() }.distinct(),
                types = dto.types.map { it.label to it.value }.distinct(),
                statuses = dto.statuses.map { it.label to it.value }.distinct(),
                seasons = dto.seasons.map { it.label to it.value }.distinct(),
                ratings = dto.ratings.map { it.label to it.value }.distinct(),
                years = dto.years.map { it.label to it.value }.distinct(),
                countries = dto.countries.map { it.label to it.value }.distinct().ifEmpty { DEFAULT_COUNTRIES },
            )
        } catch (_: Exception) {
            runCatching { cacheFile.delete() }
            null
        }
    }

    // ============================== Filters ===============================
    class SortFilter : AnimeFilter.Select<String>("Sort By", SORT_ENTRIES, 0) {
        fun getValue() = SORT_VALUES[state]

        companion object {
            private val SORT_ENTRIES = arrayOf(
                "Recently updated",
                "Trending",
                "Highest rated",
                "Most viewed",
                "Newest",
                "Name A–Z",
            )
            private val SORT_VALUES = arrayOf(
                "latest",
                "trending_week",
                "score",
                "views",
                "new_releases",
                "name",
            )
        }
    }

    class TrackFilter : AnimeFilter.Select<String>("Audio", arrayOf("Any", "Sub", "Dub"), 0) {
        fun getValue(): String? = if (state == 0) null else VALUES[state]

        companion object {
            private val VALUES = arrayOf(null, "sub", "dub")
        }
    }

    class SelectFilter(
        name: String,
        val param: String,
        private val optionValues: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, arrayOf("Any") + optionValues.map { it.first }) {
        fun getValue(): String? = if (state == 0) null else optionValues[state - 1].second
    }

    class TriStateVal(name: String, val value: String) : AnimeFilter.TriState(name)

    class TriStateGroup(
        name: String,
        val param: String,
        values: List<Pair<String, String>>,
    ) : AnimeFilter.Group<TriStateVal>(name, values.map { TriStateVal(it.first, it.second) })

    class TagModeFilter :
        AnimeFilter.Select<String>(
            "Tag Match Mode",
            arrayOf("Any (OR)", "All (AND)"),
            1,
        ) {
        fun getValue(): String? = when (state) {
            0 -> "any"
            1 -> null
            else -> null
        }
    }

    private fun buildList(d: FilterData) = AnimeFilterList(
        SortFilter(),
        SelectFilter("Format", "type", d.types),
        SelectFilter("Status", "status", d.statuses),
        SelectFilter("Season", "season", d.seasons),
        SelectFilter("Year", "year", d.years),
        SelectFilter("Country", "country", d.countries),
        TrackFilter(),
        SelectFilter("Content Rating", "rating", d.ratings),
        TriStateGroup("Genres", GENRE_PARAM, d.genres),
        TriStateGroup("Themes", THEME_PARAM, d.themes),
        TagModeFilter(),
    )

    // ============================ Static Fallback ===========================
    private val DEFAULT_GENRES = listOf(
        "Action" to "1",
        "Adventure" to "2",
        "Avant Garde" to "3",
        "Award Winning" to "4",
        "Boys Love" to "5",
        "Comedy" to "6",
        "Drama" to "7",
        "Ecchi" to "19",
        "Erotica" to "20",
        "Fantasy" to "8",
        "Girls Love" to "9",
        "Gourmet" to "10",
        "Hentai" to "21",
        "Horror" to "11",
        "Mystery" to "12",
        "Romance" to "13",
        "Sci-Fi" to "14",
        "Slice of Life" to "15",
        "Sports" to "16",
        "Supernatural" to "17",
        "Suspense" to "18",
    )
    private val DEFAULT_THEMES = listOf(
        "Adult Cast" to "22",
        "Anthropomorphic" to "23",
        "CGDCT" to "24",
        "Childcare" to "25",
        "Combat Sports" to "26",
        "Crossdressing" to "27",
        "Delinquents" to "28",
        "Detective" to "29",
        "Educational" to "30",
        "Gag Humor" to "31",
        "Gore" to "32",
        "Harem" to "33",
        "High Stakes Game" to "34",
        "Historical" to "35",
        "Idols (Female)" to "36",
        "Idols (Male)" to "37",
        "Isekai" to "38",
        "Iyashikei" to "39",
        "Love Polygon" to "40",
        "Love Status Quo" to "41",
        "Magical Sex Shift" to "42",
        "Mahou Shoujo" to "43",
        "Martial Arts" to "44",
        "Mecha" to "45",
        "Medical" to "46",
        "Military" to "47",
        "Music" to "48",
        "Mythology" to "49",
        "Organized Crime" to "50",
        "Otaku Culture" to "51",
        "Parody" to "52",
        "Performing Arts" to "53",
        "Pets" to "54",
        "Psychological" to "55",
        "Racing" to "56",
        "Reincarnation" to "57",
        "Reverse Harem" to "58",
        "Samurai" to "59",
        "School" to "60",
        "Showbiz" to "61",
        "Space" to "62",
        "Strategy Game" to "63",
        "Super Power" to "64",
        "Survival" to "65",
        "Team Sports" to "66",
        "Time Travel" to "67",
        "Urban Fantasy" to "68",
        "Vampire" to "69",
        "Video Game" to "70",
        "Villainess" to "71",
        "Visual Arts" to "72",
        "Workplace" to "73",
    )
    private val DEFAULT_TYPES = listOf(
        "TV" to "TV",
        "Movie" to "MOVIE",
        "Special" to "SPECIAL",
        "OVA" to "OVA",
        "ONA" to "ONA",
        "Music" to "MUSIC",
    )
    private val DEFAULT_STATUSES = listOf(
        "Finished" to "FINISHED",
        "Releasing" to "RELEASING",
        "Not yet released" to "NOT_YET_RELEASED",
        "Cancelled" to "CANCELLED",
        "Hiatus" to "HIATUS",
    )
    private val DEFAULT_SEASONS = listOf(
        "Winter" to "WINTER",
        "Spring" to "SPRING",
        "Summer" to "SUMMER",
        "Fall" to "FALL",
    )
    private val DEFAULT_RATINGS = listOf(
        "G" to "G",
        "PG" to "PG",
        "PG-13" to "PG_13",
        "R" to "R",
        "R+" to "R_PLUS",
        "Rx" to "RX",
    )
    private val DEFAULT_YEARS = listOf(
        "2026" to "2026",
        "2025" to "2025",
        "2024" to "2024",
        "2023" to "2023",
        "2022" to "2022",
        "2021" to "2021",
        "2020" to "2020",
        "2019" to "2019",
        "2018" to "2018",
        "2017" to "2017",
        "2016" to "2016",
        "2015" to "2015",
        "2014" to "2014",
        "2013" to "2013",
        "2012" to "2012",
        "2011" to "2011",
        "2010" to "2010",
        "2000s" to "2000s",
        "1990s" to "1990s",
        "1980s" to "1980s",
        "1970s" to "1970s",
        "1960s" to "1960s",
        "1950s" to "1950s",
        "1940s" to "1940s",
        "1930s" to "1930s",
        "1920s" to "1920s",
    )
    private val DEFAULT_COUNTRIES = listOf(
        "China" to "CN",
        "Japan" to "JP",
        "South Korea" to "KR",
        "Taiwan" to "TW",
    )

    private val DEFAULT_DATA = FilterData(
        genres = DEFAULT_GENRES,
        themes = DEFAULT_THEMES,
        types = DEFAULT_TYPES,
        statuses = DEFAULT_STATUSES,
        seasons = DEFAULT_SEASONS,
        ratings = DEFAULT_RATINGS,
        years = DEFAULT_YEARS,
        countries = DEFAULT_COUNTRIES,
    )
}

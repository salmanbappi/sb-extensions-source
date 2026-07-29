package eu.kanade.tachiyomi.animeextension.en.shuttletv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import java.util.Calendar

object Filters {

    class TypeFilter :
        UriPartFilter(
            "Media Type",
            arrayOf(
                Pair("All", "all"),
                Pair("Movie", "movie"),
                Pair("TV Show", "tv"),
            ),
        )

    class CategoryFilter :
        UriPartFilter(
            "Category",
            arrayOf(
                Pair("Trending", "trending"),
                Pair("Popular", "popular"),
                Pair("Award Winners", "award_winners"),
                Pair("Discover", "discover"),
            ),
        )

    class SortFilter :
        UriPartFilter(
            "Sort By",
            arrayOf(
                Pair("Popularity (Desc)", "popularity.desc"),
                Pair("Popularity (Asc)", "popularity.asc"),
                Pair("Release Date (Desc)", "primary_release_date.desc"),
                Pair("Release Date (Asc)", "primary_release_date.asc"),
                Pair("Vote Average (Desc)", "vote_average.desc"),
                Pair("Vote Average (Asc)", "vote_average.asc"),
                Pair("Title (AZ)", "title.asc"),
                Pair("Title (ZA)", "title.desc"),
            ),
        )

    class YearFilter :
        UriPartFilter(
            "Year",
            YEARS,
        ) {
        companion object {
            private val CURRENT_YEAR by lazy {
                Calendar.getInstance().get(Calendar.YEAR)
            }

            private val YEARS = buildList {
                add(Pair("All", ""))
                addAll((CURRENT_YEAR downTo 1970).map { Pair(it.toString(), it.toString()) })
            }.toTypedArray()
        }
    }

    class GenreCheckBox(name: String, val id: String) : AnimeFilter.CheckBox(name, false)

    class GenreFilter :
        AnimeFilter.Group<AnimeFilter.CheckBox>(
            "Genres",
            GENRES.map { GenreCheckBox(it.first, it.second) },
        ) {
        fun selectedIds(): List<String> = state.filter { it.state }.map { (it as GenreCheckBox).id }

        companion object {
            private val GENRES = listOf(
                Pair("Action", "28"),
                Pair("Action & Adventure", "10759"),
                Pair("Adventure", "12"),
                Pair("Animation", "16"),
                Pair("Comedy", "35"),
                Pair("Crime", "80"),
                Pair("Documentary", "99"),
                Pair("Drama", "18"),
                Pair("Family", "10751"),
                Pair("Fantasy", "14"),
                Pair("History", "36"),
                Pair("Horror", "27"),
                Pair("Kids", "10762"),
                Pair("Music", "10402"),
                Pair("Mystery", "9648"),
                Pair("News", "10763"),
                Pair("Reality", "10764"),
                Pair("Romance", "10749"),
                Pair("Science Fiction", "878"),
                Pair("Sci-Fi & Fantasy", "10765"),
                Pair("Soap", "10766"),
                Pair("Talk", "10767"),
                Pair("Thriller", "53"),
                Pair("War", "10752"),
                Pair("War & Politics", "10768"),
                Pair("Western", "37"),
            )
        }
    }

    class CountryFilter :
        UriPartFilter(
            "Country",
            COUNTRIES,
        ) {
        companion object {
            private val COUNTRIES = arrayOf(
                Pair("All", ""),
                Pair("United States", "US"),
                Pair("United Kingdom", "GB"),
                Pair("Canada", "CA"),
                Pair("Australia", "AU"),
                Pair("Ireland", "IE"),
                Pair("New Zealand", "NZ"),
                Pair("France", "FR"),
                Pair("Germany", "DE"),
                Pair("Spain", "ES"),
                Pair("Italy", "IT"),
                Pair("Japan", "JP"),
                Pair("South Korea", "KR"),
                Pair("China", "CN"),
                Pair("India", "IN"),
                Pair("Brazil", "BR"),
                Pair("Mexico", "MX"),
                Pair("Russia", "RU"),
                Pair("Sweden", "SE"),
                Pair("Norway", "NO"),
                Pair("Denmark", "DK"),
                Pair("Netherlands", "NL"),
                Pair("Turkey", "TR"),
                Pair("Thailand", "TH"),
                Pair("Hong Kong", "HK"),
                Pair("Taiwan", "TW"),
            )
        }
    }

    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }
}

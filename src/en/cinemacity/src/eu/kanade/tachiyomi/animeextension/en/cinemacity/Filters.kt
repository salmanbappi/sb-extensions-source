package eu.kanade.tachiyomi.animeextension.en.cinemacity

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

class TypeFilter :
    AnimeFilter.Select<String>(
        "Content Type",
        types.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = types[state].second

    companion object {
        val types = listOf(
            Pair("Movies", "movies"),
            Pair("TV Series", "tv-series"),
        )
    }
}

class SortFilter :
    AnimeFilter.Select<String>(
        "Sort By",
        sorts.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = sorts[state].second

    companion object {
        val sorts = listOf(
            Pair("Default (Latest)", "date/order=desc"),
            Pair("Rating", "rating/order=desc"),
            Pair("Popularity (Views)", "news_read/order=desc"),
            Pair("Comments", "comm_num/order=desc"),
            Pair("Title (A-Z)", "title/order=asc"),
        )
    }
}

class GenreFilter :
    AnimeFilter.Select<String>(
        "Genre",
        genres.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = genres[state].second

    companion object {
        val genres = listOf(
            Pair("All", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Animation", "animation"),
            Pair("Anime", "anime"),
            Pair("Asian", "asian"),
            Pair("Biography", "biography"),
            Pair("Indian", "indian"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Documentary", "documentary"),
            Pair("Drama", "drama"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Film-Noir", "film-noir"),
            Pair("Game-Show", "game-show"),
            Pair("History", "history"),
            Pair("Horror", "horror"),
            Pair("Music", "music"),
            Pair("Musical", "musical"),
            Pair("Mystery", "mystery"),
            Pair("News", "news"),
            Pair("Reality-TV", "reality-tv"),
            Pair("Romance", "romance"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Short", "short"),
            Pair("Sport", "sport"),
            Pair("Specials", "specials"),
            Pair("Stand-Up", "stand-up"),
            Pair("Talk-Show", "talk-show"),
            Pair("Thriller", "thriller"),
            Pair("War", "war"),
            Pair("Western", "western"),
        )
    }
}

class YearFilter :
    AnimeFilter.Select<String>(
        "Release Year",
        years.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = years[state].second

    companion object {
        val years = listOf(
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
        )
    }
}

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
            Pair("Default", ""),
            Pair("Latest", "sort=date/order=desc"),
            Pair("Rating", "sort=rating/order=desc"),
            Pair("Popularity (Views)", "sort=news_read/order=desc"),
            Pair("Comments", "sort=comm_num/order=desc"),
            Pair("Title (A-Z)", "sort=title/order=asc"),
        )
    }
}

class QualityFilter :
    AnimeFilter.Select<String>(
        "Quality",
        qualities.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = qualities[state].second

    companion object {
        val qualities = listOf(
            Pair("All", ""),
            Pair("WEB-DL", "WEB-DL"),
            Pair("TS", "TS"),
            Pair("CAM-Rip", "CAM-Rip"),
        )
    }
}

class StatusFilter :
    AnimeFilter.Select<String>(
        "Status (TV Series)",
        statuses.map { it.first }.toTypedArray(),
    ) {
    val selected: String get() = statuses[state].second

    companion object {
        val statuses = listOf(
            Pair("All", ""),
            Pair("Ongoing", "Ongoing"),
            Pair("Ended", "Ended"),
        )
    }
}

class YearFilter : AnimeFilter.Text("Release Year (e.g. 2025)")

class GenreCheckBox(name: String, val slug: String) : AnimeFilter.CheckBox(name)

class GenreGroup :
    AnimeFilter.Group<GenreCheckBox>(
        "Genres (Multi-Select)",
        listOf(
            GenreCheckBox("Action", "Action"),
            GenreCheckBox("Adventure", "Adventure"),
            GenreCheckBox("Animation", "Animation"),
            GenreCheckBox("Anime", "Anime"),
            GenreCheckBox("Asian", "Asian"),
            GenreCheckBox("Biography", "Biography"),
            GenreCheckBox("Indian", "Indian"),
            GenreCheckBox("Comedy", "Comedy"),
            GenreCheckBox("Crime", "Crime"),
            GenreCheckBox("Documentary", "Documentary"),
            GenreCheckBox("Drama", "Drama"),
            GenreCheckBox("Family", "Family"),
            GenreCheckBox("Fantasy", "Fantasy"),
            GenreCheckBox("Film-Noir", "Film-Noir"),
            GenreCheckBox("Game-Show", "Game-Show"),
            GenreCheckBox("History", "History"),
            GenreCheckBox("Horror", "Horror"),
            GenreCheckBox("Music", "Music"),
            GenreCheckBox("Musical", "Musical"),
            GenreCheckBox("Mystery", "Mystery"),
            GenreCheckBox("News", "News"),
            GenreCheckBox("Reality-TV", "Reality-TV"),
            GenreCheckBox("Romance", "Romance"),
            GenreCheckBox("Sci-Fi", "Sci-Fi"),
            GenreCheckBox("Short", "Short"),
            GenreCheckBox("Sport", "Sport"),
            GenreCheckBox("Specials", "Specials"),
            GenreCheckBox("Stand-Up", "Stand-Up"),
            GenreCheckBox("Talk-Show", "Talk-Show"),
            GenreCheckBox("Thriller", "Thriller"),
            GenreCheckBox("War", "War"),
            GenreCheckBox("Western", "Western"),
        ),
    )

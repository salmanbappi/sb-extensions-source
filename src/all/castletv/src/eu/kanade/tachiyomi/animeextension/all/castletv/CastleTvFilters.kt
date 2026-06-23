package eu.kanade.tachiyomi.animeextension.all.castletv

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

class SectionFilter :
    AnimeFilter.Select<String>(
        "Section",
        arrayOf(
            "Home",
            "TV Shows",
            "Movies",
            "Anime",
        ),
    )

class CategoryFilter :
    AnimeFilter.Select<String>(
        "Category",
        arrayOf(
            "All",
            // Home
            "Watch Popular Movies",
            "Popular WebSeries",
            "Upcoming",
            "Most Watching Trending Now",
            "Crazy Short Shows",
            "Hottest International Film",
            "Recommended Movies",
            "Bollywood Movie",
            "Anime (Home)",
            "Most watched movies in 2025",
            "Bollywood Star",
            "Hollywood Movie",
            "Most Romantic Movie & Series",
            "Fantasy and Adventure",
            "Marvel Universe",
            "Supernatural & Thriller",
            "Best of Superheroes",
            "DC Extended Universe",
            "Tollywood Movies",
            "Top Movie For Kids",
            "Most Recommended Series",
            "Popular Comedy",
            "The best Series of 2025",
            "Reality Shows",
            "Crime, Investigation & Mystery",
            "If you are timid, don't click in",

            // TV Shows
            "Popular TV Shows & WebSeries",
            "International Web Series",
            "Top 20 Series This Week",
            "Recommend Series",
            "Korean Drama Hindi dubbed",
            "Best Of Reality-Show",
            "Mystery and Thriller",
            "Most Watched Netflix Series",
            "Marvel series",
            "Anime (TV Shows)",
            "Good Action Series",
            "Erotic Series",
            "Romantic Love",
            "Crime Series",
            "Hilarious Comedy",
            "Long Series",
            "Human and Nature",

            // Movies
            "Hit Movies",
            "International Movies",
            "Most Popular Movies for Boys",
            "Tollywood",
            "Hottest Bollywood Actor",
            "Erotic",
            "Blockbuster Movies",
            "Bollywood",
            "Top 50 Most Viewed",
            "Action & Adventure",
            "Comedy",
            "Romance",
            "Crime",
            "Thriller",
            "Most Popular Celebs",
            "X-Men",
            "Indian Regional Movies",
            "Hottest Bollywood Actresses",
            "Fast and Furious · Game and Magic",
            "Classic Collection",
            "cartoon",

            // Anime
            "Ongoing Anime",
            "The Most Popular Anime",
            "Must Watch Anime For Solo Leveling Fans!",
            "Top 10 Anime This Week",
            "Sweet love",
            "Adrenaline Rush Anime",
            "Featured Recommendations",
            "Isekai",
            "Fantasy Adventure",
            "Adult Animes",
            "Happy time",
            "Movie Anime",
        ),
    )

class GenreFilter :
    AnimeFilter.Select<String>(
        "Genre",
        arrayOf(
            "All",
            "Action",
            "Drama",
            "Comedy",
            "Romance",
            "Thriller/Crime",
            "Sci-Fi/Adventure",
            "Fantasy/Mystery",
            "Cartoon/Animation",
        ),
    )

class PlatformFilter :
    AnimeFilter.Select<String>(
        "Platform",
        arrayOf(
            "All",
            "Netflix",
            "Disney+ Hotstar",
            "Zee5",
            "Prime Video",
            "MX Player",
            "Crunchyroll",
        ),
    )

class LanguageFilter :
    AnimeFilter.Select<String>(
        "Language",
        arrayOf(
            "All",
            "Hindi",
            "English",
            "Tamil",
            "Telugu",
            "Malayalam",
            "Kannada",
            "Bengali",
            "Punjabi",
            "Marathi",
            "Korean",
            "Japanese",
        ),
    )

class RegionFilter :
    AnimeFilter.Select<String>(
        "Region",
        arrayOf(
            "All",
            "India",
            "United States",
            "South Korea",
            "Japan",
            "United Kingdom",
        ),
    )

class YearFilter :
    AnimeFilter.Select<String>(
        "Release Year",
        arrayOf(
            "All",
            "2026",
            "2025",
            "2024",
            "2023",
            "2022",
            "2021",
            "2020",
            "2019",
            "2018",
            "2017",
            "2016",
            "2015",
            "Older",
        ),
    )

class SortFilter :
    AnimeFilter.Select<String>(
        "Sort By",
        arrayOf(
            "Default",
            "Latest",
            "Most Viewed (Heat)",
            "Rating",
        ),
    )

fun getCategoryLocation(category: String): String = when (category) {
    "Popular TV Shows & WebSeries",
    "International Web Series",
    "Top 20 Series This Week",
    "Recommend Series",
    "Korean Drama Hindi dubbed",
    "Best Of Reality-Show",
    "Mystery and Thriller",
    "Most Watched Netflix Series",
    "Marvel series",
    "Anime (TV Shows)",
    "Good Action Series",
    "Erotic Series",
    "Romantic Love",
    "Crime Series",
    "Hilarious Comedy",
    "Long Series",
    "Human and Nature",
    -> "1002"

    "Hit Movies",
    "International Movies",
    "Most Popular Movies for Boys",
    "Tollywood",
    "Hottest Bollywood Actor",
    "Erotic",
    "Blockbuster Movies",
    "Bollywood",
    "Top 50 Most Viewed",
    "Action & Adventure",
    "Comedy",
    "Romance",
    "Crime",
    "Thriller",
    "Most Popular Celebs",
    "X-Men",
    "Indian Regional Movies",
    "Hottest Bollywood Actresses",
    "Fast and Furious · Game and Magic",
    "Classic Collection",
    "cartoon",
    -> "1003"

    "Ongoing Anime",
    "The Most Popular Anime",
    "Must Watch Anime For Solo Leveling Fans!",
    "Top 10 Anime This Week",
    "Sweet love",
    "Adrenaline Rush Anime",
    "Featured Recommendations",
    "Isekai",
    "Fantasy Adventure",
    "Adult Animes",
    "Happy time",
    "Movie Anime",
    -> "1069"

    else -> "1001"
}

fun getCastleTvFilters(): AnimeFilterList = AnimeFilterList(
    SectionFilter(),
    CategoryFilter(),
    GenreFilter(),
    PlatformFilter(),
    LanguageFilter(),
    RegionFilter(),
    YearFilter(),
    SortFilter(),
)

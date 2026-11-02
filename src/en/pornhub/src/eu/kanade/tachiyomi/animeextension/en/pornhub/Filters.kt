package eu.kanade.tachiyomi.animeextension.en.pornhub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object PornhubFilters {

    open class SelectFilter(
        displayName: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        options.map { it.first }.toTypedArray(),
    ) {
        val selectedValue: String
            get() = options[state].second
    }

    class SearchTypeFilter : SelectFilter(
        "Search Type / Target",
        arrayOf(
            "Default (Search Videos)" to "video",
            "User Profile Videos (/users/{name}/videos)" to "user",
            "Model Channel Videos (/model/{name}/videos)" to "model",
            "Pornstar Videos (/pornstar/{name}/videos)" to "pornstar",
            "Channels Videos (/channels/{name}/videos)" to "channel",
        ),
    )

    class UsernameFilter : AnimeFilter.Text("Search by Username / Model / Pornstar")

    // ================= Specific User Filters =================
    class UserSortFilter : SelectFilter(
        "User Sort By (Applies to User/Model/Pornstar)",
        arrayOf(
            "Default" to "",
            "Most Recent" to "mr",
            "Most Viewed" to "mv",
            "Top Rated" to "tr",
            "Longest" to "lg",
        ),
    )

    class UserSectionFilter : SelectFilter(
        "User Video Section",
        arrayOf(
            "Public Videos" to "public",
            "All Uploaded Videos" to "videos",
        ),
    )

    // ================= Global / Video Filters =================
    class SortFilter : SelectFilter(
        "Global Sort By",
        arrayOf(
            "Featured / Default" to "",
            "Most Recent" to "mr",
            "Most Viewed" to "mv",
            "Top Rated" to "tr",
            "Hot / Trending" to "ht",
            "Longest" to "lg",
            "Most Discussed" to "md",
        ),
    )

    class OrientationFilter : SelectFilter(
        "Orientation",
        arrayOf(
            "Straight (Default)" to "",
            "Gay" to "gay",
            "Transgender" to "transgender",
        ),
    )

    class ProductionFilter : SelectFilter(
        "Production",
        arrayOf(
            "All" to "",
            "Professional" to "professional",
            "Homemade / Amateurs" to "homemade",
        ),
    )

    class DurationFilter : SelectFilter(
        "Duration",
        arrayOf(
            "All" to "",
            "1-10 minutes" to "10",
            "10-20 minutes" to "20",
            "20+ minutes" to "20+",
        ),
    )

    class QualityFilter : SelectFilter(
        "Quality Definition",
        arrayOf(
            "All" to "",
            "720p+ HD" to "hd",
            "1080p Full HD" to "1080p",
            "4K Ultra HD" to "4k",
        ),
    )

    class DynamicCategoryFilter(
        categories: List<Pair<String, String>>,
    ) : SelectFilter(
        "Category (Dynamic)",
        categories.toTypedArray(),
    )

    class ReloadCategoriesFilter : AnimeFilter.CheckBox("Reset & Reload Dynamic Categories from Site", false)

    class FilterData(
        val searchType: String = "video",
        val username: String = "",
        val userSort: String = "",
        val userSection: String = "public",
        val sort: String = "",
        val orientation: String = "",
        val production: String = "",
        val duration: String = "",
        val quality: String = "",
        val categoryId: String = "",
        val reloadCategories: Boolean = false,
    )

    fun getFilterData(filters: AnimeFilterList): FilterData {
        var searchType = "video"
        var username = ""
        var userSort = ""
        var userSection = "public"
        var sort = ""
        var orientation = ""
        var production = ""
        var duration = ""
        var quality = ""
        var categoryId = ""
        var reloadCategories = false

        for (filter in filters) {
            when (filter) {
                is SearchTypeFilter -> searchType = filter.selectedValue
                is UsernameFilter -> username = filter.state.trim()
                is UserSortFilter -> userSort = filter.selectedValue
                is UserSectionFilter -> userSection = filter.selectedValue
                is SortFilter -> sort = filter.selectedValue
                is OrientationFilter -> orientation = filter.selectedValue
                is ProductionFilter -> production = filter.selectedValue
                is DurationFilter -> duration = filter.selectedValue
                is QualityFilter -> quality = filter.selectedValue
                is DynamicCategoryFilter -> categoryId = filter.selectedValue
                is ReloadCategoriesFilter -> reloadCategories = filter.state
                else -> {}
            }
        }

        return FilterData(
            searchType = searchType,
            username = username,
            userSort = userSort,
            userSection = userSection,
            sort = sort,
            orientation = orientation,
            production = production,
            duration = duration,
            quality = quality,
            categoryId = categoryId,
            reloadCategories = reloadCategories,
        )
    }

    val DEFAULT_CATEGORIES: List<Pair<String, String>> = listOf(
        "All Categories" to "",
        "Amateur" to "3",
        "Anal" to "35",
        "Asian" to "1",
        "BBW" to "6",
        "Babysitter" to "89",
        "Behind The Scenes" to "141",
        "Big Ass" to "4",
        "Big Dick" to "8",
        "Big Tits" to "7",
        "Blonde" to "9",
        "Blowjob" to "13",
        "Bondage" to "10",
        "Brunette" to "11",
        "Casting" to "90",
        "Celebrity" to "12",
        "College" to "79",
        "Compilation" to "105",
        "Cosplay" to "201",
        "Creampie" to "15",
        "Cumshot" to "16",
        "Double Penetration" to "72",
        "Ebony" to "17",
        "Euro" to "59",
        "Exclusive" to "111",
        "Feet" to "93",
        "Fetish" to "18",
        "Fist" to "94",
        "For Women" to "73",
        "Funny" to "20",
        "Gangbang" to "80",
        "Hardcore" to "21",
        "HD Porn" to "38",
        "Hentai" to "36",
        "Interracial" to "25",
        "Japanese" to "108",
        "Korean" to "109",
        "Lesbian" to "27",
        "Massage" to "78",
        "Masturbation" to "22",
        "Mature" to "28",
        "MILF" to "29",
        "Muscular" to "562",
        "Music" to "205",
        "Old/Young" to "67",
        "Parody" to "84",
        "Party" to "81",
        "Pissing" to "24",
        "Pornstar" to "91",
        "POV" to "41",
        "Public" to "23",
        "Reality" to "43",
        "Redhead" to "42",
        "Roleplay" to "86",
        "Romantic" to "512",
        "Rough Sex" to "69",
        "School" to "88",
        "SFW" to "181",
        "Small Tits" to "53",
        "Solo" to "482",
        "Squirt" to "65",
        "Striptease" to "33",
        "Tattooed" to "542",
        "Teen" to "37",
        "Threesome" to "63",
        "Toys" to "492",
        "Transgender" to "83",
        "Verified Amateurs" to "138",
        "Vintage" to "48",
        "VR" to "103",
        "Webcams" to "61",
    )
}

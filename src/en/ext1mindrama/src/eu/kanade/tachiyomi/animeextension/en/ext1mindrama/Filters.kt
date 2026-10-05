package eu.kanade.tachiyomi.animeextension.en.ext1mindrama

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
        fun isDefault() = state == 0
    }

    class SortFilter : UriPartFilter(
        "Sort By",
        arrayOf(
            Pair("Most Viewed", "views"),
            Pair("Latest Updates", "latest_episode_updated_at"),
            Pair("Newest Added", "created_at"),
            Pair("Weekly Views", "weekly_views"),
            Pair("Monthly Views", "monthly_views"),
            Pair("Rating", "rating"),
        ),
    )

    class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("All", "all"),
            Pair("Completed", "completed"),
            Pair("Ongoing", "ongoing"),
            Pair("Upcoming", "upcoming"),
        ),
    )

    class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("All", "all"),
            Pair("Series", "series"),
            Pair("Movie", "movies"),
        ),
    )

    class LanguageFilter : UriPartFilter(
        "Language",
        arrayOf(
            Pair("English", "en"),
            Pair("All Languages", "all"),
            Pair("Vietnamese", "vi"),
            Pair("Chinese", "zh"),
            Pair("Japanese", "ja"),
            Pair("Korean", "ko"),
        ),
    )

    class GenreFilter : UriPartFilter(
        "Genre / Category",
        arrayOf(
            Pair("All", ""),
            Pair("Drama", "19250050-8982-4a14-a73c-ccb4f1836aca"),
            Pair("Revenge", "17848726-868c-4520-9074-6157033e8593"),
            Pair("Counterattack", "bc5ede28-e607-4c17-bdc5-c2314d943f53"),
            Pair("Romance", "1ebfcd6b-615a-4174-b1a3-4dc6d1ead4b7"),
            Pair("CEO", "5e6c9220-8c2e-4594-bf27-7dace7065ae3"),
            Pair("Urban", "92d8c46a-163e-46bd-b1fa-fdfacc182bc3"),
            Pair("Strong Female Lead", "9a77ad5f-f3aa-4c71-9166-f243d35b4e37"),
            Pair("Modern", "f37365a9-cc5a-4419-b502-82b043868740"),
            Pair("Time Travel", "cee84a3e-f43f-4b7f-9a5a-72f2375e852f"),
            Pair("Fantasy", "41ab8b0c-f4b5-4cd0-9271-fff59b55a8bd"),
            Pair("Hidden Identity", "fbdc05d3-969c-4ec7-8752-24855e6c59fe"),
            Pair("Rebirth", "49da4bf9-79f1-4b72-bd76-e1c270626617"),
            Pair("Modern Romance", "2302bd07-6aa6-451c-acf6-03a1ea18a830"),
            Pair("Underdog Rise", "37e71608-f510-4249-92aa-11170d85d07e"),
            Pair("Male Lead", "713bc050-d04b-4e8b-9910-e67e162677cf"),
            Pair("Zero to Hero", "6102a531-c3d8-4af3-bf36-5b21cd73cade"),
            Pair("System", "1d07f435-1dc3-43e4-814c-33c87a0b7794"),
            Pair("Comeback", "9b93f621-d75c-46fb-b012-38f5c7a2154d"),
            Pair("Karma Payback", "5c70e40e-a32e-4ce6-8133-2333216a62af"),
            Pair("Second Chance", "02543863-fa1f-430b-a65f-1dc5cb9a7b59"),
            Pair("Family", "b79530bd-0bfa-4f88-8f67-5bda0eadd9ce"),
            Pair("Love After Marriage", "bda2c683-d6c9-419c-8f5b-c751010f3fda"),
            Pair("Secret Identity", "be28f164-a941-4b16-a6ce-6ba9e0e04045"),
            Pair("Betrayal", "d2c81775-d5fa-4cb0-82dc-0754077efbb4"),
            Pair("Werewolf", "fd55e785-5de0-4007-9327-509339402a40"),
            Pair("Billionaire", "50725706-9664-4aaf-99af-49031dd7e7e6"),
            Pair("Romantic tension", "51be28fe-9d3c-40aa-9c15-3d91493ff2e5"),
            Pair("High Society", "d1701378-6da9-457a-acad-b321adab3ce6"),
        ),
    )
}

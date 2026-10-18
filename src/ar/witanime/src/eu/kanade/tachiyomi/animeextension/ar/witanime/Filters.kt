package eu.kanade.tachiyomi.animeextension.ar.witanime

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

open class UriPartFilter(
    name: String,
    private val vals: Array<Pair<String, String>>,
    defaultValue: Int = 0,
) : AnimeFilter.Select<String>(name, vals.map { it.first }.toTypedArray(), defaultValue) {
    fun toUriPart() = vals[state].second
}

open class CheckBoxFilterList(
    name: String,
    private val vals: Array<Pair<String, String>>,
) : AnimeFilter.Group<AnimeFilter.CheckBox>(name, vals.map { AnimeFilter.CheckBox(it.first, false) }) {
    fun getSelectedValues(): List<String> = state.mapIndexedNotNull { index, filter ->
        if (filter.state) vals[index].second else null
    }
}

class StatusFilter :
    UriPartFilter(
        "الحالة",
        arrayOf(
            Pair("الكل", ""),
            Pair("يعرض الآن (مستمر)", "ongoing"),
            Pair("مكتمل", "completed"),
            Pair("قادم قريباً", "upcoming"),
        ),
    )

class TypeFilter :
    UriPartFilter(
        "النوع",
        arrayOf(
            Pair("الكل", ""),
            Pair("TV", "TV"),
            Pair("TV Short", "TV Short"),
            Pair("Movie", "Movie"),
            Pair("OVA", "OVA"),
            Pair("ONA", "ONA"),
            Pair("Special", "Special"),
            Pair("Music", "Music"),
        ),
    )

class SortFilter :
    UriPartFilter(
        "الترتيب",
        arrayOf(
            Pair("الافتراضي", ""),
            Pair("الأعلى تقييماً", "Top Rated"),
            Pair("الأكثر مشاهدة", "Most Viewed"),
            Pair("أبجدي (A-Z)", "A-Z"),
            Pair("أبجدي (Z-A)", "Z-A"),
        ),
    )

class SeasonFilter :
    UriPartFilter(
        "الموسم",
        arrayOf(
            Pair("الكل", ""),
            Pair("الربيع (Spring)", "spring"),
            Pair("الصيف (Summer)", "summer"),
            Pair("الخريف (Fall)", "fall"),
            Pair("الشتاء (Winter)", "winter"),
        ),
    )

class YearFilter :
    UriPartFilter(
        "السنة",
        arrayOf(Pair("الكل", "")) + (2026 downTo 1970).map { Pair(it.toString(), it.toString()) }.toTypedArray(),
    )

class GenreFilter :
    CheckBoxFilterList(
        "التصنيفات",
        arrayOf(
            Pair("أساطير", "mythology"),
            Pair("أطفال", "kids"),
            Pair("أكشن", "action"),
            Pair("إيتشي", "ecchi"),
            Pair("إيسكاي", "isekai"),
            Pair("استراتيجي", "strategy-game"),
            Pair("الخارج عن المألوف", "avant-garde"),
            Pair("السفر عبر الزمن", "time-travel"),
            Pair("بقاء", "survival"),
            Pair("بيسبول", "baseball"),
            Pair("تاريخي", "historical"),
            Pair("تحري", "detective"),
            Pair("تشويق", "suspense"),
            Pair("تعليمي", "educational"),
            Pair("تناسخ", "reincarnation"),
            Pair("تنس", "tennis"),
            Pair("جوسيه", "josei"),
            Pair("حريم", "harem"),
            Pair("حريم عكسي", "reverse-harem"),
            Pair("حيوانات أليفة", "pets"),
            Pair("خارق للطبيعة", "supernatural"),
            Pair("خيال", "fantasy"),
            Pair("خيال علمي", "sci-fi"),
            Pair("دراما", "drama"),
            Pair("دموي", "gore"),
            Pair("رعب", "horror"),
            Pair("رماية بالقوس", "archery"),
            Pair("رومانسي", "romance"),
            Pair("رياضات قتالية", "combat-sports"),
            Pair("رياضة السيارات", "racing"),
            Pair("رياضي", "sports"),
            Pair("زومبي", "zombies"),
            Pair("ساخر", "parody"),
            Pair("ساموراي", "samurai"),
            Pair("سحر", "magic"),
            Pair("سينين", "seinen"),
            Pair("شريحة من الحياة", "slice-of-life"),
            Pair("شوجو", "shoujo"),
            Pair("شونين", "shounen"),
            Pair("طبي", "medical"),
            Pair("طعام راقي", "gourmet"),
            Pair("عسكري", "military"),
            Pair("غموض", "mystery"),
            Pair("فتيات سحرية", "magical-sex"),
            Pair("فضاء", "space"),
            Pair("فنون الأداء", "performing-arts"),
            Pair("فنون بصرية", "visual-arts"),
            Pair("فنون قتالية", "martial-arts"),
            Pair("قطط", "cats"),
            Pair("قوى خارقة", "super-power"),
            Pair("كرة الريشة", "badminton"),
            Pair("كرة السلة", "basketball"),
            Pair("كرة الطائرة", "volleyball"),
            Pair("كرة قدم", "soccer"),
            Pair("كوميديا", "comedy"),
            Pair("لعبة فيديو", "video-game"),
            Pair("ما بعد نهاية العالم", "post-apocalyptic"),
            Pair("مدرسي", "school"),
            Pair("مصاصي الدماء", "vampire"),
            Pair("مغامرة", "adventure"),
            Pair("مهني", "workplace"),
            Pair("موسيقى", "music"),
            Pair("ميكا", "mecha"),
            Pair("نفسي", "psychological"),
        ),
    )

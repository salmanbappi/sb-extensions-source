package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    fun getUrl(baseUrl: String, serverPath: String, filters: AnimeFilterList): String {
        val fullBaseUrl = "$baseUrl/$serverPath"

        val categoryFilter = filters[1] as DhakaFlixSelect
        val yearFilter = filters[2] as DhakaFlixSelect
        val alphabetFilter = filters[3] as DhakaFlixSelect
        val langFilter = filters.getOrNull(4) as? DhakaFlixSelect

        val category = categoryFilter.values[categoryFilter.state]
        val year = yearFilter.values[yearFilter.state]
        val alphabet = alphabetFilter.values[alphabetFilter.state]
        val lang = langFilter?.let { it.values[it.state] } ?: "Any"

        var path = category

        when (category) {
            "Hindi Movies" -> path = "Hindi Movies"

            "English Movies" -> path = "English Movies"

            "English Movies (1080p)" -> path = "English Movies (1080p)"

            "South Indian Movies" -> path = "SOUTH INDIAN MOVIES/South Movies"

            "South Hindi Dubbed" -> path = "SOUTH INDIAN MOVIES/Hindi Dubbed"

            "Kolkata Bangla Movies" -> path = "Kolkata Bangla Movies"

            "Animation Movies" -> path = "Animation Movies"

            "Foreign Language Movies" -> {
                path = "Foreign Language Movies"
                if (lang != "Any") return "$fullBaseUrl/$path/$lang/"
            }

            "TV Series" -> {
                if (alphabet != "Any") {
                    val subPath = when (alphabet) {
                        "0-9" -> "TV Series \u2605  0  \u2014  9"
                        "A-F / A-L" -> "TV Series \u2665  A  \u2014  L"
                        "G-M / M-R" -> "TV Series \u2666  M  \u2014  R"
                        "N-S / S-Z" -> "TV Series \u2666  S  \u2014  Z"
                        else -> "TV Series \u2665  A  \u2014  L"
                    }
                    return "$fullBaseUrl/TV-WEB-Series/$subPath/"
                }
                path = "TV-WEB-Series"
            }

            "Korean TV & Web Series" -> path = "KOREAN TV & WEB Series"

            "Anime-TV Series" -> {
                if (alphabet != "Any") {
                    val subPath = when (alphabet) {
                        "0-9" -> "Anime-TV Series \u2605  0  \u2014  9"
                        "G-M / M-R" -> "Anime-TV Series \u2665  G  \u2014  M"
                        "N-S / S-Z" -> "Anime-TV Series \u2666  N  \u2014  S"
                        "T-Z" -> "Anime-TV Series \u2666  T  \u2014  Z"
                        else -> "Anime-TV Series \u2665  A  \u2014  F"
                    }
                    return "$fullBaseUrl/Anime & Cartoon TV Series/$subPath/"
                }
                path = "Anime & Cartoon TV Series"
            }

            "Documentary" -> path = "Documentary"

            "WWE & AEW Wrestling" -> path = "WWE & AEW Wrestling"

            "Awards & TV Shows" -> path = "Awards & TV Shows"

            "IMDb Top-250 Movies" -> path = "IMDb Top-250 Movies"

            "3D Movies" -> path = "3D Movies"

            "Trending Movies" -> {
                path = "Hindi Movies/(2026)"
                return "$fullBaseUrl/$path/"
            }
        }

        if (alphabet != "Any" && !category.contains("Series")) {
            val alphaPath = when (alphabet) {
                "0-9" -> "0-9"
                "A-F / A-L" -> "A-F"
                "G-M / M-R" -> "G-M"
                "N-S / S-Z" -> "N-S"
                "T-Z" -> "T-Z"
                else -> alphabet
            }
            return "$fullBaseUrl/$path/$alphaPath/"
        }

        if (year != "Any") {
            val yearPath = when {
                category == "English Movies (1080p)" -> {
                    if (year == "(2009) & Before") {
                        "%282009%29%20%26%20Before"
                    } else {
                        "%28$year%29%201080p"
                    }
                }

                category == "South Indian Movies" || category == "South Hindi Dubbed" -> {
                    if (year == "(2009) & Before") "2000 & Before" else year
                }

                else -> "($year)"
            }
            return "$fullBaseUrl/$path/$yearPath/"
        }

        return "$fullBaseUrl/$path/"
    }
}

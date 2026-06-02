package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class DhakaFlixFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        DhakaFlix2(
            "DhakaFlix (Hindi & South Indian)",
            "http://172.16.50.14",
            5181466391484419941L,
            "DHAKA-FLIX-14",
            arrayOf("Hindi Movies", "English Movies (1080p)", "South Indian Movies", "South Hindi Dubbed", "Animation Movies", "Korean TV & Web Series", "IMDb Top-250 Movies", "Trending Movies")
        ),
        DhakaFlix2(
            "DhakaFlix (TV & Web Series)",
            "http://172.16.50.12",
            5181466391484419942L,
            "DHAKA-FLIX-12",
            arrayOf("TV Series", "Hindi Movies")
        ),
        DhakaFlix2(
            "DhakaFlix (Anime & Documentary)",
            "http://172.16.50.9",
            5181466391484419943L,
            "DHAKA-FLIX-9",
            arrayOf("Anime-TV Series", "Documentary", "WWE & AEW Wrestling", "Awards & TV Shows")
        ),
        DhakaFlix2(
            "DhakaFlix (English & International)",
            "http://172.16.50.7",
            5181466391484419944L,
            "DHAKA-FLIX-7",
            arrayOf("English Movies", "Kolkata Bangla Movies", "Foreign Language Movies", "3D Movies")
        )
    )
}

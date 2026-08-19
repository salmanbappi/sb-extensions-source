package eu.kanade.tachiyomi.animeextension.en.mkissa

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PopularResult(
    val data: PopularResultData? = null
) {
    @Serializable
    data class PopularResultData(
    val queryPopular: QueryPopularData? = null
) {
        @Serializable
        data class QueryPopularData(
    val recommendations: List<Recommendation>? = null
) {
            @Serializable
            data class Recommendation(
    val anyCard: Card? = null
) {
                @Serializable
                data class Card(
    @SerialName("_id")
                    val id: String? = null,
    val name: String? = null,
    val thumbnail: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val slugTime: String? = null
)
            }
        }
    }
}

@Serializable
data class SearchResult(
    val data: SearchResultData? = null
) {
    @Serializable
    data class SearchResultData(
    val shows: SearchResultShows? = null
) {
        @Serializable
        data class SearchResultShows(
    val edges: List<SearchResultEdge>? = null
) {
            @Serializable
            data class SearchResultEdge(
    @SerialName("_id")
                val id: String? = null,
    val name: String? = null,
    val thumbnail: String? = null,
    val englishName: String? = null,
    val nativeName: String? = null,
    val slugTime: String? = null
)
        }
    }
}

@Serializable
data class DetailsResult(
    val data: DataShow? = null
) {
    @Serializable
    data class DataShow(
    val show: SeriesShows? = null
) {
        @Serializable
        data class SeriesShows(
    val thumbnail: String? = null,
    val genres: List<String>? = null,
    val studios: List<String>? = null,
    val season: AirSeason? = null,
    val status: String? = null,
    val score: Float? = null,
    val type: String? = null,
    val description: String? = null
) {
            @Serializable
            data class AirSeason(
    val quarter: String? = null,
    val year: Int? = null
)
        }
    }
}

@Serializable
data class SeriesResult(
    val data: DataShow? = null
) {
    @Serializable
    data class DataShow(
    val show: SeriesShows? = null
) {
        @Serializable
        data class SeriesShows(
    @SerialName("_id")
            val id: String? = null,
    val availableEpisodesDetail: AvailableEps? = null
) {
            @Serializable
            data class AvailableEps(
    val sub: List<String>? = null,
    val dub: List<String>? = null,
    val raw: List<String>? = null
)
        }
    }
}

@Serializable
data class EpisodeResult(
    val data: DataEpisode? = null
) {
    @Serializable
    data class DataEpisode(
    val episode: Episode? = null
) {
        @Serializable
        data class Episode(
    val sourceUrls: List<SourceUrl>? = null
) {
            @Serializable
            data class SourceUrl(
    val sourceUrl: String? = null,
    val type: String? = null,
    val sourceName: String? = null,
    val priority: Float = 0F
)
        }
    }
}

@Serializable
data class EncryptedEpisodeResult(
    val data: EncryptedData? = null
) {
    @Serializable
    data class EncryptedData(
    val tobeparsed: String? = null
)
}

@Serializable
data class DecryptedEpisodeResult(
    val episode: EpisodeResult.DataEpisode.Episode? = null
)

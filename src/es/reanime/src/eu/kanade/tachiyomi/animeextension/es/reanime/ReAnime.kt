package eu.kanade.tachiyomi.animeextension.es.reanime

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import androidx.preference.PreferenceScreen
import extensions.utils.Source
import extensions.utils.parseAs
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.parallelCatchingFlatMapBlocking
import kotlin.getValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SearchResponseDto(
    val results: List<AnimeDto> = emptyList()
)

@Serializable
data class AnimeDto(
    val anime_id: String,
    val title: TitleDto,
    val cover_image: CoverImageDto
)

@Serializable
data class TitleDto(
    val user_preferred: String? = null,
    val english: String? = null,
    val romaji: String? = null,
    val native: String? = null
)

@Serializable
data class CoverImageDto(
    val large: String? = null
)

@Serializable
data class FlixResponseDto(
    val success: Boolean,
    val servers: List<FlixServerDto> = emptyList()
)

@Serializable
data class FlixServerDto(
    val serverName: String,
    val dataLink: String,
    val dataType: String
)

class ReAnime : Source() {

    override val name = "ReAnime"

    override val baseUrl = "https://reanime.to"

    override val lang = "es"

    override val supportsLatest = true

    private val playlistUtils by lazy {
        PlaylistUtils(client, headers)
    }

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val offset = (page - 1) * 20
        return GET("$baseUrl/api/v1/search?offset=$offset", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val data = response.parseAs<SearchResponseDto>()
        val animes = data.results.map {
            SAnime.create().apply {
                url = it.anime_id
                title = it.title.english ?: it.title.user_preferred ?: it.title.romaji ?: ""
                thumbnail_url = it.cover_image.large
            }
        }
        return AnimesPage(animes, hasNextPage = animes.size == 20)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val html = response.body.string()
        val latestAiredStr = extractArray(html, "latestAired:")
        if (latestAiredStr.length <= 2) {
            return AnimesPage(emptyList(), hasNextPage = false)
        }

        val inner = latestAiredStr.substring(1, latestAiredStr.length - 1)
        val items = inner.split(Regex("""\}\s*,\s*\{"""))
        val animes = items.mapNotNull { item ->
            val cleanItem = if (item.startsWith("{")) item else "{$item"
            val finalItem = if (cleanItem.endsWith("}")) cleanItem else "$cleanItem}"

            val id = Regex("""anime_id\s*:\s*"([^"]+)"""").find(finalItem)?.groupValues?.get(1) ?: return@mapNotNull null
            val titleStr = Regex("""english\s*:\s*"([^"]+)"""").find(finalItem)?.groupValues?.get(1)
                ?: Regex("""user_preferred\s*:\s*"([^"]+)"""").find(finalItem)?.groupValues?.get(1)
                ?: Regex("""romaji\s*:\s*"([^"]+)"""").find(finalItem)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val coverUrl = Regex("""large\s*:\s*"([^"]+)"""").find(finalItem)?.groupValues?.get(1)

            SAnime.create().apply {
                url = id
                title = titleStr
                thumbnail_url = coverUrl
            }
        }
        return AnimesPage(animes, hasNextPage = false)
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val offset = (page - 1) * 20
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/api/v1/search?q=$encodedQuery&offset=$offset", headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/watch/${anime.url}?ep=1", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.body.string()
        val match = Regex("""anime\s*:\s*\{""").find(html) ?: return SAnime.create()
        val objStartIndex = html.indexOf("{", match.range.first)
        if (objStartIndex == -1) return SAnime.create()

        var braceCount = 0
        var currentIndex = objStartIndex
        var animeObjStr = ""
        while (currentIndex < html.length) {
            val char = html[currentIndex]
            if (char == '{') {
                braceCount++
            } else if (char == '}') {
                braceCount--
                if (braceCount == 0) {
                    animeObjStr = html.substring(objStartIndex, currentIndex + 1)
                    break
                }
            }
            currentIndex++
        }

        val rawDesc = Regex("""description\s*:\s*"([^"]+)"""").find(animeObjStr)?.groupValues?.get(1) ?: ""
        val desc = unescapeUnicode(rawDesc)
            .replace("\\n", "\n")
            .replace(Regex("<[^>]*>"), "")

        val statusStr = Regex("""status\s*:\s*"([^"]+)"""").find(animeObjStr)?.groupValues?.get(1)
        val coverUrl = Regex("""large\s*:\s*"([^"]+)"""").find(animeObjStr)?.groupValues?.get(1)

        val genresList = mutableListOf<String>()
        val genresMatch = Regex("""genres\s*:\s*\[""").find(animeObjStr)
        if (genresMatch != null) {
            val arrayStartIndex = animeObjStr.indexOf("[", genresMatch.range.first)
            if (arrayStartIndex != -1) {
                var bracketCount = 0
                var currIdx = arrayStartIndex
                var genresStr = ""
                while (currIdx < animeObjStr.length) {
                    val char = animeObjStr[currIdx]
                    if (char == '[') {
                        bracketCount++
                    } else if (char == ']') {
                        bracketCount--
                        if (bracketCount == 0) {
                            genresStr = animeObjStr.substring(arrayStartIndex, currIdx + 1)
                            break
                        }
                    }
                    currIdx++
                }
                if (genresStr.length > 2) {
                    val inner = genresStr.substring(1, genresStr.length - 1)
                    inner.split(",").forEach {
                        val g = it.replace("\"", "").trim()
                        if (g.isNotEmpty()) genresList.add(g)
                    }
                }
            }
        }

        return SAnime.create().apply {
            description = desc
            status = when (statusStr?.lowercase()) {
                "finished" -> SAnime.COMPLETED
                "releasing" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
            genre = genresList.joinToString(", ")
            thumbnail_url = coverUrl
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/watch/${anime.url}?ep=1", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = response.body.string()
        val match = Regex("""episodes\s*:\s*\[\s*\{""").find(html) ?: return emptyList()
        val arrayStartIndex = html.indexOf("[", match.range.first)
        if (arrayStartIndex == -1) return emptyList()

        var bracketCount = 0
        var currentIndex = arrayStartIndex
        var episodesStr = ""
        while (currentIndex < html.length) {
            val char = html[currentIndex]
            if (char == '[') {
                bracketCount++
            } else if (char == ']') {
                bracketCount--
                if (bracketCount == 0) {
                    episodesStr = html.substring(arrayStartIndex, currentIndex + 1)
                    break
                }
            }
            currentIndex++
        }

        if (episodesStr.length <= 2) {
            return emptyList()
        }

        val slug = response.request.url.pathSegments.lastOrNull { it.isNotEmpty() && it != "watch" } ?: ""

        val inner = episodesStr.substring(1, episodesStr.length - 1)
        val items = inner.split(Regex("""\}\s*,\s*\{"""))
        val showThumbnails = preferences.getBoolean(PREF_SHOW_THUMBNAILS_KEY, true)

        return items.mapNotNull { item ->
            val cleanItem = if (item.startsWith("{")) item else "{$item"
            val finalItem = if (cleanItem.endsWith("}")) cleanItem else "$cleanItem}"

            val epNumStr = Regex("""episode_number\s*:\s*(\d+(\.\d+)?)""").find(finalItem)?.groupValues?.get(1) ?: return@mapNotNull null
            val epNum = epNumStr.toFloat()
            val epTitle = Regex("""title\s*:\s*"([^"]+)"""").find(finalItem)?.groupValues?.get(1) ?: "Episodio $epNumStr"

            val epDesc = Regex("""description\s*:\s*"([^"]*)"""").find(finalItem)?.groupValues?.get(1)
                ?.let { unescapeUnicode(it) }
                ?.replace("\\n", "\n")
                ?.takeIf { it.isNotEmpty() }

            val epThumbnail = if (showThumbnails) {
                Regex("""thumbnail\s*:\s*"([^"]*)"""").find(finalItem)?.groupValues?.get(1)
                    ?.takeIf { it.isNotEmpty() }
            } else null

            SEpisode.create().apply {
                url = "$slug?ep=$epNumStr"
                name = epTitle
                episode_number = epNum
                summary = epDesc
                preview_url = epThumbnail
            }
        }.reversed() // Ascending order
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        // url of episode is "episode_number", we need the anime_id to fetch the watch page
        // Wait, the episode list request url is "watch/{anime_id}?ep=1", but Tachiyomi
        // passes episode.url directly. In AniDB.kt or other extensions, the url of episode
        // includes the slug and ep number. Let's make sure the episode url includes the slug!
        // Wait! In episodeListParse above, the SEpisode.url we returned was just epNumStr.
        // If we only returned epNumStr, how do we get the anime_id?
        // Ah! In Tachiyomi, we can get the anime details or the slug from the URL.
        // Wait, we can return URL as "{anime_id}/ep/${episode_number}" or similar!
        // But wait! If we do:
        // SEpisode.url = "${anime.url}?ep=$epNumStr"
        // Then episode.url will contain the slug and episode number!
        // Let's modify episodeListParse to do this!
        // Yes, the episode.url will be:
        // url = "${anime.url}?ep=$epNumStr"
        // Wait, the parameter passed to episodeListParse is `response`, which has the watch page URL!
        // The watch page URL is like `https://reanime.to/watch/slug?ep=1`.
        // So we can extract the slug from `response.request.url.toString()`!
        // Yes!
        // val slug = response.request.url.pathSegments.last() // which is "slug"
        // This is extremely simple and elegant!
        val requestUrl = episode.url
        val slug = requestUrl.substringBefore("?ep=")
        val epNumStr = requestUrl.substringAfter("?ep=")

        // Fetch watch page to get the anilist_id
        val watchPageRequest = GET("$baseUrl/watch/$slug?ep=$epNumStr", headers)
        val watchPageResponse = client.newCall(watchPageRequest).execute()
        val watchPageHtml = watchPageResponse.body.string()

        val anilistId = Regex("""anilist_id:(\d+)""").find(watchPageHtml)?.groupValues?.get(1)
            ?: throw Exception("Could not find anilist_id")

        // Fetch Flix API
        val flixRequest = GET("$baseUrl/api/flix/$anilistId/$epNumStr", headersBuilder().set("Referer", "$baseUrl/watch/$slug?ep=$epNumStr").build())
        val flixResponse = client.newCall(flixRequest).execute()
        val flixData = flixResponse.parseAs<FlixResponseDto>()

        return flixData.servers.parallelCatchingFlatMapBlocking { server ->
            val embedReferer = "$baseUrl/watch/$slug?ep=$epNumStr"
            val embedRequest = GET(server.dataLink, headersBuilder().set("Referer", embedReferer).build())
            val embedResponse = client.newCall(embedRequest).execute()
            val embedHtml = embedResponse.body.string()

            val seed = Regex("""obfuscation_seed\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()
            val wPayload = Regex("""w_payload\s*:\s*"([^"]+)"""").find(embedHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            val mappings = resolveMappings(seed)

            val tokenRegex = Regex(""""?${mappings.tokenField}"?\s*:\s*"([^"]+)"""")
            val w = tokenRegex.find(embedHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            val frag2Regex = Regex(""""?${mappings.keyFrag2Field}"?\s*:\s*"([^"]+)"""")
            val frag2B64 = frag2Regex.find(embedHtml)?.groupValues?.get(1)
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            // Fetch session token
            val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$w"
            val tokenHeaders = Headers.Builder()
                .add("Referer", server.dataLink)
                .add("Origin", "https://flixcloud.cc")
                .add("User-Agent", headers["User-Agent"]!!)
                .build()
            val tokenResponse = client.newCall(GET(m3u8ApiUrl, tokenHeaders)).execute()
            val tokenBody = tokenResponse.body.string()

            val kField = getSha256(w + "vid").substring(0, 10)
            val pField = getSha256(w + "key").substring(0, 10)

            val jsonObject = json.parseToJsonElement(tokenBody).jsonObject
            val v = jsonObject[kField]?.jsonPrimitive?.content
                ?: return@parallelCatchingFlatMapBlocking emptyList()
            val T = jsonObject[pField]?.jsonPrimitive?.content
                ?: return@parallelCatchingFlatMapBlocking emptyList()

            // Dynamic keys resolution via container parsing
            val pattern = """"?${mappings.containerName}"?\s*:\s*\{\s*"?${mappings.arrayName}"?\s*:\s*\[\s*\{\s*"?${mappings.objectName}"?\s*:\s*\{\s*"?${mappings.keyField}"?\s*:\s*"([^"]+)"\s*,\s*"?${mappings.ivField}"?\s*:\s*"([^"]+)""""
            val match = Regex(pattern).find(embedHtml)
                ?: return@parallelCatchingFlatMapBlocking emptyList()
            val frag1B64 = match.groupValues[1]
            val ivB64 = match.groupValues[2]

            // Decryption
            val frag1 = Base64.decode(frag1B64, Base64.DEFAULT)
            val frag2 = Base64.decode(frag2B64, Base64.DEFAULT)
            val keyPart = Base64.decode(T, Base64.DEFAULT)
            val seedInt = seed.substring(0, 8).toLong(16).toInt()

            val wasmBytes = Base64.decode(wPayload, Base64.DEFAULT)
            val interpreter = MiniWasmInterpreter(wasmBytes)
            val funcs = interpreter.parseWasm()
            val derivedBaseKey = interpreter.executeWasm(funcs, frag1, frag2, keyPart, seedInt)

            val salt = seed.toByteArray(Charsets.UTF_8)
            val pbkdf2Key = pbkdf2(derivedBaseKey, salt, 1000)

            val finalKey = ByteArray(32)
            for (idx in 0 until 32) {
                finalKey[idx] = (pbkdf2Key[idx].toInt() xor seed[idx % seed.length].code).toByte()
            }

            val aesKey = getSha256Bytes(finalKey)
            val iv = Base64.decode(ivB64, Base64.DEFAULT)
            val ciphertext = Base64.decode(v, Base64.DEFAULT)

            val decryptedUrl = decryptAes(ciphertext, aesKey, iv)

            playlistUtils.extractFromHls(
                playlistUrl = decryptedUrl,
                referer = "https://flixcloud.cc/",
                masterHeaders = headers,
                videoHeaders = headers,
                videoNameGen = { quality -> "${server.serverName} (${server.dataType}) - $quality" }
            )
        }
    }

    // ============================ Utilities ===============================

    private fun extractArray(html: String, key: String): String {
        val startIndex = html.indexOf(key)
        if (startIndex == -1) return ""
        val arrayStartIndex = html.indexOf("[", startIndex)
        if (arrayStartIndex == -1) return ""

        var bracketCount = 0
        var currentIndex = arrayStartIndex
        while (currentIndex < html.length) {
            val char = html[currentIndex]
            if (char == '[') {
                bracketCount++
            } else if (char == ']') {
                bracketCount--
                if (bracketCount == 0) {
                    return html.substring(arrayStartIndex, currentIndex + 1)
                }
            }
            currentIndex++
        }
        return ""
    }

    private fun extractObject(html: String, key: String): String {
        val startIndex = html.indexOf(key)
        if (startIndex == -1) return ""
        val objStartIndex = html.indexOf("{", startIndex)
        if (objStartIndex == -1) return ""

        var braceCount = 0
        var currentIndex = objStartIndex
        while (currentIndex < html.length) {
            val char = html[currentIndex]
            if (char == '{') {
                braceCount++
            } else if (char == '}') {
                braceCount--
                if (braceCount == 0) {
                    return html.substring(objStartIndex, currentIndex + 1)
                }
            }
            currentIndex++
        }
        return ""
    }

    private fun getSha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getSha256Bytes(data: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
    }

    private data class Mappings(
        val videoField: String,
        val keyField: String,
        val ivField: String,
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val tokenField: String,
        val keyFrag2Field: String
    )

    private fun resolveMappings(seed: String): Mappings {
        var e = seed
        for (o in 0 until 3) e = getSha256(e + o.toString())
        var s = e
        for (o in 0 until 3) s = getSha256(s + o.toString())

        return Mappings(
            videoField = "vf_${e.substring(0, 8)}",
            keyField = "kf_${e.substring(8, 16)}",
            ivField = "ivf_${e.substring(16, 24)}",
            containerName = "cd_${e.substring(24, 32)}",
            arrayName = "ad_${e.substring(32, 40)}",
            objectName = "od_${e.substring(40, 48)}",
            tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}",
            keyFrag2Field = "${s.substring(0, 16)}_${s.substring(16, 24)}"
        )
    }

    private fun hmacSHA256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key, "HmacSHA256")
        mac.init(secretKey)
        return mac.doFinal(data)
    }

    private fun pbkdf2(key: ByteArray, salt: ByteArray, iterations: Int): ByteArray {
        val input = ByteArray(salt.size + 4)
        System.arraycopy(salt, 0, input, 0, salt.size)
        input[input.size - 1] = 1

        var u = hmacSHA256(key, input)
        val result = u.clone()
        for (i in 2..iterations) {
            u = hmacSHA256(key, u)
            for (j in result.indices) {
                result[j] = (result[j].toInt() xor u[j].toInt()).toByte()
            }
        }
        return result
    }

    private fun decryptAes(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted, Charsets.UTF_8)
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val order = listOf("1080p", "720p", "480p", "360p")
        return this.sortedWith(
            compareBy(
                { !it.videoTitle.contains(quality) },
                { video ->
                    val index = order.indexOfFirst { video.videoTitle.contains(it) }
                    if (index == -1) order.size else index
                }
            )
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = PREF_QUALITY_TITLE,
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_ENTRIES,
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
        screen.addSwitchPreference(
            key = PREF_SHOW_THUMBNAILS_KEY,
            title = PREF_SHOW_THUMBNAILS_TITLE,
            summary = PREF_SHOW_THUMBNAILS_SUMMARY,
            default = true,
        )
    }

    private fun unescapeUnicode(str: String): String {
        val regex = Regex("""\\u([0-9a-fA-F]{4})""")
        return regex.replace(str) { match ->
            val charCode = match.groupValues[1].toInt(16)
            charCode.toChar().toString()
        }.replace("\\\"", "\"")
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Calidad preferida"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p")

        private const val PREF_SHOW_THUMBNAILS_KEY = "pref_show_thumbnails"
        private const val PREF_SHOW_THUMBNAILS_TITLE = "Show episode thumbnails"
        private const val PREF_SHOW_THUMBNAILS_SUMMARY = "Fetch and display images in the episode list."
    }
}

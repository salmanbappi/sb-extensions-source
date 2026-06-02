package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.model.SAnime
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.util.regex.Pattern

object Server7Parser {
    private val pattern = Pattern.compile("""\"href\":\"([^\"]+)\"[^}]*\"size\":(null|\d+)""", Pattern.CASE_INSENSITIVE)

    fun parseServer7Response(bodyString: String, serverUrl: String, serverName: String, results: MutableList<SAnime>, query: String = "") {
        val hostUrl = serverUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return
        val matcher = pattern.matcher(bodyString)

        while (matcher.find()) {
            var href = matcher.group(1).replace('\\', '/').trim()
            href = href.replace(Regex("/+"), "/")

            val size = matcher.group(2)
            val isFolder = size == "null"

            var cleanHrefForTitle = href
            while (cleanHrefForTitle.endsWith("/")) {
                cleanHrefForTitle = cleanHrefForTitle.dropLast(1)
            }

            val rawTitle = cleanHrefForTitle.substringAfterLast("/")
            val title = try {
                URLDecoder.decode(rawTitle, "UTF-8").trim()
            } catch (e: Exception) {
                rawTitle.trim()
            }

            // For Server 7, we prioritize folders as they represent the movie/series entry
            if (title.isEmpty() || isIgnored(title, query)) continue

            // If it's a file, we only add it if we don't have a folder with the same name logic
            if (!isFolder && results.any { it.url.contains(cleanHrefForTitle) }) continue

            val anime = SAnime.create().apply {
                this.title = title
                val finalHref = if (href.endsWith("/") || isFolder) (if (href.endsWith("/")) href else "$href/") else href
                this.url = "$hostUrl$finalHref"
                // Only set thumbnail for folders, files will use TMDb enrichment if enabled
                this.thumbnail_url = if (this.url.endsWith("/")) (this.url + "a11.jpg").replace(" ", "%20").replace("&", "%26") else ""
            }

            synchronized(results) {
                if (results.none { it.url == anime.url }) {
                    if (isFolder) {
                        results.add(0, anime)
                    } else {
                        results.add(anime)
                    }
                }
            }
        }
    }

    private fun isIgnored(text: String, query: String = ""): Boolean {
        val ignored = listOf("Parent Directory", "modern browsers", "Name", "Last modified", "Size", "Description", "Index of", "JavaScript", "powered by", "_h5ai")
        if (ignored.any { text.contains(it, ignoreCase = true) }) return true

        if (query.isEmpty()) return false

        val uploaderTags = listOf("-LOKI", "-LOKiHD", "-TDoc", "-Tuna", "-PSA", "-Pahe", "-QxR", "-YIFY", "-RARBG")
        if (uploaderTags.any { text.endsWith(it, ignoreCase = true) || text.contains("$it.") || text.contains("$it ") }) {
            if (query.isNotEmpty() && uploaderTags.any { it.substring(1).equals(query, ignoreCase = true) }) {
                return false
            }
            return true
        }
        return false
    }
}

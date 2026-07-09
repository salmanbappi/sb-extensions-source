package eu.kanade.tachiyomi.multisrc.anikototheme

import android.util.Log
import java.net.URLEncoder

/**
 * Smart Search module — AI-powered anime search via Google AI Search.
 */
class SmartSearch(
    private val webViewFetcher: WebViewFetcher,
) {
    private val tag = "SmartSearch"

    /** Cache for pagination: last query (phrase stripped) → resolved title. */
    private var cachedQuery: String = ""
    private var cachedTitle: String = ""

    /**
     * Check if smart search should trigger for this query.
     */
    fun shouldTrigger(query: String, enabled: Boolean, phrase: String): Boolean {
        if (!enabled) return false
        val queryTrimmed = query.trim()
        if (queryTrimmed.isEmpty()) return false

        val phraseTrimmed = phrase.trim()
        if (phraseTrimmed.isEmpty()) return true // empty phrase = all searches use AI

        if (!queryTrimmed.startsWith(phraseTrimmed, ignoreCase = true)) return false

        val afterPhrase = queryTrimmed.substring(phraseTrimmed.length)
        return afterPhrase.isEmpty() || afterPhrase.startsWith(" ")
    }

    /**
     * Strip the activation phrase from the start of the query.
     */
    fun stripPhrase(query: String, phrase: String): String {
        val phraseTrimmed = phrase.trim()
        if (phraseTrimmed.isEmpty()) return query.trim()

        val queryTrimmed = query.trim()
        if (queryTrimmed.startsWith(phraseTrimmed, ignoreCase = true)) {
            val afterPhrase = queryTrimmed.substring(phraseTrimmed.length)
            return afterPhrase.trim()
        }
        return queryTrimmed
    }

    /**
     * Resolve a query to an anime title via Google AI Search.
     */
    fun resolve(query: String): String? {
        if (query.isBlank()) {
            Log.w(tag, "SmartSearch: empty query")
            return null
        }

        Log.i(tag, "SmartSearch: resolving query: \"$query\"")

        val searchQuery = buildPrompt(query)
        val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
        val googleUrl = "https://www.google.com/search?q=$encodedQuery&udm=50&hl=en"
        Log.d(tag, "SmartSearch: Google URL: $googleUrl")

        val renderedText = try {
            webViewFetcher.fetchRenderedText(googleUrl, timeoutMs = 20_000)
        } catch (e: Exception) {
            Log.e(tag, "SmartSearch: scrape failed", e)
            return null
        }

        if (renderedText.isBlank()) {
            Log.w(tag, "SmartSearch: Google scrape returned empty text")
            return null
        }

        Log.d(tag, "SmartSearch: Google rendered text (${renderedText.length} chars)")

        val title = extractAnimeTitle(renderedText)
        if (title == null) {
            Log.w(tag, "SmartSearch: could not extract title from Google AI text")
            return null
        }

        Log.i(tag, "SmartSearch: extracted title: \"$title\"")
        return title
    }

    private fun buildPrompt(query: String): String = "$query anime. " +
        "[Respond with only the English anime title, nothing else. " +
        "If the query describes an anime, give the title of the anime being described. " +
        "If the query has spelling mistakes, correct them and give the proper title. " +
        "If the query mentions a genre or theme, give one popular anime from that genre. " +
        "If the query is vague, give the most likely anime match. " +
        "Always respond with exactly one anime title, no explanations, no lists.]"

    private fun extractAnimeTitle(text: String): String? {
        // Strategy 1: "is titled [X]"
        val titledPattern = Regex(
            """(?:is\s+titled|is\s+called|is\s+named|is\s+known\s+as)\s+([A-Z][^\n.!?]{2,80}?)(?:\s*[.\n!?]|$)""",
        )
        for (match in titledPattern.findAll(text)) {
            val raw = match.groupValues[1].trim()
            val title = stripParenthetical(raw)
            val wordCount = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            Log.d(tag, "SmartSearch: strategy1 match: \"$raw\" → \"$title\" ($wordCount words)")
            if (wordCount in 2..12) return title
        }

        // Strategy 2: Quoted text
        val quotedPattern = Regex("""["“'‘]([^"”'’]{2,80})["”'’]""")
        for (match in quotedPattern.findAll(text)) {
            val raw = match.groupValues[1].trim()
            if (raw.contains("(") && raw.endsWith(")")) {
                Log.d(tag, "SmartSearch: strategy2 skip (parenthetical): \"$raw\"")
                continue
            }
            val title = stripParenthetical(raw)
            val wordCount = title.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            Log.d(tag, "SmartSearch: strategy2 match: \"$raw\" → \"$title\" ($wordCount words)")
            if (wordCount in 2..12) return title
        }

        // Strategy 3: First capitalized multi-word phrase after "Search Results"
        val lines = text.lines()
        var inResults = false
        val uiWords = setOf(
            "Sign", "AI", "All", "Images", "Videos", "News", "Books", "Finance",
            "Search", "Results", "Mode", "sites", "Learn",
        )

        for (line in lines) {
            if ("Search Results" in line || "AI Overview" in line) {
                inResults = true
                continue
            }
            if (!inResults) continue
            val trimmed = line.trim()
            if (trimmed.length < 5) continue
            if ("Respond with only" in trimmed || "anime." in trimmed.lowercase()) continue

            val words = trimmed.split(Regex("\\s+"))
            if (words.size < 2) continue
            if (words[0] in uiWords) continue
            if (words[0].firstOrNull()?.isUpperCase() != true) continue

            val phrase = mutableListOf<String>()
            for (w in words) {
                val clean = w.trim()
                if (clean.isEmpty()) continue
                if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) {
                    val stripped = clean.trimEnd('.', '!', '?')
                    if (stripped.isNotEmpty()) phrase.add(stripped)
                    break
                }
                if (clean == "—" || clean == "is" || clean == "was") break
                phrase.add(clean)
                if (phrase.size >= 12) break
            }

            if (phrase.size in 2..12) {
                val title = stripParenthetical(phrase.joinToString(" "))
                Log.d(tag, "SmartSearch: strategy3 match: \"${phrase.joinToString(" ")}\" → \"$title\"")
                return title
            }
        }

        Log.d(tag, "SmartSearch: all 3 strategies failed")
        return null
    }

    private fun stripParenthetical(s: String): String {
        var result = s.trim()
        result = result.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
        result = result.replace(Regex("^\\s*\\([^)]*\\)\\s*"), "").trim()
        return result
    }

    fun getCachedTitle(query: String, page: Int): String? {
        if (page > 1 && query == cachedQuery && cachedTitle.isNotEmpty()) {
            Log.i(tag, "SmartSearch: using cached title \"$cachedTitle\" for page $page")
            return cachedTitle
        }
        return null
    }

    fun cacheTitle(query: String, title: String) {
        cachedQuery = query
        cachedTitle = title
    }

    fun warmUp() {
        webViewFetcher.warmUpGoogleWebView()
    }
}

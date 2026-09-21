package eu.kanade.tachiyomi.animeextension.en.miruro

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.decodeHex
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/**
 * Miruro's runtime frontend configuration, published by the site at
 * `$baseUrl/env2.js` as:
 *
 * ```
 * window.env=JSON.parse("{\"VITE_PROXY_A\":\"…\",\"VITE_PROXY_B\":\"…\",…}");
 * ```
 *
 * The stream-proxy hosts, the obfuscation keys and the default stream referer
 * all live in that file, and the site rotates them: the original
 * `vault01/vault02.ultracloud.cc` proxy pair was replaced by
 * `s1.watami.win` / `s1.piltover.li`, and the default referer moved from
 * `kwik.cx` to `strm.cx`. Stream URLs are built by XOR-obfuscating the real
 * stream URL and referer and prefixing one of those proxy hosts, so a stale
 * host makes **every** stream fail to load even though browsing, details and
 * episode listing (which only need [pipeKey]) keep working.
 *
 * [DEFAULTS] holds the last known-good values so the extension still works if
 * `env2.js` cannot be read, and [fetch] refreshes them so the next rotation
 * does not silently break playback again.
 */
internal class MiruroEnv(
    val proxyA: String,
    val proxyB: String,
    val refererOrigin: String,
    val pipeKey: ByteArray,
    val proxyKey: ByteArray,
) {
    companion object {
        private const val TAG = "MiruroEnv"

        private const val ENV_JS_PATH = "env2.js"

        /** Last known-good values — see the class KDoc for why they rotate. */
        val DEFAULTS = MiruroEnv(
            proxyA = "https://s1.watami.win/",
            proxyB = "https://s1.piltover.li/",
            refererOrigin = "https://strm.cx",
            pipeKey = "71951034f8fbcf53d89db52ceb3dc22c".decodeHex(),
            proxyKey = "a54d389c18527d9fd3e7f0643e27edbe".decodeHex(),
        )

        /**
         * Captures the escaped JSON string body of
         * `window.env=JSON.parse("…")`. Anchored on `")` rather than a trailing
         * quote so the pattern stays a well-formed raw string.
         */
        private val ENV_REGEX = Regex("""window\.env\s*=\s*JSON\.parse\("(.*)"\)""")

        /**
         * Reads and parses `$baseUrl/env2.js`, falling back to [DEFAULTS] for
         * any value the file does not provide. Throws only if the request
         * itself fails; callers are expected to keep the current config.
         */
        fun fetch(client: OkHttpClient, headers: Headers, baseUrl: String): MiruroEnv {
            val envUrl = "${baseUrl.trimEnd('/')}/$ENV_JS_PATH"
            val request = GET(envUrl, headers)
            val body = client.newBuilder()
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("env2.js HTTP ${response.code}")
                    }
                    response.body?.string().orEmpty()
                }

            val match = ENV_REGEX.find(body)
                ?: throw IllegalStateException("env2.js has no window.env payload")
            val unescaped = JSONTokener("\"${match.groupValues[1]}\"").nextValue() as? String
                ?: throw IllegalStateException("env2.js window.env payload is not a string")
            val json = JSONObject(unescaped)

            val env = MiruroEnv(
                proxyA = json.optString("VITE_PROXY_A").orEmpty().asProxyBase() ?: DEFAULTS.proxyA,
                proxyB = json.optString("VITE_PROXY_B").orEmpty().asProxyBase() ?: DEFAULTS.proxyB,
                refererOrigin = json.optString("VITE_REFERER_ORIGIN").orEmpty()
                    .trim().trimEnd('/').ifBlank { DEFAULTS.refererOrigin },
                pipeKey = json.optString("VITE_PIPE_OBF_KEY").orEmpty().hexOrNull() ?: DEFAULTS.pipeKey,
                proxyKey = json.optString("VITE_PROXY_OBF_KEY").orEmpty().hexOrNull() ?: DEFAULTS.proxyKey,
            )
            Log.i(
                TAG,
                "env2.js: proxyA=${env.proxyA} proxyB=${env.proxyB} referer=${env.refererOrigin} " +
                    "(keys ${if (env.pipeKey.contentEquals(DEFAULTS.pipeKey) && env.proxyKey.contentEquals(DEFAULTS.proxyKey)) "unchanged" else "ROTATED"})",
            )
            return env
        }

        /** Proxy bases must end in `/` — the frontend normalises them the same way. */
        private fun String.asProxyBase(): String? = trim().takeIf { it.isNotBlank() }?.let {
            if (it.endsWith("/")) it else "$it/"
        }

        private fun String.hexOrNull(): ByteArray? = takeIf { it.isNotBlank() }?.let { hex ->
            runCatching { hex.decodeHex() }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}

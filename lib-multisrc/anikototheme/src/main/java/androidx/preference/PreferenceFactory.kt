package androidx.preference

import android.content.Context

/**
 * Creates a plain, non-interactive `androidx.preference.Preference` row (used by the read-only
 * "Details" and the clickable "Test connection" entries).
 *
 * `aniyomi-extensions-lib` only stubs part of `androidx.preference`: its `Preference` declares no
 * `Context` constructor, while the Aniyomi app ships the real class at runtime. The instance is
 * therefore created reflectively against the app's implementation; `null` simply skips the row.
 */
internal fun newPlainPreference(context: Context): Preference? = runCatching {
    Preference::class.java.getConstructor(Context::class.java).newInstance(context) as Preference
}.getOrNull()

/** Marks a row as non-selectable — `setSelectable` is missing from the compile-time stubs. */
internal fun Preference.unselectable() {
    runCatching {
        javaClass.getMethod("setSelectable", Boolean::class.javaPrimitiveType).invoke(this, false)
    }
}

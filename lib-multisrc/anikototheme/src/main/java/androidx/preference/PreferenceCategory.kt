package androidx.preference

import android.content.Context

/**
 * Compile-time stub of the app-provided `androidx.preference.PreferenceCategory`.
 *
 * The `aniyomi-extensions-lib` stubs this repo compiles against cover only a subset of
 * `androidx.preference` — `PreferenceCategory` is missing from it, which broke the categorised
 * settings UI in `AnikotoTheme.setupPreferenceScreen`. Declaring it here keeps the categorised
 * layout compiling against the same API the Aniyomi app provides at runtime.
 *
 * This class is never loaded: extension class loaders resolve through the app class loader
 * first, and the app ships the real `androidx.preference:preference-ktx` implementation under
 * this exact name (same mechanism as every other `compileOnly` stub in this module).
 */
@Suppress("UNUSED_PARAMETER")
class PreferenceCategory(context: Context) : Preference() {

    fun addPreference(preference: Preference): Boolean = throw UnsupportedOperationException("Stub! Provided by the Aniyomi app at runtime.")
}

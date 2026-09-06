package com.framepick.app.data.preferences

import android.content.Context

/**
 * Gates first launch behind a blocking disclaimer. Re-acceptance is required
 * whenever the disclaimer text changes (version bump).
 */
object DisclaimerStore {
    const val DISCLAIMER_VERSION = "v1"

    private const val PREFS_NAME = "framepick_preferences"
    private const val KEY_ACCEPTED_VERSION = "disclaimer_accepted_version"

    fun shouldShowDisclaimer(
        acceptedVersion: String?,
        currentVersion: String = DISCLAIMER_VERSION,
    ): Boolean = acceptedVersion != currentVersion

    fun acceptedVersion(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCEPTED_VERSION, null)

    fun accept(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCEPTED_VERSION, DISCLAIMER_VERSION)
            .apply()
    }
}

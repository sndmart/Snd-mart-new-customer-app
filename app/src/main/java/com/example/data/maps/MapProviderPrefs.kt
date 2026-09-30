package com.example.data.maps

import android.content.Context

/**
 * Local device state for the dual map-provider system:
 * - The user's manual provider override (Auto / Google / OpenStreetMap), if they've set one.
 * - The timestamp of the last detected Google Maps load failure, so we skip retrying Google
 *   (and paying the ~8s load-timeout again) for [GOOGLE_FAILURE_TTL_MS] after a failure.
 *
 * Follows the same convention as [com.example.data.session.UserSessionManager]'s own
 * `getSharedPreferences(...)` usage.
 */
class MapProviderPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var userOverride: MapProviderUserOverride
        get() = when (prefs.getString(KEY_USER_OVERRIDE, null)) {
            "GOOGLE" -> MapProviderUserOverride.GOOGLE
            "OSM" -> MapProviderUserOverride.OSM
            else -> MapProviderUserOverride.AUTO
        }
        set(value) {
            prefs.edit().putString(KEY_USER_OVERRIDE, value.name).apply()
        }

    var lastGoogleFailureAtMs: Long
        get() = prefs.getLong(KEY_LAST_GOOGLE_FAILURE_AT, 0L)
        private set(value) {
            prefs.edit().putLong(KEY_LAST_GOOGLE_FAILURE_AT, value).apply()
        }

    fun recordGoogleFailure(nowMs: Long = System.currentTimeMillis()) {
        lastGoogleFailureAtMs = nowMs
    }

    fun isGoogleFailureActive(nowMs: Long = System.currentTimeMillis()): Boolean {
        val ts = lastGoogleFailureAtMs.takeIf { it > 0L }
        return isGoogleLoadFailureActive(ts, nowMs)
    }

    companion object {
        private const val PREFS_NAME = "sndmart_map_prefs"
        private const val KEY_USER_OVERRIDE = "user_override"
        private const val KEY_LAST_GOOGLE_FAILURE_AT = "last_google_failure_at"
    }
}

package com.example.data.maps

/** Which map implementation is actually rendering right now. */
enum class MapProvider { GOOGLE, OSM }

/** The `map_provider` value from the `app_settings` table (missing/unparseable = AUTO). */
enum class RemoteMapProviderFlag { GOOGLE, OSM, AUTO }

/** The user's manual choice from the on-map provider switch (AUTO = no override). */
enum class MapProviderUserOverride { AUTO, GOOGLE, OSM }

/** How long a detected Google Maps load failure is remembered before Google is retried. */
const val GOOGLE_FAILURE_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours

/**
 * True if a previously-recorded Google Maps load failure is still within its TTL, i.e. we
 * should keep skipping Google (and the ~8s load-timeout wait) until it expires.
 */
fun isGoogleLoadFailureActive(
    failureTimestampMs: Long?,
    nowMs: Long,
    ttlMs: Long = GOOGLE_FAILURE_TTL_MS
): Boolean {
    if (failureTimestampMs == null || failureTimestampMs <= 0L) return false
    return (nowMs - failureTimestampMs) < ttlMs
}

/**
 * Pure decision function: which map provider should render right now.
 *
 * Precedence:
 * 1. A manual user override (from the on-map Auto/Google/OSM switch) always wins.
 * 2. Remote flag "osm" forces OSM unconditionally (e.g. Google billing disabled server-side).
 * 3. Remote flag "google" or "auto" both prefer Google, but only when it's actually usable
 *    (key configured, Play Services present, no recent load failure) — otherwise OSM, so the
 *    map never breaks even when the remote flag says "google".
 */
fun decideMapProvider(
    remoteFlag: RemoteMapProviderFlag,
    googleKeyConfigured: Boolean,
    playServicesOk: Boolean,
    googleLoadFailed: Boolean,
    userOverride: MapProviderUserOverride
): MapProvider {
    if (userOverride == MapProviderUserOverride.GOOGLE) return MapProvider.GOOGLE
    if (userOverride == MapProviderUserOverride.OSM) return MapProvider.OSM

    if (remoteFlag == RemoteMapProviderFlag.OSM) return MapProvider.OSM

    val googleUsable = googleKeyConfigured && playServicesOk && !googleLoadFailed
    return if (googleUsable) MapProvider.GOOGLE else MapProvider.OSM
}

package com.example.data.maps

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Whether Google Play Services is installed and up to date on this device — one of the
 * three failure signals [decideMapProvider] uses to fall back to OSM (the others being an
 * unconfigured Google Maps key and a detected map-load failure).
 */
fun isPlayServicesAvailable(context: Context): Boolean {
    return try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: Throwable) {
        false
    }
}

package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.android.libraries.places.api.Places

/**
 * Central configuration for Google Maps SDK and Places API.
 * The key is supplied via build-time BuildConfig / .env (GOOGLE_MAPS_API_KEY).
 */
object GoogleMapsConfig {
    const val DEFAULT_API_KEY = "YOUR_GOOGLE_MAPS_API_KEY"

    val apiKey: String
        get() = try {
            val key = BuildConfig.GOOGLE_MAPS_API_KEY
            if (key.isNullOrBlank()) DEFAULT_API_KEY else key
        } catch (e: Throwable) {
            DEFAULT_API_KEY
        }

    val isConfigured: Boolean
        get() {
            val configured = apiKey.isNotBlank() && apiKey != DEFAULT_API_KEY
            if (!configured && BuildConfig.DEBUG) {
                Log.w("GoogleMapsConfig", "Google Maps API Key is missing or using placeholder ('$DEFAULT_API_KEY'). Maps and Places features will be disabled until a valid GOOGLE_MAPS_API_KEY is supplied.")
            }
            return configured
        }

    /**
     * Initializes Google Places SDK once with application context.
     * Safe to call multiple times.
     */
    fun initializePlaces(context: Context) {
        try {
            if (!isConfigured) {
                if (BuildConfig.DEBUG) {
                    Log.w("GoogleMapsConfig", "Skipping Places SDK initialization: Google Maps API key is not configured.")
                }
                return
            }
            if (!Places.isInitialized()) {
                Places.initialize(context.applicationContext, apiKey)
            }
        } catch (e: Throwable) {
            Log.w("GoogleMapsConfig", "Failed to initialize Places SDK: ${e.message}")
        }
    }
}

package com.example.util

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.example.data.remote.GoogleMapsConfig
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

data class PlaceSearchResult(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String,
    val fullText: String,
    val directLatLng: LatLng? = null
)

data class GeocodeAddressResult(
    val addressLine: String,
    val featureName: String? = null,
    val subLocality: String? = null,
    val locality: String? = null,
    val postalCode: String? = null
)

fun isValidIndianCoordinate(lat: Double, lng: Double): Boolean {
    return lat in 6.0..38.0 && lng in 68.0..98.0
}

object MapLocationHelper {

    fun isValidIndianCoordinate(lat: Double, lng: Double): Boolean {
        return com.example.util.isValidIndianCoordinate(lat, lng)
    }

    @Volatile private var lastNominatimRequestAtMs: Long = 0L

    /**
     * Address search against Nominatim's `/search` endpoint — the OSM fallback used only when
     * Google Places/Geocoder are unconfigured or returned nothing. Per the Nominatim usage
     * policy this is never wired to autocomplete-as-you-type: callers (searchPlaces) already
     * gate on a minimum query length, and this additionally self-throttles to ~1 req/sec.
     */
    private suspend fun searchViaNominatim(context: Context, query: String): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!shouldAllowNominatimRequest(lastNominatimRequestAtMs, now)) return@withContext emptyList()
        lastNominatimRequestAtMs = now
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "${OsmGeocodingConfig.nominatimBaseUrl}/search?format=jsonv2&q=$encoded&countrycodes=in&addressdetails=1&limit=5"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", OsmGeocodingConfig.nominatimUserAgent(context.packageName))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                parseNominatimSearchResponse(response.body?.string())
            }
        } catch (e: Exception) {
            Log.w("MapLocationHelper", "Nominatim search fallback failed: ${e.message}")
            emptyList()
        }
    }

    /** Reverse geocoding against Nominatim's `/reverse` endpoint — OSM fallback for reverseGeocode(). */
    private suspend fun reverseGeocodeViaNominatim(context: Context, lat: Double, lng: Double): GeocodeAddressResult? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!shouldAllowNominatimRequest(lastNominatimRequestAtMs, now)) return@withContext null
        lastNominatimRequestAtMs = now
        try {
            val url = "${OsmGeocodingConfig.nominatimBaseUrl}/reverse?format=jsonv2&lat=$lat&lon=$lng&countrycodes=in&addressdetails=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", OsmGeocodingConfig.nominatimUserAgent(context.packageName))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseNominatimReverseResponse(response.body?.string())
            }
        } catch (e: Exception) {
            Log.w("MapLocationHelper", "Nominatim reverse geocoding fallback failed: ${e.message}")
            null
        }
    }

    /**
     * Searches for place predictions via Places SDK if initialized and configured.
     * Falls back to Android's built-in Geocoder if Places is unconfigured or returns no results.
     */
    suspend fun searchPlaces(context: Context, query: String): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 1. Try Google Places SDK if configured
        if (GoogleMapsConfig.isConfigured) {
            try {
                GoogleMapsConfig.initializePlaces(context)
                if (Places.isInitialized()) {
                    val client = Places.createClient(context)
                    val request = FindAutocompletePredictionsRequest.builder()
                        .setQuery(query)
                        .setCountries("IN")
                        .build()

                    val response = suspendCancellableCoroutine { cont ->
                        client.findAutocompletePredictions(request)
                            .addOnSuccessListener { resp ->
                                cont.resume(resp.autocompletePredictions)
                            }
                            .addOnFailureListener { exc ->
                                Log.w("MapLocationHelper", "Places search failed: ${exc.message}")
                                cont.resume(emptyList<AutocompletePrediction>())
                            }
                    }

                    if (response.isNotEmpty()) {
                        return@withContext response.map { pred ->
                            PlaceSearchResult(
                                placeId = pred.placeId,
                                primaryText = pred.getPrimaryText(null).toString(),
                                secondaryText = pred.getSecondaryText(null).toString(),
                                fullText = pred.getFullText(null).toString()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MapLocationHelper", "Error with Places SDK search: ${e.message}")
            }
        }

        // 2. Fallback: Built-in Android Geocoder
        val geocoderResults = try {
            val geocoder = Geocoder(context, Locale("en", "IN"))
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocationName(query, 5) ?: emptyList()
            results.mapIndexed { index, addr ->
                val primary = addr.featureName ?: addr.subLocality ?: addr.locality ?: query
                val full = (0..addr.maxAddressLineIndex).joinToString(", ") { addr.getAddressLine(it) }
                    .ifBlank { listOfNotNull(addr.subLocality, addr.locality, addr.adminArea).joinToString(", ") }
                PlaceSearchResult(
                    placeId = "geo_${addr.latitude}_${addr.longitude}_$index",
                    primaryText = primary,
                    secondaryText = full,
                    fullText = full.ifBlank { primary },
                    directLatLng = LatLng(addr.latitude, addr.longitude)
                )
            }
        } catch (e: Exception) {
            Log.w("MapLocationHelper", "Geocoder search fallback failed: ${e.message}")
            emptyList()
        }
        if (geocoderResults.isNotEmpty()) return@withContext geocoderResults

        // 3. OSM fallback: Nominatim search — only for a real, deliberate search (never
        // autocomplete-as-you-type; the minimum length here plus the request-level throttle
        // in searchViaNominatim together honour Nominatim's usage policy).
        if (query.trim().length >= 3) {
            return@withContext searchViaNominatim(context, query.trim())
        }
        emptyList()
    }

    /**
     * Resolves LatLng for a place ID (either via Places SDK or from fallback Geocoder).
     */
    suspend fun fetchPlaceLatLng(context: Context, place: PlaceSearchResult): LatLng? = withContext(Dispatchers.IO) {
        if (place.directLatLng != null) {
            return@withContext place.directLatLng
        }

        if (GoogleMapsConfig.isConfigured) {
            try {
                GoogleMapsConfig.initializePlaces(context)
                if (Places.isInitialized()) {
                    val client = Places.createClient(context)
                    val fields = listOf(Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS)
                    val request = FetchPlaceRequest.builder(place.placeId, fields).build()

                    val latLng = suspendCancellableCoroutine<LatLng?> { cont ->
                        client.fetchPlace(request)
                            .addOnSuccessListener { resp ->
                                cont.resume(resp.place.latLng)
                            }
                            .addOnFailureListener { exc ->
                                Log.w("MapLocationHelper", "Fetch place failed: ${exc.message}")
                                cont.resume(null)
                            }
                    }
                    if (latLng != null) return@withContext latLng
                }
            } catch (e: Exception) {
                Log.w("MapLocationHelper", "Error fetching place LatLng: ${e.message}")
            }
        }

        // Try Geocoder by fullText
        try {
            val geocoder = Geocoder(context, Locale("en", "IN"))
            @Suppress("DEPRECATION")
            val addrs = geocoder.getFromLocationName(place.fullText, 1)
            addrs?.firstOrNull()?.let { return@withContext LatLng(it.latitude, it.longitude) }
        } catch (e: Exception) {
            // ignore
        }
        null
    }

    private val httpClient by lazy { OkHttpClient() }

    /**
     * Fallback to Google's Geocoding REST API using GoogleMapsConfig.apiKey when
     * device built-in Geocoder fails or returns empty results.
     */
    suspend fun reverseGeocodeViaRestApi(lat: Double, lng: Double): GeocodeAddressResult? = withContext(Dispatchers.IO) {
        if (!GoogleMapsConfig.isConfigured) return@withContext null
        try {
            val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lng&key=${GoogleMapsConfig.apiKey}"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (json.optString("status") != "OK") return@withContext null
                val results = json.optJSONArray("results") ?: return@withContext null
                if (results.length() == 0) return@withContext null
                val firstResult = results.getJSONObject(0)
                val formattedAddress = firstResult.optString("formatted_address")

                var subLocality: String? = null
                var locality: String? = null
                var postalCode: String? = null
                val components = firstResult.optJSONArray("address_components")
                if (components != null) {
                    for (i in 0 until components.length()) {
                        val comp = components.getJSONObject(i)
                        val types = comp.optJSONArray("types")?.let { arr ->
                            (0 until arr.length()).map { arr.getString(it) }
                        } ?: emptyList()
                        val name = comp.optString("long_name")
                        if ("sublocality" in types || "sublocality_level_1" in types) subLocality = name
                        if ("locality" in types) locality = name
                        if ("postal_code" in types) postalCode = name
                    }
                }

                GeocodeAddressResult(
                    addressLine = formattedAddress,
                    featureName = null,
                    subLocality = subLocality,
                    locality = locality,
                    postalCode = postalCode
                )
            }
        } catch (e: Exception) {
            Log.w("MapLocationHelper", "REST geocoding failed: ${e.message}")
            null
        }
    }

    /**
     * Reverse-geocodes (lat, lng) to a human-readable street address.
     * Tries the device's built-in Geocoder first; if that returns null or fails,
     * falls back to Google's Geocoding REST API using the configured Maps API key.
     */
    suspend fun reverseGeocode(context: Context, latLng: com.google.android.gms.maps.model.LatLng): GeocodeAddressResult? =
        reverseGeocode(context, latLng.latitude, latLng.longitude)

    suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): GeocodeAddressResult? = withContext(Dispatchers.IO) {
        // Try the device's built-in Geocoder first (fast, no network cost if it works)
        val deviceResult = try {
            val geocoder = Geocoder(context, Locale("en", "IN"))
            if (Geocoder.isPresent()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val addrs = suspendCancellableCoroutine<List<Address>> { cont ->
                        try {
                            geocoder.getFromLocation(lat, lng, 1) { addresses ->
                                cont.resume(addresses)
                            }
                        } catch (e: Exception) {
                            cont.resume(emptyList())
                        }
                    }
                    addrs.firstOrNull()?.let { formatAddress(it) }
                } else {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()?.let { formatAddress(it) }
                }
            } else null
        } catch (e: Exception) {
            Log.w("MapLocationHelper", "Device geocoder failed: ${e.message}")
            null
        }

        // Fall back to Google's REST API (if a key is configured), then finally to the OSM
        // Nominatim fallback if Google is unconfigured or that also came back empty.
        deviceResult
            ?: reverseGeocodeViaRestApi(lat, lng)
            ?: reverseGeocodeViaNominatim(context, lat, lng)
    }

    private fun formatAddress(addr: Address): GeocodeAddressResult {
        val lines = (0..addr.maxAddressLineIndex).mapNotNull { addr.getAddressLine(it) }
        val full = if (lines.isNotEmpty()) lines.joinToString(", ") else {
            listOfNotNull(addr.featureName, addr.subLocality, addr.locality, addr.adminArea, addr.postalCode)
                .joinToString(", ")
        }
        return GeocodeAddressResult(
            addressLine = full,
            featureName = addr.featureName,
            subLocality = addr.subLocality,
            locality = addr.locality,
            postalCode = addr.postalCode
        )
    }
}

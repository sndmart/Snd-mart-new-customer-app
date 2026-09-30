package com.example.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * OSM geocoding fallback endpoints (used only when Google Places/Geocoder are unconfigured
 * or fail — see [MapLocationHelper]). Overridable at runtime, e.g. from the `osm_tile_url`-
 * style app_settings pattern, if a production deployment needs a different provider.
 *
 * IMPORTANT: the default Nominatim instance (nominatim.openstreetmap.org) is a free public
 * service with a strict usage policy (https://operations.osmfoundation.org/policies/nominatim/):
 * max ~1 request/second, no autocomplete-as-you-type, and a real contact User-Agent. See
 * [nominatimUserAgent] and [shouldAllowNominatimRequest]. For real production volume, point
 * this at a commercial or self-hosted instance instead.
 */
object OsmGeocodingConfig {
    var nominatimBaseUrl: String = "https://nominatim.openstreetmap.org"
    var photonBaseUrl: String = "https://photon.komoot.io"

    /** Nominatim's usage policy requires a descriptive User-Agent identifying the app. */
    fun nominatimUserAgent(packageName: String): String = "$packageName (Sndmart customer app; contact: sndmartt@gmail.com)"
}

/**
 * Pure rate-limit gate for Nominatim requests: at most one request per [minIntervalMs]
 * (policy caps free-tier usage at ~1 req/sec). Callers must additionally avoid firing this
 * on every keystroke — see [MapLocationHelper.searchPlaces]'s minimum-3-characters gate,
 * which is enforced separately from this time-based gate.
 */
fun shouldAllowNominatimRequest(lastRequestAtMs: Long?, nowMs: Long, minIntervalMs: Long = 1000L): Boolean {
    if (lastRequestAtMs == null || lastRequestAtMs <= 0L) return true
    return (nowMs - lastRequestAtMs) >= minIntervalMs
}

/** Parses a Nominatim `/reverse?format=jsonv2` response body into our existing address model. */
fun parseNominatimReverseResponse(json: String?): GeocodeAddressResult? {
    if (json.isNullOrBlank()) return null
    return try {
        val obj = JSONObject(json)
        if (obj.has("error")) return null
        val displayName = obj.optString("display_name").takeIf { it.isNotBlank() } ?: return null
        val address = obj.optJSONObject("address")
        val subLocality = address?.optString("suburb")?.takeIf { it.isNotBlank() }
            ?: address?.optString("neighbourhood")?.takeIf { it.isNotBlank() }
            ?: address?.optString("residential")?.takeIf { it.isNotBlank() }
        val locality = address?.optString("city")?.takeIf { it.isNotBlank() }
            ?: address?.optString("town")?.takeIf { it.isNotBlank() }
            ?: address?.optString("village")?.takeIf { it.isNotBlank() }
        val postalCode = address?.optString("postcode")?.takeIf { it.isNotBlank() }
        GeocodeAddressResult(
            addressLine = displayName,
            featureName = null,
            subLocality = subLocality,
            locality = locality,
            postalCode = postalCode
        )
    } catch (e: Exception) {
        null
    }
}

/** Parses a Nominatim `/search?format=jsonv2` response body into our existing search-result model. */
fun parseNominatimSearchResponse(json: String?): List<PlaceSearchResult> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = obj.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = obj.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val displayName = obj.optString("display_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val primary = displayName.substringBefore(",").trim().ifBlank { displayName }
            PlaceSearchResult(
                placeId = "nominatim_${obj.optLong("place_id", i.toLong())}",
                primaryText = primary,
                secondaryText = displayName,
                fullText = displayName,
                directLatLng = com.google.android.gms.maps.model.LatLng(lat, lon)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

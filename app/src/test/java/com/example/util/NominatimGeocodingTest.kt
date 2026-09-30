package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NominatimGeocodingTest {

    @Test
    fun parseReverseResponse_typicalIndianAddress() {
        val json = """
            {
              "display_name": "12, MG Road, Sindhanur, Raichur, Karnataka, 584128, India",
              "address": {
                "suburb": "Sindhanur",
                "city": "Raichur",
                "postcode": "584128"
              }
            }
        """.trimIndent()
        val result = parseNominatimReverseResponse(json)
        assertEquals("12, MG Road, Sindhanur, Raichur, Karnataka, 584128, India", result?.addressLine)
        assertEquals("Sindhanur", result?.subLocality)
        assertEquals("Raichur", result?.locality)
        assertEquals("584128", result?.postalCode)
    }

    @Test
    fun parseReverseResponse_fallsBackToTownAndVillageForLocality() {
        val jsonTown = """{"display_name": "X", "address": {"town": "Sindhanur"}}"""
        assertEquals("Sindhanur", parseNominatimReverseResponse(jsonTown)?.locality)

        val jsonVillage = """{"display_name": "X", "address": {"village": "Gangavati"}}"""
        assertEquals("Gangavati", parseNominatimReverseResponse(jsonVillage)?.locality)
    }

    @Test
    fun parseReverseResponse_errorOrMissingDisplayName_returnsNull() {
        assertNull(parseNominatimReverseResponse(null))
        assertNull(parseNominatimReverseResponse(""))
        assertNull(parseNominatimReverseResponse("""{"error": "Unable to geocode"}"""))
        assertNull(parseNominatimReverseResponse("""{"address": {}}"""))
        assertNull(parseNominatimReverseResponse("not json"))
    }

    @Test
    fun parseSearchResponse_typicalResultsList() {
        val json = """
            [
              {
                "place_id": 12345,
                "lat": "15.7667",
                "lon": "76.7583",
                "display_name": "Sindhanur, Raichur, Karnataka, India"
              },
              {
                "place_id": 67890,
                "lat": "16.0",
                "lon": "77.0",
                "display_name": "Raichur, Karnataka, India"
              }
            ]
        """.trimIndent()
        val results = parseNominatimSearchResponse(json)
        assertEquals(2, results.size)
        assertEquals("Sindhanur", results[0].primaryText)
        assertEquals("Sindhanur, Raichur, Karnataka, India", results[0].fullText)
        assertEquals(15.7667, results[0].directLatLng?.latitude ?: 0.0, 0.0001)
        assertEquals(76.7583, results[0].directLatLng?.longitude ?: 0.0, 0.0001)
        assertEquals("nominatim_12345", results[0].placeId)
    }

    @Test
    fun parseSearchResponse_skipsEntriesMissingCoordinates() {
        val json = """[{"place_id": 1, "display_name": "No coords here"}]"""
        assertTrue(parseNominatimSearchResponse(json).isEmpty())
    }

    @Test
    fun parseSearchResponse_malformedOrEmpty_returnsEmptyList() {
        assertTrue(parseNominatimSearchResponse(null).isEmpty())
        assertTrue(parseNominatimSearchResponse("").isEmpty())
        assertTrue(parseNominatimSearchResponse("[]").isEmpty())
        assertTrue(parseNominatimSearchResponse("not json").isEmpty())
    }

    @Test
    fun rateLimit_firstRequestAlwaysAllowed() {
        assertTrue(shouldAllowNominatimRequest(null, nowMs = 1_000_000L))
        assertTrue(shouldAllowNominatimRequest(0L, nowMs = 1_000_000L))
    }

    @Test
    fun rateLimit_blocksRequestsWithinOneSecond() {
        val last = 1_000_000L
        assertTrue(shouldAllowNominatimRequest(last, nowMs = last + 999L, minIntervalMs = 1000L).not())
    }

    @Test
    fun rateLimit_allowsRequestsAtOrAfterInterval() {
        val last = 1_000_000L
        assertTrue(shouldAllowNominatimRequest(last, nowMs = last + 1000L, minIntervalMs = 1000L))
        assertTrue(shouldAllowNominatimRequest(last, nowMs = last + 5000L, minIntervalMs = 1000L))
    }
}

package com.example

import com.example.util.MapLocationHelper
import com.example.util.isValidIndianCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationValidationUnitTest {

    @Test
    fun isValidIndianCoordinate_validCoordinates_returnsTrue() {
        // Sindhanur
        assertTrue(isValidIndianCoordinate(15.7667, 76.7583))
        // Bengaluru
        assertTrue(isValidIndianCoordinate(12.9716, 77.5946))
        // Mumbai
        assertTrue(isValidIndianCoordinate(19.0760, 72.8777))
        // Delhi
        assertTrue(isValidIndianCoordinate(28.6139, 77.2090))
        // Southern tip (Kanyakumari)
        assertTrue(isValidIndianCoordinate(8.0883, 77.5385))
        // Northern region (Srinagar)
        assertTrue(isValidIndianCoordinate(34.0837, 74.7973))
        // Western boundary (Gujarat)
        assertTrue(isValidIndianCoordinate(23.0, 68.5))
        // Eastern boundary (Arunachal Pradesh)
        assertTrue(isValidIndianCoordinate(27.0, 97.0))

        // Via MapLocationHelper
        assertTrue(MapLocationHelper.isValidIndianCoordinate(15.7667, 76.7583))
    }

    @Test
    fun isValidIndianCoordinate_invalidCoordinates_returnsFalse() {
        // Null Island / Default fallback bug
        assertFalse(isValidIndianCoordinate(0.0, 0.0))
        // Negative coordinates
        assertFalse(isValidIndianCoordinate(-15.7667, 76.7583))
        assertFalse(isValidIndianCoordinate(15.7667, -76.7583))
        assertFalse(isValidIndianCoordinate(-33.8688, 151.2093)) // Sydney

        // New York
        assertFalse(isValidIndianCoordinate(40.7128, -74.0060))
        // London
        assertFalse(isValidIndianCoordinate(51.5074, -0.1278))
        // Singapore (lat too low: 1.35 < 6.0, lng too high: 103.8 > 98.0)
        assertFalse(isValidIndianCoordinate(1.3521, 103.8198))

        // Boundary tests
        assertFalse(isValidIndianCoordinate(5.99, 75.0)) // Just below southern latitude
        assertFalse(isValidIndianCoordinate(38.01, 75.0)) // Just above northern latitude
        assertFalse(isValidIndianCoordinate(15.0, 67.99)) // Just west of longitude bound
        assertFalse(isValidIndianCoordinate(15.0, 98.01)) // Just east of longitude bound

        // Via MapLocationHelper
        assertFalse(MapLocationHelper.isValidIndianCoordinate(0.0, 0.0))
    }
}

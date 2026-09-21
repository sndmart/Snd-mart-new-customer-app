package com.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class OperatingHoursUtilTest {

    @Test
    fun nullOrBlankHours_returnsTrue() {
        assertTrue(isVendorWithinOperatingHours(null, null))
        assertTrue(isVendorWithinOperatingHours("", ""))
        assertTrue(isVendorWithinOperatingHours("  ", "10:00"))
        assertTrue(isVendorWithinOperatingHours("10:00", null))
    }

    @Test
    fun daytimeWindow_withinHours() {
        val now = LocalTime.of(14, 30)
        assertTrue(isVendorWithinOperatingHours("10:00", "22:00", now))
        assertTrue(isVendorWithinOperatingHours("10:00:00", "22:00:00", now))
    }

    @Test
    fun daytimeWindow_outsideHours() {
        val now = LocalTime.of(9, 30)
        assertFalse(isVendorWithinOperatingHours("10:00", "22:00", now))

        val late = LocalTime.of(22, 30)
        assertFalse(isVendorWithinOperatingHours("10:00", "22:00", late))
    }

    @Test
    fun overnightWindow_withinHours() {
        val open = "18:00"
        val close = "02:00"

        // At 20:00
        assertTrue(isVendorWithinOperatingHours(open, close, LocalTime.of(20, 0)))
        // At 01:30
        assertTrue(isVendorWithinOperatingHours(open, close, LocalTime.of(1, 30)))
        // Exactly at open
        assertTrue(isVendorWithinOperatingHours(open, close, LocalTime.of(18, 0)))
        // Exactly at close
        assertTrue(isVendorWithinOperatingHours(open, close, LocalTime.of(2, 0)))
    }

    @Test
    fun overnightWindow_outsideHours() {
        val open = "18:00"
        val close = "02:00"

        // At 10:00 AM
        assertFalse(isVendorWithinOperatingHours(open, close, LocalTime.of(10, 0)))
        // At 17:59
        assertFalse(isVendorWithinOperatingHours(open, close, LocalTime.of(17, 59)))
        // At 02:01
        assertFalse(isVendorWithinOperatingHours(open, close, LocalTime.of(2, 1)))
    }

    @Test
    fun amPmFormat_support() {
        val open = "10:00 AM"
        val close = "10:00 PM"
        assertTrue(isVendorWithinOperatingHours(open, close, LocalTime.of(12, 0)))
        assertFalse(isVendorWithinOperatingHours(open, close, LocalTime.of(8, 0)))
    }

    @Test
    fun invalidFormat_fallsBackToTrue() {
        assertTrue(isVendorWithinOperatingHours("invalid", "times", LocalTime.of(12, 0)))
    }

    @Test
    fun emptySlots_returnsTrue() {
        // Missing or empty slot data = Unrestricted (Open)
        assertTrue(isWithinAnySlot(emptyList(), LocalTime.of(15, 0)))
    }

    @Test
    fun multiSlot_matchesAnySlot() {
        val lunchSlot = com.example.data.model.OperatingSlot(
            id = "1",
            startTime = "12:00",
            endTime = "15:00"
        )
        val dinnerSlot = com.example.data.model.OperatingSlot(
            id = "2",
            startTime = "19:00",
            endTime = "23:00"
        )
        val slots = listOf(lunchSlot, dinnerSlot)

        // During lunch (13:00) -> Open
        assertTrue(isWithinAnySlot(slots, LocalTime.of(13, 0)))

        // Between slots (16:30) -> Closed
        assertFalse(isWithinAnySlot(slots, LocalTime.of(16, 30)))

        // During dinner (20:15) -> Open
        assertTrue(isWithinAnySlot(slots, LocalTime.of(20, 15)))

        // Late night after dinner (23:30) -> Closed
        assertFalse(isWithinAnySlot(slots, LocalTime.of(23, 30)))
    }
}

package com.example

import com.example.data.model.HotelComparator
import com.example.data.model.Vendor
import com.example.data.model.sortTier
import org.junit.Assert.assertEquals
import org.junit.Test

class HotelSortingUnitTest {

    @Test
    fun vendor_sortTier_evaluatesCorrectly() {
        // Tier 0: Active + Featured
        val v0 = Vendor(id = "1", name = "Hotel 1", isActive = true, isFeatured = true)
        assertEquals(0, v0.sortTier)

        // Tier 1: Active + Non-featured (false or null)
        val v1a = Vendor(id = "2", name = "Hotel 2", isActive = true, isFeatured = false)
        val v1b = Vendor(id = "3", name = "Hotel 3", isActive = true, isFeatured = null)
        assertEquals(1, v1a.sortTier)
        assertEquals(1, v1b.sortTier)

        // Tier 2: Inactive + Featured
        val v2 = Vendor(id = "4", name = "Hotel 4", isActive = false, isFeatured = true)
        assertEquals(2, v2.sortTier)

        // Tier 3: Inactive + Non-featured (false or null)
        val v3a = Vendor(id = "5", name = "Hotel 5", isActive = false, isFeatured = false)
        val v3b = Vendor(id = "6", name = "Hotel 6", isActive = false, isFeatured = null)
        assertEquals(3, v3a.sortTier)
        assertEquals(3, v3b.sortTier)
    }

    @Test
    fun hotelComparator_sortsAllFourTiersInOrder() {
        val list = listOf(
            Vendor(id = "inactive_normal", name = "Beta Hotel", isActive = false, isFeatured = false),
            Vendor(id = "inactive_featured", name = "Alpha Inn", isActive = false, isFeatured = true),
            Vendor(id = "active_normal", name = "Zeta Cafe", isActive = true, isFeatured = false),
            Vendor(id = "active_featured", name = "Delta Dine", isActive = true, isFeatured = true)
        )

        val sorted = list.sortedWith(HotelComparator)
        val expectedIds = listOf(
            "active_featured",   // Tier 0
            "active_normal",     // Tier 1
            "inactive_featured", // Tier 2
            "inactive_normal"    // Tier 3
        )
        assertEquals(expectedIds, sorted.map { it.id })
    }

    @Test
    fun hotelComparator_sortsAlphabeticallyWithinEachTier() {
        val list = listOf(
            Vendor(id = "af_b", name = "Biryani House", isActive = true, isFeatured = true),
            Vendor(id = "af_a", name = "annapurna", isActive = true, isFeatured = true),
            Vendor(id = "an_z", name = "Zen Garden", isActive = true, isFeatured = false),
            Vendor(id = "an_k", name = "Kamat Cafe", isActive = true, isFeatured = null),
            Vendor(id = "if_m", name = "Moonlight", isActive = false, isFeatured = true),
            Vendor(id = "if_a", name = "Akshaya", isActive = false, isFeatured = true),
            Vendor(id = "in_s", name = "Sunrise", isActive = false, isFeatured = false),
            Vendor(id = "in_c", name = "Corner Hotel", isActive = false, isFeatured = null)
        )

        val sorted = list.sortedWith(HotelComparator)
        val expectedIds = listOf(
            "af_a", // Tier 0 (annapurna)
            "af_b", // Tier 0 (Biryani House)
            "an_k", // Tier 1 (Kamat Cafe, isFeatured=null)
            "an_z", // Tier 1 (Zen Garden, isFeatured=false)
            "if_a", // Tier 2 (Akshaya)
            "if_m", // Tier 2 (Moonlight)
            "in_c", // Tier 3 (Corner Hotel, isFeatured=null)
            "in_s"  // Tier 3 (Sunrise, isFeatured=false)
        )
        assertEquals(expectedIds, sorted.map { it.id })
    }

    @Test
    fun hotelComparator_handlesNullIsFeaturedProperly() {
        val list = listOf(
            Vendor(id = "1", name = "B", isActive = false, isFeatured = null),
            Vendor(id = "2", name = "A", isActive = false, isFeatured = true),
            Vendor(id = "3", name = "D", isActive = true, isFeatured = null),
            Vendor(id = "4", name = "C", isActive = true, isFeatured = true)
        )

        val sorted = list.sortedWith(HotelComparator)
        assertEquals(listOf("4", "3", "2", "1"), sorted.map { it.id })
    }
}

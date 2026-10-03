package com.example.util

import com.example.data.model.Category
import com.example.data.model.OperatingSlot
import com.example.data.model.Product
import com.example.data.model.ResolvedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class HotelMenuAvailabilityTest {

    private fun category(id: String, name: String, sortOrder: Int = 0) =
        Category(id = id, name = name, sortOrder = sortOrder)

    private fun product(
        id: String,
        categoryId: String,
        isActive: Boolean = true,
        isAvailable: Boolean = true,
        availableFrom: String? = null,
        availableUntil: String? = null
    ) = ResolvedProduct(
        baseProduct = Product(
            id = id,
            categoryId = categoryId,
            isActive = isActive,
            isAvailable = isAvailable,
            availableFrom = availableFrom,
            availableUntil = availableUntil
        ),
        effectivePrice = 100.0,
        effectiveMrp = null,
        effectiveStock = 10,
        effectiveIsAvailable = isAvailable
    )

    // An always-open slot so these tests aren't about the hotel's own hours.
    private val alwaysOpenSlots = listOf(OperatingSlot(startTime = "00:00", endTime = "23:59"))

    @Test
    fun isHotelCategoryAvailableNow_trueWhenAnyItemAvailable() {
        val products = listOf(
            product("1", "cat_a", isAvailable = false),
            product("2", "cat_a", isAvailable = true)
        )
        assertTrue(isHotelCategoryAvailableNow("cat_a", products, alwaysOpenSlots))
    }

    @Test
    fun isHotelCategoryAvailableNow_falseWhenNoItemsAvailable() {
        val products = listOf(product("1", "cat_a", isAvailable = false))
        assertFalse(isHotelCategoryAvailableNow("cat_a", products, alwaysOpenSlots))
    }

    @Test
    fun isHotelCategoryAvailableNow_respectsItemTimeWindow() {
        val now = LocalTime.now()
        // A window that definitely does NOT include "now": 1 minute wide, ending 2 minutes ago.
        val closedWindowStart = now.minusMinutes(5).toString().take(5)
        val closedWindowEnd = now.minusMinutes(2).toString().take(5)
        val products = listOf(product("1", "tiffins", availableFrom = closedWindowStart, availableUntil = closedWindowEnd))
        assertFalse(isHotelCategoryAvailableNow("tiffins", products, alwaysOpenSlots))
    }

    @Test
    fun orderHotelCategoryTabs_hotelClosed_keepsNormalOrder_noGreying() {
        val categories = listOf(category("b", "Beverages", 2), category("a", "Tiffins", 1))
        val products = listOf(product("1", "a", isAvailable = true))
        val tabs = orderHotelCategoryTabs(categories, products, alwaysOpenSlots, isHotelOpen = false)
        assertEquals(listOf("a", "b"), tabs.map { it.category.id }) // sort_order, unchanged
        assertTrue(tabs.all { !it.isAvailableNow }) // no per-tab availability distinction when closed
    }

    @Test
    fun orderHotelCategoryTabs_hotelOpen_availableFirst_thenUnavailable() {
        val categories = listOf(
            category("tiffins", "Tiffins", 1),
            category("beverages", "Beverages", 2),
            category("snacks", "Snacks", 3)
        )
        val now = LocalTime.now()
        val futureStart = now.plusHours(2).toString().take(5)
        val futureEnd = now.plusHours(4).toString().take(5)
        val products = listOf(
            product("1", "tiffins", availableFrom = futureStart, availableUntil = futureEnd), // not available yet
            product("2", "beverages", isAvailable = true), // available
            product("3", "snacks", isAvailable = true) // available
        )
        val tabs = orderHotelCategoryTabs(categories, products, alwaysOpenSlots, isHotelOpen = true)
        // beverages, snacks (available, normal order) then tiffins (unavailable, at the end)
        assertEquals(listOf("beverages", "snacks", "tiffins"), tabs.map { it.category.id })
        assertFalse(tabs.first { it.category.id == "tiffins" }.isAvailableNow)
    }

    @Test
    fun orderHotelCategoryTabs_unavailableTab_hasFormattedHint() {
        val categories = listOf(category("tiffins", "Tiffins", 1))
        val products = listOf(product("1", "tiffins", availableFrom = "07:00", availableUntil = "11:00"))
        // "Now" in this test run is assumed outside 07:00-11:00 often; force it deterministically
        // by using a window that's always in the future relative to "now" via large offset instead:
        val now = LocalTime.now()
        val from = now.plusHours(3).toString().take(5)
        val until = now.plusHours(5).toString().take(5)
        val productsFuture = listOf(product("1", "tiffins", availableFrom = from, availableUntil = until))
        val tabs = orderHotelCategoryTabs(categories, productsFuture, alwaysOpenSlots, isHotelOpen = true)
        val tiffinsTab = tabs.first { it.category.id == "tiffins" }
        assertFalse(tiffinsTab.isAvailableNow)
        assertTrue(tiffinsTab.availableFromHint?.startsWith("From ") == true)
    }

    @Test
    fun earliestAvailableFromHint_picksEarliestAcrossItems() {
        val products = listOf(
            product("1", "tiffins", availableFrom = "09:00", availableUntil = "10:00"),
            product("2", "tiffins", availableFrom = "07:00", availableUntil = "08:00"),
            product("3", "tiffins", availableFrom = "08:30", availableUntil = "09:30")
        )
        assertEquals("From 7:00 AM", earliestAvailableFromHint("tiffins", products))
    }

    @Test
    fun earliestAvailableFromHint_nullWhenNoWindowSet() {
        val products = listOf(product("1", "tiffins"))
        assertNull(earliestAvailableFromHint("tiffins", products))
    }

    @Test
    fun pickDefaultHotelCategoryId_hotelClosed_picksFirstCategory() {
        val tabs = listOf(
            CategoryTabState(category("a", "A"), isAvailableNow = false),
            CategoryTabState(category("b", "B"), isAvailableNow = false)
        )
        assertEquals("a", pickDefaultHotelCategoryId(tabs, defaultCategoryId = "b", isHotelOpen = false))
    }

    @Test
    fun pickDefaultHotelCategoryId_defaultCategorySetAndAvailable_picksIt() {
        val tabs = listOf(
            CategoryTabState(category("a", "A"), isAvailableNow = true),
            CategoryTabState(category("tiffins", "Tiffins"), isAvailableNow = true)
        )
        assertEquals("tiffins", pickDefaultHotelCategoryId(tabs, defaultCategoryId = "tiffins", isHotelOpen = true))
    }

    @Test
    fun pickDefaultHotelCategoryId_defaultCategorySetButNotAvailable_fallsBackToFirstAvailable() {
        val tabs = listOf(
            CategoryTabState(category("tiffins", "Tiffins"), isAvailableNow = false, availableFromHint = "From 7:00 AM"),
            CategoryTabState(category("snacks", "Snacks"), isAvailableNow = true)
        )
        assertEquals("snacks", pickDefaultHotelCategoryId(tabs, defaultCategoryId = "tiffins", isHotelOpen = true))
    }

    @Test
    fun pickDefaultHotelCategoryId_noDefaultSet_picksFirstAvailable() {
        val tabs = listOf(
            CategoryTabState(category("a", "A"), isAvailableNow = false),
            CategoryTabState(category("b", "B"), isAvailableNow = true)
        )
        assertEquals("b", pickDefaultHotelCategoryId(tabs, defaultCategoryId = null, isHotelOpen = true))
    }

    @Test
    fun pickDefaultHotelCategoryId_nothingAvailable_picksFirstTab() {
        val tabs = listOf(
            CategoryTabState(category("a", "A"), isAvailableNow = false),
            CategoryTabState(category("b", "B"), isAvailableNow = false)
        )
        assertEquals("a", pickDefaultHotelCategoryId(tabs, defaultCategoryId = null, isHotelOpen = true))
    }

    @Test
    fun pickDefaultHotelCategoryId_emptyTabs_returnsNull() {
        assertNull(pickDefaultHotelCategoryId(emptyList(), defaultCategoryId = null, isHotelOpen = true))
    }
}

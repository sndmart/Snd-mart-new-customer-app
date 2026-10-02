package com.example.util

import com.example.data.model.Category
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodCategoryRotationTest {

    private fun category(id: String, name: String, vendorId: String?) =
        Category(id = id, name = name, vendorId = vendorId)

    @Test
    fun pickRotatingFoodCategories_excludesCategoriesFromClosedHotels() {
        val categories = listOf(
            category("1", "Curry", vendorId = "open_hotel"),
            category("2", "Momos", vendorId = "closed_hotel")
        )
        val result = pickRotatingFoodCategories(
            categories, openVendorIds = setOf("open_hotel"), random = Random(0)
        )
        assertEquals(listOf("Curry"), result.map { it.name })
    }

    @Test
    fun pickRotatingFoodCategories_dedupesSameNameAcrossHotels() {
        val categories = listOf(
            category("1", "Curry", vendorId = "hotel_a"),
            category("2", "curry", vendorId = "hotel_b"), // same name, different case
            category("3", " Curry ", vendorId = "hotel_c") // same name, whitespace
        )
        val result = pickRotatingFoodCategories(
            categories, openVendorIds = setOf("hotel_a", "hotel_b", "hotel_c"), random = Random(0)
        )
        assertEquals(1, result.size)
    }

    @Test
    fun pickRotatingFoodCategories_capsAtMaxCount() {
        val categories = (1..20).map { category(it.toString(), "Category $it", vendorId = "hotel") }
        val result = pickRotatingFoodCategories(
            categories, openVendorIds = setOf("hotel"), maxCount = 8, random = Random(0)
        )
        assertEquals(8, result.size)
    }

    @Test
    fun pickRotatingFoodCategories_ignoresNullVendorId() {
        val categories = listOf(category("1", "Orphan Category", vendorId = null))
        val result = pickRotatingFoodCategories(categories, openVendorIds = setOf("anything"), random = Random(0))
        assertTrue(result.isEmpty())
    }

    @Test
    fun pickRotatingFoodCategories_emptyInput_returnsEmpty() {
        assertTrue(pickRotatingFoodCategories(emptyList(), emptySet()).isEmpty())
    }

    @Test
    fun vendorIdsForCategoryName_matchesCaseInsensitively_includesClosedHotels() {
        val categories = listOf(
            category("1", "Biryani", vendorId = "hotel_open"),
            category("2", "BIRYANI", vendorId = "hotel_closed"),
            category("3", "Momos", vendorId = "hotel_other")
        )
        val result = vendorIdsForCategoryName(categories, "biryani")
        assertEquals(setOf("hotel_open", "hotel_closed"), result)
    }

    @Test
    fun vendorIdsForCategoryName_noMatch_returnsEmptySet() {
        val categories = listOf(category("1", "Momos", vendorId = "hotel_a"))
        assertTrue(vendorIdsForCategoryName(categories, "Pizza").isEmpty())
    }
}

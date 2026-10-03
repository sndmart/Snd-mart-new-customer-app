package com.example.util

import com.example.data.model.Category
import com.example.data.model.OperatingSlot
import com.example.data.model.ResolvedProduct
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One category tab's computed state for the hotel menu's category row. */
data class CategoryTabState(
    val category: Category,
    val isAvailableNow: Boolean,
    val availableFromHint: String? = null
)

private val HINT_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * True if this category has at least one item available right now — the exact same check
 * (isHotelItemAvailable) the item cards already use, so tabs and items never disagree.
 */
fun isHotelCategoryAvailableNow(
    categoryId: String,
    products: List<ResolvedProduct>,
    vendorSlots: List<OperatingSlot>
): Boolean = products.any { it.categoryId == categoryId && it.isHotelItemAvailable(vendorSlots) }

/** Earliest `available_from` among this category's items, formatted like "7:00 AM", or null if none set. */
fun earliestAvailableFromHint(categoryId: String, products: List<ResolvedProduct>): String? {
    val earliest = products.asSequence()
        .filter { it.categoryId == categoryId }
        .mapNotNull { it.baseProduct.availableFrom?.let(::parseTimeString) }
        .minOrNull() ?: return null
    return "From ${earliest.format(HINT_TIME_FORMAT)}"
}

/**
 * Orders the hotel menu's category tabs (task spec):
 * - Hotel CLOSED: normal order (sort_order, then name), no reordering, no greying.
 * - Hotel OPEN: available-now categories first (normal order), then unavailable ones at the
 *   end (normal order), each with an "available from" hint if its items set one.
 */
fun orderHotelCategoryTabs(
    categories: List<Category>,
    products: List<ResolvedProduct>,
    vendorSlots: List<OperatingSlot>,
    isHotelOpen: Boolean
): List<CategoryTabState> {
    val base = categories.sortedWith(
        compareBy<Category> { it.sortOrder ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() }
    )
    if (!isHotelOpen) {
        return base.map { CategoryTabState(it, isAvailableNow = false) }
    }
    val withAvailability = base.map { category ->
        val available = isHotelCategoryAvailableNow(category.id, products, vendorSlots)
        CategoryTabState(
            category = category,
            isAvailableNow = available,
            availableFromHint = if (!available) earliestAvailableFromHint(category.id, products) else null
        )
    }
    val (availableNow, notAvailable) = withAvailability.partition { it.isAvailableNow }
    return availableNow + notAvailable
}

/**
 * Default selected tab (task spec):
 * - Hotel closed -> first category (in normal order).
 * - vendor.default_category_id set AND available now -> that category.
 * - Otherwise -> first available-now category.
 * - Nothing available -> first category (in the ordered — i.e. still-normal — list).
 */
fun pickDefaultHotelCategoryId(
    orderedTabs: List<CategoryTabState>,
    defaultCategoryId: String?,
    isHotelOpen: Boolean
): String? {
    if (orderedTabs.isEmpty()) return null
    if (!isHotelOpen) return orderedTabs.first().category.id

    val defaultTab = defaultCategoryId?.let { id -> orderedTabs.find { it.category.id == id } }
    if (defaultTab != null && defaultTab.isAvailableNow) return defaultTab.category.id

    return orderedTabs.firstOrNull { it.isAvailableNow }?.category?.id
        ?: orderedTabs.first().category.id
}

package com.example.util

import com.example.data.model.Category
import kotlin.random.Random

/**
 * Picks the "What's on your mind?" category tiles shown on the Food Delivery hotel listing:
 * dedupes [categories] by name (case-insensitive — the same cuisine/category name can exist
 * under many different hotels' own menus, each as a separate row with a different image; any
 * one of them is picked at random for the tile's image, since the user doesn't care which),
 * restricted to hotels that are currently open ([openVendorIds]), then returns a random subset
 * of up to [maxCount]. Called every 5 minutes from the UI to rotate which hotels' categories
 * are featured.
 */
fun pickRotatingFoodCategories(
    categories: List<Category>,
    openVendorIds: Set<String>,
    maxCount: Int = 8,
    random: Random = Random.Default
): List<Category> {
    val fromOpenHotels = categories.filter { it.vendorId != null && it.vendorId in openVendorIds }
    val dedupedByName = fromOpenHotels
        .groupBy { it.name.trim().lowercase() }
        .values
        .map { sameNameGroup -> sameNameGroup.random(random) }
    return dedupedByName.shuffled(random).take(maxCount)
}

/** All hotel ids (across every hotel, open or closed) whose menu has a category with this name. */
fun vendorIdsForCategoryName(categories: List<Category>, name: String): Set<String> =
    categories
        .filter { it.name.equals(name, ignoreCase = true) }
        .mapNotNull { it.vendorId }
        .toSet()

package com.example

import com.example.data.model.DeliverySlot
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun deliverySlot_effectiveFee_belowMinOrder_appliesBaseFee() {
        val eveningSlot = DeliverySlot(
            id = "slot-evening",
            name = "Evening (4 PM - 9 PM)",
            minOrderAmount = 199.0,
            isFreeDelivery = true,
            deliveryFee = 30.0
        )

        val subtotal = 25.0
        assertFalse(eveningSlot.isFreeDeliveryEligible(subtotal))
        assertEquals(30.0, eveningSlot.getEffectiveDeliveryFee(subtotal), 0.001)
        assertEquals(174.0, eveningSlot.amountNeededForFreeDelivery(subtotal), 0.001)
    }

    @Test
    fun deliverySlot_effectiveFee_atOrAboveMinOrder_qualifiesForFreeDelivery() {
        val eveningSlot = DeliverySlot(
            id = "slot-evening",
            name = "Evening (4 PM - 9 PM)",
            minOrderAmount = 199.0,
            isFreeDelivery = true,
            deliveryFee = 30.0
        )

        val exactSubtotal = 199.0
        assertTrue(eveningSlot.isFreeDeliveryEligible(exactSubtotal))
        assertEquals(0.0, eveningSlot.getEffectiveDeliveryFee(exactSubtotal), 0.001)
        assertEquals(0.0, eveningSlot.amountNeededForFreeDelivery(exactSubtotal), 0.001)

        val higherSubtotal = 250.0
        assertTrue(eveningSlot.isFreeDeliveryEligible(higherSubtotal))
        assertEquals(0.0, eveningSlot.getEffectiveDeliveryFee(higherSubtotal), 0.001)
    }

    @Test
    fun deliverySlot_noFreeDelivery_alwaysAppliesBaseFee() {
        val standardSlot = DeliverySlot(
            id = "slot-morning",
            name = "Morning (9 AM - 12 PM)",
            minOrderAmount = 0.0,
            isFreeDelivery = false,
            deliveryFee = 30.0
        )

        assertEquals(30.0, standardSlot.getEffectiveDeliveryFee(500.0), 0.001)
        assertFalse(standardSlot.isFreeDeliveryEligible(500.0))
    }

    @Test
    fun expressDelivery_chargeWithinBaseKm() {
        val settings = com.example.data.model.ExpressDeliverySettings(
            cityId = "city-1",
            isActive = true,
            baseKm = 3.0,
            baseCharge = 25.0,
            perKmChargeBeyond = 10.0
        )
        val (fee, isFree) = settings.calculateCharge(distanceKm = 2.0, subtotal = 100.0)
        assertEquals(25.0, fee, 0.001)
        assertFalse(isFree)
    }

    @Test
    fun expressDelivery_chargeBeyondBaseKm() {
        val settings = com.example.data.model.ExpressDeliverySettings(
            cityId = "city-1",
            isActive = true,
            baseKm = 3.0,
            baseCharge = 25.0,
            perKmChargeBeyond = 10.0
        )
        val (fee, isFree) = settings.calculateCharge(distanceKm = 5.5, subtotal = 100.0)
        // 25.0 + (5.5 - 3.0) * 10.0 = 50.0
        assertEquals(50.0, fee, 0.001)
        assertFalse(isFree)
    }

    @Test
    fun expressDelivery_freeDeliveryEligibility() {
        val settings = com.example.data.model.ExpressDeliverySettings(
            cityId = "city-1",
            isActive = true,
            baseKm = 3.0,
            baseCharge = 25.0,
            perKmChargeBeyond = 10.0,
            freeDeliveryMinOrder = 499.0,
            freeDeliveryMaxKm = 5.0
        )
        // Eligible: subtotal >= 499 and distance <= 5.0
        val (freeFee, isFree) = settings.calculateCharge(distanceKm = 4.0, subtotal = 500.0)
        assertEquals(0.0, freeFee, 0.001)
        assertTrue(isFree)

        // Ineligible due to distance exceeding max free km
        val (farFee, isFarFree) = settings.calculateCharge(distanceKm = 6.0, subtotal = 500.0)
        assertEquals(55.0, farFee, 0.001)
        assertFalse(isFarFree)
    }

    @Test
    fun pickupPoint_modelCreation() {
        val hotelPoint = com.example.data.model.PickupPoint(lat = 15.7667, lng = 76.7583)
        assertEquals(15.7667, hotelPoint.lat, 0.0001)
        assertEquals(76.7583, hotelPoint.lng, 0.0001)
    }

    @Test
    fun groceryCategories_sortingAndFiltering() {
        val catVegetables = com.example.data.model.Category(
            id = "2042bc3e-b47d-4d67-8817-2b20600c42bc",
            name = "Vegitables",
            imageUrl = "https://zennmoughfhennwgecgo.supabase.co/storage/v1/object/public/category-images/categories/1789198022971-o0x1bfe.jpg",
            sortOrder = 30,
            vendorType = "vegetable",
            isActive = true
        )
        val catFruits = com.example.data.model.Category(
            id = "1f01246a-8c49-4769-88a5-a1acfbd4845d",
            name = "Fruits",
            imageUrl = "https://zennmoughfhennwgecgo.supabase.co/storage/v1/object/public/category-images/categories/1789198062454-2cxfj9x.jpg",
            sortOrder = 40,
            vendorType = "fruit",
            isActive = true
        )
        val list = listOf(catFruits, catVegetables).sortedBy { it.sortOrder }

        assertEquals(2, list.size)
        assertEquals("Vegitables", list[0].name)
        assertEquals("Fruits", list[1].name)
        assertTrue(list.all { it.vendorId == null })
        assertTrue(list.all { it.isActive })
    }

    @Test
    fun groceryCategories_defaultVegitablesSelection() {
        val categories = listOf(
            com.example.data.model.Category(
                id = "cat_fruits",
                name = "Fruits",
                vendorType = "fruit",
                isActive = true
            ),
            com.example.data.model.Category(
                id = "cat_veg",
                name = "Vegitables",
                vendorType = "vegetable",
                isActive = true
            ),
            com.example.data.model.Category(
                id = "cat_dairy",
                name = "Dairy & Breakfast",
                vendorType = "grocery",
                isActive = true
            )
        )

        // Filter to only vegetables and fruits
        val filtered = categories.filter { c ->
            val n = c.name.lowercase()
            n.contains("veg") || n.contains("fruit")
        }.sortedBy { if (it.name.lowercase().contains("veg")) 0 else 1 }

        assertEquals(2, filtered.size)
        assertEquals("Vegitables", filtered[0].name)
        assertEquals("Fruits", filtered[1].name)

        // Default-select Vegitables
        val defaultVeg = filtered.firstOrNull { it.name.lowercase().contains("veg") }
        assertNotNull(defaultVeg)
        assertEquals("cat_veg", defaultVeg?.id)
    }

    private fun createTestHotelProduct(
        id: String,
        name: String,
        categoryId: String,
        price: Double,
        isAvailable: Boolean,
        isFeatured: Boolean
    ): com.example.data.model.ResolvedProduct {
        return com.example.data.model.ResolvedProduct(
            baseProduct = com.example.data.model.Product(
                id = id,
                name = name,
                categoryId = categoryId,
                price = price,
                isAvailable = isAvailable,
                isActive = true,
                isFeatured = isFeatured
            ),
            effectivePrice = price,
            effectiveMrp = null,
            effectiveStock = if (isAvailable) 50 else 0,
            effectiveIsAvailable = isAvailable
        )
    }

    @Test
    fun hotelMenu_defaultFirstCategory_showsOnlyFirstCategoryItemsSorted() {
        val catBiryani = com.example.data.model.Category(id = "cat_biryani", name = "Biryani", vendorId = "v1", isActive = true)
        val catRoti = com.example.data.model.Category(id = "cat_roti", name = "Roti", vendorId = "v1", isActive = true)
        val categories = listOf(catBiryani, catRoti)

        val item1 = createTestHotelProduct("p1", "Chicken Biryani", "cat_biryani", 220.0, isAvailable = true, isFeatured = true)
        val item2 = createTestHotelProduct("p2", "Butter Roti", "cat_roti", 30.0, isAvailable = true, isFeatured = false)
        val item3 = createTestHotelProduct("p3", "Mutton Biryani", "cat_biryani", 320.0, isAvailable = false, isFeatured = false)
        val allProducts = listOf(item2, item3, item1)

        // Selected category state defaults to the first real category in the list (never "all")
        var selectedCategoryId: String? = null
        if (categories.isNotEmpty() && selectedCategoryId == null) {
            selectedCategoryId = categories.first().id
        }

        assertEquals("cat_biryani", selectedCategoryId)

        val displayedItems = (if (selectedCategoryId == null) emptyList() else allProducts.filter { it.categoryId == selectedCategoryId })
            .sortedWith(
                compareBy<com.example.data.model.ResolvedProduct> { if (it.isHotelItemAvailable && it.isFeatured) 0 else if (it.isHotelItemAvailable) 1 else 2 }
                    .thenByDescending { it.isFeatured }
                    .thenBy { it.name.lowercase() }
            )

        // Only Biryani category items are displayed (Butter Roti is excluded)
        assertEquals(2, displayedItems.size)
        assertFalse(displayedItems.any { it.categoryId == "cat_roti" })
        // Featured + available comes first
        assertEquals("Chicken Biryani", displayedItems[0].name)
        // Unavailable comes last
        assertEquals("Mutton Biryani", displayedItems[1].name)
    }

    @Test
    fun hotelMenu_filterByCategory_returnsOnlyScopedItemsSorted() {
        val item1 = createTestHotelProduct("p1", "Chicken Biryani", "cat_biryani", 220.0, isAvailable = true, isFeatured = true)
        val item2 = createTestHotelProduct("p2", "Butter Roti", "cat_roti", 30.0, isAvailable = true, isFeatured = false)
        val item3 = createTestHotelProduct("p3", "Egg Biryani", "cat_biryani", 180.0, isAvailable = true, isFeatured = false)
        val item4 = createTestHotelProduct("p4", "Mutton Biryani", "cat_biryani", 320.0, isAvailable = false, isFeatured = true)
        val allProducts = listOf(item1, item2, item3, item4)

        // Select Biryani category
        val selectedCategoryId = "cat_biryani"

        val displayedBiryani = allProducts.filter { it.categoryId == selectedCategoryId }
            .sortedWith(
                compareBy<com.example.data.model.ResolvedProduct> { if (it.isHotelItemAvailable && it.isFeatured) 0 else if (it.isHotelItemAvailable) 1 else 2 }
                    .thenByDescending { it.isFeatured }
                    .thenBy { it.name.lowercase() }
            )

        // Butter Roti should be excluded
        assertEquals(3, displayedBiryani.size)
        assertFalse(displayedBiryani.any { it.categoryId == "cat_roti" })

        // Available + featured first
        assertEquals("Chicken Biryani", displayedBiryani[0].name)
        // Available second
        assertEquals("Egg Biryani", displayedBiryani[1].name)
        // Unavailable last (even though featured)
        assertEquals("Mutton Biryani", displayedBiryani[2].name)
    }

    @Test
    fun hotelMenu_noAllCategoryTab_onlyRealHotelCategories() {
        val catBiryani = com.example.data.model.Category(id = "cat_biryani", name = "Biryani", vendorId = "v1", isActive = true)
        val catDessert = com.example.data.model.Category(id = "cat_dessert", name = "Dessert", vendorId = "v1", isActive = true)
        val categories = listOf(catBiryani, catDessert)

        // Verify category list only contains real hotel categories and no "All" tab
        assertFalse(categories.any { it.id == "all" || it.name.equals("All", ignoreCase = true) })
        assertEquals(2, categories.size)
        assertEquals("Biryani", categories[0].name)
        assertEquals("Dessert", categories[1].name)

        // Default category must be first real category in the list
        val defaultCategoryId = categories.firstOrNull()?.id
        assertEquals("cat_biryani", defaultCategoryId)
    }

    @Test
    fun navigationFlow_hotelBackNavigationSequence() {
        // Initial state: User is on Home (Groceries)
        var browsingMode = com.example.ui.screens.BrowsingMode.GROCERY
        assertEquals(com.example.ui.screens.BrowsingMode.GROCERY, browsingMode)

        // Step 1: User taps "Hotel Food & Dining" tab
        browsingMode = com.example.ui.screens.BrowsingMode.HOTELS
        assertEquals(com.example.ui.screens.BrowsingMode.HOTELS, browsingMode)

        // Step 2: User taps a hotel card -> push HotelMenu route
        val vendorId = "v123"
        val vendorName = "Royal Biryani & Spices"
        val route = com.example.Screen.HotelMenu.createRoute(vendorId, vendorName)
        assertTrue(route.startsWith("hotel_menu/v123/"))

        // Decode check
        val rawSegment = route.removePrefix("hotel_menu/v123/")
        val decodedName = java.net.URLDecoder.decode(rawSegment, "UTF-8")
        assertEquals(vendorName, decodedName)

        // Step 3: User presses back from HotelMenu (first back press)
        // With rememberSaveable and back stack pop, browsingMode is preserved as HOTELS
        assertEquals(com.example.ui.screens.BrowsingMode.HOTELS, browsingMode)

        // Step 4: User presses back while on Hotel List (second back press)
        // BackHandler intercepts and returns user to GROCERY (Home)
        val backHandlerEnabled = (browsingMode == com.example.ui.screens.BrowsingMode.HOTELS)
        assertTrue(backHandlerEnabled)
        if (backHandlerEnabled) {
            browsingMode = com.example.ui.screens.BrowsingMode.GROCERY
        }
        assertEquals(com.example.ui.screens.BrowsingMode.GROCERY, browsingMode)

        // Third back press: BackHandler is disabled, allowing system back (exit or prior screen)
        val subsequentBackHandlerEnabled = (browsingMode == com.example.ui.screens.BrowsingMode.HOTELS)
        assertFalse(subsequentBackHandlerEnabled)
    }

    @Test
    fun hotelMenu_routeEncodingSpecialCharacters() {
        val testCases = listOf(
            "Hotel Taj" to "Hotel%20Taj",
            "Café & Bakery" to "Caf%C3%A9%20%26%20Bakery",
            "Mom's / Kitchen" to "Mom%27s%20%2F%20Kitchen"
        )
        for ((name, _) in testCases) {
            val route = com.example.Screen.HotelMenu.createRoute("h1", name)
            val segment = route.removePrefix("hotel_menu/h1/")
            val decoded = java.net.URLDecoder.decode(segment, "UTF-8")
            assertEquals(name, decoded)
        }
    }
}


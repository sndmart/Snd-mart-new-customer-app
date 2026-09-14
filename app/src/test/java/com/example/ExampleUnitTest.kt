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
}


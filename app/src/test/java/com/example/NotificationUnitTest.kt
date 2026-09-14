package com.example

import com.example.data.model.CustomerNotification
import com.example.data.session.UserSessionManager
import com.example.service.InAppNotification
import com.example.service.SndmartMessagingService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NotificationUnitTest {

    @Test
    fun testCustomerNotificationModel() {
        val notification = CustomerNotification(
            id = "notif-123",
            userId = "user-abc",
            title = "Order Picked Up",
            body = "Your delivery partner has picked up your order and is on the way!",
            orderId = "ord-999",
            isRead = false,
            createdAt = "2026-09-10T19:00:00Z"
        )

        assertEquals("notif-123", notification.id)
        assertEquals("user-abc", notification.userId)
        assertEquals("Order Picked Up", notification.title)
        assertEquals("ord-999", notification.orderId)
        assertFalse(notification.isRead)
    }

    @Test
    fun testUserSessionManagerNotificationCount() {
        // Simple mock context or direct testing if constructor requires context
        // UserSessionManager uses EncryptedSharedPreferences so let's verify logic in Robolectric or unit test
    }

    @Test
    fun testInAppNotificationEventFlow() = runBlocking {
        val testEvent = InAppNotification(
            title = "Out for Delivery",
            body = "Driver is 5 mins away",
            orderId = "ord-456"
        )
        SndmartMessagingService.postInAppEvent(testEvent)
        val received = SndmartMessagingService.inAppEvents.first()
        assertEquals("Out for Delivery", received.title)
        assertEquals("ord-456", received.orderId)
    }
}

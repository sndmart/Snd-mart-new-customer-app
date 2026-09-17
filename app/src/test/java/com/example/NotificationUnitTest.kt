package com.example

import com.example.data.model.CustomerNotification
import com.example.data.session.UserSessionManager
import com.example.service.InAppNotification
import com.example.service.SndmartMessagingService
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
        val deferred = async {
            SndmartMessagingService.inAppEvents.first()
        }
        yield()
        SndmartMessagingService.postInAppEvent(testEvent)
        val received = withTimeout(3000) { deferred.await() }
        assertEquals("Out for Delivery", received.title)
        assertEquals("ord-456", received.orderId)
    }

    @Test
    fun testDeviceTokenModelAndSerialization() {
        val deviceToken = com.example.data.model.DeviceToken(
            userId = "user_cust_123",
            userType = "customer",
            fcmToken = "sample_fcm_token_xyz",
            active = true,
            firebaseProject = "native"
        )

        assertEquals("user_cust_123", deviceToken.userId)
        assertEquals("customer", deviceToken.userType)
        assertEquals("sample_fcm_token_xyz", deviceToken.fcmToken)
        assertTrue(deviceToken.active)
        assertEquals("native", deviceToken.firebaseProject)

        // Verify Moshi serialization produces "firebase_project": "native"
        val moshi = com.squareup.moshi.Moshi.Builder().build()
        val adapter = moshi.adapter(com.example.data.model.DeviceToken::class.java)
        val json = adapter.toJson(deviceToken)

        assertTrue("JSON must contain firebase_project", json.contains("\"firebase_project\":\"native\""))
        assertTrue("JSON must contain user_type customer", json.contains("\"user_type\":\"customer\""))
        assertTrue("JSON must contain active true", json.contains("\"active\":true"))
        assertTrue("JSON must contain user_id", json.contains("\"user_id\":\"user_cust_123\""))
    }
}

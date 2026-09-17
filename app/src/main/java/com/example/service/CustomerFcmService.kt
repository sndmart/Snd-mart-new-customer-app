package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.OrderDetailActivity
import com.example.R
import com.example.SndmartApp
import com.example.data.repository.SndmartRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

data class InAppNotification(
    val title: String,
    val body: String,
    val orderId: String?
)

/**
 * Top-level helper function for registering the customer FCM token to Supabase `device_tokens`.
 * Uses firebase_project = "native" and onConflict = "user_id,user_type".
 */
suspend fun registerCustomerFcmToken(token: String): Result<Unit> {
    val app = SndmartApp.instance
    val userId = app.sessionManager.userId.value ?: app.sessionManager.getUserId()
    if (userId.isNullOrBlank()) {
        Log.d("CustomerFcmService", "No active user session; skipping FCM registration until login.")
        return Result.failure(IllegalStateException("No current user logged in"))
    }
    val repo = SndmartRepository(sessionManager = app.sessionManager)
    return repo.registerCustomerFcmToken(token)
}

open class CustomerFcmService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val orderId = message.data["orderId"]
            ?: message.data["order_id"]
            ?: message.data["id"]
        val title = message.data["title"]
            ?: message.notification?.title
            ?: "Sndmart"
        val body = message.data["message"]
            ?: message.data["body"]
            ?: message.notification?.body
            ?: "Your order has an update"

        Log.d(TAG, "Customer FCM message received: orderId=$orderId, title=$title, body=$body")

        // Update in-memory unread count
        val app = application as? SndmartApp
        app?.sessionManager?.let { sm ->
            sm.setUnreadNotificationCount(sm.unreadNotificationCount.value + 1)
        }

        // Broadcast in-app event for foreground banners
        _inAppEvents.tryEmit(InAppNotification(title, body, orderId))

        // Create intent opening OrderDetailActivity
        val intent = Intent(this, OrderDetailActivity::class.java).apply {
            putExtra("order_id", orderId)
            if (orderId != null) {
                putExtra("orderId", orderId)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val notificationId = orderId?.hashCode() ?: System.currentTimeMillis().toInt()
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "order_updates"
        val channel = NotificationChannel(
            channelId,
            "Order Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Order status updates, dispatch and delivery progress"
            enableVibration(true)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager?.notify(notificationId, notification)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Customer FCM onNewToken: $token")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                registerCustomerFcmToken(token)
            } catch (e: Exception) {
                Log.e(TAG, "Error registering FCM token onNewToken", e)
            }
        }
    }

    companion object {
        private const val TAG = "CustomerFcmService"
        private val _inAppEvents = MutableSharedFlow<InAppNotification>(extraBufferCapacity = 5)
        val inAppEvents: SharedFlow<InAppNotification> = _inAppEvents.asSharedFlow()

        fun postInAppEvent(event: InAppNotification): Boolean = _inAppEvents.tryEmit(event)
    }
}

/**
 * Backward compatibility alias for any existing references.
 */
class SndmartMessagingService : CustomerFcmService() {
    companion object {
        val inAppEvents: SharedFlow<InAppNotification> get() = CustomerFcmService.inAppEvents
        fun postInAppEvent(event: InAppNotification): Boolean = CustomerFcmService.postInAppEvent(event)
    }
}

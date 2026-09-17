package com.example

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Trampoline Activity for Order push notifications.
 * Launched when the user taps an order update notification.
 * Extracts the order_id and forwards execution to MainActivity.
 */
class OrderDetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val orderId = intent.getStringExtra("order_id")
            ?: intent.getStringExtra("orderId")
            ?: intent.getStringExtra("id")
            ?: intent.data?.lastPathSegment

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!orderId.isNullOrBlank()) {
                putExtra("order_id", orderId)
                putExtra("orderId", orderId)
                data = Uri.parse("sndmart://order/$orderId")
            }
        }
        startActivity(mainIntent)
        finish()
    }
}

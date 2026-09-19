package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.session.UserSessionManager
import java.io.File

class SndmartApp : Application(), ImageLoaderFactory {

    lateinit var sessionManager: UserSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureWebViewCacheDirs()
        sessionManager = UserSessionManager(this)
        createNotificationChannel()
        initFirebaseSafety()
        com.example.data.remote.GoogleMapsConfig.initializePlaces(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 25% of available app memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // 2% of device storage
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private fun ensureWebViewCacheDirs() {
        try {
            val codeCacheDir = File(cacheDir, "WebView/Default/HTTP Cache/Code Cache")
            File(codeCacheDir, "js").mkdirs()
            File(codeCacheDir, "wasm").mkdirs()
        } catch (e: Throwable) {
            // Safe fallback if directory creation is restricted
        }
    }

    private fun initFirebaseSafety() {
        try {
            // Keep FCM auto-init disabled by default so the background SyncTask does not run
            // on startup in environments without active FCM registration or Play account.
            com.google.firebase.messaging.FirebaseMessaging.getInstance().isAutoInitEnabled = false
        } catch (e: Throwable) {
            // FirebaseApp or Play Services not present/initialized
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_ORDERS,
                "Order Status Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for active Sndmart order progress, dispatch, and delivery"
                enableVibration(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID_ORDERS = "sndmart_order_notifications"
        lateinit var instance: SndmartApp
            private set
    }
}

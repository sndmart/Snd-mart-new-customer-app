package com.example.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class GpsFailureReason {
    TIMEOUT,
    NO_PROVIDER,
    PLAY_SERVICES_UNAVAILABLE,
    PERMISSION_DENIED
}

sealed class GpsState {
    object Idle : GpsState()
    object RequestingPermission : GpsState()
    object PermissionDenied : GpsState()
    object GpsDisabled : GpsState()
    data class Calibrating(val attempt: Int, val currentAccuracyMeters: Float? = null, val message: String) : GpsState()
    data class Located(val location: Location, val accuracyMeters: Float, val isHighAccuracy: Boolean) : GpsState()
    data class Failure(
        val message: String,
        val canRetry: Boolean = true,
        val reason: GpsFailureReason = GpsFailureReason.TIMEOUT
    ) : GpsState()
}

sealed class LocationSettingsResult {
    object Satisfied : LocationSettingsResult()
    data class Resolvable(val exception: ResolvableApiException) : LocationSettingsResult()
    data class NotResolvable(val exception: Exception) : LocationSettingsResult()
}

object HighAccuracyLocationManager {
    private const val TAG = "HighAccuracyGPS"

    // High accuracy standards (typical outdoor GPS fix is 3m - 25m)
    const val DESIRED_ACCURACY_METERS = 25.0f
    const val ACCEPTABLE_ACCURACY_METERS = 50.0f
    const val PHASE1_TIMEOUT_MILLIS = 5000L
    const val PHASE2_TIMEOUT_MILLIS = 15000L
    private const val MAX_CALIBRATION_ATTEMPTS = 5
    private const val LAST_LOCATION_MAX_AGE_MS = 120_000L // 2 minutes

    fun isHighAccuracy(accuracyMeters: Float?): Boolean {
        return accuracyMeters != null && accuracyMeters <= DESIRED_ACCURACY_METERS
    }

    fun isAcceptableAccuracy(accuracyMeters: Float?): Boolean {
        return accuracyMeters != null && accuracyMeters <= ACCEPTABLE_ACCURACY_METERS
    }

    fun hasFineLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAnyLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isGpsProviderEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    fun isAnyLocationProviderEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    suspend fun checkLocationSettings(context: Context): LocationSettingsResult {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
        val client = LocationServices.getSettingsClient(context)
        return try {
            suspendCancellableCoroutine { cont ->
                client.checkLocationSettings(builder.build())
                    .addOnSuccessListener {
                        cont.resume(LocationSettingsResult.Satisfied)
                    }
                    .addOnFailureListener { exception ->
                        if (exception is ResolvableApiException) {
                            cont.resume(LocationSettingsResult.Resolvable(exception))
                        } else {
                            cont.resume(LocationSettingsResult.NotResolvable(exception))
                        }
                    }
            }
        } catch (e: Exception) {
            LocationSettingsResult.NotResolvable(e)
        }
    }

    fun openLocationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open location settings", e)
        }
    }

    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }

    /**
     * Exact GPS detection:
     * - Checks location permission and active location providers.
     * - Phase 1: Immediate getCurrentLocation (5 sec timeout).
     * - Phase 2: Active high-accuracy updates without wait-for-accuracy blocking (15 sec timeout).
     * - Immediately emits any available fix as GpsState.Located and refines as accuracy improves.
     * - Falls back to fusedClient.lastLocation if fresh (under 2 minutes old).
     * - Returns exact Location with latitude, longitude, and accuracy or detailed Failure state.
     */
    @SuppressLint("MissingPermission")
    suspend fun getAccurateGpsLocation(
        context: Context,
        onProgress: (status: GpsState) -> Unit
    ): Location? = withContext(Dispatchers.Main) {
        if (!hasAnyLocationPermission(context)) {
            onProgress(GpsState.PermissionDenied)
            return@withContext null
        }

        if (!isAnyLocationProviderEnabled(context)) {
            onProgress(GpsState.GpsDisabled)
            return@withContext null
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        var bestLocation: Location? = null
        var bestAccuracy = Float.MAX_VALUE

        onProgress(GpsState.Calibrating(1, null, "Acquiring High Accuracy GPS signal..."))

        // Phase 1: Try immediate getCurrentLocation with PRIORITY_HIGH_ACCURACY (5s timeout)
        val cts = CancellationTokenSource()
        val firstFix = try {
            withTimeoutOrNull(PHASE1_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Location?> { cont ->
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .addOnSuccessListener { loc -> cont.resume(loc) }
                        .addOnFailureListener { cont.resume(null) }
                    cont.invokeOnCancellation { cts.cancel() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during first GPS fix: ${e.message}")
            null
        }

        if (firstFix != null && firstFix.hasAccuracy()) {
            bestLocation = firstFix
            bestAccuracy = firstFix.accuracy
            logDebug("First fix received: lat=${firstFix.latitude}, lng=${firstFix.longitude}, acc=$bestAccuracy m")

            if (bestAccuracy <= DESIRED_ACCURACY_METERS) {
                // High accuracy achieved immediately
                onProgress(GpsState.Located(firstFix, bestAccuracy, isHighAccuracy = true))
                return@withContext firstFix
            } else {
                // Emit initial fix immediately so user sees map update, while calibrating for better precision
                onProgress(GpsState.Located(firstFix, bestAccuracy, isHighAccuracy = false))
                onProgress(GpsState.Calibrating(1, bestAccuracy, "Calibrating GPS accuracy (±${bestAccuracy.toInt()}m)... waiting for satellite lock"))
            }
        }

        // Phase 2: Request active GPS updates (15s timeout) with setWaitForAccurateLocation(false)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(1000L)
            .setWaitForAccurateLocation(false)
            .setMinUpdateDistanceMeters(0f)
            .build()

        val accurateFix = withTimeoutOrNull(PHASE2_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine<Location?> { cont ->
                var updatesCount = 0
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        for (loc in result.locations) {
                            updatesCount++
                            val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
                            logDebug("GPS update #$updatesCount: lat=${loc.latitude}, lng=${loc.longitude}, acc=$acc")

                            if (acc < bestAccuracy || bestLocation == null) {
                                bestLocation = loc
                                bestAccuracy = acc
                            }

                            val isHigh = acc <= DESIRED_ACCURACY_METERS
                            // Immediately emit every fix so UI updates in real-time
                            onProgress(GpsState.Located(loc, acc, isHighAccuracy = isHigh))

                            if (acc <= DESIRED_ACCURACY_METERS) {
                                // Locked on target with high precision
                                fusedClient.removeLocationUpdates(this)
                                if (cont.isActive) cont.resume(loc)
                                return
                            } else {
                                onProgress(GpsState.Calibrating(
                                    attempt = updatesCount + 1,
                                    currentAccuracyMeters = bestAccuracy,
                                    message = "Calibrating GPS (±${bestAccuracy.toInt()}m)... optimizing doorstep fix"
                                ))
                            }

                            if (updatesCount >= MAX_CALIBRATION_ATTEMPTS && bestAccuracy <= ACCEPTABLE_ACCURACY_METERS) {
                                fusedClient.removeLocationUpdates(this)
                                if (cont.isActive) cont.resume(bestLocation)
                                return
                            }
                        }
                    }

                    override fun onLocationAvailability(avail: LocationAvailability) {
                        if (!avail.isLocationAvailable && bestLocation == null) {
                            Log.w(TAG, "GPS provider temporarily unavailable")
                        }
                    }
                }

                fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
                cont.invokeOnCancellation {
                    fusedClient.removeLocationUpdates(callback)
                }
            }
        }

        var finalLoc = accurateFix ?: bestLocation

        // Fallback: If no fresh fix acquired, check lastLocation if under 2 minutes old
        if (finalLoc == null) {
            val lastLoc = try {
                suspendCancellableCoroutine<Location?> { cont ->
                    fusedClient.lastLocation
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception reading lastLocation: ${e.message}")
                null
            }

            if (lastLoc != null) {
                val ageMs = System.currentTimeMillis() - lastLoc.time
                if (ageMs in 0..LAST_LOCATION_MAX_AGE_MS) {
                    finalLoc = lastLoc
                    bestAccuracy = if (lastLoc.hasAccuracy()) lastLoc.accuracy else Float.MAX_VALUE
                    logDebug("Used fresh lastLocation fallback: acc=$bestAccuracy m, age=${ageMs / 1000}s")
                }
            }
        }

        if (finalLoc != null) {
            val acc = if (finalLoc.hasAccuracy()) finalLoc.accuracy else bestAccuracy.coerceAtMost(50f)
            val isHigh = acc <= DESIRED_ACCURACY_METERS
            onProgress(GpsState.Located(finalLoc, acc, isHighAccuracy = isHigh))
            finalLoc
        } else {
            val reason = if (!isAnyLocationProviderEnabled(context)) {
                GpsFailureReason.NO_PROVIDER
            } else {
                GpsFailureReason.TIMEOUT
            }
            val errorMsg = if (reason == GpsFailureReason.NO_PROVIDER) {
                "Location services are turned off. Please turn on GPS."
            } else {
                "Unable to acquire accurate GPS fix. Please move near an open window or outdoors and retry."
            }
            onProgress(GpsState.Failure(errorMsg, canRetry = true, reason = reason))
            null
        }
    }
}

package com.example.ui.components.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState

/** Minimal camera-control surface OsmMapImpl fulfills, so [SndmartMapState] never imports osmdroid types. */
internal interface OsmCameraController {
    suspend fun animateTo(lat: Double, lng: Double, zoom: Float, durationMs: Int)
    suspend fun animateToBounds(points: List<GeoLatLng>, paddingPx: Int)
}

internal data class CameraAnimationRequest(val lat: Double, val lng: Double, val zoom: Float, val durationMs: Int)

/**
 * Provider-agnostic camera controller for [SndmartMap]. Screens hold one of these (via
 * [rememberSndmartMapState]) instead of a Google-specific CameraPositionState, and call
 * [animateTo] / [animateToBounds] the same way no matter which provider is rendering.
 */
class SndmartMapState internal constructor(
    initialCenter: GeoLatLng,
    initialZoom: Float
) {
    var center by mutableStateOf(initialCenter)
        internal set
    var zoom by mutableStateOf(initialZoom)
        internal set
    var isMoving by mutableStateOf(false)
        internal set

    // Bound by whichever impl (Google or OSM) is currently composed on screen.
    internal var googleCameraPositionState: CameraPositionState? = null
    internal var osmController: OsmCameraController? = null

    private var pendingAnimation: CameraAnimationRequest? = null

    suspend fun animateTo(lat: Double, lng: Double, zoom: Float? = null, durationMs: Int = 900) {
        val targetZoom = zoom ?: this.zoom
        val gms = googleCameraPositionState
        val osm = osmController
        when {
            gms != null -> gms.animate(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), targetZoom), durationMs)
            osm != null -> osm.animateTo(lat, lng, targetZoom, durationMs)
            else -> {
                // Neither impl mounted yet (very first frame) — remember it so whichever impl
                // mounts next applies it immediately instead of silently dropping it.
                pendingAnimation = CameraAnimationRequest(lat, lng, targetZoom, durationMs)
            }
        }
        center = GeoLatLng(lat, lng)
        this.zoom = targetZoom
    }

    suspend fun animateToBounds(points: List<GeoLatLng>, paddingPx: Int = 120, fallbackZoom: Float = 15f) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            animateTo(points[0].lat, points[0].lng, fallbackZoom)
            return
        }
        val gms = googleCameraPositionState
        val osm = osmController
        if (gms != null) {
            try {
                val builder = LatLngBounds.builder()
                points.forEach { builder.include(LatLng(it.lat, it.lng)) }
                gms.animate(CameraUpdateFactory.newLatLngBounds(builder.build(), paddingPx))
            } catch (e: Exception) {
                animateTo(points[0].lat, points[0].lng, fallbackZoom)
            }
        } else if (osm != null) {
            osm.animateToBounds(points, paddingPx)
        }
    }

    internal fun consumePendingAnimation(): CameraAnimationRequest? {
        val p = pendingAnimation
        pendingAnimation = null
        return p
    }
}

@Composable
fun rememberSndmartMapState(initialCenter: GeoLatLng, initialZoom: Float = 15f): SndmartMapState {
    return remember { SndmartMapState(initialCenter, initialZoom) }
}

package com.example.ui.components.map

import androidx.compose.ui.graphics.Color

/** Provider-agnostic lat/lng — screens never hold a Google or osmdroid coordinate type directly. */
data class GeoLatLng(val lat: Double, val lng: Double)

enum class SndmartMapType { NORMAL, SATELLITE }

enum class MarkerTint { RED, BLUE, GREEN, ORANGE }

data class SndmartMarker(
    val id: String,
    val position: GeoLatLng,
    val title: String? = null,
    val snippet: String? = null,
    val tint: MarkerTint = MarkerTint.RED,
    val draggable: Boolean = false,
    val onDragEnd: ((GeoLatLng) -> Unit)? = null
)

data class SndmartCircleSpec(
    val center: GeoLatLng,
    val radiusMeters: Double,
    val fillColor: Color,
    val strokeColor: Color,
    val strokeWidthPx: Float = 2.5f
)

data class SndmartPolylineSpec(
    val points: List<GeoLatLng>,
    val color: Color,
    val widthPx: Float = 8f
)

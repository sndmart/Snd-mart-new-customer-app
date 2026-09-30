package com.example.ui.components.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

private fun tintHue(tint: MarkerTint): Float = when (tint) {
    MarkerTint.RED -> BitmapDescriptorFactory.HUE_RED
    MarkerTint.BLUE -> BitmapDescriptorFactory.HUE_AZURE
    MarkerTint.GREEN -> BitmapDescriptorFactory.HUE_GREEN
    MarkerTint.ORANGE -> BitmapDescriptorFactory.HUE_ORANGE
}

@Composable
private fun GoogleMarkerItem(m: SndmartMarker) {
    val markerState = rememberMarkerState(key = m.id, position = LatLng(m.position.lat, m.position.lng))
    if (!markerState.isDragging) {
        markerState.position = LatLng(m.position.lat, m.position.lng)
    }
    LaunchedEffect(markerState.isDragging) {
        if (!markerState.isDragging) {
            m.onDragEnd?.invoke(GeoLatLng(markerState.position.latitude, markerState.position.longitude))
        }
    }
    Marker(
        state = markerState,
        title = m.title,
        snippet = m.snippet,
        draggable = m.draggable,
        icon = BitmapDescriptorFactory.defaultMarker(tintHue(m.tint))
    )
}

@Composable
internal fun GoogleMapImpl(
    modifier: Modifier,
    testTag: String,
    state: SndmartMapState,
    markers: List<SndmartMarker>,
    circle: SndmartCircleSpec?,
    polyline: SndmartPolylineSpec?,
    myLocationEnabled: Boolean,
    mapType: SndmartMapType,
    onMapClick: ((GeoLatLng) -> Unit)?,
    onCameraIdle: ((GeoLatLng) -> Unit)?,
    onMapLoaded: () -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(state.center.lat, state.center.lng), state.zoom)
    }

    DisposableEffect(Unit) {
        state.googleCameraPositionState = cameraPositionState
        onDispose { state.googleCameraPositionState = null }
    }

    LaunchedEffect(Unit) {
        state.consumePendingAnimation()?.let { req ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(req.lat, req.lng), req.zoom),
                req.durationMs
            )
        }
    }

    LaunchedEffect(cameraPositionState.isMoving) {
        state.isMoving = cameraPositionState.isMoving
        if (!cameraPositionState.isMoving) {
            val target = cameraPositionState.position.target
            onCameraIdle?.invoke(GeoLatLng(target.latitude, target.longitude))
        }
    }

    GoogleMap(
        modifier = modifier.testTag(testTag),
        cameraPositionState = cameraPositionState,
        uiSettings = remember {
            MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                compassEnabled = true
            )
        },
        properties = remember(myLocationEnabled, mapType) {
            MapProperties(
                isMyLocationEnabled = myLocationEnabled,
                mapType = if (mapType == SndmartMapType.SATELLITE) MapType.HYBRID else MapType.NORMAL
            )
        },
        onMapClick = { latLng -> onMapClick?.invoke(GeoLatLng(latLng.latitude, latLng.longitude)) },
        onMapLoaded = onMapLoaded
    ) {
        circle?.let {
            Circle(
                center = LatLng(it.center.lat, it.center.lng),
                radius = it.radiusMeters,
                fillColor = it.fillColor,
                strokeColor = it.strokeColor,
                strokeWidth = it.strokeWidthPx
            )
        }
        polyline?.let {
            Polyline(
                points = it.points.map { p -> LatLng(p.lat, p.lng) },
                color = it.color,
                width = it.widthPx
            )
        }
        markers.forEach { m -> GoogleMarkerItem(m) }
    }
}

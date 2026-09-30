package com.example.ui.components.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.maps.MapProvider
import com.example.data.maps.MapProviderState
import kotlinx.coroutines.delay

/**
 * Dual-provider map: renders Google Maps when [mapProviderState] resolves to
 * [MapProvider.GOOGLE], otherwise OpenStreetMap (osmdroid). Drop-in replacement for a direct
 * `GoogleMap { ... }` call — same marker/circle/click/camera-idle surface either way.
 *
 * Camera is driven externally via [state] ([rememberSndmartMapState] + `state.animateTo(...)`),
 * matching the existing `cameraPositionState.animate(...)` call sites this replaces.
 */
@Composable
fun SndmartMap(
    modifier: Modifier = Modifier,
    state: SndmartMapState,
    mapProviderState: MapProviderState,
    markers: List<SndmartMarker> = emptyList(),
    circle: SndmartCircleSpec? = null,
    polyline: SndmartPolylineSpec? = null,
    myLocationEnabled: Boolean = false,
    mapType: SndmartMapType = SndmartMapType.NORMAL,
    osmTileUrlBase: String? = null,
    osmSatelliteTileUrlBase: String? = null,
    onMapClick: ((GeoLatLng) -> Unit)? = null,
    onCameraIdle: ((GeoLatLng) -> Unit)? = null,
    testTag: String = "sndmart_map"
) {
    Box(modifier = modifier) {
        when (mapProviderState.provider) {
            MapProvider.GOOGLE -> {
                var loadReported by remember(state) { mutableStateOf(false) }

                // No official Google Maps Compose "auth/load failed" callback exists, so we
                // treat a missing onMapLoaded within this window as a load failure and fall
                // back to OSM (see task spec item B).
                LaunchedEffect(state) {
                    delay(8000)
                    if (!loadReported) {
                        mapProviderState.reportGoogleFailure("onMapLoaded timeout (8s)")
                    }
                }

                GoogleMapImpl(
                    modifier = Modifier.fillMaxSize(),
                    testTag = testTag,
                    state = state,
                    markers = markers,
                    circle = circle,
                    polyline = polyline,
                    myLocationEnabled = myLocationEnabled,
                    mapType = mapType,
                    onMapClick = onMapClick,
                    onCameraIdle = onCameraIdle,
                    onMapLoaded = { loadReported = true }
                )
            }

            MapProvider.OSM -> {
                OsmMapImpl(
                    modifier = Modifier.fillMaxSize(),
                    testTag = testTag,
                    state = state,
                    markers = markers,
                    circle = circle,
                    polyline = polyline,
                    myLocationEnabled = myLocationEnabled,
                    mapType = mapType,
                    tileUrlBase = osmTileUrlBase,
                    satelliteTileUrlBase = osmSatelliteTileUrlBase,
                    onMapClick = onMapClick,
                    onCameraIdle = onCameraIdle
                )
            }
        }

        AnimatedVisibility(
            visible = mapProviderState.switchedMessage != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = mapProviderState.switchedMessage ?: "",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

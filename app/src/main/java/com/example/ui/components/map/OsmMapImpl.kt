package com.example.ui.components.map

import android.graphics.Paint
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File

/**
 * IMPORTANT — production tile usage: when [tileUrlBase]/[satelliteTileUrlBase] are not
 * configured, this falls back to the default OSM "standard" tile server
 * (tile.openstreetmap.org via TileSourceFactory.MAPNIK), which is run by volunteers under
 * the OSMF tile usage policy (https://operations.osmfoundation.org/policies/tiles/) and
 * explicitly disallows heavy production app traffic. Before this OSM fallback sees real
 * production volume, set the `osm_tile_url` app_setting to a paid/self-hosted provider
 * (MapTiler, Stadia Maps, Thunderforest, or a self-hosted tileserver-gl) — see
 * PLAY_MAPS_NOTES.md for details.
 */
private fun buildTileSource(baseUrl: String?, fallback: ITileSource = TileSourceFactory.MAPNIK): ITileSource {
    if (baseUrl.isNullOrBlank()) return fallback
    return try {
        XYTileSource("SndmartCustomOsmTiles", 0, 20, 256, ".png", arrayOf(baseUrl))
    } catch (e: Exception) {
        fallback
    }
}

private fun tintToColorInt(tint: MarkerTint): Int = when (tint) {
    MarkerTint.RED -> android.graphics.Color.rgb(211, 47, 47)
    MarkerTint.BLUE -> android.graphics.Color.rgb(30, 136, 229)
    MarkerTint.GREEN -> android.graphics.Color.rgb(67, 160, 71)
    MarkerTint.ORANGE -> android.graphics.Color.rgb(251, 140, 0)
}

/** A simple colored dot marker icon — avoids depending on osmdroid's bundled drawable resource ids. */
private fun dotDrawable(colorInt: Int, sizePx: Int = 40): ShapeDrawable {
    return ShapeDrawable(OvalShape()).apply {
        intrinsicWidth = sizePx
        intrinsicHeight = sizePx
        paint.color = colorInt
        paint.style = Paint.Style.FILL
    }
}

private class OsmCameraControllerImpl(private val mapView: MapView) : OsmCameraController {
    override suspend fun animateTo(lat: Double, lng: Double, zoom: Float, durationMs: Int) {
        try {
            mapView.controller.setZoom(zoom.toDouble())
            mapView.controller.animateTo(GeoPoint(lat, lng))
        } catch (e: Exception) {
            // Best-effort — an unlaid-out MapView can throw on the very first frame.
        }
    }

    override suspend fun animateToBounds(points: List<GeoLatLng>, paddingPx: Int) {
        try {
            val lats = points.map { it.lat }
            val lngs = points.map { it.lng }
            var north = lats.max()
            var south = lats.min()
            var east = lngs.max()
            var west = lngs.min()
            // osmdroid's zoomToBoundingBox doesn't take a pixel padding on every version, so
            // approximate it by padding the box itself.
            val latPad = ((north - south).takeIf { it > 0.0001 } ?: 0.01) * 0.15
            val lngPad = ((east - west).takeIf { it > 0.0001 } ?: 0.01) * 0.15
            north += latPad; south -= latPad; east += lngPad; west -= lngPad
            mapView.zoomToBoundingBox(BoundingBox(north, east, south, west), true)
        } catch (e: Exception) {
            if (points.isNotEmpty()) animateTo(points[0].lat, points[0].lng, 15f, 300)
        }
    }
}

@Composable
internal fun OsmMapImpl(
    modifier: Modifier,
    testTag: String,
    state: SndmartMapState,
    markers: List<SndmartMarker>,
    circle: SndmartCircleSpec?,
    polyline: SndmartPolylineSpec?,
    myLocationEnabled: Boolean,
    mapType: SndmartMapType,
    tileUrlBase: String?,
    satelliteTileUrlBase: String?,
    onMapClick: ((GeoLatLng) -> Unit)?,
    onCameraIdle: ((GeoLatLng) -> Unit)?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).apply {
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(state.zoom.toDouble())
            controller.setCenter(GeoPoint(state.center.lat, state.center.lng))
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    DisposableEffect(mapView) {
        val controller = OsmCameraControllerImpl(mapView)
        state.osmController = controller
        onDispose { state.osmController = null }
    }

    LaunchedEffect(mapView) {
        state.consumePendingAnimation()?.let { req ->
            state.osmController?.animateTo(req.lat, req.lng, req.zoom, req.durationMs)
        }
    }

    // Debounce scroll/zoom into a single "camera idle" callback, mirroring Google's isMoving -> false.
    val handler = remember { Handler(Looper.getMainLooper()) }
    val idleRunnable = remember {
        Runnable {
            state.isMoving = false
            val c = mapView.mapCenter
            onCameraIdle?.invoke(GeoLatLng(c.latitude, c.longitude))
        }
    }

    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                state.isMoving = true
                handler.removeCallbacks(idleRunnable)
                handler.postDelayed(idleRunnable, 400L)
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                state.isMoving = true
                handler.removeCallbacks(idleRunnable)
                handler.postDelayed(idleRunnable, 400L)
                return true
            }
        }
        mapView.addMapListener(listener)
        onDispose { handler.removeCallbacks(idleRunnable) }
    }

    AndroidView(
        modifier = modifier.testTag(testTag),
        factory = { mapView },
        update = { mv ->
            mv.setTileSource(
                if (mapType == SndmartMapType.SATELLITE) buildTileSource(satelliteTileUrlBase)
                else buildTileSource(tileUrlBase)
            )

            mv.overlays.clear()

            // osmdroid has no built-in onMapClick — route taps through an invisible overlay
            // added first so markers/polygons layered on top still receive their own taps.
            mv.overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        p?.let { onMapClick?.invoke(GeoLatLng(it.latitude, it.longitude)) }
                        return true
                    }

                    override fun longPressHelper(p: GeoPoint?): Boolean = false
                })
            )

            circle?.let { c ->
                val polygon = Polygon(mv).apply {
                    points = Polygon.pointsAsCircle(GeoPoint(c.center.lat, c.center.lng), c.radiusMeters)
                    fillPaint.color = c.fillColor.toArgb()
                    outlinePaint.color = c.strokeColor.toArgb()
                    outlinePaint.strokeWidth = c.strokeWidthPx
                }
                mv.overlays.add(polygon)
            }

            polyline?.let { pl ->
                val line = Polyline(mv).apply {
                    setPoints(pl.points.map { GeoPoint(it.lat, it.lng) })
                    outlinePaint.color = pl.color.toArgb()
                    outlinePaint.strokeWidth = pl.widthPx
                }
                mv.overlays.add(line)
            }

            markers.forEach { m ->
                val marker = Marker(mv).apply {
                    position = GeoPoint(m.position.lat, m.position.lng)
                    title = m.title ?: ""
                    snippet = m.snippet ?: ""
                    icon = dotDrawable(tintToColorInt(m.tint))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                if (m.draggable) {
                    marker.isDraggable = true
                    marker.setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                        override fun onMarkerDrag(marker: Marker?) {}
                        override fun onMarkerDragEnd(marker: Marker?) {
                            marker?.position?.let { m.onDragEnd?.invoke(GeoLatLng(it.latitude, it.longitude)) }
                        }

                        override fun onMarkerDragStart(marker: Marker?) {}
                    })
                }
                mv.overlays.add(marker)
            }

            if (myLocationEnabled) {
                try {
                    val myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(context), mv)
                    myLocationOverlay.enableMyLocation()
                    mv.overlays.add(myLocationOverlay)
                } catch (e: Exception) {
                    // Location permission not actually granted at the OS level — caller already
                    // gates myLocationEnabled on permission state, so this is just a safety net.
                }
            }

            // "© OpenStreetMap contributors" attribution — required by the OSM tile usage
            // policy, shown for whichever tile source is currently active.
            mv.overlays.add(CopyrightOverlay(context))

            mv.invalidate()
        }
    )
}

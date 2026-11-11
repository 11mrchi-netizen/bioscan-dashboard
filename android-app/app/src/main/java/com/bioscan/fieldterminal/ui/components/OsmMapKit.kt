package com.bioscan.fieldterminal.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView

// One setup for every osmdroid map in the app (the Map tab, the routine day map, a session's route), so they behave the
// same and stay put:
//   - the map is clipped to its own frame: Compose does not clip an embedded Android view, so without
//     Modifier.clipToBounds() a panned or zoomed map draws its tiles over the page around it;
//   - a drag on the map belongs to the map: the scrolling page around it is told not to take the gesture, so panning
//     does not jump between the map and the page;
//   - the world is shown once (no repeated copies to the sides, no panning past the poles), zoom stays between a
//     country and a street, and tiles are scaled to the screen so labels read at the phone's density;
//   - fitting the view to points waits for the map to have a size (fitting a map of 0 x 0 zooms to the maximum);
//   - callers release the map when it leaves the screen (AndroidView's onRelease = { it.onDetach() }).

private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 19.0

/** osmdroid's cache and user agent, set once. */
fun configureOsm(ctx: Context) {
    Configuration.getInstance().apply {
        userAgentValue = ctx.packageName
        osmdroidBasePath = ctx.filesDir
        osmdroidTileCache = ctx.cacheDir
    }
}

/** A map view with the app's stable defaults (see above). */
@SuppressLint("ClickableViewAccessibility")
fun stableMapView(ctx: Context): MapView {
    configureOsm(ctx)
    return MapView(ctx).apply {
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isHorizontalMapRepetitionEnabled = false
        isVerticalMapRepetitionEnabled = false
        val ts = MapView.getTileSystem()
        setScrollableAreaLimitLatitude(ts.maxLatitude, ts.minLatitude, 0)
        minZoomLevel = MIN_ZOOM
        maxZoomLevel = MAX_ZOOM
        isTilesScaledToDpi = true
        // The page around the map must not take a drag that started on it.
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }
}

/**
 * Fits the view to [points] with a margin of [pad] of the span on each side (a span of at least [minSpanDeg] degrees,
 * so one point does not zoom to the street's paving stones), once the map has a size.
 */
fun MapView.fitTo(points: List<GeoPoint>, pad: Double = 0.25, minSpanDeg: Double = 0.004) {
    if (points.isEmpty()) return
    val fit = {
        val box = BoundingBox.fromGeoPoints(points)
        val latPad = (box.latNorth - box.latSouth).coerceAtLeast(minSpanDeg) * pad
        val lonPad = (box.lonEast - box.lonWest).coerceAtLeast(minSpanDeg) * pad
        zoomToBoundingBox(
            BoundingBox(
                (box.latNorth + latPad).coerceAtMost(84.0), box.lonEast + lonPad,
                (box.latSouth - latPad).coerceAtLeast(-84.0), box.lonWest - lonPad,
            ),
            false,
        )
        if (zoomLevelDouble > 17.0) controller.setZoom(17.0)
    }
    if (width > 0 && height > 0) fit() else addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
}

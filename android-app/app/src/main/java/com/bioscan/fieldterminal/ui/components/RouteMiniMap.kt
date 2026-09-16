package com.bioscan.fieldterminal.ui.components

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.ui.theme.FieldColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

// Phase G5. Same osmdroid + CARTO Dark Matter setup as MapScreen.kt's
// OsmMapView (Step 14 follow-up), kept as its own small component rather
// than generalizing that one: this view takes a session's plain RoutePoint
// lat/lon list, not the Map tab's GpxPoint/weather/directions concerns, and
// touching the already-verified Map tab for an unrelated screen wasn't worth
// the risk for what's ~30 lines of setup.
@Composable
fun RouteMiniMap(points: List<RoutePoint>, cartoKey: String, modifier: Modifier = Modifier) {
    if (points.size < 2) return

    AndroidView(
        modifier = modifier.fillMaxWidth().height(200.dp),
        factory = { ctx ->
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                osmdroidBasePath = ctx.filesDir
                osmdroidTileCache = ctx.cacheDir
            }
            MapView(ctx).apply {
                setTileSource(
                    XYTileSource(
                        "CartoDarkMatter",
                        0,
                        20,
                        256,
                        ".png?key=$cartoKey",
                        arrayOf("https://basemaps.cartocdn.com/rastertiles/dark_all/"),
                    ),
                )
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
                overlays.add(
                    Polyline(this).apply {
                        setPoints(geoPoints)
                        outlinePaint.color = FieldColors.Cyan.toArgb()
                        outlinePaint.strokeWidth = 9f
                    },
                )
                overlays.add(dotMarker(this, geoPoints.first(), FieldColors.Green.toArgb()))
                overlays.add(dotMarker(this, geoPoints.last(), FieldColors.Magenta.toArgb()))

                post {
                    val box = BoundingBox.fromGeoPoints(geoPoints)
                    val latPad = (box.latNorth - box.latSouth).coerceAtLeast(0.001) * 0.2
                    val lonPad = (box.lonEast - box.lonWest).coerceAtLeast(0.001) * 0.2
                    zoomToBoundingBox(
                        BoundingBox(box.latNorth + latPad, box.lonEast + lonPad, box.latSouth - latPad, box.lonWest - lonPad),
                        false,
                    )
                }
            }
        },
    )
}

private fun dotMarker(mapView: MapView, point: GeoPoint, color: Int): Marker =
    Marker(mapView).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ShapeDrawable(OvalShape()).apply {
            paint.color = color
            intrinsicWidth = 34
            intrinsicHeight = 34
            setBounds(0, 0, 34, 34)
        }
        setOnMarkerClickListener { _, _ -> true }
    }

package com.bioscan.fieldterminal.ui.components

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bioscan.fieldterminal.domain.KmMarker
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.routeKmMarkers
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
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
                        outlinePaint.color = FT.DomainTraining.toArgb() // matches MapScreen's own training-route color
                        outlinePaint.strokeWidth = 9f
                    },
                )
                // A numbered marker at every km (every 5th past 20 km, so a long run's map stays readable).
                val kms = routeKmMarkers(points)
                val every = if (kms.size > 20) 5 else 1
                kms.filter { it.km % every == 0 }.forEach { overlays.add(kmMarker(this, it)) }
                overlays.add(dotMarker(this, geoPoints.first(), FT.Emerald.toArgb()))
                overlays.add(dotMarker(this, geoPoints.last(), FT.Critical.toArgb()))

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

private fun kmMarker(mapView: MapView, km: KmMarker): Marker =
    Marker(mapView).apply {
        position = GeoPoint(km.lat, km.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = KmBadge(km.km.toString(), mapView.resources.displayMetrics.density)
        setOnMarkerClickListener { _, _ -> true }
    }

// A small dark disc with the km number, outlined in the route's colour.
private class KmBadge(private val label: String, density: Float) : Drawable() {
    private val size = (18 * density).toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FT.Surface.toArgb() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FT.DomainTraining.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FT.TextPrimary.toArgb()
        textSize = 9 * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    init {
        setBounds(0, 0, size, size)
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(canvas: Canvas) {
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val r = bounds.width() / 2f - ring.strokeWidth
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

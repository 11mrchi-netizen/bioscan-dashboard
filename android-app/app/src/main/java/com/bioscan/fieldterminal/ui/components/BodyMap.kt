package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.health.BODY_MAP_SHAPES
import com.bioscan.fieldterminal.domain.health.BodyView
import com.bioscan.fieldterminal.domain.health.RegionShape
import com.bioscan.fieldterminal.domain.health.hitRegion
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Health concerns (plan §5.2): the body-map symptom logger and the concerns overview figure. FRONT and BACK side by side,
// regions from domain/health/BodyMapRegions.kt (ellipses in a 100 x 200 box per figure). A tap reports the region and the
// person's side (the front view is mirrored: its left half is their right). `marks` tints regions that have a concern,
// in that concern's state colour; `selected` outlines the region being logged. Schematic on purpose, like BodyHeatMap.

data class BodyMark(val code: String, val side: String?, val color: Color)

@Composable
fun BodyMap(
    modifier: Modifier = Modifier,
    selected: RegionShape? = null,
    marks: List<BodyMark> = emptyList(),
    height: Dp = 300.dp,
    onTap: ((RegionShape) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("FRONT · YOUR RIGHT ◂", style = FTType.Micro, color = FT.TextMuted)
            Text("BACK · ▸ YOUR RIGHT", style = FTType.Micro, color = FT.TextMuted)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "Body map, front and back. Tap where it bothers you." }
                .then(
                    if (onTap == null) Modifier else Modifier.pointerInput(Unit) {
                        detectTapGestures { pos ->
                            val g = geometry(Size(size.width.toFloat(), size.height.toFloat()))
                            val view = if (pos.x < size.width / 2f) BodyView.Front else BodyView.Back
                            val ox = if (view == BodyView.Front) g.frontX else g.backX
                            hitRegion(view, (pos.x - ox) / g.scale, (pos.y - g.oy) / g.scale)?.let(onTap)
                        }
                    },
                ),
        ) {
            val g = geometry(size)
            for (view in BodyView.entries) {
                val ox = if (view == BodyView.Front) g.frontX else g.backX
                drawSilhouette(ox, g.oy, g.scale)
                BODY_MAP_SHAPES.filter { it.view == view }.sortedByDescending { it.area }.forEach { s ->
                    val mark = marks.firstOrNull { m -> m.code == s.code && (m.side == null || m.side == "both" || s.side == null || m.side == s.side) }
                    val topLeft = Offset(ox + (s.cx - s.rx) * g.scale, g.oy + (s.cy - s.ry) * g.scale)
                    val sz = Size(2 * s.rx * g.scale, 2 * s.ry * g.scale)
                    drawOval(mark?.color?.copy(alpha = 0.55f) ?: FT.GlassFill, topLeft, sz)
                    val isSel = selected != null && selected.code == s.code && selected.side == s.side && selected.view == s.view
                    drawOval(
                        when {
                            isSel -> FT.TextPrimary
                            mark != null -> mark.color
                            else -> FT.GlassBorder
                        },
                        topLeft, sz, style = Stroke(if (isSel) 3f else 1f),
                    )
                }
            }
        }
    }
}

private class MapGeometry(val scale: Float, val frontX: Float, val backX: Float, val oy: Float)

private fun geometry(size: Size): MapGeometry {
    val scale = minOf(size.width / 2f / 100f, size.height / 200f)
    return MapGeometry(
        scale = scale,
        frontX = size.width / 4f - 50f * scale,
        backX = size.width * 3f / 4f - 50f * scale,
        oy = (size.height - 200f * scale) / 2f,
    )
}

// A plain outline under the regions: head, torso, arms and legs as faint strokes.
private fun DrawScope.drawSilhouette(ox: Float, oy: Float, scale: Float) {
    fun p(x: Float, y: Float) = Offset(ox + x * scale, oy + y * scale)
    val c = FT.GlassTrack
    val w = 1.5f
    drawCircle(c, radius = 9f * scale, center = p(50f, 14f), style = Stroke(w))
    drawLine(c, p(50f, 25f), p(50f, 33f), w)
    // shoulders and torso
    drawLine(c, p(31f, 36f), p(69f, 36f), w)
    drawLine(c, p(34f, 37f), p(38f, 92f), w)
    drawLine(c, p(66f, 37f), p(62f, 92f), w)
    drawLine(c, p(38f, 92f), p(62f, 92f), w)
    // arms
    for (sgn in listOf(-1f, 1f)) {
        val x = { d: Float -> 50f + sgn * d }
        drawLine(c, p(x(19f), 38f), p(x(26f), 70f), w)
        drawLine(c, p(x(26f), 70f), p(x(33f), 96f), w)
        drawLine(c, p(x(33f), 96f), p(x(34f), 108f), w)
        // legs
        drawLine(c, p(x(8f), 92f), p(x(9f), 132f), w)
        drawLine(c, p(x(9f), 132f), p(x(9f), 172f), w)
        drawLine(c, p(x(9f), 172f), p(x(12f), 190f), w)
    }
}

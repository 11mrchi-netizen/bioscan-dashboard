package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.BodyZone
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Front + back body silhouettes with each muscle zone tinted by its share of
// the session's load (kg of volume), replacing the old per-region bars. Zones
// are hand-authored polygons in a 100x200 viewBox per figure -- deliberately
// schematic, not anatomical art. Tap a zone to read its kg.
private typealias Poly = List<Pair<Float, Float>>

private data class ZoneShape(val zone: BodyZone, val pts: Poly)

private fun mirror(p: Poly): Poly = p.map { (x, y) -> (100f - x) to y }

// Bilateral zone: the left-half polygon plus its mirror image.
private fun both(zone: BodyZone, left: Poly) = listOf(ZoneShape(zone, left), ZoneShape(zone, mirror(left)))

private val SHOULDER: Poly = listOf(30f to 37f, 20f to 40f, 15f to 52f, 24f to 56f, 31f to 46f)
private val UPPER_ARM: Poly = listOf(22f to 57f, 30f to 52f, 29f to 80f, 20f to 80f)
private val FOREARM: Poly = listOf(19f to 82f, 28f to 82f, 25f to 112f, 16f to 110f)
private val CALF: Poly = listOf(35f to 156f, 46f to 156f, 44f to 192f, 37f to 192f)

private val FRONT_SHAPES: List<ZoneShape> =
    listOf(ZoneShape(BodyZone.NeckTraps, listOf(45f to 24f, 55f to 24f, 56f to 31f, 68f to 37f, 32f to 37f, 44f to 31f))) +
        both(BodyZone.Shoulders, SHOULDER) +
        both(BodyZone.Chest, listOf(32f to 39f, 49f to 40f, 49f to 58f, 38f to 62f, 31f to 54f)) +
        listOf(ZoneShape(BodyZone.Abs, listOf(40f to 63f, 60f to 63f, 59f to 96f, 41f to 96f))) +
        both(BodyZone.Biceps, UPPER_ARM) +
        both(BodyZone.Forearms, FOREARM) +
        both(BodyZone.Hips, listOf(31f to 98f, 49f to 98f, 49f to 114f, 32f to 114f)) +
        both(BodyZone.Quads, listOf(32f to 116f, 49f to 116f, 47f to 152f, 35f to 152f)) +
        both(BodyZone.Calves, CALF)

// UpperBack is listed first so the trapezius polygon draws over its top edge.
private val BACK_SHAPES: List<ZoneShape> =
    both(BodyZone.UpperBack, listOf(32f to 40f, 49f to 58f, 49f to 78f, 36f to 82f, 30f to 62f)) +
        listOf(ZoneShape(BodyZone.NeckTraps, listOf(45f to 24f, 55f to 24f, 56f to 30f, 70f to 38f, 50f to 56f, 30f to 38f, 44f to 30f))) +
        both(BodyZone.Shoulders, SHOULDER) +
        both(BodyZone.Triceps, UPPER_ARM) +
        both(BodyZone.Forearms, FOREARM) +
        listOf(ZoneShape(BodyZone.LowerBack, listOf(41f to 80f, 59f to 80f, 59f to 97f, 41f to 97f))) +
        both(BodyZone.Glutes, listOf(32f to 99f, 49f to 99f, 49f to 117f, 31f to 115f)) +
        both(BodyZone.Hamstrings, listOf(32f to 119f, 49f to 119f, 47f to 152f, 35f to 152f)) +
        both(BodyZone.Calves, CALF)

private fun contains(poly: Poly, x: Float, y: Float): Boolean {
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val (xi, yi) = poly[i]
        val (xj, yj) = poly[j]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

@Composable
fun BodyHeatMap(loads: Map<BodyZone, Double>, modifier: Modifier = Modifier) {
    val maxLoad = loads.values.maxOrNull()?.takeIf { it > 0 } ?: return
    val total = loads.values.sum()
    var selected by remember { mutableStateOf<BodyZone?>(null) }
    val idle = FT.GlassFill

    fun tint(zone: BodyZone): Color {
        val load = loads[zone] ?: 0.0
        return if (load <= 0) idle else lerp(idle, FT.DomainTraining, (0.3 + 0.7 * (load / maxLoad)).toFloat())
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .pointerInput(loads) {
                    detectTapGestures { pos ->
                        val scale = minOf(size.width / 2f / 100f, size.height / 200f)
                        val front = pos.x < size.width / 2f
                        val ox = (if (front) size.width / 4f else size.width * 3f / 4f) - 50f * scale
                        val oy = (size.height - 200f * scale) / 2f
                        val vx = (pos.x - ox) / scale
                        val vy = (pos.y - oy) / scale
                        val hit = (if (front) FRONT_SHAPES else BACK_SHAPES).lastOrNull { contains(it.pts, vx, vy) }?.zone
                        selected = if (hit == selected) null else hit
                    }
                },
        ) {
            val scale = minOf(size.width / 2f / 100f, size.height / 200f)
            val oy = (size.height - 200f * scale) / 2f
            listOf(FRONT_SHAPES to size.width / 4f, BACK_SHAPES to size.width * 3f / 4f).forEach { (shapes, cx) ->
                val ox = cx - 50f * scale
                fun p(pt: Pair<Float, Float>) = Offset(ox + pt.first * scale, oy + pt.second * scale)
                drawCircle(FT.GlassBorder, radius = 9f * scale, center = p(50f to 13f), style = Stroke(1.5f))
                shapes.forEach { s ->
                    val path = Path().apply {
                        s.pts.forEachIndexed { i, pt -> p(pt).let { o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                        close()
                    }
                    drawPath(path, tint(s.zone))
                    drawPath(path, if (s.zone == selected) FT.TextPrimary else FT.GlassBorder, style = Stroke(if (s.zone == selected) 3f else 1f))
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Text("FRONT", style = FTType.Micro, color = FT.TextMuted)
            Text("BACK", style = FTType.Micro, color = FT.TextMuted)
        }
        val sel = selected
        Text(
            if (sel != null) "${sel.label} — %.0f kg (%.0f%%)".format(loads[sel] ?: 0.0, (loads[sel] ?: 0.0) / total * 100)
            else "Tap a muscle group for its load.",
            style = FTType.BodySmall,
            color = if (sel != null) FT.TextPrimary else FT.TextSecondary,
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("0", style = FTType.Micro, color = FT.TextMuted)
            Box(
                Modifier.width(120.dp).height(6.dp)
                    .background(Brush.horizontalGradient(listOf(idle, FT.DomainTraining))),
            )
            Text("%.0f kg".format(maxLoad), style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

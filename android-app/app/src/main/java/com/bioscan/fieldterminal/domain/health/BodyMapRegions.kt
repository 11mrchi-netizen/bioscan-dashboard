package com.bioscan.fieldterminal.domain.health

// Health concerns (plan §5.2): where each vocabulary region sits on the body map. Two figures in a 100 x 200 box each,
// FRONT and BACK; every region is an ellipse, deliberately schematic (as BodyHeatMap's muscle polygons are). On the front
// view the person faces the viewer, so their RIGHT side is on the viewer's left; on the back view it is on the right.
// A tap picks the smallest region containing it, so a knee inside a thigh's reach is still the knee. Pure data, so
// BodyMapRegionsTest checks every vocabulary region is reachable on both sides.

enum class BodyView { Front, Back }

data class RegionShape(
    val view: BodyView,
    val code: String,
    /** "left" / "right" for sided regions, null otherwise. */
    val side: String?,
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
) {
    val area: Float get() = rx * ry
    fun contains(x: Float, y: Float): Boolean {
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        return dx * dx + dy * dy <= 1f
    }
}

const val BODY_MAP_WIDTH = 100f
const val BODY_MAP_HEIGHT = 200f

private class ShapeBuilder {
    val out = mutableListOf<RegionShape>()

    fun centre(view: BodyView, code: String, cx: Float, cy: Float, rx: Float, ry: Float) {
        out += RegionShape(view, code, null, cx, cy, rx, ry)
    }

    /** A sided region given by its offset from the midline; placed on the correct side of each view. */
    fun pair(view: BodyView, code: String, dx: Float, cy: Float, rx: Float, ry: Float) {
        // Front: the person's right is on the viewer's left. Back: on the viewer's right.
        val rightX = if (view == BodyView.Front) 50f - dx else 50f + dx
        val leftX = if (view == BodyView.Front) 50f + dx else 50f - dx
        out += RegionShape(view, code, "right", rightX, cy, rx, ry)
        out += RegionShape(view, code, "left", leftX, cy, rx, ry)
    }
}

val BODY_MAP_SHAPES: List<RegionShape> = ShapeBuilder().apply {
    for (v in BodyView.entries) {
        centre(v, "head", 50f, 14f, 9f, 11f)
        centre(v, "neck", 50f, 29f, 5f, 4f)
        pair(v, "shoulder", 19f, 40f, 7f, 6f)
        pair(v, "upper_arm", 25f, 56f, 5f, 10f)
        pair(v, "elbow", 28f, 70f, 4f, 4f)
        pair(v, "forearm", 31f, 82f, 4f, 8f)
        pair(v, "knee", 9f, 132f, 6f, 5f)
        pair(v, "lateral_thigh", 17f, 112f, 3f, 13f)
    }
    val f = BodyView.Front
    centre(f, "chest", 50f, 46f, 13f, 8f)
    centre(f, "abdomen", 50f, 66f, 11f, 10f)
    pair(f, "wrist", 33f, 94f, 3f, 3f)
    pair(f, "hand", 34f, 103f, 4f, 6f)
    pair(f, "hip", 10f, 84f, 6f, 5f)
    pair(f, "groin", 5f, 96f, 4f, 6f)
    pair(f, "quad", 9f, 112f, 7f, 14f)
    pair(f, "shin", 9f, 150f, 5f, 12f)
    pair(f, "ankle", 9f, 170f, 4f, 3f)
    pair(f, "foot_top", 10f, 180f, 5f, 4f)
    pair(f, "toes", 11f, 188f, 5f, 3f)
    val b = BodyView.Back
    centre(b, "upper_back", 50f, 48f, 14f, 10f)
    centre(b, "lower_back", 50f, 70f, 11f, 7f)
    pair(b, "glute", 8f, 86f, 8f, 7f)
    pair(b, "hamstring", 8f, 112f, 7f, 14f)
    pair(b, "calf", 8f, 150f, 6f, 11f)
    pair(b, "achilles", 8f, 166f, 3f, 4f)
    pair(b, "heel", 8f, 177f, 4f, 4f)
    pair(b, "plantar_foot", 8f, 186f, 4f, 3f)
}.out

/** The region under a tap in one figure's 100 x 200 box, or null. */
fun hitRegion(view: BodyView, x: Float, y: Float, shapes: List<RegionShape> = BODY_MAP_SHAPES): RegionShape? =
    shapes.filter { it.view == view && it.contains(x, y) }.minByOrNull { it.area }

/** The shapes to light for a region and side ("both" or null lights both sides). */
fun shapesFor(code: String, side: String?, shapes: List<RegionShape> = BODY_MAP_SHAPES): List<RegionShape> =
    shapes.filter { it.code == code && (side == null || side == "both" || it.side == null || it.side == side) }

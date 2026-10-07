package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// Shared tap/drag-to-inspect behavior for DateTrendLine and LineChart (25/9
// rework). Horizontal drag only: every chart lives inside a vertically
// scrolling screen, so a vertical drag has to keep scrolling the page.
//
// `onPick` gets the touch x and the canvas width in pixels; isTap is true for a
// tap (caller may toggle the same point off) and false while scrubbing.
// `key` must change whenever the plotted data does (the gesture block captures
// onPick, which closes over the axis mapping).
internal fun Modifier.chartInspect(key: Any?, onPick: (x: Float, width: Float, isTap: Boolean) -> Unit): Modifier =
    pointerInput(key) { detectTapGestures(onTap = { onPick(it.x, size.width.toFloat(), true) }) }
        .pointerInput(key) {
            detectHorizontalDragGestures(
                onDragStart = { onPick(it.x, size.width.toFloat(), false) },
                onHorizontalDrag = { change, _ -> onPick(change.position.x, size.width.toFloat(), false) },
            )
        }

internal data class InspectRow(val label: String?, val value: String, val color: Color)

private val inspectRowStyle = FTType.MonoCaption.copy(color = FT.TextPrimary)

// Guide line + one dot per row + a value box, all drawn inside the chart's own
// canvas (no layout math: the box is measured, then flipped to the left of the
// guide when it would overflow the right edge).
internal fun DrawScope.drawInspectOverlay(
    measurer: TextMeasurer,
    x: Float,
    dots: List<Offset>,
    header: String,
    rows: List<InspectRow>,
) {
    drawLine(FT.TextSecondary.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    dots.forEachIndexed { i, o ->
        val c = rows.getOrNull(i)?.color ?: FT.TextPrimary
        drawCircle(FT.Surface, radius = 8f, center = o)
        drawCircle(c, radius = 6f, center = o)
    }

    val text: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = FT.TextSecondary)) { append(header) }
        rows.forEach { r ->
            append("\n")
            if (r.label != null) withStyle(SpanStyle(color = r.color)) { append(r.label + " ") }
            withStyle(SpanStyle(color = FT.TextPrimary)) { append(r.value) }
        }
    }
    val layout = measurer.measure(text, inspectRowStyle)
    val padPx = 8f
    val w = layout.size.width + padPx * 2
    val h = layout.size.height + padPx * 2
    val left = if (x + 12f + w <= size.width) x + 12f else (x - 12f - w).coerceAtLeast(0f)
    val top = 2f
    drawRoundRect(FT.Surface.copy(alpha = 0.96f), Offset(left, top), androidx.compose.ui.geometry.Size(w, h), CornerRadius(8f))
    drawRoundRect(FT.GlassBorder, Offset(left, top), androidx.compose.ui.geometry.Size(w, h), CornerRadius(8f), style = Stroke(1f))
    drawText(layout, topLeft = Offset(left + padPx, top + padPx))
}

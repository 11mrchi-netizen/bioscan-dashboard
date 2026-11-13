package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

/**
 * A rating chosen by sliding or tapping, never typed (`.ft-range`, in the metric's own hue): the label and the chosen
 * value (in the metric hue) above, then the track: filled from the low end to the value in a gradient of [color], a dot
 * for each step, a white thumb ringed in the ground colour. [lowWord] / [highWord] say what the ends mean.
 *
 * An optional rating starts unset (no thumb, "—") until touched, and a CLEAR under it sets it back to null, so "not
 * answered" never silently saves as 0. Snaps to whole steps with a light tick; screen readers get it as an adjustable
 * value.
 *
 * Every scale reads bad on the left, good on the right. A scale whose number grows worse (stress, soreness, discomfort,
 * severity) passes [reversed]: the track is mirrored so its high end sits on the left, while the value it reports keeps
 * its meaning (stress 8 is still 8), so stored data and scores are unchanged.
 */
@Composable
fun FTRatingSlider(
    label: String,
    value: Int?,
    range: IntRange,
    color: Color,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    lowWord: String? = null,
    highWord: String? = null,
    optional: Boolean = true,
    enabled: Boolean = true,
    // A categorical scale (Bristol): words instead of the number, a colour per step instead of one hue, and named
    // stretches of the scale under the track. Unset, the slider is the plain metric-hue rating above.
    valueText: ((Int) -> String)? = null,
    stepColor: ((Int) -> Color)? = null,
    scale: List<Pair<IntRange, String>>? = null,
    // A long scale (an amount in steps) has no dot per step, and says what its ends are in its own words.
    ticks: Boolean = true,
    endLabels: Pair<String, String>? = null,
    reversed: Boolean = false,
) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val steps = range.last - range.first
    fun pick(x: Float, width: Float, inset: Float) {
        val f = ((x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
        val v = if (reversed) range.last - (f * steps).roundToInt() else range.first + (f * steps).roundToInt()
        if (v != current) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(v)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val ink = value?.let { stepColor?.invoke(it) } ?: color
        if (valueText != null) {
            Text(label, style = FTType.LabelCaps, color = FT.TextSecondary)
            Text(value?.let(valueText) ?: "—", style = FTType.RowTitle, color = if (value == null) FT.TextMuted else ink)
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(label, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            Text(
                value?.toString() ?: "—",
                style = FTType.MetricMedium,
                color = if (value == null) FT.TextMuted else color,
            )
            Text(" / ${range.last}", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = value?.let { v -> valueText?.invoke(v) ?: "$v of ${range.last}" } ?: "not set"
                    progressBarRangeInfo = ProgressBarRangeInfo((value ?: range.first).toFloat(), range.first.toFloat()..range.last.toFloat(), steps - 1)
                    if (enabled) setProgress { target ->
                        change(target.roundToInt().coerceIn(range.first, range.last)); true
                    }
                }
                .then(
                    if (enabled) Modifier
                        .pointerInput(range) { detectTapGestures { pick(it.x, size.width.toFloat(), 12.dp.toPx()) } }
                        .pointerInput(range) {
                            detectHorizontalDragGestures { ch, _ -> ch.consume(); pick(ch.position.x, size.width.toFloat(), 12.dp.toPx()) }
                        }
                    else Modifier,
                ),
        ) {
            val inset = 12.dp.toPx()
            val w = size.width - 2 * inset
            val cy = size.height / 2
            val th = 7.dp.toPx()
            fun x(v: Int) = inset + w * (if (reversed) range.last - v else v - range.first) / steps.coerceAtLeast(1)
            drawRoundRect(FT.GlassTrack, Offset(inset, cy - th / 2), Size(w, th), CornerRadius(th / 2))
            if (stepColor != null) {
                // Each step owns the stretch half way to its neighbours, in its own colour; brighter once chosen.
                val half = w / steps.coerceAtLeast(1) / 2
                for (v in range) {
                    val from = (x(v) - half).coerceAtLeast(inset)
                    val to = (x(v) + half).coerceAtMost(inset + w)
                    val a = if (value == null) 0.45f else if (v == value) 1f else 0.55f
                    drawRoundRect(stepColor(v).copy(alpha = a), Offset(from, cy - th / 2), Size(to - from, th),
                        CornerRadius(if (v == range.first || v == range.last) th / 2 else 0f))
                }
            } else if (value != null) {
                val end = x(value)
                drawRoundRect(
                    Brush.horizontalGradient(listOf(color.copy(alpha = 0.35f), lerp(color, Color.White, 0.08f)), startX = inset, endX = end.coerceAtLeast(inset + 1f)),
                    Offset(inset, cy - th / 2), Size((end - inset).coerceAtLeast(th), th), CornerRadius(th / 2),
                )
            }
            if (ticks) for (v in range) {
                val filled = value != null && (if (reversed) v >= value else v <= value)
                drawCircle(if (filled) Color.White.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.18f), 1.5.dp.toPx(), Offset(x(v), cy))
            }
            if (value != null) {
                val at = Offset(x(value), cy)
                drawCircle(FT.Base, 8.5.dp.toPx() + 3.dp.toPx(), at)
                drawCircle(Color.White, 8.5.dp.toPx(), at)
                drawCircle(ink, 3.dp.toPx(), at)
            }
        }
        if (scale != null) {
            // Named stretches of the scale, each centred under its steps.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                for ((r, name) in scale) {
                    Text(name, style = FTType.Micro, color = stepColor?.invoke(r.first) ?: FT.TextMuted, textAlign = TextAlign.Center,
                        modifier = Modifier.weight((r.last - r.first + 1).toFloat()))
                }
            }
            if (optional && value != null && enabled) {
                Text("CLEAR", style = FTType.LabelCaps, color = FT.TextSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally).ftClickable(role = Role.Button, onClickLabel = "Clear $label") { change(null) }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The left label is always the bad end: the high end on a reversed scale.
            val leftLabel = endLabels?.first ?: if (reversed) highWord?.let { "${range.last} · $it" } ?: "${range.last}"
                else lowWord?.let { "${range.first} · $it" } ?: "${range.first}"
            val rightLabel = endLabels?.second ?: if (reversed) lowWord?.let { "$it · ${range.first}" } ?: "${range.first}"
                else highWord?.let { "$it · ${range.last}" } ?: "${range.last}"
            Text(leftLabel, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
            if (optional && value != null && enabled) {
                Text("CLEAR", style = FTType.LabelCaps, color = FT.TextSecondary,
                    modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Clear $label") { change(null) }.padding(horizontal = 10.dp, vertical = 4.dp))
            } else if (value == null) {
                Text("slide or tap", style = FTType.Micro, color = FT.TextMuted)
            }
            Text(rightLabel, style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

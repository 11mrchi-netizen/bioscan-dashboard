package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Polish brief, filters and selectors: one chip. `.ft-chip` (design/tokens): a mono caps pill, the selected one platinum
// with dark ink, the others an outline on faint glass. The pill is 36 dp tall but its touch target is 48 dp (contract:
// interactive targets 48 dp), so a row of chips stays compact. Every chip strip is an FTChipRow: it scrolls sideways
// rather than clip on a narrow phone, fades the edge that has more chips behind it (so a seventh tab is discoverable),
// and keeps the selected chip in view.

@Composable
fun FTChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.Tab,
    // A chip that opens a picker (BLOCK): a chevron after the label.
    menu: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .ftClickable(shape = shape, role = role, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .defaultMinSize(minHeight = 36.dp)
                .then(
                    if (selected) Modifier.background(FT.Platinum, shape)
                    else Modifier.border(FT.BorderWidth, FT.Line, shape).background(FT.ChipIdle, shape),
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = if (selected) FTType.LabelCaps.copy(fontWeight = FontWeight.Bold) else FTType.LabelCaps,
                    color = if (selected) FT.PlatinumInk else FT.TextSecondary,
                    maxLines = 1,
                )
                if (menu) FTIcon(FTIcons.ChevronDown, null, tint = if (selected) FT.PlatinumInk else FT.TextSecondary, size = 14.dp)
            }
        }
    }
}

/** A strip of chips that scrolls sideways when it does not fit, with faded edges and the selection kept in view. */
@Composable
fun <T> FTChipRow(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    spacing: androidx.compose.ui.unit.Dp = 6.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    // Each chip's left edge and width, to bring the selected one into view (on first show and on every change).
    val bounds = remember(items) { mutableMapOf<Int, Pair<Int, Int>>() }
    val selIndex = items.indexOf(selected)
    LaunchedEffect(selIndex, scroll.maxValue) {
        val b = bounds[selIndex] ?: return@LaunchedEffect
        val margin = with(density) { 24.dp.roundToPx() }
        val viewport = scroll.viewportSize
        val target = when {
            b.first - margin < scroll.value -> b.first - margin
            b.first + b.second + margin > scroll.value + viewport -> b.first + b.second + margin - viewport
            else -> return@LaunchedEffect
        }
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue), FTMotion.tweenFast())
    }
    Row(
        modifier
            .fillMaxWidth()
            .edgeFades(scroll)
            .horizontalScroll(scroll)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            FTChip(
                label(item), item == selected, { onSelect(item) },
                Modifier.onGloballyPositioned { c -> bounds[i] = c.positionInParent().x.toInt() to c.size.width },
            )
        }
        trailing?.invoke()
    }
}

/** Fades the start or end of a horizontally scrolling row when there is more content that way. */
fun Modifier.edgeFades(scroll: ScrollState, width: androidx.compose.ui.unit.Dp = 24.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = width.toPx()
        if (scroll.value > 0) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w), blendMode = BlendMode.DstIn, size = size.copy(width = w))
        }
        if (scroll.value < scroll.maxValue) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                topLeft = androidx.compose.ui.geometry.Offset(size.width - w, 0f), size = size.copy(width = w), blendMode = BlendMode.DstIn,
            )
        }
    }

// The standard chart ranges (polish brief: 28D, 90D, 1Y, ALL). A page offers the ones that fit its data; the labels
// and the order never change.
enum class FTRange(val label: String, val days: Long?) {
    D28("28D", 28), D90("90D", 90), Y1("1Y", 365), ALL("ALL", null);

    fun start(asOf: java.time.LocalDate): java.time.LocalDate? = days?.let { asOf.minusDays(it - 1) }
}

// A range picked on a page is kept when the page is left and opened again (in this process): the same chart opens on
// the window it was last read in. Keyed by the page.
object RangeMemory {
    private val picks = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String, default: T): T = (picks[key] as? T) ?: default
    fun set(key: String, value: Any) { picks[key] = value }
}

/** A remembered range for [key]: survives recomposition, navigation away and back, and configuration changes. */
@Composable
fun <T : Any> rememberRange(key: String, default: T): MutableState<T> {
    val state = remember(key) { mutableStateOf(RangeMemory.get(key, default)) }
    return remember(key, state) {
        object : MutableState<T> by state {
            override var value: T
                get() = state.value
                set(v) { state.value = v; RangeMemory.set(key, v) }
        }
    }
}

@Composable
fun FTRangeSelector(
    selected: FTRange,
    onSelect: (FTRange) -> Unit,
    modifier: Modifier = Modifier,
    options: List<FTRange> = FTRange.entries,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) = FTChipRow(options, selected, { it.label }, onSelect, modifier, contentPadding)

/**
 * A choice inside a form (a sheet's options, one or several): the square-cornered segment (RadiusSmall), platinum when
 * chosen, an outline otherwise. Distinct from [FTChip], the pill that filters or switches a view. 48 dp target.
 */
@Composable
fun FTChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, multi: Boolean = false) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected }
            .ftClickable(shape = shape, role = if (multi) Role.Checkbox else Role.RadioButton, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = FTType.LabelCaps,
            color = if (selected) FT.PlatinumInk else FT.TextSecondary,
            modifier = Modifier
                .border(FT.BorderWidth, if (selected) Color.Transparent else FT.GlassBorder, shape)
                .background(if (selected) FT.PlatinumSolid else Color.Transparent, shape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

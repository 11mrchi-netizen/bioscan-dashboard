package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Polish brief, icons: one family for every interface icon. Same geometry as the dock's line icons
// (res/drawable/ic_tab_*.xml): a 24 dp grid, a 1.9 stroke, round caps and joins, no fills. Text glyphs (‹ › ✕ ⚙ ⓘ ⌄)
// are no longer used as icons: an icon is an ImageVector drawn through FTIcon, and an icon that does something is an
// FTIconButton (48 dp target, a spoken description). Built in code, so no icon library is added.
object FTIcons {
    private fun line(name: String, build: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        ).build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    val Back: ImageVector by lazy { line("Back") { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) } }
    val Close: ImageVector by lazy { line("Close") { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) } }
    val ChevronRight: ImageVector by lazy { line("ChevronRight") { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) } }
    val ChevronDown: ImageVector by lazy { line("ChevronDown") { moveTo(5f, 9f); lineTo(12f, 16f); lineTo(19f, 9f) } }
    val ChevronUp: ImageVector by lazy { line("ChevronUp") { moveTo(5f, 15f); lineTo(12f, 8f); lineTo(19f, 15f) } }
    val DoubleBack: ImageVector by lazy { line("DoubleBack") { moveTo(12f, 5f); lineTo(5f, 12f); lineTo(12f, 19f); moveTo(19f, 5f); lineTo(12f, 12f); lineTo(19f, 19f) } }
    val DoubleForward: ImageVector by lazy { line("DoubleForward") { moveTo(5f, 5f); lineTo(12f, 12f); lineTo(5f, 19f); moveTo(12f, 5f); lineTo(19f, 12f); lineTo(12f, 19f) } }
    val Check: ImageVector by lazy { line("Check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) } }
    val Plus: ImageVector by lazy { line("Plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) } }
    val Minus: ImageVector by lazy { line("Minus") { moveTo(5f, 12f); lineTo(19f, 12f) } }

    // The dock's gear (ic_tab_settings.xml): eight ticks round a small ring.
    val Settings: ImageVector by lazy {
        line("Settings") {
            moveTo(12f, 3f); lineTo(12f, 6f); moveTo(12f, 18f); lineTo(12f, 21f)
            moveTo(3f, 12f); lineTo(6f, 12f); moveTo(18f, 12f); lineTo(21f, 12f)
            moveTo(5.6f, 5.6f); lineTo(7.7f, 7.7f); moveTo(16.3f, 16.3f); lineTo(18.4f, 18.4f)
            moveTo(18.4f, 5.6f); lineTo(16.3f, 7.7f); moveTo(7.7f, 16.3f); lineTo(5.6f, 18.4f)
            circle(12f, 12f, 3.2f)
        }
    }

    val Info: ImageVector by lazy {
        line("Info") { circle(12f, 12f, 8.5f); moveTo(12f, 11f); lineTo(12f, 16.5f); moveTo(12f, 7.6f); lineTo(12f, 7.7f) }
    }

    val Help: ImageVector by lazy {
        line("Help") {
            circle(12f, 12f, 8.5f)
            moveTo(9.6f, 9.6f)
            curveTo(9.6f, 8.2f, 10.7f, 7.3f, 12f, 7.3f)
            curveTo(13.4f, 7.3f, 14.4f, 8.3f, 14.4f, 9.5f)
            curveTo(14.4f, 11.2f, 12f, 11.4f, 12f, 13.4f)
            moveTo(12f, 16.6f); lineTo(12f, 16.7f)
        }
    }

    val Warning: ImageVector by lazy {
        line("Warning") {
            moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
            moveTo(12f, 10f); lineTo(12f, 14f); moveTo(12f, 16.8f); lineTo(12f, 16.9f)
        }
    }
}

/** The three icon sizes: inline with text, in a row or header, and in a button. */
object FTIconSize {
    val Inline: Dp = 16.dp
    val Row: Dp = 20.dp
    val Button: Dp = 22.dp
}

/** A purely decorative or labelled-elsewhere icon (description null), or one that is the only label (pass it). */
@Composable
fun FTIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = FT.TextSecondary,
    size: Dp = FTIconSize.Row,
) = Icon(icon, contentDescription, modifier.size(size), tint = tint)

/**
 * An icon that does something: a 48 dp touch target (contract: buttons 48 dp), the shared press feedback, and a
 * spoken description, which is required, because the icon is the only label.
 */
@Composable
fun FTIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = FT.TextSecondary,
    size: Dp = FTIconSize.Button,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(48.dp)
            .ftClickable(shape = CircleShape, role = Role.Button, onClickLabel = contentDescription, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        FTIcon(icon, contentDescription, tint = if (enabled) tint else FT.TextMuted, size = size)
    }
}

/** The trailing "opens more" mark on a row: a chevron, decorative (the row carries the click label). */
@Composable
fun FTChevron(modifier: Modifier = Modifier, tint: Color = FT.TextMuted, expanded: Boolean? = null) =
    FTIcon(
        when (expanded) { true -> FTIcons.ChevronUp; false -> FTIcons.ChevronDown; null -> FTIcons.ChevronRight },
        contentDescription = null, modifier = modifier, tint = tint, size = FTIconSize.Inline,
    )

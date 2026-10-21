package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// One day's tally, keyed by calendar date -- intercourse and masturbation
// counted separately since they get separate hues (sexual-health tab: red
// family = intercourse, blue family = masturbation).
data class DayActivity(val intercourseCount: Int = 0, val masturbationCount: Int = 0)

private val TOOLTIP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

// A GitHub-style calendar grid, weeks x 7 days, ending today. No existing
// day-grid component in this app (BodyHeatMap.kt is a muscle-silhouette
// heatmap, not a calendar) -- this is the first one. Deliberately plain
// Column/Row, not LazyVerticalGrid: the window is at most ~90 cells, the
// same "small fixed grid, no laziness needed" call DatePickers.kt's
// ButtonGrid already makes.
@Composable
fun ActivityHeatmap(days: Map<LocalDate, DayActivity>, modifier: Modifier = Modifier, weeks: Int = 12) {
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    // Align the grid to end on the current week's Sunday so columns are
    // stable Mon..Sun, not a ragged trailing week.
    val endOfWeek = today.plusDays((7 - today.dayOfWeek.value.toLong()) % 7)
    val startDate = endOfWeek.minusDays(weeks * 7L - 1)
    val cell = 14.dp

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (w in 0 until weeks) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (d in 0 until 7) {
                        val date = startDate.plusDays(w * 7L + d)
                        HeatCell(
                            activity = days[date],
                            size = cell,
                            isFuture = date.isAfter(today),
                            onClick = { selected = date },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LegendDot(FT.Category.Arousal.c500, "INTERCOURSE")
            LegendDot(FT.Category.Wellbeing.c500, "MASTURBATION")
        }
        selected?.let { date ->
            val a = days[date]
            val text = when {
                a == null || (a.intercourseCount == 0 && a.masturbationCount == 0) -> "No activity logged."
                else -> listOfNotNull(
                    a.intercourseCount.takeIf { it > 0 }?.let { "$it intercourse" },
                    a.masturbationCount.takeIf { it > 0 }?.let { "$it masturbation" },
                ).joinToString(" · ")
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(date.format(TOOLTIP_DATE), style = FTType.Value, color = FT.TextSecondary)
                Text(text, style = FTType.BodySmall, color = FT.TextPrimary)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, style = FTType.Label, color = FT.TextSecondary)
    }
}

@Composable
private fun HeatCell(activity: DayActivity?, size: Dp, isFuture: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(2.dp)
    val hasIntercourse = (activity?.intercourseCount ?: 0) > 0
    val hasMasturbation = (activity?.masturbationCount ?: 0) > 0
    val emptyColor = if (isFuture) Color.Transparent else FT.GlassFill

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .ftClickable(enabled = !isFuture, onClick = onClick, shape = shape),
    ) {
        when {
            hasIntercourse && hasMasturbation -> Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(shadeFor(FT.Category.Arousal, activity!!.intercourseCount)))
                Box(Modifier.weight(1f).fillMaxHeight().background(shadeFor(FT.Category.Wellbeing, activity.masturbationCount)))
            }
            hasIntercourse -> Box(Modifier.fillMaxSize().background(shadeFor(FT.Category.Arousal, activity!!.intercourseCount)))
            hasMasturbation -> Box(Modifier.fillMaxSize().background(shadeFor(FT.Category.Wellbeing, activity!!.masturbationCount)))
            else -> Box(Modifier.fillMaxSize().background(emptyColor))
        }
    }
}

// Darker shade = more instances that day (1 -> c300 lightest, 4+ -> c900 darkest).
private fun shadeFor(category: FuturisticMaterialTokens.CategoryColor, count: Int): Color = when {
    count >= 4 -> category.c900
    count == 3 -> category.c700
    count == 2 -> category.c500
    else -> category.c300
}

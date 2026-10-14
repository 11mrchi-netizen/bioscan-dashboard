package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.levels.SkillLevel
import com.bioscan.fieldterminal.domain.preparePerformanceTrendData
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTBar
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfidenceChip
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.components.confidenceLevel
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// Shared scaffolding for the three skill pages (Running / Strength / Conditioning): header, scrolling column,
// async loading from UserDataCache, the level hero, section labels and the common trend chart.

@Composable
fun SkillPage(title: String, subtitle: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack, title = title, subtitle = subtitle, accent = FT.DomainUser)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) { content() }
    }
}

// Runs `load` once; shows a spinner, the content, or an honest failure -- never a blank page.
@Composable
fun <T : Any> LoadedContent(load: suspend () -> T, content: @Composable (T) -> Unit) {
    val state: State<Result<T>?> = produceState<Result<T>?>(initialValue = null) { value = runCatching { load() } }
    val result = state.value
    if (result == null) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = FT.Emerald) }
        return
    }
    val value = result.getOrNull()
    if (value != null) content(value)
    else FTDataState(DataAvailability.Unavailable, "Couldn't load this history — check your connection and reopen the page.")
}

@Composable
fun SkillHeroCard(skill: Skill, level: SkillLevel, info: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    FTCard(title = "${skill.title} LEVEL", info = info) {
        val position = level.position
        if (position == null) {
            FTDataState(DataAvailability.Building, level.reason)
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(level.label, style = FTType.MetricMedium, color = FT.TextPrimary)
                level.headline?.let { Text(it, style = FTType.Value, color = FT.TextSecondary) }
            }
            FTBar(fraction = (position / 100.0).toFloat(), color = skillColor(skill), showThumb = true)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(level.basis?.chip ?: "", style = FTType.Micro, color = FT.TextMuted)
                if (level.provisional) Text("PROVISIONAL", style = FTType.Micro, color = FT.Warning)
                FTConfidenceChip(confidenceLevel(level.confidence))
            }
        }
        extra()
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), style = FTType.LabelCaps, color = FT.TextMuted)
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(label, style = FTType.Micro, color = FT.TextMuted)
    }
}

// RAW / 7D / 28D trend for any slow-moving (date, value) series -- the chart the Training tile used for VO2max and
// lactate threshold, now shared by the Running page.
@Composable
fun PerformanceTrend(
    series: List<Pair<LocalDate, Double>>,
    timeframe: PerformanceTimeframe,
    valueFormat: (Double) -> String,
    unit: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val data = preparePerformanceTrendData(series, timeframe)
        if (series.size < 2 || data.raw.size < 2) {
            Text(
                if (series.size < 2) "Not enough readings yet to plot a trend." else "Not enough readings in the last ${timeframe.label.lowercase()} to plot a trend.",
                style = FTType.Caption, color = FT.TextSecondary,
            )
            return@Column
        }
        DateTrendLine(
            series = listOf(
                TrendSeries(data.raw, FT.TextMuted, "RAW"),
                TrendSeries(data.avg7d, FT.Info, "7D"),
                TrendSeries(data.avg28d, FT.Emerald, "28D"),
            ),
            valueFormat = valueFormat,
            unit = unit,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("RAW", FT.TextMuted)
            LegendDot("7D AVG", FT.Info)
            LegendDot("28D AVG", FT.Emerald)
        }
    }
}

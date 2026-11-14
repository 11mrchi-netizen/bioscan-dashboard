package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.levels.SkillLevel
import com.bioscan.fieldterminal.ui.components.FTBar
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.SkeletonSections
import com.bioscan.fieldterminal.ui.components.rememberCacheVersion
import com.bioscan.fieldterminal.ui.screens.detail.EmptyLine
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Shared scaffolding for the three skill pages (Running / Strength / Conditioning): header, scrolling column,
// async loading from UserDataCache, the level hero, section labels and the common trend chart.

// Runs `load` once; shows a spinner, the content, or an honest failure -- never a blank page.
@Composable
fun <T : Any> LoadedContent(load: suspend () -> T, content: @Composable (T) -> Unit) {
    // Keyed on the cache version: when a background refresh brings newer data, the content loads again (from memory).
    val version = rememberCacheVersion()
    val state: State<Result<T>?> = produceState<Result<T>?>(initialValue = null, version) {
        val result = catching { load() }
        if (result.isSuccess || value == null) value = result
    }
    val result = state.value
    if (result == null) {
        SkeletonSections()
        return
    }
    val value = result.getOrNull()
    if (value != null) content(value)
    else FTDataState(DataAvailability.Unavailable, "Couldn't load this history — check your connection and reopen the page.")
}

@Composable
fun SkillHeroCard(skill: Skill, level: SkillLevel, info: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    Section("${skill.title} LEVEL", info = info) {
        val position = level.position
        if (position == null) {
            EmptyLine(level.reason ?: "Not enough data yet.")
        } else {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(level.label, style = FTType.MetricMedium, color = FT.TextPrimary)
                level.headline?.let { Text(it, style = FTType.Value, color = FT.TextSecondary) }
            }
            FTBar(fraction = (position / 100.0).toFloat(), color = skillColor(skill), showThumb = true)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(level.basis?.chip ?: "", style = FTType.Micro, color = FT.TextMuted)
                if (level.provisional) Text("PROVISIONAL", style = FTType.Micro, color = FT.Warn)
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


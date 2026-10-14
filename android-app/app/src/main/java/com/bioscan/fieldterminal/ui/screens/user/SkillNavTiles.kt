package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.levels.SkillLevel
import com.bioscan.fieldterminal.ui.components.FTBar
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

enum class Skill(val title: String) { RUNNING("RUNNING"), STRENGTH("STRENGTH"), CONDITIONING("CONDITIONING") }

// Activity is a Category-tier colour; the three skills are shades of it so they read as one family and never
// collide with state colours (emerald/amber/red).
fun skillColor(skill: Skill): Color = when (skill) {
    Skill.RUNNING -> FT.Category.Activity.c500
    Skill.STRENGTH -> FT.Category.Activity.c300
    Skill.CONDITIONING -> FT.Category.Activity.c700
}

// The three buttons under the Aging card. They double as the domain levels: each shows the level label, the
// basis it was computed against, and a PROVISIONAL tag while evidence is thin -- tap for the full page.
@Composable
fun SkillNavTiles(
    running: SkillLevel?,
    strength: SkillLevel?,
    conditioning: SkillLevel?,
    onOpen: (Skill) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkillTile(Skill.RUNNING, running, { onOpen(Skill.RUNNING) }, Modifier.weight(1f))
        SkillTile(Skill.STRENGTH, strength, { onOpen(Skill.STRENGTH) }, Modifier.weight(1f))
        SkillTile(Skill.CONDITIONING, conditioning, { onOpen(Skill.CONDITIONING) }, Modifier.weight(1f))
    }
}

@Composable
private fun SkillTile(skill: Skill, level: SkillLevel?, onClick: () -> Unit, modifier: Modifier) {
    val color = skillColor(skill)
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier = modifier
            .background(FT.GlassFill, shape)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(skill.title, style = FTType.Label, color = color, maxLines = 1, modifier = Modifier.weight(1f))
            Text("›", style = FTType.Value, color = FT.TextMuted)
        }
        when {
            level == null -> Text("…", style = FTType.RowTitle, color = FT.TextMuted)
            level.position == null -> {
                Text("No level yet", style = FTType.RowTitle, color = FT.TextMuted)
                Text(level.reason ?: "", style = FTType.Micro, color = FT.TextMuted, maxLines = 3)
            }
            else -> {
                Text(level.label, style = FTType.RowTitle, color = FT.TextPrimary, maxLines = 1)
                FTBar(fraction = (level.position / 100.0).toFloat(), color = color, height = 5.dp)
                level.headline?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary, maxLines = 1) }
                Text(
                    (level.basis?.chip ?: "") + if (level.provisional) " · PROVISIONAL" else "",
                    style = FTType.Micro,
                    color = if (level.provisional) FT.Warning else FT.TextMuted,
                    maxLines = 2,
                )
            }
        }
    }
}

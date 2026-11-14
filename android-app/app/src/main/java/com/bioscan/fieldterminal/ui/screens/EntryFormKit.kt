package com.bioscan.fieldterminal.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.AddEntryChoice
import com.bioscan.fieldterminal.domain.SUPPLEMENT_QTY_STEP
import com.bioscan.fieldterminal.domain.formatSupplementQty
import com.bioscan.fieldterminal.domain.target
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTIcon
import com.bioscan.fieldterminal.ui.components.FTIconSize
import com.bioscan.fieldterminal.ui.components.FTIcons
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The small parts every entry form shares: the sheet's back header, labels, chip rows, toggles, the save button.

@Composable
internal fun SheetBackHeader(label: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "BACK",
            style = sheetActionLabelStyle,
            color = FT.TextSecondary,
            modifier = Modifier.ftClickable(onClick = onBack),
        )
        Text(label, style = sheetHeaderTitleStyle, color = FT.DomainLog)
    }
}

// UX review P3: one labelled group of add-entry choices, three per row. Labels are words a person reads (Inter, contract
// A7); the dot is the entry's category colour, an identity mark the Log feed uses for the same kinds, never a state.
@Composable
internal fun ChoiceSection(label: String, choices: List<AddEntryChoice>, onPick: (AddEntryChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = FTType.LabelCaps, color = FT.TextMuted)
        choices.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { choice -> ChoiceTile(choice, Modifier.weight(1f).fillMaxHeight()) { onPick(choice) } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChoiceTile(choice: AddEntryChoice, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(choiceColor(choice)))
        // Hyphenated, so a long word at a large font size breaks as "Encoun-ter", not "Encounte / r".
        Text(choice.label, style = FTType.ButtonLabel.copy(hyphens = Hyphens.Auto), color = FT.TextPrimary, maxLines = 2)
    }
}

private fun choiceColor(choice: AddEntryChoice): Color = when (choice) {
    AddEntryChoice.Food, AddEntryChoice.RunFuel -> FT.Category.Intake.c500
    AddEntryChoice.Drink -> FT.Category.Intake.c300
    AddEntryChoice.Supplements -> FT.Category.Intake.c700
    AddEntryChoice.Wellbeing -> FT.Category.Wellbeing.c500
    AddEntryChoice.Stool -> FT.Category.Digestion.c500
    AddEntryChoice.HealthCheckIn, AddEntryChoice.IllnessLog, AddEntryChoice.SymptomCheck -> FT.DomainHeart
    AddEntryChoice.Grip, AddEntryChoice.BodyCheckIn, AddEntryChoice.SweatTest, AddEntryChoice.ScaleWeighIn -> FT.Category.Activity.c500
    AddEntryChoice.Event -> FT.DomainTraining
    AddEntryChoice.SexualActivity, AddEntryChoice.Encounter -> FT.Category.Arousal.c500
    AddEntryChoice.Note -> FT.TextSecondary
}

@Composable
internal fun FormLabel(text: String) {
    Text(
        text,
        style = sheetActionLabelStyle,
        color = FT.TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
internal fun SaveButton(saving: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FTButton(label = if (saving) "SAVING..." else "SAVE", enabled = enabled && !saving, onClick = onClick, emphasis = FTButtonEmphasis.Primary)
}

@Composable
internal fun QtyRow(qty: Double, step: Double = SUPPLEMENT_QTY_STEP, words: ((Double) -> String?)? = null, onChange: (Double) -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("QTY", style = FTType.LabelCaps, color = if (qty > 0.0) FT.TextMuted else FT.Warn)
        for (delta in listOf(-step, step)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(shape)
                    .background(FT.Glass, shape)
                    .border(FT.BorderWidth, FT.GlassBorder, shape)
                    .ftClickable(shape = shape) {
                        // 0 is allowed: the item is not taken this time and is left out of the save.
                        // Rounded so a third of a dose (one of three softgels) adds back up to exactly one dose.
                        val next = Math.round((qty + delta) * 1e6) / 1e6
                        if (next >= 0.0) onChange(next)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (delta < 0) "−" else "+", style = FTType.Telemetry, color = FT.TextSecondary)
            }
            // A fixed width, so the steppers of every row line up whatever the amount reads.
            if (delta < 0) Text(
                if (qty > 0.0) (words?.invoke(qty) ?: formatSupplementQty(qty)) else "NOT TAKEN",
                style = FTType.Telemetry, color = if (qty > 0.0) FT.TextPrimary else FT.Warn,
                maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.width(112.dp),
            )
        }
    }
}

@Composable
internal fun BundleToggleRow(label: String, itemNames: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, if (checked) FT.SelectedBorder else FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .background(if (checked) FT.SelectedGlass else Color.Transparent, RoundedCornerShape(FT.RadiusModule))
            .ftClickable(onClick = onToggle, shape = RoundedCornerShape(FT.RadiusModule))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckboxGlyph(checked)
        Column {
            Text(label, style = FTType.Telemetry, color = FT.TextPrimary)
            Text(itemNames, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
internal fun CheckToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, if (checked) FT.SelectedBorder else FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .background(if (checked) FT.SelectedGlass else Color.Transparent, RoundedCornerShape(FT.RadiusModule))
            .ftClickable(onClick = onToggle, shape = RoundedCornerShape(FT.RadiusModule))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CheckboxGlyph(checked)
        Text(label, style = FTType.RowTitle, color = FT.TextPrimary)
    }
}

@Composable
private fun CheckboxGlyph(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(1.dp, if (checked) FT.Ok else FT.GlassBorder)
            .background(if (checked) FT.Ok else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            FTIcon(FTIcons.Check, null, tint = FT.Base, size = FTIconSize.Inline)
        }
    }
}

@Composable
internal fun PhotoActionButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) FT.DomainLog else FT.GlassBorder
    Box(
        modifier = modifier
            .border(FT.BorderWidth, color, RoundedCornerShape(FT.RadiusSmall))
            .ftClickable(enabled = enabled, onClick = onClick, shape = RoundedCornerShape(FT.RadiusSmall))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = sheetActionLabelStyle, color = color, maxLines = 1, softWrap = false)
    }
}

// Extracted from StoolForm's own Bristol-scale chip row -- a small bounded
// integer scale is also exactly what Encounter's 1-5 rating needs, so this
// is shared rather than a second copy of the same 20 lines.
@Composable
internal fun IntChipRow(range: IntRange, selected: Int?, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (n in range) {
            val isSelected = selected == n
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(FT.BorderWidth, if (isSelected) Color.Transparent else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                    .background(if (isSelected) FT.PlatinumSolid else FT.GlassFill, RoundedCornerShape(FT.RadiusSmall))
                    .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall)) { onSelect(n) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$n",
                    style = FTType.Value,
                    color = if (isSelected) FT.PlatinumInk else FT.TextSecondary,
                )
            }
        }
    }
}

internal val YES_NO_OPTIONS = listOf("Yes", "No")

// Tap-to-select chip row over a small fixed vocabulary, generalizing
// StoolForm's Bristol-type row (below) to string values -- these fields are
// optional (unlike Bristol), so tapping the already-selected chip again
// clears it back to null rather than always leaving exactly one selected.
@Composable
internal fun TextChipRow(options: List<String>, selected: String?, perRow: Int = 4, onSelect: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(perRow).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowOptions.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            // a 48 dp target, and the choice announced as selected (polish: touch targets, accessibility)
                            .heightIn(min = 48.dp)
                            .semantics { this.selected = isSelected }
                            .border(FT.BorderWidth, if (isSelected) Color.Transparent else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                            .background(if (isSelected) FT.PlatinumSolid else FT.GlassFill, RoundedCornerShape(FT.RadiusSmall))
                            .ftClickable(shape = RoundedCornerShape(FT.RadiusSmall), role = Role.RadioButton, onClickLabel = option) { onSelect(if (isSelected) null else option) }
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            option.uppercase(),
                            style = FTType.MonoCaption,
                            color = if (isSelected) FT.PlatinumInk else FT.TextSecondary,
                        )
                    }
                }
                repeat(perRow - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Text styles of a sheet's own chrome, shared by every sheet in this package: its title, and its action labels. */
internal val sheetHeaderTitleStyle = FTType.SectionTitle
internal val sheetActionLabelStyle = FTType.LabelCaps

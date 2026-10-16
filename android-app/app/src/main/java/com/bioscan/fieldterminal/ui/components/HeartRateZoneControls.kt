package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.ZoneAnchors
import com.bioscan.fieldterminal.domain.ZoneMethod
import com.bioscan.fieldterminal.domain.anchorsCaption
import com.bioscan.fieldterminal.domain.availableMethods
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The zone-method switch on a session's heart-rate chart (domain/HeartRateZones.kt). Only the methods this session
// has inputs for are offered; the caption names the numbers the chosen zones rest on, as they were on the session's date.

/** What a session's heart-rate chart needs for zones: the anchors on its date, its sport, the person's default. */
data class SessionZoneInputs(val anchors: ZoneAnchors, val sport: String?, val defaultMethod: ZoneMethod)

@Composable
fun HeartRateZoneMethodSwitch(inputs: SessionZoneInputs, selected: ZoneMethod, onSelect: (ZoneMethod) -> Unit, modifier: Modifier = Modifier) {
    val methods = availableMethods(inputs.anchors, inputs.sport)
    Column(modifier) {
        if (methods.size > 1) {
            SegmentedToggle(options = methods, selected = selected, labelOf = { it.label }, onSelect = onSelect, accentColor = FT.DomainTraining)
        }
        Text(
            "Zones: " + anchorsCaption(selected, inputs.anchors),
            style = FTType.Caption,
            color = FT.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.domain.weatherCodeLabel
import com.bioscan.fieldterminal.domain.weatherCodeSymbol
import com.bioscan.fieldterminal.ui.components.FTIcon
import com.bioscan.fieldterminal.ui.components.FTIconSize
import com.bioscan.fieldterminal.ui.components.FTIcons
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The Map: the weather row and its sheet.

@Composable
internal fun WeatherRow(state: WeatherUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .ftClickable(onClick = onClick, shape = RoundedCornerShape(FT.RadiusSmall))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("WEATHER AT SESSION TIME", style = sheetActionLabelStyle, color = FT.TextSecondary)
        when (state) {
            is WeatherUiState.Loaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(weatherCodeSymbol(state.weather.code), fontSize = 16.sp)
                Text("%.0f°".format(state.weather.temperatureC), style = sheetActionLabelStyle, color = FT.TextPrimary)
            }
            WeatherUiState.Loading -> Text("···", style = sheetActionLabelStyle, color = FT.TextSecondary)
            is WeatherUiState.Failed -> FTIcon(FTIcons.Warning, "Weather unavailable", tint = FT.Bad, size = FTIconSize.Inline)
            WeatherUiState.Idle -> Text("VIEW", style = sheetActionLabelStyle, color = FT.DomainMap)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeatherSheet(state: WeatherUiState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = FT.SheetShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("FORECAST AT SESSION TIME", style = sheetHeaderTitleStyle, color = FT.DomainMap)
            when (state) {
                is WeatherUiState.Loaded -> {
                    val w = state.weather
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(weatherCodeSymbol(w.code), fontSize = 32.sp)
                        Column {
                            Text(
                                text = "%.0f°C".format(w.temperatureC),
                                style = FTType.PageTitle,
                                color = FT.TextPrimary,
                            )
                            Text(weatherCodeLabel(w.code), style = FTType.BodySmall, color = FT.TextSecondary)
                        }
                    }
                    Text(
                        text = "Wind %.0f km/h · Precip %.1f mm".format(w.windSpeedKmh, w.precipitationMm),
                        style = FTType.Value,
                        color = FT.TextSecondary,
                    )
                }
                WeatherUiState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainMap)
                }
                is WeatherUiState.Failed -> Text(
                    "Couldn't load the forecast (${state.message}).",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                WeatherUiState.Idle -> Text("—", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            Text(
                "Forecast, not a guarantee — Open-Meteo, no personal weather station.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Setup's own hub: a flat card dump got too big to scan, so this is now 4
// buttons into their own pushed sub-screens (ui/screens/settings/), same
// push-and-back pattern as the Status tiles. Sign-out stays here -- it's an
// account action for the whole app, not a sub-section's content.
@Composable
fun SettingsScreen(
    scope: CoroutineScope,
    onOpenUser: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenConnectedServices: () -> Unit,
    onOpenTraining: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "SETUP", context = "PROFILE · NOTIFICATIONS · CONNECTED SERVICES · TRAINING")
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsNavRow(title = "PROFILE & GOALS", subtitle = "Profile, nutrition goals, data capture", onClick = onOpenUser)
            SettingsNavRow(title = "NOTIFICATIONS", subtitle = "Quiet hours", onClick = onOpenNotifications)
            SettingsNavRow(title = "CONNECTED SERVICES", subtitle = "AI nutrition, Map, Health Connect, Zepp", onClick = onOpenConnectedServices)
            // UX review P5 / P8: Setup keeps only true settings; the program library and import moved into PLAN A NEW BLOCK.
            SettingsNavRow(title = "TRAINING SETUP", subtitle = "Equipment, session times", onClick = onOpenTraining)

            Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                FTButton(label = "SIGN OUT") { scope.launch { GoogleAuthManager.signOut() } }
            }
        }
    }
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusCard))
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusCard))
            .ftClickable(onClick = onClick, shape = RoundedCornerShape(FT.RadiusCard))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = FTType.RowTitle, color = FT.TextPrimary)
            Text(subtitle, style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", style = FTType.SectionTitle, color = FT.TextSecondary)
    }
}

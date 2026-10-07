package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.auth.GoogleAuthManager
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Setup's own hub (DAV-?): a flat card dump got too big to scan, so this is
// now 3 buttons into their own pushed sub-screens (ui/screens/settings/),
// same push-and-back pattern as the Status tiles. Sign-out stays here --
// it's an account action for the whole app, not a sub-section's content.
@Composable
fun SettingsScreen(
    scope: CoroutineScope,
    onOpenUser: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenConnectedServices: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "SETUP", context = "USER · NOTIFICATIONS · CONNECTED SERVICES")
        Column(
            modifier = Modifier.fillMaxSize().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsNavRow(title = "USER", subtitle = "Profile, nutrition goals", onClick = onOpenUser)
            SettingsNavRow(title = "NOTIFICATIONS", subtitle = "Alert preferences", onClick = onOpenNotifications)
            SettingsNavRow(title = "CONNECTED SERVICES", subtitle = "AI nutrition, Map, Health Connect, Zepp", onClick = onOpenConnectedServices)

            Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                AmberButton(label = "SIGN OUT") { scope.launch { GoogleAuthManager.signOut() } }
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
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = FT.TextPrimary)
            Text(subtitle, style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp), color = FT.TextSecondary)
    }
}

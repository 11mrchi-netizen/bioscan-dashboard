package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.components.ComingSoonCard
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Setup > Notifications: single destination, no sub-tabs -- no notification
// preferences exist yet, stub placeholder until they do.
@Composable
fun NotificationsSettingsScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(title = "NOTIFICATIONS", context = "PREFERENCES", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            ComingSoonCard(title = "NOTIFICATIONS")
        }
    }
}

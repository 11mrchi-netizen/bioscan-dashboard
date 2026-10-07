package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.components.ComingSoonCard
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.UserTab
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Setup > User: opens on Profile per the hub's own nav. Neither tab has a
// real feature behind it yet -- stub placeholders until Profile fields and
// a Nutrition Goals editor actually exist.
@Composable
fun UserSettingsScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(UserTab.Profile) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(title = "USER", context = "PROFILE · NUTRITION GOALS", onBack = onBack)
        SubTabRow(items = UserTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (tab) {
                UserTab.Profile -> ComingSoonCard(title = "PROFILE")
                UserTab.NutritionGoals -> ComingSoonCard(title = "NUTRITION GOALS")
            }
        }
    }
}

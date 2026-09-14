package com.bioscan.fieldterminal.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.composable
import com.bioscan.fieldterminal.ui.screens.LogScreen
import com.bioscan.fieldterminal.ui.screens.MapScreen
import com.bioscan.fieldterminal.ui.screens.SettingsScreen
import com.bioscan.fieldterminal.ui.screens.status.StatusScreen

// Step 3 scaffold: the real 4-tab structure (mechanism only, no visual
// styling yet -- that's Step 4, gated on /design/ mockups). Bottom nav icons
// below are generic Material placeholders, not the mockup's exact inline-SVG
// icon shapes (pulse line, map pin, three lines, gear) -- Step 4 recreates
// those precisely; this step only needs *a* recognizable icon per tab.
@Composable
fun FieldTerminalNavHost() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route

            NavigationBar {
                TopLevelTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelTab.Status.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelTab.Status.route) { StatusScreen() }
            composable(TopLevelTab.Map.route) { MapScreen() }
            composable(TopLevelTab.Log.route) { LogScreen() }
            composable(TopLevelTab.Setup.route) { SettingsScreen(scope) }
        }
    }
}

private val TopLevelTab.icon
    get() = when (this) {
        TopLevelTab.Status -> Icons.Filled.Home
        TopLevelTab.Map -> Icons.Filled.LocationOn
        TopLevelTab.Log -> Icons.AutoMirrored.Filled.List
        TopLevelTab.Setup -> Icons.Filled.Settings
    }

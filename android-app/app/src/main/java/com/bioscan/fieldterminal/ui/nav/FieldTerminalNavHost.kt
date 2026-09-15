package com.bioscan.fieldterminal.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bioscan.fieldterminal.R
import com.bioscan.fieldterminal.ui.screens.LogScreen
import com.bioscan.fieldterminal.ui.screens.MapScreen
import com.bioscan.fieldterminal.ui.screens.SessionDetailScreen
import com.bioscan.fieldterminal.ui.screens.SettingsScreen
import com.bioscan.fieldterminal.ui.screens.status.StatusScreen
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles

@Composable
fun FieldTerminalNavHost() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = FieldColors.Ground,
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route

            FieldBottomBar(currentRoute = currentRoute) { tab ->
                navController.navigate(tab.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
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
            composable(TopLevelTab.Log.route) {
                LogScreen(onOpenSessionDetail = { id -> navController.navigate("session_detail/$id") })
            }
            composable(TopLevelTab.Setup.route) { SettingsScreen(scope) }
            // Phase G4: the app's first pushed detail route (every other
            // screen so far is a flat tab or a bottom sheet) -- a session's
            // on-demand Health Connect time-series detail, reached by
            // tapping an Exercise entry's DETAIL action in the Log tab.
            composable("session_detail/{sessionId}") { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull()
                if (sessionId != null) {
                    SessionDetailScreen(sessionId = sessionId, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

// Custom bottom bar rather than Material3's NavigationBar/NavigationBarItem --
// their built-in selected-state treatment (a rounded pill indicator) doesn't
// match design/README.md's exact spec (a top border + background tint per
// cell, square corners throughout), so this recreates the mockup's PipNavA
// component directly instead of fighting the default component's styling.
@Composable
private fun FieldBottomBar(currentRoute: String?, onTabSelected: (TopLevelTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(78.dp)
            .background(FieldColors.Panel)
            .drawBehind {
                val strokeWidth = 2.dp.toPx()
                drawLine(
                    color = FieldColors.Hairline,
                    start = Offset(0f, strokeWidth / 2),
                    end = Offset(size.width, strokeWidth / 2),
                    strokeWidth = strokeWidth,
                )
            }
            .padding(bottom = 14.dp),
    ) {
        // A `for` loop, not `.forEach { }` -- `weight()` needs RowScope as its
        // implicit receiver, and a real build confirmed the Kotlin compiler
        // doesn't reliably propagate that receiver into a non-inline lambda
        // passed to `entries.forEach` here. A for-loop body is inlined in
        // place, so there's no separate lambda for the receiver to fail to
        // reach.
        for (tab in TopLevelTab.entries) {
            val selected = currentRoute == tab.route
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (selected) {
                            Modifier
                                .padding(top = 1.dp) // net -2dp margin vs the 3dp top border below
                                .drawBehind {
                                    drawRect(color = FieldColors.Amber.copy(alpha = 0.08f))
                                    val strokeWidth = 3.dp.toPx()
                                    drawLine(
                                        color = FieldColors.Amber,
                                        start = Offset(0f, strokeWidth / 2),
                                        end = Offset(size.width, strokeWidth / 2),
                                        strokeWidth = strokeWidth,
                                    )
                                }
                        } else {
                            Modifier
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onTabSelected(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val tint = if (selected) FieldColors.Amber else FieldColors.InkMuted
                Icon(
                    painter = painterResource(tab.iconRes),
                    contentDescription = tab.label,
                    tint = tint,
                    modifier = Modifier.size(21.dp),
                )
                Text(
                    text = tab.label,
                    style = FieldTextStyles.tabBarLabel,
                    color = tint,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

private val TopLevelTab.iconRes
    get() = when (this) {
        TopLevelTab.Status -> R.drawable.ic_tab_status
        TopLevelTab.Map -> R.drawable.ic_tab_map
        TopLevelTab.Log -> R.drawable.ic_tab_log
        TopLevelTab.Setup -> R.drawable.ic_tab_setup
    }

package com.bioscan.fieldterminal.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bioscan.fieldterminal.R
import com.bioscan.fieldterminal.ui.screens.AgingProfileScreen
import com.bioscan.fieldterminal.ui.screens.LogScreen
import com.bioscan.fieldterminal.ui.screens.SessionDetailScreen
import com.bioscan.fieldterminal.ui.screens.SettingsScreen
import com.bioscan.fieldterminal.ui.screens.UserProfileScreen
import com.bioscan.fieldterminal.ui.screens.status.FuelTileScreen
import com.bioscan.fieldterminal.ui.screens.status.HeartTileScreen
import com.bioscan.fieldterminal.ui.screens.status.NutrientBreakdownScreen
import com.bioscan.fieldterminal.ui.screens.status.LabsTileScreen
import com.bioscan.fieldterminal.ui.screens.status.StatusScreen
import com.bioscan.fieldterminal.ui.screens.status.TrainingTileScreen
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono

@Composable
fun FieldTerminalNavHost() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = FT.Base,
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
            composable(TopLevelTab.Status.route) {
                StatusScreen(onOpenTile = { tile -> navController.navigate(tile.route) })
            }
            // DAV-70 (First feedback fixes): the 4 tile pages, pushed routes
            // like session_detail rather than nested inside Status's own
            // NavHost entry -- keeps their own back stack entries so
            // Android's system back button behaves the same as everywhere
            // else in this app.
            composable(TileRoute.Training.route) {
                TrainingTileScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSessionDetail = { id -> navController.navigate("session_detail/$id") },
                )
            }
            composable(TileRoute.Fuel.route) {
                FuelTileScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNutrientBreakdown = { navController.navigate("nutrient_breakdown") },
                )
            }
            composable("nutrient_breakdown") { NutrientBreakdownScreen(onBack = { navController.popBackStack() }) }
            composable(TileRoute.Heart.route) { HeartTileScreen(onBack = { navController.popBackStack() }) }
            composable(TileRoute.Labs.route) { LabsTileScreen(onBack = { navController.popBackStack() }) }
            composable(TopLevelTab.User.route) {
                UserProfileScreen(onOpenAging = { navController.navigate("aging_profile") })
            }
            composable("aging_profile") { AgingProfileScreen(onBack = { navController.popBackStack() }) }
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

// Floating dock: inset from the screen edges, soft 24dp geometry, glass-border
// outline, and a rounded selected pill (accent tint + accent outline) instead
// of the old full-width square bar with a top rule. Custom rather than
// Material3's NavigationBar so the pill, accent-per-tab and Roboto Mono labels
// follow the Futuristic Material contract. Selected state is accent color AND
// the filled pill AND the label, never color alone.
@Composable
private fun FieldBottomBar(currentRoute: String?, onTabSelected: (TopLevelTab) -> Unit) {
    val dockShape = RoundedCornerShape(FT.RadiusCard)
    val pillShape = RoundedCornerShape(FT.RadiusModule)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FT.Base)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(dockShape)
                .background(FT.Surface)
                .border(FT.BorderWidth, FT.GlassBorder, dockShape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // A `for` loop, not `.forEach { }` -- `weight()` needs RowScope as
            // its implicit receiver, and a real build confirmed the Kotlin
            // compiler doesn't reliably propagate that receiver into a
            // non-inline lambda passed to `entries.forEach` here.
            for (tab in TopLevelTab.entries) {
                val selected = currentRoute == tab.route
                val accent = tab.domainAccent
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pillShape)
                        .then(
                            if (selected) {
                                Modifier
                                    .background(accent.copy(alpha = 0.14f), pillShape)
                                    .border(FT.BorderWidth, accent.copy(alpha = 0.55f), pillShape)
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
                    val tint = if (selected) accent else FT.TextSecondary
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = tab.label,
                        tint = tint,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = tab.label,
                        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.14.em),
                        color = tint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private val TopLevelTab.iconRes
    get() = when (this) {
        TopLevelTab.Status -> R.drawable.ic_tab_status
        TopLevelTab.User -> R.drawable.ic_tab_user
        TopLevelTab.Log -> R.drawable.ic_tab_log
        TopLevelTab.Setup -> R.drawable.ic_tab_setup
    }

// User and Log have documented domain accents (contract section 4); Status
// and Setup aren't "domains" with their own accent in that table, so they
// default to Emerald, the contract's own primary/default signal.
private val TopLevelTab.domainAccent
    get() = when (this) {
        TopLevelTab.Status -> FT.Emerald
        TopLevelTab.User -> FT.DomainUser
        TopLevelTab.Log -> FT.DomainLog
        TopLevelTab.Setup -> FT.Emerald
    }

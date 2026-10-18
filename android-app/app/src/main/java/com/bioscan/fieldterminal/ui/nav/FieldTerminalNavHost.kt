package com.bioscan.fieldterminal.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bioscan.fieldterminal.R
import com.bioscan.fieldterminal.domain.notifications.NotificationDestination
import com.bioscan.fieldterminal.domain.notifications.notificationDestination
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.AgingProfileScreen
import com.bioscan.fieldterminal.ui.screens.LogScreen
import com.bioscan.fieldterminal.ui.screens.SessionDetailScreen
import com.bioscan.fieldterminal.ui.screens.SettingsScreen
import com.bioscan.fieldterminal.ui.screens.settings.ConnectedServicesScreen
import com.bioscan.fieldterminal.ui.screens.settings.NotificationsSettingsScreen
import com.bioscan.fieldterminal.ui.screens.settings.UserSettingsScreen
import com.bioscan.fieldterminal.ui.screens.TrainingBlocksScreen
import com.bioscan.fieldterminal.ui.screens.UserProfileScreen
import com.bioscan.fieldterminal.ui.screens.user.ConditioningSkillScreen
import com.bioscan.fieldterminal.ui.screens.user.RunningSkillScreen
import com.bioscan.fieldterminal.ui.screens.user.Skill
import com.bioscan.fieldterminal.ui.screens.user.StrengthSkillScreen
import com.bioscan.fieldterminal.ui.screens.status.FuelTileScreen
import com.bioscan.fieldterminal.ui.screens.status.HeartTileScreen
import com.bioscan.fieldterminal.ui.screens.status.NutrientBreakdownScreen
import com.bioscan.fieldterminal.ui.screens.status.LabsTileScreen
import com.bioscan.fieldterminal.ui.screens.status.PantryScreen
import com.bioscan.fieldterminal.ui.screens.status.StatusScreen
import com.bioscan.fieldterminal.ui.screens.status.TodayScreen
import com.bioscan.fieldterminal.ui.screens.status.TrainingTileScreen
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

@Composable
fun FieldTerminalNavHost(pendingNotificationKind: String? = null, onNotificationHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    // Set when a notification tap should land on a specific Labs subtab; LabsTileScreen consumes it.
    var labsRequestedTab by remember { mutableStateOf<LabsTab?>(null) }
    // Same hand-off for Training's subtabs (UX review F1: the Status injury flag lands on Training › Injury).
    var trainingRequestedTab by remember { mutableStateOf<TrainingTab?>(null) }
    // Analysis review phase B: the 22:25 evening check-in reminder opens Today with the check-in sheet up.
    var checkInRequested by remember { mutableStateOf(false) }
    // The monthly body check-in reminder opens Fuel › Body with the check-in sheet up.
    var bodyCheckInRequested by remember { mutableStateOf(false) }
    // Contract A4: the dock keeps lit the tab whose stack you are in, also on pushed screens (tiles, settings and detail
    // pages), which used to light nothing. Set whenever a top-level destination is shown and when a dock tab is tapped,
    // so a deep link pushed onto the current stack keeps that stack's tab.
    var dockTab by rememberSaveable { mutableStateOf(TopLevelTab.Status) }

    LaunchedEffect(pendingNotificationKind) {
        val kind = pendingNotificationKind ?: return@LaunchedEffect
        when (notificationDestination(kind)) {
            NotificationDestination.SUPPLEMENTS -> {
                labsRequestedTab = LabsTab.Supplements
                navController.navigate(TileRoute.Labs.route) { launchSingleTop = true }
            }
            NotificationDestination.LOG -> navController.navigate(TopLevelTab.Log.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            NotificationDestination.CHECKIN -> {
                checkInRequested = true
                navController.navigate("today") { launchSingleTop = true }
            }
            NotificationDestination.BODY_CHECKIN -> {
                bodyCheckInRequested = true
                navController.navigate(TileRoute.Fuel.route) { launchSingleTop = true }
            }
            NotificationDestination.NONE -> Unit
        }
        onNotificationHandled()
    }

    // Perf pass: a marker line per screen change so the http lines that follow can be attributed to it.
    androidx.compose.runtime.DisposableEffect(navController) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            com.bioscan.fieldterminal.util.Perf.mark(destination.route ?: "?")
            TopLevelTab.entries.firstOrNull { it.route == destination.route }?.let { dockTab = it }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    Scaffold(
        containerColor = FT.Base,
        bottomBar = {
            FieldBottomBar(selectedTab = dockTab) { tab ->
                dockTab = tab
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
                StatusScreen(
                    onOpenTile = { tile -> navController.navigate(tile.route) },
                    onOpenToday = { navController.navigate("today") },
                    onOpenInjury = {
                        trainingRequestedTab = TrainingTab.Injury
                        navController.navigate(TileRoute.Training.route) { launchSingleTop = true }
                    },
                    onOpenEvent = { id -> navController.navigate("event/$id") },
                )
            }
            // UX review P6: the Today page (was Daily Readiness) links to the cards it no longer repeats.
            composable("today") {
                TodayScreen(
                    openCheckIn = checkInRequested,
                    onCheckInHandled = { checkInRequested = false },
                    onBack = { navController.popBackStack() },
                    onOpenTrainingLoad = {
                        trainingRequestedTab = TrainingTab.Load
                        navController.navigate(TileRoute.Training.route) { launchSingleTop = true }
                    },
                    // Cardio is the Health tile's first tab, where a freshly pushed page opens.
                    onOpenCardio = { navController.navigate(TileRoute.Heart.route) { launchSingleTop = true } },
                    onOpenFuel = { navController.navigate(TileRoute.Fuel.route) { launchSingleTop = true } },
                )
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
                    onOpenPlanner = { navController.navigate("training_planner") },
                    onOpenRunning = { navController.navigate("skill_running") },
                    onOpenStrength = { navController.navigate("skill_strength") },
                    onOpenTrainingBlocks = { navController.navigate("training_blocks") },
                    onOpenEvent = { id -> navController.navigate("event/$id") },
                    requestedTab = trainingRequestedTab,
                    onRequestedTabConsumed = { trainingRequestedTab = null },
                )
            }
            composable("training_planner") {
                com.bioscan.fieldterminal.ui.screens.training.TrainingPlannerScreen(onBack = { navController.popBackStack() }, onCreated = { navController.popBackStack() })
            }
            composable(TileRoute.Fuel.route) {
                FuelTileScreen(
                    onBack = { navController.popBackStack() },
                    onOpenNutrientBreakdown = { navController.navigate("nutrient_breakdown") },
                    openBodyCheckIn = bodyCheckInRequested,
                    onBodyCheckInHandled = { bodyCheckInRequested = false },
                )
            }
            composable("nutrient_breakdown") { NutrientBreakdownScreen(onBack = { navController.popBackStack() }) }
            composable(TileRoute.Heart.route) { HeartTileScreen(onBack = { navController.popBackStack() }) }
            composable(TileRoute.Labs.route) {
                LabsTileScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPantry = { navController.navigate("pantry") },
                    requestedTab = labsRequestedTab,
                    onRequestedTabConsumed = { labsRequestedTab = null },
                )
            }
            composable("pantry") { PantryScreen(onBack = { navController.popBackStack() }) }
            composable(TopLevelTab.User.route) {
                UserProfileScreen(
                    onOpenAging = { navController.navigate("aging_profile") },
                    // UX review P5: the User tab's block card opens Training › Plan, the block's one home.
                    onOpenTrainingPlan = {
                        trainingRequestedTab = TrainingTab.Plan
                        navController.navigate(TileRoute.Training.route) { launchSingleTop = true }
                    },
                    onOpenSkill = { skill ->
                        navController.navigate(
                            when (skill) {
                                Skill.RUNNING -> "skill_running"
                                Skill.STRENGTH -> "skill_strength"
                                Skill.CONDITIONING -> "skill_conditioning"
                            },
                        )
                    },
                )
            }
            composable("aging_profile") { AgingProfileScreen(onBack = { navController.popBackStack() }) }
            composable("skill_running") { RunningSkillScreen(onBack = { navController.popBackStack() }) }
            composable("skill_strength") { StrengthSkillScreen(onBack = { navController.popBackStack() }) }
            composable("skill_conditioning") { ConditioningSkillScreen(onBack = { navController.popBackStack() }) }
            composable("training_blocks") {
                TrainingBlocksScreen(onBack = { navController.popBackStack() })
            }
            composable(TopLevelTab.Log.route) {
                LogScreen(onOpenSessionDetail = { id -> navController.navigate("session_detail/$id") })
            }
            composable(TopLevelTab.Setup.route) {
                SettingsScreen(
                    scope = scope,
                    onOpenUser = { navController.navigate(SettingsRoute.User.route) },
                    onOpenNotifications = { navController.navigate(SettingsRoute.Notifications.route) },
                    onOpenConnectedServices = { navController.navigate(SettingsRoute.ConnectedServices.route) },
                    onOpenTraining = { navController.navigate(SettingsRoute.Training.route) },
                )
            }
            composable(SettingsRoute.User.route) { UserSettingsScreen(scope = scope, onBack = { navController.popBackStack() }) }
            composable(SettingsRoute.Notifications.route) { NotificationsSettingsScreen(scope = scope, onBack = { navController.popBackStack() }) }
            composable(SettingsRoute.ConnectedServices.route) { ConnectedServicesScreen(scope = scope, onBack = { navController.popBackStack() }) }
            composable(SettingsRoute.Training.route) {
                com.bioscan.fieldterminal.ui.screens.training.TrainingSettingsScreen(onBack = { navController.popBackStack() })
            }
            // Phase G4: the app's first pushed detail route (every other
            // screen so far is a flat tab or a bottom sheet) -- a session's
            // on-demand Health Connect time-series detail, reached by
            // tapping an Exercise entry's DETAIL action in the Log tab.
            // Events outside the training plan (races, long days): the event page, from Training › Plan, Next up and +.
            composable("event/{eventId}") { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId")?.toLongOrNull()
                if (eventId != null) {
                    com.bioscan.fieldterminal.ui.screens.events.EventScreen(
                        eventId = eventId,
                        onBack = { navController.popBackStack() },
                        onOpenSession = { id -> navController.navigate("session_detail/$id") },
                    )
                }
            }
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
private fun FieldBottomBar(selectedTab: TopLevelTab, onTabSelected: (TopLevelTab) -> Unit) {
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
                val isSelected = tab == selectedTab
                val accent = tab.domainAccent
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pillShape)
                        .then(
                            if (isSelected) {
                                Modifier
                                    .background(accent.copy(alpha = 0.14f), pillShape)
                                    .border(FT.BorderWidth, accent.copy(alpha = 0.55f), pillShape)
                            } else {
                                Modifier
                            },
                        )
                        .ftClickable(shape = pillShape, role = Role.Tab) { onTabSelected(tab) }
                        .semantics { selected = isSelected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val tint = if (isSelected) accent else FT.TextSecondary
                    // The label below already names the tab; describing the icon too made screen readers say it twice.
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = tab.label,
                        style = FTType.LabelCaps,
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

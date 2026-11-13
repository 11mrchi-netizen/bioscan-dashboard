package com.bioscan.fieldterminal.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bioscan.fieldterminal.R
import com.bioscan.fieldterminal.domain.notifications.NotificationDestination
import com.bioscan.fieldterminal.domain.notifications.notificationDestination
import com.bioscan.fieldterminal.ui.components.FTMotion
import com.bioscan.fieldterminal.ui.components.ToastHost
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.AgingProfileScreen
import com.bioscan.fieldterminal.ui.screens.LogScreen
import com.bioscan.fieldterminal.ui.screens.SessionDetailScreen
import com.bioscan.fieldterminal.ui.screens.SettingsScreen
import com.bioscan.fieldterminal.ui.screens.TrainingBlocksScreen
import com.bioscan.fieldterminal.ui.screens.detail.CardioScreen
import com.bioscan.fieldterminal.ui.screens.detail.DetailScreen
import com.bioscan.fieldterminal.ui.screens.detail.DigestionScreen
import com.bioscan.fieldterminal.ui.screens.detail.HydrationScreen
import com.bioscan.fieldterminal.ui.screens.detail.InjuryScreen
import com.bioscan.fieldterminal.ui.screens.detail.LoadScreen
import com.bioscan.fieldterminal.ui.screens.detail.NutritionScreen
import com.bioscan.fieldterminal.ui.screens.detail.SexualHealthScreen
import com.bioscan.fieldterminal.ui.screens.detail.SleepScreen
import com.bioscan.fieldterminal.ui.screens.detail.WellbeingScreen
import com.bioscan.fieldterminal.ui.screens.events.EventScreen
import com.bioscan.fieldterminal.ui.screens.health.ConcernScreen
import com.bioscan.fieldterminal.ui.screens.me.MeScreen
import com.bioscan.fieldterminal.ui.screens.performance.RecordsScreen
import com.bioscan.fieldterminal.ui.screens.performance.SportScreen
import com.bioscan.fieldterminal.ui.screens.performance.sportRoute
import com.bioscan.fieldterminal.ui.screens.plan.PlanScreen
import com.bioscan.fieldterminal.ui.screens.routine.DayMapScreen
import com.bioscan.fieldterminal.ui.screens.settings.ConnectedServicesScreen
import com.bioscan.fieldterminal.ui.screens.settings.NotificationsSettingsScreen
import com.bioscan.fieldterminal.ui.screens.settings.UserSettingsScreen
import com.bioscan.fieldterminal.ui.screens.shoes.ShoeScreen
import com.bioscan.fieldterminal.ui.screens.status.LabTileScreen
import com.bioscan.fieldterminal.ui.screens.status.NutrientBreakdownScreen
import com.bioscan.fieldterminal.ui.screens.status.PantryScreen
import com.bioscan.fieldterminal.ui.screens.status.TrainingSessionsTab
import com.bioscan.fieldterminal.ui.screens.today.DetailPage
import com.bioscan.fieldterminal.ui.screens.today.TodayHomeScreen
import com.bioscan.fieldterminal.ui.screens.training.TrainingPlannerScreen
import com.bioscan.fieldterminal.ui.screens.training.TrainingSettingsScreen
import com.bioscan.fieldterminal.ui.screens.user.ConditioningSkillScreen
import com.bioscan.fieldterminal.ui.screens.user.RunningScreen
import com.bioscan.fieldterminal.ui.screens.user.StrengthScreen
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.util.Perf
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

@Composable
fun FieldTerminalNavHost(pendingNotificationKind: String? = null, onNotificationHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    // Set when a link should land on a specific tab of a tile; the tile consumes it. App restructure: Lab's (the
    // supplement reminder -> SUPPLEMENTS, the fuel reminder and the User block card -> PLANNING), Training's (the injury
    // flag -> INJURY, the User level buttons -> RUNNING / STRENGTH), Nutrition's (Today's fuel line -> PLAN) and Health's.
    var labsRequestedTab by rememberSaveable { mutableStateOf<LabTab?>(null) }
    // Scoring system rebuild: PLAN's and ME's sub-tab, for a link that should land on one (the fuel reminder -> FOOD).
    var planRequestedTab by rememberSaveable { mutableStateOf<PlanTab?>(null) }
    var meRequestedTab by rememberSaveable { mutableStateOf<MeTab?>(null) }
    // Analysis review phase B: the 22:25 evening check-in reminder opens TODAY with the check-in sheet up.
    var checkInRequested by rememberSaveable { mutableStateOf(false) }
    // A supplement reminder opens TODAY with that block's supplements ready to log ("" = the block due now).
    var supplementsRequested by rememberSaveable { mutableStateOf<String?>(null) }
    // The monthly body check-in reminder opens Lab › Tests with the check-in sheet up.
    var bodyCheckInRequested by rememberSaveable { mutableStateOf(false) }
    // Contract A4: the dock keeps lit the tab whose stack you are in, also on pushed screens (tiles, settings and detail
    // pages), which used to light nothing. Set whenever a top-level destination is shown and when a dock tab is tapped,
    // so a deep link pushed onto the current stack keeps that stack's tab.
    var dockTab by rememberSaveable { mutableStateOf(TopLevelTab.Status) }
    // Scoring system rebuild: tapping + LOG in the dock opens the logging menu straight away. Each tap bumps this, and
    // the Log screen opens its add sheet when it changes.
    var logMenuRequest by rememberSaveable { mutableStateOf(0) }

    // Switch to a dock tab the way tapping it does (one stack per tab, state kept).
    fun goToTab(tab: TopLevelTab) {
        dockTab = tab
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(pendingNotificationKind) {
        val target = pendingNotificationKind ?: return@LaunchedEffect
        // "kind" or "kind:bucket" (MainActivity): a supplement reminder names its block.
        val kind = target.substringBefore(':')
        when (notificationDestination(kind)) {
            NotificationDestination.SUPPLEMENTS -> {
                supplementsRequested = target.substringAfter(':', "")
                goToTab(TopLevelTab.Status)
            }
            NotificationDestination.LOG -> navController.navigate(TopLevelTab.Log.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            NotificationDestination.CHECKIN -> {
                checkInRequested = true
                goToTab(TopLevelTab.Status)
            }
            NotificationDestination.BODY_CHECKIN -> {
                bodyCheckInRequested = true
                goToTab(TopLevelTab.Lab)
            }
            NotificationDestination.HEALTH -> {
                meRequestedTab = MeTab.Health
                goToTab(TopLevelTab.User)
            }
            NotificationDestination.EVENTS -> {
                labsRequestedTab = LabTab.N1
                navController.navigate(TileRoute.Lab.route) { launchSingleTop = true }
            }
            NotificationDestination.FUEL -> {
                planRequestedTab = PlanTab.Food
                goToTab(TopLevelTab.Plan)
            }
            NotificationDestination.ROUTINE -> {
                labsRequestedTab = LabTab.Routine
                navController.navigate(TileRoute.Lab.route) { launchSingleTop = true }
            }
            NotificationDestination.NONE -> Unit
        }
        onNotificationHandled()
    }

    // Perf pass: a marker line per screen change so the http lines that follow can be attributed to it.
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            Perf.mark(destination.route ?: "?")
            TopLevelTab.entries.firstOrNull { it.route == destination.route }?.let { dockTab = it }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    Scaffold(
        containerColor = FT.Base,
        // Undo and retry toasts (ui/components/UndoToast.kt), above the dock.
        snackbarHost = { ToastHost() },
        bottomBar = {
            FieldBottomBar(selectedTab = dockTab) { tab ->
                dockTab = tab
                if (tab == TopLevelTab.Log) logMenuRequest++
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
            // The keyboard pushes the page up instead of covering the field being typed in (the dock's share of the
            // bottom inset is not counted twice).
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding).imePadding(),
            // Between dock tabs: a quick crossfade. A pushed page slides in from the right over a short fade and slides
            // back out on back; with predictive back (manifest) the swipe previews the page behind.
            // Shared motion (ui/components/FTMotion.kt): between dock tabs a crossfade; a pushed page fades in with a short
            // slide from the right and slides back out on back. With predictive back (manifest) the swipe previews the page
            // behind. Reduced motion (animations removed in the system settings) snaps.
            enterTransition = { if (isTabSwitch()) FTMotion.tabEnter() else FTMotion.pushEnter() },
            exitTransition = { if (isTabSwitch()) FTMotion.tabExit() else FTMotion.pushExit() },
            popEnterTransition = { if (isTabSwitch()) FTMotion.tabEnter() else FTMotion.popEnter() },
            popExitTransition = { if (isTabSwitch()) FTMotion.tabExit() else FTMotion.popExit() },
        ) {
            // Scoring system rebuild (docs/scoring-system): the opening page is TODAY -- CHARGE, the five domain rings and
            // today's plan.
            composable(TopLevelTab.Status.route) {
                TodayHomeScreen(
                    onOpenTab = { tab -> goToTab(tab) },
                    // A domain overlay's full-picture tiles open their own page, pushed over TODAY.
                    onOpenDetail = { detail -> navController.navigate(detail.route) { launchSingleTop = true } },
                    onOpenFoodPlan = {
                        planRequestedTab = PlanTab.Food
                        goToTab(TopLevelTab.Plan)
                    },
                    openCheckIn = checkInRequested,
                    onCheckInHandled = { checkInRequested = false },
                    requestedSupplements = supplementsRequested,
                    onSupplementsHandled = { supplementsRequested = null },
                )
            }
            // The full-picture pages (ui/screens/detail), one each; they replaced Health, Training and Nutrition's tabbed pages.
            composable(DetailPage.SLEEP.route) { SleepScreen(onBack = { navController.popBackStack() }) }
            composable(DetailPage.CARDIO.route) { CardioScreen(onBack = { navController.popBackStack() }) }
            composable(DetailPage.SEXUAL.route) { SexualHealthScreen(onBack = { navController.popBackStack() }) }
            composable(DetailPage.WELLBEING.route) { WellbeingScreen(onBack = { navController.popBackStack() }) }
            composable(DetailPage.LOAD.route) {
                LoadScreen(onBack = { navController.popBackStack() }, onOpenSessions = { navController.navigate("sessions") })
            }
            composable(DetailPage.INJURY.route) {
                InjuryScreen(onBack = { navController.popBackStack() }, onOpenConcern = { id -> navController.navigate("concern/$id") })
            }
            composable(DetailPage.NUTRITION.route) {
                NutritionScreen(onBack = { navController.popBackStack() }, onOpenNutrientBreakdown = { navController.navigate("nutrient_breakdown") })
            }
            composable(DetailPage.HYDRATION.route) { HydrationScreen(onBack = { navController.popBackStack() }) }
            composable(DetailPage.DIGESTION.route) { DigestionScreen(onBack = { navController.popBackStack() }) }
            // Every recorded session, from the Load page's ALL SESSIONS.
            composable("sessions") {
                DetailScreen("SESSIONS", onBack = { navController.popBackStack() }, eyebrow = "LOAD", ranged = false) {
                    TrainingSessionsTab { id -> navController.navigate("session_detail/$id") }
                }
            }
            composable("training_planner") {
                TrainingPlannerScreen(onBack = { navController.popBackStack() }, onCreated = { navController.popBackStack() })
            }
            composable("nutrient_breakdown") { NutrientBreakdownScreen(onBack = { navController.popBackStack() }) }
            // Scoring system rebuild: LAB is a dock tab; the old pushed route still resolves to the same page.
            for (route in listOf(TopLevelTab.Lab.route, TileRoute.Lab.route)) {
                composable(route) {
                    LabTileScreen(
                        onBack = if (route == TileRoute.Lab.route) ({ navController.popBackStack() }) else null,
                        onOpenPantry = { navController.navigate("pantry") },
                        onOpenDayMap = { date -> navController.navigate("routine_day/$date") },
                        requestedTab = labsRequestedTab,
                        onRequestedTabConsumed = { labsRequestedTab = null },
                        openBodyCheckIn = bodyCheckInRequested,
                        onBodyCheckInHandled = { bodyCheckInRequested = false },
                    )
                }
            }
            composable(TopLevelTab.Plan.route) {
                PlanScreen(
                    onOpenPlanner = { navController.navigate("training_planner") },
                    onOpenAllBlocks = { navController.navigate("training_blocks") },
                    onOpenEvent = { id -> navController.navigate("event/$id") },
                    onOpenSessionDetail = { id -> navController.navigate("session_detail/$id") },
                    requestedTab = planRequestedTab,
                    onRequestedTabConsumed = { planRequestedTab = null },
                )
            }
            composable("pantry") { PantryScreen(onBack = { navController.popBackStack() }) }
            // The day map (Lab › ROUTINE): one day's path, places and timeline; places are labelled and marked here.
            composable("routine_day/{date}") { backStackEntry ->
                val date = backStackEntry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                DayMapScreen(
                    initialDate = date ?: LocalDate.now(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(TopLevelTab.User.route) {
                MeScreen(
                    onOpenSettings = { navController.navigate(SETUP_ROUTE) },
                    onOpenAging = { navController.navigate("aging_profile") },
                    onOpenTrainingPlan = {
                        planRequestedTab = PlanTab.Training
                        goToTab(TopLevelTab.Plan)
                    },
                    onOpenConditioning = { navController.navigate("skill_conditioning") },
                    onOpenConcern = { id -> navController.navigate("concern/$id") },
                    onOpenSport = { sport -> navController.navigate(sportRoute(sport)) },
                    requestedTab = meRequestedTab,
                    onRequestedTabConsumed = { meRequestedTab = null },
                    onOpenDayMap = { date -> navController.navigate("routine_day/$date") },
                    onOpenRecords = { navController.navigate("records") },
                )
            }
            // Personal records (ME › PERFORMANCE › RECORDS).
            composable("records") {
                RecordsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSession = { id -> navController.navigate("session_detail/$id") },
                )
            }
            composable("aging_profile") { AgingProfileScreen(onBack = { navController.popBackStack() }) }
            composable("skill_conditioning") { ConditioningSkillScreen(onBack = { navController.popBackStack() }) }
            // ME › PERFORMANCE's sports: Running and Strength have their own pages, any other sport the generic one.
            composable("sport_running") {
                RunningScreen(onBack = { navController.popBackStack() }, onOpenSession = { id -> navController.navigate("session_detail/$id") })
            }
            composable("sport_strength") {
                StrengthScreen(onBack = { navController.popBackStack() }, onOpenSession = { id -> navController.navigate("session_detail/$id") })
            }
            composable("sport/{sportKey}") { entry ->
                val key = entry.arguments?.getString("sportKey") ?: "other"
                SportScreen(key, onBack = { navController.popBackStack() }, onOpenSession = { id -> navController.navigate("session_detail/$id") })
            }
            composable("training_blocks") {
                TrainingBlocksScreen(onBack = { navController.popBackStack() })
            }
            composable(TopLevelTab.Log.route) {
                LogScreen(onOpenSessionDetail = { id -> navController.navigate("session_detail/$id") }, openMenuRequest = logMenuRequest)
            }
            composable(SETUP_ROUTE) {
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
                TrainingSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenShoe = { id -> navController.navigate("shoe/$id") },
                )
            }
            // Phase G4: the app's first pushed detail route (every other
            // screen so far is a flat tab or a bottom sheet) -- a session's
            // on-demand Health Connect time-series detail, reached by
            // tapping an Exercise entry's DETAIL action in the Log tab.
            // Events outside the training plan (races, long days): the event page, from Lab › Planning › Race fuel, Next up and +.
            composable("event/{eventId}") { backStackEntry ->
                val eventId = backStackEntry.arguments?.getString("eventId")?.toLongOrNull()
                if (eventId != null) {
                    EventScreen(
                        eventId = eventId,
                        onBack = { navController.popBackStack() },
                        onOpenSession = { id -> navController.navigate("session_detail/$id") },
                    )
                }
            }
            // Health concerns: one concern's page (from Health › CONCERNS, Training › INJURY and the Status figure).
            composable("concern/{concernId}") { backStackEntry ->
                val concernId = backStackEntry.arguments?.getString("concernId")
                if (concernId != null) {
                    ConcernScreen(
                        concernId = concernId,
                        onBack = { navController.popBackStack() },
                        onOpenSession = { id -> navController.navigate("session_detail/$id") },
                    )
                }
            }
            composable("session_detail/{sessionId}") { backStackEntry ->
                val sessionId = backStackEntry.arguments?.getString("sessionId")?.toLongOrNull()
                if (sessionId != null) {
                    SessionDetailScreen(sessionId = sessionId, onBack = { navController.popBackStack() }, onOpenShoe = { id -> navController.navigate("shoe/$id") })
                }
            }
            // Shoes: one pair's page (from a run's SUMMARY and Setup › TRAINING SETUP › SHOES).
            composable("shoe/{shoeId}") { backStackEntry ->
                val shoeId = backStackEntry.arguments?.getString("shoeId")?.toLongOrNull()
                if (shoeId != null) {
                    ShoeScreen(
                        shoeId = shoeId,
                        onBack = { navController.popBackStack() },
                        onOpenSession = { id -> navController.navigate("session_detail/$id") },
                    )
                }
            }
        }
    }
}

// v1 `.ft-dock`: a floating pill (26dp) of dark translucent glass with a hairline, inset from the screen edges. The
// selected tab is light ink on a faint white pill; the others are muted. No per-tab accent: colour belongs to the
// domain wash and the metric ink, and the dock stays neutral. Selected state is the pill AND the brighter label, never
// colour alone. Custom rather than Material3's NavigationBar so the pill and the mono labels follow the contract.
@Composable
private fun FieldBottomBar(selectedTab: TopLevelTab, onTabSelected: (TopLevelTab) -> Unit) {
    val dockShape = RoundedCornerShape(FT.RadiusDock)
    val pillShape = RoundedCornerShape(FT.RadiusModule)
    // Landscape on a phone leaves ~360 dp of height: the dock drops its icons (the labels name the tabs) to keep the page.
    val compact = LocalConfiguration.current.screenHeightDp < 480
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FT.Base)
            .padding(start = 16.dp, end = 16.dp, top = if (compact) 4.dp else 8.dp, bottom = if (compact) 4.dp else 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 40.dp else 64.dp)
                .clip(dockShape)
                .background(Color(0xB814191E))
                .border(FT.BorderWidth, FT.Line, dockShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // A `for` loop, not `.forEach { }` -- `weight()` needs RowScope as
            // its implicit receiver, and a real build confirmed the Kotlin
            // compiler doesn't reliably propagate that receiver into a
            // non-inline lambda passed to `entries.forEach` here.
            for (tab in TopLevelTab.entries) {
                val isSelected = tab == selectedTab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(pillShape)
                        .then(
                            if (isSelected) Modifier.background(Color(0x1AFFFFFF), pillShape) else Modifier,
                        )
                        .ftClickable(shape = pillShape, role = Role.Tab) { onTabSelected(tab) }
                        .semantics { selected = isSelected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val tint = if (isSelected) Color(0xFFF2F5FA) else FT.TextSecondary
                    // The label below already names the tab; describing the icon too made screen readers say it twice.
                    if (!compact) Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(21.dp),
                    )
                    Text(
                        text = tab.label,
                        style = FTType.LabelCaps,
                        color = tint,
                        modifier = Modifier.padding(top = if (compact) 0.dp else 4.dp),
                    )
                }
            }
        }
    }
}

private val TopLevelTab.iconRes
    get() = when (this) {
        TopLevelTab.Status -> R.drawable.ic_tab_status
        TopLevelTab.Plan -> R.drawable.ic_tab_plan
        TopLevelTab.Log -> R.drawable.ic_tab_log
        TopLevelTab.Lab -> R.drawable.ic_tab_lab
        TopLevelTab.User -> R.drawable.ic_tab_user
    }

private val TAB_ROUTES = TopLevelTab.entries.map { it.route }.toSet()

/** Both ends are dock tabs. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES

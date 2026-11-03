package com.bioscan.fieldterminal.ui.nav

// Order matches the pinned /design/PipNavA.dc.html mockup exactly
// (Status, Map, Log, Setup) -- note this differs from the listing order in
// mobile-app-scoping.md's prose ("Status, Log, Map, Settings"), which reads
// as an incidental enumeration order there, not a deliberate tab-position
// decision. The mockup is the actual committed, "high fidelity... final"
// visual design (see design/README.md's Fidelity section), so it wins here.
// Flagged, not silently picked -- correct this if scoping.md's order was
// actually intentional.
//
// DAV-296: the User tab replaces Map in this position (same slot, per the
// milestone's own "the former Map page becomes the User page"). MapScreen.kt
// and its route stay in the codebase, just unreferenced from
// FieldTerminalNavHost's routing -- see
// docs/user-profile-milestone/01-canonical-contracts-audit.md section 10.
enum class TopLevelTab(val route: String, val label: String) {
    // Scoring system rebuild (docs/scoring-system/02-page-mockups.md): TODAY · PLAN · + LOG · LAB · ME. Reading starts on
    // TODAY; doing lives in PLAN, LOG and LAB; slow-moving things and settings in ME. The status and user routes are kept
    // so a saved back stack still resolves; Setup is a pushed page from ME's gear now (SETUP_ROUTE).
    Status("status", "TODAY"),
    Plan("plan", "PLAN"),
    // Tapping it opens the logging menu directly (FieldTerminalNavHost's logMenuRequest).
    Log("log", "+ LOG"),
    Lab("lab", "LAB"),
    User("user", "ME"),
}

/** Setup's hub, pushed from ME's gear (it was a dock tab). */
const val SETUP_ROUTE = "setup"

// PLAN's sub-tabs: what is planned and what was done.
enum class PlanTab(val label: String) {
    Training("TRAINING"),
    Food("FOOD"),
    Race("RACE"),
    Sessions("SESSIONS"),
}

// ME's sub-tabs: the slow-moving picture.
enum class MeTab(val label: String) {
    Healthspan("HEALTHSPAN"),
    Performance("PERFORMANCE"),
    Body("BODY"),
    Health("HEALTH"),
    Bloodwork("BLOODWORK"),
}

// LAB's old pushed route (it was a Status tile before it became a dock tab); kept so a saved back stack still resolves.
// The Training, Nutrition and Health tiles are gone: their tabs became the pages in ui/screens/detail.
enum class TileRoute(val route: String) {
    Lab("tile_labs"),
}

// App restructure: Lab holds what is tried, planned and tested. N=1 has the analysis cards that were on Today; PLANNING
// the training block, the food plan with meal prep and race fuel; TESTS starts the tests whose results are logged in Log.
enum class LabTab(val label: String) {
    N1("N=1"),
    Supplements("SUPPLEMENTS"),
    // Scoring system rebuild: PLANNING moved to the PLAN tab.
    Tests("TESTS"),
    // Routine intelligence R5: the week by place, the commute and the phone (docs/routine-intelligence).
    Routine("ROUTINE"),
}

// Setup's own pushed routes -- Setup is now a 4-button hub rather than one
// flat card dump, same push-and-back pattern as TileRoute above.
enum class SettingsRoute(val route: String) {
    User("settings_user"),
    Notifications("settings_notifications"),
    ConnectedServices("settings_connected"),
    Training("settings_training"),
}

enum class UserTab(val label: String) {
    Profile("PROFILE"),
    NutritionGoals("NUTRITION GOALS"),
    DataCapture("DATA CAPTURE"),
}

enum class ConnectedServicesTab(val label: String) {
    AiNutrition("AI NUTRITION"),
    // UX review P8: it serves Session Detail's route preview now, not a Map tab.
    Map("MAPS & ROUTES"),
    HealthConnect("HEALTH CONNECT"),
    Zepp("ZEPP"),
}

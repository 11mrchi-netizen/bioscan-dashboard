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
    Status("status", "STATUS"),
    User("user", "USER"),
    Log("log", "LOG"),
    Setup("setup", "SETUP"),
}

// First feedback fixes (DAV-70): Status's old single sub-tab rail is replaced by 4 tiles, each its own pushed route with
// its own internal tab strip.
// App restructure (the owner's "Field terminal new structure"): the tiles are Training, Nutrition (was Fuel), Health (was
// Heart) and Lab (was Labs). The route strings are kept, so a restored back stack still resolves.
enum class TileRoute(val route: String) {
    Training("tile_training"),
    Nutrition("tile_fuel"),
    Health("tile_heart"),
    Lab("tile_labs"),
}

// App restructure: Nutrition shows trends over spot metrics. NUTRITION is what was logged (with protein on target, moved
// from Body), HYDRATION its own tab again, PLAN the next three days of the fuel plan (the full plan and meal prep live
// in Lab › Planning). BODY moved to Health.
enum class NutritionTab(val label: String) {
    Nutrition("NUTRITION"),
    Hydration("HYDRATION"),
    Plan("PLAN"),
}

// DAV-97 collapsed seven tabs to four; the restructure adds BODY (from Fuel) and BLOODWORK (from Labs), and renames
// RECOVERY to SLEEP (today's recovery now lives only on Today).
enum class HealthTab(val label: String) {
    Cardio("CARDIO"),
    Sleep("SLEEP"),
    Body("BODY"),
    Wellbeing("WELLBEING"),
    SexualHealth("SEXUAL HEALTH"),
    Bloodwork("BLOODWORK"),
}

// App restructure: Lab holds what is tried, planned and tested. N=1 has the analysis cards that were on Today; PLANNING
// the training block, the food plan with meal prep and race fuel; TESTS starts the tests whose results are logged in Log.
enum class LabTab(val label: String) {
    N1("N=1"),
    Supplements("SUPPLEMENTS"),
    Planning("PLANNING"),
    Tests("TESTS"),
}

// The three parts of Lab › Planning; a deep link (the fuel reminder, the User tab's block card) can ask for one.
enum class PlanningSection(val label: String) {
    Training("TRAINING"),
    Food("FOOD PLAN"),
    RaceFuel("RACE FUEL"),
}

// App restructure: Running and Strength moved in from the User tab's skill pages, and the sessions list has its own tab.
// The block planner (was PLAN) moved to Lab › Planning; Injury moved in from Heart (DAV-216).
enum class TrainingTab(val label: String) {
    Load("LOAD"),
    Running("RUNNING"),
    Strength("STRENGTH"),
    Sessions("SESSIONS"),
    Injury("INJURY"),
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

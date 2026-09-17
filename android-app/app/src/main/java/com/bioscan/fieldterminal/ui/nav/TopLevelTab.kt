package com.bioscan.fieldterminal.ui.nav

// Order matches the pinned /design/PipNavA.dc.html mockup exactly
// (Status, Map, Log, Setup) -- note this differs from the listing order in
// mobile-app-scoping.md's prose ("Status, Log, Map, Settings"), which reads
// as an incidental enumeration order there, not a deliberate tab-position
// decision. The mockup is the actual committed, "high fidelity... final"
// visual design (see design/README.md's Fidelity section), so it wins here.
// Flagged, not silently picked -- correct this if scoping.md's order was
// actually intentional.
enum class TopLevelTab(val route: String, val label: String) {
    Status("status", "STATUS"),
    Map("map", "MAP"),
    Log("log", "LOG"),
    Setup("setup", "SETUP"),
}

// First feedback fixes (DAV-70): Status's old single sub-tab rail (6 chips,
// one flat screen each) is replaced by 4 tiles, each its own pushed route
// with its own internal tab strip. StatusSubTab is gone -- Training has no
// sub-tabs at all (single page), Labs starts as one page (DAV-86 adds real
// theme-bundle sub-navigation later), and Fuel/Heart get their own enums
// below since their tab sets are real, named things a tile page switches
// between, not simple flags.
enum class TileRoute(val route: String) {
    Training("tile_training"),
    Fuel("tile_fuel"),
    Heart("tile_heart"),
    Labs("tile_labs"),
}

enum class FuelTab(val label: String) {
    Nutrition("NUTRITION"),
    Hydration("HYDRATION"),
    Supplements("SUPPLEMENTS"),
    Weight("WEIGHT / TDEE"),
}

// Category 1 (HRV/RHR) lives under "Heart" itself; Arousal has no Analysis
// Layer evaluation yet (only ever logged, never evaluated) so that tab shows
// real recent log history instead of an EvalCard. Sleep and Respiratory are
// split into separate tabs per the user's own listing even though both come
// from `sleep_daily` -- Sleep Duration/SRI vs. respiratory-rate anomaly are
// genuinely different questions (Category 2's own two halves).
enum class HeartTab(val label: String) {
    Heart("HEART"),
    Arousal("AROUSAL"),
    Wellness("WELLNESS"),
    Sleep("SLEEP"),
    Stool("STOOL"),
    Injuries("INJURIES"),
    Respiratory("RESPIRATORY"),
}

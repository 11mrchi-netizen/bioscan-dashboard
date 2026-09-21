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

// DAV-96: Digestion is new (Stool content relocated here from Heart, see
// design/FIELD_TERMINAL_IA_CONTRACT.md section 6); Weight renamed to Body
// per the same doc rather than rebuilt (WeightTdeeTab's real trend content
// carries over unchanged).
enum class FuelTab(val label: String) {
    Nutrition("NUTRITION"),
    Hydration("HYDRATION"),
    Supplements("SUPPLEMENTS"),
    Digestion("DIGESTION"),
    Body("BODY"),
}

// DAV-97: 7 tabs collapsed to 4 per design/FIELD_TERMINAL_IA_CONTRACT.md
// section 7 -- Heart+Respiratory merge into Cardio, Wellness+Arousal merge
// into Wellbeing, Sleep renames to Recovery, Injuries renames to Injury.
// Stool moves out entirely to Fuel/Digestion (FuelTab above), not collapsed
// into any of these four.
enum class HeartTab(val label: String) {
    Cardio("CARDIO"),
    Recovery("RECOVERY"),
    Wellbeing("WELLBEING"),
    Injury("INJURY"),
}

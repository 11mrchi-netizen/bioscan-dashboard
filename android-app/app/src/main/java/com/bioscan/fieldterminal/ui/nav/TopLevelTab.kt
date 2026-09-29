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
// DAV-207/208/209 (24/9 fixes): Hydration folded into Nutrition and Body
// folded into Digestion -- both were thin single-metric subtabs cluttering
// the tab row rather than earning a separate destination. Supplements moved
// out entirely to LabsTab (see below); Fuel is now just the two tabs whose
// content is genuinely different in kind.
enum class FuelTab(val label: String) {
    Nutrition("NUTRITION"),
    Digestion("DIGESTION"),
}

// DAV-97: 7 tabs collapsed to 4 per design/FIELD_TERMINAL_IA_CONTRACT.md
// section 7 -- Heart+Respiratory merge into Cardio, Wellness+Arousal merge
// into Wellbeing, Sleep renames to Recovery, Injuries renames to Injury.
// Stool moves out entirely to Fuel/Digestion (FuelTab above), not collapsed
// into any of these four.
// DAV-216 (24/9 fixes): Injury moved out to TrainingTab -- it's a training-
// context concern (load/rest-cadence context cards render alongside it
// already), not a cardio/recovery/wellbeing one.
enum class HeartTab(val label: String) {
    Cardio("CARDIO"),
    Recovery("RECOVERY"),
    Wellbeing("WELLBEING"),
}

// DAV-209 (24/9 fixes): Labs had no subtab structure; Supplements moves here
// from Fuel since it's a substance-tracking concern closer to bloodwork than
// to nutrition/hydration.
enum class LabsTab(val label: String) {
    Bloodwork("BLOODWORK"),
    Supplements("SUPPLEMENTS"),
}

// DAV-216 (24/9 fixes): Training had no subtab structure; Injury moves here
// from Heart (see HeartTab above).
enum class TrainingTab(val label: String) {
    Load("LOAD"),
    Injury("INJURY"),
}

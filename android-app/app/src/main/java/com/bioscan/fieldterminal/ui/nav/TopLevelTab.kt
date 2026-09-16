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

// Segmented control under Status specifically -- NOT a separate nav level
// (per mobile-app-implementation-roadmap.md Step 3), so this is plain state,
// not a nested NavHost destination.
enum class StatusSubTab(val label: String) {
    Nutrition("NUTRITION/HYDRATION"),
    Training("TRAINING"),
    Supplements("SUPPLEMENTS"),
    Labs("LABS"),
    Injuries("INJURIES & ILLNESS"),
    Analysis("ANALYSIS"), // Phase A2 -- Evaluation Method Spec per-stream states
}

package com.bioscan.fieldterminal.domain

// Drawable zones for the session body heat map. RegionalLoad.kt's 17 anatomical
// BodyRegions are finer than a hand-drawn silhouette can show honestly, so
// they merge into these 14 (e.g. lats + mid back -> UPPER_BACK, adductors +
// abductors -> HIPS, neck folds into traps). The `when` below is exhaustive,
// so a new BodyRegion can't compile without a zone.
enum class BodyZone(val label: String) {
    NeckTraps("Neck & traps"),
    Shoulders("Shoulders"),
    Chest("Chest"),
    Biceps("Biceps"),
    Triceps("Triceps"),
    Forearms("Forearms"),
    Abs("Abs"),
    UpperBack("Lats & mid back"),
    LowerBack("Lower back"),
    Glutes("Glutes"),
    Hips("Hips & inner thigh"),
    Quads("Quads"),
    Hamstrings("Hamstrings"),
    Calves("Calves"),
}

fun BodyRegion.zone(): BodyZone = when (this) {
    BodyRegion.Neck, BodyRegion.Traps -> BodyZone.NeckTraps
    BodyRegion.Shoulders -> BodyZone.Shoulders
    BodyRegion.Chest -> BodyZone.Chest
    BodyRegion.Biceps -> BodyZone.Biceps
    BodyRegion.Triceps -> BodyZone.Triceps
    BodyRegion.Forearms -> BodyZone.Forearms
    BodyRegion.Abdominals -> BodyZone.Abs
    BodyRegion.Lats, BodyRegion.MiddleBack -> BodyZone.UpperBack
    BodyRegion.LowerBack -> BodyZone.LowerBack
    BodyRegion.Glutes -> BodyZone.Glutes
    BodyRegion.Adductors, BodyRegion.Abductors -> BodyZone.Hips
    BodyRegion.Quadriceps -> BodyZone.Quads
    BodyRegion.Hamstrings -> BodyZone.Hamstrings
    BodyRegion.Calves -> BodyZone.Calves
}

// Sums regional load into zones; the total is preserved exactly.
fun zoneLoads(regional: Map<BodyRegion, Double>): Map<BodyZone, Double> =
    regional.entries.groupBy({ it.key.zone() }, { it.value }).mapValues { (_, v) -> v.sum() }

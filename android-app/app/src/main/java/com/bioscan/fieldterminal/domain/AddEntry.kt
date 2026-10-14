package com.bioscan.fieldterminal.domain

// Step 12 (Phase D)'s "+" flow's type picker options, revised 2026-09-15 per
// direct user request: Training removed (runs are no longer manually
// loggable -- there's no in-app way to add one now, by design), Food/Drink/
// Supplements collapsed into one FUEL entry point with its own three-way
// sub-picker (see AddEntrySheet.kt's FuelForm), Wellness added (the
// wellbeing_daily questionnaire previously had no add-entry path at all).
// Each remaining type maps to a real table (see data/AddEntryRepository.kt)
// -- no type appears here without a real place to write it.
enum class AddEntryType(val label: String) {
    Fuel("FUEL"),
    Encounter("ENCOUNTER"),
    Stool("STOOL"),
    Wellness("WELLNESS"),
    Note("NOTE"),
    // UX review P3: named for what it is; OSTRC is the questionnaire behind it.
    Ostrc("INJURY CHECK-IN"),
    SexualActivity("SEXUAL ACTIVITY"),
    Grip("GRIP"),
}

// FUEL's own three-way sub-picker, shown after FUEL is selected.
enum class FuelSubType(val label: String) {
    Food("FOOD"), Drink("DRINK"), Supplements("SUPPS"), Endurance("RUN FUEL"),
}

// Where the add sheet opens when it is not opened on its type picker (UX review P1: the Status hub's quick-log row).
// SayIt opens the one-message form; Fuel opens that Fuel form directly, optionally with one supplement time-of-day bundle
// already ticked (SupplementsToday.suggestedBucket).
sealed interface AddEntryStart {
    data object SayIt : AddEntryStart
    data class Fuel(val subType: FuelSubType, val supplementBundle: String? = null) : AddEntryStart
}

// UX review P3: the add sheet's picker lists what you log, grouped by what it is, with Fuel's four forms at the top level
// (no second picker) and injury / illness reachable here as well as from Training › Injury.
enum class AddEntryGroup(val label: String) {
    Intake("INTAKE"), Body("BODY"), Training("TRAINING"), Intimacy("INTIMACY"), Other("OTHER"),
}

enum class AddEntryChoice(val label: String, val group: AddEntryGroup) {
    Food("Food", AddEntryGroup.Intake),
    Drink("Drink", AddEntryGroup.Intake),
    Supplements("Supps", AddEntryGroup.Intake),
    RunFuel("Run fuel", AddEntryGroup.Intake),
    Wellbeing("Wellbeing", AddEntryGroup.Body),
    Stool("Stool", AddEntryGroup.Body),
    Grip("Grip strength", AddEntryGroup.Body),
    InjuryCheckIn("Injury check-in", AddEntryGroup.Training),
    Injury("Injury", AddEntryGroup.Training),
    Illness("Illness", AddEntryGroup.Training),
    SexualActivity("Sexual activity", AddEntryGroup.Intimacy),
    Encounter("Encounter", AddEntryGroup.Intimacy),
    Note("Note", AddEntryGroup.Other),
}

// Where a choice leads: one of the sheet's own forms (a Fuel choice opens FuelForm on that sub-type), or the injury /
// illness form sheets that Training › Injury also uses.
sealed interface AddEntryTarget {
    data class Form(val type: AddEntryType, val fuel: FuelSubType? = null) : AddEntryTarget
    data object InjuryForm : AddEntryTarget
    data object IllnessForm : AddEntryTarget
}

val AddEntryChoice.target: AddEntryTarget
    get() = when (this) {
        AddEntryChoice.Food -> AddEntryTarget.Form(AddEntryType.Fuel, FuelSubType.Food)
        AddEntryChoice.Drink -> AddEntryTarget.Form(AddEntryType.Fuel, FuelSubType.Drink)
        AddEntryChoice.Supplements -> AddEntryTarget.Form(AddEntryType.Fuel, FuelSubType.Supplements)
        AddEntryChoice.RunFuel -> AddEntryTarget.Form(AddEntryType.Fuel, FuelSubType.Endurance)
        AddEntryChoice.Wellbeing -> AddEntryTarget.Form(AddEntryType.Wellness)
        AddEntryChoice.Stool -> AddEntryTarget.Form(AddEntryType.Stool)
        AddEntryChoice.Grip -> AddEntryTarget.Form(AddEntryType.Grip)
        AddEntryChoice.InjuryCheckIn -> AddEntryTarget.Form(AddEntryType.Ostrc)
        AddEntryChoice.Injury -> AddEntryTarget.InjuryForm
        AddEntryChoice.Illness -> AddEntryTarget.IllnessForm
        AddEntryChoice.SexualActivity -> AddEntryTarget.Form(AddEntryType.SexualActivity)
        AddEntryChoice.Encounter -> AddEntryTarget.Form(AddEntryType.Encounter)
        AddEntryChoice.Note -> AddEntryTarget.Form(AddEntryType.Note)
    }

// The RECENT row: the last few choices, newest first, each once. Stored as enum names; unknown names (a renamed or
// removed choice) are dropped instead of failing.
const val RECENT_ADD_CHOICES = 3

fun recordRecentChoice(recent: List<AddEntryChoice>, picked: AddEntryChoice, keep: Int = RECENT_ADD_CHOICES): List<AddEntryChoice> =
    (listOf(picked) + recent.filter { it != picked }).take(keep)

fun parseRecentChoices(stored: String?): List<AddEntryChoice> =
    stored.orEmpty().split(',').mapNotNull { name -> AddEntryChoice.entries.firstOrNull { it.name == name } }.distinct().take(RECENT_ADD_CHOICES)

fun formatRecentChoices(recent: List<AddEntryChoice>): String = recent.joinToString(",") { it.name }

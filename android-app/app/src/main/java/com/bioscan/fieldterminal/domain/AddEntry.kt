package com.bioscan.fieldterminal.domain

// Step 12 (Phase D): the "+" flow's type picker options. Each one maps to a
// real table Step 11 already reads from (see data/AddEntryRepository.kt) --
// no type appears here without a real place to write it.
enum class AddEntryType(val label: String) {
    Training("TRAINING"),
    Food("FOOD"),
    Drink("DRINK"),
    Encounter("ENCOUNTER"),
    Stool("STOOL"),
    Arousal("AROUSAL"),
    Note("NOTE"),
}

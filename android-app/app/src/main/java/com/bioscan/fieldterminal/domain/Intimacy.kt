package com.bioscan.fieldterminal.domain

// The choices on the encounter form, as confirmed by the user (2026-11-14). A saved value is the label itself
// (text[] columns on encounters), so renaming a label orphans the rows that carry the old one: the form
// still shows an unknown saved value as its own chip (withSaved) so it can be seen and removed.
object IntimacyVocab {
    // How many people, and how they were arranged. Several can apply to one encounter.
    val formats = listOf(
        "1 on 1", "Threesome", "MFM", "FFM", "MMF", "Foursome", "Swap", "Moresome", "Gangbang", "Reverse gangbang", "Blowbang",
        "Bukkake", "Orgy",
    )

    // Acts and kink in one list.
    val activities = listOf(
        "Kissing", "Petting", "Fingering", "Handjob", "Mutual masturbation", "Oral (given)", "Oral (received)", "PIV",
        "Anal (active)", "Anal (passive)", "Rimming (given)", "Rimming (received)", "Fisting",
        "Spanking", "Impact play", "Bondage", "Shibari", "Blindfold", "Choking", "Dom (me)", "Sub (me)", "Roleplay", "Edging",
        "Other kinks",
    )
    val positions = listOf(
        "Missionary", "Legs on shoulders", "Doggy", "Prone", "Cowgirl", "Reverse cowgirl", "Spooning", "Seated / lap",
        "Standing", "Edge of bed", "69",
    )
    val toys = listOf(
        "Vibrator", "Wand", "Dildo", "Strap-on", "Butt plug", "Anal hook", "Cock ring", "Fuck machine", "Restraints", "Ropes",
        "Spreader bar", "Mouth gag", "Whip", "Paddle", "Cane", "Riding crop", "Flogger", "Other",
    )
    val protection = listOf(
        "Condom", "Condom (partly)", "Internal condom", "Dental dam", "None", "PrEP", "Partner on contraception", "Withdrawal",
    )
    val ejaculationLocation = listOf("Vaginal", "Anal", "Mouth", "Face", "Chest", "Stomach", "Back / ass", "Hand", "None")
}

// The chips to show: the vocabulary, then any saved value that is not in it (an older label, or free text typed before
// the lists existed), in the order saved.
fun withSaved(vocabulary: List<String>, saved: Collection<String>): List<String> = vocabulary + saved.filter { it !in vocabulary }

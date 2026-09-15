package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Ported 1:1 from index.html's isSupplementActive()/supplementOutcome() --
// same active-or-ended-within-7-days cutoff (reuses isStatusCurrentlyRelevant,
// already ported for health events in Readiness.kt), same outcome-by-name
// keyword map. See ROADMAP.md P8 Step 8.
//
// Unlike the web dashboard, this screen does NOT exclude Tadalafil or split
// by category into separate body-region panels -- that distribution was a
// web-specific design decision from an earlier chapter, not part of Step 8's
// own scope ("active/ended list... condensed"). Every supplement shows here,
// in one list, same as every other mobile Status sub-tab built so far.

fun isSupplementActive(status: String, endDate: LocalDate?, today: LocalDate): Boolean =
    isStatusCurrentlyRelevant(status, endDate, setOf("active"), setOf("ended"), today)

private val OUTCOME_MAP: List<Pair<Regex, String>> = listOf(
    Regex("boron", RegexOption.IGNORE_CASE) to "Free-T ↑ ~10-15%*",
    Regex("zinc", RegexOption.IGNORE_CASE) to "Supports T synthesis*",
    Regex("nettle", RegexOption.IGNORE_CASE) to "SHBG binding ↓*",
    Regex("dim complex", RegexOption.IGNORE_CASE) to "Estrogen metabolism support*",
    Regex("omega.?3|fish oil", RegexOption.IGNORE_CASE) to "hs-CRP ↓ — primary lever",
    Regex("tart cherry", RegexOption.IGNORE_CASE) to "Exercise-induced inflammation ↓",
    Regex("magnesium", RegexOption.IGNORE_CASE) to "Sleep onset/depth ↑*",
    Regex("glycine", RegexOption.IGNORE_CASE) to "Sleep onset ↑*",
    Regex("apigenin", RegexOption.IGNORE_CASE) to "GABA-A modulation, sleep support*",
    Regex("creatine", RegexOption.IGNORE_CASE) to "Power output ↑ — well established",
    Regex("tyrosine", RegexOption.IGNORE_CASE) to "Stress-task focus ↑*",
    Regex("\\biron\\b", RegexOption.IGNORE_CASE) to "RBC production support",
    Regex("vitamin d", RegexOption.IGNORE_CASE) to "Hormonal + immune support",
    Regex("b.?complex", RegexOption.IGNORE_CASE) to "Methylation cofactors",
    Regex("tadalafil", RegexOption.IGNORE_CASE) to "Erectile fn. decoupled from stress — confirmed in data",
)

fun supplementOutcome(name: String): String =
    OUTCOME_MAP.firstOrNull { (regex, _) -> regex.containsMatchIn(name) }?.second ?: ""

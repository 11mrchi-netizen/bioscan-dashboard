package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Ported 1:1 from index.html's isSupplementActive() -- same
// active-or-ended-within-7-days cutoff (reuses isStatusCurrentlyRelevant,
// already ported for health events in Readiness.kt). See ROADMAP.md P8 Step 8.
// Expected-outcome text no longer lives here: it comes from the shared
// evidence_records registry (domain/SupplementEvidence.kt, DAV-357).
//
// Unlike the web dashboard, this screen does NOT exclude Tadalafil or split
// by category into separate body-region panels -- that distribution was a
// web-specific design decision from an earlier chapter, not part of Step 8's
// own scope ("active/ended list... condensed"). Every supplement shows here,
// in one list, same as every other mobile Status sub-tab built so far.

fun isSupplementActive(status: String, endDate: LocalDate?, today: LocalDate): Boolean =
    isStatusCurrentlyRelevant(status, endDate, setOf("active"), setOf("ended"), today)

fun supplementNextDueDate(everyNDays: Int, lastTakenDate: LocalDate): LocalDate =
    lastTakenDate.plusDays(everyNDays.toLong())

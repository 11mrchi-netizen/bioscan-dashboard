package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.IllnessRow
import com.bioscan.fieldterminal.data.model.InjuryRow
import com.bioscan.fieldterminal.data.model.MedicationEntry
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Merges injuries + illnesses into one list, same "display decision, not a
// database one" as index.html's PANELS.history (genuinely different fields
// underneath, injuries keep body-part/type, illnesses keep symptoms). Open
// events are anything active/monitoring (isHealthEventOpen, the same rule the
// Status figure's pin and flag count use) --
// resolved events show unfiltered here regardless of age, matching both
// PANELS.history's own "complete, unfiltered" framing and the committed
// mockup's closing line ("cleared events drop out of Status after 7 days
// and stay here"). See ROADMAP.md P8 Step 10 for why this does NOT implement
// the mockup's "AUTO-CLEARS <date> UNLESS RE-FLAGGED" copy for open events --
// that's not a real rule anywhere in this project; an active/monitoring
// event stays open until someone manually resolves it, confirmed against
// claude-code-new-domains-handoff.md section 4's actual spec.

/** An injury or illness is open while it is active or monitored; resolving it closes it at once, everywhere. */
fun isHealthEventOpen(status: String): Boolean = status == "active" || status == "monitoring"

fun daysSince(date: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(date, today)

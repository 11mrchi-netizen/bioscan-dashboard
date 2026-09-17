package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.IllnessRow
import com.bioscan.fieldterminal.data.model.InjuryRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class HealthEventKind { Injury, Illness }

data class HealthEvent(
    val id: Long,
    val kind: HealthEventKind,
    val title: String,
    val detail: String?,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate?,
)

// Merges injuries + illnesses into one list, same "display decision, not a
// database one" as index.html's PANELS.history (genuinely different fields
// underneath, injuries keep body-part/type, illnesses keep symptoms). Open
// events are anything active/monitoring (isHealthEventActive, Step 5) --
// resolved events show unfiltered here regardless of age, matching both
// PANELS.history's own "complete, unfiltered" framing and the committed
// mockup's closing line ("cleared events drop out of Status after 7 days
// and stay here"). See ROADMAP.md P8 Step 10 for why this does NOT implement
// the mockup's "AUTO-CLEARS <date> UNLESS RE-FLAGGED" copy for open events --
// that's not a real rule anywhere in this project; an active/monitoring
// event stays open until someone manually resolves it, confirmed against
// claude-code-new-domains-handoff.md section 4's actual spec.

fun mergeHealthEvents(injuries: List<InjuryRow>, illnesses: List<IllnessRow>): List<HealthEvent> {
    val fromInjuries = injuries.map {
        HealthEvent(
            id = it.id,
            kind = HealthEventKind.Injury,
            title = "${it.part} — ${it.type}",
            detail = it.notes,
            status = it.status,
            startDate = LocalDate.parse(it.startDate),
            endDate = it.endDate?.let(LocalDate::parse),
        )
    }
    val fromIllnesses = illnesses.map {
        HealthEvent(
            id = it.id,
            kind = HealthEventKind.Illness,
            title = it.name,
            detail = it.symptoms,
            status = it.status,
            startDate = LocalDate.parse(it.startDate),
            endDate = it.endDate?.let(LocalDate::parse),
        )
    }
    return (fromInjuries + fromIllnesses).sortedByDescending { it.startDate }
}

fun daysSince(date: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(date, today)

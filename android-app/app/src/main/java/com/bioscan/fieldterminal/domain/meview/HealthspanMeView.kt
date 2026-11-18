package com.bioscan.fieldterminal.domain.meview

import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.domain.aging.FactorStatus
import com.bioscan.fieldterminal.domain.aging.HEALTHSPAN_MIN_ASSESSED
import com.bioscan.fieldterminal.domain.aging.HealthspanFactorId
import com.bioscan.fieldterminal.domain.aging.HealthspanSummary
import com.bioscan.fieldterminal.domain.healthspanStateWord
import com.bioscan.fieldterminal.domain.levels.SkillLevel
import java.util.Locale

// Me › HEALTHSPAN as the web draws it: the same content as UserProfileScreen.kt (the Healthspan score and its radar of the
// eight factors with each factor's reading or what it waits for, biological age, the three skill levels, the training
// block).

private fun HealthspanFactorId.short(): String = when (this) {
    HealthspanFactorId.CARDIO_FITNESS -> "CARDIO"
    HealthspanFactorId.BIOLOGICAL_AGE -> "BIO AGE"
    HealthspanFactorId.STRENGTH -> "STRENGTH"
    HealthspanFactorId.BODY_COMPOSITION -> "BODY"
    HealthspanFactorId.SLEEP -> "SLEEP"
    HealthspanFactorId.ACTIVITY -> "ACTIVITY"
    HealthspanFactorId.METABOLIC -> "LIPIDS"
    HealthspanFactorId.GLYCAEMIC -> "GLUCOSE"
}

private fun tone(s: FactorStatus): Tone? = when (s) { FactorStatus.FAVORABLE -> Tone.OK; FactorStatus.OPPORTUNITY -> Tone.WARN; else -> null }

fun healthspanMeView(
    summary: HealthspanSummary?,
    aging: AgingProfileOverview?,
    levels: List<Pair<String, SkillLevel?>>,
    block: Pair<String, String>?,
): MeViewDoc {
    val sections = mutableListOf<MeSection>()
    sections += MeSection("HEALTHSPAN", if (summary == null) listOf(MeNote("The inputs could not be read.")) else listOfNotNull(
        MeTile("Healthspan", summary.score?.toString() ?: "PRELIMINARY", summary.score?.let { "/ 100" }, sub = "${summary.favorable} of ${summary.assessed} assessed factors favorable"),
        if (summary.preliminary) MeNote("No score yet: at least $HEALTHSPAN_MIN_ASSESSED of the 8 factors must be assessed, and ${summary.assessed} are.") else null,
        MeRadar("HEALTHSPAN", summary.factors.map { f -> MeAxis(f.id.short(), f.id.label, f.score, healthspanStateWord(f.status).word ?: "") }),
    ) + summary.factors.map { f ->
        MeRow(
            f.id.label, value = f.score?.let { "$it" }, tone = tone(f.status), state = healthspanStateWord(f.status).word,
            sub = listOfNotNull(f.reading, f.missingInputs.takeIf { it.isNotEmpty() }?.let { "Needs: " + it.joinToString(", ") }, f.action).joinToString(" · ").ifEmpty { null },
        )
    }, info = "A general score from 1 to 100: the mean of the factors that could be assessed from your own data. Favorable from 50, an opportunity below 25. Product-defined, not a medical assessment.")

    val ages = listOfNotNull(aging?.phenoAge, aging?.cardioAge)
    sections += MeSection("AGING", when {
        aging == null -> listOf(MeNote("Loading the aging profile failed."))
        aging.chronologicalAgeYears == null -> listOf(MeNote("Set your date of birth in Setup › Profile & goals to see this."))
        ages.isEmpty() -> listOf(MeNote("No biological-age model has a result yet."))
        else -> ages.map { r ->
            if (r.biologicalAge != null) MeTile(r.model.label, "%.0f".format(Locale.ENGLISH, r.biologicalAge), "yrs",
                sub = r.ageAcceleration?.let { "%+.1f yrs vs. real age".format(Locale.ENGLISH, it) },
                tone = r.ageAcceleration?.let { if (it <= 0) Tone.OK else Tone.WARN })
            else MeTile(r.model.label, "—", sub = r.unavailableReason ?: "Unavailable")
        } + MeNote("Chronological age ${aging.chronologicalAgeYears} years.")
    })

    sections += MeSection("SKILL LEVELS", levels.map { (name, l) ->
        MeRow(name, value = l?.takeIf { it.available }?.label, sub = listOfNotNull(l?.headline, l?.reason, if (l?.provisional == true) "provisional" else null).joinToString(" · ").ifEmpty { null },
            state = if (l == null) "LOADING" else null)
    })

    sections += MeSection("TRAINING BLOCK", listOf(block?.let { (title, progress) -> MeRow(title, sub = progress) } ?: MeNote("No active block.")))
    return MeViewDoc("HEALTHSPAN", sections)
}

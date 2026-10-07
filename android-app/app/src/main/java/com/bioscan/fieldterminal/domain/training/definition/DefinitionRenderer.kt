package com.bioscan.fieldterminal.domain.training.definition

// DAV-344. A readable rendering of a parsed definition, produced from the same model
// the engine reads, so what the user reviews is what the engine will use.

private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
private fun NumRange.text(unit: String = ""): String = (if (isFixed) fmt(min) else "${fmt(min)}-${fmt(max)}") + unit
private fun NumRange?.orDash(unit: String = ""): String = this?.text(unit) ?: "-"

private fun LoadDto.text(): String = when (kind) {
    "pct_1rm" -> "${fmt(value!!)}% 1RM"
    "pct_tm" -> "${fmt(value!!)}% TM"
    "pct_max_reps" -> "${fmt(value!!)}% of max reps"
    "work_up_rm" -> "work up to a $minRm-${maxRm}RM"
    "fixed" -> "${fmt(kg!!)} kg"
    "rpe" -> "RPE ${fmt(rpe!!)}"
    "bodyweight" -> "bodyweight"
    else -> ""
}

private fun ItemDto.text(): String {
    val scheme = listOfNotNull(
        sets?.let { "${it.text()} x" },
        reps?.text(),
        load.text().takeIf { it.isNotEmpty() },
    ).joinToString(" ")
    val flags = listOfNotNull(
        role.takeIf { it != "main" },
        technique?.let { "technique $it" },
        "optional".takeIf { optional },
        optionOf?.let { "option $it" },
        pair?.let { "pair $it" },
        note,
    ).joinToString(", ")
    return "$slot: $scheme" + if (flags.isNotEmpty()) " ($flags)" else ""
}

private fun StringBuilder.weeks(weeks: List<WeekDef>) {
    for (w in weeks) {
        append("  Week ${w.week}${if (w.kind != "normal") " [${w.kind}]" else ""}${if (!w.countsTowardBlock) " (invisible)" else ""}\n")
        for (s in w.sessions) append("    ${s.session}: ${s.items.joinToString(" | ") { it.text() }}${s.note?.let { " - $it" } ?: ""}\n")
    }
}

fun renderDefinition(def: Definition): String = buildString {
    append("${def.title}  [${def.key} v${def.version}]${def.sourceRef?.let { " - $it" } ?: ""}\n")
    when (def) {
        is StrengthModuleDef -> {
            append("Strength module (${def.family}, ${def.domain}); ${def.sessionsPerWeek} sessions/week on days ${def.dayPositions.joinToString()}; block lengths ${def.blockLengths.joinToString()} weeks\n")
            append("Slots: ${def.slots.joinToString("; ") { "${it.id}=${it.standard}${if (it.alternates.isNotEmpty()) " (or ${it.alternates.joinToString(", ")})" else ""}${if (it.weightedCalisthenics) " [weighted calisthenics]" else ""}" }}\n")
            append("Sessions: ${def.sessions.joinToString("; ") { "${it.id} ${it.label} = ${it.slots.joinToString("+")}" }}\n")
            def.variants.forEach { v -> append("Variant ${v.key}: ${v.title}\n"); weeks(v.weeks) }
            if (def.supplemental.isNotEmpty()) append("Supplemental: ${def.supplemental.joinToString("; ") { "weeks ${it.weeks.joinToString("&")}: ${it.sets.text()} x ${it.reps.text()} ${it.load.text()}" }}\n")
            def.peak?.let { append("Peak: ${it.techniques.joinToString("/")}${it.everyWeeks?.let { r -> ", every ${r.text()} weeks" } ?: ""}${if (it.scheduleDays.isNotEmpty()) ", spread on days ${it.scheduleDays.joinToString()}" else ""}\n") }
            def.progression?.let { append("Progression: +${it.upper.text()} ${it.unit} upper / +${it.lower.text()} ${it.unit} lower every ${it.afterWeeks.text()} weeks${if (it.skipIfIncomplete) "; unchanged if reps not completed" else ""}\n") }
            if (def.options.isNotEmpty()) append("Options: ${def.options.joinToString("; ") { "${it.id} (${it.title})" }}\n")
            if (def.altClusters.isNotEmpty()) append("Alternative clusters: ${def.altClusters.joinToString("; ") { it.label }}\n")
            def.thirdSession?.let { append("Third session: $it\n") }
            if (def.compatibleConditioning.isNotEmpty()) append("Pairs with: ${def.compatibleConditioning.joinToString()}\n")
        }
        is SeModuleDef -> {
            append("Strength-endurance module; ${def.sessionsPerWeek} sessions/week on days ${def.dayPositions.joinToString()}; cluster of ${def.clusterSize.text()} exercises\n")
            def.weeks.forEach { append("  Week ${it.week}: ${it.circuits.text()} circuits x ${it.reps.text()} reps${if (it.kind != "normal") " [${it.kind}]" else ""}\n") }
            def.loadPct1rm?.let { append("Load: ${it.text()}% 1RM${def.vestPctBodyweight?.let { v -> " or vest ${v.text()}% bodyweight" } ?: ""}\n") }
            def.finisher?.let { append("Finisher: $it\n") }
            if (def.options.isNotEmpty()) append("Options: ${def.options.joinToString("; ") { "${it.id} (${it.title})" }}\n")
            if (def.sampleClusters.isNotEmpty()) append("Sample clusters: ${def.sampleClusters.joinToString("; ") { it.label }}\n")
        }
        is ConditioningSessionDef -> {
            append("Conditioning session (${def.category}; ${def.domains.joinToString()}); by ${def.prescription.by.joinToString()}${def.prescription.minutes?.let { ", ${it.text()} min" } ?: ""}${def.prescription.rounds?.let { ", ${it.text()} rounds" } ?: ""}${if (def.doublesAsWc) "; doubles as work capacity" else ""}\n")
            append("Structure: ${def.structure.type}${if (def.structure.steps.isNotEmpty()) " - ${def.structure.steps.joinToString("; ")}" else ""}\n")
            def.intensity?.let { append("Intensity: ${listOfNotNull(it.rpe?.let { r -> "RPE ${r.text()}" }, it.hrPctMax?.let { r -> "${r.text()}% max HR" }, it.cue).joinToString(", ")}\n") }
        }
        is ConditioningProtocolDef -> {
            append("Conditioning protocol (${def.protocolType}${def.oaType?.let { ", $it" } ?: ""})${def.weeks?.let { "; ${it.text()} weeks" } ?: ""}\n")
            def.budget?.let { b ->
                append("Budget: low-intensity ${b.lowIntensityMinutesPerWeek.orDash(" min/week")}; min session ${b.sessionMinMinutes ?: "-"} min; high-intensity ${b.highIntensityPerWeek?.text("/week") ?: b.highIntensityEveryNWeeks?.let { "every $it weeks" } ?: "-"}${b.sessionsPerWeek?.let { "; sessions ${it.text()}/week" } ?: ""}\n")
            }
            def.weekAdjustments.forEach { append("Adjustment (${it.`when`}): low-intensity ${it.lowIntensityMinutesPerWeek.text(" min/week")}\n") }
            if (def.defaultLayout.isNotEmpty()) append("Default layout: ${def.defaultLayout.joinToString("; ") { "day ${it.day} ${it.kind}${it.minutes?.let { m -> " ${m.text()}" } ?: ""}" }}\n")
            if (def.suggested.lic.isNotEmpty()) append("Suggested low-intensity: ${def.suggested.lic.joinToString()}\n")
            if (def.suggested.hic.isNotEmpty()) append("Suggested high-intensity: ${def.suggested.hic.joinToString()}\n")
            if (def.suggested.wc.isNotEmpty()) append("Suggested work capacity: ${def.suggested.wc.joinToString()}\n")
            def.variants.forEach { append("Variant ${it.key}: ${it.title}${it.strength?.let { s -> " (strength $s)" } ?: ""}${it.budget?.sessionsPerWeek?.let { s -> ", ${s.text()} sessions/week" } ?: ""}\n") }
            if (def.rotation.isNotEmpty()) append("Rotation: ${def.rotation.joinToString("; ")}\n")
            if (def.pairsWellWith.isNotEmpty()) append("Pairs with: ${def.pairsWellWith.joinToString()}\n")
        }
        is CompositionDef -> {
            append("Composition: strength from [${def.strength.chooseFrom.joinToString()}] + conditioning from [${def.conditioning.chooseFrom.joinToString()}]; ${def.weeks.text()} weeks\n")
            def.integration.forEach { append("Integration (${it.rule}): ${it.note}\n") }
        }
        is TemplateDef -> {
            append("Fixed template, ${def.weeks} weeks; domains ${def.domains.joinToString()}\n")
            def.grid.forEach { w ->
                append("  Week ${w.week}${if (w.kind != "normal") " [${w.kind}]" else ""}${if (!w.countsTowardBlock) " (invisible)" else ""}: ${w.days.joinToString("; ") { d -> "D${d.day} " + d.cells.joinToString("+") { c -> c.label ?: (c.ref + (c.params?.let { p -> " $p" } ?: "")) } }}\n")
            }
            def.budget?.let { append("Budget: low-intensity ${it.lowIntensityMinutesPerWeek.orDash(" min/week")}\n") }
        }
        is SystemDef -> {
            append("System (${def.subtype}${def.oaType?.let { ", $it" } ?: ""})${if (def.repeat) "; repeats" else ""}\n")
            def.blocks.forEach { b -> append("  ${b.label}: ${b.weeks.text()} weeks${b.composition?.let { " [$it]" } ?: ""}${b.external?.let { " [external: $it]" } ?: ""}${b.strengthDomain?.let { ", strength $it" } ?: ""}${b.conditioningLabel?.let { ", conditioning $it" } ?: ""}\n") }
            def.baseline?.let { append("Baseline: $it; detours: ${def.detours.joinToString()}\n") }
        }
    }
    def.notes.forEach { append("Note: $it\n") }
}

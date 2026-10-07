package com.bioscan.fieldterminal.domain.training.definition

// DAV-344. Structural checks on a definition (beyond what the parser enforces) and
// cross-reference checks across a set. Errors block use; warnings are for the review.

data class ValidationReport(val errors: List<String>, val warnings: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

private val KEY = Regex("^[a-z0-9_.]+$")
private val WEEK_KINDS = setOf("normal", "peak", "deload", "taper", "test", "easy")
private val STRENGTH_DOMAINS = setOf("max_strength", "hypertrophy", "power", "strength_endurance")
private val CATEGORIES = setOf("lic", "hic", "wc", "power_hic", "core")
private val PROTOCOL_TYPES = setOf("polarized", "work_capacity", "ldp")
private val PEAK_TECHNIQUES = setOf("peak", "amrap", "amsap", "none")

fun validateDefinition(def: Definition): List<String> {
    val e = mutableListOf<String>()
    if (!KEY.matches(def.key)) e += "key '${def.key}' must be lowercase letters, digits, '_' or '.'"
    if (def.version < 1) e += "version must be >= 1"
    when (def) {
        is StrengthModuleDef -> validateStrength(def, e)
        is SeModuleDef -> validateSe(def, e)
        is ConditioningSessionDef -> {
            if (def.category !in CATEGORIES) e += "category '${def.category}' unknown"
            if (def.prescription.by.isEmpty()) e += "prescription.by is empty"
            if (def.domains.isEmpty()) e += "domains is empty"
        }
        is ConditioningProtocolDef -> {
            if (def.protocolType !in PROTOCOL_TYPES) e += "protocol_type '${def.protocolType}' unknown"
            if (def.protocolType == "polarized" && def.budget?.lowIntensityMinutesPerWeek == null) e += "a polarized protocol needs a low-intensity weekly budget"
            if (def.protocolType == "polarized" && def.budget?.highIntensityPerWeek == null && def.budget?.highIntensityEveryNWeeks == null) e += "a polarized protocol needs a high-intensity count or cadence"
            def.defaultLayout.forEach { if (it.day !in 1..7) e += "layout day ${it.day} outside 1..7" }
        }
        is CompositionDef -> {
            if (def.strength.chooseFrom.isEmpty() && !def.strength.optional) e += "strength.choose_from is empty"
            if (def.conditioning.chooseFrom.isEmpty() && !def.conditioning.optional) e += "conditioning.choose_from is empty"
        }
        is TemplateDef -> validateTemplate(def, e)
        is SystemDef -> {
            if (def.subtype == "cycle" && def.blocks.isEmpty()) e += "a cycle needs blocks"
            if (def.subtype == "perpetual" && def.baseline == null) e += "a perpetual system needs a baseline"
            if (def.subtype !in setOf("cycle", "perpetual")) e += "subtype '${def.subtype}' unknown"
            if (def.blocks.map { it.key }.let { it.size != it.distinct().size }) e += "block keys are not unique"
        }
    }
    return e
}

private fun validateWeeks(weeks: List<WeekDef>, sessionIds: Set<String>, slotIds: Set<String>, where: String, e: MutableList<String>, hasPeak: Boolean) {
    if (weeks.isEmpty()) { e += "$where: no weeks"; return }
    if (weeks.map { it.week } != (1..weeks.size).toList()) e += "$where: weeks must be numbered 1..${weeks.size} in order"
    for (w in weeks) {
        if (w.kind !in WEEK_KINDS) e += "$where week ${w.week}: kind '${w.kind}' unknown"
        if (w.kind == "peak" && !hasPeak) e += "$where week ${w.week}: peak week but the module has no peak definition"
        for (s in w.sessions) {
            if (s.session !in sessionIds) e += "$where week ${w.week}: unknown session '${s.session}'"
            for (it in s.items) {
                if (it.slot !in slotIds) e += "$where week ${w.week} ${s.session}: unknown slot '${it.slot}'"
                runCatching { it.load.toSpec() }.onFailure { f -> e += "$where week ${w.week} ${s.session} ${it.slot}: ${f.message}" }
                if (it.technique != null && it.technique !in PEAK_TECHNIQUES) e += "$where week ${w.week}: technique '${it.technique}' unknown"
                if (it.load.kind in setOf("pct_1rm", "pct_tm", "pct_max_reps") && it.sets == null) e += "$where week ${w.week} ${s.session} ${it.slot}: percentage load without sets"
            }
        }
    }
}

private fun validateStrength(d: StrengthModuleDef, e: MutableList<String>) {
    if (d.domain !in STRENGTH_DOMAINS) e += "domain '${d.domain}' unknown"
    val slotIds = d.slots.map { it.id }
    if (slotIds.size != slotIds.distinct().size) e += "slot ids are not unique"
    val sessionIds = d.sessions.map { it.id }
    if (sessionIds.size != sessionIds.distinct().size) e += "session ids are not unique"
    d.sessions.forEach { s -> s.slots.filter { it !in slotIds }.forEach { e += "session ${s.id}: unknown slot '$it'" } }
    if (d.sessions.size != d.sessionsPerWeek) e += "sessions_per_week ${d.sessionsPerWeek} != ${d.sessions.size} sessions defined"
    if (d.dayPositions.size != d.sessionsPerWeek || d.dayPositions != d.dayPositions.sorted() || d.dayPositions.any { it !in 1..7 }) e += "day_positions must be ${d.sessionsPerWeek} ascending days in 1..7"
    if (d.variants.isEmpty()) e += "no variants"
    if (d.variants.map { it.key }.let { it.size != it.distinct().size }) e += "variant keys are not unique"
    d.variants.forEach { v -> validateWeeks(v.weeks, sessionIds.toSet(), slotIds.toSet(), "variant ${v.key}", e, d.peak != null) }
    d.peak?.techniques?.filter { it !in PEAK_TECHNIQUES }?.forEach { e += "peak technique '$it' unknown" }
    d.options.map { it.id }.let { if (it.size != it.distinct().size) e += "option ids are not unique" }
    d.supplemental.forEach { r -> runCatching { r.load.toSpec() }.onFailure { f -> e += "supplemental: ${f.message}" } }
    val longest = d.variants.maxOfOrNull { it.weeks.size } ?: 0
    d.blockLengths.filter { it > longest }.forEach { e += "block length $it exceeds the longest variant ($longest weeks)" }
}

private fun validateSe(d: SeModuleDef, e: MutableList<String>) {
    if (d.weeks.map { it.week } != (1..d.weeks.size).toList()) e += "weeks must be numbered 1..${d.weeks.size} in order"
    if (d.dayPositions.size != d.sessionsPerWeek || d.dayPositions.any { it !in 1..7 }) e += "day_positions must list ${d.sessionsPerWeek} days in 1..7"
    d.weeks.forEach { if (it.kind !in WEEK_KINDS) e += "week ${it.week}: kind '${it.kind}' unknown" }
}

private fun validateTemplate(d: TemplateDef, e: MutableList<String>) {
    if (d.grid.map { it.week } != (1..d.grid.size).toList()) e += "grid weeks must be numbered 1..${d.grid.size} in order"
    if (d.grid.size != d.weeks) e += "weeks ${d.weeks} != ${d.grid.size} grid weeks"
    for (w in d.grid) {
        if (w.kind !in WEEK_KINDS) e += "week ${w.week}: kind '${w.kind}' unknown"
        for (day in w.days) {
            if (day.day !in 1..7) e += "week ${w.week}: day ${day.day} outside 1..7"
            for (c in day.cells) if (!Regex("^(strength|se|cond|test|rest|inline):?[a-z0-9_.$]*$").matches(c.ref)) e += "week ${w.week} day ${day.day}: bad ref '${c.ref}'"
        }
    }
}

// Cross-references across a set of definitions of one methodology.
fun validateSet(defs: List<Definition>): ValidationReport {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    defs.groupBy { it::class to it.key to it.version }.filter { it.value.size > 1 }.keys.forEach { errors += "duplicate definition ${it.first.second} v${it.second}" }
    defs.forEach { d -> validateDefinition(d).forEach { errors += "${d.key}: $it" } }

    val strength = defs.filterIsInstance<StrengthModuleDef>().map { it.key }.toSet() + defs.filterIsInstance<SeModuleDef>().map { it.key }
    val protocols = defs.filterIsInstance<ConditioningProtocolDef>().map { it.key }.toSet()
    // A system block or baseline may point at a composition or at a fixed template.
    val compositions = (defs.filterIsInstance<CompositionDef>().map { it.key } + defs.filterIsInstance<TemplateDef>().map { it.key }).toSet()
    val sessions = defs.filterIsInstance<ConditioningSessionDef>().flatMap { listOf(it.key) + it.aliases }.toSet()

    defs.filterIsInstance<CompositionDef>().forEach { c ->
        c.strength.chooseFrom.filter { it !in strength }.forEach { errors += "${c.key}: unknown strength module '$it'" }
        c.conditioning.chooseFrom.filter { it !in protocols }.forEach { errors += "${c.key}: unknown conditioning protocol '$it'" }
    }
    defs.filterIsInstance<ConditioningProtocolDef>().forEach { p ->
        p.pairsWellWith.filter { it !in strength }.forEach { errors += "${p.key}: pairs_well_with unknown module '$it'" }
        p.variants.mapNotNull { it.strength }.filter { it !in strength }.forEach { errors += "${p.key}: variant strength unknown '$it'" }
        (p.suggested.lic + p.suggested.hic + p.suggested.wc).filter { it !in sessions }.forEach { warnings += "${p.key}: suggested session '$it' is not defined yet" }
    }
    defs.filterIsInstance<StrengthModuleDef>().forEach { s ->
        s.compatibleConditioning.filter { it !in protocols }.forEach { warnings += "${s.key}: compatible conditioning '$it' is not defined" }
    }
    defs.filterIsInstance<SystemDef>().forEach { sys ->
        sys.blocks.forEach { b ->
            if (b.composition != null && b.composition !in compositions) errors += "${sys.key}: block ${b.key} composition '${b.composition}' unknown"
            if (b.composition == null && b.external == null) errors += "${sys.key}: block ${b.key} needs a composition or an external program name"
            b.suggested?.let { (it.lic + it.hic + it.wc).filter { k -> k !in sessions }.forEach { k -> warnings += "${sys.key}/${b.key}: suggested session '$k' is not defined yet" } }
        }
        sys.baseline?.let { if (it !in compositions) errors += "${sys.key}: baseline '$it' unknown" }
        sys.detours.filter { it !in compositions }.forEach { warnings += "${sys.key}: detour '$it' is not a defined composition" }
    }
    return ValidationReport(errors, warnings.distinct())
}

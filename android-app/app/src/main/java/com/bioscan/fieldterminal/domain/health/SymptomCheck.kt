package com.bioscan.fieldterminal.domain.health

// The symptom check (docs/health-concerns/README.md, "Illness, infections and the symptom check"): a short, fixed
// questionnaire for the small things (a headache, a stuffed nose, an ache) that is not an injury or an illness to follow.
// Deterministic: where it is, what it is, how strong, since when and which way it is going, then the area's questions.
// Some answers are red flags, checked first and always shown first: URGENT (call 119 now) or CLINICIAN (worth a
// clinician's view). The rest support patterns that fit; each pattern says which answers point to it. Answers can be
// prefilled from the person's own data (last night's sleep, the last days' training). It never names a diagnosis: it
// says what fits and what is worth doing. The assistant's WHY? (opt-in, off by default) can read the result for a second
// opinion; nothing here depends on it.

enum class CheckArea(val label: String, val symptoms: List<String>) {
    HEAD("Head", listOf("sym.headache", "sym.dizziness")),
    NOSE_THROAT("Nose and throat", listOf("sym.congestion", "sym.sneezing", "sym.sore_throat", "sym.sinus_pressure", "sym.loss_smell")),
    CHEST("Chest and breathing", listOf("sym.cough", "sym.chest_tightness", "sym.breathless")),
    STOMACH("Stomach and gut", listOf("sym.nausea", "sym.heartburn", "sym.bloating", "sym.stomach_cramps", "sym.diarrhoea", "sym.constipation", "sym.vomiting")),
    MUSCLES("Muscles and joints", listOf("sym.ache", "sym.stiffness", "sym.cramp", "sym.muscle_aches")),
    SKIN("Skin", listOf("sym.rash")),
    EYES_EARS("Eyes and ears", listOf("sym.eye_irritation", "sym.earache")),
    URINARY("Peeing", listOf("sym.urinary")),
    WHOLE_BODY("Whole body", listOf("sym.fatigue", "sym.fever", "sym.chills", "sym.low_mood", "sym.poor_sleep")),
}

/** How strong, as the 0-10 severity a signal stores. */
enum class CheckIntensity(val label: String, val severity: Int) { MILD("MILD", 2), MODERATE("MODERATE", 5), STRONG("STRONG", 8) }

enum class CheckSince(val label: String, val days: Int) { NOW("JUST NOW", 0), TODAY("TODAY", 0), DAYS("2-3 DAYS", 2), WEEK("A WEEK OR MORE", 7) }

enum class CheckCourse(val label: String) { BETTER("BETTER"), SAME("SAME"), WORSE("WORSE") }

enum class FlagLevel { URGENT, CLINICIAN }

data class CheckFlag(val level: FlagLevel, val text: String)

/** One yes/no question of an area ([symptoms]: only for these; empty = the whole area). A yes may be a red flag. */
data class CheckQuestion(
    val id: String,
    val area: CheckArea,
    val text: String,
    val symptoms: Set<String> = emptySet(),
    val flag: CheckFlag? = null,
    val supports: Set<String> = emptySet(),
)

/** A pattern that can fit: what it is called and the one thing worth doing about it. */
data class CheckPattern(val id: String, val label: String, val advice: String, val track: TrackAs? = null)

enum class TrackAs { ILLNESS, INJURY }

const val EMERGENCY_NUMBER = "119"
const val CRISIS_NUMBER = "1925"

private fun urgent(text: String) = CheckFlag(FlagLevel.URGENT, text)
private fun clinician(text: String) = CheckFlag(FlagLevel.CLINICIAN, text)

val CHECK_PATTERNS: Map<String, CheckPattern> = listOf(
    CheckPattern("tension", "Tension-type headache", "Often eased by a break from the screen, water, a walk or a usual painkiller."),
    CheckPattern("migraine_like", "Migraine-like headache", "A dark quiet room and rest often help; if these keep coming, a clinician can help with a plan."),
    CheckPattern("sinus", "Sinus congestion", "Steam, saline rinses and fluids often help; worth a check if it lasts over 10 days."),
    CheckPattern("shortfall", "Caffeine or fluid shortfall", "Water now, and your usual coffee if you skipped it."),
    CheckPattern("exertional", "Headache brought on by exertion", "Ease off; a first one is worth a clinician's check."),
    CheckPattern("orthostatic", "Light-headed on standing", "Fluids and salt, and standing up slowly; worth a check if it keeps happening."),
    CheckPattern("cold", "Common cold", "Rest and fluids; easy training is usually fine while it stays above the neck.", TrackAs.ILLNESS),
    CheckPattern("flu_like", "Flu-like illness", "Rest, fluids, no training while you have a fever; a test (COVID, flu) is worth doing.", TrackAs.ILLNESS),
    CheckPattern("allergy", "Allergy", "Avoid the trigger if you know it; an antihistamine often helps."),
    CheckPattern("post_viral_cough", "Cough after a cold", "Often lasts 2-3 weeks; worth a check after 3."),
    CheckPattern("eib", "Breathing tightness with exercise", "Worth a clinician's check: it can be tested and treated."),
    CheckPattern("chest_infection", "Chest infection", "Rest and no hard training; worth a clinician's view with fever and a chesty cough.", TrackAs.ILLNESS),
    CheckPattern("food_poisoning", "Food poisoning", "Small sips of fluid often; it usually settles in 1-2 days.", TrackAs.ILLNESS),
    CheckPattern("stomach_bug", "Stomach bug", "Fluids with salt, rest, wash hands often; it usually settles in 2-3 days.", TrackAs.ILLNESS),
    CheckPattern("runners_gut", "Gut upset from running", "Try less fibre and fat the day before long runs, and train the gut with gels slowly."),
    CheckPattern("reflux", "Reflux / heartburn", "Smaller and earlier dinners, less alcohol and spice; an antacid often helps."),
    CheckPattern("diet_change", "Gut reacting to a change in what you eat", "Go back to what suits you and change one thing at a time."),
    CheckPattern("doms", "Muscle soreness after training", "Usually peaks at 24-72 h and settles; easy movement helps."),
    CheckPattern("acute_injury", "Possible injury", "Protect it and track it as an injury, so its days and recovery are followed.", TrackAs.INJURY),
    CheckPattern("viral_aches", "Aches with an infection", "Rest until the fever and aches go.", TrackAs.ILLNESS),
    CheckPattern("strain", "Strain from posture or load", "Gentle movement and a change of position usually help."),
    CheckPattern("skin_reaction", "Skin reaction", "Stop the new product or food; an antihistamine or a mild cream often helps."),
    CheckPattern("chafing", "Chafing", "Keep it clean and dry, and use an anti-chafe balm next time."),
    CheckPattern("sunburn", "Sunburn", "Cool it, moisturise and keep it out of the sun."),
    CheckPattern("conjunctivitis", "Eye infection or irritation", "Don't share towels; worth a check if it is painful or your vision changes."),
    CheckPattern("swimmers_ear", "Swimmer's ear", "Keep it dry; worth a check if it gets painful."),
    CheckPattern("sleep_debt", "Short sleep", "An earlier night tonight; it usually lifts with sleep."),
    CheckPattern("training_fatigue", "Training fatigue", "An easier day or two."),
    CheckPattern("viral", "Viral illness starting", "Rest and fluids; track it as an illness if it grows.", TrackAs.ILLNESS),
    CheckPattern("dehydration", "Low on fluids", "Drink more through the day and watch the colour of your pee."),
).associateBy { it.id }

private val H = CheckArea.HEAD
private val N = CheckArea.NOSE_THROAT
private val C = CheckArea.CHEST
private val S = CheckArea.STOMACH
private val M = CheckArea.MUSCLES
private val K = CheckArea.SKIN
private val E = CheckArea.EYES_EARS
private val U = CheckArea.URINARY
private val W = CheckArea.WHOLE_BODY

val CHECK_QUESTIONS: List<CheckQuestion> = listOf(
    // head
    CheckQuestion("head_sudden", H, "Did it come on suddenly, like the worst headache ever?", setOf("sym.headache"),
        urgent("A sudden, severe headache needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("head_neuro", H, "Any weakness, numbness, confusion, or trouble speaking or seeing?",
        flag = urgent("Weakness, numbness, confusion or trouble speaking or seeing needs medical attention now: call $EMERGENCY_NUMBER and note when it started.")),
    CheckQuestion("head_fever_neck", H, "With a fever and a stiff neck, or a new rash?", setOf("sym.headache"),
        urgent("A headache with fever and a stiff neck or a rash needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("head_knock", H, "After a knock to the head?", flag = clinician("A headache or dizziness after a knock to the head is worth a clinician's check today.")),
    CheckQuestion("head_band", H, "Like a tight band, on both sides?", setOf("sym.headache"), supports = setOf("tension")),
    CheckQuestion("head_one_side", H, "On one side, throbbing, with light or noise bothering you?", setOf("sym.headache"), supports = setOf("migraine_like")),
    CheckQuestion("head_sinus", H, "Pressure behind the forehead or cheeks, with a blocked nose?", setOf("sym.headache"), supports = setOf("sinus")),
    CheckQuestion("head_screen", H, "Long hours at a screen or a desk today?", setOf("sym.headache"), supports = setOf("tension")),
    CheckQuestion("head_shortfall", H, "Less coffee than usual, or little water today?", supports = setOf("shortfall", "dehydration")),
    CheckQuestion("head_exertion", H, "Started during or right after hard exercise?", setOf("sym.headache"), supports = setOf("exertional")),
    CheckQuestion("head_standing", H, "Light-headed when you stand up quickly?", setOf("sym.dizziness"), supports = setOf("orthostatic", "dehydration")),
    CheckQuestion("head_fainted", H, "Did you faint or nearly faint?", setOf("sym.dizziness"),
        urgent("Fainting, above all during or right after exercise, needs a medical check before training again; call $EMERGENCY_NUMBER with chest pain or a racing heart.")),
    // nose and throat
    CheckQuestion("nt_breathing", N, "Trouble breathing, or swallowing your own saliva?",
        flag = urgent("Trouble breathing or swallowing needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("nt_fever", N, "Fever (38 °C or more) or chills?", supports = setOf("flu_like")),
    CheckQuestion("nt_aches", N, "Aching all over and very tired?", supports = setOf("flu_like")),
    CheckQuestion("nt_contact", N, "Someone around you sick recently?", supports = setOf("cold", "flu_like")),
    CheckQuestion("nt_itchy", N, "Itchy eyes or nose, sneezing fits, worse outdoors or with dust?", supports = setOf("allergy")),
    CheckQuestion("nt_long", N, "Over 10 days, or worse again after getting better?",
        flag = clinician("A blocked nose or sore throat that lasts over 10 days, or comes back worse, is worth a clinician's view."), supports = setOf("sinus")),
    CheckQuestion("nt_throat_fever", N, "A very sore throat with fever and no cough?", setOf("sym.sore_throat"),
        clinician("A very sore throat with fever and no cough is worth a clinician's view: a throat infection can need treatment.")),
    CheckQuestion("nt_smell", N, "Lost your sense of taste or smell?", supports = setOf("flu_like")),
    // chest
    CheckQuestion("ch_pain", C, "Chest pain or pressure, or pain spreading to the arm, jaw or back?",
        flag = urgent("Chest pain or pressure needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("ch_rest", C, "Short of breath at rest?", flag = urgent("Being short of breath at rest needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("ch_blood", C, "Coughing up blood?", setOf("sym.cough"), clinician("Coughing up blood is worth a clinician's view today; call $EMERGENCY_NUMBER if it is more than a streak.")),
    CheckQuestion("ch_fever", C, "With fever or chills?", supports = setOf("chest_infection")),
    CheckQuestion("ch_exercise", C, "Only during or after hard exercise, with a wheeze, easing within an hour?", supports = setOf("eib")),
    CheckQuestion("ch_after_cold", C, "With a cold, or since one?", setOf("sym.cough"), supports = setOf("post_viral_cough", "cold")),
    CheckQuestion("ch_weeks", C, "A cough for over 3 weeks?", setOf("sym.cough"), clinician("A cough that lasts over 3 weeks is worth a clinician's view.")),
    // stomach
    CheckQuestion("st_blood", S, "Blood in vomit or stool, or black stool?", flag = urgent("Blood in vomit or stool, or black stool, needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("st_severe", S, "Severe pain that doesn't ease, or a hard, swollen belly?", flag = urgent("Severe stomach pain that doesn't ease needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("st_fluids", S, "Unable to keep fluids down for a day, or very little pee?",
        flag = clinician("Not keeping fluids down for a day, or very little pee, is worth a clinician's view today.")),
    CheckQuestion("st_others", S, "Others who ate the same thing are ill too?", supports = setOf("food_poisoning")),
    CheckQuestion("st_meal", S, "Started a few hours after a particular meal?", supports = setOf("food_poisoning", "diet_change")),
    CheckQuestion("st_bug", S, "People around you with a stomach bug?", supports = setOf("stomach_bug")),
    CheckQuestion("st_run", S, "During or after a long or hard run?", supports = setOf("runners_gut")),
    CheckQuestion("st_big_meal", S, "After a big, spicy, fatty or late meal, or alcohol?", supports = setOf("reflux", "diet_change")),
    CheckQuestion("st_change", S, "More fibre, a new supplement or more gels than usual?", supports = setOf("diet_change", "runners_gut")),
    // muscles and joints
    CheckQuestion("mu_session", M, "After a new, harder or longer session in the last 1-3 days?", supports = setOf("doms")),
    CheckQuestion("mu_both_sides", M, "On both sides, in the muscles you worked?", supports = setOf("doms")),
    CheckQuestion("mu_sudden", M, "A sudden sharp pain, a pop or a twist?", supports = setOf("acute_injury")),
    CheckQuestion("mu_swelling", M, "Swelling, bruising, or you can't put weight on it?", supports = setOf("acute_injury"),
        flag = clinician("Swelling, bruising or not being able to bear weight is worth a clinician's check, and tracking it as an injury.")),
    CheckQuestion("mu_hot_joint", M, "A hot, red, swollen joint, with fever?", flag = urgent("A hot, red, swollen joint with fever needs medical attention today: call $EMERGENCY_NUMBER if you feel very unwell.")),
    CheckQuestion("mu_calf", M, "A painful, swollen, warm calf after a long flight or drive?",
        flag = urgent("A painful, swollen, warm calf after long sitting needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("mu_tingling", M, "Numbness or tingling spreading down an arm or a leg?",
        flag = clinician("Numbness or tingling spreading down a limb is worth a clinician's view.")),
    CheckQuestion("mu_ill", M, "With a cold, flu or fever?", supports = setOf("viral_aches")),
    CheckQuestion("mu_posture", M, "After long sitting, sleeping awkwardly, or carrying something?", supports = setOf("strain")),
    // skin
    CheckQuestion("sk_swelling", K, "Swelling of the lips, tongue or face, or trouble breathing?",
        flag = urgent("Swelling of the lips, tongue or face, or trouble breathing, needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("sk_spreading", K, "With fever, or spreading fast, hot and painful?",
        flag = clinician("A rash with fever, or skin that is hot, painful and spreading, is worth a clinician's view today.")),
    CheckQuestion("sk_new", K, "A new food, product, plant or medicine in the last 2 days?", supports = setOf("skin_reaction")),
    CheckQuestion("sk_rub", K, "Where clothing or a strap rubs?", supports = setOf("chafing")),
    CheckQuestion("sk_sun", K, "After time in the sun?", supports = setOf("sunburn")),
    // eyes and ears
    CheckQuestion("ee_vision", E, "A sudden change in vision, or a painful red eye?", setOf("sym.eye_irritation"),
        urgent("A sudden change in vision or a painful red eye needs medical attention today: call $EMERGENCY_NUMBER if vision is lost.")),
    CheckQuestion("ee_sticky", E, "Sticky discharge from the eye?", setOf("sym.eye_irritation"), supports = setOf("conjunctivitis")),
    CheckQuestion("ee_itchy", E, "Itchy and watery, both eyes, with sneezing?", setOf("sym.eye_irritation"), supports = setOf("allergy")),
    CheckQuestion("ee_ear_fever", E, "Ear pain with fever, or discharge from the ear?", setOf("sym.earache"),
        clinician("Ear pain with fever or discharge is worth a clinician's view.")),
    CheckQuestion("ee_swim", E, "After swimming?", setOf("sym.earache"), supports = setOf("swimmers_ear")),
    // peeing
    CheckQuestion("ur_fever", U, "With fever, or pain in the back or side?",
        flag = clinician("Burning when peeing with fever or back pain is worth a clinician's view today.")),
    CheckQuestion("ur_blood", U, "Blood in your pee?", flag = clinician("Blood in your pee is worth a clinician's view.")),
    CheckQuestion("ur_water", U, "Drunk little water lately?", supports = setOf("dehydration")),
    // whole body
    CheckQuestion("wb_faint", W, "Fainted, or chest pain or a racing heart?",
        flag = urgent("Fainting, chest pain or a racing heart needs medical attention now: call $EMERGENCY_NUMBER.")),
    CheckQuestion("wb_harm", W, "Thoughts of harming yourself?", setOf("sym.low_mood"),
        urgent("If you are thinking about harming yourself, please call $CRISIS_NUMBER now (24 hours, free), or $EMERGENCY_NUMBER if you are in danger.")),
    CheckQuestion("wb_high_fever", W, "39.5 °C or more, or a fever for over 3 days?", setOf("sym.fever", "sym.chills"),
        clinician("A fever of 39.5 °C or more, or one lasting over 3 days, is worth a clinician's view.")),
    CheckQuestion("wb_mood_weeks", W, "Low most days for 2 weeks or more?", setOf("sym.low_mood"),
        clinician("Feeling low most days for 2 weeks or more is worth talking to a clinician about.")),
    CheckQuestion("wb_infection", W, "With a sore throat, cough or runny nose?", supports = setOf("viral")),
    CheckQuestion("wb_sleep", W, "Slept less than 6 hours last night?", supports = setOf("sleep_debt")),
    CheckQuestion("wb_training", W, "A heavy training week?", setOf("sym.fatigue", "sym.poor_sleep"), supports = setOf("training_fatigue")),
)

/** What the person's own data says, to prefill answers (they can change them). */
data class CheckContext(
    val sleepHoursLastNight: Double? = null,
    /** Minutes of recorded training in the last 72 hours, and the longest session among them. */
    val trainingMin72h: Double? = null,
    val longestSessionMin72h: Double? = null,
) {
    /** The prefilled answers and the words saying where each came from. */
    fun prefill(questions: List<CheckQuestion>): Map<String, Pair<Boolean, String>> {
        val ids = questions.map { it.id }.toSet()
        val out = mutableMapOf<String, Pair<Boolean, String>>()
        sleepHoursLastNight?.let { h ->
            if ("wb_sleep" in ids) out["wb_sleep"] = (h < 6) to "Last night: %.1f h asleep".format(h)
        }
        trainingMin72h?.let { m ->
            val long = longestSessionMin72h ?: 0.0
            val heavy = m >= 180 || long >= 90
            if ("mu_session" in ids) out["mu_session"] = (long >= 60 || m >= 120) to "Last 3 days: ${m.toInt()} min of training"
            if ("wb_training" in ids) out["wb_training"] = heavy to "Last 3 days: ${m.toInt()} min of training"
        }
        return out
    }
}

data class CheckAnswers(
    val area: CheckArea,
    val symptoms: Set<String>,
    val intensity: CheckIntensity,
    val since: CheckSince,
    val course: CheckCourse,
    /** Question id -> yes / no; a question left unanswered is not here. */
    val answers: Map<String, Boolean>,
    /** For muscles and joints: where (a vocabulary region), when given. */
    val region: String? = null,
    val side: String? = null,
)

data class PatternFit(val pattern: CheckPattern, val because: List<String>)

enum class CheckLevel { URGENT, CLINICIAN, SELF_CARE }

data class CheckOutcome(
    val level: CheckLevel,
    val flags: List<CheckFlag>,
    val patterns: List<PatternFit>,
    /** What to track it as, when the answers point to more than a small thing. */
    val track: TrackAs?,
    /** One line on what happens next. */
    val next: String,
)

/** The questions an area asks for the symptoms picked: flags first, then the rest in order. */
fun questionsFor(area: CheckArea, symptoms: Set<String>): List<CheckQuestion> =
    CHECK_QUESTIONS.filter { it.area == area && (it.symptoms.isEmpty() || it.symptoms.any { s -> s in symptoms }) }
        .sortedBy { if (it.flag?.level == FlagLevel.URGENT) 0 else if (it.flag != null) 1 else 2 }

/** The outcome of a symptom check. Pure and deterministic: the same answers always give the same result. */
fun assess(a: CheckAnswers): CheckOutcome {
    val asked = questionsFor(a.area, a.symptoms)
    val yes = asked.filter { a.answers[it.id] == true }
    val flags = yes.mapNotNull { it.flag }.toMutableList()
    // How it goes, as flags of its own.
    if (a.course == CheckCourse.WORSE && a.since == CheckSince.WEEK) {
        flags += clinician("Getting worse after a week or more is worth a clinician's view.")
    }
    if (a.intensity == CheckIntensity.STRONG && a.since.days >= 2 && a.area == CheckArea.HEAD) {
        flags += clinician("A strong headache for days is worth a clinician's view.")
    }
    if (a.area == CheckArea.URINARY && flags.none { it.level == FlagLevel.URGENT }) {
        flags += clinician("A burning feeling when peeing is worth a clinician's view: it is often a urinary infection that needs treatment.")
    }
    if ("sym.breathless" in a.symptoms && a.intensity == CheckIntensity.STRONG) {
        flags += urgent("Strong breathlessness needs medical attention now: call $EMERGENCY_NUMBER.")
    }
    val distinct = flags.distinctBy { it.text }.sortedBy { it.level.ordinal }

    val byPattern = mutableMapOf<String, MutableList<String>>()
    yes.forEach { q -> q.supports.forEach { p -> byPattern.getOrPut(p) { mutableListOf() } += q.text.trimEnd('?') } }
    // Symptoms that point somewhere on their own.
    if (a.area == CheckArea.NOSE_THROAT && byPattern.isEmpty()) byPattern.getOrPut("cold") { mutableListOf() } += "Nose and throat symptoms"
    if ("sym.fever" in a.symptoms || "sym.chills" in a.symptoms) byPattern.getOrPut("viral") { mutableListOf() } += "Fever or chills"
    val fits = byPattern.mapNotNull { (id, why) -> CHECK_PATTERNS[id]?.let { PatternFit(it, why.distinct()) } }
        .sortedByDescending { it.because.size }

    val level = when {
        distinct.any { it.level == FlagLevel.URGENT } -> CheckLevel.URGENT
        distinct.isNotEmpty() -> CheckLevel.CLINICIAN
        else -> CheckLevel.SELF_CARE
    }
    // Track it as more than a small thing: what the best fitting pattern says, or when it lasts or grows.
    val track = fits.firstOrNull()?.pattern?.track
        ?: when {
            a.area == CheckArea.MUSCLES && (a.since.days >= 7 || a.course == CheckCourse.WORSE) -> TrackAs.INJURY
            a.area != CheckArea.MUSCLES && a.since.days >= 2 && a.intensity != CheckIntensity.MILD -> TrackAs.ILLNESS
            else -> null
        }
    val next = when (level) {
        CheckLevel.URGENT -> "Act on the red flag first. This check is kept as you answered it."
        CheckLevel.CLINICIAN -> "Worth a clinician's view. Log it again if it changes."
        CheckLevel.SELF_CARE -> when {
            a.course == CheckCourse.BETTER -> "Likely to settle. Nothing to do unless it comes back."
            track != null -> "If it keeps going, track it so its days and recovery are followed."
            else -> "Likely a small thing. Check again if it lasts more than a few days or gets worse."
        }
    }
    return CheckOutcome(level, distinct, fits, track, next)
}

/**
 * The rows a symptom check writes: one check-in of scope 'check' (it belongs to no concern and says nothing about the
 * others), with the answers and the outcome as its assessment, and a signal per symptom at the chosen intensity. Where on
 * the body (muscles and joints) goes in the signal's details, so no concern is matched to it by region.
 */
fun buildSymptomCheck(
    a: CheckAnswers,
    outcome: CheckOutcome,
    now: java.time.ZonedDateTime,
    newId: () -> String = { java.util.UUID.randomUUID().toString() },
): CheckInPlan {
    val date = checkInDate(now)
    val id = newId()
    val assessment = kotlinx.serialization.json.buildJsonObject {
        put("version", kotlinx.serialization.json.JsonPrimitive(1))
        put("area", kotlinx.serialization.json.JsonPrimitive(a.area.name.lowercase()))
        put("intensity", kotlinx.serialization.json.JsonPrimitive(a.intensity.name.lowercase()))
        put("since", kotlinx.serialization.json.JsonPrimitive(a.since.name.lowercase()))
        put("course", kotlinx.serialization.json.JsonPrimitive(a.course.name.lowercase()))
        put("answers", kotlinx.serialization.json.JsonObject(a.answers.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
        put("level", kotlinx.serialization.json.JsonPrimitive(outcome.level.name.lowercase()))
        put("flags", kotlinx.serialization.json.JsonArray(outcome.flags.map { kotlinx.serialization.json.JsonPrimitive(it.text) }))
        put("patterns", kotlinx.serialization.json.JsonArray(outcome.patterns.map { kotlinx.serialization.json.JsonPrimitive(it.pattern.id) }))
        outcome.track?.let { put("track", kotlinx.serialization.json.JsonPrimitive(it.name.lowercase())) }
    }
    val obs = com.bioscan.fieldterminal.data.model.HealthObservationInsert(
        id = id, observedAt = now.toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        localDate = date.toString(), utcOffsetMin = now.offset.totalSeconds / 60, concernId = null, scope = "check",
        symptomFree = false, recall = "now", assessment = assessment,
    )
    val details = buildMap {
        put("area", a.area.name.lowercase())
        a.region?.let { put("region", it) }
        a.side?.let { put("side", it) }
    }
    val signals = a.symptoms.map { s ->
        com.bioscan.fieldterminal.data.model.HealthSignalInsert(
            observationId = id, symptom = s, severity = a.intensity.severity,
            details = kotlinx.serialization.json.JsonObject(details.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }),
        )
    }
    return CheckInPlan(emptyList(), listOf(obs), signals)
}

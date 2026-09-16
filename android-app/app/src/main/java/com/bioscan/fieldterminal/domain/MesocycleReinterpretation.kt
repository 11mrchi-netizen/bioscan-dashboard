package com.bioscan.fieldterminal.domain

// Phase A3 (Analysis Layer). Training Cycle Framing doc, section 3: this is
// a pure re-labeling layer on top of the Evaluation Method Spec's own
// per-stream states -- it never recomputes anything Layer 2 already
// produced, it only decides how a SHIFT_DOWN (or any other state) should be
// read given the active training cycle's stated focus. The same underlying
// state can be "expected" or "a real concern" purely depending on context;
// this file is what decides which.
enum class ExpectationTier { PrimaryTarget, Maintained, Unmanaged }

// One entry per Evaluation Method Spec category -- lets resolveTier()
// dispatch to the right lookup without every call site needing to know
// which categories are safety-exempt.
enum class MetricCategory { HrvRhr, Sleep, Subjective, Nutrition, BodyComposition, TrainingLoad, RestCadence, BristolInjury, Bloodwork }

// Section 3.3's safety carve-out, non-negotiable: recovery/safety signals
// are never downgraded to UNMANAGED (or re-labeled at all) regardless of
// what the active cycle is trying to build. A SHIFT_DOWN in HRV during a
// strength block is still a real flag. `null` from resolveTier() for these
// means exactly that -- "render Layer 2's state directly, full weight,
// no cycle framing applied" -- not "no cycle is active."
private val SAFETY_EXEMPT_CATEGORIES = setOf(
    MetricCategory.HrvRhr,
    MetricCategory.Sleep,
    MetricCategory.Subjective,
    MetricCategory.BristolInjury, // the injury/illness half specifically -- see the design doc
)

private val ENDURANCE_QUALITIES = setOf(FocusQuality.AerobicBase, FocusQuality.RaceSpecificEndurance)

// Section 3.2's metric-to-focus-quality lookup table is explicitly a sketch
// in the source doc ("needs real per-focus-type definition work once actual
// cycle data exists to test against") -- Category 6 is the one concretely
// wired here, since it's the only performance-adaptation Layer 2 metric
// this app has (strength volume-load is still A4 remaining work). Other
// non-exempt categories fall through to Unmanaged until their own mapping
// gets defined against real cycles, per that doc's own explicit permission
// to leave this partial rather than invent a mapping with nothing to test
// it against.
fun resolveTier(category: MetricCategory, cycle: TrainingCycle?): ExpectationTier? {
    if (category in SAFETY_EXEMPT_CATEGORIES) return null
    if (cycle == null) return null

    return when (category) {
        MetricCategory.TrainingLoad -> {
            val enduranceEntry = cycle.focus.firstOrNull { it.quality in ENDURANCE_QUALITIES }
            when {
                enduranceEntry == null -> ExpectationTier.Unmanaged
                enduranceEntry.role == FocusRole.Primary -> ExpectationTier.PrimaryTarget
                else -> ExpectationTier.Maintained
            }
        }
        else -> ExpectationTier.Unmanaged
    }
}

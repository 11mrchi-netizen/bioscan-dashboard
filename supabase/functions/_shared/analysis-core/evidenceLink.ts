// Intervention & Protocol Intelligence (08.4): matching an intervention to its own evidence, nothing else. Erasable
// TypeScript, no I/O -- evidence_records rows are fetched by the caller (the same convention EvidenceRepository.kt
// uses for the Kotlin-side join); this module only matches and ranks what's already in hand.
//
// evidence_records needed no schema change for this milestone: `subject_type`/`subject_key` were already open text
// (no CHECK constraint), so an intervention's own (subjectType, subjectKey) -- shared vocabulary by convention, not
// a foreign key, same as Supplement Intelligence already used it -- is already a valid lookup key. What this
// milestone adds is outcome-specific matching: a subject's evidence can differ by outcome (DAV-334: "Allow one
// intervention to have different evidence strength for different outcomes"), and an intervention names more than
// one intended outcome, so evidence is grouped per outcome rather than collapsed into one score.
export type EvidenceTier = 'meta_analysis' | 'systematic_review' | 'controlled_human' | 'observational' | 'animal' | 'mechanistic' | 'guideline' | 'user_observed'

export interface EvidenceRow {
  subjectType: string
  subjectKey: string
  outcomeKey: string
  claimText: string
  effectDirection: 'increase' | 'decrease' | 'stabilize' | null
  evidenceTier: EvidenceTier | null
  reviewStatus: 'unreviewed' | 'reviewed'
}

export interface EvidenceLink {
  outcomeKey: string
  /** Strongest tier first; unreviewed rows are kept (same "(unreviewed)" convention as interaction_records) but never reordered ahead of a reviewed row of equal tier. */
  evidence: EvidenceRow[]
}

// Lower ranks first. Meta-analyses/systematic reviews and controlled human trials outrank observational and
// mechanistic/animal evidence; the person's own N-of-1 result (08.5/08.6) is reported last here on purpose --
// it answers "did it work for me", not "is this well established", and the two are never collapsed into one figure.
const TIER_RANK: Record<EvidenceTier, number> = {
  meta_analysis: 0,
  systematic_review: 0,
  controlled_human: 1,
  guideline: 1,
  observational: 2,
  animal: 3,
  mechanistic: 4,
  user_observed: 5,
}

const tierRank = (tier: EvidenceTier | null) => (tier === null ? 99 : TIER_RANK[tier])
const reviewRank = (status: EvidenceRow['reviewStatus']) => (status === 'reviewed' ? 0 : 1)

/**
 * Evidence for one intervention, grouped by each outcome it names, strongest tier first within each outcome
 * (reviewed rows breaking a tie with an unreviewed one of the same tier). An outcome the intervention names but
 * that has no matching evidence still gets an entry, with an empty `evidence` array, so a caller can distinguish
 * "no evidence found" from "this outcome was never looked up."
 */
export function evidenceForIntervention(subjectType: string, subjectKey: string, intendedOutcomes: string[], evidence: EvidenceRow[]): EvidenceLink[] {
  const matching = evidence.filter((e) => e.subjectType === subjectType && e.subjectKey === subjectKey)
  return intendedOutcomes.map((outcomeKey) => ({
    outcomeKey,
    evidence: matching
      .filter((e) => e.outcomeKey === outcomeKey)
      .sort((a, b) => tierRank(a.evidenceTier) - tierRank(b.evidenceTier) || reviewRank(a.reviewStatus) - reviewRank(b.reviewStatus)),
  }))
}

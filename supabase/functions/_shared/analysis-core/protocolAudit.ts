// Intervention & Protocol Intelligence (08.8): protocol audit. Erasable TypeScript, no I/O.
//
// Detects when a protocol's active interventions make interpretation difficult or create avoidable complexity.
// Returns transparent findings -- never a score (DAV-338: "Return transparent findings, not a score"). Each finding
// names exactly which interventions it concerns and why, in the same plain-sentence style as the Linear issue's own
// example: "Attribution quality is low because four interventions affecting sleep were started within the same
// 10-day window."
//
// Pair-level overlap (shared mechanisms/ingredients, timing conflicts) is read from `interactions`, already computed
// elsewhere (domain/SupplementInteractions.kt's pairing/timing logic, or its generalized successor) -- this module
// never re-derives a pair finding itself, per DAV-338's "without duplicating stack-analysis logic." It only decides
// whether a pair's two sides both belong to the protocol being audited, and folds the result in alongside its own
// protocol-level findings.
export type AuditSeverity = 'info' | 'low' | 'moderate' | 'high'

export type AuditFindingCode =
  | 'same_outcome_overlap'
  | 'no_measurable_outcome'
  | 'insufficient_baseline'
  | 'insufficient_adherence'
  | 'high_burden_weak_evidence'
  | 'stale_evaluation'
  | 'interaction'

export interface AuditFinding {
  code: AuditFindingCode
  severity: AuditSeverity
  message: string
  interventionIds: string[]
}

export type LifecycleState = 'proposed' | 'baseline' | 'active' | 'evaluating' | 'validated' | 'maintained' | 'modified' | 'stopped'
export type Burden = 'low' | 'medium' | 'high'
export type EvidenceStrength = 'strong' | 'moderate' | 'weak' | 'none'

export interface AuditableIntervention {
  id: string
  name: string
  intendedOutcomes: string[]
  requiredMeasurements: string[]
  lifecycleState: LifecycleState
  baselineStartedAt: string | null
  startedAt: string | null
  adherenceRate: number | null
  minAdherence: number
  burden: Burden | null
  evidenceStrength: EvidenceStrength
}

export interface PrecomputedInteraction {
  aId: string
  bId: string
  severity: 'major' | 'moderate' | 'minor' | 'info'
  effect: string
}

export const MIN_BASELINE_DAYS = 14
// Matches DAV-338's own example almost verbatim.
export const CLUSTER_WINDOW_DAYS = 10
export const STALE_EVALUATION_DAYS = 60

const dayDiff = (a: string, b: string) => Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86400000)
const INTERACTION_SEVERITY: Record<PrecomputedInteraction['severity'], AuditSeverity> = { major: 'high', moderate: 'moderate', minor: 'low', info: 'info' }

/** A protocol's audit findings. Order is not meaningful; a caller sorts by severity for display if it wants to. */
export function auditProtocol(interventions: AuditableIntervention[], interactions: PrecomputedInteraction[], today: string): AuditFinding[] {
  const findings: AuditFinding[] = []
  const byId = new Map(interventions.map((iv) => [iv.id, iv]))

  for (const iv of interventions) {
    if (iv.intendedOutcomes.length === 0 || iv.requiredMeasurements.length === 0) {
      findings.push({
        code: 'no_measurable_outcome', severity: 'moderate',
        message: `"${iv.name}" has no measurable outcome defined, so its effect can never be evaluated.`,
        interventionIds: [iv.id],
      })
    }
    if (iv.lifecycleState !== 'proposed' && iv.baselineStartedAt && iv.startedAt && dayDiff(iv.baselineStartedAt, iv.startedAt) < MIN_BASELINE_DAYS) {
      findings.push({
        code: 'insufficient_baseline', severity: 'moderate',
        message: `"${iv.name}" has fewer than ${MIN_BASELINE_DAYS} baseline days before starting, so its before/after comparison will be weak.`,
        interventionIds: [iv.id],
      })
    }
    if (iv.adherenceRate !== null && iv.adherenceRate < iv.minAdherence) {
      findings.push({
        code: 'insufficient_adherence', severity: 'moderate',
        message: `"${iv.name}" adherence is ${Math.round(iv.adherenceRate * 100)}%, below the ${Math.round(iv.minAdherence * 100)}% needed to trust its result.`,
        interventionIds: [iv.id],
      })
    }
    if (iv.burden === 'high' && (iv.evidenceStrength === 'weak' || iv.evidenceStrength === 'none')) {
      findings.push({
        code: 'high_burden_weak_evidence', severity: 'moderate',
        message: `"${iv.name}" carries a high burden but has ${iv.evidenceStrength} evidence behind it for its intended outcome.`,
        interventionIds: [iv.id],
      })
    }
    if (iv.lifecycleState === 'evaluating' && iv.startedAt && dayDiff(iv.startedAt, today) > STALE_EVALUATION_DAYS) {
      findings.push({
        code: 'stale_evaluation', severity: 'low',
        message: `"${iv.name}" has been evaluating for more than ${STALE_EVALUATION_DAYS} days without a decision.`,
        interventionIds: [iv.id],
      })
    }
  }

  // Multiple interventions targeting the same outcome, started close together, make attribution unreliable --
  // this is the one finding the issue spells out almost word for word.
  const byOutcome = new Map<string, AuditableIntervention[]>()
  for (const iv of interventions) {
    if (!iv.startedAt) continue
    for (const outcome of iv.intendedOutcomes) {
      if (!byOutcome.has(outcome)) byOutcome.set(outcome, [])
      byOutcome.get(outcome)!.push(iv)
    }
  }
  for (const [outcome, ivs] of byOutcome) {
    if (ivs.length < 2) continue
    const starts = ivs.map((iv) => iv.startedAt!).sort()
    const span = dayDiff(starts[0], starts[starts.length - 1])
    if (span <= CLUSTER_WINDOW_DAYS) {
      findings.push({
        code: 'same_outcome_overlap', severity: 'high',
        message: `Attribution quality is low because ${ivs.length} interventions affecting ${outcome} were started within the same ${CLUSTER_WINDOW_DAYS}-day window.`,
        interventionIds: ivs.map((iv) => iv.id),
      })
    }
  }

  // Pair findings already computed elsewhere; only include a pair where both sides are actually in this protocol.
  for (const pair of interactions) {
    if (byId.has(pair.aId) && byId.has(pair.bId)) {
      findings.push({ code: 'interaction', severity: INTERACTION_SEVERITY[pair.severity], message: pair.effect, interventionIds: [pair.aId, pair.bId] })
    }
  }

  return findings
}

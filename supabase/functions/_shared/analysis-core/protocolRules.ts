// Intervention & Protocol Intelligence (08.7): adaptive protocol rules. Erasable TypeScript, no I/O.
//
// A deterministic, ordered rule set that turns already-computed readings (an 08.5 experiment or 08.6 intervention-
// response verdict, adherence, evidence strength, burden, data confidence) into one explainable action proposal --
// never a black-box recommendation and never a score. Every proposal carries its rationale as plain sentences and
// the exact inputs that produced it (`sourceInputs`), so a person (or 08.8's protocol audit) can see exactly why.
// This is a suggestion the person accepts, rejects or modifies (`protocol_adjustment_proposals.status`) -- calling
// this function never itself changes a protocol or intervention's `lifecycle_state`.
//
// Explicitly not here: gamified points, streaks, leaderboards, or any single universal score. Rules are read in
// order top to bottom; the first one that matches decides the action -- there is no weighted scoring underneath.
//
// `verdict` accepts both 08.5's pre-registered vocabulary (`effect_detected`/`no_detectable_effect`) and 08.6's
// observational one (`signal_observed`/`no_signal`) and reads the matching pair the same way -- except that, since
// analysis review phase F, an observational verdict never changes a protocol on its own: what happened after a start is
// not what the intervention did (anything else that changed at the same time counts too), so a proposal to stop, cut
// back, pause or step up becomes 'reassess', with the reason and the way to get a tested answer (a randomised
// self-experiment). Keeping things as they are, or giving it more time, still follows from an observed read.
export type ProtocolAction = 'increase' | 'decrease' | 'maintain' | 'pause' | 'extend_evaluation' | 'terminate' | 'reassess'
export type EvidenceStrength = 'strong' | 'moderate' | 'weak' | 'none'
export type Burden = 'low' | 'medium' | 'high'
export type ExpectedDirection = 'increase' | 'decrease' | 'stabilize'
export type Verdict = 'signal_observed' | 'no_signal' | 'insufficient' | 'effect_detected' | 'no_detectable_effect'

export interface ProtocolRuleInputs {
  verdict: Verdict | null
  observedDirection: 'increase' | 'decrease' | null
  expectedDirection: ExpectedDirection | null
  adherenceRate: number | null
  minAdherence: number
  evidenceStrength: EvidenceStrength
  burden: Burden | null
  confidenceHave: number
  confidenceNeed: number
}

export interface ProtocolActionProposal {
  action: ProtocolAction
  rationale: string[]
  sourceInputs: ProtocolRuleInputs
}

const SIGNAL: Verdict[] = ['signal_observed', 'effect_detected']
const NO_SIGNAL: Verdict[] = ['no_signal', 'no_detectable_effect']
const OBSERVED: Verdict[] = ['signal_observed', 'no_signal']
const CHANGES: ProtocolAction[] = ['terminate', 'decrease', 'pause', 'increase']

function propose(action: ProtocolAction, rationale: string[], inputs: ProtocolRuleInputs): ProtocolActionProposal {
  return { action, rationale, sourceInputs: inputs }
}

/** One explainable action proposal from already-computed readings. Deterministic: the first matching rule decides. */
export function proposeProtocolAction(inputs: ProtocolRuleInputs): ProtocolActionProposal {
  const p = firstMatchingRule(inputs)
  if (inputs.verdict !== null && OBSERVED.includes(inputs.verdict) && CHANGES.includes(p.action)) {
    const next = p.action === 'terminate'
      ? 'This was observed, not tested. Stopping is always your call; to find out what it actually does, test it with a randomised self-experiment.'
      : 'This was observed, not tested: test it with a randomised self-experiment before changing the protocol.'
    return propose('reassess', [...p.rationale, next], inputs)
  }
  return p
}

function firstMatchingRule(inputs: ProtocolRuleInputs): ProtocolActionProposal {
  const { verdict, observedDirection, expectedDirection, adherenceRate, minAdherence, evidenceStrength, burden, confidenceHave, confidenceNeed } = inputs

  // 1. Not enough data to read anything yet -- keep collecting before acting on noise.
  if (confidenceHave < confidenceNeed) {
    return propose('extend_evaluation', [`Not enough data yet: ${confidenceHave} of ${confidenceNeed} days needed.`], inputs)
  }
  // 2. Low adherence makes the comparison itself untrustworthy, whatever it shows -- the problem is engagement, not biology.
  if (adherenceRate !== null && adherenceRate < minAdherence) {
    return propose('reassess', [`Adherence was ${Math.round(adherenceRate * 100)}%, below the ${Math.round(minAdherence * 100)}% this protocol needs before its result can be trusted.`], inputs)
  }
  if (verdict === null || verdict === 'insufficient') {
    return propose('extend_evaluation', ['The comparison does not yet have enough days to say anything.'], inputs)
  }
  if (verdict && SIGNAL.includes(verdict)) {
    // A signal in the wrong direction is worse than no signal: stop rather than keep applying something working against the goal.
    if (expectedDirection && observedDirection && expectedDirection !== 'stabilize' && observedDirection !== expectedDirection) {
      return propose('terminate', [`The observed signal moved the opposite way from what was intended (expected ${expectedDirection}, observed ${observedDirection}).`], inputs)
    }
    if (burden === 'high') {
      return propose('decrease', ['A signal in the intended direction was observed, but this intervention carries a high burden -- try a lower dose/intensity before committing to it long-term.'], inputs)
    }
    return propose('maintain', ['A signal in the intended direction was observed and the burden is acceptable.'], inputs)
  }
  // verdict is 'no_signal' or 'no_detectable_effect' from here on.
  if (evidenceStrength === 'strong' && burden !== 'high') {
    return propose('extend_evaluation', ['No signal yet, but the established evidence for this outcome is strong enough that a longer exposure is worth trying before concluding it does not work.'], inputs)
  }
  if (evidenceStrength === 'none' || evidenceStrength === 'weak') {
    return propose('reassess', ['No signal observed, and the evidence behind this intervention for this outcome is weak or absent -- worth reconsidering rather than extending.'], inputs)
  }
  return propose('pause', ['No signal observed with moderate evidence behind it -- pause and revisit rather than committing further time to it right now.'], inputs)
}

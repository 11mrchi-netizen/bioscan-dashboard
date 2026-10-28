// Personal health agent, phase 0: a claim is never stronger than its evidence (analysis-core/evidenceItems.ts classes).
//
// Causal wording ("caused", "because of", "improves") is allowed only when the turn's tool results hold an experiment
// with a clear verdict; associations and interrupted time series read "went with", "was linked with", "coincided with".
// A hedged suggestion to test something ("could improve", "to see whether it helps") is advice, not a claim, and passes.
// The check is per reply, not per sentence: a clear experiment in the results lets the reply speak of cause. That is
// looser than per-sentence attribution, which needs the reply to cite its result inline (a phase 1 decision).

const CAUSAL = /\b(caus(?:e|es|ed|ing)|because of|due to|leads? to|led to|results? in|resulted in|is why|the reason (?:for|why)|improv(?:e|es|ed|ing)|worsen(?:s|ed|ing)?|boost(?:s|ed|ing)?|harm(?:s|ed|ing)?|damag(?:e|es|ed|ing)|trigger(?:s|ed)?|lower(?:s|ed) your|rais(?:es|ed) your|made your|makes your)\b/gi
const HEDGE = /\b(may|might|could|would|can|whether|if|test|testing|try|trying|see if|find out)\b/

export interface ClaimResult {
  phrase: string
  index: number
}

/** Does any tool result carry an experiment with a clear verdict? Items follow the EvidenceItem shape. */
export function hasClearExperiment(...sources: unknown[]): boolean {
  let found = false
  const walk = (v: unknown): void => {
    if (found || !v || typeof v !== 'object') return
    if (Array.isArray(v)) return v.forEach(walk)
    const o = v as Record<string, unknown>
    if (o.evidenceClass === 'experiment' && o.verdict === 'clear') found = true
    else Object.values(o).forEach(walk)
  }
  sources.forEach(walk)
  return found
}

/** Causal phrases (not in capitals: IMPROVING, a trend label, is vocabulary) not hedged by the four words before them. */
export function causalClaims(text: string): ClaimResult[] {
  const out: ClaimResult[] = []
  for (const m of text.matchAll(CAUSAL)) {
    if (m[0] === m[0].toUpperCase()) continue
    const before = text.slice(Math.max(0, m.index! - 40), m.index!).split(/\s+/).slice(-4).join(' ').toLowerCase()
    if (HEDGE.test(before)) continue
    out.push({ phrase: m[0], index: m.index! })
  }
  return out
}

export function unsupportedClaims(text: string, ...sources: unknown[]): ClaimResult[] {
  return hasClearExperiment(...sources) ? [] : causalClaims(text)
}

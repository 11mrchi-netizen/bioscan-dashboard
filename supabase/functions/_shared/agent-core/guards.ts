// Personal health agent, phase 0: the checks every reply passes before it is shown (docs/personal-health-agent).
//
// checkReply runs the deterministic guards on a drafted reply against what the turn actually saw: the question, the
// screen context and the tool results. The caller (health-agent, phase 1) regenerates once on a violation, then shows
// `cleaned`: the reply without the sentences that broke a rule, with a note that something was removed.

import { causalClaims, hasClearExperiment } from './claims.ts'
import { dropSentences, groundingFrom, sentenceAt, ungroundedNumbers } from './numbers.ts'
import { allowedLabels, inventedScores, labelsIn } from './vocabulary.ts'

export type ViolationCode = 'ungrounded_number' | 'label_not_from_tools' | 'invented_score' | 'causal_claim' | 'empty_reply'

export interface Violation {
  code: ViolationCode
  /** The offending text. */
  text: string
  /** Index of the sentence it sits in, or -1. */
  sentence: number
}

export interface ToolOutput {
  tool: string
  args?: unknown
  data: unknown
}

export interface ReplyInput {
  reply: string
  question: string
  /** What the screen passed in: card title, series, date, the values it shows. */
  context?: unknown
  outputs: ToolOutput[]
}

export interface ReplyCheck {
  ok: boolean
  violations: Violation[]
  /** The reply without violating sentences; null when nothing would be left. */
  cleaned: string | null
}

export function checkReply(input: ReplyInput): ReplyCheck {
  const { reply, question, context, outputs } = input
  const data = outputs.map((o) => o.data)
  const violations: Violation[] = []
  if (!reply.trim()) return { ok: false, violations: [{ code: 'empty_reply', text: '', sentence: -1 }], cleaned: null }

  const grounding = groundingFrom(question, context ?? null, data)
  for (const t of ungroundedNumbers(reply, grounding)) {
    violations.push({ code: 'ungrounded_number', text: t.raw, sentence: sentenceAt(reply, t.index) })
  }

  const labels = allowedLabels(question, context ?? null, data)
  for (const l of labelsIn(reply)) {
    if (!labels.has(l.label)) violations.push({ code: 'label_not_from_tools', text: l.label, sentence: sentenceAt(reply, l.index) })
  }

  for (const s of inventedScores(reply, question, context ?? null, data)) {
    violations.push({ code: 'invented_score', text: s.phrase, sentence: sentenceAt(reply, s.index) })
  }

  if (!hasClearExperiment(data)) {
    for (const c of causalClaims(reply)) violations.push({ code: 'causal_claim', text: c.phrase, sentence: sentenceAt(reply, c.index) })
  }

  const drop = new Set(violations.map((v) => v.sentence).filter((i) => i >= 0))
  const cleaned = violations.length ? dropSentences(reply, drop) : reply
  return { ok: violations.length === 0, violations, cleaned: cleaned.trim() ? cleaned : null }
}

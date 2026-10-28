// Personal health agent, phase 0: symptom questions are triaged by fixed rules before any model call
// (docs/personal-health-agent/README.md: symptom assessment is not a mode). An urgent match returns fixed text and the
// model is not called; a clinician match puts fixed text first and the agent may then only describe the data. Wording
// follows the health-concern safety prompts (analysis-core/healthEpisodes.ts `safety`): non-diagnostic, no hidden
// thresholds. A false alarm costs one paragraph; a miss can cost more, so the patterns lean wide and only an explicit
// negation right before the symptom ("no chest pain") clears it.

export type FlagLevel = 'urgent' | 'clinician'

export interface RedFlagRule {
  code: string
  level: FlagLevel
  pattern: RegExp
  text: string
}

/** Emergency numbers where the person lives (Taiwan: 119 ambulance, 1925 the 24-hour suicide prevention line). */
export interface LocalNumbers {
  emergency: string
  crisis: string
}
export const TAIWAN: LocalNumbers = { emergency: '119', crisis: '1925' }

export function rules(n: LocalNumbers): RedFlagRule[] {
  return [
    {
      code: 'self_harm',
      level: 'urgent',
      pattern: /\b(suicid\w*|kill(?:ing)? myself|end(?:ing)? my life|self[- ]harm\w*|hurt(?:ing)? myself|don'?t want to (?:live|be alive))\b/i,
      text: `If you are thinking about harming yourself, please call ${n.crisis} now (24 hours, free), or ${n.emergency} if you are in danger. You do not have to go through this alone.`,
    },
    {
      code: 'chest',
      level: 'urgent',
      pattern: /\b(chest (?:pain|pressure|tightness|hurts?|ache)|pain in (?:my|the) chest|tight chest|heart attack)\b/i,
      text: `Chest pain or pressure needs medical attention now. Call ${n.emergency}, especially if it spreads to the arm, jaw or back, or comes with breathlessness, sweating or nausea.`,
    },
    {
      code: 'breathing',
      level: 'urgent',
      pattern: /\b(can'?t (?:breathe|catch my breath)|short(?:ness)? of breath at rest|breathless at rest|struggling to breathe|throat (?:is )?(?:swelling|closing))\b/i,
      text: `Trouble breathing at rest needs medical attention now. Call ${n.emergency}.`,
    },
    {
      code: 'stroke',
      level: 'urgent',
      pattern: /\b(face (?:is |was )?droop\w*|slurred speech|speech (?:is |was )?slurred|slurring (?:my )?words|(?:weak|weakness|numb|numbness) (?:on|in|down) one side|one side of my (?:face|body)|can'?t (?:lift|move) my (?:arm|leg))\b/i,
      text: `Sudden weakness or numbness on one side, a drooping face or slurred speech can be a stroke. Call ${n.emergency} now and note the time it started.`,
    },
    {
      code: 'fainting',
      level: 'urgent',
      // "HRV collapsed" is not a person collapsing, so a collapse needs a person as its subject.
      pattern: /\b(fainted|fainting|fe(?:el|lt) faint|passed out|blacked out|lost consciousness|(?:I|me|I've|I have) (?:just )?collapsed)\b/i,
      text: `Fainting or collapsing, above all during or right after exercise, needs a medical check before training again. Call ${n.emergency} if it happened with chest pain, a racing heart or breathlessness.`,
    },
    {
      code: 'headache',
      level: 'urgent',
      pattern: /\b(worst headache|sudden (?:severe )?headache|thunderclap)\b/i,
      text: `A sudden, severe headache needs medical attention now. Call ${n.emergency}.`,
    },
    {
      code: 'bleeding',
      level: 'clinician',
      pattern: /\b(blood in (?:my )?(?:stool|poo|urine|pee|vomit)|vomit(?:ed|ing)? blood|cough(?:ed|ing)? (?:up )?blood|black(?:,)? tarry stool)\b/i,
      text: 'Blood where it should not be is a reason to see a clinician soon, today if there is a lot of it or you feel faint.',
    },
    {
      code: 'fever',
      level: 'clinician',
      pattern: /\b(?:fever|temperature)\D{0,20}(?:39|4[0-2])(?:[.,]\d)?\s*°?\s*c?\b|\b(?:39|4[0-2])(?:[.,]\d)?\s*°\s*c?\b/i,
      text: 'A temperature of 39 °C or more. Consider seeing a clinician, and rest from hard training while it lasts.',
    },
    {
      code: 'heart_rhythm',
      level: 'clinician',
      pattern: /\b(palpitations?|heart (?:is )?(?:racing|pounding|skipping|fluttering)|irregular (?:heart ?beat|pulse))\b/i,
      text: 'A racing, skipping or irregular heartbeat is worth a clinician check, sooner if it comes with dizziness, chest pain or breathlessness. The watch cannot tell what kind of rhythm it is.',
    },
    {
      code: 'neuro',
      level: 'clinician',
      pattern: /\b(numb(?:ness)?|tingl\w+|pins and needles)\b/i,
      text: 'Numbness, tingling or pain spreading along a limb. Consider seeing a clinician.',
    },
  ]
}

const NEGATION = /\b(no|not|never|without|don'?t have|didn'?t have|haven'?t had|free of|zero)\s+(?:\w+\s+){0,2}$/i

export interface Triage {
  level: FlagLevel
  codes: string[]
  /** Fixed text, urgent first; shown before (urgent: instead of) anything the agent writes. */
  text: string
  callModel: boolean
}

/** Red flags in a question, or null. Run on every question before routing. */
export function triage(question: string, n: LocalNumbers = TAIWAN): Triage | null {
  const hits: RedFlagRule[] = []
  for (const r of rules(n)) {
    const m = question.match(r.pattern)
    if (!m) continue
    const before = question.slice(Math.max(0, m.index! - 30), m.index!)
    if (NEGATION.test(before) && !/\bbut\b/i.test(before)) continue
    hits.push(r)
  }
  if (!hits.length) return null
  hits.sort((a, b) => (a.level === b.level ? 0 : a.level === 'urgent' ? -1 : 1))
  const level = hits[0].level
  return { level, codes: hits.map((h) => h.code), text: hits.map((h) => h.text).join(' '), callModel: level !== 'urgent' }
}

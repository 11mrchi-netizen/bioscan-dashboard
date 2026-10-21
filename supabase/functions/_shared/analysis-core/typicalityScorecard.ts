// Analysis review phase G (P11): the typicality engine's scorecard. Erasable TypeScript, no I/O.
//
// The typicality card exists to say "this looks like the start of being ill" (a strain day: elevated or unusual, with
// resting heart rate up). Whether it does that for this person is checked on their own history: the engine is replayed
// two weeks at a time, each block judged against the baseline that existed before it (so no day sees its own future), and
// every strain day outside an illness is checked against the illnesses logged afterwards. Precision: the share of strain
// days followed by a logged illness within three days. Recall: the share of logged illnesses with a strain day in the
// three days before. Both are set against the base rate, the share of all scored days that happen to fall in the three
// days before an illness: a flag that does no better than that tells nothing.
import { analyseTypicality, EVAL_DAYS } from './typicality.ts'
import type { TypicalityInput } from './typicality.ts'

export const TYPICALITY_SCORECARD = { leadDays: 3, minOnsets: 5, minFlags: 10, negligibleLift: 1.5 }

export type TypicalityVerdict = 'earns_its_place' | 'no_better' | 'unclear' | 'not_yet'

export interface TypicalityScorecard {
  verdict: TypicalityVerdict
  scoredDays: number
  onsets: number
  caught: number
  flags: number
  flagsBeforeOnset: number
  precision: number | null
  /** 95% Wilson interval of the precision. */
  precisionLo: number | null
  precisionHi: number | null
  recall: number | null
  baseRate: number | null
  sentence: string
}

function wilson(k: number, n: number): [number, number] {
  if (n === 0) return [0, 1]
  const z = 1.96
  const p = k / n
  const centre = (p + (z * z) / (2 * n)) / (1 + (z * z) / n)
  const half = (z * Math.sqrt((p * (1 - p)) / n + (z * z) / (4 * n * n))) / (1 + (z * z) / n)
  return [Math.max(0, centre - half), Math.min(1, centre + half)]
}

export function typicalityScorecard(input: TypicalityInput): TypicalityScorecard {
  const n = input.dates.length
  const excluded = input.excluded
  // Replay, newest block first, until the baseline runs out.
  const strainByIndex = new Map<number, boolean>()
  for (let end = n - 1; end >= 0; end -= EVAL_DAYS) {
    const restart = (input.restartIndex ?? 0) <= end ? input.restartIndex ?? 0 : 0
    const cut = <T>(xs: T[]) => xs.slice(0, end + 1)
    const res = analyseTypicality({
      dates: cut(input.dates), hrv: cut(input.hrv), rhr: cut(input.rhr), sleepHours: cut(input.sleepHours),
      deepRemMin: cut(input.deepRemMin), steps: cut(input.steps), excluded: cut(excluded), restartIndex: restart, skip: input.skip,
    })
    if (res.state === 'building') break
    for (const d of res.recent) {
      if (d.state === 'insufficient') continue
      strainByIndex.set(input.dates.indexOf(d.date), d.strain)
    }
  }
  const onsets = input.dates.map((_, i) => i).filter((i) => excluded[i] && !(i > 0 && excluded[i - 1]))
  const lead = TYPICALITY_SCORECARD.leadDays
  const onsetWithin = (i: number) => onsets.some((o) => o > i && o - i <= lead)
  // Days outside an illness that the replay scored.
  const scored = [...strainByIndex.keys()].filter((i) => !excluded[i]).sort((a, b) => a - b)
  const flagged = scored.filter((i) => strainByIndex.get(i))
  const flagsBeforeOnset = flagged.filter(onsetWithin).length
  const judgedOnsets = onsets.filter((o) => scored.some((i) => i < o && o - i <= lead))
  const caught = judgedOnsets.filter((o) => flagged.some((i) => i < o && o - i <= lead)).length
  const baseRate = scored.length ? scored.filter(onsetWithin).length / scored.length : null
  const precision = flagged.length ? flagsBeforeOnset / flagged.length : null
  const [lo, hi] = wilson(flagsBeforeOnset, flagged.length)
  const recall = judgedOnsets.length ? caught / judgedOnsets.length : null
  const r3 = (v: number | null) => (v === null ? null : Math.round(v * 1000) / 1000)
  const pct = (v: number | null) => (v === null ? '-' : `${Math.round(v * 100)}%`)

  let verdict: TypicalityVerdict
  if (judgedOnsets.length < TYPICALITY_SCORECARD.minOnsets || flagged.length < TYPICALITY_SCORECARD.minFlags || baseRate === null) verdict = 'not_yet'
  else if (lo > baseRate) verdict = 'earns_its_place'
  else if (hi < TYPICALITY_SCORECARD.negligibleLift * baseRate) verdict = 'no_better'
  else verdict = 'unclear'
  const tally = `${flagsBeforeOnset} of ${flagged.length} strain days came in the ${lead} days before a logged illness (${pct(precision)}, against ${pct(baseRate)} of all days); it flagged ${caught} of ${judgedOnsets.length} illnesses beforehand`
  const sentence = verdict === 'not_yet'
    ? `Scored once there are ${TYPICALITY_SCORECARD.minOnsets} logged illnesses and ${TYPICALITY_SCORECARD.minFlags} strain days on the replayed history (${judgedOnsets.length} and ${flagged.length} so far).`
    : verdict === 'earns_its_place'
      ? `${tally}: its strain flag tells you something.`
      : verdict === 'no_better'
        ? `${tally}: no better than chance, so read the flag as unusual, not as a warning.`
        : `${tally}: not settled yet.`
  return {
    verdict, scoredDays: scored.length, onsets: judgedOnsets.length, caught, flags: flagged.length, flagsBeforeOnset,
    precision: r3(precision), precisionLo: flagged.length ? r3(lo) : null, precisionHi: flagged.length ? r3(hi) : null,
    recall: r3(recall), baseRate: r3(baseRate), sentence,
  }
}

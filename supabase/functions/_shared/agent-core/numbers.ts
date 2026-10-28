// Personal health agent, phase 0: every number in a reply must come from a tool result of the same turn, the question
// or the screen that opened the agent. The model may round a value but never compute one: a difference, an average or
// a unit conversion it worked out itself is ungrounded, so the tool layer serves display forms ("6 h 52 min") next to
// raw values. Same idea as log-agent's number grounding (_shared/log-core/proposal.ts), for prose instead of fields.

export type NumberToken =
  | { kind: 'number'; value: number; raw: string; index: number; percent: boolean }
  | { kind: 'date'; raw: string; index: number }
  | { kind: 'time'; raw: string; index: number }

const WORDS: Record<string, number> = {
  zero: 0, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12,
  thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19, twenty: 20,
  thirty: 30, forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90, hundred: 100,
}
// "one" is left out on purpose: as a pronoun ("the one before") it is far more common than as a count.
const TENS = new Set(['twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'])

const DATE = /\b\d{4}-\d{2}-\d{2}\b/g
const TIME = /\b(?:[01]?\d|2[0-3]):[0-5]\d\b/g
// A number not glued to a letter (VO2max, D3, B12, R8 and omega-3 are names, not values).
const NUM = /(?<![A-Za-z0-9.,]|[A-Za-z]-)([-−]?)(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?![A-Za-z]*\d)/g

/** The numbers, dates and times a text states, in order. */
export function extractNumbers(text: string): NumberToken[] {
  const out: NumberToken[] = []
  const taken: [number, number][] = []
  const free = (i: number, len: number) => !taken.some(([a, b]) => i < b && i + len > a)
  for (const m of text.matchAll(DATE)) {
    out.push({ kind: 'date', raw: m[0], index: m.index! })
    taken.push([m.index!, m.index! + m[0].length])
  }
  for (const m of text.matchAll(TIME)) {
    if (!free(m.index!, m[0].length)) continue
    out.push({ kind: 'time', raw: m[0].padStart(5, '0'), index: m.index! })
    taken.push([m.index!, m.index! + m[0].length])
  }
  for (const m of text.matchAll(NUM)) {
    if (!free(m.index!, m[0].length)) continue
    // A minus only counts as a sign at the start or after a space or bracket ("3-5" is a range, not -5).
    const before = text[m.index! - 1]
    const signed = m[1] !== '' && (m.index === 0 || before === ' ' || before === '(' || before === undefined)
    const intPart = m[2].replace(/,/g, '')
    const value = Number(`${intPart}${m[3] ? '.' + m[3] : ''}`) * (signed ? -1 : 1)
    const end = m.index! + m[0].length
    const percent = /^\s?%/.test(text.slice(end, end + 2))
    out.push({ kind: 'number', value, raw: m[0], index: m.index!, percent })
  }
  const words = [...text.toLowerCase().matchAll(/[a-z]+/g)]
  for (let i = 0; i < words.length; i++) {
    const w = words[i][0]
    if (!(w in WORDS)) continue
    const next = words[i + 1]?.[0]
    if (TENS.has(w) && next && next in WORDS && WORDS[next] >= 2 && WORDS[next] <= 9) {
      out.push({ kind: 'number', value: WORDS[w] + WORDS[next], raw: `${w} ${next}`, index: words[i].index!, percent: false })
      i++
      continue
    }
    out.push({ kind: 'number', value: WORDS[w], raw: w, index: words[i].index!, percent: false })
  }
  return out.sort((a, b) => a.index - b.index)
}

export interface Grounding {
  numbers: number[]
  dates: Set<string>
  times: Set<string>
}

/** Everything a turn may quote: numeric leaves and the numbers, dates and times inside string leaves. */
export function groundingFrom(...sources: unknown[]): Grounding {
  const numbers: number[] = []
  const dates = new Set<string>()
  const times = new Set<string>()
  const walk = (v: unknown): void => {
    if (typeof v === 'number' && Number.isFinite(v)) numbers.push(v)
    else if (typeof v === 'string') {
      for (const m of v.matchAll(/(\d{4})-(\d{2})-(\d{2})/g)) {
        dates.add(m[0])
        numbers.push(Number(m[1]), Number(m[2]), Number(m[3]))
      }
      for (const m of v.matchAll(/(?:^|[^\d])((?:[01]?\d|2[0-3]):[0-5]\d)/g)) times.add(m[1].padStart(5, '0'))
      for (const t of extractNumbers(v)) if (t.kind === 'number') numbers.push(t.value)
    } else if (Array.isArray(v)) v.forEach(walk)
    else if (v && typeof v === 'object') Object.values(v).forEach(walk)
  }
  sources.forEach(walk)
  return { numbers, dates, times }
}

const decimals = (raw: string) => (raw.includes('.') ? raw.split('.')[1].length : 0)
const roundTo = (x: number, d: number) => Math.round(x * 10 ** d) / 10 ** d

/**
 * A stated number is grounded by a source value it equals, or rounds from at the stated precision (38 from 38.4, 4.2
 * from 4.17), with either sign ("fell by 4.2" for a change of -4.2). With a % sign it may also be a fraction shown as
 * a percentage (12 % from 0.12).
 */
export function isGrounded(t: NumberToken, g: Grounding): boolean {
  if (t.kind === 'date') return g.dates.has(t.raw)
  if (t.kind === 'time') return g.times.has(t.raw)
  const d = decimals(t.raw)
  const want = Math.abs(t.value)
  return g.numbers.some((n) => {
    if (roundTo(Math.abs(n), d) === want) return true
    return t.percent && Math.abs(n) <= 1 && roundTo(Math.abs(n) * 100, d) === want
  })
}

export function ungroundedNumbers(text: string, g: Grounding): NumberToken[] {
  return extractNumbers(text).filter((t) => !isGrounded(t, g))
}

// ---- sentences --------------------------------------------------------------------------------------------------------

/** Sentence spans of a reply; a decimal point or an abbreviation like "vs." does not end a sentence. */
export function sentences(text: string): { start: number; end: number; text: string }[] {
  const out: { start: number; end: number; text: string }[] = []
  // A break is whitespace after . ! or ? (a decimal point has none after it), or a newline.
  const breaks = /(?<=(?<!\b(?:vs|e\.g|i\.e|approx|ca))[.!?])\s+|\n+/g
  let start = 0
  const push = (end: number) => {
    const t = text.slice(start, end)
    if (t.trim()) out.push({ start, end, text: t.trim() })
  }
  for (const m of text.matchAll(breaks)) {
    push(m.index! + m[0].length)
    start = m.index! + m[0].length
  }
  push(text.length)
  return out
}

export function sentenceAt(text: string, index: number): number {
  return sentences(text).findIndex((s) => index >= s.start && index < s.end)
}

/** The reply without the given sentences. */
export function dropSentences(text: string, drop: Set<number>): string {
  return sentences(text)
    .filter((_, i) => !drop.has(i))
    .map((s) => s.text)
    .join(' ')
}

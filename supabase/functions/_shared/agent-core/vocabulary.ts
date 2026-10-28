// Personal health agent, phase 0: the reply speaks the screens' vocabulary and invents none of its own
// (design/ANALYTICAL_PRESENTATION_CONTRACT.md R8; IA.md 6.2: no proprietary universal score).
//
// One rule covers the whole vocabulary without a copy of it here: a label in capitals (BELOW EXPECTED, MIXED SIGNALS,
// IS TODAY TYPICAL?) may appear only when a tool result or the screen that opened the agent used it in this turn. So
// the agent can repeat a verdict it was given and cannot coin one, or attach a verdict to the wrong card. The same goes
// for score names: "sleep score" is allowed when the data has one, "readiness score" is not invented.

/** Capitalised abbreviations that are units or metric names, not verdicts. */
export const ACRONYMS = new Set([
  'HRV', 'RHR', 'HR', 'BPM', 'RMSSD', 'SRI', 'VO2MAX', 'TSB', 'ATL', 'CTL', 'TRIMP', 'GI', 'GL', 'ABV', 'BMI', 'MRT',
  'UTC', 'REM', 'NREM', 'ECG', 'SPO2', 'HDL', 'LDL', 'TSH', 'FT4', 'HBA1C', 'CRP', 'HSCRP', 'ALT', 'AST', 'GGT', 'EGFR',
  'RBC', 'WBC', 'MCV', 'PPG', 'GPS', 'DEXA', 'OK', 'N', 'I', 'A', 'AM', 'PM', 'KG', 'KCAL', 'ML', 'MG', 'IU', 'MMOL',
])

// An apostrophe belongs to a label only between capitals (TODAY'S), so "I've" is not the label I'.
const LABEL = /\b[A-Z](?:[A-Z0-9]|['’](?=[A-Z]))*(?:[ -]+[A-Z](?:[A-Z0-9]|['’](?=[A-Z]))*)*\b\??/g

const norm = (s: string) => s.replace(/’/g, "'").replace(/\?$/, '').trim()

/** Capitalised phrases in a text, without the abbreviations above. Single capital letters are ordinary words. */
export function labelsIn(text: string): { label: string; index: number }[] {
  const out: { label: string; index: number }[] = []
  for (const m of text.matchAll(LABEL)) {
    // Abbreviations at either end are not part of the label ("HRV BELOW EXPECTED" is the label BELOW EXPECTED).
    const words = norm(m[0]).split(' ')
    const plain = (w: string) => w.split('-').every((p) => ACRONYMS.has(p.replace(/'S$/, '')) || p.length < 2)
    while (words.length && plain(words[0])) words.shift()
    while (words.length && plain(words[words.length - 1])) words.pop()
    if (!words.length) continue
    out.push({ label: words.join(' '), index: m.index! })
  }
  return out
}

/** Every capitalised phrase the sources use, as a set of whole labels and of their sub-phrases. */
export function allowedLabels(...sources: unknown[]): Set<string> {
  const out = new Set<string>()
  const walk = (v: unknown): void => {
    if (typeof v === 'string') {
      for (const { label } of labelsIn(v)) {
        const words = label.split(' ')
        for (let i = 0; i < words.length; i++) for (let j = i + 1; j <= words.length; j++) out.add(words.slice(i, j).join(' '))
      }
    } else if (Array.isArray(v)) v.forEach(walk)
    else if (v && typeof v === 'object') Object.values(v).forEach(walk)
  }
  sources.forEach(walk)
  return out
}

const SCORE = /\b(?:(readiness|recovery|health|wellness|fitness|stress|sleep|body|energy|strain|longevity)\s+(score|index|rating))\b|\b\d{1,3}\s*(?:\/|out of)\s*(?:10|100)\b/gi

/** Score names and "n/100" ratings the sources do not contain. */
export function inventedScores(text: string, ...sources: unknown[]): { phrase: string; index: number }[] {
  const corpus = JSON.stringify(sources).toLowerCase()
  const out: { phrase: string; index: number }[] = []
  for (const m of text.matchAll(SCORE)) {
    if (corpus.includes(m[0].toLowerCase())) continue
    out.push({ phrase: m[0], index: m.index! })
  }
  return out
}

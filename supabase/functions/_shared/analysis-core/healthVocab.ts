// Health concerns (docs/health-concerns/01-plan.md §2.2): the region and symptom vocabulary as data. The JSON
// (../health-vocab/health-vocab.json) is the one copy: the Android app packages it as an asset, the migration seeds
// public.health_vocab from it (a test keeps them equal), and the engine reads region groups and exercise muscles from it.
// Codes are never reused; a change bumps vocabVersion. Erasable TypeScript, no I/O.

export type RegionGroup = 'head_neck' | 'trunk' | 'upper_limb' | 'lower_limb'
export type Tissue = 'joint' | 'tendon' | 'muscle' | 'bone' | 'spine' | 'other'
export type SymptomScope = 'local' | 'systemic' | 'any'

export interface VocabQuestion {
  id: string
  text: string
  type: 'yes_no' | 'choice' | 'number'
  options?: string[]
  min?: number
  max?: number
}

export interface VocabRegion {
  code: string
  label: string
  group: RegionGroup
  sided: boolean
  tissue: Tissue
  /** exercise_library muscle names whose work loads this region (regional strength exposure). */
  muscles: string[]
  followups: string[]
}

export interface VocabSymptom {
  code: string
  label: string
  scope: SymptomScope
  group?: string
  followups: string[]
}

export interface HealthVocab {
  vocabVersion: string
  questions: VocabQuestion[]
  regions: VocabRegion[]
  symptoms: VocabSymptom[]
  /** The complete exercise_library muscle vocabulary (as domain/RegionalLoad.kt). */
  exerciseMuscles: string[]
}

const GROUPS = new Set(['head_neck', 'trunk', 'upper_limb', 'lower_limb'])
const TISSUES = new Set(['joint', 'tendon', 'muscle', 'bone', 'spine', 'other'])
const SCOPES = new Set(['local', 'systemic', 'any'])
const SEMVER = /^\d+\.\d+\.\d+$/
const CODE = /^[a-z][a-z0-9_]*$/
const SYM_CODE = /^sym\.[a-z][a-z0-9_]*$/

/** Problems with the vocabulary, empty when valid. */
export function validateVocab(v: HealthVocab): string[] {
  const out: string[] = []
  if (!SEMVER.test(v.vocabVersion)) out.push(`vocabVersion "${v.vocabVersion}" is not semver`)
  const qids = new Set<string>()
  for (const q of v.questions) {
    if (qids.has(q.id)) out.push(`question ${q.id} repeated`)
    qids.add(q.id)
    if (q.type === 'choice' && !(q.options && q.options.length >= 2)) out.push(`question ${q.id} needs options`)
    if (q.type === 'number' && !(typeof q.min === 'number' && typeof q.max === 'number' && q.min < q.max)) out.push(`question ${q.id} needs min < max`)
  }
  const codes = new Set<string>()
  const muscles = new Set(v.exerciseMuscles)
  const covered = new Set<string>()
  for (const r of v.regions) {
    if (!CODE.test(r.code)) out.push(`region code "${r.code}" is malformed`)
    if (codes.has(r.code)) out.push(`code ${r.code} repeated`)
    codes.add(r.code)
    if (!GROUPS.has(r.group)) out.push(`region ${r.code} has unknown group ${r.group}`)
    if (!TISSUES.has(r.tissue)) out.push(`region ${r.code} has unknown tissue ${r.tissue}`)
    for (const m of r.muscles) {
      if (!muscles.has(m)) out.push(`region ${r.code} names unknown muscle "${m}"`)
      covered.add(m)
    }
    for (const f of r.followups) if (!qids.has(f)) out.push(`region ${r.code} names unknown question ${f}`)
  }
  for (const m of v.exerciseMuscles) if (!covered.has(m)) out.push(`muscle "${m}" loads no region`)
  for (const s of v.symptoms) {
    if (!SYM_CODE.test(s.code)) out.push(`symptom code "${s.code}" is malformed`)
    if (codes.has(s.code)) out.push(`code ${s.code} repeated`)
    codes.add(s.code)
    if (!SCOPES.has(s.scope)) out.push(`symptom ${s.code} has unknown scope ${s.scope}`)
    for (const f of s.followups) if (!qids.has(f)) out.push(`symptom ${s.code} names unknown question ${f}`)
  }
  return out
}

export function regionOf(v: HealthVocab, code: string): VocabRegion | undefined {
  return v.regions.find((r) => r.code === code)
}

/** The rows of public.health_vocab, in the order the migration seeds them. */
export function vocabRows(v: HealthVocab): { code: string; kind: 'region' | 'symptom'; label: string; parent: string | null; sided: boolean }[] {
  return [
    ...v.regions.map((r) => ({ code: r.code, kind: 'region' as const, label: r.label, parent: r.group, sided: r.sided })),
    ...v.symptoms.map((s) => ({ code: s.code, kind: 'symptom' as const, label: s.label, parent: s.group ?? null, sided: false })),
  ]
}

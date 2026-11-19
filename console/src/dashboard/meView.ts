// The Me tabs as the phone worked them out (me_views; android domain/meview/MeView.kt, view.v = 1). Every value arrives
// formatted; the web only draws it. A hue names a design token ("m-weight" → var(--m-weight)).
import { supabase } from '../lib/supabase'

export type Tone = 'ok' | 'warn' | 'bad'
export type Point = [string, number]
export interface MeTile { kind: 'tile'; label: string; value: string; unit?: string; sub?: string; tone?: Tone; hue?: string; series?: Point[]; info?: string }
export interface MeRow { kind: 'row'; label: string; value?: string; sub?: string; tone?: Tone; state?: string; region?: string; side?: string; hue?: string }
export interface MeChart { kind: 'chart'; label: string; unit?: string; decimals: number; series: { name: string; hue: string; points: Point[] }[]; caption?: string; format?: 'pace' }
export interface MeBars { kind: 'bars'; label: string; unit: string; weeks: string[]; series: { name: string; hue: string; values: number[] }[]; last_partial?: boolean }
export interface MeRing { kind: 'ring'; label: string; fraction: number; value: string; lines: string[]; hue: string }
export interface MeNote { kind: 'note'; text: string; muted: boolean }
export interface MeRadar { kind: 'radar'; label: string; lines: number[]; axes: { short: string; label: string; score?: number; state: string }[] }
export interface MeRibbons { kind: 'ribbons'; label: string; slots: number; caption?: string; days: { date: string; spans: [number, number, string][] }[]; legend: { label: string; hue: string; value?: string }[] }
export type MeItem = MeTile | MeRow | MeChart | MeBars | MeRing | MeNote | MeRadar | MeRibbons
export interface MeSection { title: string; info?: string; items: MeItem[] }
export interface MeViewDoc { v: number; tab: string; range?: string; sections: MeSection[] }
export interface MeViewRow { view: MeViewDoc; builtAt: string }

/** The phone's tabs, in the phone's order (MeScreen.kt). */
export const ME_TABS = ['HEALTHSPAN', 'PERFORMANCE', 'BODY', 'HEALTH', 'BLOODWORK', 'ROUTINE', 'EXPOSURES'] as const
export type MeTab = typeof ME_TABS[number]

export async function loadMeView(tab: MeTab): Promise<MeViewRow | null> {
  const r = await supabase.from('me_views').select('view,built_at').eq('tab', tab).maybeSingle()
  if (r.error) throw new Error(`me_views: ${r.error.message}`)
  if (!r.data) return null
  const d = r.data as { view: MeViewDoc; built_at: string }
  return d.view?.v === 1 ? { view: d.view, builtAt: d.built_at } : null
}

export const hueVar = (hue?: string): string => (hue ? `var(--${hue})` : 'var(--ft-fg)')

/** The 3D body's regions: each gathers the health vocabulary's region codes it covers (android domain/health/BodyMapRegions.kt). */
export const BODY_REGIONS: { key: string; label: string; codes: string[] }[] = [
  { key: 'head', label: 'HEAD AND NECK', codes: ['head', 'neck'] },
  { key: 'upper', label: 'CHEST, SHOULDERS, UPPER BACK', codes: ['chest', 'shoulder', 'upper_back'] },
  { key: 'arms', label: 'ARMS AND HANDS', codes: ['upper_arm', 'elbow', 'forearm', 'wrist', 'hand'] },
  { key: 'core', label: 'ABDOMEN AND LOWER BACK', codes: ['abdomen', 'lower_back'] },
  { key: 'hips', label: 'HIPS AND GLUTES', codes: ['hip', 'groin', 'glute'] },
  { key: 'legs', label: 'LEGS', codes: ['quad', 'lateral_thigh', 'hamstring', 'knee', 'shin', 'calf'] },
  { key: 'feet', label: 'ANKLES AND FEET', codes: ['ankle', 'achilles', 'heel', 'foot_top', 'toes', 'plantar_foot'] },
]
export const regionOfCode = (code: string): string | null => BODY_REGIONS.find((r) => r.codes.includes(code))?.key ?? null

export const H_MAX = 12.8365 // the mesh's height in its own units (feet at 0)

/** Which region of the 3D body (Body3D.tsx) a point of the mesh is in, from its normalised height and distance from the midline. */
export function regionAt(x: number, y: number): string {
  const nx = y / H_MAX
  const ax = Math.abs(x)
  // The arms leave the torso at the shoulder and reach the hands by mid-thigh; the torso's half-width by height band.
  const armFrom = nx > 0.7 ? 1.6 : nx > 0.65 ? 1.5 : nx > 0.6 ? 1.3 : nx > 0.5 ? 1.4 : 1.6
  if (nx > 0.44 && nx <= 0.85 && ax > armFrom) return 'arms'
  if (nx > 0.85) return 'head'
  if (nx > 0.66) return 'upper'
  if (nx > 0.53) return 'core'
  if (nx > 0.44) return 'hips'
  if (nx > 0.06) return 'legs'
  return 'feet'
}

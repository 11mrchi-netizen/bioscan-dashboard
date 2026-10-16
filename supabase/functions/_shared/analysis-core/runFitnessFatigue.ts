// I/O for the personal fitness-fatigue model (analysis review phase G, fitnessFatigue.ts): takes the daily frame the
// relationship engine already loaded and publishes one analysis_results row (kind 'fitness_fatigue', key 'hrv'). The apps
// draw training load with the personal spans only while the row says they were adopted. Duck-typed client, tested in Node,
// run unchanged in analysis-run.
import type { Admin, LoadedFrame } from './runRelationships.ts'
import { analyseFitnessFatigue, FITNESS_FATIGUE } from './fitnessFatigue.ts'
import { fnv1a } from './hash.ts'
import { CORE_VERSION } from './version.ts'

export const FITNESS_FATIGUE_KIND = 'fitness_fatigue'

export interface FitnessFatigueRun {
  userId: string
  error?: string
  state?: string
  adopted?: boolean
}

export async function runFitnessFatigue(admin: Admin, loaded: LoadedFrame, now: Date = new Date()): Promise<FitnessFatigueRun> {
  const { userId, frame, today } = loaded
  const hrv = frame.series.hrv ?? frame.dates.map(() => null)
  const load = frame.series.training_load ?? frame.dates.map(() => null)
  const result = analyseFitnessFatigue(frame.dates, hrv, load)
  const { error } = await admin.from('analysis_results').upsert(
    {
      user_id: userId,
      kind: FITNESS_FATIGUE_KIND,
      key: 'hrv',
      as_of: today,
      engine_version: CORE_VERSION,
      inputs_hash: fnv1a(JSON.stringify([frame.dates[0], hrv, load])),
      state: result.state,
      confidence_have: result.heldOutDays,
      confidence_need: FITNESS_FATIGUE.minHeldOut,
      payload: result,
      calculated_at: now.toISOString(),
    },
    { onConflict: 'user_id,kind,key,as_of,engine_version' },
  )
  if (error) return { userId, error: error.message }
  await admin.from('analysis_results').delete().eq('user_id', userId).eq('kind', FITNESS_FATIGUE_KIND).eq('key', 'hrv').eq('engine_version', CORE_VERSION).lt('as_of', today)
  return { userId, state: result.state, adopted: result.adopted }
}

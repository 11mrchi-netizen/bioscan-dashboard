// The nightly run is split into groups of engines, each served by its own request so each gets its own CPU budget
// (one invocation running every engine for every user was killed with HTTP 546 "CPU Time exceeded" after ~10-12 s, which
// the health engine, last in order, never survived). Group `all` is the old single-invocation behaviour and stays the
// default, so the console's refresh buttons and manual calls keep working. Erasable TypeScript, no I/O.
//
// Order matters across groups: `bands` restarts its baselines at confirmed series breaks, which `core` writes, so the
// cron jobs run the groups a few minutes apart in this order (20261114_analysis_run_groups.sql).

export const ENGINE_GROUPS = {
  core: ['breaks', 'integrity', 'relationships'],
  bands: ['typicality', 'expectedBands', 'evidence'],
  interventions: ['experiments', 'interventionResponses', 'interventionIts', 'eventEvidence'],
  fitness: ['fitnessFatigue'],
  health: ['health'],
} as const

export type Group = keyof typeof ENGINE_GROUPS
export type GroupOrAll = Group | 'all'

export const GROUP_ORDER = Object.keys(ENGINE_GROUPS) as Group[]
/** Engines that read the shared daily frame (everything except the two that read their own rows). */
export const OWN_READ_ENGINES: readonly string[] = ['breaks', 'integrity']

/** A request's group, or null when it names something that does not exist. A missing group means `all`. */
export function parseGroup(value: unknown): GroupOrAll | null {
  if (value === undefined || value === null || value === 'all') return 'all'
  return typeof value === 'string' && (GROUP_ORDER as string[]).includes(value) ? (value as Group) : null
}

export function enginesFor(group: GroupOrAll): string[] {
  return group === 'all' ? GROUP_ORDER.flatMap((g) => [...ENGINE_GROUPS[g]]) : [...ENGINE_GROUPS[group]]
}

/** Whether a group needs the shared daily frame (so `loadFrame` is skipped for a group that never uses it). */
export function needsFrame(group: GroupOrAll): boolean {
  return enginesFor(group).some((e) => !OWN_READ_ENGINES.includes(e))
}

/** Runs one engine, recording how long it took; a thrown error becomes the engine's own error result. */
export async function timedEngine<T>(
  userId: string,
  name: string,
  timings: Record<string, number>,
  fn: () => Promise<T>,
  now: () => number = () => performance.now(),
): Promise<T | { userId: string; error: string }> {
  const start = now()
  try {
    return await fn()
  } catch (e) {
    return { userId, error: String(e) }
  } finally {
    timings[name] = Math.round(now() - start)
  }
}

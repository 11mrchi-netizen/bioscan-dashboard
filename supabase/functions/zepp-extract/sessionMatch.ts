// Which stored session a Zepp workout is, and how a Zepp-created duplicate folds into it. Pure functions, no I/O, so the
// same file runs in the edge function and under `node --test`.
//
// The match used to be "a session starting within 2 minutes". Health Connect's copy of a workout often starts several
// minutes earlier (strength: the whole gym visit; runs: the start pressed before the watch's), so the Zepp history pull
// found no match for many older workouts and inserted a second session: both then counted in records, weekly volume
// and load. Now, failing the 2-minute rule, a session of the same type whose time span overlaps the workout's by at least
// half of the longer of the two is the same workout. Half of the LONGER keeps a 6-minute fragment inside a 70-minute
// run, or a 3-hour ride that contains a 70-minute one, from counting as the same session. Only sessions whose times a
// device recorded take part in the overlap rule.

import { exercisesSource } from "./strengthImport.ts";

export const START_TOLERANCE_MS = 2 * 60 * 1000;
export const MIN_OVERLAP_OF_LONGER = 0.5;

export interface Span { startMs: number; endMs: number; }

export interface SessionCandidate extends Span {
  id: number;
  type: string | null;
  /** The Zepp track this session is already linked to, if any. */
  linkedTrackId: string | null;
  /**
   * Whether its times were recorded by a device (Health Connect), so an overlap means something. Imported logs carry
   * placeholder times (a Notion workout "at 12:00"); they match only by the 2-minute rule. Default true.
   */
  deviceTimed?: boolean;
}

export type MatchKind = "start" | "overlap";

/** A stored row's span: end_time, else start + duration_min; null without a usable start. */
export function spanOf(row: Record<string, unknown>): Span | null {
  const startMs = typeof row.start_time === "string" ? Date.parse(row.start_time) : NaN;
  if (!Number.isFinite(startMs)) return null;
  const endFromCol = typeof row.end_time === "string" ? Date.parse(row.end_time) : NaN;
  const minutes = typeof row.duration_min === "number" ? row.duration_min : Number(row.duration_min);
  const endMs = Number.isFinite(endFromCol) && endFromCol > startMs
    ? endFromCol
    : Number.isFinite(minutes) && minutes > 0 ? startMs + minutes * 60000 : startMs;
  return { startMs, endMs };
}

export function overlapMs(a: Span, b: Span): number {
  return Math.max(0, Math.min(a.endMs, b.endMs) - Math.max(a.startMs, b.startMs));
}

/**
 * The session a Zepp workout is: one starting within 2 minutes (any type, as before), else one of the same type
 * overlapping it by at least half of the longer span; the largest overlap, then the closest start. A session already
 * linked to another Zepp track is never taken.
 */
export function pickSessionMatch(
  zepp: Span & { type: string | null },
  candidates: SessionCandidate[],
  trackId: string | null,
): { id: number; kind: MatchKind } | null {
  const free = candidates.filter((c) => c.linkedTrackId === null || c.linkedTrackId === trackId);
  const startGap = (c: Span) => Math.abs(c.startMs - zepp.startMs);
  const byStart = free.filter((c) => startGap(c) <= START_TOLERANCE_MS).sort((a, b) => startGap(a) - startGap(b));
  if (byStart.length > 0) return { id: byStart[0].id, kind: "start" };
  const zeppLength = zepp.endMs - zepp.startMs;
  const byOverlap = free
    .filter((c) => c.deviceTimed !== false && c.type !== null && c.type === zepp.type)
    .map((c) => ({ c, overlap: overlapMs(zepp, c) }))
    .filter(({ c, overlap }) => overlap > 0 && overlap >= MIN_OVERLAP_OF_LONGER * Math.max(zeppLength, c.endMs - c.startMs))
    .sort((a, b) => b.overlap - a.overlap || startGap(a.c) - startGap(b.c));
  return byOverlap.length > 0 ? { id: byOverlap[0].c.id, kind: "overlap" } : null;
}

type Row = Record<string, unknown>;

/** Columns Zepp measured that the kept session takes when the Zepp copy has them ("Zepp wins when present"). */
export const ZEPP_WINS_COLUMNS = [
  "distance_km", "avg_hr", "max_hr", "calories_active", "elevation_gain_m", "avg_speed_kmh",
  "start_time", "end_time", "duration_min",
] as const;

const present = (v: unknown) => v !== null && v !== undefined && v !== "";

/**
 * The update for the kept session when a Zepp-created copy folds into it: Zepp's measurements and timing where the
 * copy has them; the copy's RPE and notes only where the kept session has none; its exercise list only when the kept
 * session has none (an exercise list on the kept session, whoever wrote it, stays).
 */
export function mergedSessionFields(zeppCopy: Row, kept: Row): Row {
  const update: Row = {};
  for (const col of ZEPP_WINS_COLUMNS) if (present(zeppCopy[col])) update[col] = zeppCopy[col];
  for (const col of ["rpe", "notes"]) if (present(zeppCopy[col]) && !present(kept[col])) update[col] = zeppCopy[col];
  const keptDetails = (kept.details ?? null) as Row | null;
  const copyDetails = (zeppCopy.details ?? null) as Row | null;
  if (exercisesSource(keptDetails) === "none" && exercisesSource(copyDetails) !== "none" && copyDetails) {
    const taken: Row = { ...(keptDetails ?? {}), exercises: copyDetails.exercises };
    if (present(copyDetails.exercises_source)) taken.exercises_source = copyDetails.exercises_source;
    if (present(copyDetails.zepp_fingerprint)) taken.zepp_fingerprint = copyDetails.zepp_fingerprint;
    update.details = taken;
  }
  return update;
}

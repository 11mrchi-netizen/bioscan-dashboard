// What one sync has to touch (performance audit, 25 Nov 2026). Pure functions, no imports, so the same file runs in the
// edge function and under `node --test`.
//
// Before this, every extraction (each app-open sync, each hourly recent pull, each history-pull window) re-ran the
// orphan merge over every Zepp-only session ever stored, one candidate query each, and fetched and rewrote every
// workout's detail on the synced days again, though a workout does not change once Zepp has it.

const DAY_MS = 86_400_000;

/** Orphans are looked at within the synced days, widened by this much each side (local days against UTC starts). */
export const ORPHAN_MARGIN_DAYS = 2;

/**
 * A stored workout detail is re-fetched until it is this long past the workout's end: Zepp can finish an upload, and
 * Health Connect can write its copy of the session, some time after the workout.
 */
export const DETAIL_SETTLE_MS = 6 * 60 * 60 * 1000;

/** The UTC instants [from, to] whose Zepp-only sessions this sync's orphan merge checks; null for no dates. */
export function orphanWindow(dates: string[]): { fromIso: string; toIso: string } | null {
  const sorted = dates.filter((d) => /^\d{4}-\d{2}-\d{2}$/.test(d)).sort();
  if (sorted.length === 0) return null;
  const from = Date.parse(`${sorted[0]}T00:00:00Z`) - ORPHAN_MARGIN_DAYS * DAY_MS;
  const to = Date.parse(`${sorted.at(-1)}T00:00:00Z`) + (1 + ORPHAN_MARGIN_DAYS) * DAY_MS;
  return { fromIso: new Date(from).toISOString(), toIso: new Date(to).toISOString() };
}

export interface StoredDetail {
  zepp_track_id: string | number;
  exercise_session_id: number | null;
  end_time: string | null;
  fetched_at: string | null;
  decoder_version: string | number | null;
}

/**
 * Whether a stored detail needs no new fetch: linked to a session, decoded by the current decoder, and fetched long
 * enough after the workout ended. Anything else is fetched and reconciled as before.
 */
export function isSettled(row: StoredDetail, decoderVersion: number): boolean {
  if (row.exercise_session_id == null) return false;
  if (row.decoder_version == null || Number(row.decoder_version) !== decoderVersion) return false;
  const end = row.end_time ? Date.parse(row.end_time) : NaN;
  const fetched = row.fetched_at ? Date.parse(row.fetched_at) : NaN;
  if (!Number.isFinite(end) || !Number.isFinite(fetched)) return false;
  return fetched - end >= DETAIL_SETTLE_MS;
}

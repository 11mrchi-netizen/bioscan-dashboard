// The hourly server pull of the last few days (supabase/migrations/20261125060126_zepp_recent_pull.sql): the Zepp app
// uploads to the Zepp cloud in the background, and this keeps Supabase in step without anyone opening Bioscan. It reads
// the same days and metrics as the app's on-open sync (ZeppRepository.sync), so both paths write the same rows through
// the same idempotent upserts. Pure functions, no imports, so the same file runs in the edge function and under
// `node --test`.

/** Days before today the pull also re-reads: Zepp fills in last night and late uploads after the fact. */
export const RECENT_DAYS_BACK = 3;
/** The same metrics as the app's on-open sync. */
export const RECENT_METRICS = ["sport_history", "stress", "band_data"];

const DAY_MS = 86_400_000;
const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10);

/**
 * The person's local days to pull, oldest first: today on their own calendar and RECENT_DAYS_BACK days before it.
 * `utcOffsetMin` is the latest daily_context.utc_offset_min. Without one, the range runs from RECENT_DAYS_BACK days
 * before the UTC date to the day after it, which holds the local today in every time zone.
 */
export function recentDates(now: Date, utcOffsetMin: number | null): string[] {
  const known = utcOffsetMin !== null && Number.isFinite(utcOffsetMin);
  const today = now.getTime() + (known ? utcOffsetMin * 60_000 : 0);
  const extra = known ? 0 : 1;
  return Array.from({ length: RECENT_DAYS_BACK + 1 + extra }, (_, i) => isoDay(today + (i - RECENT_DAYS_BACK) * DAY_MS));
}

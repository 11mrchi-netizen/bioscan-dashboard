// The whole Zepp history, pulled on the server (supabase/migrations/20261020_zepp_history_pull.sql): stored Zepp data
// started on 26 Aug 2026, and Health Connect's copy of the older runs is about twice their real distance. A pg_cron job
// calls zepp-extract every minute while a pull is pending; each call works back from where the last one stopped, a week
// at a time, for as long as its time budget allows, and stops for good where the account's history starts:
// HISTORY_STOP_AFTER_EMPTY weeks in a row with no workout and no day of band data. Pure functions, no imports, so the
// same file runs in the edge function and under `node --test`.

export const HISTORY_WINDOW_DAYS = 7;
export const HISTORY_STOP_AFTER_EMPTY = 8;
/** Never look further back than this. */
export const HISTORY_FLOOR = "2015-01-01";
/** The metrics pulled for every past day. */
export const HISTORY_METRICS = ["sport_history", "band_data", "stress"];

/** The columns of public.zepp_history_pull the pull moves forward. */
export interface HistoryState {
  next_end: string;
  empty_streak: number;
  workouts: number;
  band_days: number;
  weeks_done: number;
  oldest_with_data: string | null;
  done: boolean;
}

const DAY_MS = 86_400_000;
const toDate = (iso: string) => new Date(`${iso}T00:00:00Z`);
const toIso = (d: Date) => d.toISOString().slice(0, 10);
const addDays = (iso: string, n: number) => toIso(new Date(toDate(iso).getTime() + n * DAY_MS));

/** The next week to pull, oldest day first: the HISTORY_WINDOW_DAYS days ending on next_end. */
export function windowDates(state: Pick<HistoryState, "next_end">): string[] {
  return Array.from({ length: HISTORY_WINDOW_DAYS }, (_, i) => addDays(state.next_end, i - (HISTORY_WINDOW_DAYS - 1)));
}

/** The state after a week came back with what it held. */
export function afterWindow(state: HistoryState, found: { workouts: number; bandDays: number }): HistoryState {
  const from = windowDates(state)[0];
  const empty = found.workouts === 0 && found.bandDays === 0;
  const emptyStreak = empty ? state.empty_streak + 1 : 0;
  const nextEnd = addDays(from, -1);
  return {
    next_end: nextEnd,
    empty_streak: emptyStreak,
    workouts: state.workouts + found.workouts,
    band_days: state.band_days + found.bandDays,
    weeks_done: state.weeks_done + 1,
    oldest_with_data: empty ? state.oldest_with_data : from,
    done: emptyStreak >= HISTORY_STOP_AFTER_EMPTY || nextEnd < HISTORY_FLOOR,
  };
}

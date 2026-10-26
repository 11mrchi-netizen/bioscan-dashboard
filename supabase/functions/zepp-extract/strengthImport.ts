// Strength workouts from Zepp: the per-set laps of a workout's detail become exercise_sessions.details.exercises, and
// this decides whether a re-sync may replace the exercises already stored. Pure functions, no imports, so the same file
// runs in the edge function and under `node --test`.
//
// The problem it solves: the first sync after a workout imports Zepp's exercises; if they were wrong (exercises not
// picked on the watch) and corrected later in the Zepp app, every later sync fetched the corrected workout but never
// wrote it, because "exercises already present" could not tell Zepp's own earlier import from the person's edits in this
// app. Now the stored list says where it came from:
//   - details.exercises_source: "zepp" (written by this import) or "app" (changed in the app's edit form);
//   - details.zepp_fingerprint: the fingerprint of the Zepp version the stored list is based on.
// A list Zepp wrote is replaced whenever Zepp's version changes. A list the person changed is never touched here; the
// app shows "Zepp has a different version" when Zepp's fingerprint moves on from the one the edit was based on, and the
// person chooses. Rows from before these markers: a list Zepp wrote carries an exercise_code on every exercise, which the
// app's edit form used to drop, so that is how an old Zepp list is recognised.

export interface LapSet {
  idx: number;
  durationSec: number;
  avgHr: number;
  detectedReps: number;
  weightKg: number;
  targetReps: number;
  status: number;
  exerciseCode: number;
}

// Parse the semicolon-separated lap string into per-set objects.
// Index map (confirmed from live data 2026-10-08): [0]=idx, [1]=duration_sec,
// [4]=avg_hr, [15]=IMU_detected_reps, [21]=weight_kg, [22]=target_reps,
// [26]=status (-1 or 0=done, 2=planned/skipped), [28]=exercise_code.
export function parseLapSets(lapStr: unknown): LapSet[] {
  if (typeof lapStr !== "string" || !lapStr) return [];
  return lapStr.split(";").filter(Boolean).map((f) => {
    const c = f.split(",");
    return {
      idx: +c[0], durationSec: +c[1], avgHr: +c[4],
      detectedReps: +c[15], weightKg: +c[21], targetReps: +c[22],
      status: +c[26], exerciseCode: +c[28],
    };
  });
}

/** Done sets carry status -1 (older exports) or 0 (current firmware); 2 is a planned set that was skipped. */
export function doneSets(laps: LapSet[]): LapSet[] {
  return laps.filter((s) => s.status !== 2 && s.durationSec > 0);
}

export interface ZeppSet {
  reps: number;
  weight_kg: number;
  duration_sec: number;
  avg_hr: number | null;
  detected_reps: number | null;
}

export interface ZeppExercise {
  name: string;
  exercise_code: number;
  sets: ZeppSet[];
}

const finite = (v: number): number => (Number.isFinite(v) ? v : 0);

/** Consecutive sets of the same exercise code become one exercise, in workout order. */
export function buildZeppExercises(laps: LapSet[], nameOf: (code: number) => string | undefined): ZeppExercise[] {
  const out: ZeppExercise[] = [];
  for (const lap of laps) {
    const set: ZeppSet = {
      reps: Math.round(finite(lap.targetReps)),
      weight_kg: finite(lap.weightKg),
      duration_sec: Math.round(finite(lap.durationSec)),
      avg_hr: lap.avgHr > 0 ? lap.avgHr : null,
      detected_reps: lap.detectedReps > 0 ? lap.detectedReps : null,
    };
    const last = out.at(-1);
    if (last && last.exercise_code === lap.exerciseCode) last.sets.push(set);
    else out.push({ name: nameOf(lap.exerciseCode) ?? `Exercise ${lap.exerciseCode}`, exercise_code: lap.exerciseCode, sets: [set] });
  }
  return out;
}

/**
 * A short fingerprint of what a person can correct in Zepp: the exercises in order with each set's reps and weight.
 * Measured values (set duration, heart rate) are left out so a re-fetch of an unchanged workout keeps its fingerprint.
 */
export function exercisesFingerprint(exercises: ZeppExercise[]): string {
  const canonical = exercises
    .map((e) => `${e.exercise_code}:` + e.sets.map((s) => `${s.reps}x${s.weight_kg}`).join(","))
    .join("|");
  // FNV-1a, 32 bit.
  let h = 0x811c9dc5;
  for (let i = 0; i < canonical.length; i++) {
    h ^= canonical.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return `z1-${h.toString(16).padStart(8, "0")}-${exercises.length}`;
}

export type ExercisesSource = "zepp" | "app" | "none";

type Details = Record<string, unknown>;

/** Where the stored exercise list came from. */
export function exercisesSource(details: Details | null | undefined): ExercisesSource {
  const exercises = details?.exercises;
  if (!Array.isArray(exercises) || exercises.length === 0) return "none";
  const marked = details?.exercises_source;
  if (marked === "zepp" || marked === "app") return marked;
  // Before the markers: Zepp's import put a code on every exercise; the app's edit form dropped them.
  const allCoded = exercises.every((e) => e && typeof e === "object" && typeof (e as Record<string, unknown>).exercise_code === "number");
  return allCoded ? "zepp" : "app";
}

export type StrengthAction = "written" | "unchanged" | "kept_app_edit" | "no_sets";

/**
 * What to store as exercise_sessions.details after a sync fetched this Zepp version: the new details object (other keys
 * kept) or null to leave the row as it is, and why.
 */
export function planStrengthDetails(existing: Details | null | undefined, zepp: ZeppExercise[]): { details: Details | null; action: StrengthAction } {
  if (zepp.length === 0) return { details: null, action: "no_sets" };
  const fingerprint = exercisesFingerprint(zepp);
  const source = exercisesSource(existing);
  if (source === "app") return { details: null, action: "kept_app_edit" };
  if (source === "zepp" && existing?.zepp_fingerprint === fingerprint) return { details: null, action: "unchanged" };
  return {
    details: { ...(existing ?? {}), exercises: zepp, exercises_source: "zepp", zepp_fingerprint: fingerprint },
    action: "written",
  };
}

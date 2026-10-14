// Builds Zepp strength training templates (trainingTypeId 10) from a plan.
//
// Format decoded from templates shared out of the Zepp app and checked by importing a generated one
// (docs/zepp-integration/05-training-templates.md): one NODE per set, intervalUnit 10 = reps,
// weight = tenths of a kg + "-1", CIRCLE = repeat group (sets), manual rest = type 2 / unit 8 / -1,
// warm-up set = intervalType 0. Exercise codes and muscle regions come from Gadgetbridge's
// zeppos.json (actionType, actionName, mainPositions, subPositions).

export interface Exercise {
  code: number;
  name: string;
  main: number[];
  sub: number[];
  bodyweight?: boolean; // loaded with added weight (selfWeightType 1)
}

export const EXERCISES: Record<string, Exercise> = {
  standing_barbell_press: { code: 1761, name: "Standing Barbell Press", main: [3, 4, 38, 39], sub: [] },
  barbell_front_squat: { code: 1456, name: "Barbell Front Squat", main: [14, 15], sub: [26, 27] },
  pull_up: { code: 64, name: "Pull Up", main: [19, 20], sub: [10, 11, 34, 35], bodyweight: true },
};

// The app's movement key (domain/training/MovementKey.kt: lower-case words, filler dropped, sorted) to a
// Zepp exercise. Codes from Gadgetbridge's zeppos.json; add a line when a program uses a lift not listed.
const bar = (code: number, name: string, main: number[], sub: number[]): Exercise => ({ code, name, main, sub });
const body = (code: number, name: string, main: number[], sub: number[]): Exercise => ({ code, name, main, sub, bodyweight: true });
const PRESS = EXERCISES.standing_barbell_press, PULL_UP = EXERCISES.pull_up;
export const BY_MOVEMENT: Record<string, Exercise> = {
  "press": PRESS,
  "overhead press": PRESS,
  "front squat": EXERCISES.barbell_front_squat,
  "squat": bar(1001, "Squat", [14, 15], [26, 27]),
  "deadlift": bar(70, "Barbell Deadlift", [28, 29], [26, 27, 36, 37]),
  "deadlift romanian": bar(1310, "Barbell Romanian Deadlift", [26, 27], [26, 27, 28, 29]),
  "bench press": bar(6, "Bench Press", [5, 6], [3, 4, 22, 23]),
  "bench incline press": bar(110, "Incline Barbell Bench Press", [5, 6], [22, 23]),
  "row": bar(5, "Bent Over Row", [19, 20], [10, 11]),
  "pull up": PULL_UP,
  "pull up weighted": PULL_UP,
  "dip": body(1988, "Dip", [5, 6], [22, 23]),
  "dip weighted": body(1988, "Dip", [5, 6], [22, 23]),
};

type Node = { type: "NODE"; trainingInterval: Record<string, unknown> };
type Child = Node | { type: "CIRCLE"; circleTimes: number; children: Node[] };

function weightFields(kg: number): Record<string, string> {
  const tenths = Math.round(kg * 10);
  if (tenths <= 0) return { strengthWeight: "0-1" };
  return { strengthWeight: `${tenths}-1`, strengthWeightValue: String(kg), strengthWeightUnit: "kg" };
}

function setNode(e: Exercise, reps: number, kg: number, warmup: boolean, warmupAsTraining = false): Node {
  return {
    type: "NODE",
    trainingInterval: {
      intervalType: warmup && !warmupAsTraining ? "0" : "1",
      intervalUnit: "10",
      intervalUnitValue: String(reps),
      alertRule: "0",
      alertRuleDetail: "0-0",
      lengthUnit: 0,
      ...weightFields(kg),
      intervalTypeI18nKey: warmup && !warmupAsTraining ? "gapType_warmup" : "gapType_training",
      intervalUnitI18nKey: "gap_pickUnit1",
      lengthUnitI18nKey: "gap_metric",
      actionType: e.code,
      actionName: e.name,
      selfWeightType: e.bodyweight ? 1 : 0,
      mainPositions: e.main,
      subPositions: e.sub,
    },
  };
}

// Manual rest (ends when Skip is tapped), or a timed rest of `restSec` seconds (intervalUnit 1 = time).
function restNode(restSec?: number): Node {
  return {
    type: "NODE",
    trainingInterval: {
      intervalType: "2",
      intervalUnit: restSec ? "1" : "8",
      intervalUnitValue: restSec ? String(restSec) : "-1",
      alertRule: "0",
      alertRuleDetail: "0-0",
      lengthUnit: 0,
      intervalTypeI18nKey: "gapType_rest",
      lengthUnitI18nKey: "gap_metric",
    },
  };
}

export interface BlockOptions {
  flat?: boolean;              // write every work set out instead of one repeat group
  restSec?: number;            // timed rests instead of manual ones
  warmupAsTraining?: boolean;  // warm-up sets typed as normal training sets
}

// Warm-up sets (each followed by a rest), then `sets` work sets as a repeat group (or written out when flat).
export function liftBlock(e: Exercise, sets: number, reps: number, kg: number, warmups: Array<[number, number]> = [], o: BlockOptions = {}): Child[] {
  const out: Child[] = [];
  for (const [r, w] of warmups) out.push(setNode(e, r, w, true, o.warmupAsTraining), restNode(o.restSec));
  if (o.flat) for (let i = 0; i < sets; i++) out.push(setNode(e, reps, kg, false), restNode(o.restSec));
  else out.push({ type: "CIRCLE", circleTimes: sets, children: [setNode(e, reps, kg, false), restNode(o.restSec)] });
  return out;
}

export function strengthTemplate(title: string, description: string, children: Child[]) {
  return {
    title,
    description,
    modalities: [],
    appName: "com.xiaomi.hm.health",
    status: "AVAILABLE",
    trainingTypeId: 10,
    trainingTypeName: "力量训练",
    trainingIntervals: { type: "PARENT", children },
    difficulty: [],
    target: [],
    sourceType: 0,
    creationSource: 0,
  };
}

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

type Node = { type: "NODE"; trainingInterval: Record<string, unknown> };
type Child = Node | { type: "CIRCLE"; circleTimes: number; children: Node[] };

function weightFields(kg: number): Record<string, string> {
  const tenths = Math.round(kg * 10);
  if (tenths <= 0) return { strengthWeight: "0-1" };
  return { strengthWeight: `${tenths}-1`, strengthWeightValue: String(kg), strengthWeightUnit: "kg" };
}

function setNode(e: Exercise, reps: number, kg: number, warmup: boolean): Node {
  return {
    type: "NODE",
    trainingInterval: {
      intervalType: warmup ? "0" : "1",
      intervalUnit: "10",
      intervalUnitValue: String(reps),
      alertRule: "0",
      alertRuleDetail: "0-0",
      lengthUnit: 0,
      ...weightFields(kg),
      intervalTypeI18nKey: warmup ? "gapType_warmup" : "gapType_training",
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

function restNode(): Node {
  return {
    type: "NODE",
    trainingInterval: {
      intervalType: "2",
      intervalUnit: "8",
      intervalUnitValue: "-1",
      alertRule: "0",
      alertRuleDetail: "0-0",
      lengthUnit: 0,
      intervalTypeI18nKey: "gapType_rest",
      lengthUnitI18nKey: "gap_metric",
    },
  };
}

// Warm-up sets (each followed by a manual rest), then `sets` work sets as a repeat group.
export function liftBlock(e: Exercise, sets: number, reps: number, kg: number, warmups: Array<[number, number]> = []): Child[] {
  const out: Child[] = [];
  for (const [r, w] of warmups) out.push(setNode(e, r, w, true), restNode());
  out.push({ type: "CIRCLE", circleTimes: sets, children: [setNode(e, reps, kg, false), restNode()] });
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

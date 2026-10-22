// A packaged food's nutrition label (Taiwan's 營養標示 or any per-serving / per-100 table), read from a photo or typed
// in, turned into per-100 g values and servings for the catalog, with the checks that catch a misread label.
// Pure: no Deno or network APIs (label.test.ts runs under Node).

export interface LabelValues {
  calories?: number | null;
  protein_g?: number | null;
  fat_g?: number | null;
  saturated_fat_g?: number | null;
  trans_fat_g?: number | null;
  carbs_g?: number | null;
  sugar_g?: number | null;
  fiber_g?: number | null;
  sodium_mg?: number | null;
}

export interface ScannedLabel {
  product_name?: string | null;
  serving_size?: number | null; // 每一份量 X 公克 / 毫升
  serving_unit?: "g" | "ml" | null;
  servings_per_package?: number | null; // 本包裝含 N 份
  per_serving?: LabelValues | null;
  per_100?: LabelValues | null; // 每100公克 / 每100毫升
  energy_unit?: "kcal" | "kJ" | null; // 大卡 = kcal
}

export interface LabelCheck {
  field: string;
  message: string;
}

export interface NormalizedLabel {
  per100: Record<string, number>; // canonical nutrient names (calories, protein, fat, ...), per 100 g or ml
  basisUnit: "g" | "ml";
  serving: { name: string; grams: number } | null;
  wholePackage: { name: string; grams: number } | null;
  checks: LabelCheck[];
}

const CANONICAL: Array<[keyof LabelValues, string]> = [
  ["calories", "calories"],
  ["protein_g", "protein"],
  ["fat_g", "fat"],
  ["saturated_fat_g", "saturated_fat"],
  ["trans_fat_g", "trans_fat"],
  ["carbs_g", "carbs"],
  ["sugar_g", "sugar"],
  ["fiber_g", "fiber"],
  ["sodium_mg", "sodium"],
];

export const LABEL_UNITS: Record<string, string> = {
  calories: "kcal", protein: "g", fat: "g", saturated_fat: "g", trans_fat: "g", carbs: "g", sugar: "g", fiber: "g", sodium: "mg",
};

const KJ_PER_KCAL = 4.184;
const round = (v: number, d = 2) => Math.round(v * 10 ** d) / 10 ** d;
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) && v >= 0 ? v : null);

function canonical(values: LabelValues | null | undefined, energyUnit: string | null | undefined): Record<string, number> {
  const out: Record<string, number> = {};
  if (!values) return out;
  for (const [field, name] of CANONICAL) {
    const v = num(values[field]);
    if (v == null) continue;
    out[name] = name === "calories" && energyUnit === "kJ" ? v / KJ_PER_KCAL : v;
  }
  return out;
}

function fmt(v: number): string {
  return Number.isInteger(v) ? String(v) : String(round(v, 1));
}

export function normalizeLabel(label: ScannedLabel): NormalizedLabel {
  const checks: LabelCheck[] = [];
  const size = num(label.serving_size);
  const unit = label.serving_unit === "ml" ? "ml" : "g";
  const perServing = canonical(label.per_serving, label.energy_unit);
  const per100Printed = canonical(label.per_100, label.energy_unit);

  // Per 100 from the printed per-100 column when it has calories, else scaled from the per-serving column.
  const fromServing: Record<string, number> = {};
  if (size && size > 0) for (const [k, v] of Object.entries(perServing)) fromServing[k] = (v * 100) / size;
  const usePrinted = per100Printed.calories != null;
  const per100Raw = usePrinted ? { ...fromServing, ...per100Printed } : fromServing;
  const per100: Record<string, number> = {};
  for (const [k, v] of Object.entries(per100Raw)) per100[k] = round(v);

  if (Object.keys(per100).length === 0) {
    checks.push({ field: "per_100", message: "No values per 100 g, and no serving size to work them out from." });
  }
  if (per100.calories == null) checks.push({ field: "calories", message: "Calories are missing." });

  // Both columns printed: they must agree (a swapped or misread column shows up here).
  if (usePrinted && Object.keys(fromServing).length > 0) {
    for (const k of ["calories", "protein", "fat", "carbs"]) {
      const a = per100Printed[k];
      const b = fromServing[k];
      if (a == null || b == null) continue;
      if (Math.abs(a - b) > Math.max(0.05 * Math.max(a, b), 0.6)) {
        checks.push({ field: k, message: `Per serving and per 100 don't agree for ${k} (${fmt(round(b, 1))} vs ${fmt(a)} per 100).` });
      }
    }
  }

  // Energy from the macros (4/4/9) should be close to the printed calories.
  const kcal = per100.calories;
  if (kcal != null && (per100.protein != null || per100.carbs != null || per100.fat != null)) {
    const fromMacros = 4 * (per100.protein ?? 0) + 4 * (per100.carbs ?? 0) + 9 * (per100.fat ?? 0);
    if (Math.abs(fromMacros - kcal) > Math.max(0.15 * kcal, 10)) {
      checks.push({ field: "calories", message: `Calories (${fmt(kcal)}/100) don't match the macros (~${fmt(round(fromMacros, 0))}/100).` });
    }
  }
  if (per100.sugar != null && per100.carbs != null && per100.sugar > per100.carbs + 0.1) {
    checks.push({ field: "sugar", message: "Sugar is more than total carbohydrate." });
  }
  if (per100.saturated_fat != null && per100.fat != null && per100.saturated_fat > per100.fat + 0.1) {
    checks.push({ field: "saturated_fat", message: "Saturated fat is more than total fat." });
  }
  if (per100.trans_fat != null && per100.fat != null && per100.trans_fat > per100.fat + 0.1) {
    checks.push({ field: "trans_fat", message: "Trans fat is more than total fat." });
  }
  // Taiwan labels give sodium in mg (毫克); a value this small was probably read in grams.
  if (per100.sodium != null && per100.sodium > 0 && per100.sodium < 1) {
    checks.push({ field: "sodium", message: "Sodium looks like grams; the label lists it in mg." });
  }

  const n = num(label.servings_per_package);
  const serving = size ? { name: `1 serving (${fmt(size)} ${unit})`, grams: size } : null;
  const wholePackage = size && n && n > 1 ? { name: `Whole package (${fmt(round(size * n, 1))} ${unit})`, grams: round(size * n, 1) } : null;

  return { per100, basisUnit: unit, serving, wholePackage, checks };
}

// What the app shows and edits: per serving when the label has a serving size, else per 100.
export function labelPerServing(normalized: NormalizedLabel): Record<string, number> | null {
  if (!normalized.serving) return null;
  const g = normalized.serving.grams;
  return Object.fromEntries(Object.entries(normalized.per100).map(([k, v]) => [k, round((v * g) / 100)]));
}

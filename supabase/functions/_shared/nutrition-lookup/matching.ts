// Pure matching, portion and reconciliation logic for the nutrition lookup (no Deno or network APIs,
// so it runs under Node for matching.test.ts).

export interface CronoNutrient {
  id: number;
  amount: number;
}

export interface CronoMeasure {
  id: number;
  name: string;
  value: number; // grams per unit
  amount: number;
  type: string;
}

export interface CronoFoodDetail {
  id: number;
  name: string;
  source: string;
  measures: CronoMeasure[];
  nutrients: CronoNutrient[];
  defaultMeasureId: number;
}

export interface CronoFoodSearchResult {
  id: number;
  name: string;
  source: string;
  measureId?: number;
  score?: number;
}

export type Quality = "exact" | "good" | "weak" | "none";

// Cronometer nutrient IDs → canonical names matching our food_nutrients table
export const NUTRIENT_MAP: Record<number, { name: string; unit: string }> = {
  // Macros
  208: { name: "calories", unit: "kcal" },
  203: { name: "protein", unit: "g" },
  204: { name: "fat", unit: "g" },
  205: { name: "carbs", unit: "g" },
  291: { name: "fiber", unit: "g" },
  269: { name: "sugar", unit: "g" },
  209: { name: "starch", unit: "g" },
  307: { name: "sodium", unit: "mg" },
  255: { name: "water", unit: "g" },
  221: { name: "alcohol", unit: "g" },
  // Detailed fats
  606: { name: "saturated_fat", unit: "g" },
  645: { name: "monounsaturated_fat", unit: "g" },
  646: { name: "polyunsaturated_fat", unit: "g" },
  605: { name: "trans_fat", unit: "g" },
  601: { name: "cholesterol", unit: "mg" },
  10001: { name: "omega_3", unit: "g" },
  10002: { name: "omega_6", unit: "g" },
  621: { name: "dha", unit: "g" },
  629: { name: "epa", unit: "g" },
  851: { name: "ala", unit: "g" },
  // Vitamins
  318: { name: "vitamin_a", unit: "IU" },
  401: { name: "vitamin_c", unit: "mg" },
  324: { name: "vitamin_d", unit: "IU" },
  323: { name: "vitamin_e", unit: "mg" },
  430: { name: "vitamin_k", unit: "mcg" },
  404: { name: "thiamin_b1", unit: "mg" },
  405: { name: "riboflavin_b2", unit: "mg" },
  406: { name: "niacin_b3", unit: "mg" },
  410: { name: "pantothenic_acid_b5", unit: "mg" },
  415: { name: "vitamin_b6", unit: "mg" },
  417: { name: "folate_b9", unit: "mcg" },
  418: { name: "vitamin_b12", unit: "mcg" },
  421: { name: "choline", unit: "mg" },
  // Minerals
  301: { name: "calcium", unit: "mg" },
  303: { name: "iron", unit: "mg" },
  304: { name: "magnesium", unit: "mg" },
  305: { name: "phosphorus", unit: "mg" },
  306: { name: "potassium", unit: "mg" },
  309: { name: "zinc", unit: "mg" },
  312: { name: "copper", unit: "mg" },
  315: { name: "manganese", unit: "mg" },
  317: { name: "selenium", unit: "mcg" },
  // Amino acids
  501: { name: "tryptophan", unit: "g" },
  502: { name: "threonine", unit: "g" },
  503: { name: "isoleucine", unit: "g" },
  504: { name: "leucine", unit: "g" },
  505: { name: "lysine", unit: "g" },
  506: { name: "methionine", unit: "g" },
  507: { name: "cystine", unit: "g" },
  508: { name: "phenylalanine", unit: "g" },
  509: { name: "tyrosine", unit: "g" },
  510: { name: "valine", unit: "g" },
  511: { name: "arginine", unit: "g" },
  512: { name: "histidine", unit: "g" },
  513: { name: "alanine", unit: "g" },
  514: { name: "aspartic_acid", unit: "g" },
  515: { name: "glutamic_acid", unit: "g" },
  516: { name: "glycine", unit: "g" },
  517: { name: "proline", unit: "g" },
  518: { name: "serine", unit: "g" },
  // Other
  262: { name: "caffeine", unit: "mg" },
};

export function resolveNutrients(food: CronoFoodDetail, quantityG: number): {
  per100g: Record<string, number>;
  forPortion: Record<string, number>;
} {
  const per100g: Record<string, number> = {};
  const forPortion: Record<string, number> = {};
  const scale = quantityG / 100;

  for (const n of food.nutrients) {
    const mapped = NUTRIENT_MAP[n.id];
    if (!mapped || n.amount === 0) continue;
    per100g[mapped.name] = Math.round(n.amount * 1000) / 1000;
    forPortion[mapped.name] = Math.round(n.amount * scale * 100) / 100;
  }

  return { per100g, forPortion };
}

// ---------------------------------------------------------------------------
// Portions: "one banana", "2 large eggs", "150 g rice", "a cup of oats", "waffle x2"
// ---------------------------------------------------------------------------

const COUNT_WORDS: Record<string, number> = {
  a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, half: 0.5,
};
// Weights, and volumes taken as grams (a drink's density is close enough to water's for logging).
export const WEIGHT_UNIT_GRAMS: Record<string, number> = {
  g: 1, gr: 1, gm: 1, gram: 1, kg: 1000, oz: 28.35, ounce: 28.35, lb: 453.6,
  ml: 1, cl: 10, l: 1000, liter: 1000, litre: 1000,
};
const VOLUME_UNITS = new Set(["ml", "cl", "l", "liter", "litre"]);
// Units naming a container: a weight given with them is per container ("2 cans of coke 330ml" is 660 ml).
const CONTAINER_UNITS = new Set(["can", "bottle", "glass", "bowl"]);

export interface Portion {
  count: number;
  unit: string | null; // lower-case, singular; null when the count is of the food itself ("2 eggs")
  size: string | null; // small / medium / large
  rest: string;
}

const PORTION_RE =
  /^(\d+(?:[.,]\d+)?|a|an|one|two|three|four|five|six|seven|eight|nine|ten|half)\s*(g|grs?|gms?|grams?|kg|oz|ounces?|lbs?|ml|cl|l|liters?|litres?|cups?|tbsp|tablespoons?|tsp|teaspoons?|slices?|pieces?|scoops?|servings?|bowls?|glass(?:es)?|bottles?|cans?)?\b\.?\s*(?:of\s+)?(?:(small|medium|large)\s+)?(.+)$/i;

// Singular unit spelling: "grs" -> "gr", "gms" -> "gm", "glasses" -> "glass", "liters" -> "liter".
function unitName(u: string): string {
  const lower = u.toLowerCase();
  if (lower.startsWith("glass")) return "glass";
  if (lower === "grs" || lower === "gms" || lower === "lbs") return lower.slice(0, -1);
  return lower.replace(/s$/, "");
}

// A weight or volume anywhere in the text: "(30g)", "355ml", "35gr each", "1.5 l".
const INLINE_AMOUNT_RE =
  /\(?\s*(\d+(?:[.,]\d+)?)\s*(g|grs?|gms?|grams?|kg|oz|ounces?|lbs?|ml|cl|l|liters?|litres?)\b\.?\s*(each|apiece)?\s*\)?/i;

export function parsePortion(desc: string): Portion | null {
  const text = desc.trim();
  const trailing = text.match(/^(.+?)\s*[x×]\s*(\d+(?:[.,]\d+)?)$/i);
  if (trailing) {
    const count = Number(trailing[2].replace(",", "."));
    const inner = parsePortion(trailing[1]);
    if (Number.isFinite(count) && count > 0) {
      return inner ? { ...inner, count: inner.count * count } : { count, unit: null, size: null, rest: trailing[1].trim() };
    }
  }
  const m = text.match(PORTION_RE);
  if (!m) return null;
  const raw = m[1].toLowerCase();
  const count = COUNT_WORDS[raw] ?? Number(raw.replace(",", "."));
  const rest = m[4].trim();
  if (!Number.isFinite(count) || count <= 0 || !rest) return null;
  const unit = m[2] ? unitName(m[2]) : null;
  return { count, unit, size: m[3]?.toLowerCase() ?? null, rest };
}

export interface Amount {
  query: string; // the food, without the amount
  weightG?: number; // total grams (volumes as ml = g) when the text gives a weight or volume
  portion?: Portion; // otherwise a count ("one apple"; a bare "apple" is one apple)
  liquid: boolean; // the amount was a volume
}

// The amount and the food in a single-food description.
export function parseAmount(desc: string): Amount {
  const text = desc.trim();
  const p = parsePortion(text);
  if (p?.unit && WEIGHT_UNIT_GRAMS[p.unit]) {
    return { query: p.rest, weightG: round1(p.count * WEIGHT_UNIT_GRAMS[p.unit]), liquid: VOLUME_UNITS.has(p.unit) };
  }

  const body = p?.rest ?? text;
  const m = body.match(INLINE_AMOUNT_RE);
  if (m && m.index != null) {
    const unit = unitName(m[2]);
    const value = Number(m[1].replace(",", "."));
    // "2 waffles (35gr each)" and "2 cans of coke 330ml" are per item; "2 slices of bread (60g)" is the total.
    const perItem = m[3] != null || (p?.unit != null && CONTAINER_UNITS.has(p.unit));
    const count = p && perItem ? p.count : 1;
    const query = (body.slice(0, m.index) + " " + body.slice(m.index + m[0].length)).replace(/\(\s*\)/g, " ").replace(/\s+/g, " ").trim();
    if (value > 0 && query) return { query, weightG: round1(count * value * WEIGHT_UNIT_GRAMS[unit]), liquid: VOLUME_UNITS.has(unit) };
  }

  return { query: body, portion: p ?? { count: 1, unit: null, size: null, rest: text }, liquid: false };
}

const round1 = (v: number) => Math.round(v * 10) / 10;

const UNIT_ALIASES: Record<string, string[]> = {
  tbsp: ["tbsp", "tablespoon"], tablespoon: ["tbsp", "tablespoon"], tsp: ["tsp", "teaspoon"], teaspoon: ["tsp", "teaspoon"],
};

const PLAIN_WEIGHT_MEASURE = /^\s*[\d.]*\s*(g|gram|grams|oz|ounce|ounces|ml)\s*$/i;
// Measures that are a volume or a cut, never "one of the food" ("1 cup, sliced" is not one apple).
const VOLUME_OR_CUT = /\b(cups?|tbsp|tsp|tablespoons?|teaspoons?|fl\.?\s?oz|ml|slices?|pints?|quarts?|liters?|litres?|dash|pinch|chopped|diced|sliced)\b/i;
const WHOLE_UNIT = /\b(whole|each|item|fruit|piece|egg|unit|bar|cookie|patty|link|fillet|breast|thigh|drumstick|leg|wing|small|medium|large)\b/i;

// Grams for the portion from the food's own serving sizes. Null when nothing trustworthy is known (caller then uses 100 g).
export function portionGrams(food: CronoFoodDetail, portion: Portion): { grams: number; label: string } | null {
  const usable = food.measures.filter((m) => m.value > 0 && !PLAIN_WEIGHT_MEASURE.test(m.name));
  let measure: CronoMeasure | undefined;
  if (portion.unit && portion.unit !== "serving") {
    const names = UNIT_ALIASES[portion.unit] ?? [portion.unit];
    measure = usable.find((m) => names.some((n) => m.name.toLowerCase().includes(n)));
  } else if (portion.unit === "serving") {
    measure = usable.find((m) => m.id === food.defaultMeasureId) ?? usable[0];
  } else {
    // A count of the food itself: "one apple" is one medium apple, not the default "1 cup, sliced".
    const wholes = usable.filter((m) => !VOLUME_OR_CUT.test(m.name));
    const size = new RegExp(`\\b${portion.size ?? "medium"}\\b`, "i");
    measure = wholes.find((m) => size.test(m.name)) ??
      wholes.find((m) => WHOLE_UNIT.test(m.name)) ??
      wholes.find((m) => m.id === food.defaultMeasureId);
  }
  if (!measure) return null;
  const grams = portion.count * measure.value;
  if (!(grams >= 1 && grams <= 3000)) return null;
  return { grams: Math.round(grams * 10) / 10, label: `${portion.count} x ${measure.name}` };
}

// ---------------------------------------------------------------------------
// Name matching
// ---------------------------------------------------------------------------

// Singular, comparable form of a word: "apples" = "apple", "berries" = "berry", "tomatoes" = "tomato".
export function canon(w: string): string {
  let s = w;
  if (s.length > 3) {
    if (/(oes|ches|shes|sses|xes)$/.test(s)) s = s.slice(0, -2);
    else if (s.endsWith("s") && !/(ss|us)$/.test(s)) s = s.slice(0, -1);
  }
  if (s.length > 2 && s.endsWith("ie")) s = s.slice(0, -2) + "y";
  return s;
}

export function tokens(text: string): string[] {
  return text.toLowerCase().split(/[^\p{L}\p{N}%]+/u).filter(Boolean).map(canon);
}

const set = (words: string[]) => new Set(words.map(canon));

// Never food words, in the query or in a database name.
const STOP = set([
  "with", "without", "and", "or", "in", "on", "of", "the", "a", "an", "from", "for", "to", "as", "by", "w", "wo",
  "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "half", "couple",
  "small", "medium", "large", "extra", "piece", "slice", "serving", "portion",
  "can", "bottle", "pack", "packet", "bag", "container", "carton", "jar", "tin", "sachet", "pouch",
]);

// The unprepared state of a food: asking for "apple" means the raw one.
const BASELINE = set(["raw", "fresh", "whole", "plain", "uncooked", "natural"]);

// Database-name qualifiers that do not change the food ("Apples, raw, with skin", "Chicken, broilers or fryers, meat only").
const NEUTRAL = set([
  "skin", "peel", "peeled", "unpeeled", "flesh", "meat", "only", "edible", "commercial", "commercially", "variety",
  "all", "usda", "commodity", "regular", "prepared", "unprepared", "ns", "nfs", "type", "style", "form", "solid",
  "liquid", "drained", "enriched", "fortified", "organic", "generic", "average", "unsweetened", "unsalted",
  "boneless", "skinless", "broiler", "fryer", "mature", "include", "including", "trimmed", "lean", "fat", "free",
  "sliced", "chopped", "diced", "cut", "pan", "deep", "stir", "dia", "inch", "approx", "red", "green", "yellow",
]);

// Preparation that changes what the food is.
const PREP = set([
  "cooked", "frozen", "dried", "dry", "canned", "grilled", "baked", "fried", "roasted", "steamed", "boiled",
  "poached", "smoked", "braised", "sauteed", "sautéed", "blanched", "seared", "mashed", "salted", "seasoned",
  "flavored", "flavoured", "sweetened", "candied", "stewed", "microwaved", "toasted", "breaded", "battered",
  "dehydrated", "pickled", "cured", "scrambled", "glazed", "caramelized", "broiled", "simmered",
]);

// Foods eaten cooked: a bare "rice" or "chicken breast" means the cooked one, not the dry grain or raw meat.
const COOKED_DEFAULT = set([
  "rice", "pasta", "spaghetti", "macaroni", "penne", "noodle", "quinoa", "lentil", "bean", "chickpea", "barley",
  "couscous", "bulgur", "buckwheat", "millet", "farro", "chicken", "beef", "steak", "pork", "lamb", "turkey", "salmon",
  "cod", "tilapia", "shrimp", "fish", "duck", "veal", "mince",
]);
const COOKING = set(["cooked", "boiled", "steamed", "roasted", "grilled", "baked", "broiled", "braised", "stewed", "poached", "simmered"]);

// A different food built on the queried one: "apple" must not become apple pie, apple juice or baby food.
const DISH = set([
  "pie", "crisp", "crumble", "cobbler", "tart", "strudel", "turnover", "fritter", "muffin", "bread", "cake", "cupcake",
  "cookie", "biscuit", "bar", "juice", "sauce", "jam", "jelly", "butter", "chip", "smoothie", "salad", "soup",
  "sandwich", "pudding", "cream", "ice", "yogurt", "yoghurt", "drink", "beverage", "nectar", "syrup", "pastry",
  "dumpling", "roll", "casserole", "stew", "dessert", "snack", "cereal", "cider", "vinegar", "puree", "spread", "dip",
  "filling", "topping", "pancake", "waffle", "donut", "doughnut", "brownie", "granola", "oil", "flour", "powder",
  "concentrate", "candy", "chocolate", "milkshake", "shake", "latte", "crust", "gravy", "dressing", "wrap", "burrito",
  "taco", "pizza", "nugget", "sausage", "burger", "meatball", "loaf", "babyfood", "baby", "infant", "toddler",
  "applesauce", "strudel", "danish", "croissant", "bagel", "fruitcake", "compote", "chutney", "relish", "sorbet",
]);

// The words a database name must contain. Qualifiers ("red", "skinless") are soft unless they are all there is.
function coreTokens(query: string): string[] {
  const all = tokens(query).filter((t) => !STOP.has(t) && !PREP.has(t) && !BASELINE.has(t) && !/^\d/.test(t));
  const firm = all.filter((t) => !NEUTRAL.has(t));
  return firm.length > 0 ? firm : all;
}

// True when the query names a food with no preparation or dish words ("apple", "banana", "chicken breast").
export function isPlainQuery(query: string): boolean {
  return !tokens(query).some((t) => PREP.has(t) || DISH.has(t));
}

// The state a plain query means when it does not say: raw for produce, cooked for grains, legumes and meat.
export function defaultState(query: string): "raw" | "cooked" | null {
  if (!isPlainQuery(query) || tokens(query).some((t) => BASELINE.has(t))) return null;
  const core = coreTokens(query);
  if (core.some((t) => COOKED_DEFAULT.has(t))) return "cooked";
  if (core.some((t) => PRODUCE_MAX_KCAL[t] !== undefined)) return "raw";
  return null;
}

export interface NameMatch {
  quality: Quality;
  penalty: number;
  dish: number;
}

export function matchName(query: string, name: string): NameMatch {
  const qSet = new Set(tokens(query));
  const core = coreTokens(query);
  if (core.length === 0) return { quality: "none", penalty: 99, dish: 0 };

  const nameTokens = new Set(tokens(name));
  const covered = core.filter((t) => nameTokens.has(t)).length / core.length;
  const cookedByDefault = defaultState(query) === "cooked";
  // Dish words only mean "a different food" for a plain food ("apple" is not apple pie); when the query is a dish
  // itself ("pepperoni pizza"), "crust" or "topping" just describe it.
  const queryIsDish = [...qSet].some((t) => DISH.has(t));

  let penalty = 0;
  let dish = 0;
  for (const t of nameTokens) {
    if (cookedByDefault && t === "raw") {
      penalty += 3;
      continue;
    }
    if (cookedByDefault && COOKING.has(t)) continue;
    if (qSet.has(t) || STOP.has(t) || NEUTRAL.has(t) || BASELINE.has(t) || /^\d/.test(t)) continue;
    if (DISH.has(t) && !queryIsDish) {
      dish++;
      penalty += 4;
    } else if (PREP.has(t)) {
      penalty += 2;
    } else {
      penalty += 1;
    }
  }

  let quality: Quality;
  if (covered === 1) quality = dish > 0 ? "weak" : penalty === 0 ? "exact" : "good";
  else if (covered >= 0.66) quality = dish > 0 ? "weak" : "good";
  else if (covered >= 0.5) quality = "weak";
  else quality = "none";
  return { quality, penalty, dish };
}

export function qualityRank(q: Quality): number {
  return { exact: 3, good: 2, weak: 1, none: 0 }[q];
}

// Generic USDA/government sources have richer, more reliable profiles than branded or crowd-sourced entries.
const PREFERRED_SOURCES = new Set(["USDAsr", "NCCDB", "CFCD", "USDAleg", "USDA"]);

export function sourceRank(source: string): number {
  const prefix = source.split(":")[0];
  if (PREFERRED_SOURCES.has(prefix)) return 2;
  if (source.startsWith("FDC:Branded")) return 0;
  return 1;
}

export interface RankedCandidate {
  result: CronoFoodSearchResult;
  order: number;
  match: NameMatch;
}

// Best first: match quality, then how plain the name is, then source, then Cronometer's own order.
export function rankCandidates(query: string, results: CronoFoodSearchResult[]): RankedCandidate[] {
  return results
    .map((result, order) => ({ result, order, match: matchName(query, result.name) }))
    .filter((c) => c.match.quality !== "none")
    .sort((a, b) =>
      qualityRank(b.match.quality) - qualityRank(a.match.quality) ||
      a.match.penalty - b.match.penalty ||
      sourceRank(b.result.source) - sourceRank(a.result.source) ||
      a.order - b.order
    );
}

// ---------------------------------------------------------------------------
// Suggestions while typing (replace or add an ingredient)
// ---------------------------------------------------------------------------

export type SuggestionKind = "cronometer" | "catalog";

export interface Measure {
  name: string;
  grams: number;
}

export interface Suggestion {
  ref: { kind: SuggestionKind; id: number };
  name: string;
  source: string | null;
  kcal_per_100g: number | null;
  measures: Measure[];
  default_grams: number;
  remembered: boolean;
}

// Like rankCandidates, but nothing is dropped: while typing, "pepp" matches no whole word yet and Cronometer's own order
// is the best signal. Whole-word matches still come first.
export function rankForSuggestions(query: string, results: CronoFoodSearchResult[]): CronoFoodSearchResult[] {
  return results
    .map((result, order) => ({ result, order, match: matchName(query, result.name) }))
    .sort((a, b) =>
      qualityRank(b.match.quality) - qualityRank(a.match.quality) ||
      (b.match.quality === "none" ? 0 : a.match.penalty - b.match.penalty) ||
      a.order - b.order
    )
    .map((c) => c.result);
}

// One of the food's servings to start a new ingredient with: a whole unit ("1 medium"), else the first real serving,
// else 100 g. Volumes and cuts ("1 cup, sliced") are only used when nothing else exists.
export function defaultServing(measures: Measure[]): Measure {
  const usable = measures.filter((m) => m.grams > 0 && !PLAIN_WEIGHT_MEASURE.test(m.name));
  return usable.find((m) => /\bmedium\b/i.test(m.name)) ??
    usable.find((m) => WHOLE_UNIT.test(m.name) && !VOLUME_OR_CUT.test(m.name)) ??
    usable.find((m) => !VOLUME_OR_CUT.test(m.name)) ??
    usable[0] ??
    { name: "100 g", grams: 100 };
}

const suggestionKey = (s: Suggestion) => `${s.ref.kind}:${s.ref.id}`;
const sameName = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();

// The user's remembered choice first, then catalog foods whose name matches the query word for word, then Cronometer,
// then the remaining catalog hits. Duplicates (same ref, or same name) keep their first place.
export function mergeSuggestions(
  query: string,
  remembered: Suggestion[],
  catalog: Suggestion[],
  cronometer: Suggestion[],
  limit: number,
): Suggestion[] {
  const strongCatalog = catalog.filter((s) => qualityRank(matchName(query, s.name).quality) >= qualityRank("good"));
  const weakCatalog = catalog.filter((s) => !strongCatalog.includes(s));
  const out: Suggestion[] = [];
  const keys = new Set<string>();
  for (const s of [...remembered, ...strongCatalog, ...cronometer, ...weakCatalog]) {
    if (out.length >= limit) break;
    if (keys.has(suggestionKey(s)) || out.some((o) => sameName(o.name, s.name))) continue;
    keys.add(suggestionKey(s));
    out.push(s);
  }
  return out;
}

// Simplify a query by stripping cooking modifiers and portion words to get a more generic search
export function simplifyQuery(query: string): string | null {
  const kept = query.toLowerCase().split(/[^\p{L}\p{N}%]+/u).filter((w) => {
    const t = canon(w);
    return w && !STOP.has(t) && !PREP.has(t) && !BASELINE.has(t) && !/^\d/.test(t);
  });
  if (kept.length === 0) return null;
  const simplified = kept.join(" ");
  return simplified !== query.toLowerCase().trim() ? simplified : null;
}

// Stable per-user memory key for a food query: "Apples, raw" and "apple" share "apple"; "apple, baked" does not.
export function memoryKey(query: string): string {
  return tokens(query).filter((t) => !STOP.has(t) && !BASELINE.has(t) && !NEUTRAL.has(t) && !/^\d/.test(t)).join(" ");
}

// ---------------------------------------------------------------------------
// Plausibility: reject database entries that cannot be the food that was asked for
// ---------------------------------------------------------------------------

// Upper kcal/100 g for whole produce (generous; the real values are well below). Only applied to plain queries.
const PRODUCE_MAX_KCAL: Record<string, number> = {
  apple: 80, pear: 80, banana: 120, orange: 70, mandarin: 70, clementine: 70, tangerine: 70, grapefruit: 60,
  lemon: 50, lime: 50, peach: 60, nectarine: 60, plum: 70, apricot: 70, cherry: 80, grape: 90, strawberry: 50,
  blueberry: 75, raspberry: 70, blackberry: 60, kiwi: 80, kiwifruit: 80, mango: 80, papaya: 60, pineapple: 70,
  melon: 50, watermelon: 45, cantaloupe: 50, honeydew: 50, pomegranate: 100, fig: 90, persimmon: 130, guava: 90,
  lychee: 80, avocado: 200, tomato: 30, cucumber: 25, lettuce: 25, spinach: 35, kale: 60, cabbage: 40, broccoli: 45,
  cauliflower: 35, carrot: 50, celery: 25, zucchini: 25, eggplant: 35, onion: 50, mushroom: 40, asparagus: 30,
  beet: 55, radish: 25, potato: 100, corn: 110, pea: 90,
};

function nutrientPer100g(food: CronoFoodDetail, id: number): number | null {
  const n = food.nutrients.find((x) => x.id === id);
  return n ? n.amount : null;
}

export function implausibility(food: CronoFoodDetail, query: string, liquid = false): string | null {
  const kcal = nutrientPer100g(food, 208);
  const protein = nutrientPer100g(food, 203) ?? 0;
  const carbs = nutrientPer100g(food, 205) ?? 0;
  const fat = nutrientPer100g(food, 204) ?? 0;
  const alcohol = nutrientPer100g(food, 221) ?? 0;

  if (kcal != null && kcal > 40) {
    const fromMacros = 4 * protein + 4 * carbs + 9 * fat + 7 * alcohol;
    if (Math.abs(fromMacros - kcal) > Math.max(40, 0.35 * kcal)) return "calories do not match its macros";
  }

  // Measured by volume, so per 100 g is per ~100 ml: soft drinks, juice and milk sit well under 120 kcal; a higher value
  // is a per-serving entry or a different product, unless alcohol or fat (cream, a shake) explains it.
  if (liquid && kcal != null && kcal > 120 && alcohol < 5 && fat < 5) return "too energy-dense for a drink";

  if (isPlainQuery(query)) {
    const produce = coreTokens(query).find((t) => PRODUCE_MAX_KCAL[t] !== undefined);
    if (produce) {
      if (kcal != null && kcal > PRODUCE_MAX_KCAL[produce]) return `too energy-dense for plain ${produce}`;
      const water = nutrientPer100g(food, 255);
      if (water != null && water < 60) return `too dry for fresh ${produce}`;
    }
  }
  return null;
}

// ---------------------------------------------------------------------------
// Resolved items, the independent AI estimate, and totals
// ---------------------------------------------------------------------------

export interface MacroEstimate {
  calories: number;
  protein_g: number;
  carbs_g: number;
  fat_g: number;
}

export interface Alternative {
  food_id: number;
  name: string;
  source: string;
  kcal_per_100g: number | null;
}

export interface ResolvedItem {
  query: string;
  cronometer_food_id: number | null;
  // Set instead of cronometer_food_id when the food comes from the app's own catalog (barcode products, label scans,
  // USDA rows): the two id spaces are separate.
  catalog_food_id: number | null;
  cronometer_food_name: string | null;
  cronometer_source: string | null;
  search_score: number | null;
  quantity_g: number;
  serving_used: string | null;
  nutrients_per_100g: Record<string, number>;
  nutrients_for_portion: Record<string, number>;
  all_measures: Array<{ name: string; grams: number }>;
  match_quality: Quality;
  alternatives: Alternative[];
  rejected: string[];
  memory_key: string;
  portion_count: number | null;
  portion_unit: string | null;
  from_memory: boolean;
  ai_estimate: MacroEstimate | null;
  // The AI estimate stands in for this item: no trustworthy match, or a non-exact match that disagrees with it.
  used_estimate: boolean;
  disagreement: boolean;
}

export function emptyResolvedItem(query: string, grams: number): ResolvedItem {
  return {
    query,
    cronometer_food_id: null,
    catalog_food_id: null,
    cronometer_food_name: null,
    cronometer_source: null,
    search_score: null,
    quantity_g: grams,
    serving_used: null,
    nutrients_per_100g: {},
    nutrients_for_portion: {},
    all_measures: [],
    match_quality: "none",
    alternatives: [],
    rejected: [],
    memory_key: memoryKey(query),
    portion_count: null,
    portion_unit: null,
    from_memory: false,
    ai_estimate: null,
    used_estimate: false,
    disagreement: false,
  };
}

// Database match vs. the independent AI estimate for the same item.
export function reconcile(item: ResolvedItem): void {
  const est = item.ai_estimate;
  item.used_estimate = false;
  item.disagreement = false;
  if (!est || !(est.calories > 0)) return;

  const matched = item.match_quality === "exact" || item.match_quality === "good";
  const kcal = item.nutrients_for_portion.calories;
  if (!matched || kcal == null) {
    item.used_estimate = true;
    return;
  }
  const ratio = kcal / est.calories;
  if ((ratio > 1.75 || ratio < 0.55) && Math.abs(kcal - est.calories) > 100) {
    item.disagreement = true;
    // An exact name match keeps its numbers (the flag tells the user to check the portion); a looser one yields, and
    // so does an exact one that is off by more than 3x (a per-serving entry stored as per 100 g, a wrong portion).
    if (item.match_quality !== "exact" || ratio > 3 || ratio < 1 / 3) item.used_estimate = true;
  }
}

export function itemCalories(item: ResolvedItem): number | null {
  if (item.used_estimate && item.ai_estimate) return item.ai_estimate.calories;
  if (item.match_quality === "exact" || item.match_quality === "good") return item.nutrients_for_portion.calories ?? null;
  return null;
}

// Calorie-weighted confidence over the items that contribute to the totals.
export function confidence(items: ResolvedItem[]): number {
  let weight = 0;
  let sum = 0;
  for (const item of items) {
    const kcal = itemCalories(item);
    const c = item.used_estimate ? 0.5
      : item.disagreement ? 0.6
      : item.match_quality === "exact" ? 0.95
      : item.match_quality === "good" ? 0.85
      : null;
    if (c == null) continue;
    const w = Math.max(kcal ?? 0, 1);
    weight += w;
    sum += c * w;
  }
  return weight > 0 ? Math.round((sum / weight) * 100) / 100 : 0.3;
}

const ESTIMATE_KEYS: Array<[keyof MacroEstimate, string]> = [
  ["calories", "calories"], ["protein_g", "protein"], ["carbs_g", "carbs"], ["fat_g", "fat"],
];

export function sumNutrients(results: ResolvedItem[]): {
  totals: Record<string, number>;
  nutrient_units: Record<string, string>;
  included: number;
  skipped: string[];
} {
  const totals: Record<string, number> = {};
  const skipped: string[] = [];
  let included = 0;

  const unitLookup: Record<string, string> = {};
  for (const entry of Object.values(NUTRIENT_MAP)) unitLookup[entry.name] = entry.unit;

  for (const r of results) {
    if (r.used_estimate && r.ai_estimate) {
      included++;
      for (const [from, to] of ESTIMATE_KEYS) totals[to] = (totals[to] ?? 0) + r.ai_estimate[from];
      continue;
    }
    if (r.match_quality === "none" || r.match_quality === "weak") {
      skipped.push(r.query);
      continue;
    }
    included++;
    for (const [key, val] of Object.entries(r.nutrients_for_portion)) {
      totals[key] = (totals[key] ?? 0) + val;
    }
  }
  for (const key of Object.keys(totals)) totals[key] = Math.round(totals[key] * 100) / 100;

  const nutrient_units: Record<string, string> = {};
  for (const key of Object.keys(totals)) {
    if (unitLookup[key]) nutrient_units[key] = unitLookup[key];
  }
  return { totals, nutrient_units, included, skipped };
}

// ---------------------------------------------------------------------------
// Single-item vs compound-meal detection
// ---------------------------------------------------------------------------

export function looksCompound(desc: string): boolean {
  if (/[^\x00-\x7F]/.test(desc)) return true;
  if (desc.length > 80) return true;
  if ((desc.match(/,/g) ?? []).length >= 2) return true;
  if (/\b(and|with|plus)\b/i.test(desc)) return true;
  if (/\w\s*\/\s*\w/.test(desc) && desc.split("/").length >= 2) return true;
  return false;
}

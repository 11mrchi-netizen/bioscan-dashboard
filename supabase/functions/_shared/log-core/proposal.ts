// Log-agent core (Analysis Layer 3 / R2, docs/analysis-layer-3). Erasable TypeScript, no I/O: Node 24 runs the tests, Deno runs
// the Edge Function `log-agent`.
//
// The model only READS the utterance into loosely typed entries. This file turns those entries into proposals and enforces the
// capture contract deterministically, so a wrong model output cannot become a plausible-looking row:
//   * every number the model emits (volume, strength, score, quantity, duration) must literally occur in the utterance
//     (a numeral or a number word); otherwise it is dropped and listed in `needs` for the person to fill in;
//   * alcohol is grams of ethanol = volume_ml x ABV% / 100 x 0.789, and it needs a stated strength;
//   * dates and times are resolved here from the client's local clock, never by the model;
//   * supplements are matched to the person's own roster by name, ambiguity is a question, not a guess;
//   * nothing is written by this module or by the function: proposals are confirmed in the app, which commits them through the
//     same repositories as the forms.

export const DRINK_CLASSES = ["water", "coffee", "tea", "milk", "plant_milk", "soda", "juice", "electrolyte", "alcohol"] as const;
export const RECOVERY_CATEGORIES = ["stretching_mobility", "contrast_therapy", "other"] as const;
export const WELLBEING_DIMS = ["energy", "mood", "stress", "soreness"] as const;
export const ETHANOL_DENSITY_G_PER_ML = 0.789;

export type Need = "volume_ml" | "abv_percent" | "energy" | "mood" | "stress" | "soreness" | "supplement_match";

export interface RawEntry {
  kind: "drink" | "meal" | "wellbeing" | "supplement_taken" | "recovery";
  quote?: string;
  day_offset?: number;
  time_hhmm?: string;
  beverage_class?: string;
  count?: number;
  volume_value?: number;
  volume_unit?: string;
  abv_percent?: number;
  meal_text?: string;
  mentioned?: string[];
  energy?: number;
  mood?: number;
  stress?: number;
  soreness?: number;
  supplement_name?: string;
  quantity?: number;
  recovery_name?: string;
  recovery_category?: string;
  duration_min?: number;
}

export interface Ctx {
  /** The client's local clock with its UTC offset, e.g. 2026-10-12T21:05:00+08:00. */
  nowLocalIso: string;
  /** Active supplements of the person. */
  roster: { id: number; name: string }[];
}

interface Base {
  id: string;
  quote: string;
  summary: string;
  warnings: string[];
  needs: Need[];
}
export type Proposal =
  | (Base & { kind: "drink"; beverage_class: string; volume_ml: number | null; abv_percent: number | null; alcohol_g: number | null; logged_at: string })
  | (Base & { kind: "meal"; text: string; logged_at: string })
  | (Base & { kind: "wellbeing"; date: string; energy: number | null; mood: number | null; stress: number | null; soreness: number | null })
  | (Base & { kind: "supplement_taken"; taken_at: string; supplement_id: number | null; supplement_name: string; quantity: number; candidates: { id: number; name: string }[] })
  | (Base & { kind: "recovery"; date: string; name: string; category: string; duration_min: number | null });

export interface Unparsed {
  quote: string;
  reason: string;
}

// ---- number grounding -------------------------------------------------------------------------------------------------

const WORDS: Record<string, number> = {
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12,
  thirteen: 13, fourteen: 14, fifteen: 15, sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19, twenty: 20, thirty: 30,
  forty: 40, fifty: 50, sixty: 60, seventy: 70, eighty: 80, ninety: 90, half: 0.5, quarter: 0.25,
};
const TENS = ["twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];

// Millilitres per unit. Anything else (glass, can, bottle, pint, shot) has no fixed volume and is asked, not guessed.
const ML_PER_UNIT: Record<string, number> = {
  ml: 1, milliliter: 1, milliliters: 1, millilitre: 1, millilitres: 1,
  cl: 10, centiliter: 10, centiliters: 10, centilitre: 10, centilitres: 10,
  dl: 100, deciliter: 100, deciliters: 100, decilitre: 100, decilitres: 100,
  l: 1000, liter: 1000, liters: 1000, litre: 1000, litres: 1000,
  oz: 29.5735, ounce: 29.5735, ounces: 29.5735,
  cup: 240, cups: 240,
};

/** Every number the utterance states, as numerals or number words. "a"/"an" count as 1 only before a volume unit. */
export function groundedNumbers(text: string): Set<number> {
  const t = text.toLowerCase().replace(/(\d),(\d{1,2})(?!\d)/g, "$1.$2").replace(/½/g, " 0.5 ");
  const out = new Set<number>();
  for (const m of t.matchAll(/\d+(?:\.\d+)?/g)) out.add(Number(m[0]));
  const words = t.match(/[a-z]+/g) ?? [];
  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    if (w in WORDS) out.add(WORDS[w]);
    const next = words[i + 1];
    if (TENS.includes(w) && next && next in WORDS && WORDS[next] >= 1 && WORDS[next] <= 9) out.add(WORDS[w] + WORDS[next]);
    if ((w === "a" || w === "an") && next && next in ML_PER_UNIT) out.add(1);
  }
  return out;
}

const grounded = (n: unknown, set: Set<number>): n is number => typeof n === "number" && Number.isFinite(n) && set.has(n);

// ---- local clock -------------------------------------------------------------------------------------------------------

interface Clock {
  date: string; // YYYY-MM-DD
  time: string; // HH:MM
  offset: string; // +08:00
}

export function parseClock(iso: string): Clock {
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/.exec(iso);
  if (!m) throw new Error(`bad local time: ${iso}`);
  return { date: m[1], time: m[2], offset: m[3] === "Z" ? "+00:00" : m[3] };
}

export function shiftDate(date: string, days: number): string {
  const [y, mo, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, mo - 1, d + days)).toISOString().slice(0, 10);
}

interface When {
  date: string;
  iso: string;
  assumed: boolean;
}

function resolveWhen(e: RawEntry, clock: Clock): When {
  const off = Number.isInteger(e.day_offset) && e.day_offset! <= 0 && e.day_offset! >= -7 ? e.day_offset! : 0;
  const date = shiftDate(clock.date, off);
  const hhmm = typeof e.time_hhmm === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(e.time_hhmm) ? e.time_hhmm : null;
  if (hhmm) return { date, iso: `${date}T${hhmm}:00${clock.offset}`, assumed: false };
  // No stated time: today means now, an earlier day means midday. Either way the review sheet shows it as assumed.
  return { date, iso: `${date}T${off === 0 ? clock.time : "12:00"}:00${clock.offset}`, assumed: true };
}

// ---- helpers -----------------------------------------------------------------------------------------------------------

export function ethanolGrams(volumeMl: number, abvPercent: number): number | null {
  if (!(volumeMl > 0) || !(abvPercent > 0) || abvPercent > 100) return null;
  return Math.round(volumeMl * abvPercent / 100 * ETHANOL_DENSITY_G_PER_ML * 10) / 10;
}

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();

/** Exact name, else the one roster item whose name contains (or is contained in) the spoken name. Several = ambiguous. */
export function matchRoster(spoken: string, roster: { id: number; name: string }[]): { id: number | null; candidates: { id: number; name: string }[] } {
  const s = norm(spoken);
  if (!s) return { id: null, candidates: [] };
  const exact = roster.filter((r) => norm(r.name) === s);
  if (exact.length === 1) return { id: exact[0].id, candidates: exact };
  const loose = roster.filter((r) => {
    const n = norm(r.name);
    return (s.length >= 4 && n.includes(s)) || (n.length >= 4 && s.includes(n));
  });
  if (loose.length === 1) return { id: loose[0].id, candidates: loose };
  return { id: null, candidates: loose };
}

const fmt = (n: number) => (Number.isInteger(n) ? String(n) : String(Math.round(n * 10) / 10));

// ---- proposals ---------------------------------------------------------------------------------------------------------

export function buildProposals(utterance: string, entries: RawEntry[], ctx: Ctx): { proposals: Proposal[]; unparsed: Unparsed[] } {
  const clock = parseClock(ctx.nowLocalIso);
  const nums = groundedNumbers(utterance);
  const proposals: Proposal[] = [];
  const unparsed: Unparsed[] = [];
  const next = () => `p${proposals.length + 1}`;

  for (const e of entries) {
    const quote = typeof e.quote === "string" ? e.quote : "";
    const when = resolveWhen(e, clock);
    const warnings: string[] = when.assumed && e.kind !== "wellbeing" ? [`time assumed ${when.iso.slice(11, 16)}`] : [];
    const needs: Need[] = [];

    if (e.kind === "drink") {
      if (!(DRINK_CLASSES as readonly string[]).includes(e.beverage_class ?? "")) {
        unparsed.push({ quote, reason: "unknown drink type" });
        continue;
      }
      const cls = e.beverage_class!;
      let count = 1;
      if (e.count !== undefined && e.count !== 1) {
        if (Number.isInteger(e.count) && e.count >= 1 && e.count <= 20 && grounded(e.count, nums)) count = e.count;
        else warnings.push("count not stated, counted as 1");
      }
      const factor = ML_PER_UNIT[(e.volume_unit ?? "").toLowerCase().replace(/\./g, "").replace(/^fl\s*/, "")];
      let volume: number | null = null;
      if (factor && grounded(e.volume_value, nums) && e.volume_value! > 0) volume = Math.round(count * e.volume_value! * factor);
      if (volume === null) needs.push("volume_ml");
      else if (volume > 5000) warnings.push("unusually large volume");
      let abv: number | null = null;
      if (cls === "alcohol") {
        if (grounded(e.abv_percent, nums) && e.abv_percent! > 0 && e.abv_percent! <= 100) abv = e.abv_percent!;
        else needs.push("abv_percent");
      }
      const grams = volume !== null && abv !== null ? ethanolGrams(volume, abv) : null;
      const label = cls.replace("_", " ");
      proposals.push({
        id: next(), kind: "drink", quote, warnings, needs, beverage_class: cls, volume_ml: volume, abv_percent: abv, alcohol_g: grams, logged_at: when.iso,
        summary: `${volume !== null ? `${fmt(volume)} ml ` : ""}${label}${abv !== null ? ` ${fmt(abv)}%` : ""}${grams !== null ? ` = ${fmt(grams)} g ethanol` : ""}`,
      });
    } else if (e.kind === "meal") {
      const text = (e.meal_text ?? "").trim();
      if (!text) {
        unparsed.push({ quote, reason: "empty meal" });
        continue;
      }
      proposals.push({ id: next(), kind: "meal", quote, warnings, needs, text, logged_at: when.iso, summary: text });
    } else if (e.kind === "wellbeing") {
      const values: Record<string, number | null> = {};
      for (const dim of WELLBEING_DIMS) {
        const v = e[dim];
        values[dim] = Number.isInteger(v) && v! >= 0 && v! <= 10 && grounded(v, nums) ? v! : null;
      }
      const mentioned = (e.mentioned ?? []).filter((d): d is (typeof WELLBEING_DIMS)[number] => (WELLBEING_DIMS as readonly string[]).includes(d));
      for (const dim of mentioned) if (values[dim] === null) needs.push(dim);
      if (WELLBEING_DIMS.every((d) => values[d] === null) && needs.length === 0) {
        unparsed.push({ quote, reason: "no wellbeing score" });
        continue;
      }
      proposals.push({
        id: next(), kind: "wellbeing", quote, warnings, needs, date: when.date,
        energy: values.energy, mood: values.mood, stress: values.stress, soreness: values.soreness,
        summary: WELLBEING_DIMS.filter((d) => values[d] !== null || needs.includes(d)).map((d) => `${d} ${values[d] ?? "?"}`).join(", "),
      });
    } else if (e.kind === "supplement_taken") {
      const spoken = (e.supplement_name ?? "").trim();
      if (!spoken) {
        unparsed.push({ quote, reason: "no supplement name" });
        continue;
      }
      let quantity = 1;
      if (e.quantity !== undefined && e.quantity !== 1) {
        if (Number.isInteger(e.quantity) && e.quantity >= 1 && e.quantity <= 20 && grounded(e.quantity, nums)) quantity = e.quantity;
        else warnings.push("quantity not stated, counted as 1");
      }
      const m = matchRoster(spoken, ctx.roster);
      if (m.id === null) needs.push("supplement_match");
      const name = m.id !== null ? m.candidates[0].name : spoken;
      proposals.push({
        id: next(), kind: "supplement_taken", quote, warnings, needs, taken_at: when.iso, supplement_id: m.id, supplement_name: name, quantity, candidates: m.candidates,
        summary: `${quantity > 1 ? `${quantity} x ` : ""}${name}${m.id === null ? (m.candidates.length ? " (which one?)" : " (not in your roster)") : ""}`,
      });
    } else if (e.kind === "recovery") {
      const name = (e.recovery_name ?? "").trim();
      if (!name) {
        unparsed.push({ quote, reason: "no recovery activity" });
        continue;
      }
      const category = (RECOVERY_CATEGORIES as readonly string[]).includes(e.recovery_category ?? "") ? e.recovery_category! : "other";
      const dur = Number.isInteger(e.duration_min) && e.duration_min! >= 1 && e.duration_min! <= 600 && grounded(e.duration_min, nums) ? e.duration_min! : null;
      proposals.push({
        id: next(), kind: "recovery", quote, warnings, needs, date: when.date, name, category, duration_min: dur,
        summary: `${name}${dur !== null ? ` ${dur} min` : ""}`,
      });
    } else {
      unparsed.push({ quote, reason: "unknown entry kind" });
    }
  }
  return { proposals, unparsed };
}

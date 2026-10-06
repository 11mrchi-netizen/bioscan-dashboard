// Pure helpers for supplement-dsld-lookup (DAV-359): no Deno/network globals,
// so they run under plain Node for tests (see dsld.test.ts).

const digitsOnly = (s: string) => s.replace(/\D/g, "");

// DSLD stores upcSku inconsistently (GS1-spaced like "0 33674 13941 7" for some
// labels, digits-only for others) and its exact-phrase match is token based, so
// one spelling cannot find the other. Probe each plausible spelling in order.
export function barcodeRepresentations(barcode: string): string[] {
  const asPassed = barcode.trim();
  const digits = digitsOnly(asPassed);
  const reps = [asPassed, digits];

  // EAN-13 with a leading 0 is a UPC-A; probe the 12-digit form too.
  const upcA = digits.length === 13 && digits.startsWith("0") ? digits.slice(1) : digits;
  if (upcA !== digits) reps.push(upcA);
  if (upcA.length === 12) {
    reps.push(`${upcA[0]} ${upcA.slice(1, 6)} ${upcA.slice(6, 11)} ${upcA[11]}`);
  }
  return [...new Set(reps.filter((r) => r.length > 0))];
}

// A search hit is only a candidate. The label's own upcSku must equal the
// scanned barcode (ignoring spacing and leading zeros) to count as a match.
export function sameBarcode(a: string | null | undefined, b: string | null | undefined): boolean {
  if (!a || !b) return false;
  const x = digitsOnly(a).replace(/^0+/, "");
  const y = digitsOnly(b).replace(/^0+/, "");
  return x.length > 0 && x === y;
}

export interface CompactIngredient {
  name: string;
  category: string | null;
  amount: number | null;
  unit: string | null;
}

export interface CompactLabel {
  dsldId: number;
  fullName: string | null;
  brandName: string | null;
  upcSku: string | null;
  offMarket: boolean | null;
  entryDate: string | null;
  productVersionCode: string | null;
  // Label-reported container info (strings/objects in DSLD, normalised here).
  servingsPerContainer: number | null;
  netContents: string | null;
  form: string | null;
  servingSize: { min: number | null; max: number | null; unit: string | null } | null;
  ingredients: CompactIngredient[];
}

// Maps a raw /v9/label/{id} body to the fields the app compares. An ingredient
// without a numeric quantity (e.g. a proprietary blend row) keeps amount null
// rather than a fabricated number. The amount is the first listed quantity,
// which belongs to serving size order 1.
// deno-lint-ignore no-explicit-any
export function mapLabel(label: any): CompactLabel {
  const serving = Array.isArray(label.servingSizes) ? label.servingSizes[0] : null;
  // deno-lint-ignore no-explicit-any
  const rows: any[] = Array.isArray(label.ingredientRows) ? label.ingredientRows : [];
  return {
    dsldId: label.id,
    fullName: label.fullName ?? null,
    brandName: label.brandName ?? null,
    upcSku: label.upcSku ?? null,
    offMarket: toBool(label.offMarket),
    entryDate: label.entryDate ?? null,
    productVersionCode: label.productVersionCode ?? null,
    servingsPerContainer: toPositive(label.servingsPerContainer),
    netContents: Array.isArray(label.netContents) ? (label.netContents[0]?.display ?? null) : null,
    form: label.physicalState?.langualCodeDescription ?? null,
    servingSize: serving
      ? { min: serving.minQuantity ?? null, max: serving.maxQuantity ?? null, unit: serving.unit ?? null }
      : null,
    ingredients: rows.map((r) => {
      const q = Array.isArray(r.quantity) ? r.quantity[0] : null;
      return {
        name: String(r.name ?? ""),
        category: r.category ?? null,
        amount: typeof q?.quantity === "number" ? q.quantity : null,
        unit: q?.unit ?? null,
      };
    }),
  };
}

function toBool(v: unknown): boolean | null {
  if (typeof v === "number") return v === 1;
  return typeof v === "boolean" ? v : null;
}

// DSLD reports servingsPerContainer as a string ("150"); non-numeric or
// non-positive values stay null rather than becoming 0.
function toPositive(v: unknown): number | null {
  const n = typeof v === "number" ? v : typeof v === "string" && v.trim() !== "" ? Number(v) : NaN;
  return Number.isFinite(n) && n > 0 ? n : null;
}

// One UPC can match several label versions (5 seen for one multivitamin, 4 of
// them off-market). Current, on-market labels first, then newest entryDate;
// unknown offMarket sorts between on-market and off-market.
export function rankLabels<T extends { offMarket: boolean | null; entryDate: string | null }>(labels: T[]): T[] {
  const rank = (m: boolean | null) => (m === false ? 0 : m === null ? 1 : 2);
  return [...labels].sort((a, b) =>
    rank(a.offMarket) - rank(b.offMarket) || (b.entryDate ?? "").localeCompare(a.entryDate ?? "")
  );
}

export interface DsldCandidate {
  dsldId: number;
  fullName: string | null;
  brandName: string | null;
  offMarket: boolean | null;
  entryDate: string | null;
  netContents: string | null;
  form: string | null;
}

// Maps a /search-filter hit (its _source has no UPC) to a candidate for the
// name-search picker. The label is fetched by id once the user chooses one.
// deno-lint-ignore no-explicit-any
export function mapHit(hit: any): DsldCandidate {
  const s = hit?._source ?? {};
  return {
    dsldId: Number(hit?._id),
    fullName: s.fullName ?? null,
    brandName: s.brandName ?? null,
    offMarket: toBool(s.offMarket),
    entryDate: s.entryDate ?? null,
    netContents: Array.isArray(s.netContents) ? (s.netContents[0]?.display ?? null) : null,
    form: s.physicalState?.langualCodeDescription ?? null,
  };
}

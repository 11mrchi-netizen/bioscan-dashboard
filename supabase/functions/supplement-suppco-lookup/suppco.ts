// Pure helpers for supplement-suppco-lookup (DAV-360): no Deno/network globals,
// so they run under plain Node for tests (see suppco.test.ts).

export interface ServingSize {
  raw: string | null;
  quantity: number | null;
  unit: string | null;
}

// SuppCo's serving_size is free text ("1 Tablet(s)", "1 Scoop (5g)", "5 G").
// Returns the leading quantity and the remaining text as the unit. A range
// ("1-2 capsules") or anything without a leading number keeps only `raw` --
// never a guessed quantity.
export function parseServingSize(raw: string | null | undefined): ServingSize {
  const text = (raw ?? "").trim();
  if (!text) return { raw: null, quantity: null, unit: null };
  const s = text.replace(/(\d),(\d{3})/g, "$1$2");

  if (/^\d+(?:\.\d+)?\s*(?:-|–|to)\s*\d/i.test(s)) return { raw: text, quantity: null, unit: null };

  const fraction = s.match(/^(\d+)\s*\/\s*(\d+)\s*(.*)$/);
  if (fraction && Number(fraction[2]) !== 0) {
    return { raw: text, quantity: Number(fraction[1]) / Number(fraction[2]), unit: fraction[3].trim() || null };
  }

  const m = s.match(/^(\d+(?:\.\d+)?)\s*(.*)$/);
  if (!m) return { raw: text, quantity: null, unit: null };
  return { raw: text, quantity: Number(m[1]), unit: m[2].trim() || null };
}

const toNum = (v: unknown): number | null => {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string" && v.trim() !== "") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
};

// SuppCo stores 0.0 for "ingredient present, amount not stated". Keep that as
// null rather than presenting a fabricated "0 mg".
const positiveOrNull = (v: unknown): number | null => {
  const n = toNum(v);
  return n !== null && n > 0 ? n : null;
};

const str = (v: unknown): string | null => (typeof v === "string" && v.trim() !== "" ? v : null);

const CERTIFICATIONS: Record<string, string> = {
  usp_verified: "USP Verified",
  nsf_contents_certified: "NSF Contents Certified",
  nsf_certified_for_sport: "NSF Certified for Sport",
  informed_sport_certified: "Informed Sport",
  informed_choice_certified: "Informed Choice",
  eurofins_certified_supplement: "Eurofins Certified",
  gluten_free_certified: "Gluten-free certified",
  non_gmo_certified: "Non-GMO certified",
  usda_organic_certified: "USDA Organic",
  vegan_action_certified: "Vegan Action",
};

export interface CompactIngredient {
  name: string;
  formOf: string | null;
  category: string | null;
  nutrientId: string | null;
  amount: number | null;
  unit: string | null;
}

export interface CompactProduct {
  id: string | null;
  slug: string | null;
  name: string | null;
  brand: string | null;
  upc: string | null;
  category: string | null;
  format: string | null;
  servingSize: ServingSize;
  servingsPerContainer: number | null;
  suggestedUse: string | null;
  validated: boolean | null;
  offMarket: boolean | null;
  containsProprietaryBlend: boolean | null;
  trust: {
    product: number | null;
    brand: number | null;
    status: string | null;
    percentileInCategory: number | null;
    details: { category: string; rank: string }[];
  };
  safety: {
    activeFdaRecall: boolean;
    recallUrl: string | null;
    recallBody: string | null;
    brandActiveFdaRecall: boolean;
    fdaWarningLetter: boolean;
    failedAnyTests: boolean;
  };
  testing: {
    testedBySuppco: boolean;
    heavyMetals: string | null;
    identityPotency: string | null;
    certifications: string[];
  };
  price: number | null;
  pricePerServing: number | null;
  labelUrl: string | null;
  ingredients: CompactIngredient[];
}

// deno-lint-ignore no-explicit-any
export function mapProduct(p: any): CompactProduct {
  // deno-lint-ignore no-explicit-any
  const rows: any[] = Array.isArray(p.product_ingredients) ? p.product_ingredients : [];
  // deno-lint-ignore no-explicit-any
  const details: any[] = Array.isArray(p.trust_score_details) ? p.trust_score_details : [];
  return {
    id: str(p.id),
    slug: str(p.slug),
    name: str(p.name),
    brand: str(p.brand),
    upc: str(p.upc),
    category: str(p.category_name),
    format: str(p.format),
    servingSize: parseServingSize(p.serving_size),
    servingsPerContainer: positiveOrNull(p.servings_per_container),
    suggestedUse: str(p.suggested_use),
    validated: typeof p.validated === "boolean" ? p.validated : null,
    offMarket: typeof p.off_market === "boolean" ? p.off_market : null,
    containsProprietaryBlend: typeof p.contains_proprietary_blend === "boolean" ? p.contains_proprietary_blend : null,
    trust: {
      product: toNum(p.product_trust_score),
      brand: toNum(p.brand_trust_score),
      status: str(p.product_trust_score_status),
      percentileInCategory: toNum(p.product_trust_score_percentile_in_category),
      details: details
        .filter((d) => typeof d?.category === "string" && typeof d?.rank === "string")
        .map((d) => ({ category: d.category, rank: d.rank })),
    },
    safety: {
      activeFdaRecall: p.active_fda_recall === true,
      recallUrl: str(p.active_fda_recall_url),
      recallBody: str(p.active_fda_recall_body),
      brandActiveFdaRecall: p.brand_active_fda_recall === true,
      fdaWarningLetter: p.received_fda_warning_letter === true && p.resolved_fda_warning_letter !== true,
      failedAnyTests: p.failed_any_tests === true,
    },
    testing: {
      testedBySuppco: p.tested_by_suppco === true,
      heavyMetals: str(p.heavy_metals_testing),
      identityPotency: str(p.identity_potency_testing),
      certifications: Object.entries(CERTIFICATIONS).filter(([k]) => p[k] === true).map(([, label]) => label),
    },
    price: toNum(p.price),
    pricePerServing: toNum(p.price_per_serving),
    labelUrl: str(p.label_url),
    ingredients: rows.map((r) => ({
      name: String(r.name ?? ""),
      formOf: str(r.from),
      category: str(r.category),
      nutrientId: str(r.nutrient_id),
      amount: positiveOrNull(r.amount),
      unit: str(r.units),
    })),
  };
}

// UPC-A is stored with its leading zero ("033674139417"). An EAN-13 scan of the
// same product arrives as "0033674139417"; probe both spellings.
//
// SuppCo holds junk listings under placeholder UPCs such as "000000000000", so a
// repeated-digit barcode (an empty or misread scan) is rejected outright.
export function upcCandidates(barcode: string): string[] {
  const digits = barcode.replace(/\D/g, "");
  if (/^(\d)\1*$/.test(digits)) return [];
  const out = [digits];
  if (digits.length === 13 && digits.startsWith("0")) out.push(digits.slice(1));
  return [...new Set(out.filter((d) => d.length >= 6))];
}

// A UPC query can still return loosely related listings; keep only products whose
// own upc equals one of the requested spellings. Several listings can legitimately
// share one UPC (variants), so more than one result is returned as candidates.
export function exactUpcMatches(products: CompactProduct[], candidates: string[]): CompactProduct[] {
  return products.filter((p) => p.upc !== null && candidates.includes(p.upc.replace(/\D/g, "")));
}

// Pure parsing for regimen-reference-lookup (no network, no Deno APIs), so node --test can check it.
//
// Sources, all public and read-only:
//   RxNorm (NIH, rxnav.nlm.nih.gov/REST): the drug's standard id (RxCUI) and ingredient name.
//   openFDA drug labels (api.fda.gov/drug/label.json): the FDA label's sections.
//   PubMed E-utilities (eutils.ncbi.nlm.nih.gov): studies, for substances with no FDA label (research peptides).
// RxNav's drug-interaction API was retired by NIH in January 2024; a label's own "Drug interactions" section replaces it.

export type RxConcept = { rxcui: string; name: string; tty: string | null };

export type LabelCandidate = {
  setId: string;
  effectiveTime: string | null;
  brand: string | null;
  generic: string | null;
  manufacturer: string | null;
  route: string[];
  /** "HUMAN PRESCRIPTION DRUG" or "HUMAN OTC DRUG" (homeopathic products are OTC). */
  productType: string | null;
};

export type HalfLife = { hours: number; text: string };

export type CompactLabel = LabelCandidate & {
  version: string | null;
  url: string;
  sections: Record<string, string>;
  halfLife: HalfLife | null;
};

export type PubmedItem = { pmid: string; title: string; journal: string | null; year: string | null; url: string };

/** The label sections kept, in display order, with how much of each (characters). */
export const LABEL_SECTIONS: Array<[key: string, alternatives: string[], maxChars: number]> = [
  ["boxed_warning", ["boxed_warning"], 2000],
  ["contraindications", ["contraindications"], 2000],
  ["drug_interactions", ["drug_interactions"], 8000],
  ["warnings", ["warnings_and_cautions", "warnings"], 3000],
  ["pharmacokinetics", ["pharmacokinetics", "clinical_pharmacology"], 4000],
  ["dosage_forms_and_strengths", ["dosage_forms_and_strengths"], 1200],
];

const first = (v: unknown): string | null => (Array.isArray(v) && typeof v[0] === "string" ? v[0] : null);

export function rxcuiFromIdGroup(body: any): string | null {
  const ids = body?.idGroup?.rxnormId;
  return Array.isArray(ids) && ids.length > 0 ? String(ids[0]) : null;
}

/** approximateTerm: the best-ranked candidate's RxCUI. */
export function rxcuiFromApproximate(body: any): string | null {
  const c = body?.approximateGroup?.candidate;
  if (!Array.isArray(c) || c.length === 0) return null;
  const best = [...c].sort((a, b) => Number(a.rank) - Number(b.rank))[0];
  return best?.rxcui ? String(best.rxcui) : null;
}

export function conceptFromProperties(body: any): RxConcept | null {
  const p = body?.properties;
  if (!p?.rxcui || !p?.name) return null;
  return { rxcui: String(p.rxcui), name: String(p.name), tty: p.tty ? String(p.tty) : null };
}

/** related.json?tty=IN: the ingredient concepts of a product. */
export function ingredientsFromRelated(body: any): RxConcept[] {
  const groups = body?.relatedGroup?.conceptGroup ?? [];
  return groups.flatMap((g: any) => (g?.conceptProperties ?? []).map((c: any) => ({ rxcui: String(c.rxcui), name: String(c.name), tty: c.tty ?? null })));
}

export function labelCandidate(r: any): LabelCandidate {
  const o = r?.openfda ?? {};
  return {
    setId: String(r?.set_id ?? ""),
    effectiveTime: r?.effective_time ?? null,
    brand: first(o.brand_name),
    generic: first(o.generic_name),
    manufacturer: first(o.manufacturer_name),
    route: Array.isArray(o.route) ? o.route.map(String) : [],
    productType: first(o.product_type),
  };
}

/** Prescription labels before over-the-counter ones (where homeopathic products sit), then newest first; one per set id. */
export function rankLabels(results: any[]): LabelCandidate[] {
  const seen = new Set<string>();
  const rx = (c: LabelCandidate) => (c.productType?.includes("PRESCRIPTION") ? 0 : 1);
  return results.map(labelCandidate)
    .filter((c) => c.setId && !seen.has(c.setId) && (seen.add(c.setId), true))
    .sort((a, b) => rx(a) - rx(b) || (b.effectiveTime ?? "").localeCompare(a.effectiveTime ?? ""));
}

const NUMBER_WORDS: Record<string, number> = {
  one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12,
  fifteen: 15, twenty: 20, thirty: 30,
};

const UNIT_HOURS: Record<string, number> = { minute: 1 / 60, min: 1 / 60, hour: 1, hr: 1, h: 1, day: 24, week: 168 };

/**
 * The first half-life stated with a number and a unit in [text]: "the mean terminal half-life is 17.5 hours" -> 17.5 h.
 * A range ("4 to 6 hours") gives its middle. The quoted text is the stretch it was read from, so it can be shown.
 */
export function extractHalfLife(text: string): HalfLife | null {
  const re = /half-?life[^.;]{0,120}?(\d+(?:\.\d+)?)(?:\s*(?:to|-|–)\s*(\d+(?:\.\d+)?))?\s*(minutes?|min|hours?|hrs?|h\b|days?|weeks?)/i;
  // Sentences can contain decimals ("17.5 hours"), so the match runs over the raw text, not split sentences.
  // Labels write some numbers as words ("approximately eight days").
  const flat = text.replace(/\s+/g, " ").replace(/\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen|twenty|thirty)\b/gi,
    (w) => String(NUMBER_WORDS[w.toLowerCase()]));
  const m = re.exec(flat);
  if (!m) return null;
  const lo = Number(m[1]);
  const hi = m[2] ? Number(m[2]) : lo;
  const unit = m[3].toLowerCase().replace(/s$/, "").replace(/^hrs?$/, "hr");
  const perUnit = UNIT_HOURS[unit] ?? UNIT_HOURS[unit.replace(/r$/, "")] ?? null;
  if (perUnit == null || !(lo > 0)) return null;
  const start = Math.max(0, m.index - 60);
  const quote = flat.slice(start, m.index + m[0].length + 20).trim();
  return { hours: Math.round(((lo + hi) / 2) * perUnit * 100) / 100, text: (start > 0 ? "…" : "") + quote + "…" };
}

/** One FDA label kept small: its identity, the sections that matter (trimmed) and the half-life it states. */
export function compactLabel(r: any): CompactLabel {
  const sections: Record<string, string> = {};
  for (const [key, alts, max] of LABEL_SECTIONS) {
    const raw = alts.map((a) => first(r?.[a])).find((v) => v);
    if (raw) sections[key] = raw.length > max ? raw.slice(0, max).trimEnd() + "…" : raw;
  }
  const pk = [first(r?.pharmacokinetics), first(r?.clinical_pharmacology)].filter(Boolean).join(" ");
  const c = labelCandidate(r);
  return {
    ...c,
    version: r?.version ?? null,
    url: `https://dailymed.nlm.nih.gov/dailymed/lookup.cfm?setid=${encodeURIComponent(c.setId)}`,
    sections,
    halfLife: pk ? extractHalfLife(pk) : null,
  };
}

/** esummary.fcgi?db=pubmed&retmode=json: the items in the order of [ids]. */
export function pubmedItems(summary: any, ids: string[]): PubmedItem[] {
  const result = summary?.result ?? {};
  return ids.map((id) => result[id]).filter(Boolean).map((it: any) => ({
    pmid: String(it.uid),
    title: String(it.title ?? "").trim(),
    journal: it.fulljournalname ?? it.source ?? null,
    year: typeof it.pubdate === "string" ? it.pubdate.slice(0, 4) : null,
    url: `https://pubmed.ncbi.nlm.nih.gov/${it.uid}/`,
  }));
}

/** The words of a name worth searching on: brand noise and dose text removed ("Tadalafil 5 mg (Cialis)" -> "Tadalafil"). */
export function searchTerm(name: string): string {
  return name.replace(/\([^)]*\)/g, " ").replace(/\b\d+(?:[.,]\d+)?\s*(mg|mcg|g|iu|ml|%)\b/gi, " ").replace(/\s+/g, " ").trim().slice(0, 80);
}

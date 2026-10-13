// Analysis Layer 3 / Phase 7: methodology as data. The registry JSON (../metric-registry/metric-registry.json) holds, for every
// canonical metric, what it measures, how, its limits and its primary references, so the console and the Android app show the
// same text and a metric cannot ship without it. This file only describes the shape and checks it; erasable TypeScript.

export const METRIC_KINDS = ["raw", "derived", "modeled", "inferred", "composite"] as const;
export type MetricKind = (typeof METRIC_KINDS)[number];

export interface MetricReference {
  citation: string;
  doi: string | null;
}

export interface MetricMethodology {
  key: string;
  /** Exact canonical name in docs/analysis-layer-2/02-metric-registry.md, or null for entries that table does not list. */
  docName: string | null;
  kind: MetricKind;
  methodology: string;
  limitations: string[];
  references: MetricReference[];
  productDefined: boolean;
  methodologyVersion: string;
}

export interface MetricRegistry {
  registryVersion: string;
  metrics: MetricMethodology[];
}

const SEMVER = /^\d+\.\d+\.\d+$/;

/** Rows of the doc-02 "Registry" table: the first cell of each data row (the canonical metric name). */
export function docMetricNames(markdown: string): string[] {
  const start = markdown.indexOf("\n## Registry");
  if (start < 0) return [];
  const rest = markdown.slice(start + 1);
  const end = rest.indexOf("\n## ", 3);
  const section = end < 0 ? rest : rest.slice(0, end);
  const rows = section.split("\n").filter((l) => l.startsWith("|"));
  return rows
    .slice(2) // header + separator
    .map((l) => l.split("|")[1].trim())
    .filter((n) => n.length > 0);
}

/** Problems that make the registry unfit to ship; empty = valid. `docNames` are the canonical names from doc 02. */
export function validateRegistry(reg: MetricRegistry, docNames: string[]): string[] {
  const problems: string[] = [];
  if (!SEMVER.test(reg.registryVersion ?? "")) problems.push("registryVersion is not semver");
  const keys = new Set<string>();
  const documented = new Set<string>();
  for (const m of reg.metrics ?? []) {
    const id = m.key || "(no key)";
    if (!/^[a-z][a-z0-9_]*$/.test(m.key ?? "")) problems.push(`${id}: key must be snake_case`);
    if (keys.has(m.key)) problems.push(`${id}: duplicate key`);
    keys.add(m.key);
    if (!(METRIC_KINDS as readonly string[]).includes(m.kind)) problems.push(`${id}: unknown kind ${m.kind}`);
    if (typeof m.methodology !== "string" || m.methodology.trim().length < 20) problems.push(`${id}: methodology missing or too short`);
    if (!Array.isArray(m.limitations) || m.limitations.length === 0 || m.limitations.some((l) => typeof l !== "string" || !l.trim())) {
      problems.push(`${id}: needs at least one limitation`);
    }
    if (!Array.isArray(m.references)) problems.push(`${id}: references must be an array`);
    else {
      for (const r of m.references) {
        if (!r || typeof r.citation !== "string" || r.citation.trim().length < 10) problems.push(`${id}: reference without a citation`);
        else if (r.doi !== null && !(typeof r.doi === "string" && r.doi.startsWith("10."))) problems.push(`${id}: doi must be null or start with 10.`);
      }
    }
    if (typeof m.productDefined !== "boolean") problems.push(`${id}: productDefined must be a boolean`);
    if (!SEMVER.test(m.methodologyVersion ?? "")) problems.push(`${id}: methodologyVersion is not semver`);
    if ((m.kind === "modeled" || m.kind === "inferred") && Array.isArray(m.references) && m.references.length === 0 && m.productDefined !== true) {
      problems.push(`${id}: a ${m.kind} metric needs a reference or productDefined=true`);
    }
    if (m.kind === "composite" && m.productDefined !== true) problems.push(`${id}: a composite must be productDefined`);
    if (m.docName !== null && m.docName !== undefined) {
      if (!docNames.includes(m.docName)) problems.push(`${id}: docName "${m.docName}" is not in doc 02`);
      documented.add(m.docName);
    }
  }
  for (const n of docNames) if (!documented.has(n)) problems.push(`doc 02 metric "${n}" has no registry entry`);
  return problems;
}

/** The text shown behind a help button: methodology, limits, references and (for composites) the product-defined label. */
export function methodologyText(m: MetricMethodology): string {
  const parts = [m.methodology];
  if (m.productDefined) parts.push("Product-defined: not clinically validated.");
  if (m.limitations.length) parts.push("Limits:\n" + m.limitations.map((l) => `- ${l}`).join("\n"));
  if (m.references.length) {
    parts.push("References:\n" + m.references.map((r) => `- ${r.citation}${r.doi ? ` doi:${r.doi}` : ""}`).join("\n"));
  }
  parts.push(`Methodology v${m.methodologyVersion}`);
  return parts.join("\n\n");
}

// regimen-reference-lookup — reference cards for regimen items (docs/regimen/README.md, phase 1b)
//
// Read-only and on demand: called when the person adds, edits or refreshes an item, never in the background. It writes
// nothing; the app keeps the one card the person picks, per item (regimen_references). No catalogue is downloaded.
//   {query}       search: RxNorm's standard id and ingredient, the matching FDA labels (newest first) and, when there
//                 is no FDA label (research peptides), PubMed studies.
//   {labelSetId}  one FDA label, kept small: the sections that matter and the half-life it states.
// Sources: RxNorm (NIH), openFDA drug labels (FDA), PubMed E-utilities (NIH). All public, no keys.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import {
  compactLabel, conceptFromProperties, ingredientsFromRelated, pubmedItems, rankLabels, type RxConcept,
  rxcuiFromApproximate, rxcuiFromIdGroup, searchTerm,
} from "./reference.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, "Content-Type": "application/json" } });
}

const RXNAV = "https://rxnav.nlm.nih.gov/REST";
const OPENFDA = "https://api.fda.gov/drug/label.json";
const EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils";

async function getJson(url: string): Promise<any | null> {
  const res = await fetch(url, { signal: AbortSignal.timeout(10_000), headers: { "User-Agent": "bioscan-field-terminal/1.0" } });
  if (res.status === 404) return null; // openFDA answers "no match" with 404
  if (!res.ok) throw new Error(`${new URL(url).host} returned ${res.status}`);
  return res.json();
}

const SOURCES = {
  rxnorm: { name: "RxNorm (NIH)", url: "https://www.nlm.nih.gov/research/umls/rxnorm/" },
  openfda: { name: "FDA drug label (openFDA)", url: "https://open.fda.gov/apis/drug/label/" },
  pubmed: { name: "PubMed (NIH)", url: "https://pubmed.ncbi.nlm.nih.gov/" },
};

/** RxNorm's concept for [term], its ingredient(s); null when RxNorm does not know it. */
async function rxnorm(term: string): Promise<{ concept: RxConcept; ingredients: RxConcept[] } | null> {
  let rxcui = rxcuiFromIdGroup(await getJson(`${RXNAV}/rxcui.json?name=${encodeURIComponent(term)}&search=2`));
  if (!rxcui) {
    // Approximate matching always proposes something; keep it only when its name starts like the term.
    const approx = rxcuiFromApproximate(await getJson(`${RXNAV}/approximateTerm.json?term=${encodeURIComponent(term)}&maxEntries=3`));
    if (approx) {
      const c = conceptFromProperties(await getJson(`${RXNAV}/rxcui/${approx}/properties.json`));
      const head = term.toLowerCase().split(/\s+/)[0].slice(0, 5);
      if (c && c.name.toLowerCase().includes(head)) rxcui = approx;
    }
  }
  if (!rxcui) return null;
  const concept = conceptFromProperties(await getJson(`${RXNAV}/rxcui/${rxcui}/properties.json`));
  if (!concept) return null;
  const ingredients = concept.tty === "IN" || concept.tty === "MIN" || concept.tty === "PIN"
    ? [concept]
    : ingredientsFromRelated(await getJson(`${RXNAV}/rxcui/${rxcui}/related.json?tty=IN`));
  return { concept, ingredients };
}

async function labelsFor(names: string[]): Promise<ReturnType<typeof rankLabels>> {
  for (const n of names) {
    const q = `openfda.generic_name:"${n.replace(/"/g, "")}"`;
    const body = await getJson(`${OPENFDA}?search=${encodeURIComponent(q)}&limit=10`);
    const ranked = rankLabels(body?.results ?? []);
    if (ranked.length > 0) return ranked.slice(0, 5);
  }
  return [];
}

async function pubmed(term: string) {
  const search = await getJson(`${EUTILS}/esearch.fcgi?db=pubmed&retmode=json&retmax=5&sort=relevance&tool=bioscan&term=${encodeURIComponent(term)}`);
  const ids: string[] = search?.esearchresult?.idlist ?? [];
  if (ids.length === 0) return [];
  const summary = await getJson(`${EUTILS}/esummary.fcgi?db=pubmed&retmode=json&tool=bioscan&id=${ids.join(",")}`);
  return pubmedItems(summary, ids);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return json({ error: "missing_auth" }, 401);
    const callerClient = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
      global: { headers: { Authorization: authHeader } },
    });
    const { data: { user }, error: userError } = await callerClient.auth.getUser(authHeader.replace("Bearer ", ""));
    if (userError || !user) return json({ error: "unauthorized" }, 401);

    let body: { query?: string; labelSetId?: string };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const retrievedAt = new Date().toISOString();

    if (body.labelSetId) {
      const setId = String(body.labelSetId).replace(/[^0-9a-fA-F-]/g, "").slice(0, 64);
      const res = await getJson(`${OPENFDA}?search=${encodeURIComponent(`set_id:"${setId}"`)}&limit=1`);
      const r = res?.results?.[0];
      if (!r) return json({ found: false, retrievedAt, source: SOURCES.openfda }, 200);
      return json({ found: true, retrievedAt, source: SOURCES.openfda, label: compactLabel(r) }, 200);
    }

    const term = searchTerm(body.query ?? "");
    if (term.length < 3) return json({ error: "query_too_short" }, 400);
    // Each source fails on its own: one being down never hides the others' answers.
    const rx = await rxnorm(term).catch(() => null);
    const names = [...new Set([...(rx?.ingredients ?? []).map((i) => i.name), rx?.concept.name, term].filter(Boolean) as string[])];
    const labels = await labelsFor(names).catch(() => []);
    const studies = labels.length === 0 ? await pubmed(term).catch(() => []) : [];
    return json({
      retrievedAt,
      term,
      rxnorm: rx ? { ...rx.concept, ingredients: rx.ingredients } : null,
      labels,
      pubmed: studies,
      sources: SOURCES,
    }, 200);
  } catch (e) {
    return json({ error: "lookup_failed", detail: String((e as Error)?.message ?? e).slice(0, 200) }, 502);
  }
});

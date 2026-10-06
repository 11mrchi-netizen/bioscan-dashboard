// supplement-dsld-lookup — DAV-359
//
// Read-only barcode verification against the NIH Dietary Supplement Label
// Database (public API v9, no key). Returns the matching label(s) in a compact
// shape plus retrieval metadata; it writes nothing. The app stores the result
// as a per-user snapshot and never overwrites the user's own product data with
// it (DSLD is enrichment/verification, not a runtime dependency).
//
// Search hits do not carry the UPC and DSLD stores it inconsistently, so a hit
// is only a candidate: its label's own upcSku must equal the barcode. No
// scraping -- only the documented API.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { barcodeRepresentations, type CompactLabel, mapLabel, sameBarcode } from "./dsld.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

const DSLD = "https://api.ods.od.nih.gov/dsld/v9";
const CANDIDATES_PER_PROBE = 5;
const MAX_LABEL_FETCHES = 12;

async function getJson(url: string) {
  const res = await fetch(url, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`DSLD returned ${res.status}`);
  return res.json();
}

async function sha256Hex(text: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return json({ error: "missing_auth" }, 401);

    const callerClient = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_ANON_KEY")!,
      { global: { headers: { Authorization: authHeader } } },
    );
    const token = authHeader.replace("Bearer ", "");
    const { data: { user }, error: userError } = await callerClient.auth.getUser(token);
    if (userError || !user) return json({ error: "unauthorized" }, 401);

    let body: { barcode?: string };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    const barcode = body.barcode?.trim();
    if (!barcode || barcode.replace(/\D/g, "").length < 6) return json({ error: "invalid_barcode" }, 400);

    const seen = new Set<string>();
    let matched: CompactLabel[] = [];

    for (const rep of barcodeRepresentations(barcode)) {
      const search = await getJson(`${DSLD}/search-filter?q=${encodeURIComponent(`"${rep}"`)}&size=${CANDIDATES_PER_PROBE}`);
      const ids: string[] = (search.hits ?? [])
        .map((h: { _id: string | number }) => String(h._id))
        .filter((id: string) => !seen.has(id))
        .slice(0, Math.max(0, MAX_LABEL_FETCHES - seen.size));
      ids.forEach((id) => seen.add(id));

      const labels = await Promise.all(ids.map((id) => getJson(`${DSLD}/label/${id}`)));
      matched = labels.filter((l) => sameBarcode(l.upcSku, barcode)).map(mapLabel);
      if (matched.length > 0 || seen.size >= MAX_LABEL_FETCHES) break;
    }

    return json({
      found: matched.length > 0,
      retrievedAt: new Date().toISOString(),
      source: { name: "NIH DSLD", url: "https://dsld.od.nih.gov/", apiVersion: "v9" },
      payloadHash: await sha256Hex(JSON.stringify(matched)),
      labels: matched,
    }, 200);
  } catch (e) {
    const message = String(e);
    return json({ error: message.includes("DSLD returned") ? "dsld_error" : "internal_error", message }, 502);
  }
});

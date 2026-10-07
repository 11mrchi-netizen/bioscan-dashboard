// supplement-suppco-lookup — DAV-360
//
// Read-only lookup against SuppCo's documented public API (api.supp.co, no key).
// POST {barcode} -> GET /api/products?upcs=...   POST {query} -> GET /api/products?query=...
// Returns a compact mapped record plus retrieval metadata; it stores nothing.
// The app keeps a per-user snapshot for its owner's own pantry and verification.
//
// Terms (https://supp.co/about/terms-of-use, section 5.3): scraping web pages and
// commercial exploitation are prohibited; use is licensed for personal purposes;
// the API itself is not addressed. This uses only the documented API, on explicit
// user action, for a single user's own data -- no polling, no redistribution.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { type CompactProduct, exactUpcMatches, mapProduct, upcCandidates } from "./suppco.ts";

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

const API = "https://api.supp.co/api/products";
const MAX_RESULTS = 10;

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

    let body: { barcode?: string; query?: string };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    let url: string;
    let upcs: string[] = [];
    if (body.barcode) {
      upcs = upcCandidates(body.barcode);
      if (upcs.length === 0) return json({ error: "invalid_barcode" }, 400);
      url = `${API}?upcs=${encodeURIComponent(upcs.join(","))}`;
    } else if (body.query) {
      const query = body.query.trim().slice(0, 80);
      if (query.length < 3) return json({ error: "query_too_short" }, 400);
      url = `${API}?query=${encodeURIComponent(query)}`;
    } else {
      return json({ error: "missing_barcode_or_query" }, 400);
    }

    const res = await fetch(url, {
      headers: { "User-Agent": "FieldTerminal/1.0 (personal use)", Accept: "application/json" },
      signal: AbortSignal.timeout(10_000),
    });
    if (!res.ok) return json({ error: "suppco_error", message: `SuppCo returned ${res.status}` }, 502);

    const raw = await res.json();
    const list = Array.isArray(raw) ? raw : (raw?.products ?? []);
    const mapped: CompactProduct[] = list.slice(0, MAX_RESULTS).map(mapProduct);
    // A barcode lookup keeps only exact-UPC listings; a name search returns candidates as-is.
    const products = upcs.length > 0 ? exactUpcMatches(mapped, upcs) : mapped;

    return json({
      found: products.length > 0,
      retrievedAt: new Date().toISOString(),
      source: { name: "SuppCo", url: "https://supp.co", apiVersion: "public-api" },
      payloadHash: await sha256Hex(JSON.stringify(products)),
      products,
    }, 200);
  } catch (e) {
    const message = String(e);
    return json({ error: "internal_error", message }, 502);
  }
});

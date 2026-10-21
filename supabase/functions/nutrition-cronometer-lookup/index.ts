// nutrition-cronometer-lookup — the app's food lookup (Cronometer + an independent Gemini estimate).
//
//   { items: [{ description, quantity_g?, food_id? }] }  direct lookup of each item, or of the given Cronometer food_id
//   { description: "one apple" | "Pan-seared steak with brown rice..." }  a single food or a whole meal
//
// The lookup itself lives in ../_shared/nutrition-lookup (shared with nutrition-backfill); this file is the signed-in
// user's HTTP entry point. Secrets: CRONOMETER_EMAIL, CRONOMETER_PASSWORD, GEMINI_API_KEY (existing).

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { cronoLogin, isFailure, lookupDescription, lookupItems, memoryLoader } from "../_shared/nutrition-lookup/lookup.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // Auth gate — same pattern as every other nutrition function
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

    const cronoEmail = Deno.env.get("CRONOMETER_EMAIL");
    const cronoPassword = Deno.env.get("CRONOMETER_PASSWORD");
    if (!cronoEmail || !cronoPassword) {
      return json({
        error: "not_configured",
        message: "CRONOMETER_EMAIL and CRONOMETER_PASSWORD must be set as Edge Function secrets.",
      }, 500);
    }

    let body: {
      items?: Array<{ description: string; quantity_g?: number; food_id?: number }>;
      description?: string;
    };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    const ctx = {
      session: await cronoLogin(cronoEmail, cronoPassword),
      geminiKey: Deno.env.get("GEMINI_API_KEY"),
      loadMemory: memoryLoader(callerClient, user.id),
    };

    if (body.items && Array.isArray(body.items) && body.items.length > 0) {
      return json(await lookupItems(ctx, body.items), 200);
    }

    if (body.description?.trim()) {
      const result = await lookupDescription(ctx, body.description);
      if (isFailure(result)) return json({ error: result.error, message: result.message }, result.status);
      return json(result, 200);
    }

    return json({ error: "missing_input", message: "Provide either 'items' or 'description'." }, 400);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});

// nutrition-barcode-lookup — DAV-165
//
// Server-side barcode lookup against Open Food Facts (free, keyless API).
// foods itself IS the cache: a barcode already resolved once is looked up
// via (food_source_id, source_food_id=barcode) -- the same unique
// constraint DAV-161 created for every other food source -- before ever
// calling out to Open Food Facts again.
//
// Reference tables (food_sources/foods/food_nutrients/food_servings) are
// SELECT-only under RLS for authenticated users (verified directly against
// pg_policies -- no INSERT policy exists), so writing a newly-resolved
// product uses the service-role client, same dual-client pattern quick-log
// already established: the anon-key client only confirms the caller holds a
// real session, the service-role client does the actual catalog write.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

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

const SOURCE_NAME = "openfoodfacts";

// Canonical food_nutrients.nutrient names -- the same vocabulary
// domain/NutritionResolver.kt reads (DAV-164) -- mapped from Open Food
// Facts' own per-100g nutriment field names.
const NUTRIENT_FIELDS: Record<string, { field: string; unit: string; scale?: number }> = {
  calories: { field: "energy-kcal_100g", unit: "kcal" },
  protein: { field: "proteins_100g", unit: "g" },
  fat: { field: "fat_100g", unit: "g" },
  carbs: { field: "carbohydrates_100g", unit: "g" },
  fiber: { field: "fiber_100g", unit: "g" },
  sugar: { field: "sugars_100g", unit: "g" },
  // Open Food Facts reports sodium in g/100g; this schema stores sodium in
  // mg (matches meal_items.sodium_mg / every other source's convention).
  sodium: { field: "sodium_100g", unit: "mg", scale: 1000 },
  caffeine: { field: "caffeine_100g", unit: "mg", scale: 1000 },
};

// Open Food Facts' categories_tags are broad and inconsistent; this only
// classifies the unambiguous cases relevant to DAV-181's beverage_class
// vocabulary. An unmatched product stays non-beverage rather than guessed.
function beverageClassFromCategories(categoriesTags: string[] | undefined): string | null {
  if (!categoriesTags || categoriesTags.length === 0) return null;
  const has = (needle: string) => categoriesTags.some((t) => t.includes(needle));
  if (has("waters")) return "water";
  if (has("coffees")) return "coffee";
  if (has("teas")) return "tea";
  if (has("plant-based-milk") || has("plant-milks")) return "plant_milk";
  if (has("milks") || has("dairy-drinks")) return "milk";
  if (has("sodas") || has("carbonated-drinks")) return "soda";
  if (has("fruit-juices") || has("juices")) return "juice";
  if (has("sports-drinks") || has("energy-drinks")) return "electrolyte";
  if (has("alcoholic-beverages") || has("beers") || has("wines")) return "alcohol";
  return null;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    // --- Auth: confirm a real session, never accept anonymous calls ---
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
    if (!barcode) return json({ error: "missing_barcode" }, 400);

    // --- All catalog reads/writes use the service-role client: foods/
    // food_nutrients/food_sources/food_servings are shared reference data,
    // SELECT-only for authenticated users under RLS by design. ---
    const db = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    let { data: source, error: sourceError } = await db
      .from("food_sources")
      .select("id")
      .eq("name", SOURCE_NAME)
      .maybeSingle();
    if (sourceError) return json({ error: "db_error", message: sourceError.message }, 500);

    if (!source) {
      const { data: insertedSource, error: insertSourceError } = await db
        .from("food_sources")
        .insert({
          name: SOURCE_NAME,
          license: "Open Database License (ODbL) v1.0",
          attribution:
            "Product data from Open Food Facts (openfoodfacts.org), a collaborative, free and open database.",
          url: "https://world.openfoodfacts.org",
        })
        .select("id")
        .single();
      if (insertSourceError) return json({ error: "db_error", message: insertSourceError.message }, 500);
      source = insertedSource;
    }
    const sourceId = source!.id as number;

    // --- Cache check: foods IS the cache. ---
    const { data: cached, error: cacheError } = await db
      .from("foods")
      .select("id, name, brand, barcode, category, beverage_class, beverage_subtype")
      .eq("food_source_id", sourceId)
      .eq("source_food_id", barcode)
      .maybeSingle();
    if (cacheError) return json({ error: "db_error", message: cacheError.message }, 500);
    if (cached) return json({ found: true, cached: true, food: cached }, 200);

    // --- Live lookup ---
    const offRes = await fetch(
      `https://world.openfoodfacts.org/api/v2/product/${encodeURIComponent(barcode)}.json`,
    );
    if (!offRes.ok) {
      return json({ error: "openfoodfacts_error", message: `Open Food Facts returned ${offRes.status}` }, 502);
    }
    const offBody = await offRes.json();
    if (offBody.status !== 1 || !offBody.product) {
      // Real "not found" -- never fabricate a canonical record for an
      // unrecognized barcode; the caller falls back to manual entry.
      return json({ found: false }, 200);
    }

    const product = offBody.product;
    const nutriments = product.nutriments ?? {};
    const beverageClass = beverageClassFromCategories(product.categories_tags);

    const { data: insertedFood, error: insertFoodError } = await db
      .from("foods")
      .insert({
        food_source_id: sourceId,
        source_food_id: barcode,
        name: product.product_name || product.generic_name || `Product ${barcode}`,
        brand: product.brands ?? null,
        barcode,
        category: product.categories_tags?.[0]?.replace(/^\w+:/, "") ?? null,
        beverage_class: beverageClass,
      })
      .select("id, name, brand, barcode, category, beverage_class, beverage_subtype")
      .single();
    if (insertFoodError) return json({ error: "db_error", message: insertFoodError.message }, 500);

    // Open Food Facts' "_100g"-suffixed fields are per-100-of-whatever-the-
    // product-actually-is -- per 100ml for a liquid, despite the field name.
    // product_quantity_unit (confirmed against real data: Coca-Cola reports
    // "ml" here) is what actually disambiguates, not the field name.
    const isLiquid = product.product_quantity_unit === "ml" || product.quantity_unit === "ml";
    const nutrientBasisUnit = isLiquid ? "ml" : "g";

    // Only nutrients Open Food Facts actually reported get a row -- missing
    // panel data stays missing, never fabricated as 0 (DAV-165's own rule).
    const nutrientRows = [];
    for (const [canonicalName, spec] of Object.entries(NUTRIENT_FIELDS)) {
      const raw = nutriments[spec.field];
      if (typeof raw !== "number") continue;
      const amount = spec.scale ? raw * spec.scale : raw;
      nutrientRows.push({
        food_id: insertedFood.id,
        nutrient: canonicalName,
        amount,
        unit: spec.unit,
        basis_qty: 100,
        basis_unit: nutrientBasisUnit,
      });
    }
    if (nutrientRows.length > 0) {
      const { error: nutrientError } = await db.from("food_nutrients").insert(nutrientRows);
      if (nutrientError) return json({ error: "db_error", message: nutrientError.message }, 500);
    }

    // serving_quantity is Open Food Facts' own parsed numeric equivalent for
    // serving_size's free text (e.g. "30 g", "1 bottle (500ml)") -- only
    // recorded when it could confidently parse one. serving_quantity_unit
    // decides which schema column it belongs in (a liquid serving's amount
    // is meaningless as "grams").
    if (product.serving_size && typeof product.serving_quantity === "number") {
      const servingIsLiquid = product.serving_quantity_unit === "ml" || isLiquid;
      const { error: servingError } = await db.from("food_servings").insert({
        food_id: insertedFood.id,
        serving_name: product.serving_size,
        ...(servingIsLiquid ? { ml: product.serving_quantity } : { grams: product.serving_quantity }),
      });
      if (servingError) return json({ error: "db_error", message: servingError.message }, 500);
    }

    return json(
      { found: true, cached: false, food: insertedFood, nutrientCount: nutrientRows.length },
      200,
    );
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});

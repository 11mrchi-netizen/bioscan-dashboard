// nutrition-barcode-lookup — DAV-165, plus label scans for products no database knows (most Taiwanese ones).
//
//   { barcode }                                       the product for a barcode: the app catalog first (a label you
//                                                     scanned wins), else Open Food Facts, cached into the catalog
//   { read_label: { images: [base64], mime_type? } }  read a nutrition label photo (營養標示 or any per-serving /
//                                                     per-100 table) into numbers to confirm; nothing is stored
//   { save_label: { barcode, name, brand?, label } }  store a confirmed label as that barcode's product (source
//                                                     label_scan), so the next scan is instant
//
// Responses for a product: { found, cached?, food, needs_label } where needs_label means the product has no calories
// (unknown barcode, or an Open Food Facts entry without a nutrition table): the app then offers a label scan.
//
// foods IS the cache (unique food_source_id + source_food_id). Reference tables are SELECT-only under RLS for users, so
// writes use the service-role client; the anon-key client only confirms the caller holds a real session.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2";
import { LABEL_UNITS, labelPerServing, normalizeLabel, type ScannedLabel } from "../_shared/nutrition-lookup/label.ts";
import { geminiParts } from "../_shared/nutrition-lookup/lookup.ts";

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

const OFF_SOURCE = "openfoodfacts";
const LABEL_SOURCE = "label_scan";
const FOOD_COLUMNS = "id, name, name_zh, brand, barcode, category, beverage_class, beverage_subtype";

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

const CJK = /[㐀-鿿豈-﫿]/;

// The same product is printed as UPC-A (12 digits) or EAN-13 (with a leading 0) depending on who stored it.
function barcodeForms(code: string): string[] {
  const digits = code.replace(/\D/g, "");
  const forms = [code, digits];
  if (digits.length === 12) forms.push(`0${digits}`);
  if (digits.length === 13 && digits.startsWith("0")) forms.push(digits.slice(1));
  return [...new Set(forms.filter(Boolean))];
}

async function sourceId(db: SupabaseClient, name: string, defaults: Record<string, string>): Promise<number> {
  const { data, error } = await db.from("food_sources").select("id").eq("name", name).maybeSingle();
  if (error) throw new Error(error.message);
  if (data) return data.id as number;
  const { data: inserted, error: insertError } = await db.from("food_sources").insert({ name, ...defaults }).select("id").single();
  if (insertError) throw new Error(insertError.message);
  return inserted.id as number;
}

async function hasCalories(db: SupabaseClient, foodId: number): Promise<boolean> {
  const { count } = await db.from("food_nutrients").select("id", { count: "exact", head: true }).eq("food_id", foodId).eq("nutrient", "calories");
  return (count ?? 0) > 0;
}

async function lookupBarcode(db: SupabaseClient, barcode: string) {
  const forms = barcodeForms(barcode);

  // --- Cache: any catalog product with this barcode; a label the user scanned beats Open Food Facts. ---
  const { data: hits, error: cacheError } = await db.from("foods").select(`${FOOD_COLUMNS}, food_sources(name)`).in("barcode", forms);
  if (cacheError) throw new Error(cacheError.message);
  const rank = (s: string | undefined) => (s === LABEL_SOURCE ? 0 : s === OFF_SOURCE ? 1 : 2);
  const cached = ((hits ?? []) as unknown as Array<Record<string, unknown> & { id: number; food_sources: { name: string } | null }>)
    .sort((a, b) => rank(a.food_sources?.name) - rank(b.food_sources?.name))[0];
  if (cached) {
    const { food_sources, ...food } = cached;
    return { found: true, cached: true, food, source: food_sources?.name ?? null, needs_label: !(await hasCalories(db, cached.id)) };
  }

  // --- Live lookup: Open Food Facts, each form until one is known ---
  let product: Record<string, any> | null = null;
  for (const form of forms) {
    const offRes = await fetch(`https://world.openfoodfacts.org/api/v2/product/${encodeURIComponent(form)}.json`, {
      signal: AbortSignal.timeout(12000),
    });
    if (!offRes.ok) {
      if (offRes.status === 404) continue;
      throw new Error(`Open Food Facts returned ${offRes.status}`);
    }
    const offBody = await offRes.json();
    if (offBody.status === 1 && offBody.product) {
      product = offBody.product;
      break;
    }
  }
  // Real "not found": never fabricate a record; the app offers a label scan.
  if (!product) return { found: false, needs_label: true };

  const srcId = await sourceId(db, OFF_SOURCE, {
    license: "Open Database License (ODbL) v1.0",
    attribution: "Product data from Open Food Facts (openfoodfacts.org), a collaborative, free and open database.",
    url: "https://world.openfoodfacts.org",
  });
  const nutriments = product.nutriments ?? {};
  const name = product.product_name || product.generic_name || `Product ${barcode}`;
  const nameZh = product.product_name_zh || product["product_name_zh-tw"] || (CJK.test(name) ? name : null);

  const { data: insertedFood, error: insertFoodError } = await db
    .from("foods")
    .insert({
      food_source_id: srcId,
      source_food_id: barcode,
      name,
      name_zh: nameZh,
      brand: product.brands ?? null,
      barcode,
      category: product.categories_tags?.[0]?.replace(/^\w+:/, "") ?? null,
      beverage_class: beverageClassFromCategories(product.categories_tags),
    })
    .select(FOOD_COLUMNS)
    .single();
  if (insertFoodError) throw new Error(insertFoodError.message);

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
    nutrientRows.push({
      food_id: insertedFood.id,
      nutrient: canonicalName,
      amount: spec.scale ? raw * spec.scale : raw,
      unit: spec.unit,
      basis_qty: 100,
      basis_unit: nutrientBasisUnit,
    });
  }
  if (nutrientRows.length > 0) {
    const { error: nutrientError } = await db.from("food_nutrients").insert(nutrientRows);
    if (nutrientError) throw new Error(nutrientError.message);
  }

  // The label serving (serving_quantity is OFF's own parse of serving_size) and the whole package
  // (product_quantity), so the app can offer "1 serving", "whole bottle", "half the bag".
  const servingRows: Array<Record<string, unknown>> = [];
  const asAmount = (qty: number, liquid: boolean) => (liquid ? { ml: qty } : { grams: qty });
  if (product.serving_size && typeof product.serving_quantity === "number" && product.serving_quantity > 0) {
    servingRows.push({
      food_id: insertedFood.id,
      serving_name: product.serving_size,
      ...asAmount(product.serving_quantity, product.serving_quantity_unit === "ml" || isLiquid),
    });
  }
  const packageQty = Number(product.product_quantity);
  if (packageQty > 0 && packageQty !== product.serving_quantity) {
    servingRows.push({
      food_id: insertedFood.id,
      serving_name: `Whole package (${packageQty} ${isLiquid ? "ml" : "g"})`,
      ...asAmount(packageQty, isLiquid),
    });
  }
  if (servingRows.length > 0) {
    const { error: servingError } = await db.from("food_servings").insert(servingRows);
    if (servingError) throw new Error(servingError.message);
  }

  return {
    found: true,
    cached: false,
    food: insertedFood,
    source: OFF_SOURCE,
    needs_label: !nutrientRows.some((r) => r.nutrient === "calories"),
    nutrientCount: nutrientRows.length,
  };
}

// ---------------------------------------------------------------------------
// Label scans
// ---------------------------------------------------------------------------

const LABEL_PROMPT = `This photo shows the nutrition facts table of a packaged food. It is often a Taiwanese 營養標示 in Traditional Chinese, but may be in any language.

Copy exactly what is printed; never estimate, calculate or fill in a value that is not printed (use null).
- product_name: the product name if visible on the package (else null)
- serving_size and serving_unit: 每一份量 (or "serving size"), in g (公克) or ml (毫升)
- servings_per_package: 本包裝含 N 份 (or "servings per container")
- per_serving: the 每份 column; per_100: the 每100公克 / 每100毫升 column (null when that column is not printed)
- energy_unit: "kcal" for 大卡 / kcal / Cal, "kJ" only if energy is printed only in kilojoules
- fields: calories (熱量), protein_g (蛋白質), fat_g (脂肪), saturated_fat_g (飽和脂肪), trans_fat_g (反式脂肪), carbs_g (碳水化合物), sugar_g (糖), fiber_g (膳食纖維), sodium_mg (鈉, in mg as printed)`;

const NULLABLE_NUMBER = { type: "NUMBER", nullable: true };
const VALUES_SCHEMA = {
  type: "OBJECT",
  nullable: true,
  properties: {
    calories: NULLABLE_NUMBER,
    protein_g: NULLABLE_NUMBER,
    fat_g: NULLABLE_NUMBER,
    saturated_fat_g: NULLABLE_NUMBER,
    trans_fat_g: NULLABLE_NUMBER,
    carbs_g: NULLABLE_NUMBER,
    sugar_g: NULLABLE_NUMBER,
    fiber_g: NULLABLE_NUMBER,
    sodium_mg: NULLABLE_NUMBER,
  },
};
const LABEL_SCHEMA = {
  type: "OBJECT",
  properties: {
    product_name: { type: "STRING", nullable: true },
    serving_size: NULLABLE_NUMBER,
    serving_unit: { type: "STRING", enum: ["g", "ml"], nullable: true },
    servings_per_package: NULLABLE_NUMBER,
    energy_unit: { type: "STRING", enum: ["kcal", "kJ"], nullable: true },
    per_serving: VALUES_SCHEMA,
    per_100: VALUES_SCHEMA,
  },
  required: ["serving_size", "per_serving", "per_100"],
};

function labelReply(label: ScannedLabel) {
  const normalized = normalizeLabel(label);
  return { label, normalized, per_serving: labelPerServing(normalized) };
}

async function saveLabel(db: SupabaseClient, userId: string, input: { barcode?: string; name?: string; brand?: string | null; label?: ScannedLabel }) {
  const barcode = input.barcode?.replace(/\D/g, "");
  const name = input.name?.trim();
  if (!barcode || !name || !input.label) return { status: 400, body: { error: "missing_input", message: "barcode, name and label are required." } };

  const normalized = normalizeLabel(input.label);
  if (normalized.per100.calories == null) {
    return { status: 400, body: { error: "invalid_label", message: "The label needs calories.", checks: normalized.checks } };
  }

  const srcId = await sourceId(db, LABEL_SOURCE, {
    license: "User-scanned package label",
    attribution: "Nutrition label photographed and confirmed by a user of this app.",
  });
  const { data: food, error: foodError } = await db
    .from("foods")
    .upsert({
      food_source_id: srcId,
      source_food_id: barcode,
      name,
      name_zh: CJK.test(name) ? name : null,
      brand: input.brand?.trim() || null,
      barcode,
      contributed_by: userId,
      updated_at: new Date().toISOString(),
    }, { onConflict: "food_source_id,source_food_id" })
    .select(FOOD_COLUMNS)
    .single();
  if (foodError) throw new Error(foodError.message);

  // A re-scan replaces the previous numbers and servings for this barcode.
  await db.from("food_nutrients").delete().eq("food_id", food.id);
  const nutrientRows = Object.entries(normalized.per100)
    .filter(([k]) => LABEL_UNITS[k])
    .map(([nutrient, amount]) => ({ food_id: food.id, nutrient, amount, unit: LABEL_UNITS[nutrient], basis_qty: 100, basis_unit: normalized.basisUnit }));
  const { error: nutrientError } = await db.from("food_nutrients").insert(nutrientRows);
  if (nutrientError) throw new Error(nutrientError.message);

  await db.from("food_servings").delete().eq("food_id", food.id);
  const servingRows = [normalized.serving, normalized.wholePackage]
    .filter((s): s is { name: string; grams: number } => s != null)
    .map((s) => ({ food_id: food.id, serving_name: s.name, ...(normalized.basisUnit === "ml" ? { ml: s.grams } : { grams: s.grams }) }));
  if (servingRows.length > 0) {
    const { error: servingError } = await db.from("food_servings").insert(servingRows);
    if (servingError) throw new Error(servingError.message);
  }

  return { status: 200, body: { found: true, cached: true, food, source: LABEL_SOURCE, needs_label: false, checks: normalized.checks } };
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

    let body: {
      barcode?: string;
      read_label?: { images?: string[]; mime_type?: string };
      save_label?: { barcode?: string; name?: string; brand?: string | null; label?: ScannedLabel };
    };
    try {
      body = await req.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    // --- All catalog reads/writes use the service-role client: foods/
    // food_nutrients/food_sources/food_servings are shared reference data,
    // SELECT-only for authenticated users under RLS by design. ---
    const db = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    );

    if (body.read_label) {
      const images = (body.read_label.images ?? []).filter((i) => typeof i === "string" && i.length > 0).slice(0, 3);
      if (images.length === 0) return json({ error: "missing_image" }, 400);
      const apiKey = Deno.env.get("GEMINI_API_KEY");
      if (!apiKey) return json({ error: "not_configured", message: "GEMINI_API_KEY is not set." }, 500);
      const mime = body.read_label.mime_type || "image/jpeg";
      const label = await geminiParts<ScannedLabel>(
        apiKey,
        [{ text: LABEL_PROMPT }, ...images.map((data) => ({ inline_data: { mime_type: mime, data } }))],
        LABEL_SCHEMA,
        30000,
        "read_label",
      );
      if (!label) {
        return json({ error: "label_unreadable", message: "Couldn't read the label right now -- type the numbers in instead." }, 502);
      }
      return json(labelReply(label), 200);
    }

    if (body.save_label) {
      const { status, body: reply } = await saveLabel(db, user.id, body.save_label);
      return json(reply, status);
    }

    const barcode = body.barcode?.trim();
    if (!barcode) return json({ error: "missing_barcode" }, 400);
    return json(await lookupBarcode(db, barcode), 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});

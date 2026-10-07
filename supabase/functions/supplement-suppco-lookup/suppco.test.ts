// Run: node supabase/functions/supplement-suppco-lookup/suppco.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { exactUpcMatches, mapProduct, parseServingSize, upcCandidates } from "./suppco.ts";

// --- parseServingSize ---
assert.deepEqual(parseServingSize("1 Tablet(s)"), { raw: "1 Tablet(s)", quantity: 1, unit: "Tablet(s)" });
assert.deepEqual(parseServingSize("2 Capsules"), { raw: "2 Capsules", quantity: 2, unit: "Capsules" });
assert.deepEqual(parseServingSize("1 Scoop (5g)"), { raw: "1 Scoop (5g)", quantity: 1, unit: "Scoop (5g)" });
assert.deepEqual(parseServingSize("5 G"), { raw: "5 G", quantity: 5, unit: "G" });
assert.deepEqual(parseServingSize("1.5 ml"), { raw: "1.5 ml", quantity: 1.5, unit: "ml" });
assert.deepEqual(parseServingSize("1/2 tsp"), { raw: "1/2 tsp", quantity: 0.5, unit: "tsp" });
assert.deepEqual(parseServingSize("1,000 mg"), { raw: "1,000 mg", quantity: 1000, unit: "mg" });
assert.deepEqual(parseServingSize("3"), { raw: "3", quantity: 3, unit: null });
// A range or non-numeric text is never given a guessed quantity.
assert.deepEqual(parseServingSize("1-2 capsules"), { raw: "1-2 capsules", quantity: null, unit: null });
assert.deepEqual(parseServingSize("1 to 2 capsules"), { raw: "1 to 2 capsules", quantity: null, unit: null });
assert.deepEqual(parseServingSize("Per bottle"), { raw: "Per bottle", quantity: null, unit: null });
assert.deepEqual(parseServingSize("  "), { raw: null, quantity: null, unit: null });
assert.deepEqual(parseServingSize(null), { raw: null, quantity: null, unit: null });

// --- upcCandidates ---
assert.deepEqual(upcCandidates("033674139417"), ["033674139417"]);
assert.deepEqual(upcCandidates("0 33674 13941 7"), ["033674139417"]);
assert.deepEqual(upcCandidates("0033674139417"), ["0033674139417", "033674139417"]);
assert.deepEqual(upcCandidates("12"), []);
// Placeholder / repeated-digit barcodes are rejected (SuppCo has junk listings under them).
assert.deepEqual(upcCandidates("000000000000"), []);
assert.deepEqual(upcCandidates("0 00000 00000 0"), []);
assert.deepEqual(upcCandidates("111111111111"), []);

// exactUpcMatches keeps only listings whose own upc equals a requested spelling.
const listings = [
  mapProduct({ name: "A", upc: "033674139417" }),
  mapProduct({ name: "B", upc: "033674139417" }),
  mapProduct({ name: "C", upc: "000000000000" }),
  mapProduct({ name: "D" }),
];
assert.deepEqual(exactUpcMatches(listings, ["0033674139417", "033674139417"]).map((p) => p.name), ["A", "B"]);
assert.deepEqual(exactUpcMatches(listings, ["999999999990"]), []);

// --- mapProduct ---
const mapped = mapProduct({
  id: "6de3ca14",
  slug: "natures-way-alive",
  name: "Alive! Women's 50+ Ultra Multivitamin",
  brand: "Nature's Way",
  upc: "033674139417",
  category_name: "Multivitamin",
  format: "tablet",
  serving_size: "1 Tablet(s)",
  servings_per_container: 150.0,
  suggested_use: "Women take 1 tablet daily, preferably with food.",
  validated: true,
  off_market: false,
  contains_proprietary_blend: true,
  product_trust_score: "8.438",
  brand_trust_score: "9.215",
  product_trust_score_status: "complete",
  product_trust_score_percentile_in_category: 84.8,
  trust_score_details: [{ category: "Testing Benchmarks", rank: "high" }, { category: 5 }],
  active_fda_recall: false,
  received_fda_warning_letter: true,
  resolved_fda_warning_letter: true,
  failed_any_tests: false,
  tested_by_suppco: false,
  heavy_metals_testing: "",
  usp_verified: true,
  non_gmo_certified: true,
  price: "29.99",
  price_per_serving: "0.1999",
  label_url: "https://example.test/label.jpg",
  product_ingredients: [
    { name: "Thiamine", amount: 10.0, units: "mg", nutrient_id: "vitamin-b1", category: "vitamin", from: "Thiamine Mononitrate" },
    { name: "Garlic", amount: 0.0, units: "", nutrient_id: "garlic", category: "other", from: "Bulb" },
    { name: "Bare" },
  ],
});
assert.equal(mapped.servingSize.quantity, 1);
assert.equal(mapped.servingsPerContainer, 150);
assert.equal(mapped.trust.product, 8.438);
assert.equal(mapped.trust.brand, 9.215);
assert.deepEqual(mapped.trust.details, [{ category: "Testing Benchmarks", rank: "high" }]);
assert.equal(mapped.safety.activeFdaRecall, false);
assert.equal(mapped.safety.fdaWarningLetter, false); // received but resolved
assert.equal(mapped.testing.heavyMetals, null); // empty string -> null
assert.deepEqual(mapped.testing.certifications, ["USP Verified", "Non-GMO certified"]);
assert.equal(mapped.price, 29.99);
// 0.0 means "present, amount not stated": null, never a fabricated 0 mg.
assert.deepEqual(mapped.ingredients[0], { name: "Thiamine", formOf: "Thiamine Mononitrate", category: "vitamin", nutrientId: "vitamin-b1", amount: 10, unit: "mg" });
assert.equal(mapped.ingredients[1].amount, null);
assert.equal(mapped.ingredients[1].unit, null);
assert.equal(mapped.ingredients[2].amount, null);

// An unresolved warning letter and an active recall are surfaced.
const flagged = mapProduct({ active_fda_recall: true, active_fda_recall_url: "https://fda.example/r", received_fda_warning_letter: true });
assert.equal(flagged.safety.activeFdaRecall, true);
assert.equal(flagged.safety.recallUrl, "https://fda.example/r");
assert.equal(flagged.safety.fdaWarningLetter, true);

// Missing everything still maps without throwing.
const empty = mapProduct({});
assert.equal(empty.name, null);
assert.deepEqual(empty.ingredients, []);
assert.deepEqual(empty.servingSize, { raw: null, quantity: null, unit: null });

console.log("suppco.test.ts: all assertions passed");

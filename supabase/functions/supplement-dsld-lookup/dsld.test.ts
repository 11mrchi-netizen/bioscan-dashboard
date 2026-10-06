// Run: node supabase/functions/supplement-dsld-lookup/dsld.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { barcodeRepresentations, mapLabel, sameBarcode } from "./dsld.ts";

// UPC-A: probes as-passed, digits-only and the GS1-spaced form DSLD stores.
assert.deepEqual(barcodeRepresentations("033674139417"), ["033674139417", "0 33674 13941 7"]);
assert.deepEqual(barcodeRepresentations("0 33674 13941 7"), ["0 33674 13941 7", "033674139417"]);

// EAN-13 with a leading zero also probes the UPC-A forms.
assert.deepEqual(barcodeRepresentations("0033674139417"), [
  "0033674139417",
  "033674139417",
  "0 33674 13941 7",
]);

// Short codes (UPC-E style) are probed as given, never padded or invented.
assert.deepEqual(barcodeRepresentations("80004843"), ["80004843"]);
assert.deepEqual(barcodeRepresentations("   "), []);

// A hit counts only when the label UPC equals the barcode, ignoring spacing and leading zeros.
assert.equal(sameBarcode("0 33674 13941 7", "033674139417"), true);
assert.equal(sameBarcode("0033674139417", "0 33674 13941 7"), true);
assert.equal(sameBarcode("0 33674 13941 7", "033674139418"), false);
assert.equal(sameBarcode(null, "033674139417"), false);
assert.equal(sameBarcode("", ""), false);

// mapLabel keeps amounts as reported and null when a row has no numeric quantity.
const mapped = mapLabel({
  id: 293248,
  fullName: "Alive! Multi",
  brandName: "Nature's Way",
  upcSku: "0 33674 13941 7",
  offMarket: 0,
  productVersionCode: "09-2025",
  servingSizes: [{ minQuantity: 1, maxQuantity: 1, unit: "Tablet(s)" }],
  ingredientRows: [
    { name: "Vitamin C", category: "vitamin", quantity: [{ quantity: 180, unit: "mg" }] },
    { name: "Proprietary Blend", category: "blend", quantity: [{ quantity: null, unit: "NP" }] },
    { name: "Bare Row", category: null },
  ],
});
assert.equal(mapped.dsldId, 293248);
assert.equal(mapped.offMarket, false);
assert.deepEqual(mapped.servingSize, { min: 1, max: 1, unit: "Tablet(s)" });
assert.deepEqual(mapped.ingredients[0], { name: "Vitamin C", category: "vitamin", amount: 180, unit: "mg" });
assert.equal(mapped.ingredients[1].amount, null);
assert.equal(mapped.ingredients[2].amount, null);
assert.equal(mapped.ingredients[2].unit, null);

console.log("dsld.test.ts: all assertions passed");

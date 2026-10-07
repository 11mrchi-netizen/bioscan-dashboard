// Run: node supabase/functions/supplement-dsld-lookup/dsld.test.ts  (Node 22+ strips types)
import assert from "node:assert/strict";
import { barcodeRepresentations, mapHit, mapLabel, rankLabels, sameBarcode } from "./dsld.ts";

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

// New label fields: entryDate, servingsPerContainer (string -> number), netContents, form.
const full = mapLabel({
  id: 1, entryDate: "2025-05-21", offMarket: 0, servingsPerContainer: "150",
  netContents: [{ display: "150 Tablet(s)" }], physicalState: { langualCodeDescription: "Tablet or Pill" },
});
assert.equal(full.entryDate, "2025-05-21");
assert.equal(full.servingsPerContainer, 150);
assert.equal(full.netContents, "150 Tablet(s)");
assert.equal(full.form, "Tablet or Pill");
assert.equal(mapLabel({ id: 2, servingsPerContainer: "0" }).servingsPerContainer, null);
assert.equal(mapLabel({ id: 3, servingsPerContainer: "n/a" }).servingsPerContainer, null);
assert.equal(mapLabel({ id: 4 }).netContents, null);

// rankLabels: on-market first, then newest; unknown offMarket between; input untouched.
const versions = [
  { id: "a", offMarket: true, entryDate: "2024-08-22" },
  { id: "b", offMarket: false, entryDate: "2025-05-21" },
  { id: "c", offMarket: true, entryDate: "2023-02-21" },
  { id: "d", offMarket: null, entryDate: "2025-12-01" },
  { id: "e", offMarket: false, entryDate: "2024-01-01" },
];
assert.deepEqual(rankLabels(versions).map((v) => v.id), ["b", "e", "d", "a", "c"]);
assert.equal(versions[0].id, "a");
assert.deepEqual(rankLabels([]), []);

// mapHit: search-hit _source -> candidate (no UPC at this stage).
const cand = mapHit({
  _id: "270262",
  _source: { fullName: "Boron 3 mg", brandName: "Nutricost", offMarket: 0, entryDate: "2024-03-01",
    netContents: [{ display: "240 Capsule(s)" }], physicalState: { langualCodeDescription: "Capsule" } },
});
assert.deepEqual(cand, {
  dsldId: 270262, fullName: "Boron 3 mg", brandName: "Nutricost", offMarket: false,
  entryDate: "2024-03-01", netContents: "240 Capsule(s)", form: "Capsule",
});
assert.equal(mapHit({ _id: "1" }).fullName, null);

console.log("dsld.test.ts: all assertions passed");

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  compactLabel, conceptFromProperties, extractHalfLife, ingredientsFromRelated, pubmedItems, rankLabels,
  rxcuiFromApproximate, rxcuiFromIdGroup, searchTerm,
} from "./reference.ts";

const fixture = JSON.parse(readFileSync(new URL("./fixture-tadalafil-label.json", import.meta.url), "utf8"));

test("a real tadalafil label keeps its sections and states its half-life", () => {
  const c = compactLabel(fixture.results[0]);
  assert.equal(c.setId, "00cbc656-768d-4995-bb6d-a0d4b32a248c");
  assert.equal(c.generic?.toLowerCase(), "tadalafil");
  assert.ok(c.sections.drug_interactions.includes("nitrates"));
  assert.ok(c.sections.pharmacokinetics);
  assert.equal(c.halfLife?.hours, 17.5);
  assert.ok(c.halfLife?.text.includes("half-life is 17.5 hours"));
  assert.ok(c.url.startsWith("https://dailymed.nlm.nih.gov/"));
});

test("half-lives in other words and units", () => {
  assert.equal(extractHalfLife("The elimination half-life of semaglutide is approximately 1 week")?.hours, 168);
  assert.equal(extractHalfLife("terminal half-life of approximately 7 days")?.hours, 168);
  assert.equal(extractHalfLife("Half-life: 4 to 6 hours.")?.hours, 5);
  assert.equal(extractHalfLife("a half-life of 30 minutes")?.hours, 0.5);
  assert.equal(extractHalfLife("no number here"), null);
  // Testosterone cypionate's label: an earlier "half-life." with no number is passed over; the number is a word.
  assert.equal(extractHalfLife("will determine its half-life. About 90 percent... The half-life of testosterone cypionate when injected intramuscularly is approximately eight days.")?.hours, 192);
});

test("RxNorm and PubMed shapes", () => {
  assert.equal(rxcuiFromIdGroup({ idGroup: { rxnormId: ["358263"] } }), "358263");
  assert.equal(rxcuiFromIdGroup({ idGroup: {} }), null);
  assert.equal(rxcuiFromApproximate({ approximateGroup: { candidate: [{ rxcui: "9", rank: "2" }, { rxcui: "1991302", rank: "1" }] } }), "1991302");
  assert.deepEqual(conceptFromProperties({ properties: { rxcui: "358263", name: "tadalafil", tty: "IN" } }), { rxcui: "358263", name: "tadalafil", tty: "IN" });
  assert.deepEqual(ingredientsFromRelated({ relatedGroup: { conceptGroup: [{ tty: "IN", conceptProperties: [{ rxcui: "1", name: "x", tty: "IN" }] }] } }), [{ rxcui: "1", name: "x", tty: "IN" }]);
  const items = pubmedItems({ result: { uids: ["2", "1"], "1": { uid: "1", title: "A", source: "J", pubdate: "2020 Jan" }, "2": { uid: "2", title: "B", pubdate: "2019" } } }, ["2", "1"]);
  assert.deepEqual(items.map((i) => [i.pmid, i.year]), [["2", "2019"], ["1", "2020"]]);
});

test("labels newest first, one per set id; names trimmed for search", () => {
  const ranked = rankLabels([{ set_id: "a", effective_time: "20200101" }, { set_id: "b", effective_time: "20250101" }, { set_id: "a", effective_time: "20200101" }, {},
    { set_id: "rx", effective_time: "20100101", openfda: { product_type: ["HUMAN PRESCRIPTION DRUG"] } }]);
  assert.deepEqual(ranked.map((r) => r.setId), ["rx", "b", "a"]);
  assert.equal(searchTerm("Tadalafil 2.5 mg (Cialis)"), "Tadalafil");
  assert.equal(searchTerm("Vitamin D3 (Now Food)"), "Vitamin D3");
});

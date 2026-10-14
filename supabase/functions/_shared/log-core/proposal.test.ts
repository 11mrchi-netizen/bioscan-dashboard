import assert from "node:assert/strict";
import { test } from "node:test";
import { buildProposals, ethanolGrams, groundedNumbers, matchRoster, shiftDate, type Ctx, type RawEntry } from "./proposal.ts";

const ctx: Ctx = {
  nowLocalIso: "2026-10-12T21:05:00+08:00",
  roster: [
    { id: 1, name: "Magnesium glycinate" },
    { id: 2, name: "Vitamin D3" },
    { id: 3, name: "Vitamin C" },
    { id: 4, name: "Creatine" },
  ],
};
const run = (text: string, entries: RawEntry[]) => buildProposals(text, entries, ctx);

test("grounding reads numerals, decimal commas, number words and 'a litre'", () => {
  const n = groundedNumbers("had 0,5 l and twenty five minutes, two beers at 5%, half a pint, a litre of water, 7/10");
  for (const v of [0.5, 25, 20, 5, 2, 7, 10, 1]) assert.ok(n.has(v), String(v));
  assert.ok(!groundedNumbers("a coffee").has(1), "'a' before a non-unit word is not the number 1");
});

test("beer with a stated strength becomes grams of ethanol", () => {
  const { proposals } = run("a 500 ml beer at 5%", [{ kind: "drink", beverage_class: "alcohol", volume_value: 500, volume_unit: "ml", abv_percent: 5 }]);
  const p = proposals[0];
  assert.equal(p.kind, "drink");
  if (p.kind === "drink") {
    assert.equal(p.volume_ml, 500);
    assert.equal(p.alcohol_g, 19.7);
    assert.deepEqual(p.needs, []);
  }
});

test("alcohol without a stated strength is a question, never a guessed grams value", () => {
  const { proposals } = run("a 500 ml beer", [{ kind: "drink", beverage_class: "alcohol", volume_value: 500, volume_unit: "ml", abv_percent: 5 }]);
  const p = proposals[0];
  if (p.kind !== "drink") throw new Error("kind");
  assert.equal(p.abv_percent, null, "model-invented strength is dropped");
  assert.equal(p.alcohol_g, null);
  assert.deepEqual(p.needs, ["abv_percent"]);
});

test("ungrounded volume, unknown unit and count are not invented", () => {
  const a = run("a glass of wine at 12%", [{ kind: "drink", beverage_class: "alcohol", volume_value: 150, volume_unit: "ml", abv_percent: 12 }]).proposals[0];
  if (a.kind !== "drink") throw new Error("kind");
  assert.equal(a.volume_ml, null);
  assert.deepEqual(a.needs, ["volume_ml"]);
  const b = run("a pint of beer 5%", [{ kind: "drink", beverage_class: "alcohol", volume_value: 1, volume_unit: "pint", abv_percent: 5 }]).proposals[0];
  if (b.kind !== "drink") throw new Error("kind");
  assert.equal(b.volume_ml, null, "a pint is not a fixed volume here");
  const c = run("a couple of 250 ml coffees", [{ kind: "drink", beverage_class: "coffee", count: 2, volume_value: 250, volume_unit: "ml" }]).proposals[0];
  if (c.kind !== "drink") throw new Error("kind");
  assert.equal(c.volume_ml, 250);
  assert.ok(c.warnings.some((w) => w.includes("count")));
});

test("count multiplies a stated volume; units convert once", () => {
  const p = run("two 330 ml colas", [{ kind: "drink", beverage_class: "soda", count: 2, volume_value: 330, volume_unit: "ml" }]).proposals[0];
  if (p.kind !== "drink") throw new Error("kind");
  assert.equal(p.volume_ml, 660);
  const q = run("1.5 l of water", [{ kind: "drink", beverage_class: "water", volume_value: 1.5, volume_unit: "L" }]).proposals[0];
  if (q.kind !== "drink") throw new Error("kind");
  assert.equal(q.volume_ml, 1500);
  const r = run("12 fl oz of juice", [{ kind: "drink", beverage_class: "juice", volume_value: 12, volume_unit: "fl oz" }]).proposals[0];
  if (r.kind !== "drink") throw new Error("kind");
  assert.equal(r.volume_ml, 355);
});

test("unknown drink class goes to unparsed, not to a row", () => {
  const { proposals, unparsed } = run("some kombucha", [{ kind: "drink", beverage_class: "kombucha", quote: "some kombucha" }]);
  assert.equal(proposals.length, 0);
  assert.equal(unparsed[0].reason, "unknown drink type");
});

test("wellbeing keeps only stated scores and asks for the rest", () => {
  const { proposals } = run("energy 7 today, mood is good", [
    { kind: "wellbeing", mentioned: ["energy", "mood"], energy: 7, mood: 8 },
  ]);
  const p = proposals[0];
  if (p.kind !== "wellbeing") throw new Error("kind");
  assert.equal(p.energy, 7);
  assert.equal(p.mood, null, "8 is not in the utterance");
  assert.deepEqual(p.needs, ["mood"]);
  assert.equal(p.date, "2026-10-12");
});

test("wellbeing with nothing usable is unparsed", () => {
  const { proposals, unparsed } = run("fine I guess", [{ kind: "wellbeing", mentioned: [] }]);
  assert.equal(proposals.length, 0);
  assert.equal(unparsed.length, 1);
});

test("days and times resolve from the client clock", () => {
  const y = run("yesterday at 18:30 beer 5% 500 ml", [{ kind: "drink", beverage_class: "alcohol", day_offset: -1, time_hhmm: "18:30", volume_value: 500, volume_unit: "ml", abv_percent: 5 }]).proposals[0];
  if (y.kind !== "drink") throw new Error("kind");
  assert.equal(y.logged_at, "2026-10-11T18:30:00+08:00");
  assert.deepEqual(y.warnings, []);
  const noTime = run("yesterday sauna 20 min", [{ kind: "recovery", day_offset: -1, recovery_name: "sauna", duration_min: 20 }]).proposals[0];
  if (noTime.kind !== "recovery") throw new Error("kind");
  assert.equal(noTime.date, "2026-10-11");
  const supp = run("took creatine yesterday", [{ kind: "supplement_taken", day_offset: -1, supplement_name: "creatine" }]).proposals[0];
  if (supp.kind !== "supplement_taken") throw new Error("kind");
  assert.equal(supp.taken_at, "2026-10-11T12:00:00+08:00");
  assert.ok(supp.warnings.some((w) => w.includes("assumed")));
  const now = run("coffee 250 ml", [{ kind: "drink", beverage_class: "coffee", day_offset: 3, volume_value: 250, volume_unit: "ml" }]).proposals[0];
  if (now.kind !== "drink") throw new Error("kind");
  assert.equal(now.logged_at, "2026-10-12T21:05:00+08:00", "future offsets are rejected, today is used");
  assert.equal(shiftDate("2026-03-01", -1), "2026-02-28");
});

test("supplements match the roster; ambiguity and unknown names are questions", () => {
  assert.equal(matchRoster("magnesium", ctx.roster).id, 1);
  assert.equal(matchRoster("vitamin", ctx.roster).id, null);
  assert.equal(matchRoster("vitamin", ctx.roster).candidates.length, 2);
  assert.equal(matchRoster("ashwagandha", ctx.roster).candidates.length, 0);
  const p = run("took vitamin", [{ kind: "supplement_taken", supplement_name: "vitamin" }]).proposals[0];
  if (p.kind !== "supplement_taken") throw new Error("kind");
  assert.deepEqual(p.needs, ["supplement_match"]);
  assert.equal(p.supplement_id, null);
  const q = run("two creatine", [{ kind: "supplement_taken", supplement_name: "creatine", quantity: 2 }]).proposals[0];
  if (q.kind !== "supplement_taken") throw new Error("kind");
  assert.equal(q.supplement_id, 4);
  assert.equal(q.quantity, 2);
  assert.equal(q.summary, "2 x Creatine");
});

test("meal text is handed through untouched; recovery duration must be stated", () => {
  const m = run("had two eggs and toast", [{ kind: "meal", meal_text: "two eggs and toast" }]).proposals[0];
  if (m.kind !== "meal") throw new Error("kind");
  assert.equal(m.text, "two eggs and toast");
  const r = run("did sauna", [{ kind: "recovery", recovery_name: "sauna", recovery_category: "contrast_therapy", duration_min: 20 }]).proposals[0];
  if (r.kind !== "recovery") throw new Error("kind");
  assert.equal(r.duration_min, null);
  assert.equal(r.category, "contrast_therapy");
});

test("ethanolGrams matches the Android domain function", () => {
  assert.equal(ethanolGrams(500, 5), 19.7);
  assert.equal(ethanolGrams(150, 12), 14.2);
  assert.equal(ethanolGrams(37.5, 40), 11.8);
  assert.equal(ethanolGrams(0, 5), null);
  assert.equal(ethanolGrams(500, 100.1), null);
});

test("a mixed utterance yields independent proposals with stable ids", () => {
  const text = "2 coffees 250 ml each, magnesium, energy 6, a 40 min stretching session, and a 500 ml beer at 5%";
  const { proposals } = run(text, [
    { kind: "drink", beverage_class: "coffee", count: 2, volume_value: 250, volume_unit: "ml" },
    { kind: "supplement_taken", supplement_name: "magnesium" },
    { kind: "wellbeing", mentioned: ["energy"], energy: 6 },
    { kind: "recovery", recovery_name: "stretching", recovery_category: "stretching_mobility", duration_min: 40 },
    { kind: "drink", beverage_class: "alcohol", volume_value: 500, volume_unit: "ml", abv_percent: 5 },
  ]);
  assert.deepEqual(proposals.map((p) => p.id), ["p1", "p2", "p3", "p4", "p5"]);
  assert.deepEqual(proposals.map((p) => p.needs.length), [0, 0, 0, 0, 0]);
  assert.equal((proposals[0] as { volume_ml: number }).volume_ml, 500);
});

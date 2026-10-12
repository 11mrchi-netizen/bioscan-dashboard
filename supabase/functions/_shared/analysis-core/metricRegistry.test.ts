import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { docMetricNames, methodologyText, validateRegistry, type MetricRegistry } from "./metricRegistry.ts";

const reg = JSON.parse(readFileSync(new URL("../metric-registry/metric-registry.json", import.meta.url), "utf8")) as MetricRegistry;
const doc = readFileSync(new URL("../../../../docs/analysis-layer-2/02-metric-registry.md", import.meta.url), "utf8");

test("every doc-02 metric has a complete registry entry, and the registry is valid", () => {
  const names = docMetricNames(doc);
  assert.ok(names.length >= 30, `parsed ${names.length} metric names from doc 02`);
  assert.deepEqual(validateRegistry(reg, names), []);
});

test("the validator rejects a metric with a missing field", () => {
  const names = docMetricNames(doc);
  const broken: MetricRegistry = JSON.parse(JSON.stringify(reg));
  broken.metrics[3].limitations = [];
  broken.metrics[4].methodology = "";
  const problems = validateRegistry(broken, names);
  assert.ok(problems.some((p) => p.includes("limitation")));
  assert.ok(problems.some((p) => p.includes("methodology")));
});

test("the validator rejects a doc metric with no entry and a modeled metric with no basis", () => {
  const names = [...docMetricNames(doc), "Brand new metric"];
  assert.ok(validateRegistry(reg, names).some((p) => p.includes("Brand new metric")));
  const bare: MetricRegistry = JSON.parse(JSON.stringify(reg));
  const sri = bare.metrics.find((m) => m.key === "sri")!;
  sri.references = [];
  assert.ok(validateRegistry(bare, docMetricNames(doc)).some((p) => p.includes("sri") && p.includes("reference")));
});

test("composites are product-defined and say so in the help text", () => {
  const composites = reg.metrics.filter((m) => m.kind === "composite");
  assert.ok(composites.length >= 2);
  for (const c of composites) {
    assert.equal(c.productDefined, true);
    assert.match(methodologyText(c), /Product-defined: not clinically validated/);
  }
});

test("help text lists methodology, limits, references and version", () => {
  const hrv = reg.metrics.find((m) => m.key === "hrv")!;
  const text = methodologyText(hrv);
  assert.match(text, /Limits:/);
  assert.match(text, /References:/);
  assert.match(text, /doi:10\.1007\/s40279-013-0071-8/);
  assert.match(text, /Methodology v1\.0\.0/);
});

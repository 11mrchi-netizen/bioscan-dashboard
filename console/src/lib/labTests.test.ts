import test from 'node:test'
import assert from 'node:assert/strict'
import { markerHue, outsideRange, rangeLine, rangeSide, rangeText, sweatRate } from './labTests.ts'

test('a result against its range, in words', () => {
  assert.equal(rangeText({ ref_low: 3.5, ref_high: 5 }), '3.5–5')
  assert.equal(rangeText({ ref_low: 40, ref_high: null }), '≥ 40')
  assert.equal(rangeText({ ref_low: null, ref_high: null }), '')
  assert.equal(rangeSide({ value: 3, ref_low: 3.5, ref_high: 5 }), 'below')
  assert.equal(rangeSide({ value: 6, ref_low: null, ref_high: 5 }), 'above')
  assert.equal(rangeSide({ value: 5, ref_low: 3.5, ref_high: 5 }), 'within')
  assert.equal(rangeSide({ value: null, ref_low: 3.5, ref_high: 5 }), null)
  assert.equal(outsideRange({ value: 6, ref_low: 3.5, ref_high: 5 }), true)
  assert.equal(rangeLine({ value: 180, ref_low: null, ref_high: 150, flag: 'high' }), 'above the reference range (≤ 150) · high')
  assert.equal(rangeLine({ value: 4, ref_low: 3.5, ref_high: 5, flag: 'normal' }), 'within the reference range (3.5–5)')
  assert.equal(rangeLine({ value: 4, ref_low: null, ref_high: null, flag: null }), null)
})

test('a marker keeps its hue whatever the lab calls it', () => {
  assert.equal(markerHue('Triglyceride'), 'm-triglycerides')
  assert.equal(markerHue('Triglycerides (fasting)'), 'm-triglycerides')
  assert.equal(markerHue('HDL Cholesterol'), 'm-hdl')
  assert.equal(markerHue('HbA1c'), 'm-hba1c')
  assert.equal(markerHue('Glucose, fasting'), 'm-fasting-glucose')
  assert.equal(markerHue('Ferritin'), 'm-weight-ema')
})

test('sweat rate, by the phone rule', () => {
  assert.equal(sweatRate({ duration_min: 60, weight_before_kg: 70, weight_after_kg: 69, fluid_in_ml: 500, urine_ml: 0 }), 1500)
  assert.equal(sweatRate({ duration_min: 60, weight_before_kg: 70, weight_after_kg: 70, fluid_in_ml: 0, urine_ml: 0 }), null)
  assert.equal(sweatRate({ duration_min: 0, weight_before_kg: 70, weight_after_kg: 69, fluid_in_ml: 0, urine_ml: 0 }), null)
})

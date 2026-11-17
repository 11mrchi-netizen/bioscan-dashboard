-- DAV-181: canonical beverage, fluid, hydration and caffeine model.
-- Extends DAV-161's nutrition schema so beverages are first-class rather
-- than a separate water-only counter (hydration_daily stays untouched for
-- backward compatibility, same principle DAV-161 used for `meals`).

-- 1. Beverage classification lives on `foods`, alongside its existing
--    category/subcategory columns -- null for non-beverage foods.
alter table public.foods
  add column beverage_class text,
  add column beverage_subtype text;

comment on column public.foods.beverage_class is
  'Top-level beverage category (water, coffee, tea, milk, plant_milk, soda, juice, electrolyte, alcohol). Null for non-beverage foods.';
comment on column public.foods.beverage_subtype is
  'Free-text refinement of beverage_class, e.g. black_coffee, oat_milk. Null for non-beverage foods.';

-- 2. Composition (water/caffeine/alcohol content per 100g/100ml) reuses the
--    existing extensible food_nutrients.nutrient vocabulary -- no schema
--    change needed there. Canonical nutrient names for beverage composition:
--    'water', 'caffeine', 'alcohol' (alongside the existing 'sodium').

-- 3. Effective-hydration retention factors: a small, explicitly versioned
--    reference table (mirrors food_sources' "reference data, readable by
--    all" shape) so factors are inspectable/updateable as evidence improves,
--    per the ticket's explicit rule -- never hardcoded in app/resolver code.
create table public.hydration_factor_models (
  id bigint generated always as identity primary key,
  model_version text not null,
  beverage_class text not null,
  retention_factor numeric not null,
  notes text,
  created_at timestamptz not null default now(),
  unique (model_version, beverage_class)
);

comment on table public.hydration_factor_models is
  'Versioned beverage-class -> effective-hydration retention factor lookup (DAV-181). A meal_items row records which model_version produced its effective_hydration_ml so results stay reproducible as factors are revised.';

alter table public.hydration_factor_models enable row level security;

create policy "select hydration_factor_models"
  on public.hydration_factor_models for select
  using (true);

-- Seed v1: approximate literature-informed starting factors, not precise
-- individualized values -- explicitly a model, versioned so it can be
-- superseded without losing what produced past results.
insert into public.hydration_factor_models (model_version, beverage_class, retention_factor, notes) values
  ('v1', 'water', 1.00, 'Baseline reference -- full retention.'),
  ('v1', 'electrolyte', 1.03, 'Sodium/electrolyte content modestly improves retention vs plain water.'),
  ('v1', 'milk', 1.00, 'Comparable retention to water despite added macronutrients.'),
  ('v1', 'plant_milk', 1.00, 'Comparable retention to water; composition varies by product.'),
  ('v1', 'juice', 0.90, 'Sugar content moderately reduces relative retention vs water.'),
  ('v1', 'tea', 0.99, 'Negligible net diuretic effect at typical caffeine levels.'),
  ('v1', 'coffee', 0.98, 'Mild diuretic effect at typical caffeine levels, well short of net dehydration.'),
  ('v1', 'soda', 0.95, 'Sugar/caffeine content reduces relative retention vs water.'),
  ('v1', 'alcohol', 0.60, 'Meaningful diuretic effect; scales further with ABV at the meal-item level, not captured by class alone in v1.');

-- 4. Per-consumption beverage fields on meal_items -- the resolver (DAV-164)
--    computes and writes these; this migration only defines where they live.
--    quantity/quantity_unit (existing) already carries consumed volume when
--    quantity_unit = 'ml', so no separate volume column is added here.
alter table public.meal_items
  add column is_beverage boolean not null default false,
  add column water_ml numeric,
  add column caffeine_mg numeric,
  add column alcohol_g numeric,
  add column effective_hydration_ml numeric,
  add column hydration_model_version text,
  add column hydration_confidence numeric check (hydration_confidence is null or (hydration_confidence >= 0 and hydration_confidence <= 1));

comment on column public.meal_items.is_beverage is
  'True when this item is a beverage, set at logging time even before food_id resolution.';
comment on column public.meal_items.water_ml is
  'Actual water/fluid content of the consumed quantity -- a directly-derived quantity from composition, distinct from the nominal consumed volume (quantity/quantity_unit).';
comment on column public.meal_items.caffeine_mg is
  'Caffeine content of the consumed quantity. Independent of water_ml/effective_hydration_ml -- never subtracted from fluid.';
comment on column public.meal_items.effective_hydration_ml is
  'Derived estimate (water_ml x the matching hydration_factor_models.retention_factor), never a measured physiological value.';
comment on column public.meal_items.hydration_model_version is
  'hydration_factor_models.model_version used to compute effective_hydration_ml, for provenance/reproducibility.';
comment on column public.meal_items.hydration_confidence is
  'Confidence (0-1) in the effective-hydration estimate, independent of is_estimated/confidence which describe AI food-identification confidence.';

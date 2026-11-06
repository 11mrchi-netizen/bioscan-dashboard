-- Regimen streamlining (docs/regimen/README.md): the form decides how an item is handled, and one dose is a count of the
-- form's unit with the ingredients describing that dose.
--
-- 1. supplements.dose_amount / dose_unit now mean "how much of the form is one dose": 3 softgel, 0.5 tablet, 5 g of a
--    powder, 150 ml of a liquid. An injection is the exception: its dose is the active amount (250 mcg), the vial turns
--    it into a volume. The ingredients (supplement_product_ingredients, per label serving) times servings_per_dose are the
--    dose's contents. New units: gummy, patch, spray.
-- 2. supplements.scored: a tablet that can be split in half (halves allowed when logging).
-- 3. The 20 existing items converted with the owner's answers (4 Nov): forms and counts set, label servings set to one unit
--    for whole-unit forms (servings_per_dose = the count, so pantry consumption and nutrient totals count every unit),
--    Tadalafil a scored tablet taken as half of a 5 mg tablet, Omega-3 three 1000 mg softgels.
-- 4. Duplicate ingredients merged: the row a product uses keeps the nutrient/canonical keys of its product-named twin
--    (Iron, L-Tyrosine, Glycine and Vitamin C had lost theirs), product-named ingredients renamed to the nutrient
--    (Vitamin D3, Fish oil, Magnesium, Apigenin, DIM, Stinging nettle root), and the unused twins removed.
-- The history trigger is paused for the conversion, so tidying the dose text is not recorded as dose changes.
--
-- Rollback: the column and constraint changes reverse with alter table ... drop column scored and the earlier
--           dose_unit check (20261115_regimen_model.sql); the data conversion is not reversible automatically.

alter table public.supplements add column if not exists scored boolean not null default false;
alter table public.supplements drop constraint if exists supplements_dose_unit_check;
alter table public.supplements add constraint supplements_dose_unit_check check (dose_unit is null or dose_unit in (
  'mg', 'mcg', 'g', 'IU', 'ml', 'drop', 'pill', 'capsule', 'tablet', 'softgel', 'scoop', 'serving', 'unit', 'gummy', 'patch', 'spray'));

alter table public.supplements disable trigger supplements_record_change;

-- Forms, counts and label servings, by item (owner-confirmed).
with c(id, form, amount, unit, scored, spd, ss, su) as (values
  (14, 'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Activated B Complex
  (5,  'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Boron
  (19, 'tablet',  1, 'tablet',  false, 1, 1, 'tablet'),      -- Copper
  (4,  'powder',  5, 'g',       false, 1, 5, 'g'),           -- Creatine
  (23, 'powder',  3, 'g',       false, 1, 3, 'g'),           -- Glycine (Now Foods)
  (6,  'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Iron
  (9,  'capsule', 4, 'capsule', false, 4, 1, 'capsule'),     -- L-Tyrosine: 4 x 500 mg
  (7,  'softgel', 3, 'softgel', false, 3, 1, 'softgel'),     -- Omega-3: 3 x 1000 mg
  (12, 'tablet',  0.5, 'tablet', true, 0.5, 1, 'tablet'),    -- Tadalafil: half of a 5 mg tablet
  (1,  'liquid',  150, 'ml',    false, 1, 150, 'ml'),        -- Tart Cherry Concentrate
  (21, 'capsule', 3, 'capsule', false, 1, 3, 'capsule'),     -- Tart Cherry Extract: label serving is 3 capsules
  (10, 'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Triple Magnesium
  (22, 'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Vitamin C (Swanson)
  (2,  'softgel', 1, 'softgel', false, 1, 1, 'softgel'),     -- Vitamin D3
  (3,  'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Zinc Picolinate
  (15, 'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Apigenin (ended)
  (13, 'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- DIM (ended)
  (8,  'powder',  3, 'g',       false, 1, 3, 'g'),           -- Glycine (Nutricost, ended)
  (11, 'capsule', 1, 'capsule', false, 1, 1, 'capsule'),     -- Stinging Nettle Root (ended)
  (18, 'capsule', 1, 'capsule', false, 1, 1, 'capsule')      -- Vitamin C (nutriplus, ended)
), upd as (
  update public.supplements s
  set form = c.form, dose_amount = c.amount, dose_unit = c.unit, scored = c.scored, servings_per_dose = c.spd,
      route = 'oral', dose = trim_scale(c.amount::numeric)::text || ' ' || c.unit
  from c where s.id = c.id
  returning s.product_id, c.ss, c.su
)
update public.supplement_products p set serving_size = upd.ss, serving_unit = upd.su
from upd where p.id = upd.product_id;

-- Omega-3: the 3000 mg was the dose; the label serving is now one softgel.
update public.supplement_product_ingredients set compound_amount = 1000
where supplement_product_id = 7 and supplement_ingredient_id = 2;

-- Ingredients: keys onto the rows products use, product-named ones renamed, twins merged, unused twins removed.
update public.supplement_ingredients set nutrient_key = 'iron', canonical_key = 'iron', category = 'mineral' where id = 28;
update public.supplement_ingredients set nutrient_key = 'tyrosine', canonical_key = 'tyrosine', category = 'amino_acid' where id = 27;
update public.supplement_ingredients set nutrient_key = 'glycine', canonical_key = 'glycine', category = 'amino_acid' where id = 23;
update public.supplement_ingredients set nutrient_key = 'vitamin_c', category = 'vitamin' where id = 20;
update public.supplement_product_ingredients set supplement_ingredient_id = 23 where supplement_ingredient_id = 12; -- Glycine (Nutricost) -> Glycine
update public.supplement_product_ingredients set supplement_ingredient_id = 20 where supplement_ingredient_id = 4;  -- Vitamin C (nutriplus) -> Vitamin C
update public.supplement_ingredients set name = 'Vitamin D3' where id = 7;
update public.supplement_ingredients set name = 'Fish oil' where id = 2;
update public.supplement_ingredients set name = 'Magnesium' where id = 5;
update public.supplement_ingredients set name = 'Apigenin' where id = 3;
update public.supplement_ingredients set name = 'DIM' where id = 8;
update public.supplement_ingredients set name = 'Stinging nettle root' where id = 9;
delete from public.supplement_ingredients i
where i.id in (1, 4, 6, 11, 12, 14, 17)
  and not exists (select 1 from public.supplement_product_ingredients pi where pi.supplement_ingredient_id = i.id);

alter table public.supplements enable trigger supplements_record_change;

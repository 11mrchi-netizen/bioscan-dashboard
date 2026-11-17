-- Backfill: one supplement_products + supplement_product_ingredients row per
-- existing roster item, hand-reviewed against the real data (not a blind
-- script) -- see DAV-328 audit. All match_confidence='low': elemental_amount
-- is only set for pure vitamin/amino-acid/creatine doses where the labeled
-- compound IS the active substance; left null for minerals (zinc/iron/
-- magnesium/boron/copper -- typically salts/chelates on a real label, so
-- compound mass != elemental mass without actually reading that label) and
-- for fish oil (label mass != omega-3 mass). Where elemental is null, the
-- nutrient-mapping function (domain/SupplementComposition.kt) falls back to
-- the compound amount, so computed nutrient_intake totals for these existing
-- supplements don't change -- only their provenance does (structured now,
-- flagged low-confidence, instead of a silent regex guess).

insert into public.supplement_sources (user_id, name, url)
select distinct user_id, 'migrated_inference', null from public.supplements
on conflict (user_id, name) do nothing;

-- One row per (user_id, roster item): name, compound_amount, compound_unit,
-- nutrient_key, elemental_amount (null unless noted above), category.
with backfill(roster_name, compound_amount, compound_unit, nutrient_key, elemental_amount, category) as (
  values
    ('Tart Cherry Concentrate', 150::numeric, 'ml', null::text, null::numeric, 'anti-inflammatory'),
    ('Vitamin D3 (Now Food)', 5000::numeric, 'IU', 'vitamin_d', 5000::numeric, 'vitamin'),
    ('Zinc Picolinate (Swanson)', 50::numeric, 'mg', 'zinc', null::numeric, 'mineral'),
    ('Creatine', 5::numeric, 'g', 'creatine', 5::numeric, 'performance'),
    ('Boron (Nutricost)', 10::numeric, 'mg', 'boron', null::numeric, 'mineral'),
    ('Iron (California Gold Nutrition)', 36::numeric, 'mg', 'iron', null::numeric, 'mineral'),
    ('Omega 3 Fish Oil (Now Foods)', 3000::numeric, 'mg', 'omega_3', null::numeric, 'fatty_acid'),
    ('Glycine (Nutricost)', 3000::numeric, 'mg', 'glycine', 3000::numeric, 'amino_acid'),
    ('L-Tyrosine (Now Foods)', 2000::numeric, 'mg', 'tyrosine', 2000::numeric, 'amino_acid'),
    ('Triple Magnesium Complex (Swanson)', 400::numeric, 'mg', 'magnesium', null::numeric, 'mineral'),
    ('Stinging Nettle Root (Swanson)', 500::numeric, 'mg', null::text, null::numeric, 'herbal'),
    ('Tadalafil', 2.5::numeric, 'mg', null::text, null::numeric, 'other'),
    ('DIM Complex (Swanson)', 100::numeric, 'mg', null::text, null::numeric, 'herbal'),
    ('Activated B Complex (Swanson)', 1::numeric, 'pill', null::text, null::numeric, 'vitamin'),
    ('Apigenin (Swanson)', 50::numeric, 'mg', null::text, null::numeric, 'herbal'),
    ('Vitamin C (nutriplus)', 500::numeric, 'mg', 'vitamin_c', 500::numeric, 'vitamin'),
    ('copper', 2::numeric, 'mg', 'copper', null::numeric, 'mineral')
),
src as (
  select s.id as source_id, s.user_id from public.supplement_sources s where s.name = 'migrated_inference'
),
ins_ingredients as (
  insert into public.supplement_ingredients (user_id, name, category, nutrient_key)
  select distinct sup.user_id, b.roster_name, b.category, b.nutrient_key
  from public.supplements sup
  join backfill b on b.roster_name = sup.name
  on conflict (user_id, name) do update set category = excluded.category, nutrient_key = excluded.nutrient_key
  returning id, user_id, name
),
ins_products as (
  insert into public.supplement_products (user_id, supplement_source_id, name, match_confidence)
  select sup.user_id, src.source_id, sup.name, 'low'
  from public.supplements sup
  join backfill b on b.roster_name = sup.name
  join src on src.user_id = sup.user_id
  returning id, user_id, name
)
insert into public.supplement_product_ingredients (user_id, supplement_product_id, supplement_ingredient_id, compound_amount, compound_unit, elemental_amount, elemental_unit)
select p.user_id, p.id, i.id, b.compound_amount, b.compound_unit, b.elemental_amount,
       case when b.elemental_amount is not null then b.compound_unit else null end
from ins_products p
join backfill b on b.roster_name = p.name
join ins_ingredients i on i.name = p.name and i.user_id = p.user_id;

update public.supplements sup
set product_id = p.id
from public.supplement_products p
where p.name = sup.name and p.user_id = sup.user_id and p.match_confidence = 'low';

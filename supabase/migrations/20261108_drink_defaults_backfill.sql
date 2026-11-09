-- Run after scripts/nutrition/import_drinks.py has loaded the drinks: the default drink per kind, and the drinks
-- logged before the catalog given their kind's numbers (marked estimated).

-- One default drink per kind (what "Coffee" with nothing more said means).
update public.foods f set drink_default = true
from (values
  ('water', 'usda_fdc_sr_legacy', 'Beverages, water, tap, drinking'),
  ('coffee', 'usda_fdc_sr_legacy', 'Beverages, coffee, brewed, prepared with tap water'),
  ('tea', 'usda_fdc_sr_legacy', 'Beverages, tea, black, brewed, prepared with tap water'),
  ('milk', 'taiwan_fda_nutrient_db', 'Whole fresh milk (Taiwan average)'),
  ('plant_milk', 'usda_fdc_sr_legacy', 'Soymilk, original and vanilla, unfortified'),
  ('juice', 'taiwan_fda_nutrient_db', 'Orange juice, 100%'),
  ('soda', 'taiwan_fda_nutrient_db', 'Cola'),
  ('electrolyte', 'taiwan_fda_nutrient_db', 'Sports drink (isotonic)'),
  ('alcohol', 'usda_fdc_sr_legacy', 'Alcoholic beverage, beer, regular, all')
) as v(cls, src, name)
join public.food_sources s on s.name = v.src
where f.food_source_id = s.id and f.name = v.name and f.beverage_class = v.cls;

-- The drinks logged before the catalog: the kind's default drink, its numbers for the amount, marked estimated.
with cls as (
  select mi.id, case
    when mi.description = 'Coffee' then 'coffee' when mi.description = 'Tea' then 'tea'
    when mi.description = 'Juice' then 'juice' when mi.description = 'Soda' then 'soda'
    when mi.description = 'Milk' then 'milk' when mi.description = 'Plant milk' then 'plant_milk'
    when mi.description = 'Electrolyte drink' then 'electrolyte' when mi.description like 'Alcohol%' then 'alcohol' end as c
  from public.meal_items mi where mi.is_beverage and mi.food_id is null and mi.quantity_unit = 'ml'
),
per as (select * from public.drink_rows(array(select id from public.foods where drink_default)))
update public.meal_items mi set
  food_id = per.id, beverage_class = cls.c, is_estimated = true,
  calories = round(per.kcal * mi.quantity / 100, 1), sugar_g = round(per.sugar_g * mi.quantity / 100, 1),
  carbs_g = round(per.carbs_g * mi.quantity / 100, 1), protein_g = round(per.protein_g * mi.quantity / 100, 1),
  fat_g = round(per.fat_g * mi.quantity / 100, 1), sodium_mg = round(per.sodium_mg * mi.quantity / 100, 1),
  caffeine_mg = round(per.caffeine_mg * mi.quantity / 100, 1),
  alcohol_g = coalesce(mi.alcohol_g, round(per.alcohol_g * mi.quantity / 100, 1))
from cls join per on per.beverage_class = cls.c
where mi.id = cls.id;

-- Those drinks' meals take their item's totals (a drink meal had none).
update public.meals m set calories = s.kcal, protein_g = s.p, carbs_g = s.c, fat_g = s.f, sugar_g = s.su, sodium_mg = s.na
from (
  select meal_id, sum(calories) kcal, sum(protein_g) p, sum(carbs_g) c, sum(fat_g) f, sum(sugar_g) su, sum(sodium_mg) na, count(*) n,
    bool_and(is_beverage) only_drinks
  from public.meal_items group by meal_id
) s
where m.id = s.meal_id and s.only_drinks and m.calories is null and s.kcal is not null;

select (select count(*) from public.foods where beverage_class is not null) drinks,
  (select count(*) from public.foods where drink_default) defaults,
  (select count(*) from public.meal_items where is_beverage and food_id is not null) backfilled,
  (select count(*) from public.meal_items where is_beverage and food_id is null) left_alone;

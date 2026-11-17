-- DAV-164: two hand-verified manual fixture foods so the resolver has real,
-- resolvable data to exercise end-to-end (not just unit-test doubles) ahead
-- of DAV-162/163's real dataset imports. Values are USDA-typical figures for
-- these common items, entered manually (source = 'manual', per DAV-161).

insert into public.foods (food_source_id, name, category, is_composite)
values (1, 'Chicken breast, cooked, skinless', 'meat_poultry', false)
returning id;

insert into public.food_nutrients (food_id, nutrient, amount, unit, basis_qty, basis_unit)
select id, nutrient, amount, unit, 100, 'g' from (
  select (select id from public.foods where name = 'Chicken breast, cooked, skinless') as id, 'calories' as nutrient, 165.0 as amount, 'kcal' as unit
  union all select (select id from public.foods where name = 'Chicken breast, cooked, skinless'), 'protein', 31.0, 'g'
  union all select (select id from public.foods where name = 'Chicken breast, cooked, skinless'), 'fat', 3.6, 'g'
  union all select (select id from public.foods where name = 'Chicken breast, cooked, skinless'), 'sodium', 74.0, 'mg'
) v;

insert into public.food_servings (food_id, serving_name, grams)
values ((select id from public.foods where name = 'Chicken breast, cooked, skinless'), '1 breast (raw wt. ~172g -> cooked)', 118.0);

insert into public.foods (food_source_id, name, category, beverage_class, is_composite)
values (1, 'Black coffee, brewed', 'beverage', 'coffee', false)
returning id;

insert into public.food_nutrients (food_id, nutrient, amount, unit, basis_qty, basis_unit)
select id, nutrient, amount, unit, 100, 'ml' from (
  select (select id from public.foods where name = 'Black coffee, brewed') as id, 'calories' as nutrient, 1.0 as amount, 'kcal' as unit
  union all select (select id from public.foods where name = 'Black coffee, brewed'), 'water', 99.9, 'g'
  union all select (select id from public.foods where name = 'Black coffee, brewed'), 'caffeine', 40.0, 'mg'
) v;

insert into public.food_servings (food_id, serving_name, ml)
values ((select id from public.foods where name = 'Black coffee, brewed'), '1 cup', 240.0);

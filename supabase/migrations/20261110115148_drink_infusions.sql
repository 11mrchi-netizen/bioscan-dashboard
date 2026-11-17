-- Drinks: herbal teas and infusions as their own kind (the drink log's KIND row, "Herbal tea").
--
-- 1. hydration_factor_models v1 gains 'infusion' at 1.00: caffeine-free, retained like water.
-- 2. Catalog herbal teas (beverage_class 'tea', subtype 'herbal': chamomile, other herbs, barley, chrysanthemum,
--    guilinggao) become 'infusion', and so do the meal items logged with them. Jasmine and other real teas stay 'tea'.
-- 3. Infusions without a caffeine row get 0 mg, marked estimated (the source measured none; herbal infusions have none).
-- 4. "Beverages, tea, herb, other than chamomile, brewed" is the class's default drink ("Herbal tea" with nothing more).
--
-- Rollback: update public.meal_items set beverage_class = 'tea' where beverage_class = 'infusion';
--           update public.foods set beverage_class = 'tea', drink_default = false where beverage_class = 'infusion';
--           delete from public.food_nutrients where nutrient = 'caffeine' and estimated and amount = 0
--             and food_id in (select id from public.foods where beverage_subtype = 'herbal');
--           delete from public.hydration_factor_models where model_version = 'v1' and beverage_class = 'infusion';

insert into public.hydration_factor_models (model_version, beverage_class, retention_factor, notes)
values ('v1', 'infusion', 1.00, 'Herbal teas and infusions: caffeine-free, retained like water.')
on conflict (model_version, beverage_class) do nothing;

update public.foods set beverage_class = 'infusion'
where beverage_class = 'tea' and beverage_subtype = 'herbal';

update public.meal_items mi set beverage_class = 'infusion'
from public.foods f
where mi.food_id = f.id and f.beverage_class = 'infusion' and mi.beverage_class = 'tea';

insert into public.food_nutrients (food_id, nutrient, amount, unit, basis_qty, basis_unit, estimated)
select f.id, 'caffeine', 0, 'mg', 100, 'g', true
from public.foods f
where f.beverage_class = 'infusion'
  and not exists (select 1 from public.food_nutrients fn where fn.food_id = f.id and fn.nutrient = 'caffeine');

update public.foods set drink_default = true
where beverage_class = 'infusion' and name = 'Beverages, tea, herb, other than chamomile, brewed'
  and not exists (select 1 from public.foods d where d.beverage_class = 'infusion' and d.drink_default);

-- Applying and undoing nutrition-backfill review rows (see 20261021_nutrition_backfill_review.sql for the review run).
--
--   select public.apply_nutrition_backfill_review(array[<review ids>]);   -- write the new numbers for those rows
--   select public.revert_nutrition_backfill_review(array[<review ids>]);  -- put back exactly what was there before
--
-- Kept apart from the review migration because these rewrite the meal log: they are installed by the owner (SQL editor),
-- and run only on rows the owner chose.
--
-- Rollback: drop function public.revert_nutrition_backfill_review(bigint[]);
--           drop function public.apply_nutrition_backfill_review(bigint[]);

-- Writes a reviewed row's numbers onto its meal, meal item and nutrient_intake rows, in one transaction, after
-- snapshotting what was there. Only 'pending' rows with a result are touched; returns how many were applied.
create or replace function public.apply_nutrition_backfill_review(review_ids bigint[])
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  r public.nutrition_backfill_review%rowtype;
  applied integer := 0;
begin
  for r in
    select * from public.nutrition_backfill_review
    where id = any (review_ids) and status = 'pending' and new_calories is not null
    for update
  loop
    update public.nutrition_backfill_review set previous = jsonb_build_object(
      'meal', (select to_jsonb(m) from public.meals m where m.id = r.meal_id),
      'item', (select to_jsonb(mi) from public.meal_items mi where mi.id = r.meal_item_id),
      'intake', coalesce((
        select jsonb_agg(jsonb_build_object('nutrient', ni.nutrient, 'amount', ni.amount, 'unit', ni.unit))
        from public.nutrient_intake ni where ni.source_type = 'meal_item' and ni.source_id = r.meal_item_id
      ), '[]'::jsonb)
    ) where id = r.id;

    update public.meals set
      calories = r.new_calories,
      protein_g = r.new_protein_g,
      carbs_g = r.new_carbs_g,
      fat_g = r.new_fat_g,
      fiber_g = (r.new_totals ->> 'fiber')::numeric,
      sugar_g = (r.new_totals ->> 'sugar')::numeric,
      sodium_mg = (r.new_totals ->> 'sodium')::numeric
    where id = r.meal_id;

    update public.meal_items set
      calories = r.new_calories,
      protein_g = r.new_protein_g,
      carbs_g = r.new_carbs_g,
      fat_g = r.new_fat_g,
      fiber_g = (r.new_totals ->> 'fiber')::numeric,
      sugar_g = (r.new_totals ->> 'sugar')::numeric,
      sodium_mg = (r.new_totals ->> 'sodium')::numeric,
      quantity = r.new_grams,
      quantity_unit = 'g',
      confidence = r.new_confidence,
      cronometer_nutrients = r.new_totals,
      cronometer_source = r.new_primary_source,
      cronometer_items = r.new_items,
      updated_at = now()
    where id = r.meal_item_id;

    delete from public.nutrient_intake where source_type = 'meal_item' and source_id = r.meal_item_id;
    insert into public.nutrient_intake (user_id, logged_at, nutrient, amount, unit, source_type, source_id)
    select r.user_id, r.logged_at, t.key, t.value::double precision, r.new_units ->> t.key, 'meal_item', r.meal_item_id
    from jsonb_each_text(r.new_totals) t
    where r.new_units ? t.key;

    update public.nutrition_backfill_review set status = 'applied', applied_at = now() where id = r.id;
    applied := applied + 1;
  end loop;
  return applied;
end;
$$;

revoke all on function public.apply_nutrition_backfill_review(bigint[]) from public, anon, authenticated;
grant execute on function public.apply_nutrition_backfill_review(bigint[]) to service_role;

-- Restores applied rows from their snapshot; returns how many were reverted.
create or replace function public.revert_nutrition_backfill_review(review_ids bigint[])
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  r public.nutrition_backfill_review%rowtype;
  reverted integer := 0;
begin
  for r in
    select * from public.nutrition_backfill_review
    where id = any (review_ids) and status = 'applied' and previous is not null
    for update
  loop
    update public.meals set
      calories = (r.previous -> 'meal' ->> 'calories')::numeric,
      protein_g = (r.previous -> 'meal' ->> 'protein_g')::numeric,
      carbs_g = (r.previous -> 'meal' ->> 'carbs_g')::numeric,
      fat_g = (r.previous -> 'meal' ->> 'fat_g')::numeric,
      fiber_g = (r.previous -> 'meal' ->> 'fiber_g')::numeric,
      sugar_g = (r.previous -> 'meal' ->> 'sugar_g')::numeric,
      sodium_mg = (r.previous -> 'meal' ->> 'sodium_mg')::numeric
    where id = r.meal_id;

    update public.meal_items set
      calories = (r.previous -> 'item' ->> 'calories')::numeric,
      protein_g = (r.previous -> 'item' ->> 'protein_g')::numeric,
      carbs_g = (r.previous -> 'item' ->> 'carbs_g')::numeric,
      fat_g = (r.previous -> 'item' ->> 'fat_g')::numeric,
      fiber_g = (r.previous -> 'item' ->> 'fiber_g')::numeric,
      sugar_g = (r.previous -> 'item' ->> 'sugar_g')::numeric,
      sodium_mg = (r.previous -> 'item' ->> 'sodium_mg')::numeric,
      quantity = (r.previous -> 'item' ->> 'quantity')::numeric,
      quantity_unit = r.previous -> 'item' ->> 'quantity_unit',
      confidence = (r.previous -> 'item' ->> 'confidence')::numeric,
      cronometer_nutrients = r.previous -> 'item' -> 'cronometer_nutrients',
      cronometer_source = r.previous -> 'item' ->> 'cronometer_source',
      cronometer_items = r.previous -> 'item' -> 'cronometer_items',
      updated_at = now()
    where id = r.meal_item_id;

    delete from public.nutrient_intake where source_type = 'meal_item' and source_id = r.meal_item_id;
    insert into public.nutrient_intake (user_id, logged_at, nutrient, amount, unit, source_type, source_id)
    select r.user_id, r.logged_at, e ->> 'nutrient', (e ->> 'amount')::double precision, e ->> 'unit', 'meal_item', r.meal_item_id
    from jsonb_array_elements(r.previous -> 'intake') e;

    update public.nutrition_backfill_review set status = 'reverted' where id = r.id;
    reverted := reverted + 1;
  end loop;
  return reverted;
end;
$$;

revoke all on function public.revert_nutrition_backfill_review(bigint[]) from public, anon, authenticated;
grant execute on function public.revert_nutrition_backfill_review(bigint[]) to service_role;

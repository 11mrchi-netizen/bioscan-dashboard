-- Re-check meals already logged against the current food lookup (nutrition-backfill), review the differences, and
-- apply only the rows that were approved.
--
--   select private.run_nutrition_backfill();                 -- start a review run (all users); returns its run id
--   select * from public.nutrition_backfill_review where run_id = '<run id>' order by flagged desc, logged_at;
--   select public.apply_nutrition_backfill_review(array[<review ids>]);   -- write the new numbers for those rows
--   select public.revert_nutrition_backfill_review(array[<review ids>]);  -- put back exactly what was there before
--
-- Authentication follows 20261012_analysis_cron_secret.sql: a random secret generated inside Vault is sent by
-- private.run_nutrition_backfill() in the x-nutrition-backfill header (with the public anon key, which only gets the
-- request past the Edge Function gateway) and checked by the service role-only public.nutrition_backfill_secret_ok().
--
-- Rollback: drop function public.revert_nutrition_backfill_review(bigint[]);
--           drop function public.apply_nutrition_backfill_review(bigint[]);
--           drop function private.run_nutrition_backfill(uuid);
--           drop function public.nutrition_backfill_secret_ok(text);
--           delete from vault.secrets where name = 'nutrition_backfill_secret';
--           drop table public.nutrition_backfill_review;

create table public.nutrition_backfill_review (
  id bigint generated always as identity primary key,
  run_id uuid not null,
  user_id uuid not null references auth.users (id) on delete cascade,
  meal_id bigint not null references public.meals (id) on delete cascade,
  meal_item_id bigint not null references public.meal_items (id) on delete cascade,
  logged_at timestamptz not null,
  description text,
  source text,
  old_calories numeric,
  old_protein_g numeric,
  old_carbs_g numeric,
  old_fat_g numeric,
  new_calories numeric,
  new_protein_g numeric,
  new_carbs_g numeric,
  new_fat_g numeric,
  new_totals jsonb,
  new_units jsonb,
  new_items jsonb,
  new_confidence numeric,
  new_primary_source text,
  new_grams numeric,
  ai_estimate_total numeric,
  mode text,
  flagged boolean not null default false,
  status text not null default 'pending' check (status in ('pending', 'applied', 'reverted', 'error')),
  error text,
  -- The meal, meal item and nutrient_intake rows exactly as they were just before apply, for revert.
  previous jsonb,
  created_at timestamptz not null default now(),
  applied_at timestamptz,
  unique (run_id, meal_item_id)
);

create index nutrition_backfill_review_run_idx on public.nutrition_backfill_review (run_id, flagged);

alter table public.nutrition_backfill_review enable row level security;
create policy nutrition_backfill_review_select_own on public.nutrition_backfill_review
  for select to authenticated using (user_id = (select auth.uid()));

select vault.create_secret(
  replace(gen_random_uuid()::text || gen_random_uuid()::text, '-', ''),
  'nutrition_backfill_secret',
  'Authenticates private.run_nutrition_backfill() calls to the nutrition-backfill Edge Function'
);

create or replace function public.nutrition_backfill_secret_ok(candidate text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select candidate is not null and exists (
    select 1 from vault.decrypted_secrets where name = 'nutrition_backfill_secret' and decrypted_secret = candidate
  );
$$;

revoke all on function public.nutrition_backfill_secret_ok(text) from public, anon, authenticated;
grant execute on function public.nutrition_backfill_secret_ok(text) to service_role;

create or replace function private.run_nutrition_backfill(target_user uuid default null)
returns uuid
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  backfill_secret text;
  run uuid := gen_random_uuid();
begin
  select decrypted_secret into backfill_secret from vault.decrypted_secrets where name = 'nutrition_backfill_secret';
  if backfill_secret is null then
    raise exception 'nutrition_backfill_secret not found in vault';
  end if;

  perform net.http_post(
    url := 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/nutrition-backfill',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      -- public anon key: only satisfies the gateway's JWT check, grants no access by itself
      'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVnZnJnbGJjb2l2a3ByanF2anp6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkxMDYxOTAsImV4cCI6MjEwNDY4MjE5MH0.0G_5XnBiIsUTQG7vlSTARND8apI-mI6RdUytCSWY9To',
      'x-nutrition-backfill', backfill_secret
    ),
    body := jsonb_build_object('action', 'review', 'run_id', run, 'user_id', target_user)
  );
  return run;
end;
$$;

revoke all on function private.run_nutrition_backfill(uuid) from public, anon, authenticated;

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

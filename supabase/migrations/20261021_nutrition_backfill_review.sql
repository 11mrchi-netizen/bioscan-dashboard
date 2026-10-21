-- Re-check meals already logged against the current food lookup (nutrition-backfill) and review the differences.
-- Applying chosen rows to the log is 20261021_nutrition_backfill_apply.sql.
--
--   select private.run_nutrition_backfill();                 -- start a review run (all users); returns its run id
--   select * from public.nutrition_backfill_review where run_id = '<run id>' order by flagged desc, logged_at;
--
-- Authentication follows 20261012_analysis_cron_secret.sql: a random secret generated inside Vault is sent by
-- private.run_nutrition_backfill() in the x-nutrition-backfill header (with the public anon key, which only gets the
-- request past the Edge Function gateway) and checked by the service role-only public.nutrition_backfill_secret_ok().
--
-- Rollback: drop function private.run_nutrition_backfill(uuid);
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


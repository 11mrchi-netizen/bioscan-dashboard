-- Endurance Fueling & Personal Experiments, Phase 06.3.3: planned/actual intake events logged against an activity.
-- Ordered by `elapsed_seconds` (elapsed time since the activity started), not by device wall-clock timestamps,
-- which may be missing or inconsistent -- `logged_at` is retained when available but is never the ordering key.
--
-- Each consumed/partial event writes its known nutrients into the *existing* `nutrient_intake` single-counting
-- ledger (source_type='fueling_intake'), via a trigger rather than app-layer code. meal_items/supplement_log do
-- this dual-write in their own Kotlin repositories (no DB trigger), but a DB trigger is used here on purpose: it
-- is testable directly over SQL (this environment cannot build or run the Android app to verify a Kotlin write
-- path), and it structurally guarantees "editing/deleting an event leaves daily nutrition totals correct" --
-- delete-then-reinsert on every INSERT/UPDATE/DELETE, rather than requiring every future client to reimplement
-- the same dual-write correctly. Planned/skipped rows never reach `nutrient_intake`.
-- Rollback: drop trigger fueling_intake_log_sync_nutrient_intake on public.fueling_intake_log;
--           drop function public.fueling_intake_log_sync_nutrient_intake();
--           drop table public.fueling_intake_log;

create table public.fueling_intake_log (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  exercise_session_id bigint not null references public.exercise_sessions (id) on delete cascade,
  recipe_version_id bigint references public.fueling_recipe_versions (id) on delete set null,
  -- for an unplanned/unknown item with no product match yet
  description text,
  status text not null check (status in ('planned', 'consumed', 'skipped', 'partial')),
  -- elapsed time since exercise_session.start_time; the canonical ordering key (see header)
  elapsed_seconds integer not null check (elapsed_seconds >= 0),
  -- wall-clock timestamp when the device/app actually recorded it; null for a purely elapsed-time, post-run entry
  logged_at timestamptz,
  -- servings or a portion fraction (0.5 for half a gel); null for a free-text/unplanned item with no serving concept
  quantity numeric,
  quantity_unit text,
  -- actual-consumed nutrient values, frozen at log time -- scaled from the recipe version at the time of logging
  -- (fuelingRecipe.ts's scaleRecipeToQuantity) or entered directly; never recomputed if the recipe is later revised
  carbs_g numeric,
  fluid_ml numeric,
  sodium_mg numeric,
  caffeine_mg numeric,
  source text,
  confidence numeric,
  -- client-generated idempotency key: retrying a failed submit with the same key must not create a duplicate row
  client_key text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, client_key)
);

create index fueling_intake_log_session_idx on public.fueling_intake_log (exercise_session_id, elapsed_seconds);
create index fueling_intake_log_user_idx on public.fueling_intake_log (user_id);

create or replace function public.fueling_intake_log_touch_updated_at()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

create trigger fueling_intake_log_touch_updated_at before update on public.fueling_intake_log
  for each row execute function public.fueling_intake_log_touch_updated_at();

create or replace function public.fueling_intake_log_sync_nutrient_intake()
returns trigger
language plpgsql
set search_path = public
as $$
declare
  session_start timestamptz;
  event_time timestamptz;
begin
  -- Clear whatever this event previously contributed first -- the simplest way to guarantee correct totals after
  -- an edit or delete without reasoning about which specific fields changed.
  if tg_op in ('UPDATE', 'DELETE') then
    delete from public.nutrient_intake where source_type = 'fueling_intake' and source_id = old.id;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;

  -- Planned and skipped intake never count as consumed.
  if new.status not in ('consumed', 'partial') then
    return new;
  end if;

  select start_time into session_start from public.exercise_sessions where id = new.exercise_session_id;
  event_time := coalesce(new.logged_at, session_start + make_interval(secs => new.elapsed_seconds));

  if new.carbs_g is not null then
    insert into public.nutrient_intake (user_id, logged_at, nutrient, amount, unit, source_type, source_id)
    values (new.user_id, event_time, 'carbs', new.carbs_g, 'g', 'fueling_intake', new.id);
  end if;
  if new.fluid_ml is not null then
    insert into public.nutrient_intake (user_id, logged_at, nutrient, amount, unit, source_type, source_id)
    values (new.user_id, event_time, 'water', new.fluid_ml, 'ml', 'fueling_intake', new.id);
  end if;
  if new.sodium_mg is not null then
    insert into public.nutrient_intake (user_id, logged_at, nutrient, amount, unit, source_type, source_id)
    values (new.user_id, event_time, 'sodium', new.sodium_mg, 'mg', 'fueling_intake', new.id);
  end if;
  if new.caffeine_mg is not null then
    insert into public.nutrient_intake (user_id, logged_at, nutrient, amount, unit, source_type, source_id)
    values (new.user_id, event_time, 'caffeine', new.caffeine_mg, 'mg', 'fueling_intake', new.id);
  end if;

  return new;
end;
$$;

create trigger fueling_intake_log_sync_nutrient_intake
  after insert or update or delete on public.fueling_intake_log
  for each row execute function public.fueling_intake_log_sync_nutrient_intake();

alter table public.fueling_intake_log enable row level security;

create policy fueling_intake_log_select_own on public.fueling_intake_log for select to authenticated using (user_id = (select auth.uid()));
create policy fueling_intake_log_insert_own on public.fueling_intake_log for insert to authenticated with check (user_id = (select auth.uid()));
create policy fueling_intake_log_update_own on public.fueling_intake_log for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy fueling_intake_log_delete_own on public.fueling_intake_log for delete to authenticated using (user_id = (select auth.uid()));

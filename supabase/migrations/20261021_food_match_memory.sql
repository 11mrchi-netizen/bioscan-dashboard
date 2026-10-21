-- Food logging accuracy: remember each user's confirmed Cronometer food per query, and keep an audit of what
-- every saved meal item was matched to.
--
-- food_match_memory: written by the app when a meal is saved with an exact or user-picked match; read by
-- nutrition-cronometer-lookup (with the caller's JWT, so RLS scopes it) before searching. query_key is
-- matching.ts memoryKey() ("Apples, raw" and "apple" share "apple"). grams_per_unit is the grams for one of
-- `unit` (null unit = a count of the food itself, "2 apples"); null when the portion was a weight.
--
-- meal_items.cronometer_items: per-item match summary (query, food, source, grams, kcal, quality, whether the AI
-- estimate stood in), so a wrong number can be traced to the food it came from.
--
-- Rollback: drop table public.food_match_memory; alter table public.meal_items drop column cronometer_items;

create table public.food_match_memory (
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  query_key text not null check (length(query_key) between 1 and 200),
  cronometer_food_id bigint not null,
  food_name text not null,
  grams_per_unit numeric check (grams_per_unit is null or grams_per_unit > 0),
  unit text,
  updated_at timestamptz not null default now(),
  primary key (user_id, query_key)
);

alter table public.food_match_memory enable row level security;

create policy food_match_memory_select_own on public.food_match_memory for select to authenticated using (user_id = (select auth.uid()));
create policy food_match_memory_insert_own on public.food_match_memory for insert to authenticated with check (user_id = (select auth.uid()));
create policy food_match_memory_update_own on public.food_match_memory for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy food_match_memory_delete_own on public.food_match_memory for delete to authenticated using (user_id = (select auth.uid()));

alter table public.meal_items add column cronometer_items jsonb;

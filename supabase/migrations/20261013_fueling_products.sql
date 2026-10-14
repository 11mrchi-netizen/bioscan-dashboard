-- Endurance Fueling & Personal Experiments, Phase 06.3.2: versioned fueling products/recipes. Unlike
-- supplement_products (mutates in place, "ended not deleted"), a recipe version is append-only: "editing" a recipe
-- means inserting a new version row, never updating one. A trigger refuses any update/delete on
-- fueling_recipe_versions outright, so a historical intake log's recipe_version_id can never change meaning --
-- a stronger guarantee than experiments_lock_protocol's column-subset lock, because here *nothing* about a
-- published version may change once inserted, not even non-identity fields.
-- Rollback: drop table public.fueling_recipe_ingredients; drop table public.fueling_recipe_versions;
--           drop table public.fueling_products;

create table public.fueling_products (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  kind text not null check (kind in ('gel', 'drink_mix', 'chew', 'bar', 'homemade', 'other')),
  name text not null,
  brand text,
  barcode text,
  created_at timestamptz not null default now()
);

create index fueling_products_user_idx on public.fueling_products (user_id);

create table public.fueling_recipe_versions (
  id bigint generated always as identity primary key,
  product_id bigint not null references public.fueling_products (id) on delete cascade,
  version integer not null,
  serving_size numeric,
  serving_unit text,
  -- carb-source split stays null unless known from a reliable label or an explicit recipe -- never invented
  carbs_g numeric,
  glucose_maltodextrin_g numeric,
  fructose_g numeric,
  fluid_ml numeric,
  sodium_mg numeric,
  caffeine_mg numeric,
  other_nutrients jsonb,
  source text,
  confidence numeric,
  last_verified_at date,
  created_at timestamptz not null default now(),
  unique (product_id, version)
);

create index fueling_recipe_versions_product_idx on public.fueling_recipe_versions (product_id, version);

-- Homemade recipes built from existing foods, reusing the canonical catalog rather than re-entering nutrition facts.
create table public.fueling_recipe_ingredients (
  id bigint generated always as identity primary key,
  recipe_version_id bigint not null references public.fueling_recipe_versions (id) on delete cascade,
  food_id bigint references public.foods (id) on delete set null,
  -- for an ingredient with no canonical food match (e.g. a specific commercial mix not in the food catalog)
  description text,
  quantity numeric,
  quantity_unit text
);

create index fueling_recipe_ingredients_version_idx on public.fueling_recipe_ingredients (recipe_version_id);

create or replace function public.fueling_recipe_versions_immutable()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  raise exception 'fueling_recipe_versions is append-only: insert a new version instead of changing this one';
end;
$$;

create trigger fueling_recipe_versions_no_update before update on public.fueling_recipe_versions
  for each row execute function public.fueling_recipe_versions_immutable();
create trigger fueling_recipe_versions_no_delete before delete on public.fueling_recipe_versions
  for each row execute function public.fueling_recipe_versions_immutable();

alter table public.fueling_products enable row level security;

create policy fueling_products_select_own on public.fueling_products for select to authenticated using (user_id = (select auth.uid()));
create policy fueling_products_insert_own on public.fueling_products for insert to authenticated with check (user_id = (select auth.uid()));
create policy fueling_products_update_own on public.fueling_products for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy fueling_products_delete_own on public.fueling_products for delete to authenticated using (user_id = (select auth.uid()));

alter table public.fueling_recipe_versions enable row level security;

-- Scoped via the parent product's ownership (this table has no user_id of its own). No update/delete policy at all:
-- the trigger above would refuse those operations anyway, but omitting the policies too means RLS itself blocks
-- them outright for every role, belt-and-suspenders with the trigger.
create policy fueling_recipe_versions_select_own on public.fueling_recipe_versions for select to authenticated
  using (exists (select 1 from public.fueling_products p where p.id = fueling_recipe_versions.product_id and p.user_id = (select auth.uid())));

create policy fueling_recipe_versions_insert_own on public.fueling_recipe_versions for insert to authenticated
  with check (exists (select 1 from public.fueling_products p where p.id = fueling_recipe_versions.product_id and p.user_id = (select auth.uid())));

alter table public.fueling_recipe_ingredients enable row level security;

create policy fueling_recipe_ingredients_select_own on public.fueling_recipe_ingredients for select to authenticated
  using (exists (
    select 1 from public.fueling_recipe_versions v join public.fueling_products p on p.id = v.product_id
    where v.id = fueling_recipe_ingredients.recipe_version_id and p.user_id = (select auth.uid())
  ));

create policy fueling_recipe_ingredients_insert_own on public.fueling_recipe_ingredients for insert to authenticated
  with check (exists (
    select 1 from public.fueling_recipe_versions v join public.fueling_products p on p.id = v.product_id
    where v.id = fueling_recipe_ingredients.recipe_version_id and p.user_id = (select auth.uid())
  ));

-- Ingredients belong to a version the same way the version itself is append-only: once a version (and its
-- ingredient breakdown) is published, it does not change. No update/delete policy here either.

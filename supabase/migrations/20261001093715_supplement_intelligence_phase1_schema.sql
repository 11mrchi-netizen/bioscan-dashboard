-- Supplement Intelligence Phase 1: Product -> Ingredients -> Nutrients.
-- Additive only -- existing supplements/supplement_log rows are never
-- rewritten. Personal (user_id + owner RLS), unlike foods/food_sources'
-- shared-catalog model, since there is no real shared SuppCo-backed catalog
-- yet (see DAV-328 audit).

create table public.supplement_sources (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  url text,
  created_at timestamptz not null default now(),
  unique (user_id, name)
);

create table public.supplement_ingredients (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  category text,
  -- Free-text key into the SAME nutrient-key space nutrient_intake/
  -- NUTRIENT_REFERENCE already use -- no enum, no FK, matches the existing
  -- loose convention (NUTRIENT_REFERENCE is a display/RDA registry, not an
  -- enforced type).
  nutrient_key text,
  created_at timestamptz not null default now(),
  unique (user_id, name)
);

create table public.supplement_products (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  supplement_source_id bigint references public.supplement_sources(id),
  source_product_id text,
  name text not null,
  brand text,
  barcode text,
  serving_size numeric,
  serving_unit text,
  serving_form text,
  -- 'high' (verified against a real label/external source) / 'low'
  -- (inferred, e.g. this migration's own backfill) / 'manual' (user-entered,
  -- not yet verified). Flags uncertain mappings for later review instead of
  -- silently presenting them as equally trustworthy.
  match_confidence text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.supplement_product_ingredients (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  supplement_product_id bigint not null references public.supplement_products(id) on delete cascade,
  supplement_ingredient_id bigint not null references public.supplement_ingredients(id),
  compound_amount numeric not null,
  compound_unit text not null,
  -- Null unless the real compound-vs-elemental split is actually known --
  -- never fabricated (e.g. magnesium bisglycinate's elemental magnesium
  -- content is NOT assumed equal to the compound mass).
  elemental_amount numeric,
  elemental_unit text,
  created_at timestamptz not null default now()
);

alter table public.supplements add column product_id bigint references public.supplement_products(id);
alter table public.supplement_log add column serving_count numeric default 1;

alter table public.supplement_sources enable row level security;
alter table public.supplement_ingredients enable row level security;
alter table public.supplement_products enable row level security;
alter table public.supplement_product_ingredients enable row level security;

create policy "Users can read own supplement sources" on public.supplement_sources for select using (auth.uid() = user_id);
create policy "Users can insert own supplement sources" on public.supplement_sources for insert with check (auth.uid() = user_id);
create policy "Users can update own supplement sources" on public.supplement_sources for update using (auth.uid() = user_id);
create policy "Users can delete own supplement sources" on public.supplement_sources for delete using (auth.uid() = user_id);

create policy "Users can read own supplement ingredients" on public.supplement_ingredients for select using (auth.uid() = user_id);
create policy "Users can insert own supplement ingredients" on public.supplement_ingredients for insert with check (auth.uid() = user_id);
create policy "Users can update own supplement ingredients" on public.supplement_ingredients for update using (auth.uid() = user_id);
create policy "Users can delete own supplement ingredients" on public.supplement_ingredients for delete using (auth.uid() = user_id);

create policy "Users can read own supplement products" on public.supplement_products for select using (auth.uid() = user_id);
create policy "Users can insert own supplement products" on public.supplement_products for insert with check (auth.uid() = user_id);
create policy "Users can update own supplement products" on public.supplement_products for update using (auth.uid() = user_id);
create policy "Users can delete own supplement products" on public.supplement_products for delete using (auth.uid() = user_id);

create policy "Users can read own supplement product ingredients" on public.supplement_product_ingredients for select using (auth.uid() = user_id);
create policy "Users can insert own supplement product ingredients" on public.supplement_product_ingredients for insert with check (auth.uid() = user_id);
create policy "Users can update own supplement product ingredients" on public.supplement_product_ingredients for update using (auth.uid() = user_id);
create policy "Users can delete own supplement product ingredients" on public.supplement_product_ingredients for delete using (auth.uid() = user_id);

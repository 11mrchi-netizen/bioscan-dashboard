alter table public.supplement_products
  add column if not exists servings_per_container numeric check (servings_per_container is null or servings_per_container > 0),
  add column if not exists suggested_use text,
  add column if not exists reorder_lead_days integer check (reorder_lead_days is null or reorder_lead_days between 0 and 180);

create unique index if not exists supplement_products_user_barcode_uq
  on public.supplement_products (user_id, barcode) where barcode is not null;

alter table public.supplements
  add column if not exists servings_per_dose numeric not null default 1 check (servings_per_dose > 0);

alter table public.supplement_source_snapshots
  add column if not exists applied_fields jsonb not null default '[]'::jsonb,
  add column if not exists applied_at timestamptz;

create table if not exists public.supplement_stock_events (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  supplement_product_id bigint not null references public.supplement_products(id) on delete cascade,
  kind text not null check (kind in ('purchase','adjustment','discard')),
  servings numeric not null,
  occurred_at timestamptz not null default now(),
  expires_on date,
  price numeric check (price is null or price >= 0),
  note text,
  created_at timestamptz not null default now(),
  check ((kind = 'purchase' and servings > 0) or (kind = 'discard' and servings < 0) or kind = 'adjustment')
);
create index if not exists supplement_stock_events_product_idx on public.supplement_stock_events (supplement_product_id, occurred_at);
create index if not exists supplement_stock_events_user_idx on public.supplement_stock_events (user_id);

alter table public.supplement_stock_events enable row level security;
create policy "stock_events_select_own" on public.supplement_stock_events for select to authenticated using (user_id = (select auth.uid()));
create policy "stock_events_insert_own" on public.supplement_stock_events for insert to authenticated with check (user_id = (select auth.uid()));
create policy "stock_events_update_own" on public.supplement_stock_events for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy "stock_events_delete_own" on public.supplement_stock_events for delete to authenticated using (user_id = (select auth.uid()));

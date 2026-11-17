create table public.supplement_source_snapshots (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  supplement_product_id bigint not null references public.supplement_products(id) on delete cascade,
  supplement_source_id bigint not null references public.supplement_sources(id) on delete cascade,
  source_product_id text,
  barcode text not null,
  retrieved_at timestamptz not null,
  source_version text,
  payload_hash text not null,
  payload jsonb not null default '[]'::jsonb,
  match_status text not null check (match_status in ('matched','conflict','not_found')),
  conflicts jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now()
);

create index supplement_source_snapshots_product_idx on public.supplement_source_snapshots (supplement_product_id);
create index supplement_source_snapshots_source_idx on public.supplement_source_snapshots (supplement_source_id);
create unique index supplement_source_snapshots_dedupe_idx
  on public.supplement_source_snapshots (user_id, supplement_product_id, supplement_source_id, payload_hash);

alter table public.supplement_source_snapshots enable row level security;
create policy "Users can read own supplement source snapshots" on public.supplement_source_snapshots for select using ((select auth.uid()) = user_id);
create policy "Users can insert own supplement source snapshots" on public.supplement_source_snapshots for insert with check ((select auth.uid()) = user_id);
create policy "Users can update own supplement source snapshots" on public.supplement_source_snapshots for update using ((select auth.uid()) = user_id);
create policy "Users can delete own supplement source snapshots" on public.supplement_source_snapshots for delete using ((select auth.uid()) = user_id);

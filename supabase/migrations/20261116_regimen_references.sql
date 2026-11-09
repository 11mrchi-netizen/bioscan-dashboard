-- Regimen phase 1b (docs/regimen/README.md): reference cards and owner review of the shared registries.
--
-- 1. regimen_references: one reference card per regimen item, fetched on demand from a public source and kept only for
--    items in the person's own roster (no catalogue): an FDA label (openFDA, identified through RxNorm) for medications,
--    hormones and approved peptides, or PubMed studies for research peptides with no label. The card keeps its source,
--    retrieval time and link; refreshing replaces it. Supplements keep their DSLD / SuppCo snapshots
--    (supplement_source_snapshots), which already work this way.
-- 2. registry_reviews: the owner's verdict on a row of the shared evidence_records / interaction_records registries.
--    Those tables are read-only (rows come from migrations) and seeded 'unreviewed'; a 'confirmed' verdict here lets the
--    app show that row as reviewed for this person, with the date and note. 'disputed' keeps it flagged with the note.
--
-- Rollback: drop table public.registry_reviews; drop table public.regimen_references;

create table public.regimen_references (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  supplement_id bigint not null references public.supplements (id) on delete cascade,
  source text not null check (source in ('fda_label', 'pubmed')),
  -- FDA label set id, or the PubMed search term.
  external_id text not null check (length(external_id) between 1 and 200),
  title text not null check (length(title) between 1 and 300),
  url text check (url is null or length(url) <= 500),
  rxcui text check (rxcui is null or rxcui ~ '^[0-9]{1,10}$'),
  ingredient text check (ingredient is null or length(ingredient) <= 200),
  half_life_hours numeric check (half_life_hours is null or half_life_hours > 0),
  -- The label's own words the half-life was read from, shown beside it.
  half_life_text text check (half_life_text is null or length(half_life_text) <= 600),
  -- The compact label (sections trimmed) or the list of studies.
  payload jsonb not null check (jsonb_typeof(payload) = 'object' and pg_column_size(payload) < 60000),
  retrieved_at timestamptz not null,
  created_at timestamptz not null default now(),
  unique (supplement_id)
);
create index regimen_references_user_idx on public.regimen_references (user_id);

alter table public.regimen_references enable row level security;
create policy "select own regimen references" on public.regimen_references for select using ((select auth.uid()) = user_id);
create policy "insert own regimen references" on public.regimen_references for insert with check (
  (select auth.uid()) = user_id
  and exists (select 1 from public.supplements s where s.id = supplement_id and s.user_id = (select auth.uid())));
create policy "update own regimen references" on public.regimen_references for update using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
create policy "delete own regimen references" on public.regimen_references for delete using ((select auth.uid()) = user_id);

create table public.registry_reviews (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users (id) on delete cascade,
  registry text not null check (registry in ('evidence', 'interaction')),
  record_id bigint not null,
  -- The registry row's version when it was reviewed: a later edit of the row asks for a new review.
  record_version integer,
  verdict text not null check (verdict in ('confirmed', 'disputed')),
  note text check (note is null or length(note) <= 1000),
  reviewed_at timestamptz not null default now(),
  unique (user_id, registry, record_id)
);

alter table public.registry_reviews enable row level security;
create policy "select own registry reviews" on public.registry_reviews for select using ((select auth.uid()) = user_id);
create policy "insert own registry reviews" on public.registry_reviews for insert with check ((select auth.uid()) = user_id);
create policy "update own registry reviews" on public.registry_reviews for update using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
create policy "delete own registry reviews" on public.registry_reviews for delete using ((select auth.uid()) = user_id);

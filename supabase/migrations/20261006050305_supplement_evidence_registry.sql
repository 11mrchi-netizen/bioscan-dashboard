-- Canonical ingredient identity, independent of the nutrient ledger vocabulary
-- (7 of 20 existing ingredients have no nutrient_key).
alter table public.supplement_ingredients add column canonical_key text;

update public.supplement_ingredients si
set canonical_key = v.k
from (values
  ('L-Tyrosine (Now Foods)','tyrosine'),
  ('Omega 3 Fish Oil (Now Foods)','omega_3'),
  ('Apigenin (Swanson)','apigenin'),
  ('Vitamin C (nutriplus)','vitamin_c'),
  ('Vitamin C','vitamin_c'),
  ('Triple Magnesium Complex (Swanson)','magnesium'),
  ('Boron (Nutricost)','boron'),
  ('Vitamin D3 (Now Food)','vitamin_d'),
  ('DIM Complex (Swanson)','dim'),
  ('Stinging Nettle Root (Swanson)','stinging_nettle_root'),
  ('Tart Cherry Concentrate','tart_cherry'),
  ('Tart Cherry Extract','tart_cherry'),
  ('Zinc Picolinate (Swanson)','zinc'),
  ('Glycine (Nutricost)','glycine'),
  ('Creatine','creatine'),
  ('Iron (California Gold Nutrition)','iron'),
  ('Tadalafil','tadalafil'),
  ('copper','copper'),
  ('Activated B Complex (Swanson)','vitamin_b_complex'),
  ('Calcium','calcium')
) as v(n, k)
where si.name = v.n;

-- New ingredients default to their nutrient_key so evidence can match them.
create function public.supplement_ingredient_default_canonical_key()
returns trigger language plpgsql as $$
begin
  new.canonical_key := coalesce(new.canonical_key, new.nutrient_key);
  return new;
end $$;

create trigger supplement_ingredients_default_canonical_key
before insert on public.supplement_ingredients
for each row execute function public.supplement_ingredient_default_canonical_key();

-- Shared, outcome-specific evidence catalog. Read-only to clients (reference
-- table convention: one SELECT policy, writes by migration / service role).
create table public.evidence_records (
  id bigint generated always as identity primary key,
  subject_type text not null check (subject_type in ('ingredient','intervention')),
  subject_key text not null,
  outcome_key text not null,
  outcome_metric text,
  claim_text text not null,
  effect_direction text check (effect_direction in ('increase','decrease','no_effect','mixed','unknown')),
  population text,
  dose_range text,
  form text,
  duration text,
  study_design text check (study_design in ('mechanistic','animal','observational','controlled_human','meta_analysis','guideline','user_observed')),
  evidence_tier text check (evidence_tier in ('A','B','C','D')),
  pmid_doi text,
  source text not null,
  caveats text,
  review_status text not null default 'unreviewed' check (review_status in ('unreviewed','reviewed')),
  review_date date,
  version integer not null default 1,
  created_at timestamptz not null default now()
);
create index evidence_records_subject_idx on public.evidence_records (subject_type, subject_key);

alter table public.evidence_records enable row level security;
create policy "Authenticated users can read evidence records"
  on public.evidence_records for select to authenticated using (true);

-- Seed: the 15 entries of the hard-coded OUTCOME_MAP (domain/Supplements.kt),
-- verbatim, flagged unreviewed with no tier/citation (none was ever recorded).
insert into public.evidence_records (subject_type, subject_key, outcome_key, claim_text, effect_direction, source, caveats) values
  ('ingredient','boron','free_testosterone','Free-T ↑ ~10-15%*','increase','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','zinc','testosterone_synthesis','Supports T synthesis*','unknown','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','stinging_nettle_root','shbg_binding','SHBG binding ↓*','decrease','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','dim','estrogen_metabolism','Estrogen metabolism support*','unknown','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','omega_3','hs_crp','hs-CRP ↓ — primary lever','decrease','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded.'),
  ('ingredient','tart_cherry','exercise_inflammation','Exercise-induced inflammation ↓','decrease','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded.'),
  ('ingredient','magnesium','sleep_onset_depth','Sleep onset/depth ↑*','increase','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','glycine','sleep_onset','Sleep onset ↑*','increase','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','apigenin','sleep_support','GABA-A modulation, sleep support*','unknown','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','creatine','power_output','Power output ↑ — well established','increase','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded.'),
  ('ingredient','tyrosine','stress_task_focus','Stress-task focus ↑*','increase','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded. * marked an uncertain claim.'),
  ('ingredient','iron','rbc_production','RBC production support','unknown','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded.'),
  ('ingredient','vitamin_d','hormonal_immune_support','Hormonal + immune support','unknown','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded.'),
  ('ingredient','vitamin_b_complex','methylation_cofactors','Methylation cofactors','unknown','legacy_outcome_map','Migrated from the hard-coded outcome map; no citation recorded.'),
  ('ingredient','tadalafil','erectile_function','Erectile fn. decoupled from stress — confirmed in data','unknown','legacy_outcome_map','Migrated from the hard-coded outcome map. This is a personal-data observation, not a literature finding.');

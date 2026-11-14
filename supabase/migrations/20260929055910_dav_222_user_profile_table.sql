create table user_profile (
  user_id uuid primary key references auth.users(id),
  date_of_birth date,
  sex text check (sex in ('male', 'female')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

comment on table user_profile is 'DAV-222/09A Aging Profile: account-level demographic facts. One row per account. Backs domain/comparison/PopulationContext(ageYears, sex, ...), which existed but was always constructed with nulls before this table.';
comment on column user_profile.date_of_birth is 'Source for chronological age in PhenoAge/KDM/functional-age calculations. Never estimated.';
comment on column user_profile.sex is 'male/female only -- matches the sex-specific reference tables (KDM, some functional-age norms) this milestone consumes. Null when not set; features requiring it degrade to an explicit unavailable state rather than guessing.';

alter table user_profile enable row level security;

create policy "Users can view own profile" on user_profile
  for select using (auth.uid() = user_id);
create policy "Users can insert own profile" on user_profile
  for insert with check (auth.uid() = user_id);
create policy "Users can update own profile" on user_profile
  for update using (auth.uid() = user_id);

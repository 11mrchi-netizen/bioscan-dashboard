create table public.exercise_library (
  id text primary key,
  name text not null,
  category text,
  force text,
  level text,
  mechanic text,
  equipment text,
  primary_muscles text[] not null default '{}',
  secondary_muscles text[] not null default '{}',
  created_at timestamptz not null default now()
);
alter table public.exercise_library enable row level security;
create policy "select exercise_library" on public.exercise_library
  for select to authenticated using (true);

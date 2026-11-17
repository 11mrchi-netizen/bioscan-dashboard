create table public.sexual_activity_daily (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  date date not null,
  activity_type text not null check (activity_type in ('masturbation', 'intercourse')),
  instances jsonb not null default '[]'::jsonb,
  notes text,
  created_at timestamptz not null default now(),
  unique (user_id, date, activity_type)
);

alter table public.sexual_activity_daily enable row level security;

create policy "Users can read own sexual activity" on public.sexual_activity_daily
  for select using (auth.uid() = user_id);
create policy "Users can insert own sexual activity" on public.sexual_activity_daily
  for insert with check (auth.uid() = user_id);
create policy "Users can update own sexual activity" on public.sexual_activity_daily
  for update using (auth.uid() = user_id);
create policy "Users can delete own sexual activity" on public.sexual_activity_daily
  for delete using (auth.uid() = user_id);

-- Backfill: each existing masturbation_log row becomes one element of that
-- day's 'masturbation' instances array. orgasm is a stated heuristic
-- (orgasm_intensity present => treated as an orgasm) since the old schema
-- never recorded a no-orgasm instance explicitly.
insert into public.sexual_activity_daily (user_id, date, activity_type, instances)
select
  user_id,
  (occurred_at at time zone 'utc')::date as date,
  'masturbation',
  jsonb_agg(
    jsonb_strip_nulls(jsonb_build_object(
      'orgasm', orgasm_intensity is not null,
      'orgasmIntensity', orgasm_intensity,
      'watchedPorn', watched_porn,
      'loadSize', load_size,
      'notes', notes
    ))
    order by occurred_at
  )
from public.masturbation_log
group by user_id, (occurred_at at time zone 'utc')::date;

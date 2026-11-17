create table public.training_settings (
  user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  bars jsonb not null default '[{"key":"olympic","name":"Olympic bar","kg":20},{"key":"trap","name":"Trap bar","kg":25}]',
  plates_kg jsonb not null default '[1.25,2.5,5,10,15,20]',
  training_max_fraction numeric not null default 0.9 check (training_max_fraction between 0.5 and 1),
  default_start_time time not null default '07:00',
  default_duration_min int not null default 60 check (default_duration_min between 10 and 360),
  second_start_time time not null default '20:00',
  second_duration_min int not null default 120 check (second_duration_min between 10 and 360),
  calendar_id text,
  timezone text,
  updated_at timestamptz not null default now()
);

create table public.training_definitions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  methodology text not null,
  kind text not null check (kind in ('strength_module','se_module','conditioning_session','conditioning_protocol','composition','template','system')),
  key text not null,
  version int not null default 1 check (version >= 1),
  schema_version int not null default 1,
  title text not null,
  body jsonb not null,
  source_ref text,
  status text not null default 'draft' check (status in ('draft','reviewed','retired')),
  reviewed_at timestamptz,
  created_at timestamptz not null default now(),
  unique (user_id, methodology, kind, key, version)
);

create table public.training_plans (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name text not null,
  methodology text not null,
  start_date date, end_date date,
  status text not null default 'draft' check (status in ('draft','active','completed','archived')),
  notes text,
  created_at timestamptz not null default now()
);

create table public.training_blocks (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  plan_id bigint references public.training_plans(id) on delete set null,
  position int,
  cycle_id bigint references public.training_cycles(id) on delete set null,
  origin text not null default 'generated' check (origin in ('generated','imported')),
  name text not null,
  template_key text, template_version int,
  components jsonb not null default '[]',
  definition_refs jsonb not null default '[]',
  definition_snapshot jsonb,
  choices jsonb not null default '{}',
  start_date date not null,
  end_date date not null check (end_date >= start_date),
  status text not null default 'draft' check (status in ('draft','scheduled','active','completed','abandoned')),
  benchmark jsonb, review jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index training_blocks_plan on public.training_blocks (plan_id);
create index training_blocks_cycle on public.training_blocks (cycle_id);
create index training_blocks_user_dates on public.training_blocks (user_id, start_date);

create table public.training_block_domains (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  block_id bigint not null references public.training_blocks(id) on delete cascade,
  domain text not null check (domain in ('max_strength','hypertrophy','strength_endurance','power','aerobic_base',
    'anaerobic_capacity','speed','work_capacity','specific_endurance','sport_skill','recovery')),
  role text not null check (role in ('primary','secondary','maintenance')),
  min_per_week numeric, target_per_week numeric, max_per_week numeric,
  unique (block_id, domain)
);

create table public.athlete_maxes (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  exercise_key text not null,
  exercise_name text not null,
  kind text not null check (kind in ('1rm','e1rm','rm_derived','training_max','max_reps')),
  value numeric not null check (value > 0),
  unit text not null check (unit in ('kg','reps')),
  as_of date not null,
  source text not null check (source in ('test','implied_logged_percent','estimate','manual','progression')),
  derivation jsonb not null default '{}',
  block_id bigint references public.training_blocks(id) on delete set null,
  created_at timestamptz not null default now()
);
create index athlete_maxes_lookup on public.athlete_maxes (user_id, exercise_key, as_of desc);
create index athlete_maxes_block on public.athlete_maxes (block_id);

create table public.planned_sessions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  block_id bigint not null references public.training_blocks(id) on delete cascade,
  revision int not null default 1,
  sequence_no int not null,
  week_index int not null, day_slot int not null, slot_in_day int not null default 1,
  domain text not null check (domain in ('max_strength','hypertrophy','strength_endurance','power','aerobic_base',
    'anaerobic_capacity','speed','work_capacity','specific_endurance','sport_skill','recovery')),
  work_kind text not null default 'progression' check (work_kind in ('progression','maintenance','test','deload','recovery')),
  module_ref text not null,
  title text not null,
  prescription jsonb not null default '[]',
  prescription_version int not null default 1,
  original_date date not null,
  scheduled_date date not null, start_time time, duration_min int,
  status text not null default 'planned' check (status in ('planned','confirmed','skipped','superseded')),
  confirmed_at timestamptz,
  actual_draft jsonb,
  exercise_session_id bigint references public.exercise_sessions(id) on delete set null,
  match_status text not null default 'none' check (match_status in ('none','pending','matched','manual')),
  match_confidence numeric check (match_confidence between 0 and 1),
  match_method text,
  gcal_calendar_id text, gcal_event_id text, gcal_pushed_hash text, gcal_pushed_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (block_id, revision, sequence_no)
);
create index planned_sessions_user_date on public.planned_sessions (user_id, scheduled_date);
create index planned_sessions_block on public.planned_sessions (block_id);
create unique index planned_sessions_one_per_actual on public.planned_sessions (exercise_session_id)
  where exercise_session_id is not null and status <> 'superseded';

create table public.planned_session_deviations (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  planned_session_id bigint not null references public.planned_sessions(id) on delete cascade,
  kind text not null check (kind in ('moved','skipped','substituted','modified','calendar_deleted')),
  source text not null default 'app' check (source in ('app','calendar')),
  from_date date, to_date date, reason text,
  detail jsonb not null default '{}',
  occurred_at timestamptz not null default now()
);
create index planned_session_deviations_session on public.planned_session_deviations (planned_session_id);

create function public.planned_sessions_immutable() returns trigger language plpgsql set search_path = public as $$
begin
  if new.sequence_no is distinct from old.sequence_no
     or new.original_date is distinct from old.original_date
     or new.prescription is distinct from old.prescription
     or new.block_id is distinct from old.block_id then
    raise exception 'planned_sessions: sequence_no, original_date, prescription and block_id are immutable (create a new revision)';
  end if;
  new.updated_at := now();
  return new;
end $$;
create trigger planned_sessions_immutable before update on public.planned_sessions
  for each row execute function public.planned_sessions_immutable();

create function public.training_definitions_frozen() returns trigger language plpgsql set search_path = public as $$
begin
  if old.status = 'reviewed' and new.body is distinct from old.body then
    raise exception 'training_definitions: a reviewed definition is immutable (create a new version)';
  end if;
  return new;
end $$;
create trigger training_definitions_frozen before update on public.training_definitions
  for each row execute function public.training_definitions_frozen();

do $$
declare t text;
begin
  foreach t in array array['training_settings','training_definitions','training_plans','training_blocks',
    'training_block_domains','athlete_maxes','planned_sessions','planned_session_deviations'] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('create policy %I on public.%I for select to authenticated using (user_id = (select auth.uid()))', t || '_select_own', t);
    execute format('create policy %I on public.%I for insert to authenticated with check (user_id = (select auth.uid()))', t || '_insert_own', t);
    execute format('create policy %I on public.%I for update to authenticated using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()))', t || '_update_own', t);
    execute format('create policy %I on public.%I for delete to authenticated using (user_id = (select auth.uid()))', t || '_delete_own', t);
  end loop;
end $$;

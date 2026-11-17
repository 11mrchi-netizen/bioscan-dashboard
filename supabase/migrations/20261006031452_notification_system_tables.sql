create table public.notification_rules (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  kind text not null check (kind in ('supplement_due','checkin_morning','checkin_evening')),
  enabled boolean not null default true,
  schedule jsonb not null default '{}'::jsonb,
  conditions jsonb not null default '{}'::jsonb,
  limits jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, kind)
);

create table public.notification_prefs (
  user_id uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  master_enabled boolean not null default true,
  quiet_start text not null default '23:00',
  quiet_end text not null default '07:00',
  respect_sleep boolean not null default true,
  daily_cap integer not null default 6 check (daily_cap between 1 and 50),
  timezone text,
  updated_at timestamptz not null default now()
);

create table public.notification_log (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  rule_kind text not null,
  fired_at timestamptz not null default now(),
  outcome text not null check (outcome in ('shown','suppressed','actioned','snoozed','dismissed','expired')),
  action text,
  dedupe_key text
);

create index notification_log_user_fired_idx on public.notification_log (user_id, fired_at desc);
create unique index notification_log_user_dedupe_idx on public.notification_log (user_id, dedupe_key) where dedupe_key is not null;

alter table public.notification_rules enable row level security;
alter table public.notification_prefs enable row level security;
alter table public.notification_log enable row level security;

create policy "Users can read own notification rules" on public.notification_rules for select using ((select auth.uid()) = user_id);
create policy "Users can insert own notification rules" on public.notification_rules for insert with check ((select auth.uid()) = user_id);
create policy "Users can update own notification rules" on public.notification_rules for update using ((select auth.uid()) = user_id);
create policy "Users can delete own notification rules" on public.notification_rules for delete using ((select auth.uid()) = user_id);

create policy "Users can read own notification prefs" on public.notification_prefs for select using ((select auth.uid()) = user_id);
create policy "Users can insert own notification prefs" on public.notification_prefs for insert with check ((select auth.uid()) = user_id);
create policy "Users can update own notification prefs" on public.notification_prefs for update using ((select auth.uid()) = user_id);
create policy "Users can delete own notification prefs" on public.notification_prefs for delete using ((select auth.uid()) = user_id);

create policy "Users can read own notification log" on public.notification_log for select using ((select auth.uid()) = user_id);
create policy "Users can insert own notification log" on public.notification_log for insert with check ((select auth.uid()) = user_id);
create policy "Users can update own notification log" on public.notification_log for update using ((select auth.uid()) = user_id);
create policy "Users can delete own notification log" on public.notification_log for delete using ((select auth.uid()) = user_id);

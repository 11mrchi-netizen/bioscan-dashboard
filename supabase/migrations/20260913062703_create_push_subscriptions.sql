
create table public.push_subscriptions (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  endpoint text not null,
  p256dh_key text not null,
  auth_key text not null,
  quicklog_token text not null default encode(gen_random_bytes(24), 'base64url'),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, endpoint)
);

alter table public.push_subscriptions enable row level security;

create policy "Users can view own push subscriptions"
  on public.push_subscriptions for select
  using ((select auth.uid()) = user_id);

create policy "Users can insert own push subscriptions"
  on public.push_subscriptions for insert
  with check ((select auth.uid()) = user_id);

create policy "Users can update own push subscriptions"
  on public.push_subscriptions for update
  using ((select auth.uid()) = user_id);

create policy "Users can delete own push subscriptions"
  on public.push_subscriptions for delete
  using ((select auth.uid()) = user_id);

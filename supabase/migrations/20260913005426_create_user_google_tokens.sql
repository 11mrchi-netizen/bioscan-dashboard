create table user_google_tokens (
  user_id uuid primary key references auth.users(id) on delete cascade,
  refresh_token text not null,
  updated_at timestamptz not null default now()
);

alter table user_google_tokens enable row level security;

create policy "select own google token" on user_google_tokens
  for select using ((select auth.uid()) = user_id);

create policy "insert own google token" on user_google_tokens
  for insert with check ((select auth.uid()) = user_id);

create policy "update own google token" on user_google_tokens
  for update using ((select auth.uid()) = user_id);

comment on table user_google_tokens is 'Stores only the long-lived Google OAuth refresh token per user, used by the refresh-google-token Edge Function to mint short-lived access tokens on demand for Calendar/Drive API calls. The Google access token itself is never persisted here or anywhere else -- requested fresh each time and discarded after use.';

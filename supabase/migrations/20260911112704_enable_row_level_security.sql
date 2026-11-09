-- Enable RLS on every table, then add policies so an authenticated user can
-- only ever see/modify their own rows (matched via auth.uid()). This is what
-- makes the anon key safe to embed in client-side JS later — the key alone
-- grants no access; only a valid authenticated session for that specific row's
-- user_id does.

alter table injuries enable row level security;
alter table supplements enable row level security;
alter table lab_draws enable row level security;
alter table lab_results enable row level security;
alter table sync_log enable row level security;

create policy "select own injuries" on injuries for select using (auth.uid() = user_id);
create policy "insert own injuries" on injuries for insert with check (auth.uid() = user_id);
create policy "update own injuries" on injuries for update using (auth.uid() = user_id);
create policy "delete own injuries" on injuries for delete using (auth.uid() = user_id);

create policy "select own supplements" on supplements for select using (auth.uid() = user_id);
create policy "insert own supplements" on supplements for insert with check (auth.uid() = user_id);
create policy "update own supplements" on supplements for update using (auth.uid() = user_id);
create policy "delete own supplements" on supplements for delete using (auth.uid() = user_id);

create policy "select own lab_draws" on lab_draws for select using (auth.uid() = user_id);
create policy "insert own lab_draws" on lab_draws for insert with check (auth.uid() = user_id);
create policy "update own lab_draws" on lab_draws for update using (auth.uid() = user_id);
create policy "delete own lab_draws" on lab_draws for delete using (auth.uid() = user_id);

create policy "select own lab_results" on lab_results for select using (auth.uid() = user_id);
create policy "insert own lab_results" on lab_results for insert with check (auth.uid() = user_id);
create policy "update own lab_results" on lab_results for update using (auth.uid() = user_id);
create policy "delete own lab_results" on lab_results for delete using (auth.uid() = user_id);

create policy "select own sync_log" on sync_log for select using (auth.uid() = user_id);
create policy "insert own sync_log" on sync_log for insert with check (auth.uid() = user_id);

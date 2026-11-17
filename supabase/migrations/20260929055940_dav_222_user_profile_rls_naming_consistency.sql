drop policy "Users can view own profile" on user_profile;
drop policy "Users can insert own profile" on user_profile;
drop policy "Users can update own profile" on user_profile;

create policy "select own user_profile" on user_profile for select using (auth.uid() = user_id);
create policy "insert own user_profile" on user_profile for insert with check (auth.uid() = user_id);
create policy "update own user_profile" on user_profile for update using (auth.uid() = user_id);
create policy "delete own user_profile" on user_profile for delete using (auth.uid() = user_id);

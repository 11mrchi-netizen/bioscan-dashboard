-- nutrition-backfill: a review run can be limited to some entry sources, e.g. typed entries only, so photo meals are not
-- re-estimated again:  select private.run_nutrition_backfill(null, array['ai_text_enriched']);
-- An overload of private.run_nutrition_backfill(uuid) from 20261021_nutrition_backfill_review.sql (which stays, for all
-- sources); no defaults here, so a call with one or no arguments still resolves to that one.
-- Rollback: drop function private.run_nutrition_backfill(uuid, text[]);

create function private.run_nutrition_backfill(target_user uuid, only_sources text[])
returns uuid
language plpgsql
security definer
set search_path to 'public', 'extensions'
as $$
declare
  backfill_secret text;
  run uuid := gen_random_uuid();
begin
  select decrypted_secret into backfill_secret from vault.decrypted_secrets where name = 'nutrition_backfill_secret';
  if backfill_secret is null then
    raise exception 'nutrition_backfill_secret not found in vault';
  end if;

  perform net.http_post(
    url := 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/nutrition-backfill',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      -- public anon key: only satisfies the gateway's JWT check, grants no access by itself
      'Authorization', 'Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVnZnJnbGJjb2l2a3ByanF2anp6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODkxMDYxOTAsImV4cCI6MjEwNDY4MjE5MH0.0G_5XnBiIsUTQG7vlSTARND8apI-mI6RdUytCSWY9To',
      'x-nutrition-backfill', backfill_secret
    ),
    body := jsonb_build_object('action', 'review', 'run_id', run, 'user_id', target_user, 'sources', to_jsonb(only_sources))
  );
  return run;
end;
$$;

revoke all on function private.run_nutrition_backfill(uuid, text[]) from public, anon, authenticated;

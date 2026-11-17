
create schema if not exists private;

create or replace function private.send_daily_reminder(reminder_type text)
returns void
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  service_key text;
  uid uuid;
  already_logged boolean;
  payload jsonb;
begin
  select decrypted_secret into service_key from vault.decrypted_secrets where name = 'service_role_key';
  if service_key is null then
    raise notice 'service_role_key not found in vault, skipping reminder %', reminder_type;
    return;
  end if;

  for uid in select distinct user_id from public.push_subscriptions where active = true loop

    if reminder_type = 'morning_wood' then
      select exists(
        select 1 from public.arousal_daily
        where user_id = uid and date = current_date and morning_erection_quality is not null
      ) into already_logged;
      payload := jsonb_build_object(
        'user_id', uid,
        'title', 'Morning check-in',
        'message', 'Log this morning''s wood.',
        'tag', 'morning_wood',
        'actions', jsonb_build_array(
          jsonb_build_object('action','morning_wood_yes','title','Yes'),
          jsonb_build_object('action','morning_wood_no','title','No')
        )
      );

    elsif reminder_type = 'arousal' then
      select exists(
        select 1 from public.arousal_daily
        where user_id = uid and date = current_date and arousal_level is not null
      ) into already_logged;
      payload := jsonb_build_object(
        'user_id', uid,
        'title', 'Arousal check-in',
        'message', 'How aroused have you felt today?',
        'tag', 'arousal',
        'actions', jsonb_build_array(
          jsonb_build_object('action','arousal_low','title','Low'),
          jsonb_build_object('action','arousal_high','title','High')
        )
      );

    elsif reminder_type = 'stool' then
      select exists(
        select 1 from public.stool_log
        where user_id = uid and occurred_at::date = current_date
      ) into already_logged;
      payload := jsonb_build_object(
        'user_id', uid,
        'title', 'Stool check-in',
        'message', 'Log today''s bathroom trip.',
        'tag', 'stool',
        'actions', jsonb_build_array(
          jsonb_build_object('action','stool_normal','title','Normal'),
          jsonb_build_object('action','open_app','title','Details')
        )
      );

    else
      return;
    end if;

    if not already_logged then
      perform net.http_post(
        url := 'https://ugfrglbcoivkprjqvjzz.supabase.co/functions/v1/send-push',
        headers := jsonb_build_object('Content-Type','application/json','Authorization','Bearer ' || service_key),
        body := payload
      );
    end if;

  end loop;
end;
$$;

select cron.schedule('daily-reminder-morning-wood', '0 13 * * *', $$select private.send_daily_reminder('morning_wood')$$);
select cron.schedule('daily-reminder-arousal', '0 1 * * *', $$select private.send_daily_reminder('arousal')$$);
select cron.schedule('daily-reminder-stool', '30 1 * * *', $$select private.send_daily_reminder('stool')$$);

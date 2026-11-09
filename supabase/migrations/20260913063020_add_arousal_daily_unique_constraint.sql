alter table public.arousal_daily add constraint arousal_daily_user_date_unique unique (user_id, date);

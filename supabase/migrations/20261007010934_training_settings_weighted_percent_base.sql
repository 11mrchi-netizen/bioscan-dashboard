alter table public.training_settings
  add column weighted_percent_base text not null default 'total' check (weighted_percent_base in ('total','added'));

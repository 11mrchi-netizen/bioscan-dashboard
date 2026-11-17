alter table public.wearable_daily
  add column if not exists calories_active integer,
  add column if not exists calories_total integer,
  add column if not exists spo2_avg numeric,
  add column if not exists bmr numeric,
  add column if not exists activity_intensity_min integer;

comment on column public.wearable_daily.calories_active is 'Health Connect ActiveCaloriesBurnedRecord, kcal';
comment on column public.wearable_daily.calories_total is 'Health Connect TotalCaloriesBurnedRecord, kcal';
comment on column public.wearable_daily.spo2_avg is 'Health Connect OxygenSaturationRecord, daily average, percent';
comment on column public.wearable_daily.bmr is 'Health Connect BasalMetabolicRateRecord, kcal/day';
comment on column public.wearable_daily.activity_intensity_min is 'Health Connect ActivityIntensityRecord -- column reserved, not yet populated: that record type is absent from connect-client:1.1.0 stable (confirmed by extracting the real AAR), only in 1.2.0-alpha. Deliberately NOT the same thing as zone_minutes (an unconfirmed-definition metric from the old Wellness Project sync) -- kept separate rather than conflated.';

alter table public.body_metrics
  add column if not exists height_cm numeric;

comment on column public.body_metrics.height_cm is 'Health Connect HeightRecord, cm';

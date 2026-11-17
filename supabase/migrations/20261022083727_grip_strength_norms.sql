-- Handgrip strength norms for the Healthspan strength factor (docs/analysis-layer-3/healthspan.md).
-- Source: Dodds RM, Syddall HE, Cooper R, et al. Grip strength across the life course: normative data from twelve British
-- studies. PLoS ONE 2014;9(12):e113637. doi:10.1371/journal.pone.0113637. Table 2, retrieved in full through PubMed
-- Central (PMC4256164). Open access under CC BY.
--
-- What the table gives: the 10th, 25th, 50th, 75th and 90th centiles in whole kg, by sex, modelled (GAMLSS, BCCG) at the
-- exact ages 20, 25, ..., 90 from 60,803 observations of 49,964 people in 12 general-population studies in Great Britain.
-- The grip is the maximum of all trials (both hands in 10 of the 12 studies), as this app scores the stronger hand.
--
-- How it is stored: each tabulated age becomes the band age-2..age+2 (18-22, 23-27, ..., 88-92), so a person is placed on
-- the nearest tabulated age; the paper publishes no centiles under 20 for adults or at 95+, so nobody is placed past 92.
-- sample_size is the paper's observation count within 2.5 years of that age. Values are copied as published; nothing is
-- estimated. source_tier B: pooled cohorts, not one probability sample.
--
-- Guarded: inserts nothing when any grip_strength row already exists.
-- Rollback: delete from public.benchmark_artifacts where metric = 'grip_strength' and source_name like 'Dodds et al. 2014%';

insert into public.benchmark_artifacts (
  metric, artifact_type, source_name, source_tier, population, measurement_protocol, device_or_algorithm, sample_size,
  quantiles, directionality, supports_percentile, access_license, publication_date, artifact_version
)
select
  'grip_strength', 'quantile_table',
  'Dodds et al. 2014, Grip strength across the life course: normative data from twelve British studies. PLoS ONE 9(12):e113637, Table 2',
  'B',
  jsonb_build_object('sex', v.sex, 'age_low', v.age - 2, 'age_high', v.age + 2, 'geography', 'GB',
    'sampling_frame', 'Twelve pooled general-population studies, Great Britain, 1990-2012; centiles at exact age ' || v.age || ', used for the nearest tabulated age'),
  'Maximum of all trials (3, 4 or 6 values), both hands in 10 of 12 studies; seated in 8 studies, standing in the others',
  'Jamar, Smedley, Nottingham electronic, Harpenden or Takei handgrip dynamometer',
  v.n,
  jsonb_build_object('10', v.p10, '25', v.p25, '50', v.p50, '75', v.p75, '90', v.p90),
  'HIGHER_BETTER', true, 'Open access, CC BY (PLoS ONE)', date '2014-12-04', 1
from (values
  ('male', 20, 354, 30, 35, 40, 46, 52),
  ('male', 25, 574, 36, 41, 48, 55, 61),
  ('male', 30, 984, 38, 44, 51, 58, 64),
  ('male', 35, 1380, 39, 45, 51, 58, 64),
  ('male', 40, 880, 38, 44, 50, 57, 63),
  ('male', 45, 798, 36, 42, 49, 56, 61),
  ('male', 50, 820, 35, 41, 48, 54, 60),
  ('male', 55, 3743, 34, 40, 47, 53, 59),
  ('male', 60, 2683, 33, 39, 45, 51, 56),
  ('male', 65, 3947, 31, 37, 43, 48, 53),
  ('male', 70, 3286, 29, 34, 39, 44, 49),
  ('male', 75, 1883, 26, 31, 35, 41, 45),
  ('male', 80, 1115, 23, 27, 32, 37, 42),
  ('male', 85, 1134, 19, 24, 29, 33, 38),
  ('male', 90, 431, 16, 20, 25, 29, 33),
  ('female', 20, 463, 21, 24, 28, 32, 36),
  ('female', 25, 870, 23, 26, 30, 35, 38),
  ('female', 30, 1423, 24, 27, 31, 35, 39),
  ('female', 35, 1785, 23, 27, 31, 35, 39),
  ('female', 40, 968, 23, 27, 31, 35, 39),
  ('female', 45, 952, 22, 26, 30, 34, 38),
  ('female', 50, 1019, 21, 25, 29, 33, 37),
  ('female', 55, 4250, 19, 23, 28, 32, 35),
  ('female', 60, 2943, 18, 22, 27, 31, 34),
  ('female', 65, 4171, 17, 21, 25, 29, 33),
  ('female', 70, 3473, 16, 20, 24, 27, 31),
  ('female', 75, 2135, 14, 18, 21, 25, 28),
  ('female', 80, 1361, 13, 16, 19, 23, 26),
  ('female', 85, 1632, 11, 14, 17, 20, 23),
  ('female', 90, 702, 9, 11, 14, 17, 20)
) as v (sex, age, n, p10, p25, p50, p75, p90)
where not exists (select 1 from public.benchmark_artifacts where metric = 'grip_strength');

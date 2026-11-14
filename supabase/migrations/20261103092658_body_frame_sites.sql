-- Frame size for the muscle-potential ceiling (Casey Butt 2009): wrist and ankle girths join the body check-in. Bone
-- girths barely change, so the check-in asks for them until they are recorded once and then only on request. One reading
-- site each, taken on the right side, stored with side 'none' like the trunk girths.
--
-- Rollback: delete from public.body_measurements where site in ('wrist', 'ankle');
--           restore both constraints below without 'wrist' and 'ankle' (20261019_body_checkins.sql).

alter table public.body_measurements drop constraint body_measurements_site_check;
alter table public.body_measurements add constraint body_measurements_site_check check (site in (
  'neck', 'shoulders', 'chest', 'waist_navel', 'waist_midpoint', 'hips',
  'biceps_relaxed', 'biceps_flexed', 'forearm', 'wrist', 'thigh', 'calf', 'ankle',
  'sf_abdominal', 'sf_suprailiac', 'sf_thigh', 'sf_chest'));

alter table public.body_measurements drop constraint body_measurements_side_matches_site;
alter table public.body_measurements add constraint body_measurements_side_matches_site check (
  (side = 'none') = (site in ('neck', 'shoulders', 'chest', 'waist_navel', 'waist_midpoint', 'hips', 'wrist', 'ankle')));

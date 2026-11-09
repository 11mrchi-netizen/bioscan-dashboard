insert into public.training_blocks (user_id, cycle_id, origin, name, start_date, end_date, status, choices)
select c.user_id, c.id, 'imported',
  coalesce(nullif(f->>'template', ''), 'Block ' || c.id),
  c.start_date, c.end_date,
  case when c.end_date < current_date then 'completed'
       when c.start_date <= current_date then 'active'
       else 'scheduled' end,
  jsonb_build_object('imported_focus', f)
from public.training_cycles c
cross join lateral (select c.notes::jsonb -> 'notion_import_focus' as f) x
where not exists (select 1 from public.training_blocks b where b.cycle_id = c.id);

with m(k, domain, maint) as (values
  ('max_strength','max_strength',false),
  ('strength_maintenance','max_strength',true),
  ('aerobic_maintenance','aerobic_base',true),
  ('aerobic_capacity','aerobic_base',false),
  ('work_capacity','work_capacity',false),
  ('endurance','specific_endurance',false)),
src as (
  select b.id block_id, b.user_id, 1 ord, 'primary' as slot, b.choices->'imported_focus'->>'primary' k from public.training_blocks b where b.origin = 'imported'
  union all
  select b.id, b.user_id, 2, 'secondary', b.choices->'imported_focus'->>'secondary' from public.training_blocks b where b.origin = 'imported'
),
resolved as (
  select s.block_id, s.user_id, m.domain,
         case when s.slot = 'primary' then 'primary' when m.maint then 'maintenance' else 'secondary' end as role,
         row_number() over (partition by s.block_id, m.domain order by s.ord) rn
  from src s join m on m.k = s.k
)
insert into public.training_block_domains (user_id, block_id, domain, role)
select user_id, block_id, domain, role from resolved where rn = 1
on conflict (block_id, domain) do nothing;

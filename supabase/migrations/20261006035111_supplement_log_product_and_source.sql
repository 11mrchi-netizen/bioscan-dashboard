alter table public.supplement_log
  add column product_id bigint references public.supplement_products(id) on delete set null,
  add column source text check (source in ('manual','notification','quick_log'));

create index supplement_log_product_id_idx on public.supplement_log (product_id);

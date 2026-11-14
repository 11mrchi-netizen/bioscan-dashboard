-- Label scans: a packaged food no database knows (most Taiwanese products) is saved into the catalog from a photo of
-- its nutrition label, by nutrition-barcode-lookup's save_label mode, as source 'label_scan' keyed by barcode
-- (unique food_source_id + source_food_id, so a re-scan replaces it). contributed_by records who confirmed it.
-- The food_sources row is created by the function on first use. Catalog RLS stays SELECT-only for users.
-- Rollback: alter table public.foods drop column contributed_by;

alter table public.foods add column if not exists contributed_by uuid references auth.users (id) on delete set null;

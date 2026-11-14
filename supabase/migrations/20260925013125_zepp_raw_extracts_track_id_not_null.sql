-- A nullable track_id would break upsert idempotency: Postgres unique constraints
-- never treat two NULLs as a conflict, so every re-run of a non-detail metric
-- (track_id always null) would insert a fresh row instead of updating the existing
-- one. Use '' as the "no track id" sentinel instead so the (user_id, metric,
-- query_date, track_id) constraint actually enforces uniqueness for those rows too.
update zepp_raw_extracts set track_id = '' where track_id is null;
alter table zepp_raw_extracts alter column track_id set default '';
alter table zepp_raw_extracts alter column track_id set not null;

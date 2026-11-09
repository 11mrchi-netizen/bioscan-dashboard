ALTER TABLE supplement_log ADD COLUMN dose_value double precision;
ALTER TABLE supplement_log ADD COLUMN dose_unit text;

-- Backfill from the roster's own free-text dose, parsed into a leading
-- numeric value + the remaining text as the unit (e.g. "500 mg" -> 500/"mg",
-- "36 mg, 3x/week" -> 36/"mg, 3x/week" -- the frequency note is preserved in
-- the unit rather than dropped).
UPDATE supplement_log sl
SET dose_value = (regexp_match(s.dose, '^([0-9.]+)\s*(.*)$'))[1]::double precision,
    dose_unit = nullif((regexp_match(s.dose, '^([0-9.]+)\s*(.*)$'))[2], '')
FROM supplements s
WHERE sl.supplement_id = s.id
  AND s.dose ~ '^[0-9.]+';

-- Any roster dose string that doesn't start with a number (none currently,
-- but real going forward) keeps the whole string as the unit rather than
-- silently dropping it.
UPDATE supplement_log sl
SET dose_unit = s.dose
FROM supplements s
WHERE sl.supplement_id = s.id
  AND sl.dose_value IS NULL
  AND sl.dose_unit IS NULL
  AND s.dose IS NOT NULL;

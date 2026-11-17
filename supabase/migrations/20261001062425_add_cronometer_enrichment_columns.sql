
ALTER TABLE meal_items ADD COLUMN cronometer_nutrients JSONB DEFAULT NULL;
ALTER TABLE meal_items ADD COLUMN cronometer_source TEXT DEFAULT NULL;
COMMENT ON COLUMN meal_items.cronometer_nutrients IS 'Full nutrient map from Cronometer enrichment (80+ micronutrients as {nutrient_name: amount})';
COMMENT ON COLUMN meal_items.cronometer_source IS 'Cronometer food match source, e.g. NCCDB, USDAsr, FDC:Branded';

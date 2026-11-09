
-- Normalized nutrient intake ledger. Both meal items (via Cronometer
-- enrichment) and supplements write here, enabling unified daily
-- nutrient totals across all intake sources.

CREATE TABLE nutrient_intake (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id),
    logged_at TIMESTAMPTZ NOT NULL,
    nutrient TEXT NOT NULL,
    amount DOUBLE PRECISION NOT NULL,
    unit TEXT NOT NULL,
    source_type TEXT NOT NULL CHECK (source_type IN ('meal_item', 'supplement')),
    source_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE nutrient_intake ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can read own nutrient_intake"
    ON nutrient_intake FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "Users can insert own nutrient_intake"
    ON nutrient_intake FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can delete own nutrient_intake"
    ON nutrient_intake FOR DELETE
    USING (auth.uid() = user_id);

CREATE INDEX idx_nutrient_intake_user_date
    ON nutrient_intake (user_id, logged_at);

CREATE INDEX idx_nutrient_intake_nutrient
    ON nutrient_intake (user_id, nutrient, logged_at);

CREATE INDEX idx_nutrient_intake_source
    ON nutrient_intake (source_type, source_id);

-- Nutrient profile per supplement in the roster. Defines what canonical
-- nutrients each supplement provides per dose, so supplement_log entries
-- can generate nutrient_intake rows automatically.

CREATE TABLE supplement_nutrients (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplement_id BIGINT NOT NULL REFERENCES supplements(id) ON DELETE CASCADE,
    nutrient TEXT NOT NULL,
    amount_per_dose DOUBLE PRECISION NOT NULL,
    unit TEXT NOT NULL,
    UNIQUE(supplement_id, nutrient)
);

ALTER TABLE supplement_nutrients ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can read own supplement_nutrients"
    ON supplement_nutrients FOR SELECT
    USING (EXISTS (
        SELECT 1 FROM supplements s
        WHERE s.id = supplement_nutrients.supplement_id
        AND s.user_id = auth.uid()
    ));

CREATE POLICY "Users can insert own supplement_nutrients"
    ON supplement_nutrients FOR INSERT
    WITH CHECK (EXISTS (
        SELECT 1 FROM supplements s
        WHERE s.id = supplement_nutrients.supplement_id
        AND s.user_id = auth.uid()
    ));

CREATE POLICY "Users can update own supplement_nutrients"
    ON supplement_nutrients FOR UPDATE
    USING (EXISTS (
        SELECT 1 FROM supplements s
        WHERE s.id = supplement_nutrients.supplement_id
        AND s.user_id = auth.uid()
    ));

CREATE POLICY "Users can delete own supplement_nutrients"
    ON supplement_nutrients FOR DELETE
    USING (EXISTS (
        SELECT 1 FROM supplements s
        WHERE s.id = supplement_nutrients.supplement_id
        AND s.user_id = auth.uid()
    ));

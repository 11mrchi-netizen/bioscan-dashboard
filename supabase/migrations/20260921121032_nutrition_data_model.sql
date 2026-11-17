
-- =============================================================
-- DAV-161: Nutrition data model
-- Creates the canonical food catalog and meal-item decomposition
-- layer while preserving the existing meals table intact.
-- =============================================================

-- 1. food_sources — dataset registry
-- One row per imported dataset or manual-entry origin.
CREATE TABLE public.food_sources (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        text   NOT NULL UNIQUE,
    version     text,
    release_date date,
    license     text,
    attribution text,
    url         text,
    country     text,
    language    text   DEFAULT 'en',
    created_at  timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.food_sources IS 'Registry of food-composition data sources (USDA, Taiwan TFNCD, Open Food Facts, manual).';

-- Seed the "manual" source so human-entered foods always have an origin.
INSERT INTO public.food_sources (name, attribution)
VALUES ('manual', 'User-entered food data');

-- 2. foods — canonical food entries
CREATE TABLE public.foods (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    food_source_id  bigint     NOT NULL REFERENCES public.food_sources(id),
    source_food_id  text,
    name            text       NOT NULL,
    name_zh         text,
    aliases         text[],
    brand           text,
    barcode         text,
    category        text,
    subcategory     text,
    preparation     text,
    is_composite    boolean    NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (food_source_id, source_food_id)
);

CREATE INDEX foods_name_idx        ON public.foods USING gin (to_tsvector('english', name));
CREATE INDEX foods_barcode_idx     ON public.foods (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX foods_source_id_idx   ON public.foods (food_source_id);
CREATE INDEX foods_category_idx    ON public.foods (category) WHERE category IS NOT NULL;

COMMENT ON TABLE public.foods IS 'Canonical food entries linked to a data source. source_food_id is the upstream identifier (e.g. USDA FDC ID).';

-- 3. food_nutrients — per-food nutrient data
-- Amounts are per the stated basis (default 100g).
CREATE TABLE public.food_nutrients (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    food_id     bigint  NOT NULL REFERENCES public.foods(id) ON DELETE CASCADE,
    nutrient    text    NOT NULL,
    amount      numeric NOT NULL,
    unit        text    NOT NULL,
    basis_qty   numeric NOT NULL DEFAULT 100,
    basis_unit  text    NOT NULL DEFAULT 'g',
    UNIQUE (food_id, nutrient)
);

CREATE INDEX food_nutrients_food_id_idx ON public.food_nutrients (food_id);

COMMENT ON TABLE public.food_nutrients IS 'Nutrient composition per food, stored per basis_qty (default 100g). Supports calories, macros, and extensible micronutrients.';

-- 4. food_servings — serving unit to gram/ml equivalences
CREATE TABLE public.food_servings (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    food_id        bigint  NOT NULL REFERENCES public.foods(id) ON DELETE CASCADE,
    serving_name   text    NOT NULL,
    grams          numeric,
    ml             numeric,
    UNIQUE (food_id, serving_name),
    CONSTRAINT serving_has_weight_or_volume CHECK (grams IS NOT NULL OR ml IS NOT NULL)
);

CREATE INDEX food_servings_food_id_idx ON public.food_servings (food_id);

COMMENT ON TABLE public.food_servings IS 'Serving-size definitions per food (e.g. "1 cup" = 240g). At least one of grams/ml required.';

-- 5. meal_items — decomposed meal → food linkage
-- Each row is one food item within a meal, with quantity and provenance.
CREATE TABLE public.meal_items (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    meal_id         bigint      NOT NULL REFERENCES public.meals(id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id),
    food_id         bigint      REFERENCES public.foods(id),
    sort_order      smallint    NOT NULL DEFAULT 0,
    description     text,
    quantity        numeric,
    quantity_unit   text        DEFAULT 'g',
    quantity_low    numeric,
    quantity_high   numeric,
    serving_id      bigint      REFERENCES public.food_servings(id),
    serving_count   numeric,
    preparation     text,
    calories        numeric,
    protein_g       numeric,
    fat_g           numeric,
    carbs_g         numeric,
    fiber_g         numeric,
    sugar_g         numeric,
    sodium_mg       numeric,
    is_estimated    boolean     NOT NULL DEFAULT false,
    confidence      numeric     CHECK (confidence >= 0 AND confidence <= 1),
    source          text        NOT NULL DEFAULT 'manual',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX meal_items_meal_id_idx ON public.meal_items (meal_id);
CREATE INDEX meal_items_user_id_idx ON public.meal_items (user_id);
CREATE INDEX meal_items_food_id_idx ON public.meal_items (food_id) WHERE food_id IS NOT NULL;

COMMENT ON TABLE public.meal_items IS 'Individual food items within a meal. Nutrients here are the calculated totals for this item at this quantity. food_id is null for unresolved/legacy items.';

-- 6. meal_inputs — raw user inputs (text, image ref, barcode)
CREATE TABLE public.meal_inputs (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    meal_id         bigint      NOT NULL REFERENCES public.meals(id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id),
    input_type      text        NOT NULL CHECK (input_type IN ('text', 'image', 'barcode', 'voice')),
    raw_text        text,
    image_path      text,
    barcode_value   text,
    barcode_format  text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX meal_inputs_meal_id_idx ON public.meal_inputs (meal_id);
CREATE INDEX meal_inputs_user_id_idx ON public.meal_inputs (user_id);

COMMENT ON TABLE public.meal_inputs IS 'Raw user inputs that led to a meal being logged — text descriptions, photo references, barcode scans.';

-- 7. ai_estimates — Gemini/LLM estimation audit trail
CREATE TABLE public.ai_estimates (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    meal_id         bigint      REFERENCES public.meals(id) ON DELETE SET NULL,
    meal_item_id    bigint      REFERENCES public.meal_items(id) ON DELETE SET NULL,
    meal_input_id   bigint      REFERENCES public.meal_inputs(id) ON DELETE SET NULL,
    user_id         uuid        NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id),
    model           text        NOT NULL,
    model_version   text,
    prompt_text     text,
    prompt_image_path text,
    raw_response    jsonb,
    parsed_output   jsonb,
    latency_ms      integer,
    accepted        boolean,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ai_estimates_meal_id_idx      ON public.ai_estimates (meal_id) WHERE meal_id IS NOT NULL;
CREATE INDEX ai_estimates_meal_item_id_idx ON public.ai_estimates (meal_item_id) WHERE meal_item_id IS NOT NULL;
CREATE INDEX ai_estimates_user_id_idx      ON public.ai_estimates (user_id);

COMMENT ON TABLE public.ai_estimates IS 'Audit trail for AI-generated nutrition estimates. Links to the meal, item, or input that triggered the estimation.';

-- =============================================================
-- RLS policies — same owner-scoped pattern as existing tables
-- =============================================================

-- food_sources and foods are reference data, readable by all authenticated users
ALTER TABLE public.food_sources ENABLE ROW LEVEL SECURITY;
CREATE POLICY "select food_sources" ON public.food_sources FOR SELECT TO authenticated USING (true);

ALTER TABLE public.foods ENABLE ROW LEVEL SECURITY;
CREATE POLICY "select foods" ON public.foods FOR SELECT TO authenticated USING (true);

ALTER TABLE public.food_nutrients ENABLE ROW LEVEL SECURITY;
CREATE POLICY "select food_nutrients" ON public.food_nutrients FOR SELECT TO authenticated USING (true);

ALTER TABLE public.food_servings ENABLE ROW LEVEL SECURITY;
CREATE POLICY "select food_servings" ON public.food_servings FOR SELECT TO authenticated USING (true);

-- meal_items, meal_inputs, ai_estimates — owner-scoped CRUD
ALTER TABLE public.meal_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY "select own meal_items" ON public.meal_items FOR SELECT USING ((SELECT auth.uid()) = user_id);
CREATE POLICY "insert own meal_items" ON public.meal_items FOR INSERT WITH CHECK ((SELECT auth.uid()) = user_id);
CREATE POLICY "update own meal_items" ON public.meal_items FOR UPDATE USING ((SELECT auth.uid()) = user_id);
CREATE POLICY "delete own meal_items" ON public.meal_items FOR DELETE USING ((SELECT auth.uid()) = user_id);

ALTER TABLE public.meal_inputs ENABLE ROW LEVEL SECURITY;
CREATE POLICY "select own meal_inputs" ON public.meal_inputs FOR SELECT USING ((SELECT auth.uid()) = user_id);
CREATE POLICY "insert own meal_inputs" ON public.meal_inputs FOR INSERT WITH CHECK ((SELECT auth.uid()) = user_id);
CREATE POLICY "update own meal_inputs" ON public.meal_inputs FOR UPDATE USING ((SELECT auth.uid()) = user_id);
CREATE POLICY "delete own meal_inputs" ON public.meal_inputs FOR DELETE USING ((SELECT auth.uid()) = user_id);

ALTER TABLE public.ai_estimates ENABLE ROW LEVEL SECURITY;
CREATE POLICY "select own ai_estimates" ON public.ai_estimates FOR SELECT USING ((SELECT auth.uid()) = user_id);
CREATE POLICY "insert own ai_estimates" ON public.ai_estimates FOR INSERT WITH CHECK ((SELECT auth.uid()) = user_id);
CREATE POLICY "update own ai_estimates" ON public.ai_estimates FOR UPDATE USING ((SELECT auth.uid()) = user_id);
CREATE POLICY "delete own ai_estimates" ON public.ai_estimates FOR DELETE USING ((SELECT auth.uid()) = user_id);

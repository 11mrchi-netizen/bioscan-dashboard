
CREATE TABLE IF NOT EXISTS public.zepp_raw_extracts (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id     uuid NOT NULL DEFAULT auth.uid() REFERENCES auth.users(id),
  metric      text NOT NULL,
  query_date  date NOT NULL,
  api_host    text NOT NULL,
  endpoint    text NOT NULL,
  status_code smallint NOT NULL,
  raw_body    jsonb,
  fetched_at  timestamptz NOT NULL DEFAULT now(),
  extractor_version text NOT NULL DEFAULT '1',

  CONSTRAINT zepp_raw_extracts_metric_date_uq UNIQUE (user_id, metric, query_date)
);

CREATE INDEX idx_zepp_raw_extracts_metric ON public.zepp_raw_extracts (metric, query_date);

ALTER TABLE public.zepp_raw_extracts ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can read own zepp extracts"
  ON public.zepp_raw_extracts FOR SELECT
  USING ((SELECT auth.uid()) = user_id);

CREATE POLICY "Users can insert own zepp extracts"
  ON public.zepp_raw_extracts FOR INSERT
  WITH CHECK ((SELECT auth.uid()) = user_id);

CREATE POLICY "Users can update own zepp extracts"
  ON public.zepp_raw_extracts FOR UPDATE
  USING ((SELECT auth.uid()) = user_id)
  WITH CHECK ((SELECT auth.uid()) = user_id);

CREATE POLICY "Users can delete own zepp extracts"
  ON public.zepp_raw_extracts FOR DELETE
  USING ((SELECT auth.uid()) = user_id);

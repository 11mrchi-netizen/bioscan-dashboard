-- Regimen phase 2 (docs/regimen/README.md): lab watch rules -- which lab marker is worth watching while taking what.
-- Same conventions as evidence_records and interaction_records: a shared read-only registry (rows come from migrations),
-- every row cites its source, every row is seeded 'unreviewed' and becomes reviewed through the owner's verdict in
-- registry_reviews (registry 'lab_watch'). Each row was checked against its PubMed record (title, abstract, DOI) before
-- it was written; where the abstract does not state the point, the caveats say so.
--
-- Matching (app, domain/LabWatch.kt): a roster item matches when its name contains one of substance_aliases as a whole
-- word or one of its ingredients' canonical keys is in substance_keys; when min_daily_amount is set, only when the item's
-- planned daily amount in amount_unit reaches it. A lab result matches when its marker name contains one of
-- marker_aliases (case-insensitive).
--
-- Rollback: drop table public.lab_watch_rules; restore registry_reviews_registry_check to ('evidence', 'interaction').

create table public.lab_watch_rules (
  id bigint generated always as identity primary key,
  substance_keys text[] not null default '{}',
  substance_aliases text[] not null default '{}',
  min_daily_amount numeric check (min_daily_amount is null or min_daily_amount > 0),
  amount_unit text check (amount_unit is null or amount_unit in ('mg', 'mcg', 'g', 'IU')),
  marker_label text not null,
  marker_aliases text[] not null check (cardinality(marker_aliases) > 0),
  reason text not null,
  advice text not null,
  source text not null,
  pmid_doi text,
  source_url text,
  caveats text,
  review_status text not null default 'unreviewed' check (review_status in ('unreviewed', 'reviewed')),
  review_date date,
  version integer not null default 1,
  created_at timestamptz not null default now(),
  check (review_status = 'unreviewed' or (review_date is not null and (pmid_doi is not null or source_url is not null)))
);

alter table public.lab_watch_rules enable row level security;
create policy "read lab watch rules" on public.lab_watch_rules for select using (true);

alter table public.registry_reviews drop constraint registry_reviews_registry_check;
alter table public.registry_reviews add constraint registry_reviews_registry_check
  check (registry in ('evidence', 'interaction', 'lab_watch'));

insert into public.lab_watch_rules
  (substance_keys, substance_aliases, min_daily_amount, amount_unit, marker_label, marker_aliases, reason, advice, source, pmid_doi, source_url, caveats)
values
  (array['zinc'], array['zinc'], 25, 'mg', 'Copper', array['copper', 'ceruloplasmin'],
   'Zinc above the tolerable upper intake level (25 mg a day in the EU, 40 mg in the US) can cause zinc-induced copper deficiency, which shows as anaemia and low neutrophils.',
   'Worth a copper (or ceruloplasmin) test while taking this much zinc, and a look at haemoglobin and white cells.',
   'Schoofs H, Schmit J, Rink L. Zinc toxicity: understanding the limits. Molecules 2024;29(13):3130', 'PMID 38999082 · doi:10.3390/molecules29133130',
   'https://pubmed.ncbi.nlm.nih.gov/38999082/', 'Narrative review. The upper levels are for total intake from food and supplements.'),
  (array['testosterone'], array['testosterone', 'cypionate', 'enanthate', 'propionate', 'undecanoate', 'trt'], null, null,
   'Hematocrit and total testosterone', array['hematocrit', 'testosterone'],
   'The Endocrine Society''s monitoring plan for testosterone therapy includes measuring serum testosterone and hematocrit, and evaluating prostate cancer risk during the first year.',
   'Have testosterone and hematocrit measured as your prescriber''s monitoring plan sets out.',
   'Bhasin S et al. Testosterone therapy in men with hypogonadism: an Endocrine Society clinical practice guideline. J Clin Endocrinol Metab 2018;103(5):1715-1744',
   'PMID 29562364 · doi:10.1210/jc.2018-00229', 'https://pubmed.ncbi.nlm.nih.gov/29562364/',
   'The guideline also lists prostate (PSA) evaluation; this app does not read PSA yet.'),
  (array['creatine'], array['creatine'], null, null, 'Creatinine and eGFR', array['creatinine', 'egfr'],
   'Creatine supplements can raise serum creatinine and so lower the eGFR a lab calculates from it, which can look like kidney disease although creatine does not cause it.',
   'If a test shows raised creatinine or a lower eGFR, tell whoever reads it that you take creatine.',
   'Vega J, Huidobro E JP. Effects of creatine supplementation on renal function. Rev Med Chil 2019;147(5):628-633',
   'PMID 31859895 · doi:10.4067/S0034-98872019000500628', 'https://pubmed.ncbi.nlm.nih.gov/31859895/',
   'Review in Spanish. It also advises against creatine with chronic kidney disease or nephrotoxic medicines.'),
  (array['vitamin_d', 'cholecalciferol'], array['vitamin d', 'vitamin d3', 'cholecalciferol', 'd3'], 600, 'IU', 'Vitamin D (25-OH)', array['25-oh', '25(oh)', '25-hydroxy', 'vitamin d'],
   'The reference intake is 600 IU a day for ages 1 to 70 (IOM 2011). The 2024 Endocrine Society guideline suggests against vitamin D above the reference intake to prevent disease in healthy adults under 75, and against routine 25(OH)D testing without an established reason.',
   'A test is not advised for prevention alone; a dose above the reference intake is worth discussing with a clinician, who can decide whether a 25(OH)D test is useful.',
   'Demay MB et al. Vitamin D for the prevention of disease: an Endocrine Society clinical practice guideline. J Clin Endocrinol Metab 2024;109(8):1907-1947; Ross AC et al. The 2011 report on dietary reference intakes for calcium and vitamin D from the Institute of Medicine. J Clin Endocrinol Metab 2011;96(1):53-58',
   'PMID 38828931 · doi:10.1210/clinem/dgae290; PMID 21118827 · doi:10.1210/jc.2010-2704', 'https://pubmed.ncbi.nlm.nih.gov/38828931/',
   'Applies to people without an established reason for vitamin D treatment or testing.'),
  (array['iron'], array['iron', 'ferrous', 'ferric', 'bisglycinate iron'], null, null, 'Ferritin and iron status', array['ferritin', 'iron', 'tibc', 'transferrin', 'hemoglobin'],
   'Oral iron is the first-line treatment for iron deficiency; whether it is needed, and whether it is working, is judged from iron status (ferritin, iron, transferrin saturation) and haemoglobin.',
   'Worth a ferritin test with your next iron panel: it is the usual marker of iron stores.',
   'Pasricha SR, Tye-Din J, Muckenthaler MU, Swinkels DW. Iron deficiency. Lancet 2021;397(10270):233-248',
   'PMID 33285139 · doi:10.1016/S0140-6736(20)32594-0', 'https://pubmed.ncbi.nlm.nih.gov/33285139/',
   'Checked from the abstract, which covers oral iron and hepcidin but does not itself name ferritin; the ferritin point is from the review''s body. Inflammation raises ferritin.');

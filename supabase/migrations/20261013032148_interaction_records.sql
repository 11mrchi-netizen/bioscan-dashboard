-- Analysis Layer 3 / Phase 6: a curated registry of supplement-supplement and supplement-drug interactions.
-- Same conventions as evidence_records: shared read-only table (no write policies; rows come from migrations), every row
-- carries its primary source, and a row is shown as established only when review_status = 'reviewed'. Nothing here was
-- generated from model memory alone: each seeded row was checked against PubMed (PMID, journal, year, abstract) or the FDA
-- label before it was written, and every row is seeded 'unreviewed' so the app labels it until the owner reviews it.
-- A row can be marked reviewed only if it has a citation (pmid_doi or source_url) and a review date.
--
-- Matching (done in the app, domain/SupplementInteractions.kt): a roster item matches a side when one of its ingredient
-- canonical keys is in *_keys or its name contains one of *_aliases as a whole word. Pairs are unordered.
--   trigger_mode  both_present  flag when an item matching A and a different item matching B are both active
--                 a_without_b   flag when an item matching A is active and nothing matches B (e.g. zinc without copper)
--   spacing_hours minimum gap the evidence or label asks for; null = the interaction is cumulative, not about timing
--   prefer_together  the pairing is helpful when taken together (informational, shown only when they share a slot)
-- Rollback: drop table public.interaction_records;

create table public.interaction_records (
  id bigint generated always as identity primary key,
  a_keys text[] not null default '{}',
  a_aliases text[] not null default '{}',
  b_keys text[] not null default '{}',
  b_aliases text[] not null default '{}',
  interaction_type text not null check (interaction_type in ('absorption_reduced', 'absorption_enhanced', 'depletion', 'pharmacodynamic')),
  effect_direction text check (effect_direction in ('increase', 'decrease', 'mixed')),
  trigger_mode text not null default 'both_present' check (trigger_mode in ('both_present', 'a_without_b')),
  prefer_together boolean not null default false,
  mechanism text not null,
  effect text not null,
  advice text not null,
  spacing_hours numeric check (spacing_hours is null or spacing_hours > 0),
  severity text not null check (severity in ('major', 'moderate', 'minor', 'info')),
  evidence_tier text check (evidence_tier in ('A', 'B', 'C', 'D')),
  study_design text check (study_design in ('mechanistic', 'animal', 'observational', 'controlled_human', 'meta_analysis', 'guideline', 'label')),
  pmid_doi text,
  source text not null,
  source_url text,
  caveats text,
  review_status text not null default 'unreviewed' check (review_status in ('unreviewed', 'reviewed')),
  review_date date,
  version integer not null default 1,
  created_at timestamptz not null default now(),
  constraint interaction_a_side_present check (cardinality(a_keys) + cardinality(a_aliases) > 0),
  constraint interaction_b_side_present check (trigger_mode = 'a_without_b' or cardinality(b_keys) + cardinality(b_aliases) > 0),
  constraint interaction_reviewed_needs_citation check (
    review_status <> 'reviewed' or (review_date is not null and (nullif(btrim(pmid_doi), '') is not null or nullif(btrim(source_url), '') is not null))
  )
);

alter table public.interaction_records enable row level security;
create policy interaction_records_read on public.interaction_records for select to authenticated using (true);

-- Seed: 11 rows, all 'unreviewed'. Source details are in docs/analysis-layer-3/interaction-registry.md.
insert into public.interaction_records
  (a_keys, a_aliases, b_keys, b_aliases, interaction_type, effect_direction, trigger_mode, prefer_together, mechanism, effect, advice, spacing_hours, severity, evidence_tier, study_design, pmid_doi, source, source_url, caveats)
values
-- 1 zinc without copper (cumulative; flagged only while no copper is in the roster)
(array['zinc'], array['zinc'], array['copper'], array['copper'], 'depletion', 'decrease', 'a_without_b', false,
 'Excess zinc intake interferes with copper homeostasis; the US adult upper intake level for zinc (40 mg/day) is set on this effect.',
 'Long-term zinc above the upper level can cause copper deficiency (anaemia, low neutrophils, nerve damage in case reports).',
 'This is about total daily zinc, not timing. If you take zinc long term near or above 40 mg/day, discuss copper and having copper status checked.',
 null, 'moderate', 'B', 'observational',
 'PMID:15762288; DOI:10.1309/V6GVYW2QTYD5C5PJ; DOI:10.17226/10026',
 'Willis MS, Monaghan SA, Miller ML, et al. Zinc-induced copper deficiency: a report of three cases initially recognized on bone marrow examination. Am J Clin Pathol 2005;123:125-131. Institute of Medicine. Dietary Reference Intakes for Vitamin A, Vitamin K, Arsenic, Boron, Chromium, Copper, Iodine, Iron, Manganese, Molybdenum, Nickel, Silicon, Vanadium, and Zinc. National Academy Press, 2001 (zinc: tolerable upper intake level).',
 'https://www.nationalacademies.org/read/10026/chapter/14',
 'Case reports plus a reference-intake report (the upper level rests on copper status); the evidence does not give a safe dose for any one person.'),
-- 2 calcium and iron
(array['calcium'], array['calcium'], array['iron'], array['iron', 'ferrous'], 'absorption_reduced', 'decrease', 'both_present', false,
 'Calcium has a direct dose-related inhibiting effect on iron absorption, for non-heme and heme iron alike.',
 'In single meals 300-600 mg calcium reduced iron absorption by 50-60%; 165 mg given as milk, cheese or calcium chloride did the same.',
 'Keep the two apart where you can, for example iron with a vitamin C source away from calcium.',
 2, 'minor', 'B', 'controlled_human',
 'PMID:1984335; DOI:10.1093/ajcn/53.1.112',
 'Hallberg L, Brune M, Erlandsson M, et al. Calcium: effect of different amounts on nonheme- and heme-iron absorption in humans. Am J Clin Nutr 1991;53:112-119.',
 'https://pubmed.ncbi.nlm.nih.gov/1984335/',
 'Measured within single meals; the effect on long-term iron status is less certain. The 2 hour gap is a precaution, not a figure from the study.'),
-- 3 zinc and iron
(array['zinc'], array['zinc'], array['iron'], array['iron', 'ferrous'], 'absorption_reduced', 'decrease', 'both_present', false,
 'Zinc given together with iron in solution reduced iron bioavailability in a dose-dependent way.',
 'No significant effect up to a zinc:iron molar ratio of 2:1; at 5:1, 10:1 and 20:1 iron absorption fell by 28-40%.',
 'Matters only when zinc greatly exceeds iron. For example 25 mg zinc with 18 mg iron is about 1.2:1 by moles.',
 null, 'minor', 'B', 'controlled_human',
 'PMID:17873388; DOI:10.1007/BF02698079',
 'Olivares M, Pizarro F, Ruz M. Zinc inhibits nonheme iron bioavailability in humans. Biol Trace Elem Res 2007;117:7-14.',
 'https://pubmed.ncbi.nlm.nih.gov/17873388/',
 'Iron was given as an aqueous solution to 22 women, not as supplements with food.'),
-- 4 calcium and zinc
(array['calcium'], array['calcium'], array['zinc'], array['zinc'], 'absorption_reduced', 'decrease', 'both_present', false,
 'High calcium intake reduces zinc absorption.',
 'A 600 mg calcium carbonate supplement taken with a meal cut zinc absorption from that meal by 50%; adding about 8 mg zinc offset it. 468 mg/day extra calcium lowered net zinc absorption by about 2 mg/day.',
 'Take them at different times of day where you can.',
 2, 'minor', 'B', 'controlled_human',
 'PMID:9174476; DOI:10.1093/ajcn/65.6.1803',
 'Wood RJ, Zheng JJ. High dietary calcium intakes reduce zinc absorption and balance in humans. Am J Clin Nutr 1997;65:1803-1809.',
 'https://pubmed.ncbi.nlm.nih.gov/9174476/',
 'Studied in older adults on a controlled diet. The 2 hour gap is a precaution, not a figure from the study.'),
-- 5 vitamin C helps iron (informational; shown only when they share a slot)
(array['vitamin_c'], array['vitamin c', 'ascorbic'], array['iron'], array['iron', 'ferrous'], 'absorption_enhanced', 'increase', 'both_present', true,
 'Ascorbic acid promotes non-heme iron absorption, mainly by offsetting inhibitors such as phytates and tannins in the meal.',
 'Vitamin C taken with a meal raised non-heme iron absorption, most where the meal contained many inhibitors.',
 'A helpful pairing: take them together if you want the iron absorbed.',
 null, 'info', 'B', 'controlled_human',
 'PMID:3700141',
 'Hallberg L, Brune M, Rossander L. Effect of ascorbic acid on iron absorption from different types of meals. Studies with ascorbic-acid-rich foods and synthetic ascorbic acid given in different amounts with different meals. Hum Nutr Appl Nutr 1986;40:97-113.',
 'https://pubmed.ncbi.nlm.nih.gov/3700141/',
 'Radio-iron studies in 299 subjects; supplement doses and forms were not the focus.'),
-- 6 calcium and levothyroxine
(array['calcium'], array['calcium'], array[]::text[], array['levothyroxine', 'thyroxine', 'synthroid', 'levoxyl', 'euthyrox'], 'absorption_reduced', 'decrease', 'both_present', false,
 'Calcium carbonate binds levothyroxine in the gut.',
 '1200 mg/day elemental calcium as calcium carbonate, taken with levothyroxine for 3 months, significantly lowered free and total T4 in 20 people with hypothyroidism.',
 'Levothyroxine labelling asks for at least 4 hours between it and calcium or iron. Ask your prescriber before changing timing.',
 4, 'moderate', 'B', 'observational',
 'PMID:10838651; DOI:10.1001/jama.283.21.2822; PMID:21595516; DOI:10.1089/thy.2010.0296',
 'Singh N, Singh PN, Hershman JM. Effect of calcium carbonate on the absorption of levothyroxine. JAMA 2000;283:2822-2825. Zamfirescu I, Carlson HE. Absorption of levothyroxine when coadministered with various calcium formulations. Thyroid 2011;21:483-486. FDA levothyroxine sodium tablets prescribing information (drug interactions: separate by at least 4 hours).',
 'https://dailymed.nlm.nih.gov/dailymed/fda/fdaDrugXsl.cfm?setid=776c8dbc-ca34-40f9-9730-4aa59f644703&type=display',
 'The 4 hour gap is from the product label. Twenty patients in the JAMA study.'),
-- 7 iron and levothyroxine
(array['iron'], array['iron', 'ferrous'], array[]::text[], array['levothyroxine', 'thyroxine', 'synthroid', 'levoxyl', 'euthyrox'], 'absorption_reduced', 'decrease', 'both_present', false,
 'Iron binds thyroxine; a poorly soluble complex forms.',
 'Ferrous sulfate 300 mg taken with thyroxine for 12 weeks raised TSH from 1.6 to 5.4 mU/L and worsened symptoms in 9 of 14 patients.',
 'Levothyroxine labelling asks for at least 4 hours between it and iron. Ask your prescriber before changing timing.',
 4, 'moderate', 'C', 'observational',
 'PMID:1443969; DOI:10.7326/0003-4819-117-12-1010',
 'Campbell NR, Hasinoff BB, Stalts H, et al. Ferrous sulfate reduces thyroxine efficacy in patients with hypothyroidism. Ann Intern Med 1992;117:1010-1013. FDA levothyroxine sodium tablets prescribing information (drug interactions: separate by at least 4 hours).',
 'https://dailymed.nlm.nih.gov/dailymed/fda/fdaDrugXsl.cfm?setid=776c8dbc-ca34-40f9-9730-4aa59f644703&type=display',
 'Uncontrolled trial of 14 patients. The 4 hour gap is from the product label.'),
-- 8 polyvalent cations and fluoroquinolone antibiotics
(array['calcium', 'iron', 'magnesium', 'zinc'], array['calcium', 'iron', 'ferrous', 'magnesium', 'zinc'], array[]::text[], array['ciprofloxacin', 'levofloxacin', 'ofloxacin', 'moxifloxacin', 'norfloxacin', 'cipro', 'levaquin'], 'absorption_reduced', 'decrease', 'both_present', false,
 'Multivalent cations chelate fluoroquinolones into insoluble complexes that are not absorbed.',
 'Absorption of the antibiotic falls, with lower serum and urine levels than intended; chelation interactions were reported in 22-76% of patients on fluoroquinolones.',
 'Ciprofloxacin labelling: take it at least 2 hours before or 6 hours after magnesium/aluminium antacids and products containing calcium, iron or zinc. Ask your prescriber or pharmacist for the product you use.',
 6, 'major', 'A', 'label',
 'PMID:7669261; DOI:10.2165/00002018-199512050-00004; PMID:7689454; DOI:10.2165/00003495-199300453-00013',
 'Lomaestro BM, Bailie GR. Absorption interactions with fluoroquinolones. 1995 update. Drug Saf 1995;12:314-333. Deppermann KM, Lode H. Fluoroquinolones: interaction profile during enteral absorption. Drugs 1993;45 Suppl 3:65-72. FDA ciprofloxacin tablets prescribing information (2 hours before or 6 hours after).',
 'https://www.accessdata.fda.gov/drugsatfda_docs/label/2016/019537s086lbl.pdf',
 'Formulation-specific windows differ (extended-release products); use the label of the product actually taken.'),
-- 9 polyvalent cations and tetracyclines
(array['calcium', 'iron', 'magnesium'], array['calcium', 'iron', 'ferrous', 'magnesium'], array[]::text[], array['tetracycline', 'doxycycline', 'minocycline', 'oxytetracycline'], 'absorption_reduced', 'decrease', 'both_present', false,
 'Tetracyclines chelate polyvalent metal cations; many of the complexes are insoluble or poorly absorbed.',
 'Dairy products, antacids with polyvalent cations and iron salts can reduce tetracycline absorption by 50-90% or more; an interval of 3 hours prevented the interaction.',
 'Keep at least 3 hours between the antibiotic and these minerals. Ask your prescriber or pharmacist for the product you use.',
 3, 'major', 'B', 'controlled_human',
 'PMID:946598; DOI:10.2165/00003495-197611010-00004',
 'Neuvonen PJ. Interactions with the absorption of tetracyclines. Drugs 1976;11:45-54.',
 'https://pubmed.ncbi.nlm.nih.gov/946598/',
 'A 1976 review; the severity depends on the specific tetracycline and cation.'),
-- 10 tadalafil and nitrates
(array['tadalafil'], array['tadalafil', 'cialis'], array[]::text[], array['nitroglycerin', 'glyceryl trinitrate', 'isosorbide', 'nitrostat', 'isordil'], 'pharmacodynamic', 'decrease', 'both_present', false,
 'Phosphodiesterase 5 inhibitors augment the blood-pressure-lowering effect of nitrates.',
 'After seven daily doses of tadalafil 20 mg, sublingual nitroglycerin lowered standing blood pressure more than after placebo at 4, 8 and 24 hours, with no difference at 48, 72 and 96 hours.',
 'Not to be combined. If you may need a nitrate, tell the prescriber that you take tadalafil and ask about timing.',
 48, 'major', 'A', 'controlled_human',
 'PMID:14642699; DOI:10.1016/j.jacc.2003.09.023',
 'Kloner RA, Hutter AM, Emmick JT, et al. Time course of the interaction between tadalafil and nitrates. J Am Coll Cardiol 2003;42:1855-1860.',
 'https://pubmed.ncbi.nlm.nih.gov/14642699/',
 'Healthy men and single nitroglycerin doses; what applies to you is a question for your prescriber.'),
-- 11 tadalafil and doxazosin
(array['tadalafil'], array['tadalafil', 'cialis'], array[]::text[], array['doxazosin', 'cardura'], 'pharmacodynamic', 'decrease', 'both_present', false,
 'Both lower blood pressure; tadalafil 20 mg augmented the hypotensive effect of doxazosin 8 mg.',
 'Mean maximal fall in standing systolic pressure was 9.8 mmHg larger than with doxazosin plus placebo; standing systolic below 85 mmHg occurred in 28% versus 6%. With tamsulosin 0.4 mg the differences were small.',
 'Ask your prescriber before combining them.',
 null, 'moderate', 'B', 'controlled_human',
 'PMID:15540759; DOI:10.1097/01.ju.0000142687.75577.e4',
 'Kloner RA, Jackson G, Emmick JT, et al. Interaction between the phosphodiesterase 5 inhibitor, tadalafil and 2 alpha-blockers, doxazosin and tamsulosin in healthy normotensive men. J Urol 2004;172:1935-1940.',
 'https://pubmed.ncbi.nlm.nih.gov/15540759/',
 'Eighteen healthy normotensive men per study.');

# Interaction registry (Phase 6)

A small, hand-curated table of supplement and drug pairs, shown on the Supplements screen and in the add/edit sheet. Nothing in it is model-generated. Every row has a primary citation (PubMed ID or DOI, or a product label), and **every row is currently `unreviewed`**: the app prints "(unreviewed)" next to the effect text until you mark it reviewed.

## What is stored

Table `public.interaction_records` (migration `supabase/migrations/20261013_interaction_records.sql`, applied 2026-10-13). Read-only for signed-in users; rows are added by migration.

| Column | Meaning |
| -- | -- |
| `a_keys`, `a_aliases` / `b_keys`, `b_aliases` | The two sides of a pair. A roster item takes part when one of its product's ingredient `canonical_key`s is in `*_keys`, or a whole word of its name (or ingredient names) equals an alias. Pairs are unordered. |
| `trigger_mode` | `both_present` (default) or `a_without_b` (A on the roster and nothing covering B). |
| `prefer_together` | A helpful pairing; shown only when both items share a time slot. |
| `spacing_hours` | Minimum gap between the two slots. `null` means timing does not help (a cumulative or pharmacodynamic interaction). |
| `severity` | `major`, `moderate`, `minor`, `info`. |
| `evidence_tier`, `study_design` | Same vocabulary as `evidence_records`. |
| `pmid_doi`, `source`, `source_url`, `caveats` | The citation, written in full, and what the study does not show. |
| `review_status`, `review_date` | `unreviewed` or `reviewed`. A CHECK refuses `reviewed` without a `review_date` and a `pmid_doi` or `source_url`. |

## How it is applied

`domain/SupplementInteractions.kt` (pure, unit-tested) places each active roster item on a nominal slot (morning 08:00, afternoon 13:00, night 21:00; the gap is taken the short way round the clock) and reports each pair as:

- **Same slot** or **too close**: spacing is advised and the slots are nearer than that.
- **Spaced**: far enough apart (shown muted).
- **Any timing**: no spacing applies (for example high zinc against iron, or tadalafil with an alpha-blocker).
- **Check**: `a_without_b` and no roster item covers B.
- **As needed**: one item is as-needed, so its slot is unknown.
- **Good pair**: a helpful pairing sharing a slot.

An item that is taken less often than daily (`every_n_days` above 1) is marked "only on days you take both". One product that holds both sides is never flagged against itself. Doses are not compared (the roster stores free text).

## Entries

All eleven are `unreviewed`. Links resolve at `https://pubmed.ncbi.nlm.nih.gov/<PMID>/` or `https://doi.org/<DOI>`. Where the advised gap is a precaution and not a figure from the study, the row's caveat says so.

| # | Pair | Gap | Severity | Citations |
| -- | -- | -- | -- | -- |
| 1 | Zinc without copper | none | moderate | Willis 2005, Am J Clin Pathol, PMID 15762288; Institute of Medicine 2001 (zinc upper level 40 mg/day), DOI 10.17226/10026 |
| 2 | Calcium and iron | 2 h (precaution) | minor | Hallberg 1991, Am J Clin Nutr, PMID 1984335 |
| 3 | Zinc and iron | none | minor | Olivares 2007, Biol Trace Elem Res, PMID 17873388 |
| 4 | Calcium and zinc | 2 h (precaution) | minor | Wood and Zheng 1997, Am J Clin Nutr, PMID 9174476 |
| 5 | Vitamin C with iron (helpful) | same slot | info | Hallberg 1986, Hum Nutr Appl Nutr, PMID 3700141 |
| 6 | Calcium and levothyroxine | 4 h (label) | moderate | Singh 2000, JAMA, PMID 10838651; Zamfirescu 2011, Thyroid, PMID 21595516; FDA levothyroxine label |
| 7 | Iron and levothyroxine | 4 h (label) | moderate | Campbell 1992, Ann Intern Med, PMID 1443969; FDA levothyroxine label |
| 8 | Calcium, iron, magnesium, zinc and fluoroquinolones | 6 h (label) | major | Lomaestro 1995, Drug Saf, PMID 7669261; Deppermann 1993, Drugs, PMID 7689454; FDA ciprofloxacin label |
| 9 | Calcium, iron, magnesium and tetracyclines | 3 h | major | Neuvonen 1976, Drugs, PMID 946598 |
| 10 | Tadalafil and nitrates | 48 h | major | Kloner 2003, J Am Coll Cardiol, PMID 14642699 |
| 11 | Tadalafil and doxazosin | none | moderate | Kloner 2004, J Urol, PMID 15540759 |

How these were checked: each PubMed ID was looked up in PubMed to confirm the title, journal and year, and the numbers quoted in the "effect" text were read against the abstract. Author lists are cut to "et al." where I could not confirm the full list. The label gaps (levothyroxine 4 h, ciprofloxacin 2 h before or 6 h after) come from the FDA prescribing information named in the row. I did not read full texts, so reading the papers is the next step before marking anything reviewed.

## Things to decide when you review

- **Row 1 (zinc without copper)** is about total daily zinc over a long time, not timing, and the app cannot see the dose. It will appear for anyone with a zinc item and no copper item. Keep it, or drop it if it nags.
- **Rows 6 to 11** involve prescription medicines. They only appear if such a medicine is on the supplements roster (matched by name alias, for example "levothyroxine", "ciprofloxacin", "tadalafil"). The advice always points at the prescriber and the label.
- The 2 h gaps in rows 2 and 4 are a precaution chosen here, not a number from the studies.
- Slots are nominal; if you take "morning" items at 06:00 and "afternoon" items at 18:00, the real gaps are longer than shown.

## Marking an entry reviewed

After reading the source, run (replace `<id>`), in the Supabase SQL editor or through a migration:

```sql
update public.interaction_records
set review_status = 'reviewed', review_date = current_date
where id = <id>;
```

The CHECK refuses the update if the row has no citation. To correct a row, update the text columns and bump `version`; to drop one, delete it. Adding entries means a new migration with the same columns, a citation and `review_status = 'unreviewed'`.

# Personal health agent: design

**Date:** 2026-10-28 · **Branch:** `feat/ft-redesign` (PR 24) · **Status:** exploratory design; phase 0 built (below), nothing deployed

This goes against the original design rule that no language model sits in the analysis pipeline. It is written as
exploratory work: one design, the reasons for it, and the decisions it needs from the owner before anything is built.

Source: Heydari et al., *The anatomy of a personal health agent*, Nature (2026),
[doi:10.1038/s41586-026-11161-8](https://doi.org/10.1038/s41586-026-11161-8). Related designs reviewed: PHIA (Merrill et
al., Nature Communications 2026), WearableDeviceAgents (task decomposition), Google's AI co-scientist
(generate / reflect / rank), Microsoft MAI-DxO (a panel of models from different families).

## The decision in one paragraph

**One interactive agent with three modes, and a small weekly analyst pass. Not a standing fleet.** Google's paper needs
three specialists because its data science agent writes analysis code on the fly. Here that job is already done better
by `analysis-run` (pre-registration, held-out scorecards, false-discovery control, an append-only ledger). So the data
science agent turns into tools over published results, and the remaining specialists (domain expert, coach) become modes
of one agent: a system prompt and a set of allowed tools each, in the same loop. That keeps Google's orchestration
benefit (the right expertise for each question) without the cost of agents talking to agents. Breadth across domains is
where several agents do help, and that work is not interactive, so it runs as a weekly batch whose output enters the
existing decision queue as hints, never as findings.

| Option | Verdict | Why |
|---|---|---|
| Copy Google's design: orchestrator, data science, domain expert, coach | No | Code-writing statistics undo the engine's rigour; 3-5 model calls per question for one user |
| One agent with modes (interactive) | **Yes** | One loop, one audit trail; modes give specialist behaviour; fits the Edge Function time limit |
| Standing fleet of domain analysts, nightly | No | At n=1, five analysts trawling the same tables every night mostly find chance; costly and noisy |
| Weekly analyst pass: parallel domain analysts, then a reviewer from another model family, then the queue | **Yes** | Breadth across domains; latency does not matter; output is gated by registration (only new days count) |

## Architecture

```
                       ANDROID (Field Terminal)                     CONSOLE (analysis hub)
           WHY? on cards · ASK sheet · Lab › N=1 HINTS     Inspector EXPLAIN · Decisions BRIEF/CHALLENGE · Models › AGENT
                                  │                                          │
                                  └──────────── user JWT ────────────────────┘
                                                   ▼
   ┌─────────────────────────── Edge Function `health-agent` (interactive) ───────────────────────────┐
   │ 1 route   rules first (from the screen that called: card id, item id), small model only when free text │
   │ 2 loop    mode prompt + allowed tools; at most 8 tool steps, 60 s, token budget                    │
   │ 3 check   deterministic guards in _shared/agent-core: scope, numbers, vocabulary, claim strength     │
   │ 4 reply   text + references to results + proposals (never writes a health row or a decision)         │
   └──────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                   │ tools (read through RLS, the caller's JWT)
                                                   ▼
     analysis_results · evidence items / queue · decisions · integrity · metric registry · labs · experiments
     interventions · profile · agent_memory · PubMed (NCBI E-utilities, citations)

   pg_cron (weekly, after the nightly analysis-run) ─▶ Edge Function `agent-digest`
       analysts in parallel (sleep · training · nutrition · labs & body · interventions)  ─▶ reviewer (other family)
       ─▶ agent_hints (exploratory) + agent_digests ─▶ Lab › N=1 HINTS, Evidence › DISCOVERY
```

### Modes of the interactive agent

| Mode | Paper's equivalent | Handles | Allowed tools | Can propose |
|---|---|---|---|---|
| EXPLAIN | Data science + domain expert | "Why does this card say BELOW EXPECTED?", "what does MIXED SIGNALS mean here?" | Results, integrity, metric registry, evidence items | Nothing |
| ASK | Orchestrator over all three | Free questions on own data, general health knowledge, lab results in context | All read tools, PubMed | Register a question, preview an experiment |
| COACH | Health coach | Goals, obstacles, "what should I change?", follow-up on running experiments and decision review dates | Read tools, `agent_memory` | Goal, experiment, a log entry (handed to `log-agent`) |

Symptom assessment, the paper's fourth journey, is **not a mode**. Deterministic red-flag rules on the question
(`agent-core/redFlags.ts`: chest pain, breathing, stroke signs, fainting, self-harm, sudden headache; bleeding, fever of
39 °C or more, palpitations, numbness), worded like the health-concern safety prompts in `analysis-core/healthEpisodes.ts`,
return fixed text that sends the person to a clinician. The agent describes what the data shows and does not diagnose.

### Coordination

- **Interactive:** sequential tool loop in one function call. No agent-to-agent messages. The route is mostly
  deterministic: the screen that opens the agent knows the card, series, date or evidence item, so EXPLAIN needs no
  routing call at all.
- **Weekly:** `agent-digest` gives each analyst a pre-built packet for its domain (the week's results, integrity,
  queue state, open experiments), runs them in parallel (`Promise.all`), then passes all candidates to one reviewer.
  The reviewer drops anything unsupported by a result id or contradicted by an integrity issue, merges duplicates and
  ranks the rest by novelty, controllability and evidence strength (head-to-head, as in co-scientist). At most five
  hints a week reach the person.
- **State is in Postgres, not in agents:** threads, turns, memory, hints and digests are tables (below). Every turn is
  auditable, like `log_agent_runs`.
- **Scheduling:** pg_cron calls `agent-digest` on Sunday after the nightly `analysis-run` (cron 20:30 UTC), with the
  same Vault-secret header pattern as `private.run_analysis()`. No human-held secret, no service-role key in a client.

### The gate: how a hint becomes evidence

```
analyst hint ──▶ reviewer ──▶ agent_hints (EXPLORATORY) ──▶ owner: REGISTER / DISMISS
                                                              │
                         inside the registered space ─────────┤──▶ registered_questions (only new days count)
                         a controllable exposure ─────────────┤──▶ experiment (preview shows smallest detectable effect)
                         outside both ────────────────────────┘──▶ stays a hint, marked "needs an engine question"
                                                                        │
                                       nightly analysis-run ──▶ evidence_ledger ──▶ decision queue ──▶ decisions (human)
```

This gate is what makes a fleet safe here. `registered_questions` accepts only six exposures and three outcomes today
(`20261016_evidence_ledger.sql`), so most hints will land outside it. Widening that space (to the 2.1.0 catalogue
exposures) is the main engine change this design needs, and it is a separate owner decision.

## Guardrails (deterministic, unit-tested)

All of these live in `supabase/functions/_shared/agent-core/` and are tested in Node like `analysis-core`. None is a
prompt instruction alone.

| Guard | Rule | Built on |
|---|---|---|
| Data scope | Tools return only streams the person has shared with the agent. Classes: **shared by default** (wearable, sleep, training, nutrition, hydration, labs, results); **opt-in** (wellbeing notes, health concern notes, sexual health tables); **never** (routine and location: `docs/routine-intelligence` rule 3). Enforced in the tool layer, so the model never sees what it may not send | `docs/health-concerns/01-plan.md` privacy rules |
| Numbers | Every number in a reply must match a value a tool returned in this turn (within its display rounding). A reply that fails is regenerated once, then returned with the failing sentence removed and a note | `_shared/log-core/proposal.ts` number grounding |
| Vocabulary | Verdict words only from the R8 list (AS EXPECTED, ABOVE EXPECTED, BELOW EXPECTED, UNUSUALLY LOW, MIXED SIGNALS, INPUTS ONLY, UNCLEAR, NOT YET SCORED). No invented thresholds or scores | `design/ANALYTICAL_PRESENTATION_CONTRACT.md` |
| Claim strength | An association never reads as a cause; "caused", "because of", "improves" are allowed only for experiment-class evidence with a clear verdict | `analysis-core/evidenceItems.ts` evidence classes |
| Writes | Propose-only. The client shows proposals with confirm buttons and writes through the existing repositories and RLS. The agent never writes `decisions`: a decision's rationale is typed by the person | `log-agent` pattern; `decisions` trigger |
| Budget | 8 tool steps, 60 s, a token cap per turn; a daily turn cap per user from `agent_turns` | Flash-lite quota experience in `log-agent` |
| Red flags | Fixed text; urgent ones end the turn without a model call | Wording of `healthEpisodes.ts` safety prompts |

## Models

Through OpenRouter (one OpenAI-compatible API) with **zero data retention enforced for the whole account**, providers
pinned per role (`provider.order`, `allow_fallbacks: false`, `require_parameters: true`) and, if data location matters,
an allowlist of US/EU providers. The model and provider list per role is configuration (an Edge Function secret), so a
change is not a code change, and a self-hosted vLLM endpoint can replace OpenRouter later through the same API.

| Role | Needs | First candidates (chosen by the eval, not by public rankings) |
|---|---|---|
| Router (free text only) | Fast, cheap classification | gpt-oss-20b; rules handle most cases |
| Interactive agent (all modes) | The most reliable tool calls, grounded writing | Kimi K3, GLM-5.x |
| Weekly analysts | Cheap, run in parallel | DeepSeek-V4.1-Flash, Qwen3.6-35B-A3B |
| Weekly reviewer | Different family from the analysts | GLM-5.x if the analysts are DeepSeek/Qwen, or Gemini paid tier |
| `log-agent`, nutrition parsing | Already working | Unchanged (Gemini flash-lite) |

Public rankings disagreed with each other when this was written, so the shortlists above are only where testing starts.
The Gemini free tier may use requests to improve Google's products; health data should go only through paid or
zero-retention routes.

## Data model (new tables, own-row RLS)

| Table | Holds |
|---|---|
| `agent_threads` | One conversation: mode, opened from (card id, item id, series, date), created and closed |
| `agent_turns` | One turn: question, tool calls and their result ids, model and provider per call, tokens, latency, guard results, the reply, proposals and whether each was accepted. `unique(user_id, client_turn_id)` so a retry returns the stored reply |
| `agent_memory` | Goals, preferences, obstacles, written only from confirmed proposals; listed and deletable in Setup |
| `agent_hints` | Weekly candidates: domain, text, result ids, reviewer rank and reason, state (open, registered, dismissed, needs engine question) |
| `agent_digests` | One row a week: the hints shown, the queue items that changed, cost |
| `agent_settings` | On/off, the streams shared, the daily cap |

## Android (Field Terminal, PR 24 structure)

The agent is an interpretation layer, not navigation (IA.md §6.2): no chat tab.

| Where | What | Mode |
|---|---|---|
| Today: THIS MORNING, IS TODAY TYPICAL?, TODAY'S RECOVERY, READY FOR | A **WHY?** action on the card header (`TileHeader`) opens a bottom sheet: two to four sentences, the results they rest on as chips that open the card or the record, and the data-quality caveat from integrity | EXPLAIN |
| Training, Nutrition, Health trend cards (`RedesignCards.kt`) | The same WHY? action, with the global range passed in | EXPLAIN |
| Status, beside the quick log | **ASK**: one input, kept apart from SAY IT so logging and questions do not mix | ASK, COACH |
| Lab › N=1 | **HINTS**: this week's hints with REGISTER, TEST IT (opens the existing experiment flow with the preview) and DISMISS; the digest's one-line summary | Weekly pass |
| Lab › PLANNING | **GOALS** from `agent_memory`, each with its linked experiment or decision review date | COACH |
| Setup › Profile & goals › Data capture | An **ASSISTANT** card in the existing per-stream style: TURN ON/OFF, the streams shared, the model and provider in use, DELETE HISTORY. Starts off | |

Client: an `AgentRepository` beside `LogAgentRepository` (same Ktor call with the user's JWT to
`/functions/v1/health-agent`), and a reply model that reuses the presentation contract's provenance cue. Replies carry a
**MODEL WRITTEN** cue; adding it to the contract's provenance list is an owner decision.

## Console (analysis hub)

| Where | What |
|---|---|
| Inspector (global) | An **EXPLAIN** section for the selected series, date or result, with links into Explore and Data |
| Evidence › DECISIONS workbench | **BRIEF**: what the evidence class can and cannot say, the integrity issues on its outcome, what changed since the last decision. **CHALLENGE**: the reviewer model argues the opposite reading. The decision form stays human-only |
| Evidence › DISCOVERY | Weekly hints listed beside discovery pairs, labelled exploratory, with the same register action |
| Models › **AGENT** | The audit view: turns, tool calls, guard failures, cost per week, eval scores per model and role, the hints' outcomes (registered, later clear, later negligible) |

The last row is the agent's own scorecard, in the spirit of R8: if hints rarely become clear verdicts, the weekly pass
reads NO BETTER and is switched off.

## Evaluation

- **Golden set**: about 80 questions (55 so far, phase 0) across EXPLAIN, ASK and COACH plus red-flag symptoms, written against planted
  data in the console's mocked Supabase (`console/e2e`) and against the real history. PHIA's published benchmark
  questions are a source of wording.
- **Automatic checks**: number grounding, vocabulary, claim strength against evidence class, valid tool calls, and a
  privacy test that plants data in never-shared streams and fails if any of it reaches a model request.
- **Owner rating**: 20 replies a week rated 1-5 for the first month.
- **Gates**: a phase ships only with zero privacy-test failures, at least 99 % of replies passing number grounding,
  and no causal wording on association-class items.

## Phases

| Phase | Deliverable | Gate | Status |
|---|---|---|---|
| 0. Core | `_shared/agent-core` (guards, scope classes, tool schemas), the model adapter, the golden set and its runner; no UI | Guards unit-tested; golden set runs against two models per role | Built; live model runs not done |
| 1. EXPLAIN | `health-agent` with read-only tools; WHY? on the four Today cards; Inspector EXPLAIN; `agent_turns`; Setup ASSISTANT card | Eval gates on EXPLAIN questions | |
| 2. ASK | Free questions, PubMed citations, proposals (register, experiment preview); the ASK entry on Status | Eval gates on ASK questions; proposals write only after confirmation | |
| 3. Weekly pass | `agent-digest`, analysts and reviewer, `agent_hints`, Lab › N=1 HINTS, Discovery hints, Models › AGENT | Hints carry result ids; at most five a week; scorecard live | |
| 4. COACH | `agent_memory`, goals in Lab › PLANNING, follow-ups tied to experiments and decision review dates | Owner rating ≥ 4 over a month | |

## Phase 0: what was built

All in `supabase/functions/_shared/agent-core/`, plain TypeScript that runs in Node (tests, CI) and Deno (Edge
Functions, phase 1). No table, function or screen was added; nothing reaches a model unless a person runs the script.

| File | What it does |
|---|---|
| `scope.ts` | Every table classified as shared, opt-in (notes, sexual health, health concerns, digestive), never (routine, location, other people, the calendar) or internal; an unclassified table reads as never. Rows pass through `serveRows`: free-text columns only with notes shared, location-like columns never, `user_profile` sex only. Results about routine series are withheld, using the engine's own list (`routineSeries`), so a new routine series is covered without an edit |
| `numbers.ts` | Every number, date and time in a reply must come from the turn's tool results, the question or the screen. Rounding at the stated precision, either sign and a fraction shown as a percentage are allowed; a number the model worked out itself is not. Names with digits (VO2max, D3, omega-3) are not numbers |
| `vocabulary.ts` | A label in capitals may appear only when the turn's sources used it, so the agent repeats verdicts and cannot coin one; score names and "n/100" ratings must come from the data |
| `claims.ts` | Causal wording only with a clear experiment in the results; hedged suggestions to test pass. Per reply, not per sentence (noted as a phase 1 decision) |
| `redFlags.ts` | Triage of the question before any model call: urgent (Taiwan 119; 1925 for self-harm) ends the turn, clinician puts fixed text first. Explicit negation clears a match; data words ("HRV collapsed") do not trigger |
| `tools.ts` | 17 tools (12 read, 1 external, 4 propose) with JSON schemas, the tables each reads, and the modes that may call them; `toolsFor` drops a tool whose tables are not shared; `checkCall` validates name, mode, scope and arguments; literature queries carry no numbers or "my". The registered-question space is checked against its migration |
| `guards.ts` | `checkReply`: all reply checks together, with the reply minus the sentences that broke a rule |
| `turn.ts`, `prompts.ts`, `models.ts` | One turn independent of provider: triage, the tool loop with every call checked, one regeneration with the reasons, then the cleaned reply. An OpenAI-compatible client with OpenRouter's zero-retention routing (`zdr`, `data_collection: deny`, `require_parameters`, pinned providers) |
| `golden/golden-set.json`, `golden.ts` | 42 reply cases (12 EXPLAIN, 21 ASK, 9 COACH; 10 of them symptom triage, 3 privacy), each with a reference reply and 42 planted bad replies in all, plus 6 privacy-filter cases and 7 tool-call cases. `scoreReply` is the one scoring rule for CI and live runs |
| `scripts/agent-golden/run.ts` | Runs live models against the set: `OPENROUTER_API_KEY=... node scripts/agent-golden/run.ts --model <slug> --model <slug> --out report.json`. Checked against a local fake server only |

| `supabase/functions/agent-eval` (deployed 2026-10-28) | The same live run server-side, reading the key from the `OPENROUTER_API` secret. Planted data only: it reads no table and writes nothing. Callable only with the scheduler's Vault secret (`x-analysis-cron`, checked by `analysis_cron_secret_ok()`), so runs start from SQL with pg_net; `next_offset` continues a run past the wall-clock limit. Free (`:free`) slugs run with logged endpoints allowed, any other slug requires zero data retention |

**First live runs (2026-10-28), partial.** Free endpoints are refused by the account's own settings (zero data
retention enforced, no free models that train on prompts; Inkling's free endpoint also takes only listed agent apps), so
the runs used paid zero-retention endpoints. They stopped when the key's credit ran out (HTTP 402) after 13 of the 42
cases; the first attempt had also failed because every request reserved credit for the model's full 131k output, now
capped (`maxTokens`, 1024 in production, 4096 in eval).

| Model | Non-urgent cases scored | Passed | What failed |
|---|---|---|---|
| Nemotron 3 Ultra | 9 | 8 | `explain-rhr-illness`: did not read the context events the ILLNESS LOGGED flag points to |
| Gemma 4 31B | 13 | 11 | the same case; `explain-recovery-inputs-only`: left out NO BETTER and said the card "has not yet produced" a verdict |
| Inkling | 4 | 2 | two empty replies, most likely its hidden reasoning using up the 1024-token cap; it also ran words and numbers together ("at52") |

All urgent triage cases passed for every model (the model is never called). The guards did their job: 8 replies were
regenerated once after a violation and then passed. Medians: Nemotron about 4 s a turn, Gemma 3 to 17 s, Inkling 6 to
17 s. Two harness faults surfaced and are fixed: a tool without planted data answered with an error, which models read
as "not shared" and repeated to the person (it now answers with an empty result), and the output cap above.

**Gemma through Google AI Studio (2026-10-28).** `aistudio/<model>` slugs in agent-eval use AI Studio's
OpenAI-compatible endpoint with `GEMINI_API_KEY`, on the first 12 cases (all EXPLAIN), compared with Gemma 4 31B on
OpenRouter over the same cases:

| Route | Attempts | Completed | Passed | Errors | Typical turn |
|---|---|---|---|---|---|
| OpenRouter, Gemma 4 31B | 12 | 12 | 10 | none | 3 to 17 s |
| AI Studio, Gemma 4 26B | 12 | 7 | 7 | 3 quota (429), 2 internal (500) | 14 to 31 s |
| AI Studio, Gemma 4 31B | 4 | 0 | 0 | every request with tools: internal error (500) or a 40 s timeout | |

When AI Studio answered, the answers were as good (26B passed TODAY'S RECOVERY, which 31B on OpenRouter missed), but the
route failed on about 4 in 10 attempts, was slower, and on this key is rate-limited; its data terms depend on the key's
tier, while OpenRouter enforces zero retention. Two AI Studio behaviours are handled in agent-core: the model's reasoning
arrives inside the reply as `<thought>...</thought>` (stripped before the guards), and each tool call carries a thought
signature that must be sent back on the next turn.

**Requesty (2026-10-28): the first complete run.** `requesty/<model>` slugs use Requesty's router with
`REQUESTY_API`; the account allows only models without data retention or training, one request at a time, 200 a day.
Of its three models, Ling 3.0 Tiny is listed but its provider answers "Not Found". About 171 of the 200 requests were
used.

| Model | EXPLAIN (12) | ASK (12) | COACH (9) | Symptoms, not urgent (5) | Urgent (5, no model call) | Requests per case | Typical turn |
|---|---|---|---|---|---|---|---|
| Ling 3.1 Flash | 12 | 10 | 8 | 3 | 5 | about 3 | 10 to 25 s |
| Leanstral 1.5 | 8 | not run | not run | not run | | about 4.5 | 7 to 11 s |

Ling 3.1 Flash passed 33 of 38 cases that call the model, with careful wording ("coincided with", "nothing recorded").
Its misses: it offered to register a question instead of drafting the registration; on "how is my knee" it did not say
that health concerns are not shared; COACH did not check the decision due for review; and two symptom cases, where it
looked for the planted data under a different tool than the one that held it. Leanstral, a model for formal proofs,
passed 8 of 12 EXPLAIN cases and three of its passes were wrong in meaning ("the composite hasn't been built yet",
"the reason is an active cold", "short duration is the main factor").

What the run changed in agent-core: the prompt now lists what is not shared (from the same settings as the tool layer),
says an empty result means nothing recorded, reads a named evidence item first, and has COACH check decisions due for
review; the claims check catches "the reason is", "main factor" and "is driving"; expectations read "isn't" as "is not".

Two limits of the set showed: the guards check numbers and wording, not meaning, so a wrong explanation that uses the
right numbers passes (an independent reviewer model or the owner's ratings are needed, as planned); and each case
plants its data under one tool, so a model that looks in a reasonable other place fails. The next version of the set
should plant data under every tool that would return it.

To finish: top up the key, then run batches from SQL (one at a time; parallel batches exhaust the in-flight budget):
`select net.http_post(url := '<project>/functions/v1/agent-eval', headers := <anon key + x-analysis-cron from Vault>,
body := '{"models":[...],"offset":12,"limit":6}', timeout_milliseconds := 150000)` and read `net._http_response`.

**Tests:** `node --test "supabase/functions/_shared/agent-core/*.test.ts"` (in CI, `test-core.yml`): 49 tests, among
them every reference reply passing, every bad reply failing for the reason it was planted for, every table in the
migrations and the code classified, and the privacy cases' planted values never reaching a model request. A strict
TypeScript check of the folder and the script passes.

**Not done in phase 0:** a live run against real models (needs an OpenRouter key and the owner's choice of models), a
Gemini-native adapter (OpenRouter can route to Gemini), and the remaining golden cases toward about 80.

## Owner decisions needed

| Question | Recommendation |
|---|---|
| Allow a language model to interpret results at all (an exception to the original design) | Yes, as an interpretation layer only, behind the guards above |
| Provider route | OpenRouter with account-wide zero data retention; US/EU providers only |
| Opt-in streams (wellbeing notes, health concern notes, sexual health) | Off by default, one toggle each |
| Widen `registered_questions` beyond six exposures and three outcomes | Yes, to the catalogue exposures, before phase 3 |
| A MODEL WRITTEN provenance cue in the presentation contract | Yes |
| Weekly pass cadence | Weekly, switched off if its scorecard reads NO BETTER after twelve weeks |

# Focus and structure (phone app, November 2026)

Status: **agreed; building** (M0 docs, M1 Sleep pilot and M2a are in the code; M2b is in review; the rest is not). Milestone M0 of the plan "Focus and structure plan: phone app"
(https://claude.ai/artifact/7RR8tvUMyoLC8YbUNJjupK). The analysis behind it is "Phone app: one focus per page"
(https://claude.ai/artifact/RCM9DRrXogGqwScEQDVrkj). Nothing in this document is in the code yet; `ui/nav/TopLevelTab.kt` and
`ui/nav/FieldTerminalNavHost.kt` stay authoritative until a milestone says otherwise. The web console is planned separately
and is not bound by this document.

## Why

TODAY has one question and answers it. The other tabs grew by accretion: Lab holds analysis, things you do and a routine
page; Me holds the slow picture, risk screens, records and a second routine page; Plan holds the future and the past. A page
that lists its headline, its supporting measures and its explanations as equal cards has no lead.

## The five tabs

Each tab has one question. **Admission test:** a new screen goes in the tab whose question it answers. If it fits none, it
goes in the Depth of the nearest page, never on a tab's top level.

| Tab | Question | Holds |
|---|---|---|
| TODAY | How am I, and what now? | CHARGE and the five domains, the next planned item, close the day. |
| PLAN | What am I doing next? | This week (training and food together), blocks, races, the supplement schedule and pantry. Future only. |
| + LOG | Record something. | The add tunnel and the day-by-day log. |
| INSIGHTS (was LAB) | What does my data say? | N=1 and experiments, Explore, reports, the evidence board. |
| ME | Who am I, slowly? | Healthspan, Body, Performance (with all Sessions), Health, Routine. |

## Where things move

| Today in | Item | Goes to | Milestone |
|---|---|---|---|
| Lab | Routine (practices, time, places, phone) | Me › Routine; today's practices become Today timeline items | M2b |
| Lab | Supplements, Pantry | Plan › Regimen | M2a |
| Lab | Tests (BP, grip, body check-in, sweat test, running form) | + Log › Measure; a "test due" chip on Today | M3 |
| Lab | Breathwork | + Log › Time (a practice type) | M3 |
| Lab | N=1, Explore, Reports | Insights | M2a |
| Plan | Sessions (recorded list) | Me › Performance; Plan keeps planned sessions | M2a |
| Me | Bloodwork, Exposures | Me › Health, section "Prevention and risk" | M2b |
| Me | Intimacy | stays behind the discreet and privacy setting, out of the first tab row | M2b |

Me goes from eight sub-tabs to five: Healthspan, Body, Performance, Health, Routine. No screen is deleted.

## The page pattern: Lead, Support, Depth

- **Lead.** One number or chart with a verdict word and one sentence. Exactly one per page.
- **Support.** Up to five compact tiles or rows: value, direction in words and an arrow, the metric dot. Tapping one swaps it
  into the lead in place, or opens `MetricHistoryOverlay` when the metric has no page of its own.
- **Depth.** Everything else, folded, one line each. Explanations (weights, caps, scorecards, "what is X") share one fold per
  page, **HOW THIS IS BUILT**.
- **Admission ladder.** A new analysis enters Depth. It becomes Support once it has a verdict. It leads only on its own page.

The contract text is amendment A10 in `design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md`.

### Verdict-line style

One sentence under the lead, in interface text, no more than 90 characters: the verdict word first, then the one thing that
drove it, with a number.

- `BELOW NORMAL · sleep was 5 h 40 against your usual 7 h 10`
- `IN BAND · training load held this week`
- `3 to do · 2 sessions to match, 1 debrief`

No colour carries the verdict alone: the word and the arrow always say it.

## Today's lead by time of day

| When | Lead | Support |
|---|---|---|
| Morning, until the first logged item | CHARGE with its one-sentence reason | the five domain rings |
| During the day | the next planned item (meal, session, supplements) | CHARGE as a header chip, the so-far bar, the plan timeline |
| From 21:00 or the 22:25 reminder | CLOSE THE DAY | CHARGE as a header chip |

Illness and heads-up lines stay directly under the lead in every state. IN THE BODY (caffeine, alcohol curves) goes to Depth
under one line.

## The add tunnel

Rules (contract amendment A11):

1. At most five choices on a screen, each with a label and a one-line hint.
2. A door with a single destination opens it directly.
3. The form is always the last step.
4. The header shows the path (`ADD › FUEL › MEAL`); Back goes up one level.
5. Choices use plain words for what the person is doing, not the app's nouns.

**Level 1.** LIKELY NOW (up to three shortcuts chosen by time of day and by what is missing today, plus last used and AGAIN
meals), then five doors:

| Door | Choices |
|---|---|
| FUEL | Meal, Drink, Supplements, Stool, Run fuel (only after a recent session) |
| FEEL | Wellness check, Something is wrong, Note |
| MEASURE | Weigh-in, Blood pressure, Body check-in, Grip, Sweat test, Running form |
| INTIMACY | Sexual activity, Encounter (follows the discreet setting) |
| TIME | Time block, Practice, Breathwork, Event |

**Level 2.** Meal asks how: photo, barcode, describe it, by hand, again. **Something is wrong** asks one plain question
(a small symptom, an illness or infection, an injury or pain) and replaces the three separate entries Symptom check, Illness
and Health. The symptom check can still hand off to "track as illness".

Multi-page forms (illness, symptom check, fuel debrief, supplements) share one header, `STEP 2 OF 3 · SYMPTOMS`. OTHER
disappears; Blood pressure moves into MEASURE.

A weigh-in goes from one tap to two. LIKELY NOW, AGAIN, Today plan items and reminder deep links keep the frequent entries at
one tap.

## Decisions for checkpoint C0

Approve or change each. The first four are recommendations, used as the working choice until answered.

| # | Question | Recommendation |
|---|---|---|
| 1 | Name of the tab: INSIGHTS or keep LAB | INSIGHTS, because Lab no longer holds anything you do |
| 2 | Lead metric of each Me tab | Healthspan: the healthspan score; Body: weight with its trend word; Performance: all-sports hours; Health: open concerns, or worth a clinician's view when present; Routine: regularity |
| 3 | Intimacy | Behind the privacy setting, out of the Me tab row, opened from Me › Health |
| 4 | Long-press on + for the top three recents | Try it in M3 and keep it only if the taps count says so |
| 5 | The charters, the move table and the tunnel labels above | Approve before anything is built |

## Built so far

| Milestone | What | State |
|---|---|---|
| M0 | This document, contract amendments A10 and A11 | merged (PR 71) |
| M1 | Lead, Support, Depth on the Sleep detail page (`FocusKit.kt`, `FocusPattern.kt`) | merged (PR 71), checked by Davide on his phone |
| M2a | PLAN › SESSIONS replaced by PLAN › REGIMEN (the supplements and pantry); Me › PERFORMANCE has an ALL SESSIONS row; the LAB dock tab and page are named INSIGHTS (the `lab` route and enum name stay so a saved back stack resolves); Lab's SUPPLEMENTS sub-tab is gone | merged (PR 79) |
| M2b | Me tab row is five (Healthspan, Performance, Body, Health, Routine); Bloodwork, Exposures and Intimacy open from Health › PREVENTION AND RISK; Lab › ROUTINE is gone, its practices, time, day map and commute are at the top of Me › ROUTINE; the Routine notification opens it | built, in review |
| M2c | Route and notification-link audit with tests | not started |

## Not covered here

Detail-page rollout, score overlay layout and per-tab pages are milestones M4 to M6 and get their own checks. The Field
Terminal Map is updated by the milestone that changes a screen (first at M2), not by this documentation change.

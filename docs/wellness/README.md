# Wellness (guided sexual-wellness sessions)

Private, clinical-register guided sessions on the phone, starting with stop-start training. Full proposal and the staged
programme: the project's "Guided sexual-wellness sessions" doc. This page describes what is built.

## Built

- **Lab › TESTS › WELLNESS** (`ui/screens/lab/WellnessScreen.kt`): the next session (its cycles follow the last ones), a note, RECENT.
- **Session** (`domain/wellness/StopStart.kt`): SETTLE (2 min breathing), then cycles of BUILD → STOP (tap with the rating
  reached) → PAUSE (at least 45 s, RESUME offered when arousal should have fallen to about 4) → BUILD, then COOL DOWN and a
  1 to 5 "how did it feel". Paced by the clock and two taps, not a fixed timeline. A stop at 9 or above is a "late" stop.
- **Progression** (`StopStartProgress`): starts at 3 cycles; two clean completed sessions in a row add a cycle (to 6);
  a session with more late stops than half its cycles takes one away. The stage is met after 6 completed sessions, the last
  three with 5+ cycles and at most one late stop between them.
- **Data**: `wellness_sessions` (`supabase/migrations/20261127140000_wellness_sessions.sql`), own rows, counts and ratings
  only, no free text. Times are the phone's wall clock labelled +00 (`WallClock`).
- **Voice** (`data/WellnessVoice.kt`): plays `assets/wellness/audio/<cue>.mp3` when it exists, otherwise the phone's speech
  engine reads the cue. Words: `assets/wellness/stopstart-v1.json` (a new version number for any rewrite).

## Making the recordings (ElevenLabs)

```
ELEVENLABS_API_KEY=... ELEVENLABS_VOICE_ID=... node scripts/wellness-audio/generate.mjs stopstart-v1
```

Run it on your own computer; the key stays there. Pick a calm voice in the ElevenLabs voice library and use its id. Existing
files are kept unless `--force`. Rebuild the app after adding files. The first run is also the check that the service accepts
these scripts.

## Not built yet

Pelvic-floor, squeeze/partner, orgasm-control stages and maintenance; the web view (progress and planner) in Me › Intimacy;
sessions in the Log feed; the discreet setting (the phone has no such setting yet, so the row shows in Lab › TESTS).

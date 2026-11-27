# Phone notifications

`NotificationDispatchWorker` runs every 15 minutes. A reminder is a question about the data, not about the clock: the
schedule only says when to ask.

## The rule

1. **Before posting**, every kind reads its own state (`check()` in the worker): supplements still due in the slot,
   wellbeing scores, the morning-erection / arousal values on today's `wellbeing_daily` row (morning and evening
   check-ins), saved "say it" messages, tonight's exposures, last body / health / questionnaire dates, unrated events,
   today's draws, closed follow-up windows. Done means nothing is posted. A failed read means nothing is posted and
   nothing is withdrawn (it never invents or suppresses a prompt).
2. **After posting**, a reminder still showing in the shade is withdrawn on the next run once its check says done
   (`withdrawDone`): logged in the app, on another phone or on the web. Only reminders still showing are looked up.
3. Fuel reminders (`fuel:<session>:pre|post`, race prompts) are withdrawn when their prompt is no longer in the plan
   (session recorded, moved or removed: `staleFuelKeys`). Practice reminders are withdrawn once the practice has a log
   today, done or skipped (`practicesLoggedOn`); the DONE / SKIP actions already cancel their own.
4. The `skip_if_logged` condition of a rule still switches rules 1 and 2 off for that rule.

Days are the phone's local calendar; logged values are read as wall clock (see CLAUDE.md, dates and times).

## Why (27 Nov 2026)

The morning check-in (`checkin_morning`, 09:00) was the only reminder with no state check: it fired at 09:04 although
the day's wellbeing row was complete. `checkinNeeded` existed in `domain/notifications/NotificationRules.kt` but the
worker never called it. The evening note (`checkin_evening`, 21:00) had the same gap.

Tests: `NotificationRulesTest`, `FuelPromptsTest`, `PracticesTest`.

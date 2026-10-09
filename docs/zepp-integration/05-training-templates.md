# 05 — Zepp training templates: format, cloud API, and what is still open

**Status:** create / list / delete of strength templates, and create / list / delete of calendar entries that
schedule a template, verified end to end against the real account (2026-10-09). Everything here is reverse-engineered from the Zepp Android app
(v10.8.7) and from templates shared out of the app; none of it is documented by Zepp and any of it can
change with an app update.

## 1. What a template is

A custom workout built in the Zepp app (Workout > Custom). A strength template is `trainingTypeId` 10
(`力量训练`, sport 52). One **NODE** is one set; a **CIRCLE** repeats its children `circleTimes` times.

| Field (on a NODE's `trainingInterval`) | Meaning |
|---|---|
| `intervalType` | `"0"` warm-up, `"1"` training, `"2"` rest, `"3"` recover, `"4"` cool-down |
| `intervalUnit` | `"10"` reps (strength), `"8"` manual: ends when Skip is tapped (used for rests, value `-1`) |
| `intervalUnitValue` | reps for a set, `"-1"` for a manual rest |
| `strengthWeight` | tenths of a kg + `-1`: `"425-1"` = 42.5 kg, `"0-1"` = no weight |
| `actionType` / `actionName` | exercise code and name from Gadgetbridge's `zeppos.json` (`app/src/main/assets/workouts/exercises/zeppos.json`, 1133 entries) |
| `mainPositions` / `subPositions` | muscle-region ids, from the same catalog entry |
| `selfWeightType` | `1` for bodyweight lifts (pull-up), `0` for barbell lifts |
| `alertRule` / `alertRuleDetail` | `"0"` / `"0-0"` (none) |
| `lengthUnit` | `0` |

The share file the app produces (the `data=` URL behind a share link) also carries `...I18nKey`,
`strengthWeightValue` and `strengthWeightUnit`. The cloud copy does not; they are not needed.

Verified decodes: three user-shared templates (3 sets of 3 lifts; warm-up + 4-set loop + rest; weight on a
pull-up) and the 8 Oct session's lap data, whose per-set weight and reps are the template's targets.
`strengthWeight` for a weighted pull-up is stored the same way as for a barbell (`"365-1"` = 36.5 kg).

## 2. Cloud API (host from `ZEPP_API_HOST`, header `apptoken`)

| Call | Result |
|---|---|
| `GET /users/training/templates` | the account's templates (`items`), same node format as above, slim |
| `GET /users/training/types?size=100` | template types (`id`, `sportType`, `supportIntervalUnits`, ...) |
| `PUT /users/training/templates` | **create**; body below. `POST` here is 405 |
| `DELETE /users/training/templates/{id}` | delete; 200 with an empty body |
| `POST /users/training/templates/{id}` | accepted verb (update); not exercised |
| `GET /users/training/plan/schedules` | `{"code":1,"data":[],"message":"success"}` for an account with none |

Create body (`TrainingTemplateEntityForCreate`, read from the app's serializer):

```json
{ "trainingTypeId": 10, "title": "...", "description": "...",
  "trainingInterval": { "type": "PARENT", "children": [ ...NODE / CIRCLE... ] },
  "target": [], "difficulty": [], "sourceType": 0, "modalities": [] }
```

The structure key is `trainingInterval` (singular). The share format's `trainingIntervals` makes the
server answer `400 {"code":-1001,"message":"Error parameter 'trainingStructureValid'"}`: that is the
server's structure check failing, not a missing parameter. Optional fields in the app's entity:
`clientWorkoutId` (number), `officialId`, `blockWorkout`, `totalTime`.

Ids are client-made in the app (millis * 1000 + 3 digits); the server assigned its own on create.

## 3. Scheduling (verified 2026-10-09)

A scheduled workout is a **calendar entry** at `users/{userId}/training/calendar` (`userId` = `ZEPP_USER_ID`),
read from the app's `TrainingScheduleCloudApiImpl` and confirmed against the real account:

| Call | Result |
|---|---|
| `PUT /users/{uid}/training/calendar` | **add**; body = one entity (no wrapper), answer `{"code":1,"data":{...,"id":"<ULID>"},"message":"Created"}`. `POST` here is 405 |
| `GET /users/{uid}/training/calendar?startTime=<ms>&endTime=<ms>&limit=100` | entries in the window as `data.items` (`next` for paging). Recurring entries appear when a repeat falls in the window |
| `DELETE /users/{uid}/training/calendar/{id}` | `{"data":{"deleted":1}}` |

Entity (Kotlin types from the app's bytecode; all non-null): `id` String (send `""`, the server makes a ULID),
`title`, `description`, `scheduledStartAt` / `scheduledEndAt` Long (epoch **ms**), `timezone` ("Asia/Taipei"),
`isRecurring` Boolean, `icalendarData` String, `provider` String, `status` Int.

Values that work, copied from the account's own recurring "Fighter" entry: `provider: "USER_CUSTOM"`,
`status: 1`. `status: 0` with provider `MANUAL_TRAINING_CALENDAR` is accepted but the entry is not listed
(inactive), and deleting it reports `deleted: 0`.

The template is linked inside `icalendarData`, an iCalendar text (CRLF or LF lines):

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Training App//Schedule//EN
CALSCALE:GREGORIAN
BEGIN:VEVENT
UID:<anything>
DTSTAMP:20260904T151853Z
DTSTART;TZID=Asia/Taipei:20261020T070000
DTEND;TZID=Asia/Taipei:20261020T080000
SUMMARY:<title>
RRULE:FREQ=WEEKLY;BYDAY=MO,TH        (optional, with isRecurring true)
X-TRAINING-TEMPLATE-ID:<template id from PUT /users/training/templates>
END:VEVENT
END:VCALENDAR
```

Other schedule-related endpoints in the app, not used: `users/training/plan/schedules` (GET by `startDate`/`endDate`,
DELETE `{ids}`) and `training/plan/list` + `training/plan/schedules` for official plans; PUT there returns a bare 400
for the calendar entity, so they are a different service.

Not yet checked: that a scheduled entry shows in the Zepp app's training calendar and reaches the watch after the
app's own sync.

## 4. Tools in the repo

- `supabase/functions/zepp-training`: `template.ts` builds a template from a plan (exercise catalog subset,
  warm-up sets, repeat groups, manual rests); `index.ts` is a probe: read-only GETs by default, and
  `{"action":"write_test"}` runs create > list > delete of a throwaway "ZZ TEST cloud" template, and `{"action":"schedule_test"}` creates a template, schedules it for 20 Oct, lists, then deletes both. Raw
  answers are stored in `zepp_raw_extracts` (metric `training_probe`). JWT required; uses the same
  `ZEPP_APP_TOKEN` as `zepp-extract`.
- Settings > Connected services > Zepp: "CHECK TEMPLATE API", "TEMPLATE WRITE TEST" and "TEMPLATE SCHEDULE TEST" buttons.
- Deep link that opens a template in the Zepp editor from a file served by the phone itself:
  `amazfit://com.huami.watch.hmwatchmanager/action?name=trainingtemplate_crossfit&target=share&data=<url>`;
  the app fetches `data` directly from the phone (an `adb reverse` localhost URL works).

## 5. How this was found

The Zepp APK was pulled from the phone (`adb shell pm path`), the dex files read with the SDK's
`dexdump -d`, and the serializer descriptors of the request/response classes listed
(`com.huami.trainingtemplate.core.remote.entity.*`, `com.huami.sport.training.plan.*`). Re-run that on a
new app version if a call starts failing.

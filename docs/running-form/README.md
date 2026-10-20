# Running form analyser

A phone video of a run becomes a short report on running form: how the foot lands, the knee and shin at landing, trunk
lean, bounce, hip extension and, with 120 fps or more, contact time. It lives on Lab › Tests (RUNNING FORM ANALYSER).
Everything runs on the phone; the video never leaves it.

## Why, and what it adds

The watch already measures cadence, ground contact time, vertical ratio and stride length on road runs (Training ›
Running). A camera adds what the watch cannot see: **where and how the foot lands relative to the body, and the angles
of the knee, shin, hip and trunk**. Those are what a physiotherapist's video gait analysis looks at (Souza 2016). The
report therefore leads with kinematics; cadence and contact time from the clip are shown next to the watch's own values
for the same run, as a check on both.

## Decisions (defaults until the owner says otherwise)

- **On the phone only**: Google's MediaPipe Pose Landmarker (free, Apache 2.0, offline). No server, no paid model, no
  language model; the feedback is fixed rules with their sources, like the rest of the app.
- **A recorded clip, not a live camera view**: film 10–20 s with the phone's own camera (slow motion if it has it) and
  import the file. Analysing a file is simpler, works on every phone and is more accurate than a live overlay.
- **Side view first** (sagittal angles, foot strike, timing). The rear view (pelvic drop, knee caving in, step width)
  comes in a later phase.
- **Results are stored, the video is not.** A 10 s clip at 120 fps is 30–60 MB; the stored result is a few kilobytes.

## Filming a clip

The in-app guide repeats this before the video is picked.

- **Where**: a treadmill is best (the camera stays still and every stride is in frame). Outdoors: a flat straight path,
  the phone fixed, and several passes through the frame.
- **Camera**: on a tripod or wedged steady, at hip height, square to the direction of running, 3–4 m away so the whole
  body including both feet stays in frame for the whole clip. Landscape. No zoom while filming.
- **Frame rate**: slow motion at 120 or 240 fps when the phone has it (contact time needs it); 60 fps gives the landing
  angles and foot strike; 30 fps gives only cadence, posture and bounce.
- **Runner**: tight or short clothing (baggy shorts hide the knee and hip), contrasting with the background; good light;
  warmed up and at the pace to be judged. 10–20 s of steady running, at least six steps of the leg nearer the camera.
- **Tell the app**: which side faces the camera, treadmill or overground, the speed (treadmill display) and, if the run
  was recorded by the watch, which session it was.

## What is measured (side view)

Angles are in the image plane, so they are 2-D projections of 3-D angles; they are valid when the camera is square to
the direction of running (Souza 2016). Lengths have no scale in a single video, so distances are given as a share of the
runner's own leg length.

| Metric | Definition | Needs | How it is read |
|---|---|---|---|
| Cadence | steps per minute from successive foot contacts | ≥ 30 fps | Against the watch for the same run; +5–10 % cadence lowers hip and knee load at the same speed (Heiderscheit 2011) |
| Contact time | initial contact to toe-off of the near foot, ms | ≥ 100 fps | Against the watch; recreational runners about 200–300 ms |
| Flight time, duty factor | step time minus contact time; contact time ÷ stride time | ≥ 100 fps | Descriptive |
| Foot strike | foot angle at contact minus its angle flat in mid-stance; rearfoot > 8.1°, midfoot −1.6 to 8.1°, forefoot < −1.6° (Altman & Davis 2012) | ≥ 50 fps | Descriptive: no pattern is "right" |
| Shin angle at contact | knee-to-ankle line against vertical, positive with the ankle ahead of the knee | ≥ 50 fps | Near vertical is the usual aim; a shin well forward is the overstride sign (Souza 2016; Lieberman 2015) |
| Foot ahead of hip at contact | horizontal ankle-to-hip distance ÷ leg length | ≥ 50 fps | Overstriding in another form (Lieberman 2015) |
| Knee flexion at contact | 180° minus the hip-knee-ankle angle | ≥ 50 fps | A nearly straight knee at landing goes with overstriding (Souza 2016) |
| Peak knee flexion in stance | most knee bend between contact and toe-off | ≥ 30 fps | Descriptive, compared over time |
| Hip extension at toe-off | thigh line behind vertical at toe-off | ≥ 50 fps | Descriptive, compared over time |
| Trunk lean | hip-midpoint-to-shoulder-midpoint line forward of vertical, over the clip | ≥ 30 fps | Some forward lean shifts load from knee to hip (Teng & Powers 2014) |
| Vertical oscillation | rise and fall of the hip midpoint per step ÷ leg length | ≥ 30 fps | Against the watch's vertical ratio; descriptive |
| Step-to-step variability | spread of step times | ≥ 30 fps | A high spread flags an unsteady clip as much as an unsteady runner |

Rear view, later phase: contralateral pelvic drop, knee frontal-plane projection angle at mid-stance, step width and
heel whip (Souza 2016).

Reference values are **starting points with their source**, not targets. The main reading is the person's own change
from one analysis to the next, as everywhere else in the app; a value outside the usual range reads WORTH A LOOK with the
reason, never "bad".

## How a clip is analysed

1. **Frames**: the video is read frame by frame with its real timestamps. A slow-motion file is checked for its capture
   frame rate (`METADATA_KEY_CAPTURE_FRAMERATE`), because some phones store slow motion re-timed to 30 fps.
2. **Pose**: MediaPipe Pose Landmarker in video mode gives 33 body points per frame with a visibility for each.
3. **Clean-up**: short gaps (≤ 0.1 s) are filled; left and right are un-swapped where MediaPipe swaps them (a known
   problem in a pure side view); the points are low-pass filtered forward and backward (Butterworth, cut-off 12 Hz, or a
   fifth of the frame rate when lower; Winter 2009).
4. **Which leg is near**: the leg with the higher visibility; the near leg carries the timing, the far leg is reported
   only when it is visible enough (Young et al. 2023 found right-foot errors from exactly this occlusion).
5. **Contacts**: a foot is on the ground while its lowest point (heel or toe) stays near the floor line under that step.
   Landing and toe-off are where the foot crosses two levels just above that floor, interpolated between frames and
   extrapolated to the floor itself (one level alone would make every contact too long). Each contact is checked against
   the coordinate method of Zeni et al. (2008: the foot is ahead of the pelvis at landing and behind it at toe-off), and
   contacts of implausible length are dropped. Angles at landing and toe-off are read at those interpolated moments.
6. **Metrics** per step, then the median and spread over the clip.
7. **Quality** is judged separately from form: share of frames with a person, visibility of the near leg, number of
   usable steps, real frame rate, camera steadiness, and whether the view really is a side view. A POOR clip shows why and
   what to change, and no form findings.
8. **Findings**: fixed rules, each with its reason and source; the watch's values for the linked run alongside.

## Accuracy and limits

- One camera, 2-D. Angles are projections; a camera 20° off square changes them by several degrees.
- On the synthetic runner the analyser reads landings within a frame at 120 fps and the knee at landing about 2° high (3°
  at 60 fps; at 30 fps 8°, which is why landing angles need 50 fps): the filter smooths the knee's fast bend just after
  landing. Real footage will be checked against the watch and by eye on the key frames.
- Published agreement with lab motion capture for the same pose model on a smartphone, running: good to excellent for
  contact, swing and step time, knee flexion and foot strike (ICC 0.75–0.90+), timing errors 0.01–0.014 s on the near
  foot, worse on the far foot (Young et al. 2023). Walking studies with MediaPipe show stance-time errors near 190 ms at
  low frame rates, which is why contact time needs 100 fps or more.
- The heel and toe points are MediaPipe's estimates of the foot, not the shoe; foot strike is an estimate and is labelled
  so.
- Treadmill and overground running differ slightly; the report says which it was.
- Not a medical device; the findings describe, they do not diagnose.

## Architecture

- `domain/form/` (pure Kotlin, tested): the pose clip model, clean-up (gap fill, left-right swap fix, zero-phase
  Butterworth), contact detection, metrics, quality and findings. A synthetic runner (stick figure with known contacts,
  angles and foot strike, plus noise, swaps and dropped frames) drives the tests.
- `data/form/` (Android): video frame reader (MediaMetadataRetriever, capture frame rate), the MediaPipe pose extractor
  (VIDEO mode, background thread, progress, cancel) and, later, the repository.
- `ui/screens/form/`: the guide, the picker and choices, progress, and the report (quality, findings, a few key frames with
  the skeleton drawn so the reading can be checked by eye).
- Dependency `com.google.mediapipe:tasks-vision` (0.10.26 or newer: the first release whose Android packages support the
  16 KB page size). The pose model `pose_landmarker_full.task` (9.4 MB, version 1, SHA-256 `5134a3aa…11b1`) is downloaded
  by a Gradle task at build time into a generated assets folder and checked against its hash; it is not committed.
- Storage (phase F4): `running_form_analyses` (owner-only RLS): when, view, surface, speed, frame rate, quality, metrics,
  per-step values, the linked session, analyser version, notes. No video, no per-frame points.

## Phases (one commit each)

- **F1, the analysis core**: `domain/form/` with the synthetic runner and tests: clean-up, contacts, every side-view
  metric, quality and findings. Verifiable here end to end on synthetic clips.
- **F2, pose on the phone**: the dependency and model download, the frame reader, the pose extractor. Type-checked here
  against stubs; real detection is checked on the owner's phone.
- **F3, the screen**: START on Lab › Tests, the filming guide, picking the clip and the choices, progress with cancel,
  the report with key frames.
- **F4, saving and comparing**: the migration (applied with the owner's go-ahead), save and history, the linked run's
  watch values next to the clip's.
- **F5, rear view**: pelvic drop, knee projection angle, step width.
- **F6**: registry entries, docs, PR description.

## Costs

None recurring: MediaPipe and the model are free and run on the phone; stored results are a few kilobytes per analysis.
The app grows by about 10–20 MB (native libraries and the model). A tripod or phone clamp (about $15–30) helps; a
treadmill makes the best clips.

## Needed from the owner

A test clip once F2 is in: 10–20 s on a treadmill, side view, slow motion if the phone has it, ideally during a run the
watch records. It is the only way to check detection on real footage before the report is trusted.

## References

- Altman AR, Davis IS (2012). A kinematic method for footstrike pattern detection in barefoot and shod runners. *Gait &
  Posture* 35(2):298–300.
- Heiderscheit BC et al. (2011). Effects of step rate manipulation on joint mechanics during running. *Med Sci Sports
  Exerc* 43(2):296–302.
- Lieberman DE et al. (2015). Effects of stride frequency and foot position at landing on braking force, hip torque,
  impact peak force and the metabolic cost of running in humans. *J Exp Biol* 218:3406–3414.
- Souza RB (2016). An evidence-based videotaped running biomechanics analysis. *Phys Med Rehabil Clin N Am* 27(1):217–236.
- Teng HL, Powers CM (2014). Sagittal plane trunk posture influences patellofemoral joint stress during running. *J
  Orthop Sports Phys Ther* 44(10):785–792.
- Winter DA (2009). *Biomechanics and Motor Control of Human Movement*, 4th ed. Wiley.
- Young F et al. (2023). Internet-of-Things-enabled markerless running gait assessment from a single smartphone camera.
  *Sensors* 23(2):696.
- Zeni JA, Richards JG, Higginson JS (2008). Two simple methods for determining gait events during treadmill and
  overground walking using kinematic data. *Gait & Posture* 27(4):710–714.
- Reference implementations read for methods (not used as code): myogait (MIT), vision-demos `running` (Apache-2.0),
  motion-analysis-ai (MIT).

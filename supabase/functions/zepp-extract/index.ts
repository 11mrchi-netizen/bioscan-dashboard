// zepp-extract — DAV-112
//
// Supabase Edge Function that extracts data from the Zepp mobile API
// and stores raw responses in zepp_raw_extracts for inspection/decoding.
// All Zepp credentials stay server-side (env vars); the caller only needs
// a valid Supabase JWT and a date range.

import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import { computeEffort } from "./effort.ts";
import { decodeMinuteHr, type HrMinutePoint } from "./minuteHr.ts";
import { hrHistogram } from "./hrHistogram.ts";
import { mergedSessionFields, pickSessionMatch, type SessionCandidate, type Span, spanOf, START_TOLERANCE_MS } from "./sessionMatch.ts";
import { exerciseTypeForZeppSport, summaryDistanceM, takesDistance, zeppDistanceKm } from "./workoutDistance.ts";
import { afterWindow, HISTORY_METRICS, type HistoryState, windowDates } from "./historyPull.ts";
import { buildZeppExercises, doneSets, exercisesFingerprint, parseLapSets, planStrengthDetails } from "./strengthImport.ts";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}

const EXTRACTOR_VERSION = "14"; // 4: GAP/EF/decoupling (DAV-272); 5: altitude is cm, drop no-fix samples;
// 6: write Zepp's own decoded distance back onto exercise_sessions.distance_km, and merge
// orphaned zepp-sourced placeholder rows created by the Zepp/Health-Connect sync race (DAV-274)
// 7: fix the "stress" metric's endpoint (was 404ing every attempt -- see METRIC_DEFS) (DAV-246)
// 8: the v7 endpoint fix moved stress from 404 to a real Huami-side 500
// ("code":-50000, "Failed to process the request") -- fetchAndStore was only
// ever sending `apptoken`, never the app-identity headers Huami's newer
// /users/{id}/events endpoint family validates (legacy /v1//v2/ endpoints
// tolerate their absence, this one doesn't). Real values confirmed against
// zepp-health-cli's own _headers() (github.com/m4ary/zepp-health-cli,
// literal defaults baked into the library, not placeholders) -- same
// reverse-engineering source that found the endpoint shape itself.
// 9: band_data endpoints (heart_rate/hrv/sleep/spo2) were all 404ing because
// we used /v2/ path, query_type=summary, single `date` param, and a
// data_type filter -- none of which match the real mobile API. Corrected to
// match ZeppBridge's working approach: /v1/ path, query_type=detail,
// from_date/to_date range params, byteLength=8, no data_type filter.
// 10: parse band_data response -- decode the base64 summary JSON to extract
// sleep stages, resting HR, sleep score, and steps, then upsert into
// sleep_daily and wearable_daily. Stage mode mapping confirmed against a
// real response: 4=light, 5=deep, 7=awake, 8=REM.
// 13: done lap sets carry status -1 (older exports) or 0 (current firmware); 2 is a planned set that was skipped.
// 14: Analysis Layer 3 R1 -- record the origin of every daily field this function writes in
// daily_field_provenance (origin 'zepp', writer 'zepp-extract'), because sleep_daily/wearable_daily rows are shared with
// the Health Connect sync (same user_id,date upsert key, different fields) and the row-level `source` column cannot
// say who wrote which field; and apply the same 2 h plausibility floor the Health Connect path uses so a nap/blip never
// becomes a night (live data had 0.08 h and 0.13 h nights).


// Same floor as HealthConnectDailySyncRepository.MIN_PLAUSIBLE_SLEEP_HOURS (DAV-212): a date whose longest sleep is
// shorter than this gets no sleep row instead of a fabricated near-zero night.
const MIN_PLAUSIBLE_SLEEP_HOURS = 2.0;

// Records which origin wrote each field of a daily row. Best effort: provenance must never fail the data write.
// deno-lint-ignore no-explicit-any
async function recordFieldProvenance(supabase: any, userId: string, date: string, fields: string[]): Promise<void> {
  if (fields.length === 0) return;
  const rows = fields.map((field) => ({
    user_id: userId,
    date,
    field,
    origin: "zepp",
    writer: "zepp-extract",
    written_at: new Date().toISOString(),
  }));
  const { error } = await supabase.from("daily_field_provenance").upsert(rows, { onConflict: "user_id,date,field" });
  if (error) console.warn(`daily_field_provenance upsert failed for ${date}: ${error.message}`);
}

// DAV-115/123: decode detail.json's per-second fields into plain TimePoint-
// shaped series the Android app already knows how to render (see
// domain/SessionDetail.kt). Format confirmed 2026-09-25 against a real GPS
// run, cross-checked against that run's own summary numbers -- see
// docs/zepp-integration/03-workout-detail-field-decode.md. Same decoder for
// every sport_type: a real 30-day backfill across run/strength/trail-run/
// cycling confirmed the encoding is identical, just with different fields
// left empty (e.g. strength has no GPS/pace/speed) -- no per-type branching
// needed, only per-field presence checks.
interface DecodedPoint {
  offsetSeconds: number;
  value: number;
}

interface WorkoutSummaryFields {
  // DAV-272, computed here from the per-second series (see effort.ts); only
  // set for running sport types with usable altitude + distance.
  gapMinPerKm?: number | null;
  efficiencyFactor?: number | null;
  hrDecouplingPct?: number | null;
  smoothedAscentM?: number | null;
  avgCadenceSpm: number | null;
  maxCadenceSpm: number | null;
  avgStrideLengthCm: number | null;
  avgGroundContactMs: number | null;
  avgVerticalStrideRatioPct: number | null;
  lactateThresholdHrBpm: number | null;
  lactateThresholdPaceSecPerKm: number | null;
}

interface DecodedSeries {
  heartRate: DecodedPoint[];
  speedKmh: DecodedPoint[];
  altitudeM: DecodedPoint[];
  distanceKm: DecodedPoint[];
  cadenceSpm: DecodedPoint[];
  verticalStrideRatioPct: DecodedPoint[];
  routePoints: RoutePoint[];
  summary: WorkoutSummaryFields;
}

interface RoutePoint { offsetSeconds: number; lat: number; lon: number; }

function splitEntries(raw: unknown): string[] {
  if (typeof raw !== "string" || raw.length === 0) return [];
  return raw.replace(/;$/, "").split(";").filter((e) => e.length > 0);
}

// heart_rate: first entry is the absolute starting bpm; every entry after
// that is a signed delta to add to the running total. The flag before the
// comma (seen: empty, 0, 2, 7 on entry 1 only) doesn't gate the decode.
function decodeHeartRate(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  let running = 0;
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const v = Number(parts[1] ?? parts[0]);
    if (!Number.isFinite(v)) return;
    running = i === 0 ? v : running + v;
    out.push({ offsetSeconds: i, value: running });
  });
  return out;
}

// speed: "<flag>,<m/s>" per second, no delta encoding -- convert straight to km/h.
function decodeSpeedKmh(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const ms = Number(parts[1] ?? parts[0]);
    if (!Number.isFinite(ms)) return;
    out.push({ offsetSeconds: i, value: ms * 3.6 });
  });
  return out;
}

// altitude: plain per-second value, no delta encoding, in CENTIMETRES (checked
// 2026-09-25: a run with ~20 m of real ascent spans 922..2054; a trail run
// tops out at 30716 = 307 m) -- converted to metres here. Zepp writes about
// -2,000,000 when it has no fix; those samples are dropped, not decoded.
const ALTITUDE_INVALID_BELOW_CM = -1_000_000;
function decodeAltitude(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  entries.forEach((entry, i) => {
    const v = Number(entry);
    if (!Number.isFinite(v) || v < ALTITUDE_INVALID_BELOW_CM) return;
    out.push({ offsetSeconds: i, value: v / 100 });
  });
  return out;
}

// gait: "<f1>,<f2>,<vertRatio_x10>,<cadence_spm>" per second -- f1/f2 look
// like run-state flags (0 before the run starts, settling to 1/2 once
// steady), not decoded since neither has a summary field to check against.
// Confirmed 2026-09-25: avg cadence (f4) = 175.5 vs summary avg_frequency
// 173.0; max f4 = 200 vs summary max_frequency 201; avg f3/10 = 8.89% vs
// summary avgVertStrideRatio/10 = 8.6% -- both close enough (same
// time-vs-distance-weighting gap as pace/speed) to trust. No ground-contact-
// time or stride-length per-second series found in this or any other field;
// those exist only as this workout's summary averages (see WorkoutSummaryFields).
function decodeCadenceAndVerticalRatio(raw: unknown): { cadenceSpm: DecodedPoint[]; verticalStrideRatioPct: DecodedPoint[] } {
  const entries = splitEntries(raw);
  const cadenceSpm: DecodedPoint[] = [];
  const verticalStrideRatioPct: DecodedPoint[] = [];
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const vertRatio = Number(parts[2]);
    const cadence = Number(parts[3]);
    if (Number.isFinite(cadence) && cadence > 0) cadenceSpm.push({ offsetSeconds: i, value: cadence });
    if (Number.isFinite(vertRatio) && cadence > 0) verticalStrideRatioPct.push({ offsetSeconds: i, value: vertRatio / 10 });
  });
  return { cadenceSpm, verticalStrideRatioPct };
}

function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const R = 6371000;
  const toRad = (d: number) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return R * 2 * Math.asin(Math.sqrt(a));
}

// longitude_latitude: first pair is absolute (lat*1e8, lon*1e8); every pair
// after that is (dlat*1e8, dlon*1e8) to cumulatively sum. Produces cumulative
// distance (km), not the raw lat/lon track -- that's all SessionDetail needs
// today; the reconstructed positions themselves aren't kept.
function decodeDistanceKm(raw: unknown): DecodedPoint[] {
  const entries = splitEntries(raw);
  const out: DecodedPoint[] = [];
  let lat = 0, lon = 0, cumMeters = 0;
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const a = Number(parts[0]);
    const b = Number(parts[1]);
    if (!Number.isFinite(a) || !Number.isFinite(b)) return;
    if (i === 0) {
      lat = a / 1e8;
      lon = b / 1e8;
    } else {
      const newLat = lat + a / 1e8;
      const newLon = lon + b / 1e8;
      cumMeters += haversineMeters(lat, lon, newLat, newLon);
      lat = newLat;
      lon = newLon;
    }
    out.push({ offsetSeconds: i, value: cumMeters / 1000 });
  });
  return out;
}

// Gadgetbridge Zepp OS exercise catalog (Freeyourgadget/Gadgetbridge, zeppos.json)
// actionType → display name. Extracted 2026-10-08. Fallback: "Exercise ${code}".
// ponytail: inline map rather than bundled JSON file to avoid Deno file-read setup.
const ZEPP_EXERCISE_NAMES: Record<number, string> = {1:"Bodyweight Squats",2:"Straight Arm Front Squats",3:"Barbell Squats",4:"Triceps Pushdowns",5:"Bent Over Row",6:"Bench Press",7:"Sit Up Holds",8:"Knee Touch Sit-ups",9:"Dumbbell Flys",10:"Burpee Jumps",11:"Burpees",12:"Overhead Tricep Extensions",13:"Lying Tricep Extensions",14:"Lateral Raises",15:"Jumping Jacks",16:"Incline Dumbbell Row",17:"Bent-over Dumbbell Row",18:"Bent-over Barbell Row",19:"Single-arm Dumbbell Row (Left)",20:"Single-arm Dumbbell Row (Right)",21:"Dumbbell Supine Triceps Extensions",22:"Supine Triceps Extensions",23:"Dumbbell Single-arm Triceps Extensions(Left)",24:"Seated Bent-over Dumbbell Triceps Extensions",25:"Seated Triceps Press",26:"Smith Machine Squats",30:"Dumbbell Bench Press",32:"Smith Machine Bench Press",33:"Dumbbell Single-arm Triceps Extensions (Right)",34:"Incline Dumbbell Flys",35:"Decline Dumbbell Flys",37:"V-bar Tricep Pushdowns",38:"Triceps Pushdowns- Rope Attachment",39:"Elastic Band Lateral Raise",40:"Dumbbell Lateral Raise",41:"Dumbbell Front Raise",60:"Lat Pulldowns",61:"Deadlift",62:"Push-up",63:"Shoulder Press",64:"Pull Up",65:"Biceps Curl",66:"Seated Cable Row",67:"Upright Row",68:"Straight-arm Pulldown",69:"Russian Twist",70:"Barbell Deadlift",71:"The perfect Push-UP",72:"Dumbbell Push Press",73:"Dumbbell Upright Row",74:"Neck Pulldown",75:"Chin-up",76:"Dumbbell Biceps Curl",77:"T-bar Row",78:"Kettlebell Deadlift",79:"Kneeling Push-up",80:"Rotational Dumbbell Push Press",81:"Incline Barbell Upright Row",82:"Rope Straight-arm Pulldown",83:"The perfect pull-up",84:"Hammer Curl To Press",85:"Seated Row Machine",86:"Chest Flyes",87:"Single-arm Kettlebell Swing (Right)",88:"Barbell Push Press",89:"Dumbbell Compound Push Press",90:"Barbell Biceps Curl",91:"Single-arm Kettlebell Swing (Left)",92:"V-grip Cable Row",93:"Dumbbell Front Raise (Left)",94:"Dumbbell Front Raise (Right)",95:"Bent-over Lateral Raise",96:"Front Raise",97:"Plank",98:"Barbell Hip Thrust With Bench",99:"Barbell Snatch",100:"Standing Calf Raise",101:"Barbell Shrug",102:"Dumbbell Shrug",103:"Farmer's Carry Walk Lunge",104:"V-up",105:"Lateral Slide",106:"Battle Rope",107:"Kettlebell Swing",108:"Walking",109:"Incline Dumbbell Bench Press",110:"Incline Barbell Bench Press",111:"Decline Dumbbell Bench Press",112:"Decline Barbell Bench Press",113:"Butterfly Chest Workout",114:"Butterfly Machine Reverse Flys",115:"TRX Triceps Press",116:"TRX Reverse Flys",117:"Chest Push From 3 Point Stance",118:"Incline Cable Flys",119:"Incline Cable Chest Press",120:"Low Cable Crossover",121:"Cable Crossover",122:"Reverse Grip Triceps Pushdown",123:"Cable Crunch",124:"Smith Machine Incline Bench Press",125:"Smith Machine Decline Bench Press",126:"Chest Press Machine",127:"Leverage Incline Chest Press",128:"Leverage Decline Chest Press",129:"Leverage Chest Press",130:"Machine Shoulder Press",1001:"Squat",1002:"Crunch",1003:"Wall Sit",1004:"Glute Bridge",1005:"Prone Back Extension",1006:"Slow Sit-Up",1007:"Superman Pose",1008:"Prone V-Up",1009:"Kneeling Ab Wheel Rollout",1010:"Sumo Squat",1011:"Dumbbell Squat",1012:"Kneeling Back Stretch",1013:"Alternating Knee Raise Squat",1030:"Goblet Squat",1036:"Pike Push-Up",1039:"Dumbbell Straight Leg Deadlift",1041:"Leg Raise",1042:"Dumbbell Sumo Squat",1051:"Bent Over Dumbbell Tricep Extension",1053:"Back Extension",1057:"Basic Push-Up",1060:"Toe Touch V-Up",1064:"Right Side Lunge",1068:"Forward Alternating Lunge",1072:"Good Morning",1076:"Dumbbell Single Leg Deadlift",1094:"Dumbbell Deadlift",1098:"Exercise Ball Back Extension",1100:"Dumbbell Alternating Curl",1101:"Left Leg Lunge",1103:"Right Side Bulgarian Split Squat",1107:"Assisted Back Extension",1108:"Walking Lunges",1110:"Barbell Curl",1111:"Box Squat",1113:"Weighted Back Extension",1114:"Straight Arm Plank",1117:"Dumbbell Crunch",1119:"Left Side Bulgarian Split Squat",1121:"Quadruped",1123:"Left Side Dumbbell Curl",1124:"Decline Diamond Push-Up",1126:"Left Side Dumbbell Lunge",1127:"Kneeling Plank",1128:"Reverse Alternating Lunge",1130:"Slow Reverse Crunch",1131:"Standing Alternating Leg Curl",1132:"Seated Overhead Dumbbell Tricep Extension",1135:"Right Side Dumbbell Lunge",1137:"Triple Squat",1139:"Reverse Crunch",1140:"Knee Bent Supine Bridge",1141:"Knee Bent V-Up",1143:"Dumbbell Wrist Curl",1146:"Plank with Hip Twist",1147:"Sphinx Push-Up",1149:"Crunch with Punch",1150:"Half Squat",1152:"Kneeling Push-Up",1153:"Plank with Rotation",1158:"Alternating Shoulder Tap Plank",1162:"Cushion Squat",1164:"Dumbbell Hammer Curl",1165:"Resistance Band Curl",1167:"Plank Hip Raise",1168:"V-Sit Hold",1170:"Alternating Single Leg Plank Push-Up",1171:"V-Sit Hold with Twist",1172:"Cossack Squat",1174:"Dumbbell Glute Bridge Press",1183:"Squat Thrust",1187:"Reverse Grip Pull-Up",1189:"Banded Glute Bridge Abduction",1199:"Supine Dumbbell Tricep Extension",1201:"Wide-Grip Pull-Up",1205:"Squat with Alternating Straight Punch",1209:"Dead Bug",1214:"Behind the Neck Dumbbell Tricep Extension",1219:"Sliding Deep Push-Up",1221:"Toe Touch Squat",1225:"Kettlebell Sumo Deadlift",1226:"Quick Russian Twist",1227:"EZ-Bar Curl",1230:"Dumbbell Half Curl",1234:"Supine Barbell Skull Crusher",1237:"Ab Wheel Plank",1239:"Elevated Push-Up",1241:"Eccentric Pull-Up",1243:"Assisted Squat",1246:"Yoga Mat Assisted Crunch",1250:"Kettlebell Goblet Squat",1251:"Right Dumbbell Concentration Curl",1253:"Dumbbell Thruster",1262:"Triceps Dip",1265:"Crunch with Twist",1266:"Squat with Alternating Leg Kick",1272:"EZ-Bar Reverse Curl",1275:"Kneeling Half Ab Wheel Rollout",1281:"Dumbbell Spider Curl",1290:"EZ-Bar Narrow Grip Curl",1292:"Seated Scissor Kick",1293:"Squat with Twist Punch",1296:"High Glute Bridge",1301:"Barbell Straight Leg Deadlift",1305:"Cable Supine Curl",1306:"Right Dumbbell Bulgarian Split Squat",1308:"Incline Dumbbell Curl",1309:"Left Dumbbell Bulgarian Split Squat",1310:"Barbell Romanian Deadlift",1317:"Dumbbell Row Deadlift",1320:"Goblet Left Side Lunge",1322:"Dumbbell Forward Alternating Lunge",1323:"Goblet Forward Alternating Lunge",1332:"Simple Dead Bug",1341:"Goblet Right Side Lunge",1344:"Supine Resistance Band Curl",1346:"Dumbbell Romanian Deadlift",1347:"Plank Elbow to Ground",1350:"Resistance Band Weighted Push-Up",1352:"Dumbbell Right Leg Offset Deadlift",1356:"Four-Point Ab Wheel",1358:"Horizontal Hammer Curl",1361:"Dumbbell Russian Twist",1362:"Right Leg Touch Deadlift",1366:"Barbell Sumo Deadlift",1374:"Right Dumbbell Elbow Fixed Curl",1378:"Left Dumbbell Elbow Fixed Hammer Curl",1381:"Ab Wheel Glute Bridge",1382:"Seated Bent Knee V-Up",1385:"Supine Alternating Diagonal Crunch",1387:"Ab Wheel Mountain Climbers",1390:"Banded Squat with Knee Band",1392:"Plank Leg Step Out",1394:"Resistance Band Diamond Push-Up",1396:"Resistance Band Deadlift",1398:"Dumbbell Hip Thrust",1400:"Right Dumbbell Elbow Fixed Hammer Curl",1402:"Resistance Band Russian Twist",1404:"Left Dumbbell Elbow Fixed Curl",1406:"Kneeling Ab Wheel Plank",1408:"Decline Crunch",1409:"Left Touch Deadlift",1411:"Supine Weighted Glute Bridge",1415:"Right Split Stretch",1416:"Left Dumbbell Windmill",1417:"Prone Alternating Straight Leg Lift",1419:"Foam Roller Quad Relaxation",1420:"Decline Reverse Crunch",1421:"Inchworm to Tuck Jump",1423:"Barbell Forward Alternating Lunge",1425:"Incline Supine Dumbbell Hammer Curl",1427:"Bent Over Dumbbell Rotational Row",1429:"Left Split Stretch",1430:"Barbell Plate Overhand Curl",1431:"Dumbbell Sumo Deadlift",1433:"Barbell Spider Curl",1434:"Plank Knee to Donkey Kick",1436:"Right Lunge with Knee Raise",1438:"Smith Machine Incline Bench Press",1440:"Left Single Leg Assisted Half Squat",1441:"Partial Decline Crunch",1442:"Left Lunge Kick",1444:"Dumbbell Goblet Squat to Straight Arm Raise",1447:"Bear Kick",1449:"Side Lunge",1450:"Resistance Band Assisted Pull-Up",1454:"Exercise Ball Push-Up",1455:"Twisted Crunch with Leg Raise",1456:"Barbell Front Squat",1459:"Resistance Band Squat",1460:"Dumbbell Left Leg Offset Deadlift",1465:"Right Dumbbell Windmill",1466:"Bear Crawl",1467:"Dumbbell Goblet Squat",1468:"Fitness Bench Crunch",1469:"Right Reverse Lunge",1472:"Twisted Crunch with Diagonal Leg Raise",1473:"Diagonal Plank Crunch",1474:"Squat to Straight Arm Raise",1477:"Barbell Reverse Alternating Lunge",1478:"Cable Seated Crunch",1480:"Resistance Band Sumo Deadlift",1481:"Forward-Backward Cat Crawl",1483:"Dumbbell Supine Single Leg V-Up",1485:"Duck Walk",1487:"Dumbbell Reverse Alternating Lunge",1488:"Right Single Leg Hip Thrust",1490:"Dumbbell Russian Twist",1492:"Elevated Left Single Leg Glute Bridge",1493:"Dumbbell Bicycle Crunch",1496:"Barbell Overhead Squat",1498:"Crunch with Right Knee to Foot Touch",1500:"Barbell Alternating Side Lunge",1501:"Twisted Crunch with Right Leg Raise",1503:"Stability Ball Wall Sit",1505:"Stability Ball Plank",1506:"Barbell Left Bulgarian Split Squat",1508:"Supine Stability Ball Pass V-Up",1510:"Squat with Forward Punch",1512:"Barbell Box Squat",1514:"Decline Bent Knee Reverse Crunch",1515:"Dumbbell Alternating Side Lunge",1518:"Right Dumbbell Lunge",1520:"Right Dumbbell Single Leg Deadlift",1522:"Dumbbell Punch Crunch",1524:"Cable Straight Leg V-Support",1526:"Supine Left Hamstring Dynamic Stretch",1527:"Right Bent Knee Crunch",1528:"Inchworm with Back Extension",1533:"Right Forward Lunge",1535:"Supine Partial V-Up",1537:"Bench Straight Leg Crunch",1538:"Left Dumbbell Lunge",1539:"Barbell Bulgarian Split Squat",1543:"Barbell Plate Sicilian Crunch",1549:"Reverse Plank Alternating Foot Touch",1551:"Reverse Plank Bicycle",1555:"Crunch with Left Knee to Foot Touch",1562:"Alternating Lunge with Twist",1565:"Dumbbell Diagonal Reverse Lunge",1566:"Left Bent Knee Crunch",1567:"Barbell Plate Plank Crunch",1569:"Simple Barbell Plate Russian Twist",1571:"Resistance Band Box Squat",1572:"Plank Bent Knee Reverse Crunch",1575:"Resistance Band Behind the Neck Squat",1577:"Supine Stability Ball Leg Curl",1580:"Dumbbell Goblet Squat Side Press",1581:"Simple Dumbbell Russian Twist",1582:"Dumbbell Overhead Forward Alternating Lunge",1584:"Dumbbell Windmill Crunch",1585:"Cable 90° Crunch",1590:"Stability Ball Straight Leg Reverse Crunch",1594:"Right Leg V-Up",1599:"BOSU Ball Seated Ab Crunch",1601:"Stability Ball Two-Part Leg Curl",1603:"Leopard Crawl",1604:"Dumbbell Underhand Lateral Raise",1605:"Dumbbell Squat and Drive",1608:"Dumbbell Flip Squat",1609:"Crab Twist",1611:"Left V-Up",1612:"Left Forward Lunge",1613:"Forward and Backward Bear Crawl",1615:"Dumbbell Walking Lunge",1616:"Right Single Leg V-Up",1618:"Stability Ball Straight Arm Plank",1620:"Dumbbell Side Lunge with Touchdown",1621:"Decline Diagonal Crunch",1625:"Fast Half Squat",1627:"Suspension Trainer Plank",1630:"Fast Plank Rotation Kick",1632:"High Frequency Running in Place",1634:"Landmine Romanian Deadlift",1636:"Barbell Good Morning",1638:"Dumbbell Squat and Twist Punch",1641:"Right Half Side Lunge",1643:"Left Half Side Lunge",1644:"Kneeling Stability Ball Saw Plank",1647:"Right Single Leg Half Squat Tap",1648:"Kneeling Stability Ball Push-Pull",1650:"Stability Ball Right Leg Deadlift",1653:"Stability Ball Squat",1655:"Stability Ball Left Leg Deadlift",1656:"BOSU Ball Touchdown Squat",1658:"Feet Together Reverse Step",1660:"Dumbbell Squat with Forward Punch",1662:"Ab Wheel Side Step",1664:"V-Position Stability Ball Static Support",1667:"Left Pulse Lunge",1668:"Right Eccentric Explosive Lunge",1669:"Right Lunge Arm Swing",1671:"Left Half Lunge Hold",1672:"Right Half Lunge Hold",1674:"Right Lunge Hold",1675:"Left Lunge Side Arm Raise",1676:"Right Lunge Side Arm Raise",1677:"Rapid Jumps in Place",1678:"High Crab Walk",1679:"Right Single Leg Hop",1680:"Prone Stability Ball Z-Crunch",1681:"Left Bottom Pause Side Lunge",1682:"Bent Knee Stability Ball Reverse Crunch",1684:"Right Stability Ball Diagonal Crunch",1685:"Left Side Lunge Hold",1686:"Prone Stability Ball Jack Plank",1687:"Left Half Side Lunge Hold",1689:"Left Single Leg Hop",1692:"Left Resistance Band Reverse Lunge",1695:"Left Resistance Band Reverse Lunge Press",1696:"Prone Alternating Step and Twist",1697:"Right Resistance Band Reverse Lunge Press",1698:"Dumbbell Reverse Lunge Press",1700:"BOSU Ball Left Lunge",1701:"Right-Hand Stability Ball Plank",1702:"BOSU Ball Right Side Plank with Arm Raise",1703:"BOSU Ball Right Lunge",1704:"Dumbbell Reverse Step Stretch",1705:"Suspension Trainer Prone Knee Tuck",1706:"Dumbbell Lunge Swing",1707:"Cable Knee Tuck V-Support",1709:"Supine Stability Ball Twist",1710:"Alternating Side Knee Touch",1711:"Right High Lunge Switch Jump",1712:"Ski Swing",1713:"Right Single Leg Squat Hold",1715:"Left High Lunge Switch Jump",1716:"Left Stability Ball Low Woodchop",1717:"Right Stability Ball Low Woodchop",1721:"Four-Point Front and Back Slide",1722:"Low Crab Walk",1723:"Bear Squat",1724:"Reverse Bent Knee Crunch Tap",1726:"Left Stability Ball Diagonal Crunch",1727:"Standing Stability Ball Saw Plank",1728:"Alternating Stability Ball Diagonal Crunch",1729:"Stability Ball Twist Crunch with Bent Knees",1730:"Supine Stability Ball T-Twist",1731:"Supine Resistance Band Woodchop",1732:"Right Dumbbell Turkish Get-Up",1733:"Left Dumbbell Turkish Get-Up",1734:"Crawling Twist",1739:"Left-Hand Stability Ball Plank",1741:"Dumbbell V-Support Twist",1742:"BOSU Ball Right Side Plank",1746:"BOSU Ball Left Side Plank",1747:"Suspension Trainer Left Side Plank",1748:"Suspension Trainer Right Side Plank",1749:"Dumbbell Plank Twist",1750:"Peak Contraction Sicilian Crunch",1751:"Standing Dumbbell Press",1754:"Bodyweight Press",1759:"Overhead Dumbbell Lateral Raise",1760:"Standing Deep Shoulder Activation",1761:"Standing Barbell Press",1762:"Bodyweight Arnold Press",1763:"Dumbbell Pronated Front Raise",1764:"Bodyweight Front Raise",1765:"Dumbbell Neutral Grip Press",1766:"Bodyweight Cuban Press",1767:"Single Dumbbell Front Raise",1768:"Seated Shoulder External Rotation",1769:"Static Lateral Raise",1770:"Seated Barbell Neck Press",1771:"Right Dumbbell Lateral Raise",1772:"Dumbbell Hammer Alternating Front Raise",1773:"Bent Over Dumbbell Reverse Fly",1774:"Dumbbell Compound Press",1775:"Arnold Press",1776:"Seated Bent Over Dumbbell Reverse Fly",1777:"Standing Dumbbell Neutral Grip Press",1778:"Dumbbell Shoulder External Rotation",1779:"Deep Shoulder Activation",1780:"Resistance Band Upright Row",1781:"Dumbbell Front Raise Curl Combo",1782:"Bodyweight Front Raise Hold",1783:"Dumbbell Pronated Alternating Front Raise",1784:"Barbell Wide Grip Upright Row",1785:"Dumbbell Front Raise Abduction",1786:"Barbell Front Raise",1787:"Self-Hug Shoulder Stretch",1789:"Seated Reverse Grip Overhead Press",1790:"Resistance Band Upright Row",1791:"Prone Dumbbell Reverse Fly",1792:"Dumbbell Lateral Raise Adduction",1793:"Dumbbell Neutral Grip Alternating Front Raise",1794:"Seated Dumbbell Press",1795:"Barbell Behind the Neck Press",1796:"Dumbbell Hammer Compound Press",1797:"Resistance Band Lateral Raise",1798:"Seated Resistance Band Chest Expansion",1799:"Resistance Band Press",1800:"Resistance Band T-Stretch",1801:"Resistance Band Shoulder External Rotation",1802:"Bodyweight Overhead Lateral Raise",1803:"Dumbbell Cuban Press",1804:"Bent Over Dumbbell Wide Row",1805:"Dumbbell Combo Raise",1806:"Seated Alternating Dumbbell Press",1808:"Left Dumbbell Lateral Raise",1809:"Bent Over Head Hug Reverse Elbow Raise",1815:"Seated Resistance Band Press",1817:"Dumbbell Compound Raise",1820:"Bent Over Stability Ball Row",1825:"Resistance Band Arm Openings",1833:"Resistance Band Combo Raise",1834:"Left Resistance Band Lateral Raise",1835:"Right Resistance Band Lateral Raise",1837:"Scott Press",1838:"Pendlay Row",1840:"Side-Lying Left Dumbbell Lateral Raise",1841:"Side-Lying Right Dumbbell Lateral Raise",1842:"Head Resting Bent Over Reverse Fly",1846:"Seated Resistance Band Lateral Raise",1848:"Resistance Band Combo Press",1849:"Seated Alternating Resistance Band Press",1853:"Resistance Band Alternating Press",1856:"Standing Dumbbell Steering Wheel",1858:"Upper Back Stretch",1860:"Prone Right Dumbbell Row",1861:"Prone Left Dumbbell Row",1864:"Bent Over Pronated Barbell Row",1865:"Shrug and Depress",1866:"Breaststroke Arm Pull",1868:"Prone YW-Stretch",1869:"Prone Y-Stretch",1870:"Bent Over Right Dumbbell Row",1871:"Bent Over W-Stretch",1872:"Bent Over Left Dumbbell Row",1873:"Prone A-Stretch",1874:"Wall Arm Pull",1875:"Bent Over Hammer Dumbbell Row",1876:"Standing Resistance Band Row",1877:"Seated Resistance Band High Pull",1878:"Prone YTW-Stretch",1879:"Bent Over Dumbbell Row with Tricep Extension",1880:"Bent Over Y-Stretch",1881:"Supine Barbell Underhand Row",1882:"Prone W-Stretch",1884:"Resistance Band Straight Arm Pulldown",1886:"Scapular Protraction and Retraction",1889:"Supine Arm Pull",1890:"Bent Over T-Stretch",1891:"Prone I-Stretch",1893:"Resistance Band High Row",1895:"Seated Cable Underhand Row",1896:"Scapular Retraction Pull-Up",1897:"Kneeling Right Back Stretch",1898:"Bent Over A-Stretch",1899:"Prone YA-Stretch",1900:"Bent Over I-Stretch",1903:"Standing Left Back Stretch",1904:"Kneeling Left Back Stretch",1905:"Single Arm Resistance Band High Pull",1906:"Standing Right Back Stretch",1907:"Bent Over Upper Back Stretch",1908:"Seated Straight Leg Cable Row",1909:"Foam Roller Upper Back Release",1910:"Bent Over Dumbbell External Rotation Row",1913:"Bent Over Dumbbell Kickback",1914:"Seated Upper Back Stretch",1916:"Prone Resistance Band Row",1917:"Seated Resistance Band High Row",1918:"Seated Alternating Resistance Band Pulldown",1919:"Bent Over Left Single Arm Resistance Band Row",1920:"Seated Left Resistance Band High Pulldown",1921:"Chair Supported Shoulder Shrug",1924:"Bent Over Right Single Arm Resistance Band Row",1925:"Seated Reverse Grip Straight Leg Cable Row",1926:"Standing Resistance Band YW-Stretch",1927:"Resistance Band Behind the Neck Stretch",1928:"Prone YT-Stretch",1929:"Seated Right Resistance Band High Pulldown",1930:"Behind the Back Barbell Shrug",1931:"Seated Reverse Grip Bent Leg Cable Row",1935:"Standing Left Resistance Band Row",1936:"Bent Over Left Resistance Band Row",1939:"Incline Bent Over Dumbbell Row",1942:"Standing Right Resistance Band Row",1947:"T-Bar Row",1948:"Incline Prone Dumbbell Shrug",1949:"Single Arm Landmine Row",1950:"Bent Over Wide Grip Barbell Row",1963:"Half Push-Up",1964:"Resistance Band Push-Up",1965:"Resistance Band Chest Fly",1966:"Resistance Band Chest Press",1967:"Split Stance Push-Up",1968:"Dive Bomber Push-Up",1969:"Overhead Barbell Squat",1970:"Archer Push-Up",1971:"Kneeling Half Push-Up",1972:"Kneeling Explosive Push-Up",1973:"Kneeling Alternating Push-Up",1974:"Kneeling Jack Push-Up",1975:"Kneeling Sliding Push-Up",1976:"Kneeling Release Push-Up",1977:"Hack Squat",1978:"Wall Push-Up",1979:"Chest Expansion",1980:"Flat Bench Press",1982:"Incline Hammer Dumbbell Fly",1983:"Incline Reverse Grip Dumbbell Bench Press",1984:"Incline Push-Up",1986:"Release Push-Up",1987:"Cross Support",1988:"Dip",1989:"Horizontal Chest Fly",1990:"Swan Chest Fly",1991:"Small Dumbbell Hip Bridge Support Fly",1992:"Decline Push-Up",1993:"Scorpion Demonstration",1994:"Chest Stretch",1995:"Supine Dumbbell Pullover",1996:"Side Push-Up",1997:"Dumbbell Floor Fly",1998:"Dumbbell Floor Press",1999:"Dumbbell Floor Diamond Press",2000:"Dumbbell Hip Bridge Press",2001:"Dumbbell Diamond Press",2002:"Narrow Grip Push-Up",2003:"Spider Push-Up",2004:"Diamond Push-Up",2005:"Seated Chest Press Machine",10022:"High Knees",10046:"Butt Kicks",10049:"A marches",10050:"B marches",10051:"C skips",10052:"Single Leg Knee Drive (Left)",10053:"Single Leg Knee Drive (Right)",10055:"Alternating Lunge Jump",10058:"Walking Knee Hugs",10061:"The Greatest Stretch",10064:"Skater Jumps",10067:"Ankle Circles",10068:"Speed Endurance Running",10069:"Easy Run",10070:"Jogging",10071:"Easy Walking",10074:"Pick-Up",10075:"5km Event",10076:"10km Event",10077:"15km Event",10078:"Half Marathon"};

// Only import strength data for workouts on or after this date.
const STRENGTH_IMPORT_START = new Date("2026-10-08T00:00:00.000Z");

interface StrengthMovementScores {
  stability: number;
  consistency: number;
  speedDecay: number;
  rhythm: number;
  continuity: number;
}

interface StrengthSetDecoded {
  idx: number;
  startOffsetSec: number;
  durationSec: number;
  exerciseCode: number;
  scores: StrengthMovementScores | null;
}

// Parse strengthAssess JSON string → per-set timing + IMU movement scores.
// eq[] positional order confirmed by user against Zepp app radar 2026-10-08:
// clockwise from top = Stability, Consistency, Speed Decay, Rhythm, Continuity
// → eq[0]=stability, eq[1]=consistency, eq[2]=speedDecay, eq[3]=rhythm, eq[4]=continuity.
// Sentinel value = sce: -1 on any eq item.
function parseStrengthAssess(assessStr: unknown): Array<{
  idx: number; startMs: number; scores: StrengthMovementScores | null;
}> {
  if (typeof assessStr !== "string" || !assessStr) return [];
  try {
    // deno-lint-ignore no-explicit-any
    return (JSON.parse(assessStr) as any[]).map(item => {
      const eq = Array.isArray(item.eq) ? item.eq as Array<Record<string, number>> : [];
      const sentinel = !eq.length || eq[0]?.sce === -1;
      return {
        idx: Number(item.idx),
        startMs: Number(item.time),
        scores: sentinel ? null : {
          stability: eq[0]?.sce ?? -1,
          consistency: eq[1]?.sce ?? -1,
          speedDecay: eq[2]?.sce ?? -1,
          rhythm: eq[3]?.sce ?? -1,
          continuity: eq[4]?.sce ?? -1,
        },
      };
    });
  } catch {
    return [];
  }
}

// Minetti's cost curve is a running model -- cycling/strength get no GAP/EF.
const RUN_SPORT_TYPES = new Set(["1", "7"]);

function decodeRoute(raw: unknown): RoutePoint[] {
  const entries = splitEntries(raw);
  const out: RoutePoint[] = [];
  let lat = 0, lon = 0;
  entries.forEach((entry, i) => {
    const parts = entry.split(",");
    const a = Number(parts[0]), b = Number(parts[1]);
    if (!Number.isFinite(a) || !Number.isFinite(b)) return;
    if (i === 0) { lat = a / 1e8; lon = b / 1e8; }
    else { lat += a / 1e8; lon += b / 1e8; }
    out.push({ offsetSeconds: i, lat, lon });
  });
  return out;
}

function decodeWorkoutDetail(detailRawBody: unknown, summary: WorkoutSummaryFields, sportType: string): DecodedSeries | null {
  if (!detailRawBody || typeof detailRawBody !== "object") return null;
  const data = (detailRawBody as Record<string, unknown>).data;
  if (!data || typeof data !== "object") return null;
  const d = data as Record<string, unknown>;
  const { cadenceSpm, verticalStrideRatioPct } = decodeCadenceAndVerticalRatio(d.gait);
  const routePoints = sportType !== "52" ? decodeRoute(d.longitude_latitude) : [];
  const series = {
    heartRate: decodeHeartRate(d.heart_rate),
    speedKmh: decodeSpeedKmh(d.speed),
    altitudeM: decodeAltitude(d.altitude),
    distanceKm: decodeDistanceKm(d.longitude_latitude),
    cadenceSpm,
    verticalStrideRatioPct,
    routePoints,
  };
  const effort = RUN_SPORT_TYPES.has(sportType)
    ? computeEffort({ ...series, lactateThresholdHrBpm: summary.lactateThresholdHrBpm })
    : null;
  return { ...series, summary: { ...summary, ...(effort ?? {}) } };
}

// DAV-112/123: a workout's real per-point pace/power/GPS lives behind a *second*
// call, not the list endpoint below -- confirmed independently in two reference
// implementations (zepp-health-cli, ZeppBridge's zepp.rs). See
// docs/zepp-integration/02-token-capture-and-data-extraction.md §2.
const WORKOUT_DETAIL_METRIC = "sport_history_detail";

interface WorkoutRef {
  trackId: string;
  source: string;
  // ISO timestamps, derived from the list entry's own end_time/run_time --
  // empty when those fields are missing/unparseable, in which case the
  // caller stores the raw detail payload but skips reconciliation (no
  // reliable time to match or insert with).
  startTimeIso: string;
  endTimeIso: string;
  sportType: string;
  // Workout-level averages -- these live in the *summary* entry (this list
  // response), not detail.json, so they're carried through here rather than
  // decoded from the detail payload. null when the summary entry doesn't
  // have them (e.g. a non-running sport, or a device that doesn't report
  // dynamics). See docs/zepp-integration/03-workout-detail-field-decode.md.
  avgCadenceSpm: number | null;
  maxCadenceSpm: number | null;
  avgStrideLengthCm: number | null;
  avgGroundContactMs: number | null;
  avgVerticalStrideRatioPct: number | null;
  // Zepp recomputes this per qualifying run, not from one dedicated test --
  // "current" is whichever run was synced most recently, not a fixed event.
  lactateThresholdHrBpm: number | null;
  lactateThresholdPaceSecPerKm: number | null;
  // Aggregate fields from the sport_history summary entry -- not in detail.json.
  avgHrBpm: number | null;
  maxHrBpm: number | null;
  calorieKcal: number | null;
  altitudeAscendM: number | null;
  avgPaceSecPerKm: number | null;
  distanceSummaryM: number | null;
  // Real Unix epoch ms for the workout start (endSec - runSec). Used to
  // compute per-set startOffsetSec for strength data (strengthAssess.time is
  // also real epoch ms). Distinct from startTimeIso which is local-labeled-UTC.
  realStartMs: number;
}

function numOrNull(v: unknown): number | null {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

// DAV-115 real bug, found 2026-09-25: exercise_sessions.start_time for
// Health-Connect-sourced rows is the device's *local wall-clock* reading
// written with a UTC-looking suffix, not a real UTC instant (see
// HealthConnectExerciseSyncRepository's own comment on this convention,
// and SessionDetailScreen.kt's "reconstruct the real instant via the
// device's zone" reversal of it). Storing a genuine UTC instant for Zepp
// rows in that same column broke every match by exactly the local UTC
// offset (8h for this Taiwan account) -- confirmed against a real pair:
// a Health-Connect ride at "2026-09-05 08:54:24+00" and the same workout's
// Zepp entry landing at "2026-09-05 00:54:24+00", 8h apart, both really the
// same moment. Fixed by reproducing the same "local time, UTC-labeled"
// value Zepp's own summary already tells us the local zone for
// (`syncedTimezone`, e.g. "Asia/Taipei") -- Taipei as the fallback since
// this is a single-user, Taiwan-based account, not a guess made blind.
const FALLBACK_TIMEZONE = "Asia/Taipei";

function toLocalLabeledUtcIso(realUtcMs: number, timeZone: string): string {
  const dtf = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
  const parts = dtf.formatToParts(new Date(realUtcMs));
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? "00";
  const hour = get("hour") === "24" ? "00" : get("hour");
  return `${get("year")}-${get("month")}-${get("day")}T${hour}:${get("minute")}:${get("second")}.000Z`;
}

// Confirmed 2026-09-25 against a real account (curl, not guessed): the envelope
// is `{code, message, data: {next, summary: [...]}}`. Each `summary` entry has
// numeric `trackid` and a string `source` (e.g. "run.10289411.huami.com") --
// exactly the pair `detail.json` needs -- plus `end_time`/`run_time` (both unix
// seconds, as strings), used below to derive each workout's real start/end for
// reconciliation. Kept the bare-array/`items` fallbacks too since they're free
// and harmless if a different sport/region ever wraps it differently; `[]` on a
// wrong guess still can't take down the extraction run.
function extractWorkoutRefs(historyResponse: unknown): WorkoutRef[] {
  if (!historyResponse || typeof historyResponse !== "object") return [];
  const obj = historyResponse as Record<string, unknown>;
  const data = obj.data as Record<string, unknown> | undefined;

  const entries: unknown[] = Array.isArray(historyResponse)
    ? historyResponse
    : Array.isArray(obj.items)
    ? obj.items as unknown[]
    : Array.isArray(data?.summary)
    ? data!.summary as unknown[]
    : Array.isArray(obj.data)
    ? obj.data as unknown[]
    : Array.isArray(data?.items)
    ? data!.items as unknown[]
    : [];

  const refs: WorkoutRef[] = [];
  for (const entry of entries) {
    if (!entry || typeof entry !== "object") continue;
    const e = entry as Record<string, unknown>;
    const rawId = e.trackid ?? e.trackId ?? e.track_id;
    if (rawId === undefined || rawId === null || rawId === "") continue;

    const endSec = Number(e.end_time);
    const runSec = Number(e.run_time);
    const hasEnd = Number.isFinite(endSec) && endSec > 0;
    const hasRun = Number.isFinite(runSec) && runSec >= 0;
    const tz = typeof e.syncedTimezone === "string" && e.syncedTimezone ? e.syncedTimezone : FALLBACK_TIMEZONE;
    const endTimeIso = hasEnd ? toLocalLabeledUtcIso(endSec * 1000, tz) : "";
    const startTimeIso = hasEnd && hasRun ? toLocalLabeledUtcIso((endSec - runSec) * 1000, tz) : "";

    refs.push({
      trackId: String(rawId),
      source: String(e.source ?? ""),
      startTimeIso,
      endTimeIso,
      sportType: String(e.type ?? ""),
      avgCadenceSpm: numOrNull(e.avg_frequency),
      maxCadenceSpm: numOrNull(e.max_frequency),
      avgStrideLengthCm: numOrNull(e.avg_stride_length),
      avgGroundContactMs: numOrNull(e.averageGct),
      avgVerticalStrideRatioPct: numOrNull(e.avgVertStrideRatio) !== null
        ? (e.avgVertStrideRatio as number) / 10
        : null,
      lactateThresholdHrBpm: numOrNull(e.lactateThresholdHr),
      lactateThresholdPaceSecPerKm: numOrNull(e.lactateThresholdPace),
      avgHrBpm: numOrNull(e.avg_heart_rate),
      maxHrBpm: numOrNull(e.max_heart_rate),
      calorieKcal: numOrNull(e.calorie),
      altitudeAscendM: numOrNull(e.altitude_ascend),
      avgPaceSecPerKm: numOrNull(e.avg_pace),
      // Zepp's summary carries `dis` / `highPrecisionDistance` (metres); there is no `distance` key (workoutDistance.ts).
      distanceSummaryM: summaryDistanceM(e),
      realStartMs: hasEnd && hasRun ? (endSec - runSec) * 1000 : 0,
    });
  }
  return refs;
}

// Metric definitions: which Zepp mobile API endpoints to hit and how to
// build the URL for a given date. The user_id placeholder is filled at
// runtime from ZEPP_USER_ID.
interface MetricDef {
  metric: string;
  endpoint: (userId: string, date: string) => string;
}

const METRIC_DEFS: MetricDef[] = [
  {
    // v9: band_data is now a single "detail" query per day (matching
    // ZeppBridge's fetch_band_data), returning heart_rate + hrv + sleep +
    // spo2 in one response. The v1 endpoint with query_type=detail,
    // from_date/to_date, and byteLength=8 is the shape the mobile app
    // actually uses -- /v2/ with query_type=summary was never real.
    metric: "band_data",
    endpoint: (uid, date) =>
      `/v1/data/band_data.json?query_type=detail&device_type=0&userid=${uid}&from_date=${date}&to_date=${date}&byteLength=8`,
  },
  {
    // Stress: a user-events timeline, not a band_data field. `from`/`to`
    // are epoch-ms bounds; this metric's fetchAndStore call already runs
    // once per requested day (the loop below), so each call's window is
    // just that one UTC day.
    metric: "stress",
    endpoint: (uid, date) => {
      const startMs = new Date(`${date}T00:00:00Z`).getTime();
      const endMs = startMs + 86400000 - 1;
      return `/users/${uid}/events?eventType=all_day_stress&from=${startMs}&to=${endMs}&limit=2000&reverse=0&userId=${uid}`;
    },
  },
  {
    metric: "training_load",
    endpoint: (uid, _date) =>
      `/v2/watch/users/${uid}/WatchSportStatistics/SPORT_LOAD`,
  },
  {
    metric: "vo2max",
    endpoint: (uid, _date) =>
      `/v2/watch/users/${uid}/WatchSportStatistics/VO2_MAX`,
  },
  {
    metric: "sport_history",
    // Confirmed real param shape (zepp-health-cli, ZeppBridge): userid +
    // startTrackId/stopTrackId cursor bounds, not a `date` filter -- the
    // original guess here was never tested against a live token. Use the
    // requested day's UTC boundaries as the cursor window, matching
    // zepp-health-cli's own single-day usage.
    endpoint: (uid, date) => {
      const startOfDay = Math.floor(new Date(`${date}T00:00:00Z`).getTime() / 1000);
      const startOfNextDay = startOfDay + 86400;
      return `/v1/sport/run/history.json?userid=${uid}&startTrackId=${startOfDay}` +
        `&stopTrackId=${startOfNextDay}&need_sub_data=1&type=`;
    },
  },
];

// v10: sleep stage modes from the Zepp band_data summary.
// Confirmed against a real 2026-10-01 response and ZeppBridge's conventions:
// 4=light, 5=deep, 7=awake, 8=REM. Only AWAKE is used in the decoder
// (to subtract awake time from total time-in-bed); the summary's own
// dp/lt/dt fields give deep/light/REM minutes pre-aggregated.
const SLEEP_MODE_AWAKE = 7;

interface BandDataDecoded {
  sleepHours: number | null;
  sleepScore: number | null;
  bedtimeIso: string | null;
  wakeTimeIso: string | null;
  deepMin: number | null;
  remMin: number | null;
  lightMin: number | null;
  rhr: number | null;
  steps: number | null;
  hrTimeseries: HrMinutePoint[] | null;
}

function decodeBandDataSummary(rawBody: unknown): BandDataDecoded | null {
  if (!rawBody || typeof rawBody !== "object") return null;
  const body = rawBody as Record<string, unknown>;
  if (body.code !== 1) return null;
  const dataArr = body.data;
  if (!Array.isArray(dataArr) || dataArr.length === 0) return null;
  const entry = dataArr[0] as Record<string, unknown>;
  const summaryB64 = entry.summary;
  if (typeof summaryB64 !== "string" || !summaryB64) return null;

  let summary: Record<string, unknown>;
  try {
    const decoded = atob(summaryB64);
    summary = JSON.parse(decoded);
  } catch {
    return null;
  }

  const slp = summary.slp as Record<string, unknown> | undefined;
  let sleepHours: number | null = null;
  let sleepScore: number | null = null;
  let bedtimeIso: string | null = null;
  let wakeTimeIso: string | null = null;
  let deepMin: number | null = null;
  let remMin: number | null = null;
  let lightMin: number | null = null;

  if (slp) {
    const st = Number(slp.st);
    const ed = Number(slp.ed);
    if (Number.isFinite(st) && Number.isFinite(ed) && ed > st) {
      const totalSeconds = ed - st;
      // Subtract awake stage minutes from total time-in-bed, matching
      // HealthConnectDailySyncRepository's own awake subtraction.
      const stages = slp.stage as Array<{ start: number; stop: number; mode: number }> | undefined;
      let awakeMin = 0;
      if (Array.isArray(stages)) {
        for (const s of stages) {
          if (s.mode === SLEEP_MODE_AWAKE) awakeMin += s.stop - s.start;
        }
      }
      sleepHours = (totalSeconds / 3600) - (awakeMin / 60);
      if (sleepHours < 0) sleepHours = 0;
      bedtimeIso = new Date(st * 1000).toISOString();
      wakeTimeIso = new Date(ed * 1000).toISOString();
    }
    const dp = Number(slp.dp);
    if (Number.isFinite(dp) && dp >= 0) deepMin = dp;
    const dt = Number(slp.dt);
    if (Number.isFinite(dt) && dt >= 0) remMin = dt;
    const lt = Number(slp.lt);
    if (Number.isFinite(lt) && lt >= 0) lightMin = lt;
    const ss = Number(slp.ss);
    if (Number.isFinite(ss) && ss > 0) sleepScore = ss;
  }

  const rhrVal = Number(summary.slp && (summary.slp as Record<string, unknown>).rhr);
  const rhr = Number.isFinite(rhrVal) && rhrVal > 0 ? rhrVal : null;

  const stp = summary.stp as Record<string, unknown> | undefined;
  const stepsVal = stp ? Number(stp.ttl) : NaN;
  const steps = Number.isFinite(stepsVal) && stepsVal >= 0 ? stepsVal : null;

  // Minute heart rate without the band's no-reading markers (minuteHr.ts).
  const hrTimeseries = decodeMinuteHr(entry.data_hr);

  return { sleepHours, sleepScore, bedtimeIso, wakeTimeIso, deepMin, remMin, lightMin, rhr, steps, hrTimeseries };
}

function parseDateRange(
  from: string,
  to: string,
): { dates: string[]; error?: string } {
  const start = new Date(from + "T00:00:00Z");
  const end = new Date(to + "T00:00:00Z");
  if (isNaN(start.getTime()) || isNaN(end.getTime())) {
    return { dates: [], error: "Invalid date format. Use YYYY-MM-DD." };
  }
  if (end < start) {
    return { dates: [], error: "'to' must be >= 'from'." };
  }
  const diffDays =
    (end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24) + 1;
  if (diffDays > 31) {
    return { dates: [], error: "Max 31 days per extraction." };
  }
  const dates: string[] = [];
  const cursor = new Date(start);
  while (cursor <= end) {
    dates.push(cursor.toISOString().slice(0, 10));
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return { dates };
}

// The extraction itself, for the signed-in app (the handler below) and for the history pull (runHistoryPull):
// the requested metrics for each requested day, decoded and written for `user`. `supabase` is the caller's client, or
// the service role client for the scheduler, which is why every write names user_id itself.
interface ZeppConfig { token: string; userId: string; host: string }

function zeppConfig(): ZeppConfig | null {
  const token = Deno.env.get("ZEPP_APP_TOKEN");
  const userId = Deno.env.get("ZEPP_USER_ID");
  const host = Deno.env.get("ZEPP_API_HOST");
  return token && userId && host ? { token, userId, host } : null;
}

interface ExtractOutcome {
  summary: { total: number; succeeded: number; failed: number };
  found: { workouts: number; bandDays: number };
  authFailed: boolean;
  results: Array<{ metric: string; date: string; status: number; stored: boolean; error?: string }>;
}

async function extract(
  // deno-lint-ignore no-explicit-any
  supabase: any,
  user: { id: string },
  dates: string[],
  defs: MetricDef[],
  zepp: ZeppConfig,
  options: { updateSyncState: boolean },
): Promise<ExtractOutcome> {
  const zeppToken = zepp.token;
  const zeppUserId = zepp.userId;
  const zeppHost = zepp.host;

  // --- Extract ---
  const results: Array<{
    metric: string;
    date: string;
    status: number;
    stored: boolean;
    error?: string;
  }> = [];

  // track_id defaults to '' (not null) for every non-detail metric -- a nullable
  // track_id would break upsert idempotency, since Postgres unique constraints
  // never treat two NULLs as a conflict (see the schema comment/migration).
  async function fetchAndStore(
    endpoint: string,
    metric: string,
    date: string,
    trackId: string,
  ): Promise<{ statusCode: number; rawBody: unknown; stored: boolean; error?: string }> {
    // v8: append the same "r" cache-buster every zepp-health-cli request
    // carries (a random UUID, not a validated value -- just present).
    const sep = endpoint.includes("?") ? "&" : "?";
    const fetchUrl = `https://${zeppHost}${endpoint}${sep}r=${crypto.randomUUID().toUpperCase()}`;
    let statusCode: number;
    let rawBody: unknown = null;

    try {
      const res = await fetch(fetchUrl, {
        headers: {
          apptoken: zeppToken!,
          "Content-Type": "application/json",
          appname: "com.huami.midong",
          appplatform: "ios_phone",
          v: "2.0",
          vn: "10.2.5",
          cv: "1722_10.2.5",
          vb: "202604132257",
          "user-agent": "Zepp/10.2.5 (iPhone; iOS 26.3.1; Scale/3.00)",
          lang: "en",
          country: "",
          timezone: "UTC",
        },
      });
      statusCode = res.status;
      const text = await res.text();
      try {
        rawBody = JSON.parse(text);
      } catch {
        rawBody = { _raw_text: text };
      }
    } catch (e) {
      statusCode = 0;
      rawBody = { _fetch_error: String(e) };
    }

    const { error: upsertError } = await supabase
      .from("zepp_raw_extracts")
      .upsert(
        {
          user_id: user.id,
          metric,
          query_date: date,
          track_id: trackId,
          api_host: zeppHost,
          endpoint,
          status_code: statusCode,
          raw_body: rawBody,
          fetched_at: new Date().toISOString(),
          extractor_version: EXTRACTOR_VERSION,
        },
        { onConflict: "user_id,metric,query_date,track_id" },
      );

    return { statusCode, rawBody, stored: !upsertError, error: upsertError?.message };
  }

  // DAV-115: match a Zepp workout to an existing Health-Connect-sourced
  // exercise_sessions row rather than duplicating it; insert a new
  // source='zepp' row only when nothing matches. Matching (sessionMatch.ts):
  // a session starting within 2 minutes, else one of the same type
  // overlapping the workout by at least half of the longer span. Also tracks
  // the highest track_id seen, for zepp_sync_state below.
  const RECONCILE_TOLERANCE_MS = START_TOLERANCE_MS;
  const CANDIDATE_LOOKBACK_MS = 6 * 60 * 60 * 1000;
  let maxTrackIdSeen = 0;

  type SessionRow = Record<string, unknown> & { id: number; type: string | null; source: string | null };

  // Sessions that could be this workout: starting up to 6 h before it and no later than 2 min after its end, each with
  // the Zepp track it is already linked to (if any).
  async function candidateSessions(span: Span, columns: string): Promise<SessionRow[]> {
    const { data, error } = await supabase
      .from("exercise_sessions")
      .select(`${columns},zepp_workout_detail(zepp_track_id)`)
      .eq("user_id", user.id)
      .gte("start_time", new Date(span.startMs - CANDIDATE_LOOKBACK_MS).toISOString())
      .lte("start_time", new Date(span.endMs + RECONCILE_TOLERANCE_MS).toISOString());
    if (error) throw new Error(`exercise_sessions candidate read failed: ${error.message}`);
    return (data ?? []) as SessionRow[];
  }

  function asCandidate(row: SessionRow): SessionCandidate | null {
    const span = spanOf(row);
    if (!span) return null;
    const links = row.zepp_workout_detail as { zepp_track_id: unknown }[] | null | undefined;
    const linked = Array.isArray(links) && links.length > 0 ? String(links[0].zepp_track_id) : null;
    return { id: row.id, type: row.type, startMs: span.startMs, endMs: span.endMs, linkedTrackId: linked };
  }

  async function reconcileWorkout(
    ref: WorkoutRef,
    detailRawBody: unknown,
  ): Promise<void> {
    if (!ref.startTimeIso || !ref.endTimeIso) return; // no reliable time to reconcile with

    const startMs = new Date(ref.startTimeIso).getTime();
    const zeppSpan = { startMs, endMs: new Date(ref.endTimeIso).getTime() };
    const rows = await candidateSessions(zeppSpan, "id,type,source,start_time,end_time,duration_min");
    const match = pickSessionMatch(
      { ...zeppSpan, type: exerciseTypeForZeppSport(ref.sportType) },
      rows.map(asCandidate).filter((c): c is SessionCandidate => c !== null),
      String(ref.trackId),
    );
    const matchedRow = match ? rows.find((r) => r.id === match.id) : undefined;

    let exerciseSessionId: number | null = matchedRow?.id ?? null;
    const matchedType: string = matchedRow?.type ?? exerciseTypeForZeppSport(ref.sportType);

    if (exerciseSessionId === null) {
      // Unmatched: this workout isn't in Health Connect yet. Insert minimally
      // -- aggregate fields (distance/calories/hr) are left for the decode
      // pass once detail.json's real field semantics are confirmed, rather
      // than guessing units now. Type comes from Zepp's sport code (DAV-268
      // has the full mapping; unknown codes land as 'other', never 'run').
      const { data: inserted, error: insertError } = await supabase
        .from("exercise_sessions")
        .insert({
          user_id: user.id,
          type: exerciseTypeForZeppSport(ref.sportType),
          source: "zepp",
          start_time: ref.startTimeIso,
          end_time: ref.endTimeIso,
          duration_min: Math.round((new Date(ref.endTimeIso).getTime() - startMs) / 60000),
        })
        .select("id")
        .single();
      // Surfacing this insert's error mattered in practice: it silently
      // failed once already (a since-fixed CHECK constraint didn't allow
      // source='zepp') and the swallowed error hid it -- DAV-115's own
      // acceptance criterion is "no silent data loss."
      if (insertError) throw new Error(`exercise_sessions insert failed: ${insertError.message}`);
      exerciseSessionId = inserted?.id ?? null;
    }

    const decoded = decodeWorkoutDetail(detailRawBody, {
      avgCadenceSpm: ref.avgCadenceSpm,
      maxCadenceSpm: ref.maxCadenceSpm,
      avgStrideLengthCm: ref.avgStrideLengthCm,
      avgGroundContactMs: ref.avgGroundContactMs,
      avgVerticalStrideRatioPct: ref.avgVerticalStrideRatioPct,
      lactateThresholdHrBpm: ref.lactateThresholdHrBpm,
      lactateThresholdPaceSecPerKm: ref.lactateThresholdPaceSecPerKm,
    }, ref.sportType);

    const { error: detailUpsertError } = await supabase.from("zepp_workout_detail").upsert(
      {
        user_id: user.id,
        exercise_session_id: exerciseSessionId,
        zepp_track_id: ref.trackId,
        zepp_source: ref.source,
        start_time: ref.startTimeIso,
        end_time: ref.endTimeIso,
        sport_type: ref.sportType,
        raw: detailRawBody,
        decoded,
        hr_histogram: hrHistogram(decoded?.heartRate),
        fetched_at: new Date().toISOString(),
      },
      { onConflict: "user_id,zepp_track_id" },
    );
    if (detailUpsertError) throw new Error(`zepp_workout_detail upsert failed: ${detailUpsertError.message}`);

    // DAV-274 real bug: exercise_sessions.distance_km (Health Connect's own
    // multi-source-summed figure, sometimes wrong by 2-3x -- see
    // domain/Training.kt's reconcileDistanceWithSpeed on the Android side)
    // is never corrected once a real Zepp GPS track exists for the same
    // session, matched or not. Zepp's decoded per-second distance is real
    // GPS ground truth (repeatedly cross-checked this session) -- write it
    // back outright, same "Zepp wins when present" principle
    // mergePreferZepp() already applies to the per-second series client-side.
    // Zepp's distance: the GPS track, else the summary's (a treadmill has no track). It also goes to any duplicate
    // row of the same session (same type, same start within the tolerance): the source app wrote some workouts to
    // Health Connect twice, and correcting one copy left the run list keeping the other (workoutDistance.ts).
    const zeppKm = zeppDistanceKm(decoded?.distanceKm?.at(-1)?.value, ref.distanceSummaryM);
    if (exerciseSessionId !== null && zeppKm !== null && takesDistance(matchedType)) {
      const { error: distanceUpdateError } = await supabase
        .from("exercise_sessions")
        .update({ distance_km: zeppKm })
        .eq("user_id", user.id)
        .eq("type", matchedType)
        .gte("start_time", new Date(startMs - RECONCILE_TOLERANCE_MS).toISOString())
        .lte("start_time", new Date(startMs + RECONCILE_TOLERANCE_MS).toISOString());
      if (distanceUpdateError) throw new Error(`exercise_sessions distance update failed: ${distanceUpdateError.message}`);
      // A session matched by overlap starts outside that 2-minute window.
      if (match?.kind === "overlap") {
        const { error: matchedDistanceError } = await supabase
          .from("exercise_sessions").update({ distance_km: zeppKm }).eq("id", exerciseSessionId);
        if (matchedDistanceError) throw new Error(`exercise_sessions distance update failed: ${matchedDistanceError.message}`);
      }
    }

    // v12: write Zepp-primary aggregates. Prefer smoothedAscentM (GPS-smoothed
    // by effort.ts) over the raw altitude_ascend from the summary. "Zepp wins
    // when > 0" -- skip the update if Zepp has zero/null to avoid clobbering
    // legitimate HC values. avg_speed_kmh is derived from avg_pace (s/km).
    if (exerciseSessionId !== null) {
      const smoothedM = decoded?.summary?.smoothedAscentM ?? 0;
      const elevationM = smoothedM > 0 ? smoothedM
        : (ref.altitudeAscendM ?? 0) > 0 ? ref.altitudeAscendM : null;

      const aggregates: Record<string, number> = {};
      if ((ref.avgHrBpm ?? 0) > 0)       aggregates.avg_hr = ref.avgHrBpm!;
      if ((ref.maxHrBpm ?? 0) > 0)       aggregates.max_hr = ref.maxHrBpm!;
      if ((ref.calorieKcal ?? 0) > 0)    aggregates.calories_active = ref.calorieKcal!;
      if (elevationM !== null)            aggregates.elevation_gain_m = elevationM;
      if ((ref.avgPaceSecPerKm ?? 0) > 0) aggregates.avg_speed_kmh = (1000 / ref.avgPaceSecPerKm!) * 3.6;
      // Matched by overlap rather than start: the stored session's times are another app's (a gym visit, a start pressed
      // early); the workout's own times keep its pace and load right.
      const timing: Record<string, unknown> = {};
      if (match?.kind === "overlap") {
        timing.start_time = ref.startTimeIso;
        timing.end_time = ref.endTimeIso;
        timing.duration_min = Math.round((zeppSpan.endMs - startMs) / 60000);
      }
      Object.assign(aggregates, timing);

      if (Object.keys(aggregates).length > 0) {
        const { error: aggUpdateError } = await supabase
          .from("exercise_sessions")
          .update(aggregates)
          .eq("id", exerciseSessionId);
        if (aggUpdateError) throw new Error(`exercise_sessions aggregate update failed: ${aggUpdateError.message}`);
      }
    }

    // v11: strength set import for workouts on/after 2026-10-08. Writes
    // decoded.strengthData (per-set timing + movement scores) and, if
    // exercise_sessions.details is currently null, populates it with exercises
    // and sets from the lap field using the Gadgetbridge exercise catalog.
    if (
      ref.sportType === "52" &&
      exerciseSessionId !== null &&
      new Date(ref.startTimeIso) >= STRENGTH_IMPORT_START
    ) {
      await reconcileStrengthData(
        exerciseSessionId, detailRawBody, ref.realStartMs,
        decoded as Record<string, unknown> | null,
      );
    }

    const n = Number(ref.trackId);
    if (Number.isFinite(n) && n > maxTrackIdSeen) maxTrackIdSeen = n;
  }

  async function reconcileStrengthData(
    exerciseSessionId: number,
    detailRawBody: unknown,
    realStartMs: number,
    existingDecoded: Record<string, unknown> | null,
  ): Promise<void> {
    if (!detailRawBody || typeof detailRawBody !== "object") return;
    const rawData = ((detailRawBody as Record<string, unknown>).data) as Record<string, unknown> | undefined;
    if (!rawData) return;

    // Done sets only (strengthImport.ts: status -1 or 0; 2 is a planned set that was skipped).
    const laps = doneSets(parseLapSets(rawData.lap));
    if (!laps.length) return;
    // Zepp's current version of the exercises, kept with the per-set data so the app can offer it when the person's
    // own edit and Zepp's version differ (strengthImport.ts).
    const zeppExercises = buildZeppExercises(laps, (code) => ZEPP_EXERCISE_NAMES[code]);

    const assess = parseStrengthAssess(rawData.strengthAssess as unknown);
    const assessByIdx = new Map(assess.map(a => [a.idx, a]));

    const strengthSets: StrengthSetDecoded[] = laps.map(lap => {
      const a = assessByIdx.get(lap.idx);
      const startOffsetSec = (a && realStartMs > 0)
        ? Math.max(0, Math.round((a.startMs - realStartMs) / 1000))
        : 0;
      return {
        idx: lap.idx,
        startOffsetSec,
        durationSec: lap.durationSec,
        exerciseCode: lap.exerciseCode,
        scores: a?.scores ?? null,
      };
    });

    // Patch decoded.strengthData onto the existing decoded object.
    const mergedDecoded = {
      ...(existingDecoded ?? {}),
      strengthData: { sets: strengthSets, exercises: zeppExercises, fingerprint: exercisesFingerprint(zeppExercises) },
    };
    const { error: decodedUpdateErr } = await supabase
      .from("zepp_workout_detail")
      .update({ decoded: mergedDecoded })
      .eq("exercise_session_id", exerciseSessionId);
    if (decodedUpdateErr) throw new Error(`zepp_workout_detail decoded patch failed: ${decodedUpdateErr.message}`);

    // Exercises Zepp wrote follow Zepp: a later correction in the Zepp app replaces them. Exercises changed in the app
    // are never overwritten here (the app offers Zepp's version instead). strengthImport.ts has the rules.
    const { data: sessionRow, error: readErr } = await supabase
      .from("exercise_sessions")
      .select("details")
      .eq("id", exerciseSessionId)
      .single();
    if (readErr) throw new Error(`exercise_sessions details read failed: ${readErr.message}`);
    const existing = (sessionRow as Record<string, unknown> | null)?.details as Record<string, unknown> | null | undefined;
    const plan = planStrengthDetails(existing, zeppExercises);
    if (plan.details === null) return;

    const { error: detailsUpdateErr } = await supabase
      .from("exercise_sessions")
      .update({ details: plan.details })
      .eq("id", exerciseSessionId);
    if (detailsUpdateErr) throw new Error(`exercise_sessions details update failed: ${detailsUpdateErr.message}`);
  }

  // DAV-274: the Zepp sync and the Health Connect sync both run on app open
  // and can race -- if Zepp's reconcile query above runs before Health
  // Connect's own sync has inserted its row for the same real session, the
  // match finds nothing and a second source='zepp' placeholder gets
  // inserted (confirmed against real created_at timestamps 12s apart for
  // the account's 2026-09-26 run). zepp_workout_detail then stays linked to
  // that orphan forever, so neither the corrected distance above nor the
  // real per-second series (splits, charts) ever reaches the row the app
  // actually displays. Self-heals here: by the *next* sync, Health Connect
  // has normally caught up, so re-run the same match this time and merge.
  //
  // The same matching as reconcileWorkout (sessionMatch.ts), so a copy the
  // history pull inserted because the Health Connect session started minutes
  // earlier folds in too. The Health Connect row is kept (its record id serves
  // the session page's series and route, and a Health Connect re-sync would
  // bring it back if deleted); it takes the copy's Zepp measurements, timing,
  // and RPE, notes and exercises where it has none (mergedSessionFields), and
  // every reference to the copy is moved to it before the copy is deleted.
  const MERGE_COLUMNS =
    "id,type,source,start_time,end_time,duration_min,distance_km,avg_hr,max_hr,calories_active,elevation_gain_m,avg_speed_kmh,rpe,notes,details";

  async function mergeOrphanedZeppSessions(): Promise<number> {
    const { data: orphanRows, error: orphanErr } = await supabase
      .from("exercise_sessions")
      .select(`${MERGE_COLUMNS},zepp_workout_detail(zepp_track_id)`)
      .eq("user_id", user.id)
      .eq("source", "zepp");
    if (orphanErr) throw new Error(`orphan read failed: ${orphanErr.message}`);
    const orphans = (orphanRows ?? []) as SessionRow[];
    if (orphans.length === 0) return 0;

    let merged = 0;
    for (const orphan of orphans) {
      const self = asCandidate(orphan);
      if (!self) continue;
      const rows = (await candidateSessions(self, MERGE_COLUMNS)).filter((r) => r.id !== orphan.id && r.source !== "zepp");
      const match = pickSessionMatch(
        { startMs: self.startMs, endMs: self.endMs, type: orphan.type },
        rows.map(asCandidate).filter((c): c is SessionCandidate => c !== null),
        self.linkedTrackId,
      );
      const real = match ? rows.find((r) => r.id === match.id) : undefined;
      if (!real) continue;
      const realId = real.id;

      const update = mergedSessionFields(orphan, real);
      if (Object.keys(update).length > 0) {
        const { error: updateError } = await supabase.from("exercise_sessions").update(update).eq("id", realId);
        if (updateError) throw new Error(`merged session update failed: ${updateError.message}`);
      }

      // Every table that references exercise_sessions (fueling_intake_log and calendar_strength_staging cascade on
      // delete, the others would lose their link).
      for (const [table, column] of [
        ["zepp_workout_detail", "exercise_session_id"],
        ["planned_routes", "exercise_session_id"],
        ["fueling_intake_log", "exercise_session_id"],
        ["calendar_strength_staging", "session_id"],
      ]) {
        const { error: repointError } = await supabase.from(table).update({ [column]: realId }).eq(column, orphan.id);
        if (repointError) throw new Error(`${table} repoint failed: ${repointError.message}`);
      }
      // A planned session moves over only if the kept session has none (one plan per workout).
      const { data: keptPlans, error: planReadError } = await supabase
        .from("planned_sessions").select("id").eq("exercise_session_id", realId).limit(1);
      if (planReadError) throw new Error(`planned_sessions read failed: ${planReadError.message}`);
      if ((keptPlans ?? []).length === 0) {
        const { error: planError } = await supabase
          .from("planned_sessions").update({ exercise_session_id: realId }).eq("exercise_session_id", orphan.id);
        if (planError) throw new Error(`planned_sessions repoint failed: ${planError.message}`);
      }

      const { error: deleteError } = await supabase.from("exercise_sessions").delete().eq("id", orphan.id);
      if (deleteError) throw new Error(`orphaned exercise_sessions delete failed: ${deleteError.message}`);
      merged++;
    }
    return merged;
  }

  try {
    const merged = await mergeOrphanedZeppSessions();
    if (merged > 0) results.push({ metric: "zepp_orphan_merge", date: "", status: 200, stored: true, error: `merged ${merged}` });
  } catch (e) {
    results.push({ metric: "zepp_orphan_merge", date: "", status: 0, stored: false, error: String(e) });
  }

  for (const date of dates) {
    for (const def of defs) {
      const endpoint = def.endpoint(zeppUserId, date);
      const { statusCode, rawBody, stored, error } = await fetchAndStore(endpoint, def.metric, date, "");
      results.push({ metric: def.metric, date, status: statusCode, stored, ...(error ? { error } : {}) });

      // v10: decode band_data and upsert sleep/wearable daily rows.
      if (def.metric === "band_data" && statusCode >= 200 && statusCode < 300) {
        try {
          const decoded = decodeBandDataSummary(rawBody);
          if (decoded) {
            if (decoded.sleepHours !== null && decoded.sleepHours >= MIN_PLAUSIBLE_SLEEP_HOURS && decoded.bedtimeIso && decoded.wakeTimeIso) {
              const sleepRow: Record<string, unknown> = {
                user_id: user.id,
                date,
                hours: decoded.sleepHours,
                bedtime: decoded.bedtimeIso,
                wake_time: decoded.wakeTimeIso,
              };
              if (decoded.sleepScore !== null) sleepRow.score = decoded.sleepScore;
              if (decoded.deepMin !== null) sleepRow.deep_min = decoded.deepMin;
              if (decoded.remMin !== null) sleepRow.rem_min = decoded.remMin;
              if (decoded.lightMin !== null) sleepRow.light_min = decoded.lightMin;
              const { error: sleepErr } = await supabase.from("sleep_daily")
                .upsert(sleepRow, { onConflict: "user_id,date" });
              if (sleepErr) throw new Error(`sleep_daily upsert: ${sleepErr.message}`);
              await recordFieldProvenance(supabase, user.id, date, Object.keys(sleepRow).filter((k) => k !== "user_id" && k !== "date").map((k) => `sleep_daily.${k}`));
            }
            const wearableUpdates: Record<string, unknown> = { user_id: user.id, date };
            let hasWearable = false;
            if (decoded.rhr !== null) { wearableUpdates.rhr = decoded.rhr; hasWearable = true; }
            if (decoded.steps !== null) { wearableUpdates.steps = decoded.steps; hasWearable = true; }
            if (decoded.hrTimeseries !== null) { wearableUpdates.hr_timeseries = decoded.hrTimeseries; hasWearable = true; }
            if (hasWearable) {
              const { error: wearErr } = await supabase.from("wearable_daily")
                .upsert(wearableUpdates, { onConflict: "user_id,date" });
              if (wearErr) throw new Error(`wearable_daily upsert: ${wearErr.message}`);
              await recordFieldProvenance(supabase, user.id, date, Object.keys(wearableUpdates).filter((k) => k !== "user_id" && k !== "date").map((k) => `wearable_daily.${k}`));
            }
            results.push({ metric: "band_data_decode", date, status: 200, stored: true });
          }
        } catch (e) {
          results.push({ metric: "band_data_decode", date, status: 0, stored: false, error: String(e) });
        }
      }

      // DAV-112/123: sport_history is the list; follow up with the real
      // per-point detail payload for each workout it references.
      if (def.metric === "sport_history" && statusCode >= 200 && statusCode < 300) {
        let refs: WorkoutRef[] = [];
        try {
          refs = extractWorkoutRefs(rawBody);
        } catch (e) {
          results.push({
            metric: WORKOUT_DETAIL_METRIC,
            date,
            status: 0,
            stored: false,
            error: `extractWorkoutRefs failed: ${String(e)}`,
          });
        }

        for (const ref of refs) {
          const detailEndpoint =
            `/v1/sport/run/detail.json?trackid=${encodeURIComponent(ref.trackId)}` +
            `&source=${encodeURIComponent(ref.source)}`;
          const detail = await fetchAndStore(detailEndpoint, WORKOUT_DETAIL_METRIC, date, ref.trackId);
          results.push({
            metric: WORKOUT_DETAIL_METRIC,
            date,
            status: detail.statusCode,
            stored: detail.stored,
            ...(detail.error ? { error: detail.error } : {}),
          });

          if (detail.stored && detail.statusCode >= 200 && detail.statusCode < 300) {
            try {
              await reconcileWorkout(ref, detail.rawBody);
            } catch (e) {
              results.push({
                metric: "zepp_reconcile",
                date,
                status: 0,
                stored: false,
                error: String(e),
              });
            }
          }
        }
      }
    }
  }

  // Surface auth failures (expired ~30-day apptoken) distinctly from a
  // successful-but-empty run, so Settings can tell "re-auth needed" apart
  // from "nothing new today" (DAV-118).
  const authFailed = results.some((r) => r.status === 401 || r.status === 403);
  if (!options.updateSyncState) {
    // The history pull reads old days: it must not move the "last synced" marker back in time.
  } else if (authFailed) {
    await supabase.from("zepp_sync_state").upsert(
      {
        user_id: user.id,
        last_error: "Zepp API returned 401/403 -- apptoken likely expired, re-capture needed.",
        last_error_at: new Date().toISOString(),
      },
      { onConflict: "user_id" },
    );
  } else {
    const update: Record<string, unknown> = { user_id: user.id, last_error: null, last_error_at: null };
    if (maxTrackIdSeen > 0) {
      update.last_synced_track_id = String(maxTrackIdSeen);
      update.last_synced_at = new Date().toISOString();
    }
    await supabase.from("zepp_sync_state").upsert(update, { onConflict: "user_id" });
  }

  const succeeded = results.filter((r) => r.stored && r.status >= 200 && r.status < 300).length;
  const failed = results.length - succeeded;

  // What the requested days actually held: the history pull on the phone walks back until Zepp has had nothing
  // for several windows in a row, which is where the account's history starts.
  const found = {
    workouts: results.filter((r) => r.metric === WORKOUT_DETAIL_METRIC).length,
    bandDays: results.filter((r) => r.metric === "band_data_decode" && r.stored).length,
  };


  return {
    summary: { total: results.length, succeeded, failed },
    found,
    authFailed,
    results,
  };
}

// The history pull's scheduler call (historyPull.ts, migration 20261020_zepp_history_pull.sql). pg_cron sends a random
// Vault secret in x-zepp-history; only a service role-only SQL function can say whether it matches, so no human-held key
// is involved. The call claims the pending pull (a lease, so two calls never work on it at once), answers at once and
// works on in the background: week after week back in time for up to HISTORY_BUDGET_MS, saving after every week.
const HISTORY_BUDGET_MS = 100_000;

async function runHistoryPull(secret: string): Promise<Response> {
  const admin = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: ok } = await admin.rpc("zepp_history_secret_ok", { candidate: secret });
  if (ok !== true) return json({ error: "unauthorized" }, 401);
  const zepp = zeppConfig();
  if (!zepp) return json({ error: "not_configured" }, 500);

  const { data: claimed, error: claimError } = await admin.rpc("claim_zepp_history_pull");
  if (claimError) return json({ error: "claim_failed", message: claimError.message }, 500);
  const rows = (claimed ?? []) as Array<HistoryState & { user_id: string }>;
  if (rows.length === 0) return json({ status: "idle" }, 200);

  const defs = METRIC_DEFS.filter((d) => HISTORY_METRICS.includes(d.metric));
  const work = (async () => {
    for (const row of rows) {
      const started = Date.now();
      let state: HistoryState = row;
      const save = (patch: Record<string, unknown>) =>
        admin.from("zepp_history_pull").update({ ...patch, updated_at: new Date().toISOString() }).eq("user_id", row.user_id);
      try {
        while (!state.done && Date.now() - started < HISTORY_BUDGET_MS) {
          const outcome = await extract(admin, { id: row.user_id }, windowDates(state), defs, zepp, { updateSyncState: false });
          if (outcome.authFailed) {
            await save({ error: "Zepp refused the app token (401/403): renew ZEPP_APP_TOKEN, then carry on" });
            break;
          }
          state = afterWindow(state, outcome.found);
          await save({ ...state });
        }
      } catch (e) {
        await save({ error: `Stopped: ${String(e).slice(0, 300)}` });
      } finally {
        await admin.from("zepp_history_pull").update({ running_since: null }).eq("user_id", row.user_id);
      }
    }
  })();
  // Answer the scheduler at once and keep working (Supabase's edge runtime); elsewhere, finish before answering.
  const runtime = (globalThis as { EdgeRuntime?: { waitUntil(p: Promise<unknown>): void } }).EdgeRuntime;
  if (runtime) runtime.waitUntil(work);
  else await work;
  return json({ status: "started", users: rows.length }, 202);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const historySecret = req.headers.get("x-zepp-history");
    if (historySecret) return await runHistoryPull(historySecret);

    // --- Auth ---
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return json({ error: "missing_auth" }, 401);
    }

    const supabase = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_ANON_KEY")!,
      { global: { headers: { Authorization: authHeader } } },
    );

    const token = authHeader.replace("Bearer ", "");
    const {
      data: { user },
      error: userError,
    } = await supabase.auth.getUser(token);
    if (userError || !user) {
      return json({ error: "unauthorized" }, 401);
    }

    // --- Zepp config ---
    const zepp = zeppConfig();
    if (!zepp) {
      return json({
        error: "not_configured",
        message:
          "ZEPP_APP_TOKEN, ZEPP_USER_ID, and ZEPP_API_HOST must be set as Edge Function secrets.",
      }, 500);
    }

    // --- Parse request ---
    // Accepts params either as a query string (manual/curl testing) or a JSON
    // body (the Android client's supabase-kt functions.invoke, which posts a
    // body rather than building a query string) -- body wins if both present.
    const url = new URL(req.url);
    let bodyParams: Record<string, string> = {};
    try {
      const bodyText = await req.text();
      if (bodyText) {
        const parsed = JSON.parse(bodyText);
        if (parsed && typeof parsed === "object") {
          for (const k of ["from", "to", "metrics"]) {
            if (typeof parsed[k] === "string") bodyParams[k] = parsed[k];
          }
        }
      }
    } catch {
      // no body, or not JSON -- query params still work below
    }

    const from = bodyParams.from ?? url.searchParams.get("from");
    const to = bodyParams.to ?? url.searchParams.get("to") ?? from;
    const metricsParam = bodyParams.metrics ?? url.searchParams.get("metrics");

    if (!from) {
      return json({
        error: "missing_params",
        message: "Required: ?from=YYYY-MM-DD (optional: &to=YYYY-MM-DD&metrics=hrv,sleep,...)",
      }, 400);
    }

    const { dates, error: dateError } = parseDateRange(from, to!);
    if (dateError) {
      return json({ error: "invalid_params", message: dateError }, 400);
    }

    // v9: the old per-type band_data metrics (heart_rate, hrv, sleep, spo2)
    // are now a single "band_data" metric. Accept the old names as aliases
    // so existing callers don't break.
    const BAND_DATA_ALIASES = new Set(["heart_rate", "hrv", "sleep", "spo2"]);
    let requestedMetrics = metricsParam
      ? metricsParam.split(",").map((m) => m.trim())
      : METRIC_DEFS.map((d) => d.metric);
    if (requestedMetrics.some((m) => BAND_DATA_ALIASES.has(m))) {
      requestedMetrics = [
        ...requestedMetrics.filter((m) => !BAND_DATA_ALIASES.has(m)),
        "band_data",
      ];
    }

    const defs = METRIC_DEFS.filter((d) =>
      requestedMetrics.includes(d.metric)
    );
    if (defs.length === 0) {
      return json({
        error: "invalid_params",
        message: `Unknown metrics. Available: ${METRIC_DEFS.map((d) => d.metric).join(", ")}`,
      }, 400);
    }

    // --- Extract ---
    return json(await extract(supabase, user, dates, defs, zepp, { updateSyncState: true }), 200);
  } catch (e) {
    return json({ error: "internal_error", message: String(e) }, 500);
  }
});

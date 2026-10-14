// Calendar entries that schedule a template (docs/zepp-integration/05-training-templates.md, section 3).
// PUT /users/{uid}/training/calendar takes one entry; the template is linked by an
// X-TRAINING-TEMPLATE-ID line inside the iCalendar text. Times are epoch ms; the zone is fixed to
// Asia/Taipei (UTC+8, no daylight saving) until the account's zone is read from settings.

const TZ = "Asia/Taipei";
const OFFSET_MS = 8 * 3600_000;
const pad = (n: number) => String(n).padStart(2, "0");

function localStamp(ms: number): string {
  const t = new Date(ms + OFFSET_MS);
  return `${t.getUTCFullYear()}${pad(t.getUTCMonth() + 1)}${pad(t.getUTCDate())}T${pad(t.getUTCHours())}${pad(t.getUTCMinutes())}00`;
}

export interface IcsStyle {
  crlf?: boolean; // RFC 5545 line endings; the Zepp app itself writes plain LF
  uid?: string;   // the Zepp app writes an empty UID
  localId?: string;    // X-TRAINING-LOCAL-ID, set (with the same UID) on entries made in the app
  tplLocalId?: string; // X-TRAINING-TEMPLATE-LOCAL-ID, the template's id in the phone's database
}

// `date` is the local calendar day as yyyy-mm-dd, `hour` the local start hour. The iCalendar text copies
// what the Zepp app writes for its own entries (LF line endings, empty UID, trailing newline): the app
// did not show entries built with CRLF and a random UID.
export function calendarEntry(title: string, description: string, templateId: string, date: string, hour: number, durationMin: number, style: IcsStyle = {}) {
  const [y, m, d] = date.split("-").map(Number);
  const startMs = Date.UTC(y, m - 1, d, hour) - OFFSET_MS;
  const endMs = startMs + durationMin * 60_000;
  const ics = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//Training App//Schedule//EN",
    "CALSCALE:GREGORIAN",
    "BEGIN:VEVENT",
    `UID:${style.uid ?? ""}`,
    ...(style.localId ? [`X-TRAINING-LOCAL-ID:${style.localId}`] : []),
    `DTSTAMP:${new Date().toISOString().replace(/[-:]/g, "").slice(0, 15)}Z`,
    `DTSTART;TZID=${TZ}:${localStamp(startMs)}`,
    `DTEND;TZID=${TZ}:${localStamp(endMs)}`,
    `SUMMARY:${title}`,
    `X-TRAINING-TEMPLATE-ID:${templateId}`,
    ...(style.tplLocalId ? [`X-TRAINING-TEMPLATE-LOCAL-ID:${style.tplLocalId}`] : []),
    "END:VEVENT",
    "END:VCALENDAR",
  ].join(style.crlf ? "\r\n" : "\n") + (style.crlf ? "" : "\n");
  return {
    id: "",
    title,
    description,
    scheduledStartAt: startMs,
    scheduledEndAt: endMs,
    timezone: TZ,
    isRecurring: false,
    icalendarData: ics,
    provider: "USER_CUSTOM",
    status: 1,
  };
}

// Local yyyy-mm-dd plus n days (calendar arithmetic on the date only).
export function addDays(date: string, n: number): string {
  const [y, m, d] = date.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`;
}

export function dayWindowMs(from: string, to: string): [number, number] {
  const f = (s: string) => {
    const [y, m, d] = s.split("-").map(Number);
    return Date.UTC(y, m - 1, d) - OFFSET_MS;
  };
  return [f(from), f(to)];
}

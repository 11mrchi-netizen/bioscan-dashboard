// LAB › COMPARE (web redesign, phase 4). Two views of metrics side by side, kept apart because they do different jobs:
//   DAYS     any score, input or series, on the same days, with a pair read at a lag (Metric.tsx, the metric page's compare)
//   PERIODS  series only, at the resolution the range needs (days, weeks, months over ten years), drilling into a period and
//            the Inspector on a day's records (Explore.tsx)
// Their address parameters do not overlap (m, with, lag, then, range / series, start, end, grain), so a link carries only its own.
import { Explore } from './Explore'

export default function Compare() {
  return <Explore />
}

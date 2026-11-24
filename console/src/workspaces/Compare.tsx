// LAB › COMPARE (web redesign, phase 4). Two views of metrics side by side, kept apart because they do different jobs:
//   DAYS     (default) any score, input or series on the same days, with a pair read at a lag: the metric page's compare
//            (Metric.tsx), embedded. #/lab/compare?m=series:hrv&with=sleep&lag=1
//   PERIODS  series only, at the resolution the range needs (days, weeks, months over ten years), drilling into a period and
//            the Inspector on a day's records (Explore.tsx). #/lab/compare?view=periods&series=hrv,rhr&grain=week
// Their address parameters do not overlap (m, with, lag, then, range / series, start, end, grain), so a link carries only its own.
// Two views until a later pass unifies them: Explore's period grain and drill-down are not in the days view yet.
import { useSearchParams } from 'react-router-dom'
import { Explore } from './Explore'
import { MetricView } from './Metric'

export const DEFAULT_COMPARE = 'series:hrv'

export default function Compare() {
  const [params, setParams] = useSearchParams()
  const periods = params.get('view') === 'periods'
  const setView = (v: 'days' | 'periods') => {
    const n = new URLSearchParams(params)
    if (v === 'periods') n.set('view', 'periods'); else n.delete('view')
    setParams(n, { replace: true })
  }
  // A new main metric: it replaces the old one, and leaves the extras unless it was one of them.
  const setPrimary = (ref: string) => {
    const n = new URLSearchParams(params)
    n.set('m', ref)
    const keep = n.getAll('with').filter((w) => w !== ref)
    n.delete('with')
    for (const w of keep) n.append('with', w)
    setParams(n, { replace: true })
  }
  return (
    <>
      <div className="lab-views" role="group" aria-label="Compare">
        <button className="ft-chip" aria-current={!periods} onClick={() => setView('days')}>DAYS · scores, inputs, series, pairs</button>
        <button className="ft-chip" aria-current={periods} onClick={() => setView('periods')}>PERIODS · weeks and months, drill-down</button>
      </div>
      {periods ? <Explore /> : <MetricView rawRef={params.get('m') ?? DEFAULT_COMPARE} embedded setPrimary={setPrimary} />}
    </>
  )
}

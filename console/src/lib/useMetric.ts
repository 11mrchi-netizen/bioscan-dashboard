import { useEffect, useState } from 'react'
import { fetchMetric, type MetricRequest, type MetricResult } from './metricAdapter'

interface UseMetricResult {
  data: MetricResult | null
  loading: boolean
  error: string | null
}

export function useMetric(req: MetricRequest | null): UseMetricResult {
  const [data, setData] = useState<MetricResult | null>(null)
  const [loading, setLoading] = useState(!!req)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!req) { setData(null); setLoading(false); return }

    let cancelled = false
    setLoading(true)
    setError(null)

    fetchMetric(req).then(result => {
      if (cancelled) return
      setData(result)
      setError(result.error)
      setLoading(false)
    })

    return () => { cancelled = true }
  }, [req?.table, req?.column, req?.range.start, req?.range.end, req?.aggregation])

  return { data, loading, error }
}

export function useMetrics(requests: MetricRequest[]): {
  data: Record<string, MetricResult>
  loading: boolean
} {
  const [data, setData] = useState<Record<string, MetricResult>>({})
  const [loading, setLoading] = useState(requests.length > 0)

  const key = requests.map(r => `${r.table}.${r.column}:${r.range.start}:${r.range.end}`).join('|')

  useEffect(() => {
    if (!requests.length) { setData({}); setLoading(false); return }

    let cancelled = false
    setLoading(true)

    Promise.all(requests.map(r => fetchMetric(r))).then(results => {
      if (cancelled) return
      const map: Record<string, MetricResult> = {}
      for (const r of results) map[r.metricId] = r
      setData(map)
      setLoading(false)
    })

    return () => { cancelled = true }
  }, [key])

  return { data, loading }
}

import { useEffect, useRef, useState, type DependencyList } from 'react'

// Performance pass, Stage 1. The workspaces used to do `setLoading(true); Promise.all(...).then(() => setLoading(false))`:
// a thrown error left the spinner on forever, an older response could overwrite a newer one, and every range change
// replaced the whole workspace with a spinner (tearing down and re-initialising every chart).
//
//   const { loading, refreshing, error, retry } = useLoadEffect(async (isCurrent) => {
//     const data = await fetchSomething(range)
//     if (isCurrent()) setData(data)          // only the latest request may write
//   }, [range])
//
// `loading` is true only until the first successful load. Later loads set `refreshing` and leave the old data on screen.
// A failure sets `error`; `retry()` runs the load again.
export interface LoadStatus {
  loading: boolean
  refreshing: boolean
  error: string | null
  retry: () => void
}

export function useLoadEffect(run: (isCurrent: () => boolean) => Promise<void>, deps: DependencyList): LoadStatus {
  const [status, setStatus] = useState({ loading: true, refreshing: false, error: null as string | null })
  const [attempt, setAttempt] = useState(0)
  const loadedOnce = useRef(false)

  useEffect(() => {
    let current = true
    setStatus({ loading: !loadedOnce.current, refreshing: loadedOnce.current, error: null })
    run(() => current)
      .then(() => {
        if (!current) return
        loadedOnce.current = true
        setStatus({ loading: false, refreshing: false, error: null })
      })
      .catch((e: unknown) => {
        if (!current) return
        console.error('[BioScan] load failed:', e)
        setStatus({ loading: false, refreshing: false, error: e instanceof Error ? e.message : String(e) })
      })
    return () => { current = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, attempt])

  return { ...status, retry: () => setAttempt(a => a + 1) }
}

import { useEffect, useState } from 'react'
import { localToday } from './dates'

/**
 * The local calendar day, kept current: a page left open overnight moves on to the new day at local midnight, and when
 * the tab comes back to the front (timers sleep in a background tab).
 */
export function useLocalToday(): string {
  const [today, setToday] = useState(localToday)
  useEffect(() => {
    const check = () => setToday(localToday())
    const now = new Date()
    const midnight = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 1)
    const t = window.setTimeout(check, midnight.getTime() - now.getTime())
    const onVisible = () => { if (document.visibilityState === 'visible') check() }
    document.addEventListener('visibilitychange', onVisible)
    return () => { window.clearTimeout(t); document.removeEventListener('visibilitychange', onVisible) }
  }, [today])
  return today
}

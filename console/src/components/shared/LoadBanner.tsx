import './LoadBanner.css'

// A visible, recoverable failure: says what went wrong in plain words and offers RETRY. Used instead of a bare spinner
// that never ends, or an empty chart that looks like "no data" when the request actually failed.
export function LoadBanner({ error, onRetry }: { error: string; onRetry: () => void }) {
  const offline = /fetch|network|failed to load|timeout|aborted/i.test(error)
  return (
    <div className="load-banner mono" role="alert">
      <span className="load-banner-text">
        {offline ? 'COULD NOT REACH THE SERVER (OFFLINE OR TIMED OUT)' : 'LOAD FAILED'}: {error}
      </span>
      <button className="load-banner-retry mono" onClick={onRetry}>RETRY</button>
    </div>
  )
}

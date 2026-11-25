// A page in the Futuristic Material look (ftSkin.css) on the plain ground: the page fills the canvas, its cards are neutral
// glass, its selection and primary action platinum, its status Ok / Warn / Bad. Lab, Plan, Setup and the Log belong to no
// domain, so they take no wash (design contract, "Domain wash"); the domain pages (Today, Me) put their own wash on.
import type { ReactNode } from 'react'
import '../../ftSkin.css'

export function SkinPage({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`dash dash-embed ft-page ft-skin ${className}`.trim()}>{children}</div>
}

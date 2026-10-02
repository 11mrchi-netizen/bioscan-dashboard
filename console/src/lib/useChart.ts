import { useEffect, useRef } from 'react'
import echarts from './echarts'

export function useChart(container: React.RefObject<HTMLDivElement | null>) {
  const chart = useRef<echarts.EChartsType | null>(null)

  useEffect(() => {
    if (!container.current) return
    chart.current = echarts.init(container.current, null, { renderer: 'canvas' })
    const ro = new ResizeObserver(() => chart.current?.resize())
    ro.observe(container.current)
    return () => { chart.current?.dispose(); chart.current = null; ro.disconnect() }
  }, [container])

  return chart
}

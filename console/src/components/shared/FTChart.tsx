// DAV-309: Field Terminal ECharts wrapper.
// Every chart in the console is rendered through this component.
// Components supply a ChartSpec + MetricResult; this owns the ECharts option.

import { memo, useEffect, useRef } from 'react'
import echarts from '../../lib/echarts'
import type { ChartSpec } from '../../lib/chartSpec'
import type { MetricResult, MetricPoint } from '../../lib/metricAdapter'
import { PALETTE } from '../../lib/palette'
import { useSelectedDate, useSetSelectedDate, useInspect } from '../../lib/analysisStore'
import './FTChart.css'

interface Props {
  spec: ChartSpec
  result: MetricResult
  height?: number
  onDateSelect?: (date: string) => void
}

export const FTChart = memo(function FTChart({ spec, result, height = 200, onDateSelect }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.EChartsType | null>(null)
  const selectedDate = useSelectedDate()
  const setSelectedDate = useSetSelectedDate()
  const inspect = useInspect()

  // Initialize
  useEffect(() => {
    if (!ref.current) return
    chartRef.current = echarts.init(ref.current, null, { renderer: 'canvas' })
    return () => { chartRef.current?.dispose(); chartRef.current = null }
  }, [])

  // Update option whenever data or spec changes
  useEffect(() => {
    const chart = chartRef.current
    if (!chart) return

    const dates = result.points.map(p => p.date)
    const values = result.points.map(p => p.value)

    const seriesBase = {
      name: spec.title,
      type: spec.kind === 'area' ? 'line' : spec.kind,
      data: values,
      smooth: spec.smooth ?? false,
      symbolSize: 4,
      itemStyle: { color: PALETTE.emerald },
    }

    const series = [
      spec.kind === 'area'
        ? { ...seriesBase, areaStyle: { color: 'rgba(16,185,129,0.12)' } }
        : seriesBase,
    ]

    const option: echarts.EChartsCoreOption = {
      backgroundColor: 'transparent',
      animation: false,
      grid: { top: 8, right: 12, bottom: 32, left: 48, containLabel: false },
      xAxis: {
        type: 'category',
        data: dates,
        axisLine: { lineStyle: { color: PALETTE.border } },
        axisTick: { show: false },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        name: spec.yAxisLabel ?? spec.unit ?? '',
        nameTextStyle: { color: PALETTE.textMuted, fontSize: 10 },
        axisLabel: { color: PALETTE.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: PALETTE.border } },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      tooltip: {
        trigger: 'axis',
        backgroundColor: PALETTE.surface,
        borderColor: PALETTE.border,
        textStyle: { color: PALETTE.text, fontFamily: 'Roboto Mono', fontSize: 11 },
        formatter: (params: unknown) => {
          const p = (params as { axisValue: string; data: number | null }[])[0]
          return `<span style="color:${PALETTE.textMuted}">${p.axisValue}</span><br/><b>${p.data ?? '—'}</b> ${spec.unit ?? ''}`
        },
      },
      series,
    }

    chart.setOption(option, true)

    // Click: emit date + open Inspector
    chart.off('click')
    chart.on('click', (params: { name: string; dataIndex: number }) => {
      const date = params.name
      setSelectedDate(date)
      onDateSelect?.(date)
      const point: MetricPoint = result.points[params.dataIndex]
      if (point) {
        inspect({
          kind: 'metric_point',
          metricId: spec.metricId,
          date,
          value: point.value,
          unit: spec.unit,
          source: point.source,
          confidence: point.confidence,
          provenance: point.provenance,
          aggregation: 'daily_avg',
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        })
      }
    })
  }, [result, spec, onDateSelect])

  // Sync cursor from AnalysisContext
  useEffect(() => {
    const chart = chartRef.current
    if (!chart || !selectedDate) return
    const dates = result.points.map(p => p.date)
    const idx = dates.indexOf(selectedDate)
    if (idx >= 0) {
      chart.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: idx })
    }
  }, [selectedDate, result.points])

  // Resize observer
  useEffect(() => {
    if (!ref.current) return
    const ro = new ResizeObserver(() => chartRef.current?.resize())
    ro.observe(ref.current)
    return () => ro.disconnect()
  }, [])

  if (result.error) {
    return <div className="ft-chart-error mono">{result.error}</div>
  }

  if (result.missingness === 'unavailable') {
    return <div className="ft-chart-empty mono">NO DATA — {spec.title}</div>
  }

  return (
    <div className="ft-chart-wrap">
      <div className="ft-chart-title mono">{spec.title}{spec.unit ? ` · ${spec.unit}` : ''}</div>
      {result.missingness !== 'none' && (
        <div className="ft-chart-missingness mono">{result.missingness.toUpperCase()} COVERAGE</div>
      )}
      <div ref={ref} style={{ width: '100%', height }} />
    </div>
  )
})

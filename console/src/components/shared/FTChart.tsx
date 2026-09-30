// DAV-309: Field Terminal ECharts wrapper.
// Every chart in the console is rendered through this component.
// Components supply a ChartSpec + MetricResult; this owns the ECharts option.

import { useEffect, useRef } from 'react'
import * as echarts from 'echarts'
import type { ChartSpec } from '../../lib/chartSpec'
import type { MetricResult, MetricPoint } from '../../lib/metricAdapter'
import { useAnalysis } from '../../context/AnalysisContext'
import './FTChart.css'


// Field Terminal palette for ECharts
const COLORS = {
  emerald:   '#10B981',
  text:      '#A4AFBA',
  textMuted: '#66717C',
  border:    'rgba(255,255,255,0.09)',
  surface:   '#171E23',
}

interface Props {
  spec: ChartSpec
  result: MetricResult
  height?: number
  onDateSelect?: (date: string) => void
}

export function FTChart({ spec, result, height = 200, onDateSelect }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.EChartsType | null>(null)
  const { ctx, setSelectedDate, inspect } = useAnalysis()

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
      itemStyle: { color: COLORS.emerald },
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
        axisLine: { lineStyle: { color: COLORS.border } },
        axisTick: { show: false },
        axisLabel: { color: COLORS.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        name: spec.yAxisLabel ?? spec.unit ?? '',
        nameTextStyle: { color: COLORS.textMuted, fontSize: 10 },
        axisLabel: { color: COLORS.textMuted, fontSize: 10, fontFamily: 'Roboto Mono' },
        splitLine: { lineStyle: { color: COLORS.border } },
        axisLine: { show: false },
        axisTick: { show: false },
      },
      tooltip: {
        trigger: 'axis',
        backgroundColor: COLORS.surface,
        borderColor: COLORS.border,
        textStyle: { color: COLORS.text, fontFamily: 'Roboto Mono', fontSize: 11 },
        formatter: (params: unknown) => {
          const p = (params as { axisValue: string; data: number | null }[])[0]
          return `<span style="color:${COLORS.textMuted}">${p.axisValue}</span><br/><b>${p.data ?? '—'}</b> ${spec.unit ?? ''}`
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
    if (!chart || !ctx.selectedDate) return
    const dates = result.points.map(p => p.date)
    const idx = dates.indexOf(ctx.selectedDate)
    if (idx >= 0) {
      chart.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: idx })
    }
  }, [ctx.selectedDate, result.points])

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
}

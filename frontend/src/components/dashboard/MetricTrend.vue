<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, graphic, type ECharts } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])
const props = defineProps<{ points: { label: string; value: number }[] }>()
const element = ref<HTMLElement>(); let chart: ECharts | undefined; let observer: ResizeObserver | undefined; let resizeHandler: (() => void) | undefined
const render = () => chart?.setOption({ grid: { left: 38, right: 16, top: 22, bottom: 28 }, tooltip: { trigger: 'axis', valueFormatter: (value: number) => `${value}%` }, xAxis: { type: 'category', data: props.points.map(point => point.label) }, yAxis: { type: 'value', max: 100, min: 0, axisLabel: { formatter: '{value}%' } }, series: [{ type: 'line', smooth: true, showSymbol: false, data: props.points.map(point => point.value), lineStyle: { width: 3, color: '#20a4ae' }, areaStyle: { color: new graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#20a4ae55' }, { offset: 1, color: '#20a4ae03' }]) } }] })
onMounted(() => { if (!element.value) return; chart = init(element.value); render(); if (typeof ResizeObserver !== 'undefined') { observer = new ResizeObserver(() => chart?.resize()); observer.observe(element.value) } else { resizeHandler = () => chart?.resize(); window.addEventListener('resize', resizeHandler) } })
watch(() => props.points, render, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); if (resizeHandler) window.removeEventListener('resize', resizeHandler); chart?.dispose() })
</script>
<template><div ref="element" class="trend-chart" data-test="metric-trend" /></template>
<style scoped>.trend-chart{height:270px;min-width:0}</style>

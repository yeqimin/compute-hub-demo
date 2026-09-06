import { readdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const assetsDirectory = fileURLToPath(new URL('../dist/assets/', import.meta.url))
const chunkNames = (await readdir(assetsDirectory)).filter(name => name.startsWith('echarts-') && name.endsWith('.js'))
const chunkSet = new Set(chunkNames)
const graph = new Map()

for (const chunkName of chunkNames) {
  const source = await readFile(`${assetsDirectory}/${chunkName}`, 'utf8')
  const dependencies = []
  const importPattern = /(?:from|import)\s*["']\.\/([^"']+\.js)["']/g
  for (const match of source.matchAll(importPattern)) {
    if (chunkSet.has(match[1])) dependencies.push(match[1])
  }
  graph.set(chunkName, dependencies)
}

const visited = new Set()
const active = new Set()
const path = []

function findCycle(chunkName) {
  if (active.has(chunkName)) {
    const start = path.indexOf(chunkName)
    return [...path.slice(start), chunkName]
  }
  if (visited.has(chunkName)) return undefined

  visited.add(chunkName)
  active.add(chunkName)
  path.push(chunkName)
  for (const dependency of graph.get(chunkName) ?? []) {
    const cycle = findCycle(dependency)
    if (cycle) return cycle
  }
  path.pop()
  active.delete(chunkName)
  return undefined
}

for (const chunkName of chunkNames) {
  const cycle = findCycle(chunkName)
  if (cycle) {
    throw new Error(`ECharts bundle contains a circular chunk import: ${cycle.join(' -> ')}`)
  }
}

console.log(`Bundle cycle check passed: ${chunkNames.length} ECharts chunk(s), no circular imports`)

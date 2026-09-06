import { readdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const assetsDirectory = fileURLToPath(new URL('../dist/assets/', import.meta.url))
const stylesheetNames = (await readdir(assetsDirectory)).filter(name => name.endsWith('.css'))
const stylesheets = await Promise.all(
  stylesheetNames.map(name => readFile(`${assetsDirectory}/${name}`, 'utf8')),
)
const bundledCss = stylesheets.join('\n')

const requiredSelectors = [
  '.el-message-box{',
  '.is-message-box .el-overlay-message-box{',
]
const missingSelectors = requiredSelectors.filter(selector => !bundledCss.includes(selector))

if (missingSelectors.length > 0) {
  throw new Error(`Production bundle is missing confirmation dialog styles: ${missingSelectors.join(', ')}`)
}

console.log('Bundle style check passed: confirmation dialogs are styled and centered')

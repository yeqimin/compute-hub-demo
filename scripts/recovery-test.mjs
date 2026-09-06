import { execFileSync } from 'node:child_process'
import {
  createInstance,
  getInstance,
  login,
  waitForHealth,
  waitForInstance,
} from './lib/demo-client.mjs'

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

await waitForHealth()
const token = await login('tenant_admin', 'Tenant@123')
const suffix = Date.now()
const created = await createInstance(token, 'SUCCESS', `recovery-${suffix}`)

const acceptedDeadline = Date.now() + 5_000
let accepted = false
while (Date.now() < acceptedDeadline) {
  const current = await getInstance(token, created.id)
  if (current.status === 'CREATING') {
    accepted = true
    break
  }
  await sleep(50)
}
if (!accepted) throw new Error(`engine did not accept command before restart; status=${(await getInstance(token, created.id)).status}`)

execFileSync('docker', ['compose', 'restart', 'mock-engine'], { stdio: 'inherit' })
await waitForInstance(token, created.id, 'RUNNING', 30_000)

console.log('Recovery passed: persisted engine command resumed after Mock Engine restart')

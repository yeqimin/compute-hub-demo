import {
  createInstance,
  instanceAction,
  listLedgers,
  login,
  waitForHealth,
  waitForInstance,
} from './lib/demo-client.mjs'

await waitForHealth()
const token = await login('tenant_admin', 'Tenant@123')
const suffix = Date.now()
const created = await createInstance(token, 'SUCCESS', `lifecycle-${suffix}`)

console.log(`Created ${created.instanceNo}; waiting for RUNNING`)
await waitForInstance(token, created.id, 'RUNNING')
console.log('Submitting STOP')
await instanceAction(token, created.id, 'stop', 'SUCCESS', `stop-${suffix}`)
await waitForInstance(token, created.id, 'STOPPED')
console.log('Submitting START')
await instanceAction(token, created.id, 'start', 'SUCCESS', `start-${suffix}`)
await waitForInstance(token, created.id, 'RUNNING')
console.log('Submitting RESTART with duplicate callback')
await instanceAction(token, created.id, 'restart', 'DUPLICATE_CALLBACK', `restart-${suffix}`)
await waitForInstance(token, created.id, 'RUNNING')
console.log('Submitting DELETE')
await instanceAction(token, created.id, 'delete', 'SUCCESS', `delete-${suffix}`)
await waitForInstance(token, created.id, 'DELETED')

const ledgers = await listLedgers(token, { bizNo: created.orderNo, size: 100 })
const deductions = ledgers.filter(row => row.bizNo === created.orderNo && row.type === 'DEDUCT')
if (deductions.length !== 1) {
  throw new Error(`expected one DEDUCT ledger for ${created.orderNo}, got ${deductions.length}`)
}

console.log(`Lifecycle passed: ${created.instanceNo} create -> stop -> start -> restart -> delete, one DEDUCT ledger`)

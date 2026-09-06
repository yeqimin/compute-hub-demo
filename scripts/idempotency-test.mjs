import { login, request, waitForHealth } from './lib/demo-client.mjs'

await waitForHealth()
const token = await login('tenant_admin', 'Tenant@123')
const suffix = Date.now()
const key = `concurrency-${suffix}`
const body = {
  productId: 1,
  clusterId: 1,
  name: `concurrency-${suffix}`,
  quantity: 1,
  scenario: 'SUCCESS',
}

const results = await Promise.all(Array.from({ length: 50 }, () => request('/instances', {
  token,
  method: 'POST',
  key,
  body,
})))
const ids = new Set(results.filter(result => result.payload?.code === 'SUCCESS').map(result => result.payload.data.id))
const statuses = results.map(result => result.response.status)
if (statuses.some(status => status !== 200) || ids.size !== 1) {
  throw new Error(`Idempotency failed: statuses=${statuses.join(',')} uniqueResources=${ids.size}`)
}

console.log(`Idempotency passed: 50 requests -> 1 instance (id=${[...ids][0]})`)

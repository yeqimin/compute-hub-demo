const base = process.env.BASE_URL ?? 'http://localhost:8080/api/v1'
const login = await fetch(`${base}/auth/login`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ username: 'tenant_admin', password: 'Tenant@123' }) }).then(r => r.json())
const token = login.data.token
const key = `concurrency-${Date.now()}`
const body = JSON.stringify({ productId: 1, clusterId: 1, name: `concurrency-${Date.now()}`, quantity: 1, scenario: 'SUCCESS' })
const responses = await Promise.all(Array.from({ length: 50 }, () => fetch(`${base}/instances`, { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', 'idempotency-key': key }, body })))
const payloads = await Promise.all(responses.map(r => r.json()))
const ids = new Set(payloads.filter(p => p.code === 'SUCCESS').map(p => p.data.id))
if (responses.some(r => r.status !== 200) || ids.size !== 1) {
  throw new Error(`Idempotency failed: statuses=${responses.map(r => r.status).join(',')} uniqueResources=${ids.size}`)
}
console.log(`Idempotency passed: 50 requests -> 1 instance (id=${[...ids][0]})`)

import { execFileSync } from 'node:child_process'

const base = process.env.BASE_URL ?? 'http://localhost:8080/api/v1'
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

const loginResponse = await fetch(`${base}/auth/login`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ username: 'tenant_admin', password: 'Tenant@123' }),
})
const login = await loginResponse.json()
if (!loginResponse.ok || !login.data?.token) throw new Error('login failed')
const headers = { authorization: `Bearer ${login.data.token}` }

const suffix = Date.now()
const createResponse = await fetch(`${base}/instances`, {
  method: 'POST',
  headers: {
    ...headers,
    'content-type': 'application/json',
    'idempotency-key': `recovery-${suffix}`,
  },
  body: JSON.stringify({
    productId: 1,
    clusterId: 1,
    name: `engine-recovery-${suffix}`,
    quantity: 1,
    scenario: 'SUCCESS',
  }),
})
const created = await createResponse.json()
if (!createResponse.ok) throw new Error(`create failed: ${JSON.stringify(created)}`)

const getInstance = async () => {
  const response = await fetch(`${base}/instances/${created.data.id}`, { headers })
  const payload = await response.json()
  if (!response.ok) throw new Error(`query failed: ${JSON.stringify(payload)}`)
  return payload.data
}

const acceptedDeadline = Date.now() + 5000
while (Date.now() < acceptedDeadline) {
  const current = await getInstance()
  if (current.status === 'DISPATCHING') break
  await sleep(50)
}
if ((await getInstance()).status !== 'DISPATCHING') throw new Error('engine did not accept command before restart')

execFileSync('docker', ['compose', 'restart', 'mock-engine'], { stdio: 'inherit' })

const recoveryDeadline = Date.now() + 20000
while (Date.now() < recoveryDeadline) {
  const current = await getInstance()
  if (current.status === 'RUNNING') {
    console.log('Recovery passed: persisted engine command resumed after Mock Engine restart')
    process.exit(0)
  }
  await sleep(250)
}
throw new Error(`engine command did not recover, final status=${(await getInstance()).status}`)

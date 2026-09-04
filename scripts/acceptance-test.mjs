const base = process.env.BASE_URL ?? 'http://localhost:8080/api/v1'

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

async function request(path, { token, method = 'GET', body, key } = {}) {
  const headers = {}
  if (token) headers.authorization = `Bearer ${token}`
  if (body !== undefined) headers['content-type'] = 'application/json'
  if (key) headers['idempotency-key'] = key
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const payload = await response.json()
  return { response, payload }
}

async function login(username, password) {
  const { response, payload } = await request('/auth/login', {
    method: 'POST',
    body: { username, password },
  })
  if (!response.ok || !payload.data?.token) throw new Error(`${username} login failed`)
  return payload.data.token
}

async function create(token, scenario, suffix, extra = {}) {
  const body = {
    productId: 1,
    clusterId: 1,
    name: `acceptance-${scenario.toLowerCase()}-${suffix}`,
    quantity: 1,
    scenario,
    ...extra,
  }
  const result = await request('/instances', {
    token,
    method: 'POST',
    key: `acceptance-${scenario}-${suffix}`,
    body,
  })
  if (!result.response.ok) throw new Error(`${scenario} create failed: ${JSON.stringify(result.payload)}`)
  return result.payload.data
}

async function instance(token, id) {
  const { response, payload } = await request(`/instances/${id}`, { token })
  if (!response.ok) throw new Error(`instance ${id} query failed: ${JSON.stringify(payload)}`)
  return payload.data
}

async function waitFor(token, id, expected, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const current = await instance(token, id)
    if (current.status === expected) return current
    await sleep(500)
  }
  const current = await instance(token, id)
  throw new Error(`instance ${id}: expected ${expected}, actual ${current.status}`)
}

const suffix = Date.now()
const tenantToken = await login('tenant_admin', 'Tenant@123')
const viewerToken = await login('viewer', 'Viewer@123')
const adminToken = await login('admin', 'Admin@123')

const beforeWallet = (await request('/wallet', { token: tenantToken })).payload.data
const failed = await create(tenantToken, 'FAIL', suffix)
const duplicate = await create(tenantToken, 'DUPLICATE_CALLBACK', suffix)
const timeout = await create(tenantToken, 'TIMEOUT', suffix)

await waitFor(tenantToken, failed.id, 'FAILED')
await waitFor(tenantToken, duplicate.id, 'RUNNING')
const unknown = await waitFor(tenantToken, timeout.id, 'UNKNOWN', 35000)

const heldWallet = (await request('/wallet', { token: tenantToken })).payload.data
if (heldWallet.frozenCent !== beforeWallet.frozenCent + timeout.amountCent) {
  throw new Error(`timeout funds were not held: before=${beforeWallet.frozenCent}, after=${heldWallet.frozenCent}`)
}

const ledgers = (await request('/billing/ledgers?size=100', { token: tenantToken })).payload.data.items
const duplicateDeductions = ledgers.filter(row => row.bizNo === duplicate.orderNo && row.type === 'DEDUCT')
if (duplicateDeductions.length !== 1) {
  throw new Error(`duplicate callback produced ${duplicateDeductions.length} DEDUCT ledgers`)
}

const reconciliation = await request(`/tasks/${unknown.taskId}/retry`, {
  token: tenantToken,
  method: 'POST',
})
if (!reconciliation.response.ok) {
  throw new Error(`manual reconciliation failed: ${JSON.stringify(reconciliation.payload)}`)
}
await waitFor(tenantToken, timeout.id, 'RUNNING')
const settledWallet = (await request('/wallet', { token: tenantToken })).payload.data
if (settledWallet.frozenCent !== beforeWallet.frozenCent) {
  throw new Error(`reconciliation did not release frozen funds: ${settledWallet.frozenCent}`)
}

const viewerDenied = await request('/instances', {
  token: viewerToken,
  method: 'POST',
  key: `acceptance-viewer-${suffix}`,
  body: { productId: 1, clusterId: 1, name: 'forbidden', quantity: 1, scenario: 'SUCCESS' },
})
if (viewerDenied.response.status !== 403) {
  throw new Error(`viewer write expected 403, actual ${viewerDenied.response.status}`)
}

const adminInstance = await create(adminToken, 'SUCCESS', suffix, { tenantId: 1 })
const crossTenantRead = await request(`/instances/${adminInstance.id}`, { token: tenantToken })
if (crossTenantRead.response.status !== 404) {
  throw new Error(`cross-tenant read expected 404, actual ${crossTenantRead.response.status}`)
}

console.log('Acceptance passed: FAIL unfreeze, duplicate callback dedupe, TIMEOUT hold, manual reconciliation, RBAC, tenant isolation')

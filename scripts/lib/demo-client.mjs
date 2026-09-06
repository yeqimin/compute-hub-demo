const apiBase = (process.env.BASE_URL ?? 'http://localhost:8080/api/v1').replace(/\/$/, '')
const healthUrl = process.env.HEALTH_URL ?? `${apiBase.replace(/\/api\/v1$/, '')}/actuator/health`

const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

function describe(payload) {
  if (typeof payload === 'string') return payload
  try { return JSON.stringify(payload) } catch { return String(payload) }
}

export async function request(path, { token, method = 'GET', body, key } = {}) {
  const headers = {}
  if (token) headers.authorization = `Bearer ${token}`
  if (body !== undefined) headers['content-type'] = 'application/json'
  if (key) headers['idempotency-key'] = key
  const response = await fetch(`${apiBase}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let payload = text
  if (text) {
    try { payload = JSON.parse(text) } catch { /* preserve non-JSON diagnostics */ }
  }
  return { response, payload }
}

export function requireSuccess(result, operation) {
  if (!result.response.ok || result.payload?.code !== 'SUCCESS') {
    const trace = result.payload?.traceId ? ` traceId=${result.payload.traceId}` : ''
    throw new Error(`${operation} failed: HTTP ${result.response.status}${trace} ${describe(result.payload)}`)
  }
  return result.payload.data
}

export async function waitForHealth(timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs
  let lastError = 'not ready'
  while (Date.now() < deadline) {
    try {
      const response = await fetch(healthUrl)
      const payload = await response.json()
      if (response.ok && payload.status === 'UP') return payload
      lastError = `HTTP ${response.status} ${describe(payload)}`
    } catch (error) {
      lastError = error instanceof Error ? error.message : String(error)
    }
    await sleep(500)
  }
  throw new Error(`business-server health timeout: ${lastError}`)
}

export async function login(username, password) {
  const result = await request('/auth/login', { method: 'POST', body: { username, password } })
  const data = requireSuccess(result, `${username} login`)
  if (!data?.token) throw new Error(`${username} login returned no token`)
  return data.token
}

export async function getWallet(token, tenantId) {
  const query = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : ''
  return requireSuccess(await request(`/wallet${query}`, { token }), 'wallet query')
}

export async function recharge(token, amountCent, key, { tenantId, remark = 'Demo acceptance recharge' } = {}) {
  return requireSuccess(await request('/wallet/recharges', {
    token,
    method: 'POST',
    key,
    body: { tenantId: tenantId ?? null, amountCent, remark },
  }), 'wallet recharge')
}

export async function createInstance(token, scenario, suffix, extra = {}) {
  const key = extra.key ?? `create-${scenario.toLowerCase()}-${suffix}`
  const body = {
    productId: 1,
    clusterId: 1,
    name: `demo-${scenario.toLowerCase()}-${suffix}`,
    quantity: 1,
    scenario,
    ...extra,
  }
  delete body.key
  return requireSuccess(await request('/instances', { token, method: 'POST', key, body }), `${scenario} instance creation`)
}

export async function getInstance(token, instanceId) {
  return requireSuccess(await request(`/instances/${instanceId}`, { token }), `instance ${instanceId} query`)
}

export async function waitForInstance(token, instanceId, expected, timeoutMs = 45_000) {
  const accepted = new Set(Array.isArray(expected) ? expected : [expected])
  const deadline = Date.now() + timeoutMs
  let current
  while (Date.now() < deadline) {
    current = await getInstance(token, instanceId)
    if (accepted.has(current.status)) return current
    await sleep(500)
  }
  current = await getInstance(token, instanceId)
  throw new Error(`instance ${instanceId}: expected ${[...accepted].join('|')}, actual ${current.status}`)
}

export async function instanceAction(token, instanceId, action, scenario, key) {
  const normalized = action.toLowerCase()
  const result = normalized === 'delete'
    ? await request(`/instances/${instanceId}?scenario=${encodeURIComponent(scenario)}`, { token, method: 'DELETE', key })
    : await request(`/instances/${instanceId}/${normalized}`, { token, method: 'POST', key, body: { scenario } })
  return requireSuccess(result, `${normalized} instance ${instanceId}`)
}

export async function listLedgers(token, filters = {}) {
  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') query.set(name, String(value))
  }
  const suffix = query.size ? `?${query}` : ''
  return requireSuccess(await request(`/billing/ledgers${suffix}`, { token }), 'ledger query').items
}

export async function taskAction(token, taskId, action) {
  return requireSuccess(await request(`/tasks/${taskId}/${action}`, { token, method: 'POST' }), `${action} task ${taskId}`)
}

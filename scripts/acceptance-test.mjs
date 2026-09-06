import {
  createInstance,
  getWallet,
  listLedgers,
  login,
  request,
  taskAction,
  waitForHealth,
  waitForInstance,
} from './lib/demo-client.mjs'

await waitForHealth()
const suffix = Date.now()
const tenantToken = await login('tenant_admin', 'Tenant@123')
const viewerToken = await login('viewer', 'Viewer@123')
const adminToken = await login('admin', 'Admin@123')

const beforeWallet = await getWallet(tenantToken)
const failed = await createInstance(tenantToken, 'FAIL', `acceptance-${suffix}`)
const duplicate = await createInstance(tenantToken, 'DUPLICATE_CALLBACK', `acceptance-${suffix}`)
const timeout = await createInstance(tenantToken, 'TIMEOUT', `acceptance-${suffix}`)

await waitForInstance(tenantToken, failed.id, 'FAILED')
await waitForInstance(tenantToken, duplicate.id, 'RUNNING')
const unknown = await waitForInstance(tenantToken, timeout.id, 'UNKNOWN')

const heldWallet = await getWallet(tenantToken)
if (heldWallet.frozenCent !== beforeWallet.frozenCent + timeout.amountCent) {
  throw new Error(`timeout funds were not held: before=${beforeWallet.frozenCent}, after=${heldWallet.frozenCent}`)
}

const ledgers = await listLedgers(tenantToken, { bizNo: duplicate.orderNo, size: 100 })
const duplicateDeductions = ledgers.filter(row => row.bizNo === duplicate.orderNo && row.type === 'DEDUCT')
if (duplicateDeductions.length !== 1) {
  throw new Error(`duplicate callback produced ${duplicateDeductions.length} DEDUCT ledgers`)
}

await taskAction(tenantToken, unknown.taskId, 'reconcile')
await waitForInstance(tenantToken, timeout.id, 'RUNNING')
const settledWallet = await getWallet(tenantToken)
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

const adminInstance = await createInstance(adminToken, 'SUCCESS', `admin-${suffix}`, { tenantId: 1 })
const crossTenantRead = await request(`/instances/${adminInstance.id}`, { token: tenantToken })
if (crossTenantRead.response.status !== 404) {
  throw new Error(`cross-tenant read expected 404, actual ${crossTenantRead.response.status}`)
}

console.log('Acceptance passed: failure unfreeze, callback dedupe, timeout reconciliation, RBAC, tenant isolation')

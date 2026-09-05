import { describe, expect, it } from 'vitest'
import { allowedActions } from './instanceActions'
import { hasPermission, hasRole } from './permissions'

describe('instance action permissions', () => {
  it('allows stop and restart only for a running writable instance', () => {
    expect(allowedActions('RUNNING', ['instance:operate'])).toEqual(['STOP', 'RESTART', 'DELETE'])
    expect(allowedActions('RUNNING', [])).toEqual([])
  })

  it('only exposes valid actions for stopped and failed instances', () => {
    expect(allowedActions('STOPPED', ['instance:operate'])).toEqual(['START', 'DELETE'])
    expect(allowedActions('FAILED', ['instance:operate'])).toEqual(['DELETE'])
  })

  it('checks permissions and roles without granting partial matches', () => {
    expect(hasPermission(['instance:read', 'instance:operate'], 'instance:operate')).toBe(true)
    expect(hasPermission(['instance:operator'], 'instance:operate')).toBe(false)
    expect(hasRole(['TENANT_ADMIN'], 'PLATFORM_ADMIN')).toBe(false)
    expect(hasRole(['PLATFORM_ADMIN'], 'PLATFORM_ADMIN')).toBe(true)
  })
})

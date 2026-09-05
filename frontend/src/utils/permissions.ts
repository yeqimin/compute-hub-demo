import type { RoleCode } from '../types/api'

export const hasPermission = (permissions: readonly string[], permission: string) =>
  permissions.includes(permission)

export const hasRole = (roles: readonly string[], role: RoleCode) => roles.includes(role)

export const hasAnyRole = (roles: readonly string[], requiredRoles: readonly RoleCode[]) =>
  requiredRoles.some((role) => hasRole(roles, role))

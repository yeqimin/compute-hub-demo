#!/bin/sh
set -eu

BASE_URL=${BASE_URL:-http://localhost:8080/api/v1}
HEALTH_URL=${HEALTH_URL:-http://localhost:8080/actuator/health}

READY=0
for _ in $(seq 1 120); do
  if curl -fsS "$HEALTH_URL" | grep -q '"status":"UP"'; then
    READY=1
    break
  fi
  sleep 1
done
test "$READY" -eq 1

LOGIN=$(curl -fsS -H 'Content-Type: application/json' -d '{"username":"tenant_admin","password":"Tenant@123"}' "$BASE_URL/auth/login")
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
test -n "$TOKEN"
AUTH="Authorization: Bearer $TOKEN"
KEY="smoke-$(date +%s)"
CREATED=$(curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"productId":1,"clusterId":1,"name":"smoke-instance","quantity":1,"scenario":"SUCCESS"}' "$BASE_URL/instances")
INSTANCE_ID=$(printf '%s' "$CREATED" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
test -n "$INSTANCE_ID"

RUNNING=0
for _ in $(seq 1 45); do
  RESULT=$(curl -fsS -H "$AUTH" "$BASE_URL/instances/$INSTANCE_ID")
  if printf '%s' "$RESULT" | grep -q '"status":"RUNNING"'; then
    RUNNING=1
    break
  fi
  sleep 1
done
test "$RUNNING" -eq 1

printf '\nSmoke test passed: login -> freeze -> gRPC -> callback -> settlement\n'

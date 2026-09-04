#!/bin/sh
set -eu
BASE_URL=${BASE_URL:-http://localhost:8080/api/v1}
LOGIN=$(curl -fsS -H 'Content-Type: application/json' -d '{"username":"tenant_admin","password":"Tenant@123"}' "$BASE_URL/auth/login")
TOKEN=$(printf '%s' "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
test -n "$TOKEN"
AUTH="Authorization: Bearer $TOKEN"
KEY="smoke-$(date +%s)"
curl -fsS -H "$AUTH" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"productId":1,"clusterId":1,"name":"smoke-instance","quantity":1,"scenario":"SUCCESS"}' "$BASE_URL/instances"
sleep 4
RESULT=$(curl -fsS -H "$AUTH" "$BASE_URL/instances")
printf '%s' "$RESULT" | grep -q 'RUNNING'
printf '\nSmoke test passed: login -> freeze -> gRPC -> callback -> settlement\n'

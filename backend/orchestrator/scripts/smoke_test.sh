#!/usr/bin/env zsh
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"

printf '\n[1/6] Health check\n'
curl -sS "$BASE_URL/health" | sed 's/.*/  &/'

printf '\n[2/6] Connector status + authorize\n'
curl -sS "$BASE_URL/v1/connectors/status?userId=demo-user" | sed 's/.*/  &/'
curl -sS -X POST "$BASE_URL/v1/connectors/gmail/authorize" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"demo-user"}' | sed 's/.*/  &/'

printf '\n[3/6] Ingest one context event\n'
curl -sS -X POST "$BASE_URL/v1/context/events" \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "demo-user",
    "sessionId": "local-session",
    "events": [
      {
        "eventId": "evt-1",
        "occurredAt": 1735660800000,
        "source": "notification_usage",
        "category": "notification",
        "summary": "calendar reminder in 30 minutes",
        "payload": {"app": "calendar"},
        "sensitivity": "MEDIUM",
        "ttlSeconds": 86400
      }
    ]
  }' | sed 's/.*/  &/'

printf '\n[4/6] Request proactive plan\n'
PLAN_JSON=$(curl -sS -X POST "$BASE_URL/v1/proactive/plan" \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "demo-user",
    "now": 1735660860000,
    "contextWindow": []
  }')

echo "$PLAN_JSON" | sed 's/.*/  &/'

PLAN_ID=$(echo "$PLAN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["planId"])')
STEP_ID=$(echo "$PLAN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["steps"][0]["stepId"])')

printf '\n[5/6] Submit HITL approve and execute first step\n'
curl -sS -X POST "$BASE_URL/v1/hitl/decision" \
  -H 'Content-Type: application/json' \
  -d "{\"planId\":\"$PLAN_ID\",\"approved\":true}" | sed 's/.*/  &/'

curl -sS -X POST "$BASE_URL/v1/actions/execute" \
  -H 'Content-Type: application/json' \
  -d "{\"planId\":\"$PLAN_ID\",\"stepId\":\"$STEP_ID\"}" | sed 's/.*/  &/'

printf '\n[6/6] Disconnect connector\n'
curl -sS -X POST "$BASE_URL/v1/connectors/gmail/disconnect" \
  -H 'Content-Type: application/json' \
  -d '{"userId":"demo-user"}' | sed 's/.*/  &/'

echo "\nSmoke test complete."

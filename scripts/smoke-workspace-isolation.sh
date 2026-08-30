#!/usr/bin/env bash
set -euo pipefail

base_url="${ASKMETRIC_BASE_URL:-http://localhost:8080}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
access_token="${ASKMETRIC_ACCESS_TOKEN:-$("$script_dir/demo-access-token.sh")}"

forged_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "Authorization: Bearer $access_token" \
  -H 'X-Workspace-Id: workspace-finance' \
  "$base_url/api/v1/session")"
test "$forged_status" = "403"

forged_run_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "Authorization: Bearer $access_token" \
  -H 'X-Workspace-Id: workspace-finance' \
  -H 'Content-Type: application/json' \
  -d '{"message":"forged Workspace"}' \
  "$base_url/api/v1/conversations/isolation-probe/runs")"
test "$forged_run_status" = "403"

forged_conversation_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "Authorization: Bearer $access_token" \
  -H 'X-Workspace-Id: workspace-demo' \
  -H 'Content-Type: application/json' \
  -d '{"message":"forged Conversation"}' \
  "$base_url/api/v1/conversations/conversation-finance/runs")"
test "$forged_conversation_status" = "403"

printf 'Workspace isolation smoke check passed\n'

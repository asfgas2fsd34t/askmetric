#!/usr/bin/env bash
set -euo pipefail

token_endpoint="${ASKMETRIC_TOKEN_ENDPOINT:-http://localhost:8082/realms/askmetric/protocol/openid-connect/token}"
client_id="${ASKMETRIC_OIDC_CLIENT_ID:-askmetric-web}"
username="${ASKMETRIC_DEMO_USERNAME:-alice}"
password="${ASKMETRIC_DEMO_PASSWORD:-askmetric-demo}"
python_command="$(command -v python3 || command -v python)"

for _ in $(seq 1 60); do
  response="$(curl --fail-with-body --silent --show-error \
    --data-urlencode "client_id=$client_id" \
    --data-urlencode "username=$username" \
    --data-urlencode "password=$password" \
    --data-urlencode 'grant_type=password' \
    "$token_endpoint" 2>/dev/null || true)"
  if [ -n "$response" ]; then
    printf '%s' "$response" | "$python_command" -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
    exit 0
  fi
  sleep 2
done

echo "Timed out waiting for the AskMetric identity provider" >&2
exit 1

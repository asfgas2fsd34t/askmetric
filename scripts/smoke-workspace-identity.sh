#!/usr/bin/env bash
set -euo pipefail

base_url="${ASKMETRIC_BASE_URL:-http://localhost:8080}"
web_url="${ASKMETRIC_WEB_URL:-http://localhost:5173}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
access_token="${ASKMETRIC_ACCESS_TOKEN:-$("$script_dir/demo-access-token.sh")}"
python_command="$(command -v python3 || command -v python)"

unauthenticated_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "$base_url/api/v1/session")"
test "$unauthenticated_status" = "401"

session="$(curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $access_token" \
  "$base_url/api/v1/session")"
selected="$(curl --fail-with-body --silent --show-error \
  -H "Authorization: Bearer $access_token" \
  -H 'X-Workspace-Id: workspace-growth' \
  "$base_url/api/v1/session")"
forged_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  -H "Authorization: Bearer $access_token" \
  -H 'X-Workspace-Id: workspace-forged' \
  "$base_url/api/v1/session")"
test "$forged_status" = "403"

printf '%s\n%s\n' "$session" "$selected" | "$python_command" -c '
import json
import sys

current, selected = [json.loads(line) for line in sys.stdin]
if current["user"]["username"] != "alice":
    raise SystemExit("unexpected current user")
if current["currentMembership"]["workspaceId"] != "workspace-demo":
    raise SystemExit("unexpected default Workspace Membership")
if len(current["memberships"]) != 2:
    raise SystemExit("unexpected Workspace Membership count")
if selected["currentMembership"]["workspaceId"] != "workspace-growth":
    raise SystemExit("Java did not confirm the selected Workspace Membership")
'

curl --fail --silent "$web_url" | grep -q '<div id="app">'
printf 'Workspace identity smoke check passed for alice\n'

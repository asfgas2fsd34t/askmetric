#!/usr/bin/env bash
set -euo pipefail

base_url="${ASKMETRIC_BASE_URL:-http://localhost:8080}"
conversation_id="smoke-conversation"

response="$(curl --fail-with-body --silent --show-error \
  -H 'Content-Type: application/json' \
  -d '{"message":"smoke agent run"}' \
  "${base_url}/api/v1/conversations/${conversation_id}/runs")"

run_id="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["runId"])')"
events_url="$(printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["eventsUrl"])')"

events="$(curl --fail-with-body --silent --show-error --max-time 15 "${base_url}${events_url}")"
printf '%s\n' "$events"

printf '%s\n' "$events" | ASKMETRIC_EXPECTED_RUN_ID="$run_id" python3 -c '
import json
import os
import sys

expected_run_id = os.environ["ASKMETRIC_EXPECTED_RUN_ID"]
payloads = [json.loads(line.removeprefix("data: ")) for line in sys.stdin if line.startswith("data: ")]
expected_types = ["agent.run.accepted", "agent.run.progress", "agent.run.completed"]
if [payload["eventType"] for payload in payloads] != expected_types:
    raise SystemExit(f"unexpected event types: {payloads}")
if len(payloads) != len(expected_types):
    raise SystemExit(f"unexpected event count: {len(payloads)}")
for sequence, payload in enumerate(payloads, start=1):
    if payload["schemaVersion"] != 1:
        raise SystemExit("schemaVersion must be 1")
    if payload["runId"] != expected_run_id:
        raise SystemExit("run does not match the accepted run")
    if payload["sequence"] != sequence:
        raise SystemExit("event sequence is not contiguous")
if payloads[-1]["eventType"] != "agent.run.completed" or payloads[-1]["message"] != "Synthetic Agent Run completed":
    raise SystemExit("run did not complete successfully")
'
printf 'Smoke check passed for %s\n' "$run_id"

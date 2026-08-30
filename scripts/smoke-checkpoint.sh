#!/usr/bin/env bash
set -euo pipefail

compose_file="infra/docker-compose.yml"
run_id="checkpoint-smoke-$(date +%s)-$$"
conversation_id="checkpoint-conversation"
correlation_id="checkpoint-correlation-$run_id"
python_command="$(command -v python3 || command -v python)"

docker compose -f "$compose_file" up -d --wait checkpoint-db
docker compose -f "$compose_file" build checkpoint-probe

started="$(docker compose -f "$compose_file" run --rm checkpoint-probe \
  start \
  --run-id "$run_id" \
  --conversation-id "$conversation_id" \
  --correlation-id "$correlation_id")"
resumed="$(docker compose -f "$compose_file" run --rm checkpoint-probe \
  resume --run-id "$run_id")"
resumed_again="$(docker compose -f "$compose_file" run --rm checkpoint-probe \
  resume --run-id "$run_id")"

printf '%s\n%s\n%s\n' "$started" "$resumed" "$resumed_again" | \
  ASKMETRIC_RUN_ID="$run_id" \
  ASKMETRIC_CONVERSATION_ID="$conversation_id" \
  ASKMETRIC_CORRELATION_ID="$correlation_id" \
  "$python_command" -c '
import json
import os
import sys

started, resumed, resumed_again = [json.loads(line) for line in sys.stdin]
identity = {
    "conversation_id": os.environ["ASKMETRIC_CONVERSATION_ID"],
    "run_id": os.environ["ASKMETRIC_RUN_ID"],
    "correlation_id": os.environ["ASKMETRIC_CORRELATION_ID"],
}
progress = {**identity, "event_type": "agent.run.progress", "sequence": 2}
completed = {**identity, "event_type": "agent.run.completed", "sequence": 3}
if started != {
    "state": {**identity, "events": [progress]},
    "emitted_events": [progress],
}:
    raise SystemExit(f"unexpected interrupted state: {started}")
final_state = {**identity, "events": [progress, completed]}
if resumed != {"state": final_state, "emitted_events": [completed]}:
    raise SystemExit(f"unexpected recovered state: {resumed}")
if resumed_again != {"state": final_state, "emitted_events": []}:
    raise SystemExit(f"unexpected recovered state: {resumed}, {resumed_again}")
'

printf 'Checkpoint smoke check passed for %s\n' "$run_id"

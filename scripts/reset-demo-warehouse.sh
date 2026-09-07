#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose -f "$repo_dir/infra/docker-compose.yml")

"${compose[@]}" up -d demo-warehouse
until "${compose[@]}" exec -T demo-warehouse pg_isready -U askmetric_demo -d askmetric_demo_warehouse >/dev/null 2>&1; do
  sleep 1
done

"${compose[@]}" exec -T demo-warehouse \
  psql -X -v ON_ERROR_STOP=1 -U askmetric_demo -d askmetric_demo_warehouse \
  -f /demo-warehouse/reset.sql >/dev/null

printf '演示数据仓库已重置为 mrr-drop-v1\n'

#!/usr/bin/env bash
set -euo pipefail

expected_hash="9fe842dc3f62f0b7125ec591a63dbbf6cd224adc76c70e61fd3c01cb054ce2d3"
output_dir="$(mktemp -d)"
trap 'rm -rf "$output_dir"' EXIT

mvn -q -f apps/server/pom.xml -DskipTests package

first_hash="$(java -cp apps/server/target/classes \
  dev.askmetric.server.report.ReportProbe "$output_dir/report-first.html")"
second_hash="$(java -cp apps/server/target/classes \
  dev.askmetric.server.report.ReportProbe "$output_dir/report-second.html")"

cmp "$output_dir/report-first.html" "$output_dir/report-second.html"
test "$first_hash" = "$expected_hash"
test "$second_hash" = "$expected_hash"
grep -Fq '<!doctype html>' "$output_dir/report-first.html"
if grep -Eiq 'https?://|<script|[[:space:]]src=' "$output_dir/report-first.html"; then
  echo "Report contains a script or external dependency" >&2
  exit 1
fi

printf 'Deterministic report smoke check passed: %s\n' "$first_hash"

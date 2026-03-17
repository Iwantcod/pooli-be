#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# 100-line policy test suite runner
# -----------------------------------------------------------------------------
# Executes both scenarios end-to-end with deterministic reset in between.
# Final PASS requires Scenario A + Scenario B both PASS.
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for cmd in k6; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "ERROR: required command is missing: $cmd"
    exit 1
  fi
done

echo "==============================================="
echo "Traffic Policy Suite (100 lines)"
echo "==============================================="

echo "[A-1/3] Setup for Scenario A"
"$SCRIPT_DIR/setup_traffic_test_100.sh"

echo "[A-2/3] Run Scenario A (Burst Stress)"
k6 run "$SCRIPT_DIR/k6_traffic_test_100.js"

echo "[A-3/3] Verify Scenario A"
"$SCRIPT_DIR/verify_policy_consistency_100_burst.sh"

echo "[B-1/3] Setup for Scenario B"
"$SCRIPT_DIR/setup_traffic_test_100.sh"

echo "[B-2/3] Run Scenario B (Speed Exact)"
k6 run "$SCRIPT_DIR/k6_traffic_test_100_speed_exact.js"

echo "[B-3/3] Verify Scenario B"
"$SCRIPT_DIR/verify_policy_consistency_100_speed_exact.sh"

echo
echo "-----------------------------------------------"
echo "SUITE RESULT: PASS (Scenario A + B both passed)"
echo "-----------------------------------------------"

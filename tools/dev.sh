#!/usr/bin/env bash
# Developer entry point for AutoCockpit.
#
#   bash tools/dev.sh unit       # JVM unit tests for all three modules
#   bash tools/dev.sh lint       # Android lint + (optional) ktlint
#   bash tools/dev.sh sim        # headless seeded simulation -> results/simulation.json
#   bash tools/dev.sh emulator   # AAOS emulator + instrumented suite + metrics
#   bash tools/dev.sh metrics    # aggregate JUnit/gfxinfo/EGL artifacts into results/
#   bash tools/dev.sh all        # everything that this machine can actually run
#
# Environment: SESSIONS (default 500), SEED (default 42), SKIP_EMULATOR=1.

set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ROOT"

SESSIONS="${SESSIONS:-500}"
SEED="${SEED:-42}"
OUT="${OUT:-results/simulation.json}"

usage() {
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
}

run_unit() {
  echo "== unit tests =="
  "$GRADLE" --console=plain :core:test :gauge:test :app:testDebugUnitTest
}

run_lint() {
  echo "== lint =="
  "$GRADLE" --console=plain lint
}

run_sim() {
  echo "== simulation ($SESSIONS sessions, seed $SEED) =="
  mkdir -p results
  "$GRADLE" --console=plain :core:run \
    --args="simulate --sessions $SESSIONS --seed $SEED --out $OUT"
}

run_geometry_benchmark() {
  echo "== geometry benchmark =="
  "$GRADLE" --console=plain :gauge:test --tests "*GaugeGeometryBenchmarkTest*"
}

run_emulator() {
  if [[ ! -f tools/emulator.sh ]]; then
    echo "tools/emulator.sh is not present yet; skipping device run." >&2
    return 0
  fi
  echo "== emulator instrumented run =="
  bash tools/emulator.sh
}

run_metrics() {
  if [[ ! -f tools/metrics.sh ]]; then
    echo "tools/metrics.sh is not present yet; skipping metrics aggregation." >&2
    return 0
  fi
  echo "== metrics =="
  bash tools/metrics.sh
}

run_all() {
  local status=0
  run_unit || status=$?
  run_lint || status=$?
  run_sim || status=$?
  run_geometry_benchmark || status=$?
  if [[ "${SKIP_EMULATOR:-0}" == "1" ]]; then
    echo "SKIP_EMULATOR=1: device suite not attempted."
  else
    run_emulator || echo "emulator run failed (see results/ENVIRONMENT.md)" >&2
  fi
  run_metrics || true
  echo "== dev.sh all finished (gradle status $status) =="
  return "$status"
}

cmd="${1:-help}"
case "$cmd" in
  unit) run_unit ;;
  lint) run_lint ;;
  sim) run_sim ;;
  emulator) run_emulator ;;
  metrics) run_metrics ;;
  all) run_all ;;
  help|-h|--help) usage ;;
  *) echo "unknown subcommand: $cmd" >&2; usage; exit 2 ;;
esac

#!/usr/bin/env bash
# Aggregates every measurement artifact in results/ (frame metrics, gfxinfo, JUnit XML) into
# machine-readable JSON for the README. Safe to run with or without a device.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ROOT"
mkdir -p results
python3 tools/metrics.py

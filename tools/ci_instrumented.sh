#!/usr/bin/env bash
# Instrumented suites + gauge metrics on a booted emulator (CI's emulator step).
# Kept in a script because reactivecircus/android-emulator-runner runs each
# line of its `script:` input as a separate command, which breaks `\`
# continuations and multi-line loops.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
mkdir -p results

adb shell pm grant com.autocockpit.app android.permission.BLUETOOTH_CONNECT || true
adb shell pm grant com.autocockpit.app android.permission.BLUETOOTH_ADVERTISE || true
./gradlew --no-daemon --console=plain :app:connectedDebugAndroidTest
./gradlew --no-daemon --console=plain :gauge:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.autocockpit.gauge.OffscreenGaugeBenchmark
for pkg in com.autocockpit.gauge.test com.autocockpit.app; do
  adb exec-out run-as "$pkg" cat files/gauge_egl_benchmark.json > results/gauge_egl_benchmark_device.json 2>/dev/null || true
  adb exec-out run-as "$pkg" cat files/gauge_frame_metrics.json > results/gauge_frame_metrics_device.json 2>/dev/null || true
done
adb logcat -d | grep -o "AUTOCOCKPIT_EGL_JSON:.*" | tail -1 | sed 's/^AUTOCOCKPIT_EGL_JSON://' > results/gauge_egl_benchmark_device.json || true
adb logcat -d | grep -o "AUTOCOCKPIT_FRAME_JSON:.*" | tail -1 | sed 's/^AUTOCOCKPIT_FRAME_JSON://' > results/gauge_frame_metrics_device.json || true
adb shell dumpsys gfxinfo com.autocockpit.app framestats > results/gfxinfo_framestats.txt || true
python3 tools/metrics.py

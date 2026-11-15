#!/usr/bin/env bash
# Boots an Android Automotive emulator headless, runs the instrumented suites, and pulls metrics.
#
# Image selection order (first available wins):
#   1. system-images;android-33;android-automotive;<host-arch>   (AAOS, preferred)
#   2. system-images;android-33;android-automotive-playstore;<host-arch>
#   3. system-images;android-34;google_apis;<host-arch>          (documented fallback: the app
#      declares android.hardware.type.automotive as required, so it will NOT install on this image;
#      the script reports that instead of pretending the suite ran)
#
# Environment: AVD_NAME, BOOT_TIMEOUT (seconds, default 900), KEEP_EMULATOR=1.

set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"
cd "$ROOT"
mkdir -p results

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found (install platform-tools)" >&2
  exit 1
fi
if [[ ! -x "${ANDROID_HOME}/emulator/emulator" ]]; then
  echo "error: emulator binary missing (sdkmanager emulator)" >&2
  exit 1
fi

case "$(uname -m)" in
  arm64|aarch64) HOST_ARCH="arm64-v8a" ;;
  *) HOST_ARCH="x86_64" ;;
esac

AVD_NAME="${AVD_NAME:-autocockpit_aaos}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-900}"
SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/avdmanager"

IMAGE=""
for candidate in \
  "system-images;android-33;android-automotive;${HOST_ARCH}" \
  "system-images;android-33;android-automotive-playstore;${HOST_ARCH}" \
  "system-images;android-34;google_apis;${HOST_ARCH}"; do
  if "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --list_installed 2>/dev/null | grep -q "${candidate}"; then
    IMAGE="$candidate"
    break
  fi
done

if [[ -z "$IMAGE" ]]; then
  echo "No emulator image installed yet; installing the AAOS ${HOST_ARCH} image (this is ~1.5 GB)..."
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
  if ! "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "system-images;android-33;android-automotive;${HOST_ARCH}" 2>&1 | tail -2; then
    echo "AAOS image install failed; trying the Play Store automotive image." >&2
    "$SDKMANAGER" --sdk_root="$ANDROID_HOME" "system-images;android-33;android-automotive-playstore;${HOST_ARCH}" 2>&1 | tail -2 || true
  fi
  for candidate in \
    "system-images;android-33;android-automotive;${HOST_ARCH}" \
    "system-images;android-33;android-automotive-playstore;${HOST_ARCH}"; do
    if "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --list_installed 2>/dev/null | grep -q "${candidate}"; then
      IMAGE="$candidate"
      break
    fi
  done
fi

if [[ -z "$IMAGE" ]]; then
  {
    echo "# Emulator environment"
    echo
    echo "not measured in this build: no Android Automotive system image could be installed."
    echo
    echo "Host arch: ${HOST_ARCH}"
    echo "Tried: android-33;android-automotive, android-automotive-playstore, android-34;google_apis"
    echo "sdkmanager output:"
    "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --list 2>&1 | grep -i automotive | head -20
  } > results/ENVIRONMENT.md
  echo "No automotive image available; see results/ENVIRONMENT.md" >&2
  exit 3
fi

echo "Using image: ${IMAGE}"

# Create (or recreate) the AVD.
if ! "${ANDROID_HOME}/emulator/emulator" -list-avds | grep -qx "$AVD_NAME"; then
  echo "no" | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" --force 2>&1 | tail -3
fi

cleanup() {
  if [[ "${KEEP_EMULATOR:-0}" != "1" ]]; then
    adb emu kill >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "Booting ${AVD_NAME} headless..."
"${ANDROID_HOME}/emulator/emulator" -avd "$AVD_NAME" \
  -no-window -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim \
  -netdelay none -netspeed full > results/emulator.log 2>&1 &
EMULATOR_PID=$!

adb wait-for-device
waited=0
while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
  sleep 5
  waited=$((waited + 5))
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    {
      echo "# Emulator environment"
      echo
      echo "not measured in this build: the emulator process exited during boot."
      echo
      echo '```'
      tail -40 results/emulator.log
      echo '```'
    } > results/ENVIRONMENT.md
    echo "emulator died during boot; see results/ENVIRONMENT.md" >&2
    exit 4
  fi
  if (( waited > BOOT_TIMEOUT )); then
    adb emu kill >/dev/null 2>&1 || true
    {
      echo "# Emulator environment"
      echo
      echo "not measured in this build: the emulator did not finish booting within ${BOOT_TIMEOUT}s."
      echo
      echo '```'
      tail -40 results/emulator.log
      echo '```'
    } > results/ENVIRONMENT.md
    echo "emulator boot timeout; see results/ENVIRONMENT.md" >&2
    exit 4
  fi
done

echo "Boot completed after ${waited}s"
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb shell input keyevent 82 || true
adb shell wm dismiss-keyguard || true

adb shell getprop ro.build.version.sdk > results/device_sdk.txt
adb shell getprop ro.build.flavor > results/device_flavor.txt
adb shell getprop ro.hardware.egl > results/device_egl.txt 2>/dev/null || true
adb shell dumpsys SurfaceFlinger | grep -i "GLES" | head -3 > results/device_gl.txt 2>/dev/null || true

# Grant the runtime Bluetooth permissions so the GATT server can start without a dialog.
adb shell pm grant com.autocockpit.app android.permission.BLUETOOTH_CONNECT >/dev/null 2>&1 || true
adb shell pm grant com.autocockpit.app android.permission.BLUETOOTH_ADVERTISE >/dev/null 2>&1 || true
adb shell pm grant com.autocockpit.app android.permission.BLUETOOTH_SCAN >/dev/null 2>&1 || true
adb shell pm grant com.autocockpit.app android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

echo "== instrumented app suite =="
set +e
"$GRADLE" --console=plain :app:connectedDebugAndroidTest 2>&1 | tee results/connected_app.log | tail -20
APP_STATUS=${PIPESTATUS[0]}

echo "== instrumented gauge benchmark (offscreen EGL) =="
"$GRADLE" --console=plain :gauge:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.autocockpit.gauge.OffscreenGaugeBenchmark \
  2>&1 | tee results/connected_gauge.log | tail -20
GAUGE_STATUS=${PIPESTATUS[0]}
set -e

# Pull whatever the device produced.
for pkg in com.autocockpit.gauge.test com.autocockpit.app; do
  for name in gauge_egl_benchmark.json gauge_frame_metrics.json; do
    if adb shell run-as "$pkg" ls "files/$name" >/dev/null 2>&1; then
      adb exec-out run-as "$pkg" cat "files/$name" > "results/${name%.json}_device.json" 2>/dev/null || true
    fi
  done
done

# Instrumented runs uninstall their APKs afterwards, so the measurements are also emitted to
# logcat as single-line JSON markers. Harvest those first, then try the files dir as a fallback.
adb logcat -d | grep -o "AUTOCOCKPIT_EGL_JSON:.*" | tail -1 | sed 's/^AUTOCOCKPIT_EGL_JSON://' \
  > results/gauge_egl_benchmark_device.json 2>/dev/null || true
[[ -s results/gauge_egl_benchmark_device.json ]] || rm -f results/gauge_egl_benchmark_device.json

adb logcat -d | grep -o "AUTOCOCKPIT_FRAME_JSON:.*" | tail -1 | sed 's/^AUTOCOCKPIT_FRAME_JSON://' \
  > results/gauge_frame_metrics_device.json 2>/dev/null || true
[[ -s results/gauge_frame_metrics_device.json ]] || rm -f results/gauge_frame_metrics_device.json

adb shell dumpsys gfxinfo com.autocockpit.app framestats > results/gfxinfo_framestats.txt 2>/dev/null || true
adb shell dumpsys gfxinfo com.autocockpit.app > results/gfxinfo.txt 2>/dev/null || true

{
  echo "# Emulator environment"
  echo
  echo "- Host: $(uname -m) / $(uname -sr)"
  echo "- Image: ${IMAGE}"
  echo "- AVD: ${AVD_NAME}"
  echo "- Boot: ${waited}s"
  echo "- App instrumentation exit: ${APP_STATUS}"
  echo "- Gauge instrumentation exit: ${GAUGE_STATUS}"
  echo "- SDK: $(cat results/device_sdk.txt 2>/dev/null)"
  echo "- Flavor: $(cat results/device_flavor.txt 2>/dev/null)"
} > results/ENVIRONMENT.md

if [[ "$APP_STATUS" != "0" || "$GAUGE_STATUS" != "0" ]]; then
  echo "instrumented suites failed (app=${APP_STATUS}, gauge=${GAUGE_STATUS}); see results/connected_*.log" >&2
  exit 5
fi
echo "instrumented suites passed"

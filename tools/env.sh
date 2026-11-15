#!/usr/bin/env bash
# Shared environment bootstrap for AutoCockpit build scripts.
# Sources JDK 17 and the Android SDK, and fails loudly when tooling is missing.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ROOT

# --- JDK 17 -----------------------------------------------------------------
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for candidate in \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/usr/lib/jvm/java-17-openjdk-amd64" \
    "/usr/lib/jvm/temurin-17-jdk-amd64" \
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"; do
    if [[ -x "${candidate}/bin/java" ]]; then
      export JAVA_HOME="${candidate}"
      break
    fi
  done
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "error: JDK 17 not found. Install it (brew install openjdk@17) or set JAVA_HOME." >&2
  exit 1
fi

JAVA_MAJOR="$("${JAVA_HOME}/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
if [[ "${JAVA_MAJOR}" != "17" ]]; then
  echo "warning: JAVA_HOME points at JDK ${JAVA_MAJOR}; the build targets 17." >&2
fi

# --- Android SDK ------------------------------------------------------------
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in "${HOME}/android-sdk" "${HOME}/Library/Android/sdk" "${ANDROID_SDK_ROOT:-}"; do
    if [[ -n "${candidate}" && -d "${candidate}/platforms" ]]; then
      export ANDROID_HOME="${candidate}"
      break
    fi
  done
fi
export ANDROID_SDK_ROOT="${ANDROID_HOME:-}"

if [[ -n "${ANDROID_HOME:-}" ]]; then
  export PATH="${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"
fi

GRADLE="${ROOT}/gradlew"
export GRADLE

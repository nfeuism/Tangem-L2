#!/usr/bin/env bash
# Optional build helper.
#
# On a normal network, `./gradlew assembleDebug` is all you need. This script
# exists for environments where Google's Maven CDN is unreliable (it returns
# intermittent 404s for artifacts that exist): it auto-detects a JDK 17 and the
# Android SDK, starts build-env/maven_proxy.py, and points Gradle at the local
# retrying/caching mirror.
#
#   ./build-env/build.sh assembleDebug
#   ./build-env/build.sh testDebugUnitTest
#   USE_MAVEN_PROXY=0 ./build-env/build.sh assembleDebug   # skip the proxy
#
# Override detection with JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT.
set -euo pipefail

# ── JDK 17 ───────────────────────────────────────────────────────────────────
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for candidate in \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "error: JDK 17 not found. Set JAVA_HOME to a JDK 17 installation." >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# ── Android SDK ──────────────────────────────────────────────────────────────
if [[ -z "${ANDROID_HOME:-}" ]]; then
  for candidate in "${ANDROID_SDK_ROOT:-}" "$HOME/android-sdk" "$HOME/Android/Sdk" \
    "$HOME/Library/Android/sdk" /opt/android-sdk; do
    if [[ -n "$candidate" && -d "$candidate/platforms" ]]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "error: Android SDK not found. Set ANDROID_HOME (needs platform 34 + build-tools 34.0.0)." >&2
  exit 1
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

cd "$(dirname "$0")/.."

# ── Optional Maven mirror ────────────────────────────────────────────────────
gradle_args=(--console=plain)
if [[ "${USE_MAVEN_PROXY:-1}" != "0" ]]; then
  if ! curl -sf -o /dev/null --max-time 3 \
    "http://127.0.0.1:8899/central/org/json/json/20240303/json-20240303.pom"; then
    echo "[build] starting local Maven proxy…"
    nohup python3 build-env/maven_proxy.py > build-env/proxy.log 2>&1 &
    sleep 2
  fi
  gradle_args+=(-I build-env/init.gradle.kts)
fi

echo "[build] JAVA_HOME=$JAVA_HOME"
echo "[build] ANDROID_HOME=$ANDROID_HOME"
exec ./gradlew "${gradle_args[@]}" "${@:-assembleDebug}"

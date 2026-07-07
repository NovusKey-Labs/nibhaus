#!/usr/bin/env bash
# Install just enough Android SDK for `./gradlew :app:testDebugUnitTest` and `:app:assembleDebug`
# inside a Codespace — mirrors what the CI "Set up Android SDK" step provides. The emulator
# (instrumented) tests are NOT runnable here (no KVM in Codespaces); they stay CI-only.
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Android cmdline-tools already present at $SDK"
else
  sudo mkdir -p "$SDK/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/tools.zip" "$TOOLS_URL"
  sudo unzip -q "$tmp/tools.zip" -d "$SDK/cmdline-tools"
  # The zip unpacks to cmdline-tools/cmdline-tools; sdkmanager expects it under .../latest.
  sudo mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  sudo chown -R "$(id -u):$(id -g)" "$SDK"
  rm -rf "$tmp"
fi

sdkmanager="$SDK/cmdline-tools/latest/bin/sdkmanager"
yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true
# platform-tools + the compile SDK; AGP auto-downloads the matching build-tools on first build.
# android-37 may not be in the public channel yet (same caveat as CI) — tolerate and let AGP fetch.
"$sdkmanager" "platform-tools" "platforms;android-37" \
  || echo "note: android-37 not in the sdkmanager channel; AGP will resolve it on build"

echo "Android SDK ready at $SDK — try: cd android && ./gradlew :app:testDebugUnitTest"

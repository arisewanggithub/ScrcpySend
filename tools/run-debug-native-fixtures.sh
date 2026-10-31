#!/usr/bin/env bash
set -euo pipefail
source tools/stop-ci-emulator.sh
[[ "${GITHUB_ACTIONS:-}" == true && "${RUNNER_OS:-}" == Linux ]] || { echo "Debug native fixtures require the disposable Linux Actions runner"; exit 1; }
for variable in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_BASE64 ANDROID_KEY_ALIAS ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD; do
  if [[ -v "$variable" ]]; then echo "Production signing configuration is forbidden"; exit 1; fi
done
output=publication-output/debug-native
adb="$ANDROID_HOME/platform-tools/adb"
expected=6
mkdir -p "$output" "$ANDROID_AVD_HOME"
: > "$output/instrumentation-result.txt"
diagnostics() {
  status=$?
  trap - EXIT
  set +e
  timeout 10 "$adb" -s emulator-5554 logcat -d -v threadtime AndroidRuntime:E TestRunner:V '*:S' > "$output/test-logcat.txt" 2>&1
  cat "$output/instrumentation-result.txt"
  if [[ -f "$output/disk-space.txt" ]]; then cat "$output/disk-space.txt"; fi
  if [[ -n "${emulator_pid:-}" ]]; then
    timeout --kill-after=5s 10s "$adb" -s emulator-5554 emu kill ||
      echo "::warning::Emulator console stop did not respond (it may already have exited)" >&2
    if ! stop_ci_emulator "$emulator_pid" && [[ "$status" == 0 ]]; then status=1; fi
  fi
  printf '%s\n' "$status" > "$output/exit-status.txt"
  exit "$status"
}
trap diagnostics EXIT
[[ -r /dev/kvm && -w /dev/kvm ]] || { echo "KVM unavailable"; exit 1; }
echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name debug-native --package "system-images;android-35;google_apis;x86_64"
bash tools/set-ci-avd-userdata.sh "$ANDROID_AVD_HOME/debug-native.avd/config.ini"
df -h "$ANDROID_AVD_HOME" > "$output/disk-space.txt"
available_kb="$(df -Pk "$ANDROID_AVD_HOME" | awk 'NR==2 { print $4 }')"
[[ "$available_kb" =~ ^[0-9]+$ && "$available_kb" -ge 3145728 ]] || { echo "At least 3 GiB free is required for debug fixture userdata plus overhead"; exit 1; }
"$ANDROID_HOME/emulator/emulator" -avd debug-native -port 5554 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on > "$output/emulator.log" 2>&1 &
emulator_pid=$!
timeout 180 "$adb" -s emulator-5554 wait-for-device
booted=false
for attempt in $(seq 1 90); do
  kill -0 "$emulator_pid"
  if [[ "$("$adb" -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then booted=true; break; fi
  sleep 2
done
[[ "$booted" == true ]] || { echo "Debug fixture emulator did not boot"; exit 1; }
[[ "$("$adb" -s emulator-5554 shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo "Not an emulator"; exit 1; }
[[ "$("$adb" -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" == 35 ]] || { echo "Not API35"; exit 1; }
"$adb" -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
timeout 300 "$adb" -s emulator-5554 shell am instrument -w -r -e class dev.screencopy.sender.NativeSrtTest dev.screencopy.sender.test/androidx.test.runner.AndroidJUnitRunner > "$output/instrumentation-result.txt" 2>&1
bash tools/check-instrumentation-result.sh "$output/instrumentation-result.txt" "$expected"

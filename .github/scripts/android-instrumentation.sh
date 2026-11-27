#!/bin/sh
set -eu
cd SoundScapeApp
diagnostics="$PWD/build/instrumentation-diagnostics"
mkdir -p "$diagnostics"

adb shell '
  getprop ro.build.version.sdk
  getprop ro.product.cpu.abi
  wm size
  wm density
  settings get global window_animation_scale
  settings get global transition_animation_scale
  settings get global animator_duration_scale
' > "$diagnostics/device.txt"

if sh ./gradlew :core:database:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest \
    :app:connectedDebugAndroidTest --max-workers=1 --no-daemon; then
    exit 0
else
    status=$?
fi

# Diagnostics run while the emulator is still alive. Preserve the Gradle failure status.
adb logcat -d -b main -b system -b crash -v threadtime > "$diagnostics/logcat.txt" 2>&1 || :
adb shell dumpsys audio > "$diagnostics/audio.txt" 2>&1 || :
adb shell dumpsys media.audio_flinger > "$diagnostics/audio-flinger.txt" 2>&1 || :
adb shell dumpsys window > "$diagnostics/window.txt" 2>&1 || :
adb exec-out screencap -p > "$diagnostics/screen.png" || :
adb shell uiautomator dump /data/local/tmp/soundscape-ci-window.xml > "$diagnostics/ui-dump.log" 2>&1 &&
    adb pull /data/local/tmp/soundscape-ci-window.xml "$diagnostics/window.xml" >> "$diagnostics/ui-dump.log" 2>&1 || :
exit "$status"

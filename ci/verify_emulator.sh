#!/usr/bin/env bash
set -euo pipefail
mkdir -p emulator-evidence
adb wait-for-device
adb shell getprop > emulator-evidence/device-properties.txt
adb install -r "$(find validation-inputs -name 'LumaView_Mobile_0.1.0-test_x86_64.apk' -print -quit)"
adb install -r "$(find validation-inputs -name '*androidTest.apk' -print -quit)"
set +e
adb shell am instrument -w -r org.lumaview.mobile.test/androidx.test.runner.AndroidJUnitRunner | tee emulator-evidence/instrumentation.txt
result=${PIPESTATUS[0]}
adb logcat -d > emulator-evidence/logcat.txt
adb exec-out run-as org.lumaview.mobile tar -cf - files > emulator-evidence/app-files.tar
set -e
mkdir -p emulator-evidence/results
tar -xf emulator-evidence/app-files.tar -C emulator-evidence/results
python3 ci/verify_exports.py emulator-evidence/results/files > emulator-evidence/export-checks.json
if ! rg -q 'OK \([0-9]+ tests?\)' emulator-evidence/instrumentation.txt; then exit 1; fi
exit "$result"

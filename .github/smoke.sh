#!/bin/bash
# Install release APK, launch, check it survives 20 s, grab screenshot + crash log.
mkdir -p smoke
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
adb install -r -g "$APK" > smoke/install.txt 2>&1
adb logcat -c
adb shell am start -W -n com.example.report/.MainActivity > smoke/start.txt 2>&1
sleep 20
adb exec-out screencap -p > smoke/screen_main.png
PID=$(adb shell pidof com.example.report | tr -d '\r')
echo "pid=$PID" > smoke/result.txt
adb logcat -d -b crash > smoke/crash.txt
adb logcat -d | grep -E "AndroidRuntime|FATAL|com.example.report" | tail -200 > smoke/logcat.txt
[ -n "$PID" ] && [ ! -s smoke/crash.txt ] && echo ALIVE >> smoke/result.txt || echo CRASHED >> smoke/result.txt
exit 0

#!/bin/bash
# Run identical UI scenario on original APK and rebuilt release APK; collect screenshots + crash logs.
mkdir -p smoke
git fetch -q --depth 1 origin orig-apk && git show FETCH_HEAD:original.apk > /tmp/orig.apk
NEW=$(ls app/build/outputs/apk/release/*.apk | head -1)
PKG=com.example.report

tap() { # tap element by text or content-desc
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > /tmp/ui.xml
  b=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as E
t=sys.argv[1]
for n in E.parse('/tmp/ui.xml').iter('node'):
  if n.get('text')==t or n.get('content-desc')==t:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((x1+x2)//2,(y1+y2)//2); break
PY
)
  [ -n "$b" ] && adb shell input tap $b && echo "tap '$1' @ $b" || echo "NOT FOUND '$1'"
}
shot() { adb exec-out screencap -p > smoke/${TAG}_$1.png; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > smoke/${TAG}_$1.xml; }

run() {
  TAG=$1; APK=$2
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r -g "$APK" > smoke/${TAG}_install.txt 2>&1
  adb shell appops set $PKG SYSTEM_ALERT_WINDOW deny
  adb logcat -c
  adb shell am start -W -n $PKG/.MainActivity >/dev/null; sleep 8
  for p in "Дозволити" "Allow" "ОК" "OK"; do tap "$p" >/dev/null; done; sleep 1
  shot 1_main
  { tap "ВЗЛІТ ПІШОВ"; sleep 4; shot 2_flying
    tap "СІВ"; sleep 3; shot 3_landed
    tap "Доба"; sleep 2; shot 4_shift
    tap "Налаштування"; sleep 2; shot 5_settings; adb shell input keyevent 4; sleep 1
    tap "Меню"; sleep 2; shot 6_menu; adb shell input keyevent 4; sleep 1
    tap "Інструкція"; sleep 2; shot 7_guide; adb shell input keyevent 4; sleep 1
  } > smoke/${TAG}_steps.txt 2>&1
  PID=$(adb shell pidof $PKG | tr -d '\r')
  adb logcat -d -b crash > smoke/${TAG}_crash.txt
  echo "$TAG pid=$PID crashlog=$(wc -c < smoke/${TAG}_crash.txt)" >> smoke/result.txt
}
run orig /tmp/orig.apk
run new "$NEW"
exit 0

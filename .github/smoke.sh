#!/bin/bash
# Run identical UI scenario on original APK and rebuilt release APK; collect screenshots + crash logs.
mkdir -p smoke
# original APK may be absent (orig-apk branch removed) — the comparison run is then skipped
git fetch -q --depth 1 origin orig-apk 2>/dev/null && git show FETCH_HEAD:original.apk > /tmp/orig.apk 2>/dev/null || rm -f /tmp/orig.apk
NEW=$(ls app/build/outputs/apk/release/*.apk | head -1)

tap() { # tap element by text or content-desc
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > /tmp/ui.xml
  b=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as E
t=sys.argv[1]; ns=list(E.parse('/tmp/ui.xml').iter('node'))
def c(n): x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); return f"{(x1+x2)//2} {(y1+y2)//2}"
hit=[n for n in ns if n.get('text')==t or n.get('content-desc')==t] or [n for n in ns if (n.get('text') or '').startswith(t)]
if hit: print(c(hit[-1]))
PY
)
  [ -n "$b" ] && adb shell input tap $b && echo "tap '$1' @ $b" || echo "NOT FOUND '$1'"
}
shot() { adb exec-out screencap -p > smoke/${TAG}_$1.png; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml > smoke/${TAG}_$1.xml; }

run() {
  TAG=$1; APK=$2; PKG=$3
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r -g "$APK" > smoke/${TAG}_install.txt 2>&1
  adb shell appops set $PKG SYSTEM_ALERT_WINDOW deny
  adb logcat -c
  adb shell am start -W -n $PKG/com.example.report.MainActivity >/dev/null; sleep 8
  for p in "Дозволити" "Allow" "ОК" "OK"; do tap "$p" >/dev/null; done; sleep 1
  shot 1_main
  { tap "ВЗЛІТ ПІШОВ"; sleep 4; shot 2_flying
    tap "СІВ"; sleep 3; shot 3_landed
    tap "Доба"; sleep 2; shot 4_shift
    tap "Налаштування"; sleep 2; shot 5_settings; adb shell input keyevent 4; sleep 1
    tap "Меню"; sleep 2; shot 6_menu; adb shell input keyevent 4; sleep 1
    tap "Інструкція"; sleep 2; shot 7_guide; adb shell input keyevent 4; sleep 1
    adb shell am start -n $PKG/com.example.report.MainActivity >/dev/null; sleep 2
    tap "Ураження"; sleep 2; shot 12_strike; adb shell input keyevent 4; sleep 1
    tap "Карта"; sleep 4; tap "Малювання"; sleep 1; tap "▢"; sleep 1; adb shell input tap 100 300; sleep 1; adb shell input tap 200 380; sleep 1; adb shell input tap 150 480; sleep 2; shot 13_route; tap "Зберегти маршрут"; sleep 1; adb shell input text Test1; tap "Зберегти"; sleep 2; sleep 1; shot 14_area_saved; tap "Малювання"; sleep 1; tap "☰"; sleep 2; shot 15_routes; adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
    tap "ДРОН"; sleep 2; shot 8_dronemenu; tap "Заїхав дрон"; sleep 4; shot 8b_scanner; adb shell input keyevent 4; sleep 2; tap "ДРОН"; sleep 2; tap "Списки / вручну / поділитися"; sleep 2; tap "Вручну"; sleep 2
    tap "4 цифри (в дужках)"; adb shell input text 0417; tap "Номер (QR або вручну)"; adb shell input text 1581F6Q8D24B0012; adb shell input keyevent 111; sleep 1
    shot 9_droneentry; tap "Зберегти"; sleep 2; shot 10_dronelist; tap "ПОДІЛИТИСЯ"; sleep 3; shot 11_share; adb shell input keyevent 4; sleep 1; adb shell input keyevent 4; sleep 1
  } > smoke/${TAG}_steps.txt 2>&1
  PID=$(adb shell pidof $PKG | tr -d '\r')
  adb logcat -d -b crash > smoke/${TAG}_crash.txt
  echo "$TAG pid=$PID crashlog=$(wc -c < smoke/${TAG}_crash.txt)" >> smoke/result.txt
}
[ -s /tmp/orig.apk ] && run orig /tmp/orig.apk com.example.report
run new "$NEW" io.github.axiles126.report

# --- cleartext check: app (new build) as sync client -> fake hub on host via adb reverse ---
pip install -q websockets >/dev/null 2>&1
cat > /tmp/hub.py <<'PY'
import asyncio, websockets
async def h(ws):
    m = await ws.recv(); open('smoke/hub_got.txt','a').write(m[:80] + "\n"); await ws.close()
async def main():
    async with websockets.serve(h, "127.0.0.1", 8777): await asyncio.sleep(90)
asyncio.run(main())
PY
python3 /tmp/hub.py & HUB=$!
adb reverse tcp:8777 tcp:8777
TAG=sync; PKG=io.github.axiles126.report
adb shell am start -W -n $PKG/com.example.report.MainActivity >/dev/null; sleep 3
{ sleep 5; tap "Меню" | grep -q "^tap" || { sleep 5; tap "Меню"; }; sleep 2
  for i in 1 2 3 4 5; do tap "Синхронізація" | grep -q "^tap" && break; adb shell input swipe 120 560 120 160 300; sleep 1; done
  sleep 2; shot 1_net
  tap "IP хаба (напр. 192.168.0.5)"; sleep 1; adb shell input text 127.0.0.1; adb shell input keyevent 111; sleep 1
  tap "Код (XXXX-XXXX-XXXX-XXXX)"; sleep 1; adb shell input text ABCDEFGHJKMNPQRS; adb shell input keyevent 111; sleep 1
  shot 1b_filled
  tap "Підключитися"; sleep 20; shot 2_after; } > smoke/sync_steps.txt 2>&1
kill $HUB 2>/dev/null
echo "sync connections=$(wc -l < smoke/hub_got.txt 2>/dev/null || echo 0) first=$(head -1 smoke/hub_got.txt 2>/dev/null)" >> smoke/result.txt

# --- migration: plain prefs from the previous build must end up encrypted, data kept ---
PKG=io.github.axiles126.report
NEW=$(ls app/build/outputs/apk/release/*.apk | head -1)
cp "$NEW" /tmp/prev.apk  # published APKs are encrypted now; migrate plain prefs into this same build
{
  adb root >/dev/null 2>&1; sleep 3
  adb uninstall $PKG >/dev/null 2>&1
  adb install -r -g /tmp/prev.apk && adb shell am start -W -n $PKG/com.example.report.MainActivity >/dev/null; sleep 5
  adb shell am force-stop $PKG
  printf '<?xml version="1.0" encoding="utf-8"?>\n<map>\n<string name="kap_name">MIGRTEST</string>\n</map>\n' > /tmp/kapibara.xml
  OWNER=$(adb shell stat -c %u:%g /data/data/$PKG | tr -d '\r')
  adb push /tmp/kapibara.xml /data/data/$PKG/shared_prefs/kapibara.xml && adb shell chown $OWNER /data/data/$PKG/shared_prefs/kapibara.xml
  adb install -r -g "$NEW" && adb shell am start -W -n $PKG/com.example.report.MainActivity >/dev/null; sleep 6
  adb shell ls /data/data/$PKG/shared_prefs/
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb shell cat /sdcard/ui.xml | grep -o 'MIGRTEST' | head -1
  adb shell grep -c MIGRTEST /data/data/$PKG/shared_prefs/kapibara_enc.xml
} > smoke/migration.txt 2>&1
echo "migration: $(grep -c 'kapibara_enc.xml' smoke/migration.txt) enc, plain_left=$(grep -c '^kapibara.xml' smoke/migration.txt), ui=$(grep -c '^MIGRTEST' smoke/migration.txt)" >> smoke/result.txt

# --- screen-record floating button: enable in drawer, record ~5 s from the home screen, check MediaStore ---
{
  adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
  adb logcat -c
  adb shell am start -W -n $PKG/com.example.report.MainActivity >/dev/null; sleep 4
  tap "Меню"; sleep 2; TAG=rec; shot 0_drawer
  for i in 1 2 3 4 5 6; do r=$(tap "Плаваюча кнопка «Запис екрана»"); echo "$r"; echo "$r" | grep -q '^tap' && break; adb shell input swipe 120 560 120 160 300; sleep 1; done
  shot 0b_toggled
  sleep 2; adb shell input keyevent 4; sleep 1; adb shell input keyevent 3; sleep 3
  TAG=rec; shot 1_bubble
  # overlay windows aren't in the uiautomator dump → tap the bubble by position (default spot)
  adb shell input tap 68 552; sleep 3; shot 2_consent
  tap "A single app" >/dev/null && sleep 1 && tap "Entire screen"; sleep 1
  for p in "Start" "Start recording" "Start now" "Почати"; do tap "$p"; done; sleep 6; shot 3_recording
  adb shell input tap 75 552; sleep 4; shot 4_stopped
  adb shell content query --uri content://media/external/video/media --projection _display_name:relative_path:_size
  adb logcat -d | grep -E "FATAL|AndroidRuntime|RecordService" | head -30
} > smoke/rec.txt 2>&1
echo "rec: files=$(grep -c 'Report_' smoke/rec.txt) fatal=$(grep -c FATAL smoke/rec.txt)" >> smoke/result.txt

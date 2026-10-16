#!/data/data/com.termux/files/usr/bin/bash
# Ключ підпису для CI (axiles126/Raport).
#   bash s.sh /шлях/до/старого.jks  -> перевіряє, що це ключ оригіналу (оновлення поверх без втрати даних)
#   bash s.sh                       -> створює НОВИЙ постійний ключ (потрібне одноразове видалення старого Report)
set -e
REPO=axiles126/Raport
WANT=4BFCBA9112D5861AE3051B4555D02C0A402AF4AEE5470EAC29556FDB8B469CD3
DIR=$HOME/.report-keys; NEW=$DIR/report-release.jks; ALIAS=report

command -v keytool >/dev/null || pkg install -y openjdk-21
command -v gh >/dev/null || pkg install -y gh
gh auth status >/dev/null 2>&1 || gh auth login -h github.com -p https -w
mkdir -p "$DIR"; chmod 700 "$DIR"

KS="$1"
if [ -n "$KS" ]; then
  [ -f "$KS" ] || { echo "Нема файлу: $KS"; exit 1; }
  read -rsp "Пароль keystore: " SP; echo
  A=$(keytool -list -v -keystore "$KS" -storepass "$SP" 2>/dev/null | awk -v w="$WANT" '
      /^Alias name:|^Псевдоним|^Ім.я псевдоніма/ {a=$NF} /SHA256:/ {gsub(/:/,"",$2); if (toupper($2)==w){print a; exit}}')
  [ -z "$A" ] && { echo "Це НЕ ключ оригіналу (або пароль невірний). Без аргументу скрипт створить новий ключ."; exit 1; }
  echo "OK: ключ оригіналу, alias=$A — APK ставитиметься поверх, дані збережуться."
  read -rsp "Пароль ключа (Enter = такий самий): " KP; echo; KP=${KP:-$SP}
else
  if [ ! -f "$NEW" ]; then
    SP=$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)
    keytool -genkeypair -keystore "$NEW" -storetype PKCS12 -alias $ALIAS -keyalg RSA -keysize 4096 \
      -validity 10000 -dname "CN=Report, O=Report, C=UA" -storepass "$SP" -keypass "$SP" >/dev/null
    printf '%s\n' "$SP" > "$DIR/password.txt"; chmod 600 "$DIR"/*
    # No copy to shared storage (/sdcard is readable by other apps). Back up $DIR to offline media yourself.
    echo "Ключ лише в $DIR (приватна тека Termux). Збережи копію поза телефоном — втрата = знову видаляти застосунок."
    echo "Створено новий ключ: $NEW"
  else
    echo "Використовую вже створений ключ: $NEW"
  fi
  KS=$NEW; A=$ALIAS; SP=$(cat "$DIR/password.txt"); KP=$SP
  echo "УВАГА: перший раз старий Report треба видалити (інший підпис) — вильоти/шаблони зітруться. Далі всі збірки оновлюються поверх."
fi

base64 -w0 "$KS" | gh secret set RELEASE_KEYSTORE_B64 -R $REPO
printf %s "$SP" | gh secret set RELEASE_STORE_PASSWORD -R $REPO
printf %s "$A"  | gh secret set RELEASE_KEY_ALIAS -R $REPO
printf %s "$KP" | gh secret set RELEASE_KEY_PASSWORD -R $REPO
keytool -list -v -keystore "$KS" -storepass "$SP" 2>/dev/null | grep -m1 SHA256
gh workflow run build.yml -R $REPO --ref decomp && echo "Секрети записані, збірку запущено. APK: гілка ci-log → app-release.apk"

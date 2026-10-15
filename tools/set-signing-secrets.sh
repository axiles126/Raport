#!/data/data/com.termux/files/usr/bin/bash
# Знаходить keystore, яким підписаний оригінальний Report (CN=Report, C=UA),
# перевіряє відбиток і записує 4 секрети в GitHub (axiles126/Raport). Termux / Linux.
set -e
REPO=axiles126/Raport
WANT=4BFCBA9112D5861AE3051B4555D02C0A402AF4AEE5470EAC29556FDB8B469CD3

command -v keytool >/dev/null || { pkg install -y openjdk-21 2>/dev/null || pkg install -y openjdk-17; }
command -v gh >/dev/null || pkg install -y gh
gh auth status >/dev/null 2>&1 || gh auth login -h github.com -p https -w

KS="$1"
if [ -z "$KS" ]; then
  mapfile -t C < <(find ~ /sdcard /storage/emulated/0 -maxdepth 6 -type f \( -iname '*.jks' -o -iname '*.keystore' -o -iname '*.p12' -o -iname '*.pfx' \) 2>/dev/null | grep -v '/debug.keystore$' | sort -u)
  [ ${#C[@]} -eq 0 ] && { echo "keystore не знайдено. Запусти: $0 /шлях/до/файлу.jks"; exit 1; }
  echo "Знайдено:"; for i in "${!C[@]}"; do echo "  [$i] ${C[$i]}"; done
fi
read -rsp "Пароль keystore: " SP; echo

pick() { # $1 = keystore; друкує alias, чий сертифікат = WANT
  keytool -list -v -keystore "$1" -storepass "$SP" 2>/dev/null | awk -v w="$WANT" '
    /^Alias name:|^Псевдоним|^Ім.я псевдоніма/ {a=$NF}
    /SHA256:/ {gsub(/:/,"",$2); if (toupper($2)==w) {print a; exit}}'
}
if [ -z "$KS" ]; then for f in "${C[@]}"; do A=$(pick "$f"); [ -n "$A" ] && { KS="$f"; break; }; done
else A=$(pick "$KS"); fi
[ -z "$A" ] && { echo "Жоден keystore з цим паролем не містить ключ оригіналу (SHA-256 $WANT)."; exit 1; }
echo "OK: $KS, alias=$A — відбиток збігається з оригінальним APK."

read -rsp "Пароль ключа (Enter = такий самий): " KP; echo; KP=${KP:-$SP}
base64 -w0 "$KS" | gh secret set RELEASE_KEYSTORE_B64 -R $REPO
printf %s "$SP" | gh secret set RELEASE_STORE_PASSWORD -R $REPO
printf %s "$A"  | gh secret set RELEASE_KEY_ALIAS -R $REPO
printf %s "$KP" | gh secret set RELEASE_KEY_PASSWORD -R $REPO
gh workflow run build.yml -R $REPO --ref decomp && echo "Секрети записані, збірку запущено."

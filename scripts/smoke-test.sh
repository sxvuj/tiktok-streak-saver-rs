#!/usr/bin/env bash
# Installs the APK on a running emulator, opens every tab and checks the screens render,
# which also proves the native library loads and the JNI calls behind each screen work.
# Usage: scripts/smoke-test.sh path/to/app.apk
set -u
APK="$1"
PKG=com.sxvuj.streaksaver
OUT="${SMOKE_OUT:-smoke-out}"
mkdir -p "$OUT"
fail=0

adb install -r "$APK" || { echo "install failed"; exit 1; }
adb logcat -c
adb shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null
sleep 8

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1
}

expect() { # name text
  if grep -q "$2" "$OUT/$1.xml"; then echo "ok   $1: found '$2'"; else echo "FAIL $1: '$2' not on screen"; fail=1; fi
}

tap_text() { # xml-name text
  python3 - "$OUT/$1.xml" "$2" <<'PY' | { read -r x y && adb shell input tap "$x" "$y"; }
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
for m in re.finditer(r'<node[^>]*?text="%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(sys.argv[2]), xml):
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PY
}

dump home
expect home "Streak Saver"
expect home "Not logged in to TikTok"
expect home "Run now"

tap_text home "Chats"; sleep 2; dump chats
expect chats "No friends or groups yet"
expect chats "Import list"

tap_text chats "History"; sleep 2; dump history
expect history "No runs yet"
expect history "Export log"

tap_text history "Profile"; sleep 2; dump profile
expect profile "Background automation"
expect profile "Battery optimization"

adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|UnsatisfiedLinkError|AndroidRuntime: Process: $PKG" "$OUT/logcat.txt"; then
  echo "FAIL: crash found in logcat"; fail=1
fi
exit $fail

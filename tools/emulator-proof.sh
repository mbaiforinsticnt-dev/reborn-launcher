#!/usr/bin/env bash
# Emulator proof for the C2 Reborn launcher on a normal touchscreen phone.
# Drives the real on-screen keypad by touch coordinates, then verifies the
# SMS and call paths with emulator-console modem commands.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:?ANDROID_SERIAL is required}"
ADB=(adb -s "$SERIAL")
SCREEN_DIR="screenshots"
PKG="dev.mbaiforinstinct.rebornlauncher"
mkdir -p "$SCREEN_DIR"

"${ADB[@]}" wait-for-device
until [ "$("${ADB[@]}" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
  sleep 3
done
sleep 5

# The API-30 AVD's SystemUI hangs and its modal ANR dialog eats every tap
# (proven on the F21 image): kill it and let a healthy instance restart.
"${ADB[@]}" root >/dev/null 2>&1 || true
sleep 3
"${ADB[@]}" wait-for-device
for p in com.android.systemui com.android.settings; do
  pid=$("${ADB[@]}" shell pidof "$p" | tr -d '\r')
  [ -n "$pid" ] && "${ADB[@]}" shell kill "$pid" || true
done
sleep 6


# adb shell can be briefly unresponsive right after the SystemUI kill; retry.
W=""; H=""
for i in $(seq 1 10); do
  SZ=$("${ADB[@]}" shell wm size 2>/dev/null | tr -d '\r' | sed -E 's/.*: ([0-9]+)x([0-9]+)/\1 \2/') || true
  read -r W H <<< "$SZ"
  [ -n "$W" ] && [ -n "$H" ] && break
  echo "DIAG: wm size unreadable (attempt $i); retrying"
  sleep 4
done
[ -n "$W" ] && [ -n "$H" ] || { echo "DIAG: could not read screen size"; exit 1; }

# This image ANRs random system apps and the modal dialog eats every tap.
# Watchdog sweeps every 4s: kill the ANR'd package and tap "Wait" away.
NOSWEEP="/tmp/reborn_nosweep"
anr_sweep() {
  [ -f "$NOSWEEP" ] && return 0
  WIN=$("${ADB[@]}" shell "dumpsys window windows" 2>/dev/null | tr -d '\r') || return 0
  echo "$WIN" | grep -qi "Not Responding" || return 0
  PKG=$(echo "$WIN" | grep -oiE "(Not Responding|Application Error): *[a-zA-Z0-9._]+" | head -1 | sed -E 's/.*: *//')
  for p in $PKG com.android.systemui com.android.settings; do
    [ -z "$p" ] && continue
    pid=$("${ADB[@]}" shell pidof "$p" 2>/dev/null | tr -d '\r')
    [ -n "$pid" ] && "${ADB[@]}" shell kill "$pid" 2>/dev/null
  done
  sleep 2
  # Tap "Wait" only if the dialog survived the kill; a blind tap here landed
  # on the stock incoming-call DECLINE button and ate the proof call.
  WIN2=$("${ADB[@]}" shell "dumpsys window windows" 2>/dev/null | tr -d '\r') || return 0
  if echo "$WIN2" | grep -qi "Not Responding"; then
    "${ADB[@]}" shell input tap $((W * 50 / 100)) $((H * 55 / 100)) 2>/dev/null
  fi
  echo "watchdog: swept ANR ($PKG)"
  sleep 2
  return 0
}
anr_watchdog() {
  set +e
  while :; do
    anr_sweep
    sleep 4
  done
}
anr_watchdog &
WATCHDOG=$!
trap 'kill $WATCHDOG 2>/dev/null || true' EXIT

tapf() {
  awk -v x="$1" -v y="$2" -v w="$W" -v kt="$KT" -v kh="$KH" \
    'BEGIN{ printf "%d %d\n", x*w, kt + y*kh }'
}
tap() {
  read -r tx ty < <(tapf "$1" "$2")
  "${ADB[@]}" shell input tap "$tx" "$ty"
}

# Keypad-relative tap points (fractions of the keypad view; see OnScreenKeypad).
CENTER="0.500 0.330"; UP="0.500 0.208"; DOWN="0.500 0.452"
LEFT="0.165 0.330"; RIGHT="0.835 0.330"
CALL="0.165 0.452"; END="0.835 0.452"; LSK="0.165 0.075"
D1="0.165 0.586"; D2="0.500 0.586"; D3="0.835 0.586"
D4="0.165 0.699"; D5="0.500 0.699"; D6="0.835 0.699"
D7="0.165 0.811"; D8="0.500 0.811"; D9="0.835 0.811"
D0="0.500 0.924"

tap_key() { # name from the constants above
  local pt="${!1}"
  tap $pt
}

shot() {
  anr_sweep || true
  "${ADB[@]}" exec-out screencap -p > "$SCREEN_DIR/$1.png"
  python3 - "$SCREEN_DIR/$1.png" <<'PY'
import struct, sys
p = sys.argv[1]
d = open(p, "rb").read()
assert d[:8] == bytes.fromhex("89504e470d0a1a0a"), f"{p} is not a PNG"
w, h = struct.unpack(">II", d[16:24])
assert w >= 360 and h >= 640, f"{p} has suspicious size {w}x{h}"
print(f"{p}: {w}x{h} ok")
PY
}

TOKEN="$(cat "$HOME/.emulator_console_auth_token" | tr -d '\r\n')"
emu_console() {
  { sleep 1; echo "auth $TOKEN"; sleep 1; echo "$1"; sleep 2; } | timeout 10 nc localhost 5554 >/dev/null 2>&1 || true
}

echo "screen ${W}x${H}"

# The API-30 image sometimes wedges system_server during early boot and the
# package service vanishes ("Can't find service: package"); it can take a
# minute to come back. Gate on it instead of blind sleeps.
wait_pkg_service() {
  for i in $(seq 1 30); do
    if "${ADB[@]}" shell service check package 2>/dev/null | tr -d '\r' | grep -q "found"; then
      return 0
    fi
    sleep 5
  done
  return 1
}

INSTALL_OK=0
for attempt in 1 2 3 4 5 6; do
  "${ADB[@]}" wait-for-device
  wait_pkg_service || { echo "DIAG: package service never came up"; exit 1; }
  if "${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk; then
    INSTALL_OK=1
    break
  fi
  echo "DIAG: install attempt $attempt failed; waiting for package service and retrying"
  sleep 10
done
[ "$INSTALL_OK" = 1 ] || { echo "DIAG: apk install failed after 6 attempts"; exit 1; }
"${ADB[@]}" shell pm list packages | tr -d '\r' | grep "$PKG" || {
  echo "DIAG: $PKG not in pm list packages after install:"
  "${ADB[@]}" shell pm list packages | head -30
  exit 1
}
echo "DIAG: installed: $("${ADB[@]}" shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 versionName)"
"${ADB[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tr -d '\r' | tail -3

# Permissions must exist before the launcher queries providers.
for perm in CALL_PHONE READ_CONTACTS READ_CALL_LOG READ_SMS SEND_SMS; do
  "${ADB[@]}" shell pm grant "$PKG" "android.permission.$perm"
done

"${ADB[@]}" shell am start -n "$PKG/.MainActivity"
sleep 5
FG=$("${ADB[@]}" shell "dumpsys activity activities | grep -m1 ResumedActivity" | tr -d '\r')
echo "DIAG foreground: $FG"
echo "$FG" | grep -q "$PKG" || { echo "DIAG: launcher not foreground after am start - aborting"; exit 1; }
# Keypad geometry from the UI itself: uiautomator dump exposes the
# OnScreenKeypad view's absolute bounds. (The earlier wm-size fraction was
# off by a full key row because the app window excludes the nav bar.)
KT=""; KH=""
for i in $(seq 1 8); do
  "${ADB[@]}" shell uiautomator dump /sdcard/reborn_ui.xml >/dev/null 2>&1 || true
  "${ADB[@]}" pull /sdcard/reborn_ui.xml /tmp/reborn_ui.xml >/dev/null 2>&1 || true
  if [ -s /tmp/reborn_ui.xml ] && GEO=$(python3 - /tmp/reborn_ui.xml <<'PY'
import re, sys
xml = open(sys.argv[1], encoding='utf-8', errors='replace').read()
for node in re.finditer(r'<node\b[^>]*>', xml):
    tag = node.group(0)
    cls = re.search(r'class="([^"]*)"', tag)
    bnd = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if cls and 'OnScreenKeypad' in cls.group(1) and bnd:
        top = int(bnd.group(2))
        bottom = int(bnd.group(4))
        print(top, bottom - top)
        sys.exit(0)
sys.exit(1)
PY
); then
    read -r KT KH <<< "$GEO"
    [ -n "$KT" ] && [ -n "$KH" ] && break
  fi
  echo "DIAG: geometry detection failed (attempt $i); retrying"
  sleep 4
done
[ -n "$KT" ] && [ -n "$KH" ] || { echo "DIAG: could not detect keypad geometry"; exit 1; }
echo "keypad top $KT height $KH"

shot 01-idle-keypad

tap_key CENTER; sleep 2; shot 02-menu

# Messaging -> Conversations before any SMS arrives.
tap_key CENTER; sleep 2; shot 03-messaging-list
tap_key CENTER; sleep 2; shot 04-conversations-empty

# A real inbound SMS through the emulator modem.
emu_console 'sms send +15551234567 Reborn proof OK'
sleep 4

# Back to Messaging -> Conversations: message must be listed.
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key CENTER; sleep 2
tap_key CENTER; sleep 2; shot 05-sms-inbox

# Dialer: END home, digits 1 2 3, green key to the system dialer.
tap_key END; sleep 1
tap_key D1; sleep 1
tap_key D2; sleep 1
tap_key D3; sleep 1; shot 06-dialer
tap_key CALL; sleep 3; shot 07-dial-bridge
"${ADB[@]}" shell input keyevent KEYCODE_BACK
sleep 2

# Real inbound call through the emulator modem. The incoming-call UI is the
# stock AVD dialer; the watchdog's ANR kill/tap can dismiss it, so sweeping
# is paused for this window.
touch "$NOSWEEP"
emu_console 'gsm call +15557654321'
sleep 6
shot 08-incoming-call
emu_console 'gsm cancel +15557654321'
sleep 2
rm -f "$NOSWEEP"
sleep 2

# Call log: missed call from the modem must be listed.
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2; shot 09-calllog

# Compose with multitap: Messaging -> New message -> number -> text -> send.
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2
for d in D0 D7 D7 D0 D0 D9 D0 D0 D1 D2 D3; do tap_key "$d"; sleep 1; done
shot 10-compose-number
tap_key CENTER; sleep 2
# Multitap "hi": 4 4 (commit window) then 4 4 4 (commit window).
tap_key D4; sleep 0.3
tap_key D4; sleep 2
tap_key D4; sleep 0.3
tap_key D4; sleep 0.3
tap_key D4; sleep 2
shot 11-compose-text
tap_key CENTER; sleep 3; shot 12-sent

echo "proof complete"

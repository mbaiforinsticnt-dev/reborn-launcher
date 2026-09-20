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

INSTALL_OK=0
for attempt in 1 2 3 4; do
  "${ADB[@]}" wait-for-device
  if "${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk; then
    INSTALL_OK=1
    break
  fi
  echo "DIAG: install attempt $attempt failed; waiting for package service and retrying"
  sleep 12
done
[ "$INSTALL_OK" = 1 ] || { echo "DIAG: apk install failed after 4 attempts"; exit 1; }
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
# Keypad geometry from a real frame: the keypad background is dark slate
# (32,36,42) and the system nav bar is pure black. wm-size fractions lie
# because the app window excludes the nav bar (proven: assumed KT=1056 but
# measured 986; taps landed one row low and opened the dialer instead of
# the menu).
KT=""; KH=""
for i in 1 2 3 4 5; do
  "${ADB[@]}" exec-out screencap > /tmp/reborn_geo.raw 2>/dev/null || true
  if GEO=$(python3 - /tmp/reborn_geo.raw <<'PY'
import struct, sys
d = open(sys.argv[1], 'rb').read()
if len(d) < 12:
    sys.exit(1)
w, h, fmt = struct.unpack('<III', d[:12])
px = d[12:]
if len(px) != w * h * 4:
    if len(d) == w * h * 4:
        px = d
    else:
        sys.exit(1)
def ch(x, y, c):
    return px[(y * w + x) * 4 + c]
x = w // 2
run = 0
KT = None
for y in range(h // 4, h):
    dark = (ch(x, y, 0) + ch(x, y, 1) + ch(x, y, 2)) < 240
    if dark:
        run += 1
        if run >= 100:
            KT = y - 99
            break
    else:
        run = 0
if KT is None:
    sys.exit(1)
y = h - 1
nx = w // 16  # away from the nav-bar buttons (triangle/circle/square)
while y > KT and ch(nx, y, 0) == 0 and ch(nx, y, 1) == 0 and ch(nx, y, 2) == 0:
    y -= 1
NT = y + 1
if NT < KT + 200:
    NT = h
print(KT, NT - KT)
PY
); then
    read -r KT KH <<< "$GEO"
    [ -n "$KT" ] && [ -n "$KH" ] && break
  fi
  echo "DIAG: geometry detection failed (attempt $i); retrying"
  sleep 3
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

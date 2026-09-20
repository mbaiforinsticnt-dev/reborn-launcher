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
# Green-run logs are unreadable in the Actions UI, so mirror all output to a
# log file. It must live OUTSIDE screenshots/ during the run - if the publish
# step's own git output kept appending to it, the tree would be dirty at
# rebase time and the publish would fail. Copied in at the end.
PROOF_LOG=/tmp/reborn-proof-log.txt
exec > >(tee -a "$PROOF_LOG") 2>&1

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
  # Never kill system-critical packages: killing settings/systemui mid-boot
  # crashes system_server and takes the package service down with it (seen
  # as "Can't find service: package" at install). Those get the Wait tap only.
  case "$PKG" in
    system|system_server)
      # A wedged system_server never recovers on its own; killing it makes
      # the Android runtime restart (zygote respawns it). This IS the fix.
      pid=$("${ADB[@]}" shell pidof system_server 2>/dev/null | tr -d '\r')
      [ -n "$pid" ] && "${ADB[@]}" shell kill -9 "$pid" 2>/dev/null
      echo "watchdog: system_server wedged - forced runtime restart"
      sleep 15
      ;;
    ""|android|com.android.systemui|com.android.settings|com.android.phone|com.android.providers*|com.android.server*)
      : ;;
    *)
      pid=$("${ADB[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
      [ -n "$pid" ] && "${ADB[@]}" shell kill "$pid" 2>/dev/null
      ;;
  esac
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
"${ADB[@]}" shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true

# The API-30 image sometimes wedges system_server during early boot and the
# package service vanishes ("Can't find service: package"); it can take a
# minute to come back. Gate on it instead of blind sleeps.
wait_pkg_service() {
  for i in $(seq 1 30); do
    if "${ADB[@]}" shell service check package 2>/dev/null | tr -d '\r' | grep -q ": found"; then
      return 0
    fi
    sleep 5
  done
  # Service never came back: restart the Android runtime once (emulator is
  # rooted; stop/start respawns zygote + system_server), then poll again.
  echo "DIAG: package service lost - restarting Android runtime"
  "${ADB[@]}" root >/dev/null 2>&1 || true
  sleep 2
  "${ADB[@]}" shell stop >/dev/null 2>&1 || true
  sleep 5
  "${ADB[@]}" shell start >/dev/null 2>&1 || true
  "${ADB[@]}" wait-for-device
  for i in $(seq 1 40); do
    BC=$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [ "$BC" = "1" ] && "${ADB[@]}" shell service check package 2>/dev/null | tr -d '\r' | grep -q ": found"; then
      echo "DIAG: runtime restart recovered package service"
      return 0
    fi
    sleep 5
  done
  return 1
}

# Keep the watchdog from touching anything while the package manager works.
touch "$NOSWEEP"
# Install + verify in ONE loop: adb install can report Success and the
# package still vanish if system_server restarts right after (packages.xml
# lost with the dying process). Only pm path proving the package is
# registered counts as success; otherwise reinstall.
INSTALL_OK=0
for attempt in 1 2 3 4 5 6 7 8; do
  "${ADB[@]}" wait-for-device
  wait_pkg_service || { echo "DIAG: package service never came up"; exit 1; }
  "${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk || true
  sleep 4
  if "${ADB[@]}" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | grep -q "package:"; then
    INSTALL_OK=1
    break
  fi
  echo "DIAG: install attempt $attempt did not stick (service: $("${ADB[@]}" shell service check package 2>/dev/null | tr -d '\r')); reinstalling"
  sleep 8
done
rm -f "$NOSWEEP"
[ "$INSTALL_OK" = 1 ] || { echo "DIAG: apk install never stuck after 8 attempts"; exit 1; }
echo "DIAG: installed: $("${ADB[@]}" shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 versionName)"
"${ADB[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tr -d '\r' | tail -3

# Permissions must exist before the launcher queries providers.
for perm in CALL_PHONE READ_CONTACTS READ_CALL_LOG READ_SMS SEND_SMS; do
  for g in 1 2 3; do
    "${ADB[@]}" shell pm grant "$PKG" "android.permission.$perm" 2>/dev/null && break
    echo "DIAG: pm grant $perm attempt $g failed; waiting for package service"
    wait_pkg_service || true
    sleep 4
  done
done

# am start can itself hit a mid-restart system_server (proven: NPE in
# ActivityStarter, exit 255); retry the launch until we are foreground.
LAUNCH_OK=0
for attempt in 1 2 3 4 5; do
  "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  sleep 5
  FG=$("${ADB[@]}" shell "dumpsys activity activities | grep -m1 ResumedActivity" | tr -d '\r')
  echo "DIAG foreground: $FG"
  case "$FG" in *"$PKG"*) LAUNCH_OK=1; break ;; esac
  echo "DIAG: launcher not foreground (attempt $attempt); retrying"
  sleep 4
done
[ "$LAUNCH_OK" = 1 ] || { echo "DIAG: launcher not foreground after am start retries - aborting"; exit 1; }
# Keypad geometry from a real frame: PNG screencap decoded in pure python
# (zlib + unfiltering). Keypad bg is dark slate, nav bar is pure black, and
# the softkey strip above the keypad is near-white. (uiautomator can't see
# the keypad: the custom views are accessibility-pruned; raw screencap
# format proved unreliable. This detector is verified offline against real
# proof screenshots.)
KT=""; KH=""
for i in $(seq 1 10); do
  anr_sweep || true
  sleep 2
  "${ADB[@]}" exec-out screencap -p > /tmp/reborn_geo.png 2>/dev/null || true
  if GEO=$(python3 - /tmp/reborn_geo.png 2>/tmp/reborn_geo.err <<'PY'
import struct, sys, zlib
d = open(sys.argv[1], 'rb').read()
if d[:8] != bytes.fromhex('89504e470d0a1a0a'):
    print('REASON bad-png', file=sys.stderr)
    sys.exit(1)
pos = 8
idat = b''
w = h = None
ctype = None
while pos < len(d):
    ln, typ = struct.unpack('>I4s', d[pos:pos+8])
    chunk = d[pos+8:pos+8+ln]
    if typ == b'IHDR':
        w, h, depth, ctype = struct.unpack('>IIBB', chunk[:10])
    elif typ == b'IDAT':
        idat += chunk
    elif typ == b'IEND':
        break
    pos += 12 + ln
if w is None or not idat:
    sys.exit(1)
raw = zlib.decompress(idat)
channels = {0:1, 2:3, 3:1, 4:2, 6:4}.get(ctype)
if channels is None or depth != 8:
    sys.exit(1)
stride = w * channels
out = bytearray(h * stride)
prev = bytearray(stride)
p = 0
for y in range(h):
    f = raw[p]; p += 1
    line = bytearray(raw[p:p+stride]); p += stride
    if f == 1:
        for i in range(channels, stride):
            line[i] = (line[i] + line[i-channels]) & 255
    elif f == 2:
        for i in range(stride):
            line[i] = (line[i] + prev[i]) & 255
    elif f == 3:
        for i in range(stride):
            a = line[i-channels] if i >= channels else 0
            line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
    elif f == 4:
        for i in range(stride):
            a = line[i-channels] if i >= channels else 0
            b = prev[i]
            c = prev[i-channels] if i >= channels else 0
            pp = a + b - c
            pa, pb, pc = abs(pp-a), abs(pp-b), abs(pp-c)
            pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
            line[i] = (line[i] + pr) & 255
    out[y*stride:(y+1)*stride] = line
    prev = line
def lum(x, y):
    o = y * stride + x * channels
    if channels >= 3:
        return (out[o] + out[o+1] + out[o+2]) // 3
    return out[o]
x = w // 2
run = 0
KT = None
for y in range(h // 4, h):
    if lum(x, y) < 80:
        run += 1
        if run >= 100:
            KT = y - 99
            break
    else:
        run = 0
if KT is None:
    print('REASON no-dark-keypad-run', file=sys.stderr)
    sys.exit(1)
y = h - 1
nx = w // 16
while y > KT and lum(nx, y) < 8:
    y -= 1
NT = y + 1
if NT >= h - 2:
    # no pure-black nav bar found: something (ANR dialog) covers the frame
    print('REASON no-nav-bar', file=sys.stderr)
    sys.exit(1)
if NT < KT + 200:
    print('REASON nav-inside-keypad', file=sys.stderr)
    sys.exit(1)
# keypad bottom padding must still be dark slate (dialog would be light)
if lum(x, NT - 10) > 80:
    print('REASON light-keypad-bottom', file=sys.stderr)
    sys.exit(1)
print(KT, NT - KT)
PY
); then
    read -r KT KH <<< "$GEO"
    [ -n "$KT" ] && [ -n "$KH" ] && break
  fi
  echo "DIAG: geometry detection failed (attempt $i): $(cat /tmp/reborn_geo.err 2>/dev/null)"
  cp /tmp/reborn_geo.png "$SCREEN_DIR/00-geo-fail-$i.png" 2>/dev/null || true
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

# The bridge hands foreground to the stock dialer; a single BACK keyevent is
# not a reliable return (proven: later taps dialed 123 for real inside the
# stock dialer). Re-foreground our launcher explicitly instead.
fg_ours() {
  for try in 1 2 3; do
    # the call sequence can leave the display off (proven: post-call shots
    # were pure black); wake and dismiss a possible keyguard first
    "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
    "${ADB[@]}" shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) >/dev/null 2>&1 || true
    sleep 1
    WAKE=$("${ADB[@]}" shell "dumpsys power | grep -m1 mWakefulness" | tr -d '\r')
    echo "DIAG power: $WAKE"
    if ! echo "$WAKE" | grep -q "Awake"; then
      echo "still asleep - toggling POWER"
      "${ADB[@]}" shell input keyevent KEYCODE_POWER >/dev/null 2>&1 || true
      sleep 1
      "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
      "${ADB[@]}" shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) >/dev/null 2>&1 || true
      sleep 1
      echo "DIAG power after toggle: $("${ADB[@]}" shell "dumpsys power | grep -m1 mWakefulness" | tr -d '\r')"
    fi
    "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 4
    FG=$("${ADB[@]}" shell "dumpsys activity activities | grep -m1 ResumedActivity" | tr -d '\r')
    echo "DIAG foreground: $FG"
    case "$FG" in *"$PKG"*) return 0 ;; esac
    sleep 3
  done
  return 1
}
fg_ours || { echo "DIAG: launcher not foreground after dial bridge - aborting"; exit 1; }

# Real inbound call through the emulator modem. The incoming-call UI is the
# stock AVD dialer; the watchdog's ANR kill/tap can dismiss it, so sweeping
# is paused for this window.
touch "$NOSWEEP"
emu_console 'gsm call +15557654321'
sleep 6
shot 08-incoming-call
emu_console 'gsm cancel +15557654321'
# the stock dialer holds the screen off via the proximity wake lock even after
# the call ends; report "far" so wakeup/POWER can relight the display
emu_console 'sensor set proximity 10' || true
sleep 2
rm -f "$NOSWEEP"
sleep 2
fg_ours || { echo "DIAG: launcher not foreground after incoming call - aborting"; exit 1; }

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
# Multitap "hi": 4 4 (commit window) then 4 4 4 (commit window). Same-key
# presses go in ONE adb shell: separate adb calls take >1s each and blew the
# app's 1100ms commit window (proven: shot read "ggggg" instead of "hi").
read -r D4X D4Y < <(tapf $D4)
# Even within one adb shell, each 'input tap' spawn costs >1.1s on this
# emulator (proven: sequential in-shell taps still read "ggggg"). Run the
# same-key presses CONCURRENTLY so both land inside the 1100ms window.
"${ADB[@]}" shell "input tap $D4X $D4Y & sleep 0.2; input tap $D4X $D4Y & wait"
sleep 2
"${ADB[@]}" shell "input tap $D4X $D4Y & sleep 0.2; input tap $D4X $D4Y & sleep 0.2; input tap $D4X $D4Y & wait"
sleep 2
shot 11-compose-text
# Send is the left softkey on the compose screen, not CENTER. Decisive taps
# get lost under emulator load; retry until the frame actually changes.
tap_key LSK; sleep 3; shot 12-sent
for r in 1 2 3; do
  if cmp -s "$SCREEN_DIR/11-compose-text.png" "$SCREEN_DIR/12-sent.png"; then
    echo "DIAG: send tap $r did not register; retrying"
    tap_key LSK; sleep 3; shot 12-sent
  else
    break
  fi
done

cp "$PROOF_LOG" "$SCREEN_DIR/proof-log.txt"
echo "proof complete"

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
# Trace every command: this image keeps dying silently in different places;
# xtrace turns each silent exit into a diagnosed one in the job log.
set -x

"${ADB[@]}" wait-for-device
until [ "$("${ADB[@]}" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
  sleep 3
done
sleep 5

# The API-30 AVD's SystemUI hangs and its modal ANR dialog eats every tap
# (proven on the F21 image): kill it and let a healthy instance restart.
# API-30-specific: on API 33 this kill triggers a system ANR that the
# watchdog escalates into a runtime restart (proven: run 35836613774).
"${ADB[@]}" root >/dev/null 2>&1 || true
sleep 3
"${ADB[@]}" wait-for-device
SDK=$("${ADB[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')
echo "DIAG: emulator sdk is $SDK"
if [ "$SDK" = "30" ]; then
  for p in com.android.systemui com.android.settings; do
    pid=$("${ADB[@]}" shell pidof "$p" 2>/dev/null | tr -d '\r') || true
    [ -n "$pid" ] && "${ADB[@]}" shell kill "$pid" 2>/dev/null || true
  done
  sleep 6
fi


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

# Wait out a dead/restarting Android runtime: after the watchdog kills
# system_server the activity manager is gone for tens of seconds, and any
# am/pm/dumpsys call in that window fails (proven: exit 20 on am start).
wait_runtime() {
  for i in $(seq 1 40); do
    BC=$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [ "$BC" = "1" ] && "${ADB[@]}" shell service check package 2>/dev/null | tr -d '\r' | grep -q ": found"; then
      return 0
    fi
    sleep 5
  done
  return 1
}
anr_sweep() {
  [ -f "$NOSWEEP" ] && return 0
  WIN=$("${ADB[@]}" shell "dumpsys window windows" 2>/dev/null | tr -d '\r') || return 0
  echo "$WIN" | grep -qi "Not Responding" || return 0
  # NB: never reuse $PKG here - this watchdog runs in the same shell as the
  # proof script and clobbering PKG retargets every later launch at the
  # ANR'd package (proven: am start went to com.android.systemui/.MainActivity).
  ANRPKG=$(echo "$WIN" | grep -oiE "(Not Responding|Application Error): *[a-zA-Z0-9._]+" | head -1 | sed -E 's/.*: *//')
  # Never kill system-critical packages: killing settings/systemui mid-boot
  # crashes system_server and takes the package service down with it (seen
  # as "Can't find service: package" at install). Those get the Wait tap only.
  case "$ANRPKG" in
    system|system_server)
      # A wedged system_server never recovers on its own; killing it makes
      # the Android runtime restart (zygote respawns it). This IS the fix.
      pid=$("${ADB[@]}" shell pidof system_server 2>/dev/null | tr -d '\r')
      [ -n "$pid" ] && "${ADB[@]}" shell kill -9 "$pid" 2>/dev/null
      echo "watchdog: system_server wedged - forced runtime restart"
      wait_runtime
      ;;
    ""|android|com.android.systemui|com.android.settings|com.android.phone|com.android.providers*|com.android.server*)
      : ;;
    *)
      pid=$("${ADB[@]}" shell pidof "$ANRPKG" 2>/dev/null | tr -d '\r')
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
  echo "watchdog: swept ANR ($ANRPKG)"
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
CALL="0.165 0.452"; END="0.835 0.452"; LSK="0.165 0.075"; RSK="0.835 0.075"

# Log the modem call state (0=idle, 1=ringing, 2=offhook) at key moments.
diag_callstate() {
  "${ADB[@]}" shell "dumpsys telephony.registry 2>/dev/null | grep -m1 mCallState" | tr -d '\r' | sed "s/^/DIAG callstate $1: /"
}
D1="0.165 0.586"; D2="0.500 0.586"; D3="0.835 0.586"
D4="0.165 0.699"; D5="0.500 0.699"; D6="0.835 0.699"
D7="0.165 0.811"; D8="0.500 0.811"; D9="0.835 0.811"
D0="0.500 0.924"; STAR="0.165 0.924"; HASH="0.835 0.924"

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

ensure_fg() {
  # Re-foreground the launcher when a heads-up notification or keyguard
  # stole it; taps are keypad-relative and land on whatever is on screen.
  FG=$("${ADB[@]}" shell "dumpsys activity activities 2>/dev/null | awk '/ResumedActivity/ && !v {print; v=1}'" | tr -d '\r')
  case "$FG" in *"$PKG"*) return 0 ;; esac
  echo "DIAG: foreground is [$FG] - re-foregrounding launcher"
  if [ -z "$FG" ]; then
    # Empty dumpsys: the activity manager is down (runtime restarting).
    # Ride it out instead of letting am start kill the script (exit 20).
    echo "DIAG: activity manager down - waiting for the runtime"
    wait_runtime || true
  fi
  "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
  "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  sleep 4
  FG=$("${ADB[@]}" shell "dumpsys activity activities 2>/dev/null | awk '/ResumedActivity/ && !v {print; v=1}'" | tr -d '\r')
  echo "DIAG foreground after ensure: $FG"
}
fresh() {
  # Menu selection is session-preserved (sim lastMenuSel), so menu-relative
  # nav must start from a known state: relaunch -> idle, selection row 0.
  "${ADB[@]}" shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 2
  "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  sleep 5
  ensure_fg
  sleep 2
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
echo "DIAG: installed: $("${ADB[@]}" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | awk '/versionName/ && !v {print; v=1}' || echo unreadable)"
"${ADB[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | tr -d '\r' | tail -3

# Permissions must exist before the launcher queries providers.
for perm in CALL_PHONE READ_CONTACTS READ_CALL_LOG READ_SMS SEND_SMS READ_PHONE_STATE ANSWER_PHONE_CALLS; do
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
  FG=$("${ADB[@]}" shell "dumpsys activity activities 2>/dev/null | awk '/ResumedActivity/ && !v {print; v=1}'" | tr -d '\r')
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

# Raw touchscreen device for timing-critical taps: 'input tap' spawns cost
# >1s each on this emulator, which breaks multitap windows. sendevent
# spawns are ~30ms, so same-key presses land within the app's window.
RAWDEVS=$("${ADB[@]}" shell getevent -pl 2>/dev/null | tr -d '\r' | awk '/^add device/{dev=$NF} /ABS_MT_TRACKING_ID/{print dev}' | awk '!seen[$0]++')
echo "DIAG: raw touch devices: ${RAWDEVS:-none}"
raw_seq() { # dev x y -> full type-B touch sequence (slot, tracking, position,
  # pressure, BTN_TOUCH, SYN; the minimal sequence was ignored by this driver)
  printf 'sendevent %s 3 47 0; sendevent %s 3 57 100; sendevent %s 3 53 %s; sendevent %s 3 54 %s; sendevent %s 3 58 50; sendevent %s 0 0 2; sendevent %s 1 330 1; sendevent %s 0 0 0; sleep 0.05; sendevent %s 3 57 4294967295; sendevent %s 0 0 2; sendevent %s 1 330 0; sendevent %s 0 0 0' \
    "$1" "$1" "$1" "$2" "$1" "$3" "$1" "$1" "$1" "$1" "$1" "$1" "$1" "$1"
}
echo "keypad top $KT height $KH"

shot 01-idle-keypad

tap_key CENTER; sleep 2; shot 02-menu

# Messaging -> Conversations before any SMS arrives. Sim menu order:
# Messaging is grid index 4 (RIGHT then DOWN from Contacts).
tap_key RIGHT; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 03-messaging-list
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 04-conversations-empty

# A real inbound SMS through the emulator modem.
emu_console 'sms send +15551234567 Reborn proof OK'
sleep 4

# Back to Messaging -> Conversations: message must be listed. Lost taps
# and stolen foreground have derailed this chain (proven: shot 05 once
# landed in system Display settings), so verify every hop changed the
# frame and rewalk the chain when one did not.
sms_walk() {
  ensure_fg
fresh
  tap_key END; sleep 1
  tap_key CENTER; sleep 2; shot probe-b-menu
  tap_key RIGHT; sleep 1
  tap_key DOWN; sleep 1
  tap_key CENTER; sleep 2; shot probe-b-mlist
  tap_key DOWN; sleep 1; shot probe-b-msel
  tap_key CENTER; sleep 2; shot probe-b-conv
}
sms_walk
for attempt in 1 2; do
  bad=""
  cmp -s "$SCREEN_DIR/probe-b-menu.png" "$SCREEN_DIR/probe-b-mlist.png" && bad="$bad mlist"
  cmp -s "$SCREEN_DIR/probe-b-mlist.png" "$SCREEN_DIR/probe-b-msel.png" && bad="$bad select-down"
  cmp -s "$SCREEN_DIR/probe-b-msel.png" "$SCREEN_DIR/probe-b-conv.png" && bad="$bad conversations"
  [ -z "$bad" ] && break
  echo "DIAG: sms walk attempt $attempt lost hop(s):$bad - rewalking"
  sms_walk
done
cp "$SCREEN_DIR/probe-b-conv.png" "$SCREEN_DIR/05-sms-inbox.png"
rm -f "$SCREEN_DIR"/probe-b-*.png

# Dialer: END home, digits 1 2 3, green key places a REAL call now
# (ACTION_CALL with CALL_PHONE granted); the emulator modem connects it.
tap_key END; sleep 1
tap_key D1; sleep 1
tap_key D2; sleep 1
tap_key D3; sleep 1; shot 06-dialer
tap_key CALL; sleep 5
diag_callstate "after dial"
shot 07-dial-started

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
    WAKE=$("${ADB[@]}" shell "dumpsys power | awk '/mWakefulness/ && !v {print; v=1}'" | tr -d '\r')
    echo "DIAG power: $WAKE"
    if ! echo "$WAKE" | grep -q "Awake"; then
      echo "still asleep - toggling POWER"
      "${ADB[@]}" shell input keyevent KEYCODE_POWER >/dev/null 2>&1 || true
      sleep 1
      "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
      "${ADB[@]}" shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) >/dev/null 2>&1 || true
      sleep 1
      echo "DIAG power after toggle: $("${ADB[@]}" shell "dumpsys power | awk '/mWakefulness/ && !v {print; v=1}'" | tr -d '\r')"
    fi
    "${ADB[@]}" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 4
    FG=$("${ADB[@]}" shell "dumpsys activity activities 2>/dev/null | awk '/ResumedActivity/ && !v {print; v=1}'" | tr -d '\r')
    echo "DIAG foreground: $FG"
    case "$FG" in *"$PKG"*) return 0 ;; esac
    sleep 3
  done
  return 1
}
# the stock telecom UI can hold the screen off via the proximity wake lock
emu_console 'sensor set proximity 10' || true
sleep 1
fg_ours || { echo "DIAG: launcher not foreground after dial - aborting"; exit 1; }

# Our launcher in the foreground shows the C2 incall screen for the live
# call: number, ticking timer, Options/Menu/Loudsp. softkeys.
fresh
shot 07a-incall
tap_key RSK; sleep 2; shot 07b-incall-loudsp
# Red key ends the real call through TelecomManager; the telephony callback
# then shows 'Call ended' on the idle screen.
tap_key END; sleep 3
diag_callstate "after end"
shot 07c-call-ended

# Real inbound call through the emulator modem. The incoming-call UI is the
# stock AVD dialer; the watchdog's ANR kill/tap can dismiss it, so sweeping
# is paused for this window.
touch "$NOSWEEP"
emu_console 'gsm call +15557654321'
sleep 6
shot 08-incoming-call
# Answer through the modem: our launcher foreground shows the C2 incall
# screen with the ringing number, and our red key ends the call.
emu_console 'gsm accept +15557654321'
sleep 3
emu_console 'sensor set proximity 10' || true
sleep 1
fg_ours || { echo "DIAG: launcher not foreground after answering - aborting"; exit 1; }
shot 08a-incall-answered
tap_key END; sleep 3
diag_callstate "after answered end"
shot 08b-answered-ended
emu_console 'gsm cancel +15557654321' || true
sleep 2
rm -f "$NOSWEEP"
sleep 2
fg_ours || { echo "DIAG: launcher not foreground after incoming call - aborting"; exit 1; }

# Call log: the answered-then-ended modem call must be listed.
fresh
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 09-calllog

# Compose with multitap: Messaging -> Create message -> Message -> number
# -> text -> send. Same lost-tap hardening as the sms chain (proven: a
# lost CENTER once stranded the flow on the submenu; the following DOWN
# then opened Flash message instead of the composer).
compose_walk() {
  ensure_fg
fresh
  tap_key END; sleep 1
  tap_key CENTER; sleep 2; shot probe-c-menu
  tap_key RIGHT; sleep 1
  tap_key DOWN; sleep 1
  tap_key CENTER; sleep 2; shot probe-c-mlist
  tap_key CENTER; sleep 2; shot probe-c-submenu
  # Sim Create message submenu: row 0 "Message" opens the composer.
  tap_key CENTER; sleep 2; shot probe-c-composer
}
compose_walk
for attempt in 1 2; do
  bad=""
  cmp -s "$SCREEN_DIR/probe-c-menu.png" "$SCREEN_DIR/probe-c-mlist.png" && bad="$bad mlist"
  cmp -s "$SCREEN_DIR/probe-c-mlist.png" "$SCREEN_DIR/probe-c-submenu.png" && bad="$bad submenu"
  cmp -s "$SCREEN_DIR/probe-c-submenu.png" "$SCREEN_DIR/probe-c-composer.png" && bad="$bad composer"
  [ -z "$bad" ] && break
  echo "DIAG: compose walk attempt $attempt lost hop(s):$bad - rewalking"
  compose_walk
done
# The first digit must change the frame; if it does not we are not on the
# composer and the compose shots below are evidence, not proof.
tap_key D0; sleep 1; shot probe-c-digit
if cmp -s "$SCREEN_DIR/probe-c-composer.png" "$SCREEN_DIR/probe-c-digit.png"; then
  echo "DIAG: first digit did not land - compose chain still derailed"
fi
rm -f "$SCREEN_DIR"/probe-c-*.png
for d in D7 D7 D0 D0 D9 D0 D0 D1 D2 D3; do tap_key "$d"; sleep 1; done
shot 10-compose-number
# Unified composer: DOWN moves focus from To: to Text: (sim behaviour).
tap_key DOWN; sleep 1
# Multitap "hi": 4 4 (commit window) then 4 4 4 (commit window). Same-key
# presses go in ONE adb shell: separate adb calls take >1s each and blew the
# app's 1100ms commit window (proven: shot read "ggggg" instead of "hi").
read -r D4X D4Y < <(tapf $D4)
# Even within one adb shell, each 'input tap' spawn costs >1.1s on this
# emulator (proven: sequential in-shell taps still read "ggggg"). Run the
# same-key presses CONCURRENTLY so both land inside the multitap window.
# Capture what a real 'input tap' emits so the sendevent path is diagnosable
# from the published proof log if it still misses.
"${ADB[@]}" shell "EV=/data/local/tmp/evcap.txt; getevent -lt -c 40 > \$EV 2>&1 & GPID=\$!; sleep 0.6; input tap $D4X $D4Y >/dev/null 2>&1; sleep 1.2; kill \$GPID 2>/dev/null; cat \$EV 2>/dev/null" | awk 'NR<=40{print "DIAG evcap: " $0}' || true
if [ -n "$RAWDEVS" ]; then
  # Full type-B sequence on every MT-capable device (usually exactly one).
  # ~30ms per sendevent, presses 350ms apart: well inside the 1600ms window.
  for DEV in $RAWDEVS; do
    "${ADB[@]}" shell "$(raw_seq $DEV $D4X $D4Y); sleep 0.35; $(raw_seq $DEV $D4X $D4Y)"
  done
  sleep 2
  for DEV in $RAWDEVS; do
    "${ADB[@]}" shell "$(raw_seq $DEV $D4X $D4Y); sleep 0.35; $(raw_seq $DEV $D4X $D4Y); sleep 0.35; $(raw_seq $DEV $D4X $D4Y)"
  done
  sleep 2
else
  "${ADB[@]}" shell "input tap $D4X $D4Y & sleep 0.2; input tap $D4X $D4Y & wait"
  sleep 2
  "${ADB[@]}" shell "input tap $D4X $D4Y & sleep 0.2; input tap $D4X $D4Y & sleep 0.2; input tap $D4X $D4Y & wait"
  sleep 2
fi
shot 11-compose-text
# Send is the CENTER key on the unified compose screen (sim: centre = Send
# when a recipient is present). Decisive taps get lost under emulator load;
# retry until the frame actually changes.
tap_key CENTER; sleep 3; shot 12-sent
for r in 1 2 3; do
  if cmp -s "$SCREEN_DIR/11-compose-text.png" "$SCREEN_DIR/12-sent.png"; then
    echo "DIAG: send tap $r did not register; retrying"
    tap_key CENTER; sleep 3; shot 12-sent
  else
    break
  fi
done
if cmp -s "$SCREEN_DIR/11-compose-text.png" "$SCREEN_DIR/12-sent.png"; then
  echo "DIAG: send never registered; Reborn logcat:"
  "${ADB[@]}" shell logcat -d -s Reborn:* 2>/dev/null | tr -d '\r' | tail -8
fi

# Alarm clock: Go to -> Alarm clock -> time editor -> save 07:30.
tap_key END; sleep 1
tap_key LSK; sleep 2; shot 13-goto
tap_key DOWN; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 14-alarm
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 15-alarm-edit
for i in 1 2 3 4; do tap_key RSK; sleep 1; done
tap_key D0; sleep 1
tap_key D7; sleep 1
tap_key D3; sleep 1
tap_key D0; sleep 1
tap_key CENTER; sleep 2; shot 16-alarm-set
tap_key END; sleep 1

# Calculator: Go to row 5 -> 12 + 3 = 15 (nav keys are the operators).
tap_key LSK; sleep 2
for i in 1 2 3 4 5; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 17-calc
tap_key D1; sleep 1
tap_key D2; sleep 1
tap_key UP; sleep 1
tap_key D3; sleep 1
tap_key CENTER; sleep 2; shot 18-calc-result
tap_key END; sleep 1

# Camera: Go to row 3 -> demo preview -> Capture -> options -> Settings detail.
tap_key LSK; sleep 2
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 19-camera
tap_key CENTER; sleep 2; shot 20-camera-capture
tap_key LSK; sleep 2; shot 21-camera-options
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 22-camera-settings
tap_key END; sleep 1

# Video recorder: Go to row 4 -> demo preview -> Record -> Stop.
tap_key LSK; sleep 2
for i in 1 2 3 4; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 23-video
tap_key CENTER; sleep 2; shot 24-video-recording
tap_key CENTER; sleep 2; shot 25-video-saved
tap_key END; sleep 1

# Nokia Browser: Go to row 6 -> home page -> Bookmarks -> Go to address -> Downloads.
tap_key LSK; sleep 2
for i in 1 2 3 4 5 6; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 26-browser
tap_key LSK; sleep 2
tap_key DOWN; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 27-browser-bookmarks
tap_key LSK; sleep 2
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 28-urlentry
tap_key RSK; sleep 2
tap_key LSK; sleep 2
for i in 1 2 3 4 5; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 29-appdownloads
tap_key END; sleep 1

# Media player: Go to row 7 -> player -> Music library -> All songs -> Play -> Equaliser.
tap_key LSK; sleep 2
for i in 1 2 3 4 5 6 7; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 30-player
tap_key LSK; sleep 2
tap_key CENTER; sleep 2; shot 31-mediamenu
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 32-allsongs
tap_key CENTER; sleep 3; shot 33-player-playing
tap_key LSK; sleep 2
for i in 1 2 3 4; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 34-equaliser
fresh
tap_key END; sleep 1

# Radio + Voice recorder: menu -> Media -> Radio -> Play -> back -> Voice recorder -> Record -> Stop.
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 35-radio
tap_key CENTER; sleep 2; shot 36-radio-playing
tap_key RSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 37-voicerec
tap_key CENTER; sleep 3; shot 38-voicerec-recording
tap_key CENTER; sleep 2; shot 39-voicerec-saved
fresh
tap_key END; sleep 1

# Maps + Stopwatch: menu -> Organiser -> Maps -> Stopwatch start/stop -> Split timing.
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 40-maps
tap_key RSK; sleep 2
for i in 1 2 3 4 5; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 41-stopwatch
tap_key CENTER; sleep 3; shot 42-stopwatch-running
tap_key CENTER; sleep 2; shot 43-stopwatch-stopped
tap_key LSK; sleep 2
tap_key CENTER; sleep 2; shot 44-splittiming
tap_key END; sleep 1

# Media + Apps list pages: menu -> Media (list shot) and menu -> Apps. (list shot).
fresh
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2; shot 45-medialist
fresh
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1; tap_key RIGHT; sleep 1; tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2; shot 46-appslist
fresh
tap_key END; sleep 1

# Media Options -> Settings itemdetail; Apps Options -> Memory status subtree
# -> Add folder (folder-name page) with the empty-name notice.
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
tap_key LSK; sleep 2; shot 47-media-options
tap_key DOWN; sleep 1
tap_key DOWN; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 48-media-settings
tap_key RSK; sleep 2
fresh
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1; tap_key RIGHT; sleep 1; tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
tap_key LSK; sleep 2; shot 49-apps-options
for i in 1 2 3 4 5; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 50-memorystatus
tap_key CENTER; sleep 2; shot 51-phonememory
tap_key RSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 52-memcard
tap_key RSK; sleep 2
tap_key RSK; sleep 2
tap_key LSK; sleep 2
for i in 1 2 3 4; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 53-foldername
tap_key CENTER; sleep 2; shot 54-foldername-notice
fresh
tap_key END; sleep 1

# Organiser subtree: Calendar -> note types -> editor -> saved note -> view;
# To-do -> editor -> saved; Notes -> editor -> saved -> view -> list.
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 55-calendar-empty
tap_key CENTER; sleep 2; shot 56-caltypes
tap_key CENTER; sleep 2; shot 57-calnote
tap_key D6; sleep 2
tap_key D3; sleep 2
tap_key D6; sleep 2
tap_key D6; sleep 2
shot 58-calnote-typed
tap_key CENTER; sleep 2; shot 59-calendar-list
tap_key CENTER; sleep 2; shot 60-calview
tap_key RSK; sleep 2
tap_key RSK; sleep 2
tap_key DOWN; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 61-todolist-empty
tap_key CENTER; sleep 2; shot 62-todoedit
tap_key D5; sleep 2
tap_key D2; sleep 2
tap_key D6; sleep 2
shot 63-todoedit-typed
tap_key CENTER; sleep 2; shot 64-todolist
tap_key RSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 65-notes-empty
tap_key CENTER; sleep 2; shot 66-textnote
tap_key D5; sleep 2
tap_key D2; sleep 2
tap_key D6; sleep 2
tap_key CENTER; sleep 2; shot 67-noteview
tap_key RSK; sleep 2; shot 68-notes-list
fresh
tap_key END; sleep 1

# Countdown timer subtree: menu -> Normal timer -> fields -> note -> start;
# Interval timer -> start; Timer settings.
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
for i in 1 2 3 4 5 6; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 69-countdown
tap_key CENTER; sleep 2; shot 70-normaltimer
tap_key D1; sleep 1
tap_key D5; sleep 1
shot 71-normaltimer-typed
tap_key CENTER; sleep 2; shot 72-timernote
tap_key CENTER; sleep 2; shot 73-organiser-countdown
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 74-intervaltimer
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 75-interval-running
tap_key RSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 76-cdsettings
fresh
tap_key END; sleep 1

# Note editor sub-pages: editing options (Copy all -> Paste), writing language,
# prediction options; saved numeric note -> use detail, send note, BT prompt.
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
for i in 1 2 3 4; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2
tap_key LSK; sleep 2
tap_key CENTER; sleep 2
for i in 1 2 3 4; do tap_key D2; sleep 1; done
for i in 1 2 3 4; do tap_key D3; sleep 1; done
for i in 1 2 3 4; do tap_key D4; sleep 1; done
for i in 1 2 3 4; do tap_key D5; sleep 1; done
sleep 2
tap_key LSK; sleep 2
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 77-editing-options
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 78-copyall-notice
tap_key LSK; sleep 2
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2
for i in 1 2 3; do tap_key DOWN; sleep 1; done
shot 79-editing-paste-row
tap_key CENTER; sleep 2; shot 80-pasted
tap_key LSK; sleep 2
for i in 1 2 3 4; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 81-writing-language
tap_key RSK; sleep 2
tap_key LSK; sleep 2
for i in 1 2 3 4 5; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 82-prediction
tap_key RSK; sleep 2
tap_key CENTER; sleep 2
tap_key LSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 83-usedetail
tap_key CENTER; sleep 2; shot 84-usedetailnumber
tap_key RSK; sleep 2
tap_key RSK; sleep 2
tap_key LSK; sleep 2
for i in 1 2; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 85-sendnote
tap_key CENTER; sleep 3; shot 86-composer-prefilled
fresh
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
for i in 1 2 3 4; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2
tap_key CENTER; sleep 2
tap_key LSK; sleep 2
for i in 1 2; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 87-bluetooth-prompt
tap_key RSK; sleep 2
fresh
tap_key END; sleep 1

# Symbol flyout + picker + note mark mode (184): textnote editor -> flyout ->
# emoji grid -> back -> character grid -> insert; editing options -> Copy mark
# mode; composer 'Insert symbol' -> character grid (no flyout).
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2
for i in 1 2 3 4; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2
tap_key LSK; sleep 2
tap_key CENTER; sleep 2
tap_key LSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 88-symbol-flyout
tap_key CENTER; sleep 2; shot 89-emoji-grid
tap_key RSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 90-symbol-grid
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2; shot 91-symbol-inserted
tap_key LSK; sleep 2
for i in 1 2 3; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2
tap_key CENTER; sleep 2; shot 92-mark-dialog
tap_key CENTER; sleep 1
tap_key RIGHT; sleep 1
tap_key CENTER; sleep 2; shot 93-mark-copy
fresh
tap_key END; sleep 1
tap_key CENTER; sleep 2
tap_key RIGHT; sleep 1
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2
tap_key CENTER; sleep 2
tap_key CENTER; sleep 2
tap_key DOWN; sleep 1
tap_key LSK; sleep 2
for i in 1 2 3 4 5 6 7; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 94-composer-symbol-grid
tap_key CENTER; sleep 2; shot 95-composer-symbol-inserted
# (185) Composer quirks: 'Editing options' shows only a notice;
# 'Writing language' opens the shared list with Back -> composer.
tap_key LSK; sleep 2
for i in 1 2 3 4 5 6 7 8; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 96-composer-editing-notice
tap_key LSK; sleep 2
for i in 1 2 3 4 5 6 7 8 9; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2; shot 97-composer-writing-language
tap_key RSK; sleep 2
tap_key END; sleep 1

# (186) Scientific + loan calculator: calc options -> Scientific (sin 90 = 1);
# Loan intro -> form 20000/5/24 -> Calculate monthly instalment.
tap_key LSK; sleep 2
for i in 1 2 3 4 5; do tap_key DOWN; sleep 1; done
tap_key CENTER; sleep 2
tap_key LSK; sleep 2
tap_key CENTER; sleep 2; shot 98-scientific
tap_key D9; sleep 1
tap_key D0; sleep 1
tap_key CENTER; sleep 2; shot 99-scientific-sin
tap_key LSK; sleep 2
tap_key DOWN; sleep 1
tap_key CENTER; sleep 2; shot 100-loanintro
tap_key CENTER; sleep 2; shot 101-loancalc
tap_key D2; sleep 1
tap_key D0; sleep 1
tap_key D0; sleep 1
tap_key D0; sleep 1
tap_key D0; sleep 1
tap_key DOWN; sleep 1
tap_key D5; sleep 1
tap_key DOWN; sleep 1
tap_key D2; sleep 1
tap_key D4; sleep 1
tap_key DOWN; sleep 1
shot 102-loancalc-ready
tap_key CENTER; sleep 2; shot 103-loancalc-result
tap_key END; sleep 1

# (187) Keypad lock: Go to row 0 -> locked; Unlock -> 'Now press *' notice;
# wrong key -> 'Keypad locked' notice; Unlock + * -> 'Keypad unlocked'.
tap_key LSK; sleep 2
tap_key CENTER; sleep 2; shot 104-lockscreen
tap_key LSK; sleep 2; shot 105-lock-now-press
tap_key D5; sleep 2; shot 106-lock-wrong-key
tap_key LSK; sleep 1
tap_key STAR; sleep 2; shot 107-unlocked
tap_key END; sleep 1

cp "$PROOF_LOG" "$SCREEN_DIR/proof-log.txt"
echo "proof complete"

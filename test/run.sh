#!/usr/bin/env bash
# Runs the script tests against a stub adb. No TV needed.
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

export PATH="$root/test/stub:$PATH"
export TVS_CONF="$tmp/tvs.conf"
export STUB_LOG="$tmp/adb.log"
export TV_BACKUPS="$tmp/backups"

pass=0
fail=0

# run <cmd...>: captures combined output in $out and exit code in $code
run() {
  out="$("$@" 2>&1)"
  code=$?
}

ok() { pass=$((pass + 1)); echo "ok   - $1"; }
not_ok() { fail=$((fail + 1)); echo "FAIL - $1"; echo "$out" | sed 's/^/       /'; }

expect() { # expect <description> <condition...>
  local desc="$1"; shift
  if "$@"; then ok "$desc"; else not_ok "$desc"; fi
}

contains() { [[ "$out" == *"$1"* ]]; }

reset() {
  : > "$STUB_LOG"
  rm -rf "$TV_BACKUPS"
  unset STUB_CONNECT STUB_STATE STUB_SERIAL STUB_HOME STUB_FIRMWARE STUB_PACKAGES
  cat > "$TVS_CONF" <<'EOF'
# name  ip
living-room  192.168.1.50
EOF
}

# --- connect ---

reset
export STUB_CONNECT=ok STUB_STATE=device
run "$root/bin/connect"
expect "connect succeeds when the TV accepts" test "$code" -eq 0
expect "connect uses the IP from config on port 5555" \
  grep -qx "connect 192.168.1.50:5555" "$STUB_LOG"
expect "connect says which TV it connected to" \
  contains "Connected to living-room (192.168.1.50:5555)"

reset
cat >> "$TVS_CONF" <<'EOF'
bedroom  192.168.1.51
EOF
export STUB_CONNECT=ok STUB_STATE=device STUB_SERIAL=192.168.1.51:5555
run "$root/bin/connect" bedroom
expect "connect can target a TV by name" grep -qx "connect 192.168.1.51:5555" "$STUB_LOG"

reset
run "$root/bin/connect"
expect "connect fails when the TV refuses" test "$code" -ne 0
expect "connect points at network debugging when it can't reach the TV" \
  contains "USB debugging"

reset
export STUB_CONNECT=hang ADB_CONNECT_TIMEOUT=1
start=$SECONDS
run "$root/bin/connect"
expect "connect gives up when the TV doesn't answer" test "$code" -ne 0
expect "connect doesn't wait forever" test $((SECONDS - start)) -lt 5
expect "connect says it timed out" contains "didn't answer within 1s"
unset ADB_CONNECT_TIMEOUT

reset
export STUB_CONNECT=ok STUB_STATE=unauthorized
run "$root/bin/connect"
expect "connect fails while the TV hasn't authorized this Mac" test "$code" -ne 0
expect "connect tells you to accept the prompt on the TV" \
  contains "Accept the USB debugging prompt on the TV"

# --- check ---

reset
run "$root/bin/check"
expect "check fails when no TV is connected" test "$code" -ne 0
expect "check says no TV is connected and how to fix it" \
  contains "No TV connected at 192.168.1.50:5555"

reset
export STUB_STATE=unauthorized
run "$root/bin/check"
expect "check fails when the TV hasn't authorized this Mac" test "$code" -ne 0
expect "check tells you to accept the prompt on the TV" \
  contains "Accept the USB debugging prompt on the TV"

reset
export STUB_STATE=device
run "$root/bin/check"
expect "check succeeds when a TV is connected" test "$code" -eq 0
expect "check prints the model" contains "Model:            Smart TV"
expect "check prints the Android version" contains "Android version:  11"
expect "check prints TCL's firmware version" contains "Firmware:         V8-R51MT05-LF1V620"
expect "check prints the Android build" contains "Android build:    RP1A.200622.001 release-keys"
expect "check prints the security patch" contains "Security patch:   2024-03-05"
expect "check prints the Home activity" \
  contains "Home activity:    com.google.android.tvlauncher/.MainActivity"
expect "check talks to the TV from config" \
  grep -q -- "-s 192.168.1.50:5555 shell getprop ro.product.model" "$STUB_LOG"

reset
cat >> "$TVS_CONF" <<'EOF'
bedroom  192.168.1.51
EOF
export STUB_STATE=device STUB_SERIAL=192.168.1.51:5555
run "$root/bin/check" bedroom
expect "check can target a TV by name" \
  grep -q -- "-s 192.168.1.51:5555 shell" "$STUB_LOG"

reset
export STUB_STATE=device STUB_FIRMWARE=""
run "$root/bin/check"
expect "check says when the TV has no TCL firmware version" \
  contains "Firmware:         unknown"

reset
run "$root/bin/check" kitchen
expect "check fails for an unknown TV name" contains "no TV named 'kitchen'"

reset
rm "$TVS_CONF"
run "$root/bin/check"
expect "check explains a missing config" contains "Copy tvs.conf.example to tvs.conf"

# --- backup ---

# packages <state pkg>...: gives the stub TV these packages
packages() {
  export STUB_PACKAGES="$tmp/packages"
  printf '%s\n' "$@" > "$STUB_PACKAGES"
}

# only_snapshot: prints the one snapshot file for living-room
only_snapshot() { ls "$TV_BACKUPS"/living-room/*.txt; }

reset
packages "enabled com.tcl.ads" "disabled com.tcl.promo" \
  "uninstalled com.tcl.demo" "enabled com.android.tv.settings"
export STUB_STATE=device
run "$root/bin/backup"
expect "backup succeeds" test "$code" -eq 0
expect "backup writes one timestamped snapshot for the TV" \
  bash -c "ls '$TV_BACKUPS'/living-room | grep -qxE '[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{6}\.txt'"
snap="$(only_snapshot)"
expect "the snapshot records each package's state, sorted" \
  diff <(grep -v '^#' "$snap") <(printf '%s\n' "enabled com.android.tv.settings" \
    "enabled com.tcl.ads" "uninstalled com.tcl.demo" "disabled com.tcl.promo")
expect "the snapshot names the TV" grep -qx "# tv: living-room" "$snap"
expect "the snapshot records the firmware" grep -qx "# firmware: V8-R51MT05-LF1V620" "$snap"
expect "backup summarises what it saved" \
  contains "Saved 4 packages (2 enabled, 1 disabled, 1 uninstalled)"

reset
run "$root/bin/backup"
expect "backup fails when no TV is connected" test "$code" -ne 0
expect "backup writes nothing when no TV is connected" test ! -e "$TV_BACKUPS"

# --- restore ---

reset
run "$root/bin/restore"
expect "restore fails when there's no snapshot" test "$code" -ne 0
expect "restore says to run backup first" contains "Run bin/backup first"

reset
export STUB_STATE=device
packages "enabled com.tcl.ads" "enabled com.tcl.demo" "disabled com.tcl.promo" \
  "enabled com.tcl.gone" "enabled com.android.tv.settings"
run "$root/bin/backup"
# What happens to the TV after the backup:
packages "disabled com.tcl.ads" "uninstalled com.tcl.demo" "disabled com.tcl.promo" \
  "enabled com.android.tv.settings" "disabled com.tcl.new"
: > "$STUB_LOG"
run "$root/bin/restore"
expect "restore succeeds" test "$code" -eq 0
expect "restore re-enables a package disabled since the snapshot" \
  grep -qx "enabled com.tcl.ads" "$STUB_PACKAGES"
expect "restore reinstalls a package uninstalled since the snapshot" \
  grep -qx "enabled com.tcl.demo" "$STUB_PACKAGES"
expect "restore uses install-existing for an uninstalled package" \
  grep -q "cmd package install-existing --user 0 com.tcl.demo" "$STUB_LOG"
expect "restore leaves a package that was disabled in the snapshot" \
  grep -qx "disabled com.tcl.promo" "$STUB_PACKAGES"
expect "restore leaves a package that isn't in the snapshot" \
  grep -qx "disabled com.tcl.new" "$STUB_PACKAGES"
expect "restore never disables anything" bash -c "! grep -q disable '$STUB_LOG'"
expect "restore says what it re-enabled" contains "Re-enabled 2 package(s)"
expect "restore skips a package that's gone from the TV" \
  contains "skip    com.tcl.gone (no longer on the TV)"

before="$(cat "$STUB_PACKAGES")"
: > "$STUB_LOG"
run "$root/bin/restore"
expect "restore succeeds when run twice" test "$code" -eq 0
expect "restore changes nothing the second time" test "$before" == "$(cat "$STUB_PACKAGES")"
expect "restore runs no enable commands the second time" \
  bash -c "! grep -qE 'pm enable|install-existing' '$STUB_LOG'"
expect "restore says there's nothing to do" contains "Nothing to restore"

reset
export STUB_STATE=device
packages "enabled com.tcl.ads"
run "$root/bin/backup"
first="$(only_snapshot)"
sleep 1
packages "disabled com.tcl.ads"
run "$root/bin/backup"
run "$root/bin/restore"
expect "restore uses the most recent snapshot by default" \
  contains "Nothing to restore"
run "$root/bin/restore" "$first"
expect "restore takes a snapshot file" test "$code" -eq 0
expect "restore applies the snapshot it was given" \
  grep -qx "enabled com.tcl.ads" "$STUB_PACKAGES"

reset
echo "bedroom  192.168.1.51" >> "$TVS_CONF"
export STUB_STATE=device STUB_SERIAL=192.168.1.51:5555
packages "enabled com.tcl.ads"
run "$root/bin/backup" bedroom
expect "backup can target a TV by name" test -n "$(ls "$TV_BACKUPS"/bedroom/*.txt)"
packages "disabled com.tcl.ads"
: > "$STUB_LOG"
run "$root/bin/restore" bedroom
expect "restore can target a TV by name" \
  grep -q -- "-s 192.168.1.51:5555 shell pm enable --user 0 com.tcl.ads" "$STUB_LOG"

echo
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]

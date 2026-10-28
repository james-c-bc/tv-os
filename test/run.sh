#!/usr/bin/env bash
# Runs the script tests against a stub adb. No TV needed.
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

export PATH="$root/test/stub:$PATH"
export TVS_CONF="$tmp/tvs.conf"
export STUB_LOG="$tmp/adb.log"

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
  unset STUB_CONNECT STUB_STATE STUB_SERIAL STUB_HOME
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
  contains "Network debugging"

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
expect "check prints the firmware build" contains "Build:            V8-R51MT05-LF1V620.021315"
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
run "$root/bin/check" kitchen
expect "check fails for an unknown TV name" contains "no TV named 'kitchen'"

reset
rm "$TVS_CONF"
run "$root/bin/check"
expect "check explains a missing config" contains "Copy tvs.conf.example to tvs.conf"

echo
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]

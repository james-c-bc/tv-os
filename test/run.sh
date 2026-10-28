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
export TV_LISTS="$tmp/lists"
export TV_APKS="$tmp/apks"
export HOME_APP_DIR="$tmp/home-app"
export HOME_APP_KEYSTORE="$tmp/config/home.keystore"
export JAVA_HOME="$root/test/stub/java"
export ANDROID_HOME="$tmp/sdk"
mkdir -p "$ANDROID_HOME/platforms"
export LAUNCHER_SETTLE=0

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
  rm -rf "$TV_BACKUPS" "$TV_LISTS" "$TV_APKS" \
    "$HOME_APP_DIR" "$(dirname "$HOME_APP_KEYSTORE")"
  mkdir -p "$HOME_APP_DIR"
  cp "$root/test/stub/home/gradlew" "$HOME_APP_DIR/"
  unset STUB_CONNECT STUB_STATE STUB_SERIAL STUB_FIRMWARE STUB_FOREGROUND STUB_DISABLE_FAIL
  unset STUB_INSTALL_FAIL STUB_BUILD_FAIL
  cat > "$TVS_CONF" <<'EOF'
# name  ip
living-room  192.168.1.50
EOF
  packages "enabled com.android.tv.settings"
  lists
}

# packages <state pkg>...: gives the stub TV these packages
packages() {
  export STUB_PACKAGES="$tmp/packages"
  printf '%s\n' "$@" > "$STUB_PACKAGES"
}

# lists <disable lines> -- <never-touch lines>: gives debloat and check these
# lists
lists() {
  local file="$TV_LISTS/disable.txt" line
  mkdir -p "$TV_LISTS"
  : > "$TV_LISTS/disable.txt"
  : > "$TV_LISTS/never-touch.txt"
  for line in "$@"; do
    if [[ "$line" == -- ]]; then
      file="$TV_LISTS/never-touch.txt"
      continue
    fi
    echo "$line" >> "$file"
  done
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
  contains "Home activity:    com.android.tv.settings/.FallbackHome"
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
export STUB_STATE=device
packages "disabled com.tcl.ads" "enabled com.tcl.promo" "uninstalled com.tcl.demo" \
  "enabled com.android.tv.settings" "enabled com.google.android.apps.tv.launcherx"
lists "com.tcl.ads  # ads" "com.tcl.promo  # promo" "com.tcl.demo  # demo" \
  "com.tcl.gone  # gone" -- "com.android.tv.settings  # Settings"
run "$root/bin/check"
expect "check counts how much of the disable list is disabled" \
  contains "Disable list:     1 of 4 disabled"
expect "check shows a disabled package on the list" contains "  disabled     com.tcl.ads"
expect "check shows an enabled package on the list" contains "  enabled      com.tcl.promo"
expect "check shows an uninstalled package on the list" contains "  uninstalled  com.tcl.demo"
expect "check shows a listed package that isn't on the TV" contains "  not on TV    com.tcl.gone"
expect "check doesn't list packages off the disable list" \
  bash -c "! grep -q '^  .*com.android.tv.settings' <<< \"\$1\"" _ "$out"

reset
export STUB_STATE=device
packages "enabled com.google.android.apps.tv.launcherx" \
  "enabled com.google.android.tungsten.setupwraith" "enabled io.github.james_c_bc.home"
run "$root/bin/check"
expect "check prints launcherx as Home while it's enabled" \
  contains "Home activity:    com.google.android.apps.tv.launcherx/.home.HomeActivity"
expect "check says launcherx is enabled" contains "launcherx:        enabled"
expect "check says setupwraith is enabled" contains "setupwraith:      enabled"

reset
export STUB_STATE=device
packages "disabled com.google.android.apps.tv.launcherx" \
  "disabled com.google.android.tungsten.setupwraith" "enabled io.github.james_c_bc.home"
run "$root/bin/check"
expect "check prints our app as Home once the stock Homes are off" \
  contains "Home activity:    io.github.james_c_bc.home/.HomeActivity"
expect "check says launcherx is disabled" contains "launcherx:        disabled"
expect "check says setupwraith is disabled" contains "setupwraith:      disabled"

reset
export STUB_STATE=device
run "$root/bin/check"
expect "check says when launcherx isn't on the TV" contains "launcherx:        not on TV"

reset
run "$root/bin/check" kitchen
expect "check fails for an unknown TV name" contains "no TV named 'kitchen'"

reset
rm "$TVS_CONF"
run "$root/bin/check"
expect "check explains a missing config" contains "Copy tvs.conf.example to tvs.conf"

# --- backup ---

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

# --- debloat ---

reset
export STUB_STATE=device
packages "enabled com.tcl.ads" "enabled com.android.tv.settings"
lists "# ads" "com.tcl.ads  # TCL ad service" -- "com.android.tv.settings  # Settings"
run "$root/bin/debloat"
expect "debloat succeeds" test "$code" -eq 0
expect "debloat disables an enabled package on the disable list" \
  grep -qx "disabled com.tcl.ads" "$STUB_PACKAGES"
expect "debloat disables with pm disable-user for user 0" \
  grep -q -- "-s 192.168.1.50:5555 shell pm disable-user --user 0 com.tcl.ads" "$STUB_LOG"
expect "debloat leaves packages that aren't on the list" \
  grep -qx "enabled com.android.tv.settings" "$STUB_PACKAGES"
snap="$(only_snapshot)"
expect "debloat backs up the TV first" grep -qx "enabled com.tcl.ads" "$snap"
expect "debloat says where the backup went" contains "backups/living-room/"
expect "debloat says what it disabled" contains "disabled com.tcl.ads"

before="$(cat "$STUB_PACKAGES")"
: > "$STUB_LOG"
run "$root/bin/debloat"
expect "debloat succeeds when run twice" test "$code" -eq 0
expect "debloat changes nothing the second time" test "$before" == "$(cat "$STUB_PACKAGES")"
expect "debloat runs no disable commands the second time" \
  bash -c "! grep -q disable-user '$STUB_LOG'"
expect "debloat takes no new backup when there's nothing to do" \
  test "$(ls "$TV_BACKUPS"/living-room | wc -l)" -eq 1
expect "debloat says there's nothing to do" contains "Nothing to disable"

reset
export STUB_STATE=device
packages "disabled com.tcl.promo" "uninstalled com.tcl.demo" "enabled com.android.tv.settings"
lists "com.tcl.promo  # promo" "com.tcl.demo  # demo" "com.tcl.gone  # gone" -- \
  "com.android.tv.settings  # Settings"
run "$root/bin/debloat"
expect "debloat succeeds when listed packages are already off or missing" test "$code" -eq 0
expect "debloat leaves an uninstalled package alone" \
  bash -c "! grep -q 'disable-user --user 0 com.tcl.demo' '$STUB_LOG'"
expect "debloat skips a package that isn't on the TV" \
  contains "skip     com.tcl.gone (not on the TV)"

reset
export STUB_STATE=device
packages "enabled com.tcl.ads" "enabled com.tcl.tvinput"
lists "com.tcl.ads  # ads" "com.tcl.tvinput  # oops" -- "com.tcl.tvinput  # HDMI inputs"
run "$root/bin/debloat"
expect "debloat refuses a package on the never-touch list" test "$code" -ne 0
expect "debloat names the never-touch package it refused" \
  contains "com.tcl.tvinput is on the never-touch list"
expect "debloat changes nothing when it refuses" \
  bash -c "! grep -q disable-user '$STUB_LOG'"
expect "debloat takes no backup when it refuses" test ! -e "$TV_BACKUPS"

# --- launcher ---

LAUNCHERX=com.google.android.apps.tv.launcherx
SETUPWRAITH=com.google.android.tungsten.setupwraith
OUR_HOME=io.github.james_c_bc.home

# tv_with_home: a connected RT51M TV with the stock Homes and our Home app
tv_with_home() {
  export STUB_STATE=device
  packages "enabled $LAUNCHERX" "enabled $SETUPWRAITH" \
    "enabled $OUR_HOME" "enabled com.android.tv.settings"
}

reset
export STUB_STATE=device
packages "enabled $LAUNCHERX" "enabled $SETUPWRAITH" "enabled com.android.tv.settings"
run "$root/bin/launcher"
expect "launcher fails when our Home app isn't installed" test "$code" -ne 0
expect "launcher says to run bin/home" contains "Run bin/home first"
expect "launcher disables nothing without our Home app" \
  bash -c "! grep -q disable-user '$STUB_LOG'"
expect "launcher takes no backup without our Home app" test ! -e "$TV_BACKUPS"

reset
tv_with_home
set_state_of() { sed -i '' "s/^[a-z]* $2\$/$1 $2/" "$STUB_PACKAGES"; }
set_state_of disabled "$OUR_HOME"
run "$root/bin/launcher"
expect "launcher fails when our Home app is disabled" contains "Run bin/home first"

reset
tv_with_home
run "$root/bin/launcher"
expect "launcher succeeds" test "$code" -eq 0
expect "launcher disables launcherx" grep -qx "disabled $LAUNCHERX" "$STUB_PACKAGES"
expect "launcher disables setupwraith" grep -qx "disabled $SETUPWRAITH" "$STUB_PACKAGES"
expect "launcher disables with pm disable-user for user 0" \
  grep -q "shell pm disable-user --user 0 $LAUNCHERX" "$STUB_LOG"
expect "launcher leaves our Home app enabled" grep -qx "enabled $OUR_HOME" "$STUB_PACKAGES"
snap="$(only_snapshot)"
expect "launcher backs up the TV before disabling" grep -qx "enabled $LAUNCHERX" "$snap"
expect "launcher presses Home after disabling" \
  bash -c "grep -n '' '$STUB_LOG' | grep -E 'KEYCODE_HOME|disable-user' | tail -n 1 | grep -q KEYCODE_HOME"
expect "launcher says Home lands on our app" \
  contains "Home button lands on our Home app ($OUR_HOME/.HomeActivity)"
expect "launcher doesn't use set-home-activity" \
  bash -c "! grep -q set-home-activity '$STUB_LOG'"

: > "$STUB_LOG"
run "$root/bin/launcher"
expect "launcher succeeds when run twice" test "$code" -eq 0
expect "launcher disables nothing the second time" \
  bash -c "! grep -q disable-user '$STUB_LOG'"
expect "launcher takes no second backup" test "$(ls "$TV_BACKUPS/living-room" | wc -l)" -eq 1
expect "launcher says there's nothing to disable" contains "Nothing to disable"
expect "launcher still checks Home the second time" grep -q KEYCODE_HOME "$STUB_LOG"

: > "$STUB_LOG"
run "$root/bin/restore"
expect "restore after launcher succeeds" test "$code" -eq 0
expect "restore brings launcherx back" grep -qx "enabled $LAUNCHERX" "$STUB_PACKAGES"
expect "restore brings setupwraith back" grep -qx "enabled $SETUPWRAITH" "$STUB_PACKAGES"
run "$root/bin/check"
expect "restore puts Home back on launcherx" \
  contains "Home activity:    $LAUNCHERX/.home.HomeActivity"

# A firmware update re-enables launcherx; launcher disables just that.
reset
tv_with_home
set_state_of disabled "$SETUPWRAITH"
run "$root/bin/launcher"
expect "launcher re-disables launcherx after a firmware update" \
  grep -qx "disabled $LAUNCHERX" "$STUB_PACKAGES"
expect "launcher skips setupwraith when it's already off" \
  contains "skip     $SETUPWRAITH (already disabled)"

# Rollback: Home doesn't land on our app once the stock Homes are off.
reset
tv_with_home
export STUB_FOREGROUND=com.android.tv.settings/.FallbackHome
run "$root/bin/launcher"
expect "launcher fails when Home doesn't land on our app" test "$code" -ne 0
expect "launcher says what Home opened instead" \
  contains "Home opened com.android.tv.settings/.FallbackHome instead of our Home app"
expect "launcher re-enables launcherx when Home misses our app" \
  grep -qx "enabled $LAUNCHERX" "$STUB_PACKAGES"
expect "launcher re-enables setupwraith when Home misses our app" \
  grep -qx "enabled $SETUPWRAITH" "$STUB_PACKAGES"
expect "launcher re-enables with pm enable for user 0" \
  grep -q "shell pm enable --user 0 $LAUNCHERX" "$STUB_LOG"

# Rollback also covers stock Homes that were already off before this run.
reset
tv_with_home
set_state_of disabled "$LAUNCHERX"
set_state_of disabled "$SETUPWRAITH"
export STUB_FOREGROUND=com.android.tv.settings/.FallbackHome
run "$root/bin/launcher"
expect "launcher fails when Home misses our app with nothing to disable" test "$code" -ne 0
expect "launcher re-enables the stock Homes even when it disabled nothing" \
  bash -c "grep -qx 'enabled $LAUNCHERX' '$STUB_PACKAGES' && grep -qx 'enabled $SETUPWRAITH' '$STUB_PACKAGES'"

# Rollback: a disable fails partway.
reset
tv_with_home
export STUB_DISABLE_FAIL="$SETUPWRAITH"
run "$root/bin/launcher"
expect "launcher fails when a disable fails" test "$code" -ne 0
expect "launcher says which disable failed" \
  contains "'pm disable-user --user 0 $SETUPWRAITH' failed"
expect "launcher re-enables launcherx when a later disable fails" \
  grep -qx "enabled $LAUNCHERX" "$STUB_PACKAGES"

# --- sideload ---

reset
run "$root/bin/sideload"
expect "sideload fails when no TV is connected" test "$code" -ne 0
expect "sideload says no TV is connected" contains "No TV connected at 192.168.1.50:5555"

reset
export STUB_STATE=device
run "$root/bin/sideload"
expect "sideload with no apks folder succeeds" test "$code" -eq 0
expect "sideload with no apks folder says there's nothing to install" \
  contains "No APKs to install"

mkdir -p "$TV_APKS"
run "$root/bin/sideload"
expect "sideload with an empty apks folder succeeds" test "$code" -eq 0
expect "sideload with an empty apks folder says there's nothing to install" \
  contains "No APKs to install"
expect "sideload with an empty apks folder installs nothing" \
  bash -c "! grep -q install '$STUB_LOG'"

touch "$TV_APKS/Smart Tube.apk" "$TV_APKS/kodi.APK" "$TV_APKS/notes.txt"
run "$root/bin/sideload"
expect "sideload succeeds when every APK installs" test "$code" -eq 0
expect "sideload installs each APK with install -r" \
  grep -qx -- "-s 192.168.1.50:5555 install -r $TV_APKS/Smart Tube.apk" "$STUB_LOG"
expect "sideload installs .APK files too" \
  grep -qx -- "-s 192.168.1.50:5555 install -r $TV_APKS/kodi.APK" "$STUB_LOG"
expect "sideload ignores files that aren't APKs" bash -c "! grep -q notes.txt '$STUB_LOG'"
expect "sideload reports each APK" contains "ok     Smart Tube.apk"
expect "sideload says how many it installed" contains "Installed 2 APK(s)"

run "$root/bin/sideload"
expect "sideload again updates without errors" test "$code" -eq 0

export STUB_INSTALL_FAIL=kodi
run "$root/bin/sideload"
expect "sideload fails when an APK fails to install" test "$code" -ne 0
expect "sideload says which APK failed and why" \
  contains "FAILED kodi.APK: adb: failed to install"
expect "sideload still installs the other APKs" contains "ok     Smart Tube.apk"
expect "sideload counts the failures" contains "1 of 2 APK(s) failed to install"

# --- home ---

home_apk="$HOME_APP_DIR/build/outputs/apk/release/home-release.apk"

reset
run "$root/bin/home"
expect "home fails when no TV is connected" test "$code" -ne 0
expect "home says no TV is connected" contains "No TV connected at 192.168.1.50:5555"
expect "home builds nothing when no TV is connected" bash -c "! grep -q gradlew '$STUB_LOG'"

reset
export STUB_STATE=device
run "$root/bin/home"
expect "home succeeds" test "$code" -eq 0
expect "home creates a signing key on first run" test -f "$HOME_APP_KEYSTORE"
expect "home makes the key with keytool" \
  grep -q "keytool -genkeypair -noprompt -keystore $HOME_APP_KEYSTORE" "$STUB_LOG"
expect "home keeps the key's password next to it" test -s "$HOME_APP_KEYSTORE.password"
expect "home keeps the key private" test "$(stat -f %Lp "$HOME_APP_KEYSTORE")" == 600
expect "home keeps the password private" \
  test "$(stat -f %Lp "$HOME_APP_KEYSTORE.password")" == 600
expect "home builds a release APK" grep -q "gradlew .*assembleRelease" "$STUB_LOG"
expect "home signs with the key and its password" \
  grep -q "keystore=$HOME_APP_KEYSTORE password=$(cat "$HOME_APP_KEYSTORE.password")$" "$STUB_LOG"
expect "home installs the APK with install -r" \
  grep -qx -- "-s 192.168.1.50:5555 install -r $home_apk" "$STUB_LOG"
expect "home says it installed" contains "Installed Home"

password="$(cat "$HOME_APP_KEYSTORE.password")"
: > "$STUB_LOG"
run "$root/bin/home"
expect "home again updates without errors" test "$code" -eq 0
expect "home reuses the signing key" bash -c "! grep -q keytool '$STUB_LOG'"
expect "home reuses the key's password" test "$password" == "$(cat "$HOME_APP_KEYSTORE.password")"
expect "home installs again" grep -q "install -r $home_apk" "$STUB_LOG"

export STUB_BUILD_FAIL=1
: > "$STUB_LOG"
rm -f "$home_apk"
run "$root/bin/home"
expect "home fails when the build fails" test "$code" -ne 0
expect "home shows the build error" contains "Unresolved reference: oops"
expect "home installs nothing when the build fails" bash -c "! grep -q install '$STUB_LOG'"
unset STUB_BUILD_FAIL

export STUB_INSTALL_FAIL=home
run "$root/bin/home"
expect "home fails when the TV has Home signed with another key" test "$code" -ne 0
expect "home says how to uninstall the old Home" \
  contains "adb -s 192.168.1.50:5555 uninstall io.github.james_c_bc.home"

reset
export STUB_STATE=device
mkdir -p "$(dirname "$HOME_APP_KEYSTORE")"
echo keystore > "$HOME_APP_KEYSTORE"
run "$root/bin/home"
expect "home fails when the key's password is missing" test "$code" -ne 0
expect "home says where the password should be" contains "no password for $HOME_APP_KEYSTORE"

echo
echo "$pass passed, $fail failed"
[[ "$fail" -eq 0 ]]

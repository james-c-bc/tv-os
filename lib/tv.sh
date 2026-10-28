# Shared helpers for the bin/ scripts. Source, don't run.
#
# TVs live in tvs.conf at the repo root (git-ignored), one per line:
#   <name> <ip>
# Blank lines and lines starting with # are ignored. Set TVS_CONF to use
# a different file.

TV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TVS_CONF="${TVS_CONF:-$TV_ROOT/tvs.conf}"
ADB_PORT=5555
ADB_CONNECT_TIMEOUT="${ADB_CONNECT_TIMEOUT:-10}"
TV_BACKUPS="${TV_BACKUPS:-$TV_ROOT/backups}"
TV_LISTS="${TV_LISTS:-$TV_ROOT/lists}"

die() {
  echo "error: $*" >&2
  exit 1
}

require_adb() {
  command -v adb > /dev/null \
    || die "adb not found. Install it with: brew install --cask android-platform-tools"
}

# with_timeout <seconds> <cmd...>: runs cmd, killing it after <seconds>.
# Returns 124 on timeout, like coreutils timeout (which macOS lacks).
with_timeout() {
  local secs="$1" pid watcher rc flag
  shift
  flag="$(mktemp)"
  "$@" &
  pid=$!
  # The watcher's output goes to /dev/null so a caller's $(...) doesn't
  # wait on it.
  ( sleep "$secs" && rm -f "$flag" && kill "$pid" ) > /dev/null 2>&1 &
  watcher=$!
  wait "$pid"
  rc=$?
  kill "$watcher" 2> /dev/null
  if [[ ! -e "$flag" ]]; then
    return 124
  fi
  rm -f "$flag"
  return "$rc"
}

# tv_lookup [name]: prints "<name> <ip>" for the named TV, or for the first
# TV if no name is given.
tv_lookup() {
  local want="${1:-}" name ip
  [[ -f "$TVS_CONF" ]] \
    || die "no TV config at $TVS_CONF. Copy tvs.conf.example to tvs.conf and set your TV's IP."
  while read -r name ip _; do
    [[ -z "$name" || "$name" == \#* ]] && continue
    if [[ -z "$want" || "$name" == "$want" ]]; then
      [[ -n "$ip" ]] || die "TV '$name' in $TVS_CONF has no IP"
      echo "$name $ip"
      return
    fi
  done < "$TVS_CONF"
  if [[ -n "$want" ]]; then
    die "no TV named '$want' in $TVS_CONF"
  fi
  die "no TVs listed in $TVS_CONF"
}

# tv_serial [name]: prints the adb serial (ip:port) for a TV.
tv_serial() {
  local entry
  entry="$(tv_lookup "$@")" || exit 1
  echo "${entry#* }:$ADB_PORT"
}

# tv_state <serial>: prints adb's state for a serial (device, unauthorized,
# offline...), or nothing if adb doesn't know it.
tv_state() {
  adb devices | awk -v s="$1" '$1 == s { print $2 }'
}

# require_connected <serial>: exits with a clear message unless the TV is
# connected and has authorized this machine.
require_connected() {
  case "$(tv_state "$1")" in
    device) ;;
    unauthorized)
      die "TV at $1 hasn't authorized this Mac. Accept the USB debugging prompt on the TV, then try again." ;;
    "")
      die "No TV connected at $1. Run bin/connect first." ;;
    *)
      die "TV at $1 is $(tv_state "$1"). Run bin/connect to reconnect." ;;
  esac
}

# tv_packages <serial>: prints "<state> <package>" for every package on the
# TV, sorted by package. State is one of:
#   enabled      installed for user 0 and enabled
#   disabled     installed for user 0 but disabled (pm disable-user)
#   uninstalled  still on the system but removed for user 0 (pm uninstall --user 0)
tv_packages() {
  local serial="$1" packages
  list() { # list <tag> [pm flags]: prints "<tag> <package>" per package
    local tag="$1"
    shift
    adb -s "$serial" shell pm list packages "$@" --user 0 \
      | tr -d '\r' | sed -n "s/^package:/$tag /p"
  }
  packages="$(
    { list all -u && list installed && list disabled -d; } | awk '
      $1 == "all" { all[$2] = 1 }
      $1 == "installed" { inst[$2] = 1 }
      $1 == "disabled" { dis[$2] = 1 }
      END {
        for (p in all) {
          if (!(p in inst)) print "uninstalled", p
          else if (p in dis) print "disabled", p
          else print "enabled", p
        }
      }' | sort -k 2
  )" || return 1
  [[ -n "$packages" ]] || die "the TV at $serial listed no packages"
  echo "$packages"
}

# read_list <name>: prints the packages in lists/<name>.txt, one per line.
# Each line is "<package>  # what it is". Blank lines and comments are
# ignored.
read_list() {
  local file="$TV_LISTS/$1.txt"
  [[ -f "$file" ]] || die "no package list at $file"
  sed 's/#.*//' "$file" | awk 'NF { print $1 }'
}

PROJECTIVY=com.spocky.projengmenu
# Android TV 11 firmware ships one stock launcher or the other; RT51M
# firmware has launcherx.
STOCK_LAUNCHERS=(com.google.android.apps.tv.launcherx com.google.android.tvlauncher)
STOCK_RECOMMENDATIONS=com.google.android.tvrecommendations

# home_activity <serial>: prints the TV's default Home activity.
home_activity() {
  adb -s "$1" shell cmd package resolve-activity --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | tail -n 1
}

# home_activity_of <serial> <package>: prints the package's Home activity, or
# nothing if it has none (or is disabled or not installed).
home_activity_of() {
  adb -s "$1" shell cmd package query-activities --brief \
    -a android.intent.action.MAIN -c android.intent.category.HOME \
    | tr -d '\r' | awk -v p="$2/" '
        { sub(/^[ \t]+/, "") }
        index($0, p) == 1 && !found { found = 1; print }'
}

# set_home <serial> <component>: makes component the default Home activity.
set_home() {
  local result
  result="$(adb -s "$1" shell cmd package set-home-activity --user 0 "$2" 2>&1 | tr -d '\r')"
  [[ "$result" == *Success* ]] || die "couldn't make $2 the default Home: $result"
}

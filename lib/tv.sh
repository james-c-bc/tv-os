# Shared helpers for the bin/ scripts. Source, don't run.
#
# TVs live in tvs.conf at the repo root (git-ignored), one per line:
#   <name> <ip>
# Blank lines and lines starting with # are ignored. Set TVS_CONF to use
# a different file.

TV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TVS_CONF="${TVS_CONF:-$TV_ROOT/tvs.conf}"
ADB_PORT=5555

die() {
  echo "error: $*" >&2
  exit 1
}

require_adb() {
  command -v adb > /dev/null \
    || die "adb not found. Install it with: brew install --cask android-platform-tools"
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

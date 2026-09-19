#!/usr/bin/env bash
# Drive the app on a connected phone: screenshots, taps, typing, restarts.
#
#   ./scripts/device.sh shot [name]          screenshot into captures/
#   ./scripts/device.sh tap <x> <y> [name]   tap, settle, screenshot
#   ./scripts/device.sh swipe <x1> <y1> <x2> <y2> [ms]
#   ./scripts/device.sh type <text>          type into the focused field
#   ./scripts/device.sh key <name>           back | home | enter | del | tab | wake
#   ./scripts/device.sh link <uri> [name]    open a porygonlist:// link, settle, shot
#   ./scripts/device.sh restart [name]       force-stop, launch, settle, shot
#   ./scripts/device.sh stop                 force-stop and leave it stopped
#   ./scripts/device.sh size                 screen size, for picking coordinates
#
# Why this exists: checking a UI change means install, launch, tap through to
# the screen, look. Written out longhand that is a pile of adb invocations with
# a sourced environment in front of each one, which is both tedious and, for an
# agent working here, a permission prompt every single time. One script is one
# prompt, and the settle timing below stops being something you have to
# remember.
#
# The settle: Compose animates transitions, and screencap is fast enough to
# catch the frame before the animation has finished. A capture taken the instant
# a tap returns shows the *previous* screen often enough to waste a round trip,
# so every capture here waits first. Override with SETTLE=<seconds> when an
# animation is slower than the default:
#
#   SETTLE=1.5 ./scripts/device.sh tap 125 618 ticked
#
# Screenshots land in captures/, which is gitignored. Nothing here is written to
# be kept; the point is to look at it and move on.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly PACKAGE="io.github.molleware.porygonlist"
readonly ACTIVITY="$PACKAGE/.MainActivity"
readonly CAPTURES="$REPO_ROOT/captures"

# Long enough for a Compose transition, short enough not to be annoying.
SETTLE="${SETTLE:-0.6}"

require_device >/dev/null

# Names a capture after what it shows, not when it was taken, so consecutive
# shots of the same screen overwrite rather than pile up. A timestamp only helps
# if you intend to keep them, and these are not worth keeping.
capture() {
  local name="${1:-shot}"
  mkdir -p "$CAPTURES"
  local out="$CAPTURES/${name}.png"

  # exec-out, not shell + redirect: shell mangles the binary stream on some
  # devices by translating line endings, which produces a corrupt PNG.
  "$ADB" exec-out screencap -p > "$out"

  [[ -s "$out" ]] || die "Capture was empty. Is the screen on and unlocked?"
  printf '%s\n' "${out#"$REPO_ROOT"/}"
}

settle() { sleep "$SETTLE"; }

cmd="${1:-shot}"
shift || true

case "$cmd" in
  shot)
    capture "${1:-shot}"
    ;;

  tap)
    [[ $# -ge 2 ]] || die "tap needs x and y. Try: ./scripts/device.sh size"
    "$ADB" shell input tap "$1" "$2"
    settle
    capture "${3:-tap}"
    ;;

  swipe)
    [[ $# -ge 4 ]] || die "swipe needs x1 y1 x2 y2, optionally a duration in ms"
    "$ADB" shell input swipe "$1" "$2" "$3" "$4" "${5:-300}"
    settle
    capture "${6:-swipe}"
    ;;

  type)
    [[ $# -ge 1 ]] || die "type needs some text"
    # input text takes %s for a space and chokes on much else; anything with
    # quotes or punctuation is better set by the test than typed through adb.
    "$ADB" shell input text "${*// /%s}"
    settle
    capture "typed"
    ;;

  key)
    case "${1:-}" in
      back)  code=KEYCODE_BACK ;;
      home)  code=KEYCODE_HOME ;;
      enter) code=KEYCODE_ENTER ;;
      del)   code=KEYCODE_DEL ;;
      tab)   code=KEYCODE_TAB ;;
      # Worth knowing about: with the screen off, a launch never reaches a drawn
      # frame, so captures come back black and startup.sh reports no timings at
      # all. Wake first when the phone has been sitting idle.
      wake)  code=KEYCODE_WAKEUP ;;
      "")    die "key needs a name: back | home | enter | del | tab | wake" ;;
      *)     die "Unknown key '$1'. Use: back | home | enter | del | tab | wake" ;;
    esac
    "$ADB" shell input keyevent "$code"
    settle
    capture "${2:-key}"
    ;;

  link)
    [[ $# -ge 1 ]] || die "link needs a uri, e.g. 'porygonlist://pair?c=PLPAIR1....'"
    # Single-quoted for the device's shell, which would otherwise read the '?'
    # and '&' in a pairing link as its own. The code alphabet is base64url, so
    # there is never a quote in here to close them early.
    started="$("$ADB" shell am start -a android.intent.action.VIEW -d "'$1'" 2>&1)"
    # am start exits 0 even when nothing handles the intent, which is the one
    # failure that matters here: it means the manifest filter did not match.
    grep -q '^Error:' <<<"$started" && die "Nothing handled it. Is the intent-filter right?\n$started"
    settle
    settle
    capture "${2:-link}"
    ;;

  restart)
    "$ADB" shell am force-stop "$PACKAGE"
    "$ADB" shell am start -n "$ACTIVITY" >/dev/null
    # A launch is slower than a transition: first frame, then whatever the app
    # reads after it. Two settles rather than a special case.
    settle
    settle
    capture "${1:-launch}"
    ;;

  stop)
    "$ADB" shell am force-stop "$PACKAGE"
    info "Stopped $PACKAGE"
    ;;

  size)
    "$ADB" shell wm size
    "$ADB" shell wm density
    ;;

  *)
    die "Unknown command '$cmd'. Use: shot | tap | swipe | type | key | link | restart | stop | size"
    ;;
esac

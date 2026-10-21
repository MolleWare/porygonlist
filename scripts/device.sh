#!/usr/bin/env bash
# Drive the app on a connected phone: screenshots, taps, typing, restarts.
# With both phones attached, put `-s <serial>` before the command.
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
#   ./scripts/device.sh stay-awake on|off    keep the screen on while charging over USB
#   ./scripts/device.sh drag <x1> <y1> <x2> <y2> [seconds] [name]
#                                            press, move, hold at the end, let go, shot
#   ./scripts/device.sh font [scale] [name]  read the font scale, or set it, settle, shot
#
# By name rather than by coordinates — the way to drive the app, since coordinates differ between
# the two phones and move whenever a list scrolls:
#
#   ./scripts/device.sh tap-on <label> [--scroll] [--nth N] [name]
#                                            tap the element with that text or accessibility label
#   ./scripts/device.sh hold-on <label> [--scroll] [--nth N] [name]
#                                            the same, as a long press (rename, edit sheet)
#   ./scripts/device.sh wait-for <text> [seconds]
#                                            poll until some text or label contains it, else fail
#   ./scripts/device.sh read                 every visible text and label, in order
#
# A label is matched exactly first, then as a substring. --scroll pages down until it appears;
# --nth picks among several matches, top to bottom ("Share" is a tab and a button).
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

# Which phone, when more than one is attached: `-s <serial>` first, the same as adb's. Taken as an
# argument rather than only through ANDROID_SERIAL so every call starts with ./scripts/device.sh.
if [[ "${1:-}" == "-s" ]]; then
  export ANDROID_SERIAL="${2:?-s needs a serial; see adb devices -l}"
  shift 2
fi

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

readonly UI_DUMP="$CAPTURES/.ui.xml"

# The screen as a tree of elements, into captures/ like everything else here.
dump() {
  mkdir -p "$CAPTURES"
  "$ADB" shell uiautomator dump /sdcard/porygonlist-ui.xml >/dev/null 2>&1 || true
  "$ADB" exec-out cat /sdcard/porygonlist-ui.xml > "$UI_DUMP"
  [[ -s "$UI_DUMP" ]] || die "The screen could not be read. Is it on and unlocked?"
}

ui() { python3 -I "$REPO_ROOT/scripts/ui.py" "$@"; }

# Pages the content down by most of a screen, from the middle so the tab bar is never touched.
page_down() {
  local w h
  read -r w h < <(ui screen "$UI_DUMP")
  "$ADB" shell input swipe $((w / 2)) $((h * 3 / 4)) $((w / 2)) $((h / 3)) 300
  settle
}

# "x y" of the element, scrolling if asked; dies naming the label when it is not there.
# A fourth argument of "text" prefers visible text to accessibility labels (see ui.py).
locate() {
  local label="$1" scroll="$2" nth="$3" how="find" at
  [[ "${4:-}" == text ]] && how="find-text"
  for _ in 1 2 3 4 5 6; do
    dump
    if at="$(ui "$how" "$UI_DUMP" "$label" "$nth")"; then
      printf '%s\n' "$at"
      return
    fi
    [[ "$scroll" == 1 ]] || break
    page_down
  done
  die "Nothing on screen is called '$label'. See: ./scripts/device.sh read"
}

# Splits tap-on / hold-on arguments into LABEL, SCROLL, NTH and NAME.
parse_target() {
  LABEL="" SCROLL=0 NTH=1 NAME=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --scroll) SCROLL=1 ;;
      --nth)    NTH="${2:?--nth needs a number}"; shift ;;
      *)        if [[ -z "$LABEL" ]]; then LABEL="$1"; else NAME="$1"; fi ;;
    esac
    shift
  done
  [[ -n "$LABEL" ]] || die "Which element? Give its text or label, e.g. tap-on \"Save\""
}

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
    # input text takes %s for a space, and the whole string goes through the device's shell, so
    # it is single-quoted there with any quote inside it closed, escaped and reopened.
    text="${*// /%s}"
    "$ADB" shell "input text '${text//\'/\'\\\'\'}'"
    settle
    capture "typed"
    ;;

  tap-on | hold-on)
    parse_target "$@"
    # A long press goes to the row's visible text: an item's checkbox carries the same name.
    read -r x y < <(locate "$LABEL" "$SCROLL" "$NTH" "$([[ "$cmd" == hold-on ]] && echo text)")
    if [[ "$cmd" == tap-on ]]; then
      "$ADB" shell input tap "$x" "$y"
    else
      "$ADB" shell input swipe "$x" "$y" "$x" "$y" 900
    fi
    settle
    capture "${NAME:-$cmd}"
    ;;

  wait-for)
    [[ $# -ge 1 ]] || die "wait-for needs the text to wait for"
    deadline=$((SECONDS + ${2:-30}))
    start=$SECONDS
    until dump && ui has "$UI_DUMP" "$1"; do
      ((SECONDS < deadline)) || die "No '$1' after ${2:-30} s."
      sleep 2
    done
    info "'$1' after $((SECONDS - start)) s"
    ;;

  read)
    dump
    ui read "$UI_DUMP"
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

  stay-awake)
    # The screen stays on while the phone charges over USB, so a session nobody is watching is not
    # cut short by the screen timeout. Turn it off afterwards: it is the phone's own setting, and
    # Doze, background sync and battery checks all need the screen to be allowed to sleep.
    case "${1:-}" in
      on)  "$ADB" shell svc power stayon usb ;;
      off) "$ADB" shell svc power stayon false ;;
      *)   die "stay-awake needs on or off" ;;
    esac
    info "Stay awake while charging: ${1}"
    ;;

  drag)
    # A press that moves, then waits before letting go: what a reorder needs to reach the edge of the
    # screen and make the list scroll along. swipe cannot do it, because it lets go on arrival.
    # Holds [seconds] at the end (default 2). Runs in one device shell, so nothing lifts in between.
    [[ $# -ge 4 ]] || die "drag needs x1 y1 x2 y2 [seconds] [name]"
    x1=$1 y1=$2 x2=$3 y2=$4 hold=${5:-2}
    "$ADB" shell "input motionevent DOWN $x1 $y1; sleep 0.6;
      for i in 1 2 3 4 5 6 7 8 9 10; do
        input motionevent MOVE \$(( $x1 + ($x2 - $x1) * i / 10 )) \$(( $y1 + ($y2 - $y1) * i / 10 ))
      done;
      sleep $hold; input motionevent UP $x2 $y2"
    settle
    capture "${6:-drag}"
    ;;

  font)
    # The system font scale, for checking that nothing clips or runs off the edge at the largest size
    # (2 on Android 14 and later). It is the phone's own setting, so put it back afterwards: font 1.
    if [[ -z "${1:-}" ]]; then
      "$ADB" shell settings get system font_scale
    else
      "$ADB" shell settings put system font_scale "$1"
      settle
      capture "${2:-font}"
    fi
    ;;

  *)
    die "Unknown command '$cmd'. Use: shot | tap | tap-on | hold-on | swipe | type | key | link | wait-for | read | restart | stop | size | stay-awake | drag | font"
    ;;
esac

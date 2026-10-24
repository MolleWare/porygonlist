#!/usr/bin/env bash
# A laptop standing in for a second phone, for device tests when only one phone is at hand.
#
#   ./scripts/fakephone.sh run [name]     run it in the foreground (default name: Laptop)
#   ./scripts/fakephone.sh send <command> [<command>…]
#                                         one or more commands, each its own argument
#   ./scripts/fakephone.sh log [lines]    the end of what it has said
#   ./scripts/fakephone.sh wait <text> [seconds]
#                                         until a new line containing <text> appears (default 30 s)
#   ./scripts/fakephone.sh forget         delete its key and lists, to start as a new phone
#
# It runs the app's own sync code on the JVM (app/src/test/…/fakephone/FakePhone.kt), so what
# crosses the wifi is what a phone sends. `send help` lists what it understands. To pair, `send code`
# and paste the printed link into the phone's pairing screen; the phone hands its key back by itself.
#
# Its key and lists live outside the repository, under $XDG_DATA_HOME/porygonlist-fakephone. The key
# is a throwaway test identity; it never goes anywhere but this machine.
#
# FAKEPHONE=<name> runs another one beside it, with its own key and lists, for checks that need a
# third phone: FAKEPHONE=sam ./scripts/fakephone.sh run Sam, then FAKEPHONE=sam on every call to it.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

STATE_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/porygonlist-fakephone${FAKEPHONE:+-$FAKEPHONE}"
readonly STATE_DIR
readonly FIFO="$STATE_DIR/in"
readonly LOG="$STATE_DIR/out.log"

mode="${1:-}"
shift || true

case "$mode" in
  run)
    mkdir -p "$STATE_DIR"
    [[ -p "$FIFO" ]] || mkfifo "$FIFO"
    : >"$LOG"
    info "Fake phone state in $STATE_DIR; its output also goes to $LOG"
    gradlew -q :app:fakePhone --args="$STATE_DIR ${1:-Laptop}" 2>&1 | tee -a "$LOG"
    ;;

  send)
    [[ $# -gt 0 ]] || die "Nothing to send. Try: ./scripts/fakephone.sh send help"
    [[ -p "$FIFO" ]] || die "Not running. Start it with: ./scripts/fakephone.sh run"
    before="$(wc -l <"$LOG")"
    # A writer with no reader blocks for ever; a running fake phone always has the FIFO open.
    timeout 5 bash -c 'printf "%s\n" "$@" >"$0"' "$FIFO" "$@" || die "The fake phone is not reading. Is it running?"
    # Give it a moment to answer, then show what it said to these commands.
    sleep 1
    tail -n +"$((before + 1))" "$LOG"
    ;;

  log)
    tail -n "${1:-40}" "$LOG"
    ;;

  wait)
    text="${1:-}"
    [[ -n "$text" ]] || die "wait <text> [seconds]"
    before="$(wc -l <"$LOG")"
    deadline=$((SECONDS + ${2:-30}))
    while ((SECONDS < deadline)); do
      if tail -n +"$((before + 1))" "$LOG" | grep -F -- "$text"; then exit 0; fi
      sleep 0.5
    done
    die "No '$text' within ${2:-30} s"
    ;;

  forget)
    pgrep -f -- "$STATE_DIR " >/dev/null && die "Stop it first: ./scripts/fakephone.sh send quit"
    rm -rf "$STATE_DIR"
    info "Forgotten. The next run is a new phone, and the old one stays paired on the phone until unpaired there."
    ;;

  *)
    die "Use: run [name] | send <command>… | log [lines] | wait <text> [seconds] | forget"
    ;;
esac

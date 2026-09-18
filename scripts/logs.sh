#!/usr/bin/env bash
# Read the app's logs off a connected phone.
#
#   ./scripts/logs.sh            follow the app live (waits for it to start)
#   ./scripts/logs.sh crash      dump the crash buffer and exit
#   ./scripts/logs.sh all        dump everything this app has said, and exit
#   ./scripts/logs.sh clear      wipe the buffers, for a clean run
#
# The app itself logs nothing, deliberately — a grocery list has no business
# narrating what is on it. What these surface is what Android says about it:
# crashes, ANRs and the odd framework warning. That is enough for a stack trace,
# which is the thing worth having.
#
# Before pasting output anywhere public: a crash inside the sync code can carry
# an item id in its message, and an item id begins with a device id. Not a
# secret, but it identifies the phone across any other log you share.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly PACKAGE="io.github.molleware.porygonlist"

require_device

mode="${1:-follow}"

app_pid() {
  "$ADB" shell pidof -s "$PACKAGE" 2>/dev/null | tr -d '\r'
}

case "$mode" in
  clear)
    info "Clearing log buffers"
    "$ADB" logcat -b all -c
    info "Cleared. Start the app, then run: ./scripts/logs.sh"
    ;;

  crash)
    info "Crash buffer"
    # -d dumps and exits rather than following. The crash buffer survives the
    # process dying, which the main buffer's pid filter does not.
    "$ADB" logcat -b crash -d | grep -F "$PACKAGE" -A 40 || warn "Nothing for $PACKAGE in the crash buffer"
    ;;

  all)
    info "Everything mentioning $PACKAGE"
    "$ADB" logcat -b all -d | grep -F "$PACKAGE" || warn "Nothing for $PACKAGE"
    ;;

  follow)
    pid="$(app_pid)"
    if [[ -z "$pid" ]]; then
      info "Waiting for $PACKAGE to start — launch it on the phone"
      # A crash on first launch is over before a pid filter can attach, so give
      # up after a while and point at the buffer that does survive it.
      for _ in $(seq 1 60); do
        pid="$(app_pid)"
        [[ -n "$pid" ]] && break
        sleep 1
      done
    fi

    [[ -n "$pid" ]] || die "App never started. If it crashed on launch, its trace is in: ./scripts/logs.sh crash"

    info "Following pid $pid — Ctrl-C to stop"
    "$ADB" logcat --pid="$pid"
    ;;

  *)
    die "Unknown mode '$mode'. Use: follow | crash | all | clear"
    ;;
esac

#!/usr/bin/env bash
# Start a Claude Code session on this machine that you can drive from a phone.
#
#   ./scripts/remote.sh           session named "porygonlist"
#   ./scripts/remote.sh bench     session named "bench"
#
# The session runs here, with this machine's JDK, Android SDK and working tree.
# The phone is only a terminal for it: open claude.ai/code, pick the session by
# the name printed below, and type. Closing the phone does not end the session;
# closing this terminal does.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

session_name="${1:-porygonlist}"

command -v claude >/dev/null 2>&1 || die \
  "claude is not on PATH. Install Claude Code first."

# Remote Control pairs through the Anthropic account, so an expired login shows
# up on the phone as a session that never appears, with no explanation there.
claude auth status 2>/dev/null | grep -q '"loggedIn": true' || die \
  "Not logged in. Run: claude auth login"

# Fail here, in front of a keyboard, rather than on the phone several minutes
# into the first Gradle invocation of the day.
require_java
require_android_sdk

# A suspended desktop takes the session with it, and from the phone that is
# indistinguishable from the network dropping.
inhibit=()
if command -v systemd-inhibit >/dev/null 2>&1; then
  inhibit=(systemd-inhibit --what=idle:sleep --mode=block
           --why="Claude Code Remote Control session: $session_name")
else
  warn "systemd-inhibit not found; this machine may suspend and drop the session."
fi

info "Session name on the phone: $session_name"
info "JDK $REQUIRED_JDK: $JAVA_HOME"
info "Android SDK: $ANDROID_HOME"

cd "$REPO_ROOT"
exec "${inhibit[@]}" claude --remote-control "$session_name"

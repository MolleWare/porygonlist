#!/usr/bin/env bash
# Work on PorygonList inside a podman container, where an agent can be given wide rights without
# those rights reaching the rest of this machine.
#
#   ./scripts/dev.sh [claude args]      Claude Code in the container
#   ./scripts/dev.sh remote [name]      the same, driven from the phone (see scripts/remote.sh)
#   ./scripts/dev.sh shell              a shell in the container
#   ./scripts/dev.sh stop               stop the container, ending every session in it
#
# One container, porygonlist-dev, started by the first of these and shared by the rest, so every
# session talks to the same adb server and Gradle daemon. VS Code joins it the same way: with the
# Dev Containers extension set to podman, run "Dev Containers: Attach to Running Container", pick
# porygonlist-dev and open this folder there.
#
# What the container gets:
#   - this repository, read-write, at the same path as on the host: Claude Code files its memory
#     under that path, and Gradle's outputs carry it;
#   - this project's Claude memory folder and the global CLAUDE.md, and nothing else of ~/.claude;
#   - the debug signing key, read-only, so a debug or benchmark build installed from in there can
#     replace one installed from the host, instead of needing an uninstall that wipes the app's
#     data on the phone;
#   - the host's network, for wireless adb and the app's own discovery on the local network;
#   - a volume, porygonlist-dev-home, for its home: its own Claude login, its own adb key (approve
#     it once on each phone), Gradle's caches.
#
# What it does not get: the release key (this refuses to start while one is in the repository),
# the host's adb key, SSH keys or any GitHub credential. Signing and pushing stay on the host.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly CONTAINER=porygonlist-dev
readonly VOLUME=porygonlist-dev-home
readonly CONTAINERFILE="$REPO_ROOT/container/Containerfile"
# Claude Code's folder for a project is named after its path, every other character made a dash.
readonly MEMORY="$HOME/.claude/projects/${REPO_ROOT//[^A-Za-z0-9]/-}/memory"

[[ -z "${PORYGONLIST_DEV_CONTAINER:-}" ]] || die "Already inside the development container."
command -v podman >/dev/null || die "podman is not installed."

# Tagged with the Containerfile's hash, so the first start after an edit to it rebuilds the image.
image="localhost/porygonlist-dev:$(sha256sum "$CONTAINERFILE" | cut -c1-12)"

# A published app's key can never be replaced, so it must not be anywhere the container can read.
check_no_release_key() {
  local found
  found="$(find "$REPO_ROOT" -path "$REPO_ROOT/.git" -prune -o -type f \
    \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name '*.pfx' \) -print)"
  [[ -z "$found" ]] || die "A key is in the repository, where the container could read it:
$found
Move it out of this folder and point storeFile in keystore.properties at its new place."
}

start() {
  if [[ "$(podman container inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" == true ]]; then
    [[ "$(podman container inspect -f '{{.ImageName}}' "$CONTAINER")" == "$image" ]] \
      || warn "$CONTAINER runs an image older than container/Containerfile. ./scripts/dev.sh stop, then start again."
    return
  fi

  check_no_release_key
  if ! podman image exists "$image"; then
    info "Building $image"
    podman build -t "$image" -f "$CONTAINERFILE" "$REPO_ROOT/container"
  fi

  # podman creates a mount point nested in the home volume as root, and the user could then not
  # write next to it. Make those folders as the user first.
  mkdir -p "$MEMORY"
  podman run --rm --userns=keep-id -v "$VOLUME:$HOME" "$image" mkdir -p "$MEMORY" "$HOME/.android"

  local mounts=(-v "$VOLUME:$HOME" -v "$REPO_ROOT:$REPO_ROOT:z" -v "$MEMORY:$MEMORY:z")
  local file
  for file in "$HOME/.claude/CLAUDE.md" "$HOME/.android/debug.keystore"; do
    [[ -f "$file" ]] && mounts+=(-v "$file:$file:ro,z")
  done

  # Commits are made as git on the host would make them in this repository. ~/.gitconfig itself
  # stays out: it can hold credential helpers.
  local name email
  name="$(git -C "$REPO_ROOT" config user.name || true)"
  email="$(git -C "$REPO_ROOT" config user.email || true)"

  info "Starting $CONTAINER"
  podman run -d --rm --name "$CONTAINER" --init \
    --userns=keep-id --network=host \
    -e HOME="$HOME" \
    -e PATH="$HOME/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
    -e GIT_AUTHOR_NAME="$name" -e GIT_COMMITTER_NAME="$name" \
    -e GIT_AUTHOR_EMAIL="$email" -e GIT_COMMITTER_EMAIL="$email" \
    -w "$REPO_ROOT" "${mounts[@]}" \
    "$image" sleep infinity >/dev/null

  # Claude Code lives in the home volume rather than the image, so it keeps updating itself.
  podman exec "$CONTAINER" bash -c \
    'command -v claude >/dev/null || curl -fsSL https://claude.ai/install.sh | bash'
}

tty=(-i)
[[ -t 0 ]] && tty=(-it)

case "${1:-}" in
  shell)
    start
    exec podman exec "${tty[@]}" "$CONTAINER" bash
    ;;
  remote)
    shift
    start
    # The session runs in the container, but it is this machine that must not suspend.
    inhibit=()
    if command -v systemd-inhibit >/dev/null 2>&1; then
      inhibit=(systemd-inhibit --what=idle:sleep --mode=block
               --why="Claude Code Remote Control session in $CONTAINER")
    else
      warn "systemd-inhibit not found; this machine may suspend and drop the session."
    fi
    exec "${inhibit[@]}" podman exec "${tty[@]}" "$CONTAINER" ./scripts/remote.sh "$@"
    ;;
  stop)
    podman stop "$CONTAINER" >/dev/null 2>&1 || true
    info "$CONTAINER stopped"
    ;;
  *)
    start
    exec podman exec "${tty[@]}" "$CONTAINER" claude "$@"
    ;;
esac
